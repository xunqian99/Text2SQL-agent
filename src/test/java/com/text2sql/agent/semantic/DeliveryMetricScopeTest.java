package com.text2sql.agent.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryMetricScopeTest {
    @ParameterizedTest @ValueSource(strings = {"每家承运商的平均配送时长是多少天？", "各物流商配送天数是多少？",
            "各承运公司的送达时长是多少？", "shipments 的平均配送时长", "发货至送达平均配送时长是多少？",
            "SHIPMENTS 的 SHIPPED_AT 到 delivered_at 平均多少天？", "承 运 商 的 平 均 配 送 时 长 是 多 少？"})
    void carrierContextSelectsShipmentClockAndRetainsWhyOrderClockWasExcluded(String question) {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.findMentioned(question)).extracting(Metric::name)
                .contains("shipment_delivery_days").doesNotContain("delivery_days");
        var shipment = registry.matchEvidence(question).stream().filter(e -> e.metric().name().equals("shipment_delivery_days"))
                .findFirst().orElseThrow();
        assertThat(shipment.alias()).isNotBlank();
        assertThat(shipment.requiredContextTerm()).isNotBlank();
        assertThat(shipment.decision()).isEqualTo(MetricRegistry.MatchDecision.MATCHED);
        assertThat(registry.findApplicable(question, Set.of("SHIPMENTS"))).extracting(Metric::name)
                .contains("shipment_delivery_days");
        assertThat(registry.findApplicable(question, Set.of("orders"))).extracting(Metric::name)
                .doesNotContain("shipment_delivery_days", "delivery_days");
    }

    @ParameterizedTest @ValueSource(strings = {"每个州的平均配送时长是多少天？", "各州的平均配送时长（下单到签收）是多少天？",
            "平均配送天数是多少？", "各州物流时长是多少？"})
    void ordinaryOrderClockDoesNotInheritCarrierDefinition(String question) {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.findMentioned(question)).extracting(Metric::name)
                .contains("delivery_days").doesNotContain("shipment_delivery_days");
        var rejected = registry.matchEvidence(question).stream().filter(e -> e.metric().name().equals("shipment_delivery_days"))
                .findFirst().orElseThrow();
        assertThat(rejected.decision()).isIn(MetricRegistry.MatchDecision.REQUIRED_CONTEXT_MISSING,
                MetricRegistry.MatchDecision.EXCLUDED_CONTEXT);
    }

    @ParameterizedTest @ValueSource(strings = {"按承运商看平均配送时长，但明确采用下单到签收口径",
            "承运商配送时长按 order_delivered_customer_date 减 order_purchase_timestamp 计算"})
    void explicitOrderIntervalPreventsContradictingCarrierDefault(String question) {
        var registry = DimensionPolicyTest.registry();
        // When both contexts occur, omit both incompatible defaults; the original explicit question stays intact.
        assertThat(registry.findMentioned(question)).extracting(Metric::name)
                .doesNotContain("shipment_delivery_days", "delivery_days");
        assertThat(registry.matchEvidence(question)).filteredOn(e -> e.metric().name().equals("shipment_delivery_days"))
                .allSatisfy(e -> {
                    assertThat(e.decision()).isEqualTo(MetricRegistry.MatchDecision.EXCLUDED_CONTEXT);
                    assertThat(e.excludedContextTerm()).isNotBlank();
                });
    }

    @Test void carrierMentionWithoutDurationDoesNotInventMetric() {
        assertThat(DimensionPolicyTest.registry().findMentioned("每家承运商各承担了多少个发货单？"))
                .extracting(Metric::name).doesNotContain("shipment_delivery_days", "delivery_days");
    }

    @Test void noContextPreservesLegacyConstructionAndCompoundMetricIdentity() {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.matchEvidence(null)).isEmpty();
        assertThat(registry.matchEvidence(" ")).isEmpty();
        assertThat(registry.findMentioned("每家承运商的配送时长和复购率分别是多少？")).extracting(Metric::name)
                .contains("shipment_delivery_days", "repeat_rate").doesNotContain("delivery_days");
        var metric = new Metric("legacy", List.of("legacy"), "", "1", null, List.of(), "", null, null);
        assertThat(metric.requiredContextAny()).isEmpty();
        assertThat(metric.excludedContextAny()).isEmpty();
    }

    @Test void invalidScopeTermsCannotSilentlyCreateGlobalMatches() {
        assertThatThrownBy(() -> scoped(List.of(""), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped(List.of(), java.util.Arrays.asList((String) null)))
                .isInstanceOf(IllegalArgumentException.class);
        var required = new java.util.ArrayList<>(List.of("carrier"));
        var metric = scoped(required, List.of()); required.clear();
        assertThat(metric.requiredContextAny()).containsExactly("carrier");
    }

    @Test void shipmentDefinitionOwnsTheCorrectFactClockAndNullBoundary() {
        var metric = MetricUnitContractTest.metric("shipment_delivery_days");
        assertThat(metric.tables()).containsExactly("shipments");
        assertThat(metric.expression()).contains("s.delivered_at - s.shipped_at", "EPOCH", "86400.0", "::numeric")
                .doesNotContain("order_purchase_timestamp", "order_delivered_customer_date", "EXTRACT(DAY");
        assertThat(metric.filter()).contains("s.delivered_at IS NOT NULL", "s.shipped_at IS NOT NULL");
        assertThat(metric.render()).contains("shipments.carrier", "空集合返回NULL", "不使用订单下单/签收时间");
        assertThat(MetricUnitContractTest.metric("delivery_days").expression())
                .contains("o.order_delivered_customer_date - o.order_purchase_timestamp");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void fullHybridAndFallbackPathsAllRespectScopeAndSemanticSwitch(boolean fallback) {
        var props = new com.text2sql.agent.config.AgentProperties();
        props.getSemantic().setEnabled(true); props.getRetrieval().setJoinHintsEnabled(false);
        var shipment = new com.text2sql.agent.retrieval.SchemaContext.Table("shipments", "", List.of(
                new com.text2sql.agent.retrieval.SchemaContext.Column("carrier", "text", false, ""),
                new com.text2sql.agent.retrieval.SchemaContext.Column("shipped_at", "timestamp", true, ""),
                new com.text2sql.agent.retrieval.SchemaContext.Column("delivered_at", "timestamp", true, "")));
        var full = new com.text2sql.agent.retrieval.SchemaContext(List.of(shipment), List.of(), "", "CREATE TABLE shipments (...)");
        var catalog = mock(com.text2sql.agent.retrieval.SchemaCatalog.class); when(catalog.full()).thenReturn(full);
        var retriever = mock(com.text2sql.agent.retrieval.LexicalSchemaRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(new com.text2sql.agent.retrieval.RetrievalResult(
                List.of("shipments"), java.util.Map.of("shipments", 1.0), java.util.Map.of(), Set.of(), fallback));
        var registry = DimensionPolicyTest.registry();
        var providers = List.<com.text2sql.agent.retrieval.SchemaProvider>of(
                new com.text2sql.agent.retrieval.FullSchemaProvider(catalog, registry, props),
                new com.text2sql.agent.retrieval.HybridSchemaProvider(catalog, retriever,
                        mock(com.text2sql.agent.retrieval.glossary.GlossaryLoader.class), props, registry));
        for (var provider : providers) {
            String q = "每家承运商的平均配送时长是多少天？";
            assertThat(provider.provide(q).metricsText()).contains("METRIC shipment_delivery_days", "s.delivered_at - s.shipped_at")
                    .doesNotContain("METRIC delivery_days ", "o.order_purchase_timestamp");
            props.getSemantic().setEnabled(false);
            assertThat(provider.provide(q).metricsText()).isBlank();
            props.getSemantic().setEnabled(true);
        }
        assertThat(full.metricsText()).isBlank();
    }

    private static Metric scoped(List<String> required, List<String> excluded) {
        return new Metric("sample", List.of("sample"), "", "1", null, List.of(), "", null, null,
                null, null, required, excluded);
    }
}
