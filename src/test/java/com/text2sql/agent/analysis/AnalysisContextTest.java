package com.text2sql.agent.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisContextTest {

    @Test
    @DisplayName("创建上下文保留 session、analysis、指标、周期和指标指纹")
    void createsContextWithIdentityAndMetricFingerprint() {
        AnalysisContext context = baseContext();

        assertThat(context.sessionId()).isEqualTo("session-001");
        assertThat(context.analysisId()).isEqualTo("analysis-001");
        assertThat(context.metric().name()).isEqualTo("gmv_with_freight");
        assertThat(context.period()).isEqualTo(new AnalysisContext.Period("YEAR", "2018"));
        assertThat(context.metricDefinitionSha256()).isEqualTo("sha256:metric-v1");
    }

    @Test
    @DisplayName("追加维度不会丢失已有过滤条件")
    void addingDimensionKeepsExistingFilters() {
        AnalysisFilter filter = AnalysisFilter.eq("order_status", "delivered", AnalysisContext.Source.METRIC_DEFINITION);
        AnalysisContext context = baseContext().withFilter(filter);

        AnalysisContext drilled = context.withDimension("category", AnalysisContext.Source.USER_DIRECT);

        assertThat(drilled.dimensions()).extracting(AnalysisContext.DimensionRef::name)
                .containsExactly("category");
        assertThat(drilled.filters()).containsExactly(filter);
        assertThat(context.dimensions()).isEmpty();
    }

    @Test
    @DisplayName("重复追加同一维度保持幂等，不产生重复值")
    void addingSameDimensionIsIdempotent() {
        AnalysisContext context = baseContext()
                .withDimension("category", AnalysisContext.Source.USER_DIRECT)
                .withDimension("category", AnalysisContext.Source.INHERITED);

        assertThat(context.dimensions()).hasSize(1);
        assertThat(context.dimensions().getFirst().source()).isEqualTo(AnalysisContext.Source.USER_DIRECT);
    }

    @Test
    @DisplayName("同字段过滤按契约替换旧值")
    void addingSameFieldFilterReplacesPreviousValue() {
        AnalysisContext context = baseContext()
                .withFilter(AnalysisFilter.eq("category", "beleza_saude", AnalysisContext.Source.INHERITED))
                .withFilter(AnalysisFilter.eq("category", "informatica", AnalysisContext.Source.USER_DIRECT));

        assertThat(context.filters()).containsExactly(
                AnalysisFilter.eq("category", "informatica", AnalysisContext.Source.USER_DIRECT));
    }

    @Test
    @DisplayName("外部列表修改不会污染上下文内部状态")
    void copiesInputListsDefensively() {
        List<AnalysisContext.DimensionRef> dimensions = new ArrayList<>();
        dimensions.add(new AnalysisContext.DimensionRef("category", AnalysisContext.Source.USER_DIRECT));
        List<AnalysisFilter> filters = new ArrayList<>();
        filters.add(AnalysisFilter.eq("category", "beleza_saude", AnalysisContext.Source.USER_DIRECT));

        AnalysisContext context = new AnalysisContext(
                "session-001", "analysis-001",
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), dimensions, filters, null,
                "sha256:metric-v1");
        dimensions.clear();
        filters.clear();

        assertThat(context.dimensions()).hasSize(1);
        assertThat(context.filters()).hasSize(1);
        assertThatThrownBy(() -> context.dimensions().add(
                new AnalysisContext.DimensionRef("region", AnalysisContext.Source.USER_DIRECT)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("空身份和空指标被拒绝")
    void rejectsBlankIdentityAndMetric() {
        assertThatThrownBy(() -> new AnalysisContext(
                "", "analysis-001",
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                "sha256:metric-v1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AnalysisContext(
                "session-001", "analysis-001",
                new AnalysisContext.MetricRef(" ", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                "sha256:metric-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("上下文要求有效的分析身份和指标定义指纹")
    void rejectsMissingAnalysisIdAndMetricFingerprint() {
        assertThatThrownBy(() -> new AnalysisContext(
                "session-001", null,
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                "sha256:metric-v1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AnalysisContext(
                "session-001", "analysis-001",
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AnalysisContext baseContext() {
        return new AnalysisContext(
                "session-001", "analysis-001",
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                "sha256:metric-v1");
    }
}
