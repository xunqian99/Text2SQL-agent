package com.text2sql.agent.retrieval;

import com.text2sql.agent.retrieval.glossary.Glossary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关系图测试。**不连数据库**，用内存里构造的 schema + 词典。
 *
 * <p>这组测试里最重要的是 {@code carriesColumnLevelEdgeForT4_015}——它固定了
 * 阶段 3 的动机本身：{@code customers.customer_state -> regions.region_code}
 * 这条边必须出现在图里，而且**必须带着列名**。如果哪天有人把它退回成
 * 「表到表」的邻接表，那条测试会立刻失败，而 T4-015 那类错误会重新出现。
 */
class JoinGraphTest {

    private static SchemaContext.Table table(String name, String... columns) {
        List<SchemaContext.Column> cols = java.util.Arrays.stream(columns)
                .map(c -> new SchemaContext.Column(c, "text", true, null))
                .toList();
        return new SchemaContext.Table(name, null, cols);
    }

    /** 只声明真实外键的 schema。 */
    private static SchemaContext schemaWith(List<SchemaContext.ForeignKey> fks, String... tables) {
        List<SchemaContext.Table> tableList = java.util.Arrays.stream(tables)
                .map(t -> table(t, "id"))
                .toList();
        return new SchemaContext(tableList, fks, "", "");
    }

    private static Glossary glossaryWith(List<Glossary.Relation> relations) {
        return new Glossary(relations, Map.of());
    }

    @Test
    @DisplayName("阶段 3 动机：customers 与 regions 之间的边带列名，而不是只到表级")
    void carriesColumnLevelEdgeForT4_015() {
        // 真实库的形态：orders->customers 是真外键；
        // customers.customer_state -> regions.region_code 没有外键，靠词典补。
        SchemaContext schema = new SchemaContext(
                List.of(table("orders", "order_id", "customer_id"),
                        table("customers", "customer_id", "customer_state"),
                        table("regions", "region_code", "region_name")),
                List.of(new SchemaContext.ForeignKey("orders", "customer_id", "customers", "customer_id")),
                "", "");
        Glossary glossary = glossaryWith(List.of(
                new Glossary.Relation("customers.customer_state", "regions.region_code")));

        JoinGraph graph = JoinGraph.from(schema, glossary);
        List<JoinGraph.Edge> among = graph.edgesAmong(Set.of("orders", "customers", "regions"));

        // 关键断言：这条边必须存在，而且列名必须是 customer_state -> region_code。
        // 阶段 2 的 bug 正是模型看不到这条边，于是猜成了 region_name
        // （两列在 regions 里都存在，SQL 能跑，结果错——静默错误）。
        assertThat(among)
                .anySatisfy(edge -> {
                    assertThat(edge.fromTable()).isEqualTo("customers");
                    assertThat(edge.fromColumn()).isEqualTo("customer_state");
                    assertThat(edge.toTable()).isEqualTo("regions");
                    assertThat(edge.toColumn()).isEqualTo("region_code");
                });
    }

    @Test
    @DisplayName("外键带 [FK] 标记，词典关联不带——可信度必须能区分")
    void marksForeignKeysOnly() {
        SchemaContext schema = schemaWith(
                List.of(new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id")),
                "orders", "order_items", "refunds");
        Glossary glossary = glossaryWith(List.of(
                new Glossary.Relation("refunds.order_id", "orders.order_id")));

        JoinGraph graph = JoinGraph.from(schema, glossary);

        List<String> rendered = graph.edges().stream().map(JoinGraph.Edge::render).toList();
        assertThat(rendered).anyMatch(r -> r.contains("order_items.order_id -> orders.order_id")
                && r.endsWith("[FK]"));
        assertThat(rendered).anyMatch(r -> r.contains("refunds.order_id -> orders.order_id")
                && !r.endsWith("[FK]"));
    }

    @Test
    @DisplayName("同一条边被外键和词典各声明一次时只留一条，且按外键强度")
    void deduplicatesRepeatedEdge() {
        // 词典里会把一部分真实外键再声明一遍（为了让人读词典时看到完整关联）。
        SchemaContext schema = schemaWith(
                List.of(new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id")),
                "orders", "order_items");
        Glossary glossary = glossaryWith(List.of(
                new Glossary.Relation("order_items.order_id", "orders.order_id")));

        JoinGraph graph = JoinGraph.from(schema, glossary);

        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().getFirst().strength()).isEqualTo(JoinGraph.FOREIGN_KEY_STRENGTH);
        assertThat(graph.edges().getFirst().isForeignKey()).isTrue();
    }

    @Test
    @DisplayName("edgesAmong 只保留两端都被选中的边，避免给模型「表没召回却告诉它怎么 join」")
    void filtersEdgesToSelectedTables() {
        SchemaContext schema = schemaWith(
                List.of(new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id")),
                "orders", "order_items", "products");
        Glossary glossary = glossaryWith(List.of(
                new Glossary.Relation("order_items.product_id", "products.product_id")));

        JoinGraph graph = JoinGraph.from(schema, glossary);

        // 只选 orders 和 order_items：到 products 的那条边必须被过滤掉。
        List<JoinGraph.Edge> among = graph.edgesAmong(Set.of("orders", "order_items"));
        assertThat(among).hasSize(1);
        assertThat(among.getFirst().toTable()).isEqualTo("orders");
    }

    @Test
    @DisplayName("表对之间存在任意外键时，该表对上的词典关联也按外键强度算")
    void upgradesInferredEdgeWhenTablePairHasForeignKey() {
        SchemaContext schema = schemaWith(
                List.of(new SchemaContext.ForeignKey("members", "customer_unique_id",
                        "customers", "customer_unique_id")),
                "members", "customers");
        // 同一对表之间还有一条别的关联列，按表对判定应升级成外键强度。
        Glossary glossary = glossaryWith(List.of(
                new Glossary.Relation("members.other_col", "customers.other_col")));

        JoinGraph graph = JoinGraph.from(schema, glossary);

        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.edges()).allMatch(e -> e.strength() == JoinGraph.FOREIGN_KEY_STRENGTH);
    }

    @Test
    @DisplayName("表级邻接表与列级边同源：能连上的表对，邻接表里一定有")
    void tableAdjacencyAgreesWithEdges() {
        SchemaContext schema = schemaWith(
                List.of(new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id")),
                "orders", "order_items", "refunds");
        Glossary glossary = glossaryWith(List.of(
                new Glossary.Relation("refunds.order_id", "orders.order_id")));

        JoinGraph graph = JoinGraph.from(schema, glossary);

        // 这是「检索打分」与「prompt 渲染」不会再分叉的结构性保证。
        for (JoinGraph.Edge edge : graph.edges()) {
            assertThat(graph.connected(edge.fromTable(), edge.toTable())).isTrue();
        }
        assertThat(graph.tableAdjacency().get("refunds")).containsKey("orders");
    }

    @Test
    @DisplayName("格式不对的词典端点被跳过，不抛异常")
    void skipsMalformedEndpoints() {
        SchemaContext schema = schemaWith(List.of(), "orders");
        Glossary glossary = glossaryWith(List.of(
                new Glossary.Relation("orders", "orders.order_id"),
                new Glossary.Relation("orders.", "orders.order_id")));

        JoinGraph graph = JoinGraph.from(schema, glossary);

        assertThat(graph.edges()).isEmpty();
    }
}
