package com.text2sql.agent.retrieval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Join 路径规划：把一张「扁平的边列表」变成一棵「可照着写的连接树」。
 *
 * <p>对应 ROADMAP 阶段 3 第 2、3 条（求最短路径、把 join 关系作为约束写进 prompt）。
 *
 * <p><b>为什么光有边列表还不够</b>
 *
 * <p>阶段 3 第 1 步已经把「哪一列连哪一列」写进了 prompt，那解决了「猜错列名」
 * （T4-015 把 {@code region_code} 猜成 {@code region_name}）。但多表题仍然难：
 * 六张表的子图有七八条边，模型要自己在脑子里拼出遍历顺序；更麻烦的是当
 * **多条路径都能连通**时，边列表不会告诉它哪条是推荐路径。
 *
 * <p>实测的例子是 T5-001：gold 走
 * {@code orders -> customers -> members -> member_levels}，而
 * {@code orders -> coupon_usages -> members} 在图上同样连通。只给边列表，
 * 两条路径在模型眼里是等价的。
 *
     * <p><b>算法：以「最相关的表」为根，做优先队列驱动的生成树</b>
 *
     * <p>本质是 Prim 算法的一个变体，每轮从边界上挑一条边，优先级依次是：
 *
 * <ul>
     *   <li><b>边强度</b>：优先走外键（1.0）而不是词典关联（0.5）——外键是数据库保证的硬事实；</li>
     *   <li><b>目标表的相关度</b>：同强度时先连**更相关**的表（检索分数已经排好了序）；</li>
     *   <li><b>表名</b>：还相同就按字母序兜底，保证同一份输入永远产出同一棵树。</li>
 * </ul>
     *
     * <p><b>为什么第二优先级必须是相关度，而不是直接按表名</b>
     *
     * <p>这一条是被集成测试逼出来的，而且**它决定 T5-001 能不能修好**。
     * 最初只写了「强度 → 表名」，结果规划 T5-001 的六表链路时出现了这个局面：
     * 到第三轮迭代，{@code coupon_usages}、{@code members}、{@code regions}
     * 三条候选边强度都是 0.5（都是词典关联），于是由表名字母序决定——
     * {@code coupon_usages} 排到了 {@code members} 前面，主干变成
     * 「经优惠券到会员」，**正是 T5-001 出错的那条路径**。
     *
     * <p>加上相关度做第二键之后，{@code members} 的检索排名远高于
     * {@code coupon_usages}，主干就回到
     * {@code orders -> customers -> members -> member_levels}——与 gold 一致。
     *
     * <p>这个教训值得记：**强度只区分「硬事实 / 软知识」，它无法区分
     * 「软知识里哪条更该走」**。而检索分数恰好携带了那个信息，
     * 直接复用比再造一个启发式规则更可靠。
 *
 * <p><b>为什么根选「最相关的表」而不是随便选</b>
 *
 * <p>树是给模型照着写 SQL 用的，从事实表出发（{@code orders}、{@code order_items}）
 * 符合 SQL 的自然写法；从维表出发（比如从 {@code regions} 出发）会得到一棵
 * 语义上别扭的树。检索分数已经排好了「哪张表最相关」，直接复用这个顺序。
 *
 * <p><b>被否掉的方案一：最短路（Dijkstra）</b>
 *
 * <p>「最短路」这个词容易误导——它不是要找两个点之间的一条路，而是要让
 * **所有被选中的表都连起来**，那本来就是生成树问题。对每一对表分别求最短路，
 * 得到的是 {n×(n-1)/2} 条路径的集合，反而比边列表更难读。
 *
 * <p><b>被否掉的方案二：把所有边都按分数排序输出</b>
 *
 * <p>那就是第 1 步已经在做的事。它不回答「先连谁后连谁」。
 *
 * <p><b>被否掉的方案三：不做规划，让模型自己看着办</b>
 *
 * <p>这正是阶段 1、2 的做法，也是 T5-001 这类题持续失败的原因。多表题的
 * join 错误是**静默的**——SQL 能跑，结果错——所以不能指望模型自己纠错。
 */
