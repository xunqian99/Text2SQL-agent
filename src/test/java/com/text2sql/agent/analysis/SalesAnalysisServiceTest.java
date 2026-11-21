package com.text2sql.agent.analysis;

import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesAnalysisServiceTest {

    @Test
    @DisplayName("服务执行结构化品类下钻并只返回业务结果")
    void executesCategoryDrilldownAndReturnsBusinessResult() {
        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.execute(contains("AS category"))).thenReturn(new QueryResult(
                List.of("category", "metric_value"),
                List.of(List.of("beleza_saude", "125.50")), false, 12));
        SalesAnalysisService service = new SalesAnalysisService(executor, new SalesAnalysisQueryBuilder());

        SalesAnalysisResult result = service.analyze(context(), SalesAnalysisView.CATEGORY);

        assertThat(result.analysisId()).isEqualTo("analysis-001");
        assertThat(result.view()).isEqualTo(SalesAnalysisView.CATEGORY);
        assertThat(result.columns()).containsExactly("category", "metric_value");
        assertThat(result.rows()).containsExactly(List.of("beleza_saude", "125.50"));
        assertThat(result.elapsedMs()).isEqualTo(12);
        verify(executor).execute(contains("ORDER BY metric_value DESC"));
    }

    @Test
    @DisplayName("业务结果保留 NULL 维度值，不把合法空值当成系统异常")
    void preservesNullDimensionValues() {
        SqlExecutor executor = mock(SqlExecutor.class);
        when(executor.execute(contains("AS region"))).thenReturn(new QueryResult(
                List.of("region", "metric_value"),
                List.of(Arrays.asList(null, "1.0")), true, 8));
        SalesAnalysisService service = new SalesAnalysisService(executor, new SalesAnalysisQueryBuilder());

        SalesAnalysisResult result = service.analyze(context(), SalesAnalysisView.REGION);

        assertThat(result.rows()).containsExactly(Arrays.asList(null, "1.0"));
        assertThat(result.truncated()).isTrue();
    }

    private static AnalysisContext context() {
        return new AnalysisContext(
                "session-001", "analysis-001",
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                "sha256:metric-v1");
    }
}
