package com.text2sql.agent.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevent business defaults from being injected into ordinary, user-defined averages. */
class MetricAliasScopeTest {
    private static final Set<String> TABLES = Set.of("orders", "order_items", "customers");

    @ParameterizedTest
    @ValueSource(strings = {
            "按订单明细商品金额计算每日平均订单金额",
            "统计全部订单的平均订单金额，包含取消订单",
            "不含运费的订单均价是多少",
            "含运费的平均订单金额按月份展示",
            "按支付金额计算订单均价",
            "平均 订单 金额是多少"
    })
    void descriptiveAveragesDoNotAcquireAovDefaults(String question) {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.matchEvidence(question))
                .noneMatch(e -> e.metric().name().equals("aov"));
        assertThat(registry.findApplicable(question, TABLES))
                .noneMatch(m -> m.name().equals("aov"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"客单价是多少", "按季度看客 单 价", "GMV和客单价分别是多少"})
    void explicitBusinessMetricKeepsItsExistingDefinition(String question) {
        var metric = DimensionPolicyTest.registry().findApplicable(question, TABLES).stream()
                .filter(m -> m.name().equals("aov")).findFirst().orElseThrow();
        assertThat(metric.expression()).contains("oi.price + oi.freight_value", "COUNT(DISTINCT o.order_id)");
        assertThat(metric.filter()).isEqualTo("o.order_status NOT IN ('canceled', 'unavailable')");
    }

    @Test
    void compoundRequestKeepsIndependentlyMentionedMetrics() {
        var metrics = DimensionPolicyTest.registry().findApplicable(
                "平均订单金额按商品金额计算，同时统计GMV和有效订单数", TABLES);
        assertThat(metrics).extracting(Metric::name)
                .contains("gmv_with_freight", "valid_order").doesNotContain("aov");
    }

    @Test
    void explicitAovElsewhereInCompoundRequestIsNotSilentlySuppressed() {
        assertThat(DimensionPolicyTest.registry().findApplicable(
                "分别给出全部订单的平均订单金额和项目定义的客单价", TABLES))
                .extracting(Metric::name).contains("aov");
        // Per-output scope remains the generator's responsibility; alias narrowing is no semantic parser.
    }

    @Test
    void explicitMetricStillRequiresItsSchemaDependencies() {
        assertThat(DimensionPolicyTest.registry().findApplicable("客单价", Set.of("orders")))
                .extracting(Metric::name).doesNotContain("aov");
    }
}
