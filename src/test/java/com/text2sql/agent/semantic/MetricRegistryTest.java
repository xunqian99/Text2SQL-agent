package com.text2sql.agent.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标注册表测试。**用真实的 metrics.yml**，不是内存里造的假数据。
 *
 * <p>理由：这一层的价值全在「口径写对了没有」。用假数据测只能验证匹配逻辑，
 * 验证不了「有效订单的口径是不是真的排除了 canceled/unavailable」。
 * 而后者才是阶段 4 要解决的问题。
 */
class MetricRegistryTest {

    private static MetricRegistry loaded() {
        MetricRegistry registry = new MetricRegistry();
        registry.load();
        return registry;
    }

    @Test
    @DisplayName("真实指标表能加载，且包含最高频的「有效订单」口径")
    void loadsRealMetricsFile() {
        MetricRegistry registry = loaded();

        assertThat(registry.all()).isNotEmpty();
        assertThat(registry.all()).anySatisfy(m -> {
            assertThat(m.name()).isEqualTo("valid_order");
            // 这条表达式是 17/25 道 T6 题的基础，写错了整层都错。
            assertThat(m.expression()).contains("canceled").contains("unavailable");
        });
    }

    @Test
    @DisplayName("按别名命中：问「复购率」能找到 repeat_rate")
    void findsMetricByAlias() {
        MetricRegistry registry = loaded();

        List<Metric> hits = registry.findMentioned("复购率是多少？");

        assertThat(hits).anySatisfy(m -> assertThat(m.name()).isEqualTo("repeat_rate"));
    }

    @Test
    @DisplayName("命中多个指标：复合问题不丢信息")
    void findsMultipleMetrics() {
        MetricRegistry registry = loaded();

        List<Metric> hits = registry.findMentioned("GMV 和客单价分别是多少？");

        assertThat(hits).extracting(Metric::name)
                .contains("gmv_with_freight", "aov");
    }

    @Test
    @DisplayName("依赖表不可用时过滤掉该指标，避免注入引用不存在表的表达式")
    void filtersByAvailableTables() {
        MetricRegistry registry = loaded();

        // 复购率的表达式依赖 customers（要按 customer_unique_id 聚合）。
        // 只给 orders 时必须把它滤掉，否则模型会照抄一个引用不存在表的 SQL。
        List<Metric> onlyOrders = registry.findApplicable("复购率是多少？", Set.of("orders"));
        assertThat(onlyOrders).noneMatch(m -> m.name().equals("repeat_rate"));

        List<Metric> withCustomers = registry.findApplicable("复购率是多少？",
                Set.of("orders", "customers"));
        assertThat(withCustomers).anySatisfy(m -> assertThat(m.name()).isEqualTo("repeat_rate"));
    }

    @Test
    @DisplayName("表名比较不区分大小写")
    void tableNameComparisonIsCaseInsensitive() {
        MetricRegistry registry = loaded();

        List<Metric> hits = registry.findApplicable("有效订单有多少？", Set.of("ORDERS"));

        assertThat(hits).anySatisfy(m -> assertThat(m.name()).isEqualTo("valid_order"));
    }

    @Test
    @DisplayName("没提到任何指标时返回空，不做无差别注入")
    void returnsEmptyWhenNothingMentioned() {
        MetricRegistry registry = loaded();

        assertThat(registry.findMentioned("2018 年有多少笔订单？")).isEmpty();
    }

    @Test
    @DisplayName("渲染出的文本包含表达式和口径说明，两者缺一不可")
    void rendersExpressionAndNotes() {
        MetricRegistry registry = loaded();
        Metric repeat = registry.all().stream()
                .filter(m -> m.name().equals("repeat_rate"))
                .findFirst()
                .orElseThrow();

        String text = repeat.render();

        assertThat(text).contains("METRIC repeat_rate");
        assertThat(text).contains("表达式:");
        // notes 里的关键提醒必须出现——只给表达式防不住「用错分母」这类错误。
        assertThat(text).contains("customer_unique_id");
    }

    @Test
    @DisplayName("每条指标都有 aliases、expression 和 tables，定义完整")
    void everyMetricIsComplete() {
        MetricRegistry registry = loaded();

        for (Metric metric : registry.all()) {
            assertThat(metric.aliases()).as("%s 缺 aliases", metric.name()).isNotEmpty();
            assertThat(metric.expression()).as("%s 缺 expression", metric.name()).isNotBlank();
            assertThat(metric.tables()).as("%s 缺 tables", metric.name()).isNotEmpty();
            assertThat(metric.notes()).as("%s 缺 notes", metric.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("指标名唯一")
    void metricNamesAreUnique() {
        MetricRegistry registry = loaded();

        assertThat(registry.all()).extracting(Metric::name).doesNotHaveDuplicates();
    }
}