public final class JoinPathPlanner {

    private JoinPathPlanner() {
    }

    /**
     * 一棵连接树：从哪张表出发，按什么顺序连过去。
     *
     * @param root  起点表（检索分数最高的那张）
     * @param edges 选中的边，顺序即推荐的连接顺序（从根向外一层层展开）
     */
    public record Plan(String root, List<JoinGraph.Edge> edges) {

        public Plan {
            edges = List.copyOf(edges);
        }

        public boolean isEmpty() {
            return edges.isEmpty();
        }
    }

    /**
     * 规划一棵连接所有已选表的树。
     *
     * @param edges        候选边（应当已经过滤成「两端都被选中」）
     * @param rankedTables 表名，**按相关度从高到低**；第一个作为树的根
     * @return 连接树；无法规划时返回空 Plan（root 为 null）
     */
    public static Plan plan(List<JoinGraph.Edge> edges, List<String> rankedTables) {
        if (edges.isEmpty() || rankedTables.isEmpty()) {
            return new Plan(null, List.of());
        }

        // 表名大小写可能不一致（schema 元数据与词典各写各的），
        // 统一按小写建索引，输出时再还原成边里本来的写法。
        Map<String, List<JoinGraph.Edge>> byTable = new LinkedHashMap<>();
        for (JoinGraph.Edge edge : edges) {
            byTable.computeIfAbsent(normalize(edge.fromTable()), k -> new ArrayList<>()).add(edge);
            byTable.computeIfAbsent(normalize(edge.toTable()), k -> new ArrayList<>()).add(edge);
        }

        String root = normalize(rankedTables.getFirst());
        // 还原成边里本来的大小写，让渲染出来的表名与 DDL 段一致。
        String rootDisplay = rankedTables.getFirst();

        // 相关度排名：表名 -> 名次（0 表示最相关）。
        // 检索分数已经排好了序，这里直接复用，不再另造启发式规则。
        Map<String, Integer> relevance = new LinkedHashMap<>();
        for (int i = 0; i < rankedTables.size(); i++) {
            relevance.putIfAbsent(normalize(rankedTables.get(i)), i);
        }

        if (!byTable.containsKey(root)) {
            // 根是孤立点（没有边）：从剩下的表里挑第一个有边的当根。
            // 这种情况说明子图本身不连通，规划退化成「能连多少连多少」。
            String fallback = rankedTables.stream()
                    .map(JoinPathPlanner::normalize)
                    .filter(byTable::containsKey)
                    .findFirst()
                    .orElse(null);
            if (fallback == null) {
                return new Plan(null, List.of());
            }
            root = fallback;
            rootDisplay = displayName(edges, fallback);
        }

        Set<String> visited = new LinkedHashSet<>();
        visited.add(root);
        List<JoinGraph.Edge> plan = new ArrayList<>();

        // 优先队列：先比边强度（降序），再比目标表名（升序，保证确定性）。
        // 用一个「每轮重新筛边界」的写法而不是 java.util.PriorityQueue，
        // 是因为边界集合很小（几十条边），重新筛的代价可以忽略，
        // 而代码可读性明显更好——不需要处理惰性删除。
        while (true) {
            JoinGraph.Edge best = null;
            for (JoinGraph.Edge edge : edges) {
                boolean fromVisited = visited.contains(normalize(edge.fromTable()));
                boolean toVisited = visited.contains(normalize(edge.toTable()));
                // 只考虑「一端已连上、另一端还没连上」的边。
                if (fromVisited == toVisited) {
                    continue;
                }
                if (best == null || better(edge, best, visited, relevance)) {
                    best = edge;
                }
            }
            if (best == null) {
                break; // 剩下的表与已连部分没有边，说明子图不连通
            }
            plan.add(best);
            visited.add(normalize(best.fromTable()));
            visited.add(normalize(best.toTable()));
        }
        return new Plan(rootDisplay, plan);
    }

