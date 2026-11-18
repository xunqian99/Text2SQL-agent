package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricFactScopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"超时送达的订单占已送达订单的比例", "逾期送达订单占比",
            "延迟送达率", "延误送达的比率"})
    void lateRatioRetainsItsOwnIdentityAndNeedsOrders(String question) {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.findApplicable(question, Set.of("orders")))
                .extracting(Metric::name).contains("late_delivery_rate");
        assertThat(registry.findApplicable(question, Set.of("shipments")))
                .extracting(Metric::name).doesNotContain("late_delivery_rate");
        assertThat(registry.matchEvidence(question)).filteredOn(e -> e.metric().name().equals("late_delivery_rate"))
                .singleElement().satisfies(e -> {
                    assertThat(e.decision()).isEqualTo(MetricRegistry.MatchDecision.MATCHED);
                    assertThat(e.alias()).isNotBlank();
                    assertThat(e.requiredContextTerm()).isNotBlank();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"超时送达订单有多少", "逾期送达订单明细", "延迟送达的订单号"})
    void countingOrListingLateOrdersDoesNotInheritRatioOutput(String question) {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.findMentioned(question)).extracting(Metric::name).doesNotContain("late_delivery_rate");
        assertThat(registry.matchEvidence(question)).filteredOn(e -> e.metric().name().equals("late_delivery_rate"))
                .singleElement().satisfies(e -> assertThat(e.decision())
                        .isEqualTo(MetricRegistry.MatchDecision.REQUIRED_CONTEXT_MISSING));
    }

    record LateScenario(String name, String rows, String expected) {
        @Override public String toString() { return name; }
    }

    static Stream<LateScenario> lateScenarios() {
        String late = "TIMESTAMP '2024-02-03',TIMESTAMP '2024-02-02'";
        String onTime = "TIMESTAMP '2024-02-01',TIMESTAMP '2024-02-02'";
        return Stream.of(
                new LateScenario("status-disagrees-with-delivery-fact", "VALUES ('1','processing'," + late
                        + "),('2','delivered',NULL::timestamp,TIMESTAMP '2024-02-02'),('3','delivered'," + onTime + ")", "0.5"),
                new LateScenario("unknown-estimate-stays-in-denominator", "VALUES ('1','delivered'," + late
                        + "),('2','delivered',TIMESTAMP '2024-02-01',NULL::timestamp),('3','delivered'," + onTime + ")", "0.3333"),
                new LateScenario("status-does-not-filter-real-deliveries", "VALUES ('1',NULL::text," + late
                        + "),('2','canceled'," + onTime + ")", "0.5"),
                new LateScenario("empty", "SELECT NULL::text,NULL::text,NULL::timestamp,NULL::timestamp WHERE FALSE", null),
                new LateScenario("no-delivery-fact", "VALUES ('1','delivered',NULL::timestamp,NULL::timestamp)", null),
                new LateScenario("all-on-time", "VALUES ('1','delivered'," + onTime + ")", "0"),
                new LateScenario("all-late", "VALUES ('1','delivered'," + late + ")", "1"),
                new LateScenario("exact-promised-time-is-not-late", "VALUES ('1','delivered',TIMESTAMP '2024-02-02',TIMESTAMP '2024-02-02')", "0"));
    }

    @ParameterizedTest @MethodSource("lateScenarios") @EnabledIf("databaseAvailable")
    void lateExpressionUsesIndependentTimeConditions(LateScenario scenario) throws Exception {
        query("WITH orders(order_id,order_status,order_delivered_customer_date,order_estimated_delivery_date) AS ("
                + scenario.rows() + ") SELECT " + metric("late_delivery_rate").expression() + " FROM orders o",
                java.util.Collections.singletonList(scenario.expected()));
    }

    record DiscountScenario(String name, String secondStatus, boolean zero, boolean noCoupons,
                            boolean noItems, boolean nullDiscounts, List<String> expected) {
        @Override public String toString() { return name; }
    }

    private static List<String> expected(String gmv, String discount, String rate) {
        return java.util.Arrays.asList(gmv, discount, rate);
    }

    static Stream<DiscountScenario> discountScenarios() {
        return Stream.of(
                new DiscountScenario("two-coupons-and-two-items-equal-discounts", "delivered", false, false, false, false, expected("200", "30", "15")),
                new DiscountScenario("cancelled-order-excluded", "canceled", false, false, false, false, expected("100", "20", "20")),
                new DiscountScenario("unavailable-order-excluded", "unavailable", false, false, false, false, expected("100", "20", "20")),
                new DiscountScenario("unknown-status-keeps-existing-not-in-contract", null, false, false, false, false, expected("100", "20", "20")),
                new DiscountScenario("zero-gmv", "delivered", true, false, false, false, expected("0", "30", null)),
                new DiscountScenario("no-coupons", "delivered", false, true, false, false, expected(null, null, null)),
                new DiscountScenario("no-items", "delivered", false, false, true, false, expected(null, null, null)),
                new DiscountScenario("unknown-discount-is-not-zero", "delivered", false, false, false, true, expected("200", null, null)));
    }

    @ParameterizedTest @MethodSource("discountScenarios") @EnabledIf("databaseAvailable")
    void shippedDiscountQueryPreservesBothFactGrains(DiscountScenario scenario) throws Exception {
        String status = scenario.secondStatus() == null ? "NULL::text" : "'" + scenario.secondStatus() + "'";
        String discount = scenario.nullDiscounts() ? "NULL::numeric" : "10::numeric";
        String price = scenario.zero() ? "0" : "40";
        String freight = scenario.zero() ? "0" : "10";
        String secondPrice = scenario.zero() ? "0" : "100";
        String fixture = "WITH orders(order_id,order_status) AS (VALUES ('o1','delivered'),('o2'," + status + ")),"
                + " coupon_usages(usage_id,order_id,discount_amount) AS (SELECT * FROM (VALUES (1,'o1'," + discount
                + "),(2,'o1'," + discount + "),(3,'o2'," + discount + ")) x WHERE " + !scenario.noCoupons() + "),"
                + " order_items(order_id,order_item_id,price,freight_value) AS (SELECT * FROM (VALUES ('o1',1,"
                + price + "::numeric," + freight + "::numeric),('o1',2," + price + "," + freight + "),('o2',1,"
                + secondPrice + ",0)) x WHERE " + !scenario.noItems() + "), ";
        String pattern = metric("discount_rate").queryPattern().strip();
        assertThat(pattern).startsWith("WITH ");
        query(fixture + pattern.substring(5), scenario.expected());
    }

    @Test
    void finalDiscountContextKeepsPhysicalSourceColumnsFromTheRecommendedQuery() {
        var orders = table("orders", column("order_id", "text", true), column("order_status", "text", false));
        var items = table("order_items", column("order_id", "text", true), column("order_item_id", "integer", true),
                column("product_id", "text", false), column("price", "numeric", false), column("freight_value", "numeric", false));
        var coupons = table("coupon_usages", column("usage_id", "bigint", true), column("order_id", "text", false),
                column("coupon_id", "bigint", false), column("discount_amount", "numeric", false));
        var full = new SchemaContext(List.of(orders, items, coupons), List.of(), "", "");
        var catalog = mock(SchemaCatalog.class); var byName = new LinkedHashMap<String, SchemaContext.Table>();
        full.tables().forEach(t -> byName.put(t.name(), t));
        when(catalog.full()).thenReturn(full); when(catalog.tablesByName()).thenReturn(byName);
        var props = new AgentProperties(); props.getSemantic().setEnabled(true);
        props.getRetrieval().setColumnPruningEnabled(true); props.getRetrieval().setColumnPruningMinColumns(1);
        var glossary = new GlossaryLoader(catalog); var retriever = new LexicalSchemaRetriever(glossary, catalog, props);
        String question = "用券记录的折扣率是多少？";
        assertThat(retriever.retrieve(question).fellBack()).isFalse();
        var provider = new HybridSchemaProvider(catalog, retriever, glossary, props,
                DimensionPolicyTest.registry(), null, new ValueRetriever(glossary), new ColumnPruner(glossary));
        var context = provider.provide(question);
        assertThat(context.tables().stream().filter(t -> t.name().equals("order_items")).findFirst().orElseThrow().columns())
                .extracting(SchemaContext.Column::name).contains("price", "freight_value");
        assertThat(new PromptTemplate(props).userPrompt(context, question))
                .contains("coupon_totals", "order_totals", "discount_amount", "price", "freight_value")
                .doesNotContain("不要重复附加这个过滤");
    }

    @Test
    void subsetExceptionsMustComeFromTheCurrentMetricRatherThanAnyOtherFilter() {
        String gmv = metric("gmv_with_freight").render();
        assertThat(gmv).contains("order_status NOT IN", "用户明确覆盖此过滤", "当前指标说明", "类目、年份")
                .doesNotContain("仅某类商品", "则不要重复附加这个过滤");
        assertThat(metric("low_score_rate").render()).contains("退款订单", "不要再叠加订单状态过滤");
    }

    private static Metric metric(String name) {
        return DimensionPolicyTest.registry().all().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }
    private static SchemaContext.Table table(String name, SchemaContext.Column... columns) {
        return new SchemaContext.Table(name, null, List.of(columns));
    }
    private static SchemaContext.Column column(String name, String type, boolean key) {
        return new SchemaContext.Column(name, type, true, null, key);
    }
    private static void query(String sql, List<String> expected) throws Exception {
        try (var c = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            c.setReadOnly(true);
            try (var st = c.createStatement()) {
                st.setQueryTimeout(5);
                try (var rows = st.executeQuery(sql)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getMetaData().getColumnCount()).isEqualTo(expected.size());
                    for (int i = 0; i < expected.size(); i++) {
                        if (expected.get(i) == null) assertThat(rows.getBigDecimal(i + 1)).isNull();
                        else assertThat(rows.getBigDecimal(i + 1)).isEqualByComparingTo(expected.get(i));
                    }
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
