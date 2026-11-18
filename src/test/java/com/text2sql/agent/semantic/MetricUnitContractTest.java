package com.text2sql.agent.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class MetricUnitContractTest {
    @ParameterizedTest @ValueSource(strings = {"repeat_rate", "on_time_rate", "refund_rate", "sell_through_rate",
            "member_penetration", "low_score_rate", "ctr", "freight_ratio", "sla_rate", "discount_rate",
            "conversion_rate", "retention_rate", "yoy_growth"})
    void percentageMetricsOwnTheirUnitAndPrecision(String name) {
        var metric = metric(name);
        assertThat(metric.outputUnit()).isEqualTo(Metric.OutputUnit.PERCENT);
        assertThat(metric.outputScale()).isEqualTo(2);
        assertThat(metric.render()).contains("METRIC " + name, "默认输出单位: PERCENT", "该指标", "用户明确指定单位时覆盖默认值")
                .contains("增长率可为负或超过100");
    }

    @Test void ordinaryCommissionRatioKeepsDecimalRatherThanInheritingPercentFromOtherMetrics() {
        var metric = metric("commission_rate");
        assertThat(metric.outputUnit()).isEqualTo(Metric.OutputUnit.DECIMAL_RATIO);
        assertThat(metric.outputScale()).isEqualTo(4);
        assertThat(metric.render()).contains("默认输出单位: DECIMAL_RATIO", "不额外乘100").doesNotContain("默认输出单位: PERCENT");
        assertThat(metric("aov").outputUnit()).isNull();
        assertThat(metric("gmv_with_freight").outputUnit()).isNull();
    }

    @Test void archivedDefinitionsStayUnchangedExceptDocumentedContractAndMetricBoundaryChanges() throws Exception {
        var mapper = new ObjectMapper(new YAMLFactory());
        var before = mapper.readTree(getClass().getResourceAsStream("/contracts/metrics-before-result-contract.yml"));
        var after = mapper.readTree(getClass().getResourceAsStream("/schema/metrics.yml"));
        // Customer population scope is checked independently in EntityPolicyTest; historical snapshot stays intact.
        ((com.fasterxml.jackson.databind.node.ObjectNode) before).remove("entityPolicies");
        ((com.fasterxml.jackson.databind.node.ObjectNode) after).remove("entityPolicies");
        int unitCount = 0;
        var metricEntries = (com.fasterxml.jackson.databind.node.ArrayNode) after.path("metrics");
        for (int i = metricEntries.size() - 1; i >= 0; i--) {
            if (java.util.Set.of("shipment_delivery_days", "late_delivery_rate", "customer_cohort")
                    .contains(metricEntries.get(i).path("name").asText())) {
                metricEntries.remove(i); // Independent scope and database contracts cover these added metrics.
            }
        }
        for (var entry : after.path("metrics")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) entry;
            String name = node.path("name").asText();
            var originalMetric = java.util.stream.StreamSupport.stream(before.path("metrics").spliterator(), false)
                    .filter(n -> n.path("name").asText().equals(name)).findFirst().orElseThrow();
            if (java.util.Set.of("aov", "refund_rate", "sell_through_rate", "low_score_rate", "ctr",
                    "freight_ratio", "sla_rate", "discount_rate", "commission_rate", "conversion_rate",
                    "retention_rate", "yoy_growth").contains(name)) {
                // Executed against empty, zero and positive denominators in MetricDivisionBoundaryTest.
                assertThat(node.path("expression").asText()).contains("NULLIF(");
                node.set("expression", originalMetric.path("expression"));
            }
            if (java.util.Set.of("retention_rate", "yoy_growth").contains(name)) {
                assertThat(node.has("queryPattern")).isFalse();
                assertThat(node.path("notes").asText()).doesNotContain("2017", "2018");
                for (String field : java.util.List.of("notes", "outputRule", "queryPattern")) {
                    node.set(field, originalMetric.path(field));
                }
            }
            if (name.equals("discount_rate")) {
                // MetricFactScopeTest executes the shipped query on unequal child cardinalities and equal amounts.
                for (String field : java.util.List.of("notes", "queryPattern")) {
                    node.set(field, originalMetric.path(field));
                }
            }
            if (name.equals("mau")) {
                // Remove copied review-rate exceptions; activity remains based on valid-order behavior.
                assertThat(node.path("notes").asText()).doesNotContain("低分率", "退款订单");
                node.set("notes", originalMetric.path("notes"));
            }
            if (name.equals("yoy_growth")) {
                assertThat(node.path("aliases").toString()).isEqualTo("[\"同比增长率\",\"YoY\"]");
                assertThat(node.path("requiredContextAny").toString())
                        .isEqualTo("[\"GMV\",\"成交总额\",\"商品交易总额\"]");
                node.set("aliases", originalMetric.path("aliases"));
                node.set("description", originalMetric.path("description"));
                node.remove("requiredContextAny");
            }
            if (node.path("name").asText().equals("gmv_with_freight")) {
                // FreightMetricScopeTest verifies the behavior; never rewrite the historical snapshot.
                assertThat(node.path("aliases").toString()).isEqualTo("[\"GMV\",\"成交总额\",\"商品交易总额\"]");
                var original = java.util.stream.StreamSupport.stream(before.path("metrics").spliterator(), false)
                        .filter(n -> n.path("name").asText().equals("gmv_with_freight")).findFirst().orElseThrow();
                node.set("aliases", original.path("aliases"));
            }
            if (node.path("name").asText().equals("delivery_days")) {
                node.remove("excludedContextAny");
                node.remove("preferredContextAny");
            }
            if (node.has("outputUnit")) { unitCount++; node.remove("outputUnit"); node.remove("outputScale"); }
            if (node.path("name").asText().equals("repeat_rate")) {
                assertThat(node.path("expression").asText()).contains("ROUND(", "NULLIF(COUNT(*), 0), 2)");
                var original = java.util.stream.StreamSupport.stream(before.path("metrics").spliterator(), false)
                        .filter(n -> n.path("name").asText().equals("repeat_rate")).findFirst().orElseThrow();
                node.set("expression", original.path("expression"));
            }
            if (node.path("name").asText().equals("on_time_rate")) {
                // Executed against status/time disagreement and empty inputs in EntityCoverageDatabaseTest.
                assertThat(node.path("expression").asText())
                        .contains("NULLIF(COUNT(*) FILTER (", "WHERE o.order_delivered_customer_date IS NOT NULL")
                        .doesNotContain("order_status");
                assertThat(node.path("notes").asText()).contains("分母为0返回NULL", "一行一订单");
                var original = java.util.stream.StreamSupport.stream(before.path("metrics").spliterator(), false)
                        .filter(n -> n.path("name").asText().equals("on_time_rate")).findFirst().orElseThrow();
                node.set("expression", original.path("expression"));
                node.set("notes", original.path("notes"));
            }
        }
        assertThat(unitCount).isEqualTo(14);
        assertThat(after).isEqualTo(before);
    }

    @Test void incompleteOrInvalidUnitPrecisionFailsAtConstruction() {
        assertThatThrownBy(() -> create(Metric.OutputUnit.PERCENT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(null, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(Metric.OutputUnit.PERCENT, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(Metric.OutputUnit.PERCENT, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThat(create(null, null).render()).doesNotContain("默认输出单位");
    }

    @Test void finalPromptKeepsMetricAndQuestionAsSeparateSourcesWithExplicitOverridePrecedence() {
        var registry = DimensionPolicyTest.registry();
        var applicable = registry.findApplicable("复购率是多少？以0到1的小数比率输出，保留4位小数", Set.of("orders", "customers"));
        var schema = new SchemaContext(List.of(), List.of(), "", "", applicable.stream().map(Metric::render).collect(java.util.stream.Collectors.joining("\n")));
        var template = new PromptTemplate();
        String question = "复购率是多少？以0到1的小数比率输出，保留4位小数";
        assertThat(template.userPrompt(schema, question)).contains("默认输出单位: PERCENT").endsWith(question + "\n");
        assertThat(template.systemPrompt()).contains("指标名称本身不算显式单位覆盖", "参考示例与推荐查询不能覆盖用户要求", "每个指标分别处理");
    }

    private static Metric create(Metric.OutputUnit unit, Integer scale) {
        return new Metric("sample", List.of("sample"), "", "1", null, List.of(), "", null, null, unit, scale);
    }
    static Metric metric(String name) {
        return DimensionPolicyTest.registry().all().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }
}
