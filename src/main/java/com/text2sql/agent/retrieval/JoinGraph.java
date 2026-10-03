package com.text2sql.agent.retrieval;

import com.text2sql.agent.retrieval.glossary.Glossary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表关系图：外键 + 词典约定关联的**唯一定义**。
 *
 * <p>对应 ROADMAP 阶段 3 第 1、3、4 条（抽取外键构图、把 join 关系写进 prompt、
 * 处理无外键但逻辑相关的表）。
 *
 * <p><b>为什么必须把它抽出来，而不是继续留在检索器里</b>
 *
 * <p>阶段 2 里「有哪些 join 边」这件事被写了两遍，而且两遍不一样：
 * {@code LexicalSchemaRetriever} 内部的 {@code adjacency()} 给候选表打分时
 * 用的是「13 条真实外键 + 36 条词典关联」，而 {@code HybridSchemaProvider}
 * 渲染 prompt 时用的是 {@code SchemaContext.foreignKeys()}——**只有 13 条外键**。
 * 直接后果是：**检索器知道怎么 join，模型不知道。**
 *
 * <p>实测踩到的例子：T4-015「每个大区的订单数量」，gold 写
 * {@code r.region_code = c.customer_state}，模型写成了
 * {@code c.customer_state = r.region_name}。这两列在 {@code regions} 里都存在，
 * 所以 SQL 能跑通、只是结果错——**静默错误**。而
 * {@code customers.customer_state -> regions.region_code} 这条边一直躺在词典里，
 * 只是从来没进过 prompt。
 *
 * <p><b>为什么边要带列名</b>
 *
 * <p>阶段 2 的 {@code adjacency()} 只保留「表到表」，因为打分只需要判断
 * 「够不够得着」。但 prompt 里必须写清**哪一列连哪一列**——那正是模型缺的信息。
 * 所以这里保留列级边，表级邻接表由它投影出来（{@link #tableAdjacency()}）。
 *
 * <p><b>强度为什么分两档</b>
 *
 * <p>外键是数据库强制约束的硬事实，词典关联是人工核对的软知识，可信度不同，
 * 渲染时用 {@code [FK]} 标出来，让模型知道哪条更可靠。这与阶段 2 的边权重
 * 是同一个判断，只是这里要把它显式写进文本。
 */
public final class JoinGraph {

    /** 真实外键的强度。 */
    public static final double FOREIGN_KEY_STRENGTH = 1.0;

    /** 词典约定关联的强度。 */
    public static final double INFERRED_RELATION_STRENGTH = 0.5;

    /**
     * 一条 join 边。
     *
     * <p>逻辑上无向（「某订单的客户」和「某客户的所有订单」都要能查），
     * 但保留词典/外键声明的方向，渲染成文本时可读性更好。
     */
    public record Edge(String fromTable, String fromColumn,
                       String toTable, String toColumn,
                       double strength) {

        /** 是否来自数据库外键。渲染时加 {@code [FK]} 标记。 */
        public boolean isForeignKey() {
            return strength >= FOREIGN_KEY_STRENGTH;
        }

        /**
         * 无向规范键：同一对 (表, 列) 无论方向、无论来源都算同一条边。
         *
         * <p>词典里会把一部分真实外键再声明一遍（为了让人读词典时看到完整关联，
         * 不用去翻 DDL）。用这个键去重，才能避免同一条边在 prompt 里出现两次。
         */
        String key() {
            String a = qualified(fromTable, fromColumn);
            String b = qualified(toTable, toColumn);
            return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
        }

        /** 渲染成 prompt 里的一行。 */
        public String render() {
            return "%s.%s -> %s.%s%s".formatted(
                    fromTable, fromColumn, toTable, toColumn,
                    isForeignKey() ? "  [FK]" : "");
        }

        private static String qualified(String table, String column) {
            return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
        }
    }

    private final List<Edge> edges;
    private final Map<String, Map<String, Double>> tableAdjacency;

    private JoinGraph(List<Edge> edges, Map<String, Map<String, Double>> tableAdjacency) {
        this.edges = List.copyOf(edges);
        this.tableAdjacency = tableAdjacency;
    }

    /**
     * 从 schema 元数据与词典构建关系图。
     *
     * <p>纯函数：给定同样的 schema 和词典，结果完全确定。所以不需要缓存成 Bean，
     * 调用方各自缓存即可（{@link LexicalSchemaRetriever} 就是这么做的）。
     *
     * <p><b>强度判定按「表对」而不是「列对」</b>：只要两张表之间存在任意一条真实外键，
     * 那么这两张表之间的所有关联都按外键强度算。理由是外键的存在说明这两张表在
     * 数据库层面就是强关联的，同一对表之间再出现别的关联列，可信度不会更低。
     * 这个判定与阶段 2 的 {@code adjacency()} 完全一致，保证打分行为不变。
     */
    public static JoinGraph from(SchemaContext schema, Glossary glossary) {
        Set<String> fkTablePairs = new LinkedHashSet<>();
        for (SchemaContext.ForeignKey fk : schema.foreignKeys()) {
            fkTablePairs.add(tablePairKey(fk.fromTable(), fk.toTable()));
        }

        // 用 LinkedHashMap 去重并保序：先写外键、再写词典关联，
        // 于是 edges 天然是「硬事实在前、软知识在后」，渲染出来就是这个顺序。
        Map<String, Edge> byKey = new LinkedHashMap<>();

        for (SchemaContext.ForeignKey fk : schema.foreignKeys()) {
            merge(byKey, new Edge(fk.fromTable(), fk.fromColumn(),
                    fk.toTable(), fk.toColumn(), FOREIGN_KEY_STRENGTH));
        }
        for (Glossary.Relation relation : glossary.relations()) {
            String[] from = splitEndpoint(relation.from());
            String[] to = splitEndpoint(relation.to());
            if (from == null || to == null) {
                continue; // 词典写错了会被 GlossaryLoader 拦住，这里再兜一层
            }
            double strength = fkTablePairs.contains(tablePairKey(from[0], to[0]))
                    ? FOREIGN_KEY_STRENGTH
                    : INFERRED_RELATION_STRENGTH;
            merge(byKey, new Edge(from[0], from[1], to[0], to[1], strength));
        }

        List<Edge> edges = new ArrayList<>(byKey.values());
        return new JoinGraph(edges, buildTableAdjacency(edges));
    }

    /** 全图的所有边，顺序为「外键在前、词典关联在后」。 */
    public List<Edge> edges() {
        return edges;
    }

    /**
     * 只保留**两端都被选中**的边。
     *
     * <p>与阶段 2 过滤外键是同一个理由：如果只留表不留边，模型看到
     * {@code orders} 和 {@code customers} 却不知道它们怎么连，就会去猜
     * join 条件——而猜错的 join 照样能执行，错误是静默的。
     *
     * <p>反过来，也**不能**把全图的边都塞进去：那会引入
     * 「表都没召回，却告诉模型它怎么 join」的矛盾信息。
     *
     * @param tables 本次上下文包含的表名（大小写不敏感）
     */
    public List<Edge> edgesAmong(Set<String> tables) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String table : tables) {
            normalized.add(table.toLowerCase(Locale.ROOT));
        }
        return edges.stream()
                .filter(e -> normalized.contains(e.fromTable().toLowerCase(Locale.ROOT))
                        && normalized.contains(e.toTable().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * 表级邻接表：{@code 表 -> 邻居表 -> 边强度}。
     *
     * <p>这是给检索打分用的投影——打分只需要知道「够不够得着」和「有多可信」，
     * 不需要列名。同一对表之间有多条边时取最大强度。
     *
     * <p>键使用 schema/词典里的原始表名（不做大小写归一），与阶段 2 的行为
     * 保持一致，避免改动影响已有测试与排序结果。
     */
    public Map<String, Map<String, Double>> tableAdjacency() {
        return tableAdjacency;
    }

    /** 两点之间是否存在边（忽略方向）。 */
    public boolean connected(String a, String b) {
        return tableAdjacency.getOrDefault(a, Map.of()).containsKey(b)
                || tableAdjacency.getOrDefault(b, Map.of()).containsKey(a);
    }

    private static void merge(Map<String, Edge> byKey, Edge candidate) {
        byKey.merge(candidate.key(), candidate,
                // 同一条边有两个来源时保留强度更高的那个（外键 1.0 不会被词典 0.5 覆盖）。
                (existing, incoming) -> incoming.strength() > existing.strength() ? incoming : existing);
    }

    private static Map<String, Map<String, Double>> buildTableAdjacency(List<Edge> edges) {
        Map<String, Map<String, Double>> adjacency = new LinkedHashMap<>();
        for (Edge edge : edges) {
            // 取 max 而不是累加：累加会让「邻居多的表」靠数量堆分数，
            // 而我们要的是「最强的那个关联有多可信」。
            link(adjacency, edge.fromTable(), edge.toTable(), edge.strength());
        }
        return adjacency;
    }

    private static void link(Map<String, Map<String, Double>> adjacency,
                             String a, String b, double strength) {
        if (a == null || b == null || a.equalsIgnoreCase(b)) {
            return;
        }
        adjacency.computeIfAbsent(a, k -> new LinkedHashMap<>()).merge(b, strength, Math::max);
        adjacency.computeIfAbsent(b, k -> new LinkedHashMap<>()).merge(a, strength, Math::max);
    }

    /** {@code "table.column"} -> {@code ["table", "column"]}；格式不对时返回 null。 */
    private static String[] splitEndpoint(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        int dot = endpoint.indexOf('.');
        if (dot <= 0 || dot == endpoint.length() - 1) {
            return null;
        }
        return new String[]{endpoint.substring(0, dot), endpoint.substring(dot + 1)};
    }

    /** 无向表对的规范键，用于判断某对表之间是否存在真实外键。 */
    private static String tablePairKey(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }
}
