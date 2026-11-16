package com.text2sql.agent.budget;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.SqlExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetDatabaseTest {
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static final String URL = env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist");
    private static final String USER = env("TEXT2SQL_TEST_DB_USER", "text2sql_ro");
    private static final String PASSWORD = env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro");

    static boolean databaseAvailable() {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD)) { return connection.isValid(2); }
        catch (Exception unavailable) { return false; }
    }

    @Test @EnabledIf("databaseAvailable")
    void deadlineAbortsRealPostgresQueryRatherThanOnlyAbandoningItsJavaFuture() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD));
        var executor = new SqlExecutor(jdbc, new AgentProperties());
        String tag = "budget_" + UUID.randomUUID().toString().replace("-", "");
        long started = System.nanoTime();
        var result = RequestBudgetRunner.run("slow SQL", null, RequestBudgetTest.config(400), () -> {
            RequestBudget.stage("EXECUTION");
            executor.execute("SELECT pg_sleep(5) /* " + tag + " */");
            return RequestBudgetTest.answer();
        });
        assertThat(result.status()).isEqualTo(com.text2sql.agent.orchestrator.AgentResponse.Status.REQUEST_TIMEOUT);
        assertThat(result.timings().executionMs()).isGreaterThanOrEqualTo(300);
        assertThat(result.timings().executionMs()).isEqualTo(result.budget().stageMs().get("EXECUTION"));
        assertThat(result.timings().generationMs()).isZero();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
        // Check the actual server, not just a mock Statement.cancel invocation.
        int active = 1;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (active > 0 && System.nanoTime() < until) {
            active = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid() "
                    + "AND state = 'active' AND query LIKE ?", Integer.class, "%" + tag + "%");
            if (active > 0) Thread.sleep(20);
        }
        assertThat(active).as("timed-out SQL must disappear from server active queries").isZero();
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }
}
