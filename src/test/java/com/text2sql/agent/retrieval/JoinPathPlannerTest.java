package com.text2sql.agent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Join 路径规划测试。
 *
 * <p>最重要的是 {@code prefersForeignKeyPathOverInferredPath}——它固定了
 * 阶段 3 的核心判断：当**多条路径都连通**时，优先走外键那条。
 * 这正是 T5-001 的形态（gold 走 {@code orders->customers->members}，
 * 但 {@code orders->coupon_usages->members} 在图上同样连通）。
 */
class JoinPathPlannerTest {

    private static JoinGraph.Edge fk(String ft, String fc, String tt, String tc) {
        return new JoinGraph.Edge(ft, fc, tt, tc, JoinGraph.FOREIGN_KEY_STRENGTH);
    }

    private static JoinGraph.Edge inferred(String ft, String fc, String tt, String tc) {
        return new JoinGraph.Edge(ft, fc, tt, tc, JoinGraph.INFERRED_RELATION_STRENGTH);
    }

    @Test
    @DisplayName("核心：两条路径都连通时，优先走外键那条")
    void prefersForeignKeyPathOverInferredPath() {
        // T5-001 的形态：orders 到 members 有两条路
        //   路 A（外键）：orders -> customers -> members
        //   路 B（词典）：orders -> coupon_usages -> members
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "customer_id", "customers", "customer_id"),
                fk("customers", "customer_unique_id", "members", "customer_unique_id"),
                inferred("orders", "order_id", "coupon_usages", "order_id"),
                inferred("coupon_usages", "member_id", "members", "member_id"));

        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges,
                List.of("orders", "members", "customers", "coupon_usages"));

        assertThat(plan.root()).isEqualTo("orders");
        // 关键断言：members 必须**经 customers 的外键**连进来，而不是经 coupon_usages。
        // 这决定了模型写出来的 SQL 会不会绕过 customers——T5-001 正是栽在这里。
        assertThat(plan.edges()).anySatisfy(e -> {
            assertThat(e.fromTable()).isEqualTo("customers");
            assertThat(e.toTable()).isEqualTo("members");
            assertThat(e.isForeignKey()).isTrue();
        });
        // 外键边必须排在词典边前面：前两条都是外键。
        assertThat(plan.edges().get(0).isForeignKey()).isTrue();
        assertThat(plan.edges().get(1).isForeignKey()).isTrue();
        // coupon_usages 仍在候选表里，生成树会把它连上——但只能排在最后，
        // 且走的必然是词典关联。这不是缺陷：树要覆盖所有被选中的表。
        assertThat(plan.edges().getLast().fromTable()).isEqualTo("orders");
        assertThat(plan.edges().getLast().toTable()).isEqualTo("coupon_usages");
        assertThat(plan.edges().getLast().isForeignKey()).isFalse();
    }

    @Test
    @DisplayName("起点是相关度最高的表，不是字母序第一张")
    void rootsAtMostRelevantTable() {
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "customer_id", "customers", "customer_id"),
                fk("customers", "customer_unique_id", "members", "customer_unique_id"));

        // customers 排最后，但仍是 orders 当根。
        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges,
                List.of("orders", "members", "customers"));

        assertThat(plan.root()).isEqualTo("orders");
    }

    @Test
    @DisplayName("能连的表都连进来，形成覆盖全体的生成树")
    void connectsAllReachableTables() {
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "customer_id", "customers", "customer_id"),
                fk("customers", "customer_state", "regions", "region_code"),
                fk("orders", "order_id", "order_items", "order_id"),
                fk("order_items", "product_id", "products", "product_id"));

        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges,
                List.of("orders", "order_items", "customers", "regions", "products"));

        // 5 张表需要 4 条边（树的性质：n 个节点 n-1 条边）。
        assertThat(plan.edges()).hasSize(4);
    }

    @Test
    @DisplayName("子图不连通时只连能连上的部分，不抛异常")
    void handlesDisconnectedSubgraph() {
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "customer_id", "customers", "customer_id"));

        // products 是孤点，与谁都没有边。
        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges,
                List.of("orders", "customers", "products"));

        assertThat(plan.edges()).hasSize(1);
    }

    @Test
    @DisplayName("只规划根所在分量：第二个分量有边也不会进 plan（不是生成森林）")
    void coversOnlyRootComponent() {
        // 两个分量、各自都有边：
        //   分量 A = {orders, order_items}    分量 B = {members, member_snapshot}
        // 两者之间没有路径（customers 没被选中，桥接表不在）。
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "order_id", "order_items", "order_id"),
                fk("members", "member_id", "member_snapshot", "member_id"));

        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges,
                List.of("orders", "order_items", "members", "member_snapshot"));

        // 关键断言：只产出分量 A 的那一条边，分量 B 的边不在 plan 里。
        assertThat(plan.edges()).hasSize(1);
        assertThat(plan.edges().getFirst().fromTable()).isEqualTo("orders");

        // 但这个事实必须被暴露出来，而不是悄悄吞掉。
        assertThat(plan.selectedTableCount()).isEqualTo(4);
        assertThat(plan.plannedTableCount()).isEqualTo(2);
        assertThat(plan.incomplete()).isTrue();
    }

    @Test
    @DisplayName("渲染时声明覆盖不完整，避免模型把 JOIN PLAN 当成全部 join")
    void declaresIncompleteCoverage() {
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "order_id", "order_items", "order_id"),
                fk("members", "member_id", "member_snapshot", "member_id"));

        String text = JoinPathPlanner.render(JoinPathPlanner.plan(edges,
                List.of("orders", "order_items", "members", "member_snapshot")));

        assertThat(text).contains("还有 2 张表与起点不在同一连通分量");
        assertThat(text).contains("见上面的 JOINS");
    }

    @Test
    @DisplayName("覆盖完整时不输出警示行")
    void noWarningWhenFullyCovered() {
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "order_id", "order_items", "order_id"));

        String text = JoinPathPlanner.render(JoinPathPlanner.plan(edges,
                List.of("orders", "order_items")));

        assertThat(text).doesNotContain("不在同一连通分量");
    }

    @Test
    @DisplayName("根是孤点时改用其它有边的表当起点")
    void fallsBackWhenRootIsIsolated() {
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "customer_id", "customers", "customer_id"));

        // 相关度最高的 products 没有任何边。
        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges,
                List.of("products", "orders", "customers"));

        assertThat(plan.root()).isEqualTo("orders");
        assertThat(plan.edges()).hasSize(1);
    }

    @Test
    @DisplayName("同样输入永远产出同样结果（确定性）")
    void isDeterministic() {
        // 两条同强度的边竞争同一个位置，靠表名兜底排序。
        List<JoinGraph.Edge> edges = List.of(
                fk("orders", "c1", "customers", "c1"),
                fk("orders", "c2", "campaigns", "c2"));

        JoinPathPlanner.Plan first = JoinPathPlanner.plan(edges, List.of("orders"));
        JoinPathPlanner.Plan second = JoinPathPlanner.plan(edges, List.of("orders"));

        assertThat(first.edges()).isEqualTo(second.edges());
        // campaigns 字母序在 customers 前，所以它先被连上。
        assertThat(first.edges().getFirst().toTable()).isEqualTo("campaigns");
    }

    @Test
    @DisplayName("渲染成等式形式，且方向是「从已连上的表出发」")
    void rendersAsEqualityFromConnectedSide() {
        // 这条边在数据里是 customers -> orders，但根是 orders，
        // 渲染时必须翻成 orders.customer_id = customers.customer_id。
        List<JoinGraph.Edge> edges = List.of(
                fk("customers", "customer_id", "orders", "customer_id"));

        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges, List.of("orders", "customers"));
        String text = JoinPathPlanner.render(plan);

        assertThat(text).contains("起点 orders");
        assertThat(text).contains("orders.customer_id = customers.customer_id");
        assertThat(text).contains("[FK]");
    }

    @Test
    @DisplayName("词典关联在渲染里标成 [约定]，与外键区分")
    void marksInferredEdges() {
        List<JoinGraph.Edge> edges = List.of(
                inferred("customers", "customer_state", "regions", "region_code"));

        String text = JoinPathPlanner.render(
                JoinPathPlanner.plan(edges, List.of("customers", "regions")));

        assertThat(text).contains("[约定]");
        assertThat(text).doesNotContain("[FK]");
    }

    @Test
    @DisplayName("没有边时返回空文本，不产出空段")
    void rendersEmptyForNoEdges() {
        assertThat(JoinPathPlanner.render(JoinPathPlanner.plan(List.of(), List.of("orders"))))
                .isEmpty();
    }

    @Test
    @DisplayName("planForTargets：目标表不足 2 张时返回空 Plan，杜绝单表题过度 Join")
    void planForTargetsReturnsEmptyForSingleTarget() {
        JoinGraph graph = JoinGraph.from(new SchemaContext(
                List.of(new SchemaContext.Table("orders", null, List.of())),
                List.of(), "", ""), new com.text2sql.agent.retrieval.glossary.Glossary(List.of(), java.util.Map.of()));

        var plan = JoinPathPlanner.planForTargets(graph, java.util.Set.of("orders"), List.of("orders", "customers"));
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("planForTargets：仅保留连接目标表的最短主干边，排除未涉及的候选表")
    void planForTargetsExtractsMinimalSteinerPath() {
        // 图结构:
        // orders -> customers -> regions
        // orders -> sellers
        // orders -> order_items -> products
        SchemaContext schema = new SchemaContext(List.of(
                new SchemaContext.Table("orders", null, List.of()),
                new SchemaContext.Table("customers", null, List.of()),
                new SchemaContext.Table("regions", null, List.of()),
                new SchemaContext.Table("sellers", null, List.of()),
                new SchemaContext.Table("order_items", null, List.of()),
                new SchemaContext.Table("products", null, List.of())),
                List.of(
                        new SchemaContext.ForeignKey("orders", "customer_id", "customers", "customer_id"),
                        new SchemaContext.ForeignKey("orders", "seller_id", "sellers", "seller_id"),
                        new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id"),
                        new SchemaContext.ForeignKey("order_items", "product_id", "products", "product_id")
                ), "", "");

        com.text2sql.agent.retrieval.glossary.Glossary glossary = new com.text2sql.agent.retrieval.glossary.Glossary(
                List.of(new com.text2sql.agent.retrieval.glossary.Glossary.Relation("customers.customer_state", "regions.region_code")),
                java.util.Map.of());

        JoinGraph graph = JoinGraph.from(schema, glossary);

        // 目标表只有 orders 和 regions，候选表包含了全部 6 张表
        var targets = java.util.Set.of("orders", "regions");
        var ranked = List.of("orders", "sellers", "order_items", "products", "customers", "regions");

        var plan = JoinPathPlanner.planForTargets(graph, targets, ranked);

        assertThat(plan.isEmpty()).isFalse();
        assertThat(plan.root()).isEqualTo("orders");
        // 关键断言：仅包含 orders -> customers 和 customers -> regions，绝不能包含 sellers / products / order_items
        assertThat(plan.edges()).hasSize(2);
        assertThat(plan.edges()).allMatch(e ->
                (e.fromTable().equals("orders") && e.toTable().equals("customers")) ||
                (e.fromTable().equals("customers") && e.toTable().equals("regions")));
    }
}