    /** 在边里找到某张表本来的写法。 */
    private static String displayName(List<JoinGraph.Edge> edges, String normalized) {
        for (JoinGraph.Edge edge : edges) {
            if (normalize(edge.fromTable()).equals(normalized)) {
                return edge.fromTable();
            }
            if (normalize(edge.toTable()).equals(normalized)) {
                return edge.toTable();
            }
        }
        return normalized;
    }

    /**
     * 排序规则：强度 → 新连上那张表的相关度 → 表名。
     *
     * <p><b>为什么兜底键必须是表名而不是整条边</b>
     *
     * <p>最初写的是「fromTable|fromColumn|toTable|toColumn」全字段排序，
     * 结果同强度的两条边由**列名**决定了先后——而「先连哪张表」是树的形状，
     * 不该由列名这种实现细节决定。
     *
     * <p>这个 bug 是单元测试抓出来的（{@code isDeterministic} 里两条同强度边
     * 的目标表是 customers 和 campaigns，期望 campaigns 先连，实际 customers 先连
     * ——因为 c1 < c2）。**测试写错了断言，反而暴露了代码的排序键选得不对。**
     *
     * <p>后来又发现只有「强度 → 表名」还不够，必须插入相关度这一级——
     * 理由见类注释里 T5-001 那段。
     */
    private static boolean better(JoinGraph.Edge candidate, JoinGraph.Edge current,
                                  Set<String> visited, Map<String, Integer> relevance) {
        if (candidate.strength() != current.strength()) {
            return candidate.strength() > current.strength();
        }
        int candidateRank = rank(newTable(candidate, visited), relevance);
        int currentRank = rank(newTable(current, visited), relevance);
        if (candidateRank != currentRank) {
            return candidateRank < currentRank;
        }
        return newTable(candidate, visited).compareTo(newTable(current, visited)) < 0;
    }

    /** 表的相关度名次；没出现在排名里的表排到最后。 */
    private static int rank(String table, Map<String, Integer> relevance) {
        return relevance.getOrDefault(table, Integer.MAX_VALUE);
    }

    /** 这条边里「还没连上」的那张表（同强度比较时用它做兜底键）。 */
    private static String newTable(JoinGraph.Edge edge, Set<String> visited) {
        return visited.contains(normalize(edge.fromTable()))
                ? normalize(edge.toTable())
                : normalize(edge.fromTable());
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /**
     * 把规划结果渲染成 prompt 里的一段。
     *
     * <p>每行写成 {@code 已连上的表.列 = 新表.列} 的等式形式，而不是
     * {@code A -> B}——因为模型最终要写的就是 ON 子句里的等式，
     * 直接给它等式形式，抄起来最不容易走样。
     *
     * @return 多行文本；没有可规划的边时返回空串
     */
    public static String render(Plan plan) {
        if (plan == null || plan.isEmpty() || plan.root() == null) {
            return "";
        }
        Set<String> connected = new LinkedHashSet<>();
        connected.add(normalize(plan.root()));
        StringBuilder sb = new StringBuilder();
        sb.append("  起点 ").append(plan.root()).append('\n');
        for (JoinGraph.Edge edge : plan.edges()) {
            // 方向以「从已连上的表出发」为准，读起来才是树。
            boolean fromConnected = connected.contains(normalize(edge.fromTable()));
            String from = fromConnected ? edge.fromTable() : edge.toTable();
            String fromCol = fromConnected ? edge.fromColumn() : edge.toColumn();
            String to = fromConnected ? edge.toTable() : edge.fromTable();
            String toCol = fromConnected ? edge.toColumn() : edge.fromColumn();
            connected.add(normalize(edge.fromTable()));
            connected.add(normalize(edge.toTable()));
            sb.append("  ").append(from).append('.').append(fromCol)
                    .append(" = ").append(to).append('.').append(toCol)
                    .append("   -- ").append(to)
                    .append(edge.isForeignKey() ? " [FK]" : " [约定]")
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
