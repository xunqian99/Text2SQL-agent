package com.text2sql.agent.analysis;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.SqlExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-1 真实分析快照验收。不调用模型，只验证固定 SQL 在 Olist PostgreSQL 上能拿到业务结果。
 */
class SalesAnalysisDatabaseTest {

    private static final String URL = env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist");
    private static final String USER = env("TEXT2SQL_TEST_DB_USER", "text2sql_ro");
    private static final String PASSWORD = env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro");

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实 Olist 快照：总额、月份、品类、地区四种视图均能返回结果")
    void runsFourSalesViewsAgainstAnalysisSnapshot() {
        AgentProperties properties = new AgentProperties();
        properties.getDb().setMaxRows(200);
        var executor = new SqlExecutor(
                new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD)), properties);
        var service = new SalesAnalysisService(executor, new SalesAnalysisQueryBuilder());
        var context = new AnalysisContext(
                "integration-session", "integration-analysis",
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                "sha256:metric-v1");

        List<Long> elapsed = new ArrayList<>();
        List<Integer> rowCounts = new ArrayList<>();
        for (SalesAnalysisView view : SalesAnalysisView.values()) {
            SalesAnalysisResult result = service.analyze(context, view);
            assertThat(result.rows()).as(view + " should return data").isNotEmpty();
            assertThat(result.truncated()).as(view + " must fit the 200-row business limit").isFalse();
            assertThat(result.elapsedMs()).as(view + " query P95 guard").isLessThan(8_000);
            elapsed.add(result.elapsedMs());
            rowCounts.add(result.rows().size());
            if (view == SalesAnalysisView.TOTAL) {
                assertThat(new BigDecimal(result.rows().getFirst().getFirst())).isPositive();
            }
        }
        Collections.sort(elapsed);
        long p95 = elapsed.get((int) Math.ceil(elapsed.size() * 0.95) - 1);
        System.out.printf("B-1 real snapshot: views=4, rows=%s, elapsedMs=%s, p95Ms=%d, modelCalls=0, apiCost=0%n",
                rowCounts, elapsed, p95);
    }

    static boolean databaseAvailable() {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
