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

    @Test void archivedDefinitionsStayUnchangedExceptDocumentedUnitRepeatDeliveryAndEntityScopeAdditions() throws Exception {
        var mapper = new ObjectMapper(new YAMLFactory());
        var before = mapper.readTree(getClass().getResourceAsStream("/contracts/metrics-before-result-contract.yml"));
        var after = mapper.readTree(getClass().getResourceAsStream("/schema/metrics.yml"));
        // Customer population scope is checked independently in EntityPolicyTest; historical snapshot stays intact.
        ((com.fasterxml.jackson.databind.node.ObjectNode) before).remove("entityPolicies");
        ((com.fasterxml.jackson.databind.node.ObjectNode) after).remove("entityPolicies");
        int unitCount = 0;
        var metricEntries = (com.fasterxml.jackson.databind.node.ArrayNode) after.path("metrics");
        for (int i = metricEntries.size() - 1; i >= 0; i--) {
            if (metricEntries.get(i).path("name").asText().equals("shipment_delivery_days")) {
                metricEntries.remove(i); // Its independent scope/numeric contract is covered by DeliveryMetricScopeTest.
            }
        }
        for (var entry : after.path("metrics")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) entry;
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
