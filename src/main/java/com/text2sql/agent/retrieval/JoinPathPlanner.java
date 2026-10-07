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
     * <p><b>它只覆盖一个连通分量。</b>算法从 {@code root} 出发扩展，
     * 覆盖不到就停止，不会为其它分量另起一棵树。所以
     * {@code plannedTableCount} 可能小于 {@code selectedTableCount}——
     * 这个差距不是 bug，但**必须告诉模型**。
     *
     * @param root  起点表（检索分数最高的那张）
     * @param edges 选中的边，顺序即推荐的连接顺序（从根向外一层层展开）
     * @param selectedTableCount 本次上下文里的表总数（用来判断覆盖是否完整）
     */
    public record Plan(String root, List<JoinGraph.Edge> edges, int selectedTableCount) {

        public Plan {
            edges = List.copyOf(edges);
        }

        public boolean isEmpty() {
            return edges.isEmpty();
        }

        /** 这棵树覆盖了多少张表 = 边数 + 1（树有 n-1 条边）。 */
        public int plannedTableCount() {
            return edges.isEmpty() ? 0 : edges.size() + 1;
        }

        /** 是否有表连不上：这些表在 JOINS 段里有边，但没有推荐顺序。 */
        public boolean incomplete() {
            return !edges.isEmpty() && plannedTableCount() < selectedTableCount;
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
            return new Plan(null, List.of(), rankedTables.size());
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
            // 根是孤立点（没有任何边）：换一张有边的表当根。
            // 注意这只是「换个分量开始」，**不意味着会规划多个分量**——
            // 下面的循环从单一 root 出发、覆盖不到就 break，所以它产出的是
            // 「根所在的那一个连通分量」的树，不是生成森林。
            String fallback = rankedTables.stream()
                    .map(JoinPathPlanner::normalize)
                    .filter(byTable::containsKey)
                    .findFirst()
                    .orElse(null);
            if (fallback == null) {
                return new Plan(null, List.of(), rankedTables.size());
            }
            root = fallback;
            rootDisplay = displayName(edges, fallback);
        }

        Set<String> visited = new LinkedHashSet<>();
        visited.add(root);
        List<JoinGraph.Edge> plan = new ArrayList<>();

        // 每轮从「边界」上挑一条边：先比边强度（降序），再比新连上那张表的
        // 相关度（升序），最后按表名兜底保证确定性。
        // 用「每轮重新筛边界」的写法而不是 java.util.PriorityQueue，
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
        return new Plan(rootDisplay, plan, rankedTables.size());
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
     * 针对核心业务目标表集合，规划最小连通连接树（Steiner Tree / 最短连接路径启发式）。
     *
     * <p>解决的核心问题：Top-K 召回可能返回 8~10 张表，如果对全部召回表生成树，
     * 会强制把无关表连进来，导致模型发生「过度 Join」；
     * 本方法只寻找能够连通 {@code targetTables} 核心实体的最短主干边（外键优先），
     * 自动补齐必要的桥接表，并剔除其余无关边。
     *
     * @param graph        表关系图
     * @param targetTables 核心实体表集合（自然语言/取值/指标直接命中的表）
     * @param rankedTables 召回表的优先级列表
     * @return 仅覆盖核心实体的最小连接树；若核心表不足 2 张或无法连通，返回空 Plan
     */
    public static Plan planForTargets(JoinGraph graph, Set<String> targetTables, List<String> rankedTables) {
        if (graph == null || targetTables == null || rankedTables == null || targetTables.size() < 2) {
            return new Plan(null, List.of(), targetTables == null ? 0 : targetTables.size());
        }

        Set<String> normalizedTargets = new LinkedHashSet<>();
        for (String t : targetTables) {
            if (t != null && !t.isBlank()) {
                normalizedTargets.add(normalize(t));
            }
        }
        if (normalizedTargets.size() < 2) {
            return new Plan(null, List.of(), normalizedTargets.size());
        }

        // 确定根节点：优先选择 rankedTables 中排名最靠前的 targetTable
        String root = rankedTables.stream()
                .map(JoinPathPlanner::normalize)
                .filter(normalizedTargets::contains)
                .findFirst()
                .orElse(normalizedTargets.iterator().next());

        Set<String> allowedTables = new LinkedHashSet<>();
        rankedTables.forEach(t -> allowedTables.add(normalize(t)));
        allowedTables.addAll(normalizedTargets);

        List<JoinGraph.Edge> candidateEdges = graph.edgesAmong(allowedTables);
        Map<String, List<JoinGraph.Edge>> adj = new LinkedHashMap<>();
        for (JoinGraph.Edge edge : candidateEdges) {
            adj.computeIfAbsent(normalize(edge.fromTable()), k -> new ArrayList<>()).add(edge);
            adj.computeIfAbsent(normalize(edge.toTable()), k -> new ArrayList<>()).add(edge);
        }

        if (!adj.containsKey(root)) {
            return new Plan(null, List.of(), normalizedTargets.size());
        }

        Set<JoinGraph.Edge> selectedEdges = new LinkedHashSet<>();
        for (String target : normalizedTargets) {
            if (target.equals(root)) {
                continue;
            }
            List<JoinGraph.Edge> path = findShortestPath(root, target, adj);
            if (path != null) {
                selectedEdges.addAll(path);
            }
        }

        if (selectedEdges.isEmpty()) {
            return new Plan(null, List.of(), normalizedTargets.size());
        }

        return plan(new ArrayList<>(selectedEdges), rankedTables);
    }

    private static List<JoinGraph.Edge> findShortestPath(String start, String target, Map<String, List<JoinGraph.Edge>> adj) {
        record NodeDist(String node, double dist) {}
        Map<String, Double> dist = new LinkedHashMap<>();
        Map<String, JoinGraph.Edge> prevEdge = new LinkedHashMap<>();
        Map<String, String> prevNode = new LinkedHashMap<>();

        java.util.PriorityQueue<NodeDist> pq = new java.util.PriorityQueue<>(Comparator.comparingDouble(NodeDist::dist));
        dist.put(start, 0.0);
        pq.add(new NodeDist(start, 0.0));

        while (!pq.isEmpty()) {
            NodeDist curr = pq.poll();
            if (curr.dist() > dist.getOrDefault(curr.node(), Double.MAX_VALUE)) {
                continue;
            }
            if (curr.node().equals(target)) {
                break;
            }

            List<JoinGraph.Edge> neighbors = adj.getOrDefault(curr.node(), List.of());
            for (JoinGraph.Edge edge : neighbors) {
                String neighborNode = normalize(edge.fromTable()).equals(curr.node())
                        ? normalize(edge.toTable())
                        : normalize(edge.fromTable());

                // 外键边代价 1.0，推断边代价 2.0（优先走外键硬事实）
                double weight = edge.isForeignKey() ? 1.0 : 2.0;
                double newDist = curr.dist() + weight;

                if (newDist < dist.getOrDefault(neighborNode, Double.MAX_VALUE)) {
                    dist.put(neighborNode, newDist);
                    prevEdge.put(neighborNode, edge);
                    prevNode.put(neighborNode, curr.node());
                    pq.add(new NodeDist(neighborNode, newDist));
                }
            }
        }

        if (!dist.containsKey(target)) {
            return null; // 无法连通
        }

        List<JoinGraph.Edge> path = new ArrayList<>();
        String curr = target;
        while (!curr.equals(start)) {
            JoinGraph.Edge edge = prevEdge.get(curr);
            if (edge == null) {
                break;
            }
            path.add(edge);
            curr = prevNode.get(curr);
        }
        java.util.Collections.reverse(path);
        return path;
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
        // 覆盖声明。这一行不是装饰——它是给模型的**警示信号**。
        //
        // 规划只覆盖根所在的那个连通分量，其它分量的边仍然出现在 JOINS 段里，
        // 但没有推荐顺序。如果不说破，模型很可能把 JOIN PLAN 当成
        // 「这就是全部的 join」而漏掉剩下的表。告诉它「还有 N 张连不上」，
        // 比悄悄少给更有用——这类遗漏会以「SQL 能跑、结果错」的形式出现，
        // 是最难查的那一类。
        if (plan.incomplete()) {
            int missing = plan.selectedTableCount() - plan.plannedTableCount();
            sb.append("  -- 注意：还有 ").append(missing)
                    .append(" 张表与起点不在同一连通分量，本段未给出连接顺序；")
                    .append("它们的关联见上面的 JOINS。\n");
        }
        return sb.toString().stripTrailing();
    }
}
