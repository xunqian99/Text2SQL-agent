package com.text2sql.agent.retrieval;

import com.text2sql.agent.retrieval.glossary.Glossary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端渲染测试：**从真实的失败题出发**，验证阶段 3 真的修掉了它们。
 *
 * <p>这一组测试的价值在于「可追溯」：每一条都对应评估集里一道真实做错的题。
 * 如果哪天有人把 join 渲染改回去，这些测试会失败，并直接指向
 * 「T4-015 那类错误会重新出现」。
 */
class JoinRenderingIntegrationTest {

    private static SchemaContext.Column col(String name) {
        return new SchemaContext.Column(name, "text", true, null);
    }

    private static SchemaContext.Table table(String name, String... cols) {
        return new SchemaContext.Table(name, null,
                java.util.Arrays.stream(cols).map(JoinRenderingIntegrationTest::col).toList());
    }

    /** 用真实库的形状构造一个最小 schema。 */
    private static SchemaContext realisticSchema() {
        return new SchemaContext(
                List.of(
                        table("orders", "order_id", "customer_id", "order_status"),
                        table("customers", "customer_id", "customer_unique_id", "customer_state"),
                        table("regions", "region_code", "region_name", "macro_region"),
                        table("members", "member_id", "customer_unique_id", "level_id"),
                        table("member_levels", "level_id", "level_name"),
                        table("order_items", "order_id", "order_item_id", "product_id", "price"),
                        table("coupon_usages", "order_id", "member_id", "coupon_id")),
                List.of(
                        new SchemaContext.ForeignKey("orders", "customer_id", "customers", "customer_id"),
                        new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id"),
                        new SchemaContext.ForeignKey("members", "level_id", "member_levels", "level_id")),
                "", "");
    }

    private static Glossary realisticGlossary() {
        return new Glossary(List.of(
                new Glossary.Relation("customers.customer_state", "regions.region_code"),
                new Glossary.Relation("members.customer_unique_id", "customers.customer_unique_id"),
                new Glossary.Relation("coupon_usages.order_id", "orders.order_id"),
                new Glossary.Relation("coupon_usages.member_id", "members.member_id")),
                Map.of());
    }

    /** 走一遍真实链路：构图 → 过滤 → 规划 → 渲染。 */
    private static String render(Set<String> selected, List<String> ranked) {
        JoinGraph graph = JoinGraph.from(realisticSchema(), realisticGlossary());
        List<JoinGraph.Edge> edges = graph.edgesAmong(selected);
        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges, ranked);
        List<SchemaContext.Table> tables = realisticSchema().tables().stream()
                .filter(t -> selected.contains(t.name()))
                .toList();
        return SchemaDdlRenderer.renderWithJoins(tables, edges, plan);
    }

    @Test
    @DisplayName("T4-015：模型能看到 customer_state 该连 region_code，不必再猜 region_name")
    void fixesT4_015RegionJoin() {
        String ddl = render(Set.of("orders", "customers", "regions"),
                List.of("orders", "customers", "regions"));

        // 这就是修掉 T4-015 的那一行。之前模型看不到它，猜成了 region_name
        // （两列在 regions 里都存在，SQL 能跑通、结果错——静默错误）。
        assertThat(ddl).contains("customers.customer_state -> regions.region_code");
        // 而且规划的连接顺序里也要出现这一跳。
        assertThat(ddl).contains("JOIN PLAN");
        assertThat(ddl).contains("customers.customer_state = regions.region_code");
    }

    @Test
    @DisplayName("T5-001：六表链路优先走外键主干，而不是 coupon_usages 那条捷径")
    void fixesT5_001PrefersForeignKeyBackbone() {
        String ddl = render(
                Set.of("orders", "customers", "regions", "members", "member_levels",
                        "order_items", "coupon_usages"),
                List.of("orders", "order_items", "customers", "members", "member_levels",
                        "regions", "coupon_usages"));

        String planSection = ddl.substring(ddl.indexOf("JOIN PLAN"));

        // 主干必须经 customers 到 members（外键），而不是经 coupon_usages（词典）。
        assertThat(planSection).contains("customers.customer_unique_id = members.customer_unique_id");
        // 起点是 orders——事实表，符合 SQL 的自然写法。
        assertThat(planSection).contains("起点 orders");
        // coupon_usages 仍然会被连上（树要覆盖所有选中表），但排在后面。
        int membersAt = planSection.indexOf("members");
        int couponAt = planSection.indexOf("coupon_usages");
        assertThat(membersAt).isLessThan(couponAt);
    }

    @Test
    @DisplayName("单表问题不产生空的 JOIN PLAN 段")
    void singleTableHasNoJoinPlan() {
        String ddl = render(Set.of("orders"), List.of("orders"));

        assertThat(ddl).contains("TABLE orders");
        assertThat(ddl).doesNotContain("JOINS");
        assertThat(ddl).doesNotContain("JOIN PLAN");
    }

    @Test
    @DisplayName("外键标 [FK]，词典关联标 [约定]，模型能看出可信度差别")
    void distinguishesForeignKeyFromInferredInPlan() {
        String ddl = render(Set.of("orders", "customers", "regions"),
                List.of("orders", "customers", "regions"));

        String planSection = ddl.substring(ddl.indexOf("JOIN PLAN"));
        assertThat(planSection).contains("orders.customer_id = customers.customer_id   -- customers [FK]");
        assertThat(planSection).contains("customers.customer_state = regions.region_code   -- regions [约定]");
    }
}
