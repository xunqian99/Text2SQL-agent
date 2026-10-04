package com.text2sql.agent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL 渲染测试。
 *
 * <p>它固定的是**模型实际读到的那段文本**——这是整条链路里唯一
 * 「人可以直接肉眼检查」的产物。渲染错了，后面所有环节都白搭，
 * 而且不会有任何异常抛出来，只是准确率悄悄掉。
 */
class SchemaDdlRendererTest {

    private static SchemaContext.Table orders() {
        return new SchemaContext.Table("orders", null, List.of(
                new SchemaContext.Column("order_id", "varchar", false, null),
                new SchemaContext.Column("customer_id", "varchar", false, null)));
    }

    private static SchemaContext.Table customers() {
        return new SchemaContext.Table("customers", null, List.of(
                new SchemaContext.Column("customer_id", "varchar", false, null),
                new SchemaContext.Column("customer_state", "varchar", true, null)));
    }

    private static SchemaContext.Table regions() {
        return new SchemaContext.Table("regions", null, List.of(
                new SchemaContext.Column("region_code", "varchar", false, null),
                new SchemaContext.Column("region_name", "varchar", true, null)));
    }

    @Test
    @DisplayName("阶段 3：join 段写清「哪一列连哪一列」，并用 [FK] 区分外键")
    void rendersColumnLevelJoins() {
        List<SchemaContext.Table> tables = List.of(orders(), customers(), regions());
        List<JoinGraph.Edge> edges = List.of(
                new JoinGraph.Edge("orders", "customer_id", "customers", "customer_id",
                        JoinGraph.FOREIGN_KEY_STRENGTH),
                new JoinGraph.Edge("customers", "customer_state", "regions", "region_code",
                        JoinGraph.INFERRED_RELATION_STRENGTH));

        String ddl = SchemaDdlRenderer.renderWithJoins(tables, edges);

        // 段标题必须叫 JOINS 而不是 FOREIGN KEYS——里面已经不全是外键了。
        assertThat(ddl).contains("JOINS");
        assertThat(ddl).doesNotContain("FOREIGN KEYS");
        // 外键带标记，词典关联不带。
        assertThat(ddl).contains("orders.customer_id -> customers.customer_id  [FK]");
        assertThat(ddl).contains("customers.customer_state -> regions.region_code");
        assertThat(ddl).doesNotContain("customers.customer_state -> regions.region_code  [FK]");
    }

    @Test
    @DisplayName("阶段 1 形态的 render 保持不变：表头仍是 FOREIGN KEYS，没有 [FK] 标记")
    void legacyRenderStaysUnchangedForBaseline() {
        List<SchemaContext.Table> tables = List.of(orders(), customers());
        List<SchemaContext.ForeignKey> fks = List.of(
                new SchemaContext.ForeignKey("orders", "customer_id", "customers", "customer_id"));

        String ddl = SchemaDdlRenderer.render(tables, fks);

        // baseline 必须保持阶段 1 的样子，否则 47% 那个历史数字就作废了。
        assertThat(ddl).contains("FOREIGN KEYS");
        assertThat(ddl).doesNotContain("JOINS");
        assertThat(ddl).doesNotContain("[FK]");
        assertThat(ddl).contains("orders.customer_id -> customers.customer_id");
    }

    @Test
    @DisplayName("两个 render 方法的表格部分逐字节相同，只有边那一段不同")
    void tableSectionIsSharedBetweenBothRenderers() {
        List<SchemaContext.Table> tables = List.of(orders(), customers());

        String legacy = SchemaDdlRenderer.render(tables, List.of());
        String withJoins = SchemaDdlRenderer.renderWithJoins(tables, List.of());

        // 没有边时，两者必须完全一样——这证明表格格式没有分叉。
        assertThat(withJoins).isEqualTo(legacy);
    }

    @Test
    @DisplayName("没有边时不输出空的 join 段")
    void omitsJoinSectionWhenNoEdges() {
        String ddl = SchemaDdlRenderer.renderWithJoins(List.of(orders()), List.of());

        assertThat(ddl).doesNotContain("JOINS");
        assertThat(ddl).contains("TABLE orders");
    }

    @Test
    @DisplayName("列注释优先于其它来源；没有注释时不补空说明")
    void keepsColumnComments() {
        SchemaContext.Table withComment = new SchemaContext.Table("members", null, List.of(
                new SchemaContext.Column("status", "smallint", false, "会员状态：1=正常 2=冻结 3=注销")));

        String ddl = SchemaDdlRenderer.render(List.of(withComment), List.of());

        assertThat(ddl).contains("-- 会员状态：1=正常 2=冻结 3=注销");
    }

    @Test
    @DisplayName("阶段 3 结论：两段可独立关闭，用来定位「过度 join」的来源")
    void joinSectionsCanBeDisabledIndependently() {
        List<SchemaContext.Table> tables = List.of(orders(), customers(), regions());
        List<JoinGraph.Edge> edges = List.of(
                new JoinGraph.Edge("orders", "customer_id", "customers", "customer_id",
                        JoinGraph.FOREIGN_KEY_STRENGTH),
                new JoinGraph.Edge("customers", "customer_state", "regions", "region_code",
                        JoinGraph.INFERRED_RELATION_STRENGTH));
        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(edges,
                List.of("orders", "customers", "regions"));

        String both = SchemaDdlRenderer.renderWithJoins(tables, edges, plan, true, true);
        String listOnly = SchemaDdlRenderer.renderWithJoins(tables, edges, plan, true, false);
        String planOnly = SchemaDdlRenderer.renderWithJoins(tables, edges, plan, false, true);
        String neither = SchemaDdlRenderer.renderWithJoins(tables, edges, plan, false, false);

        assertThat(both).contains("JOINS").contains("JOIN PLAN");
        assertThat(listOnly).contains("JOINS").doesNotContain("JOIN PLAN");
        assertThat(planOnly).doesNotContain("JOINS").contains("JOIN PLAN");
        // 两段都关时，输出必须与阶段 2 形态完全一致（只有表和列）。
        assertThat(neither).doesNotContain("JOINS").doesNotContain("JOIN PLAN");
        assertThat(neither).isEqualTo(SchemaDdlRenderer.render(tables, List.of()));
    }
}
