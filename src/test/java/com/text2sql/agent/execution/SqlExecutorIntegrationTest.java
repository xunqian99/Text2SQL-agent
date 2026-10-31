package com.text2sql.agent.execution;

import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 连真实 PostgreSQL 的集成测试：验证执行层的两道硬边界。
 *
 * <p><b>为什么这个测试必须连真库，不能 mock</b>：这里要验证的是「JDBC 驱动 +
 * 数据库」怎么配合，而不是 Java 逻辑。具体说，{@code truncated} 这个标志
 * 依赖「驱动最多返回 {@code setMaxRows} 行」这个真实行为；用 mock 的话，
 * 我们只能验证自己写的判断式，验证不了前提。
 *
 * <p>这条测试的价值在于钉住一个**曾经的真实 bug**：校验层补的 LIMIT 恰好
 * 等于行数上限，导致数据库最多只返回 maxRows 行，执行层无论怎么多取一行
 * 都取不到，{@code truncated} 永远是 false——调用方以为拿全了数据。
 * 修法是校验层补 {@code maxRows + 1}。
 *
 * <p><b>没有数据库时自动跳过</b>：用 {@link EnabledIf} 探测连通性，
 * 所以 CI 或别人的机器上不会因为缺库而红。连接参数可用环境变量覆盖。
 */
class SqlExecutorIntegrationTest {

    private static final String URL = env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist");
    private static final String USER = env("TEXT2SQL_TEST_DB_USER", "text2sql_ro");
    private static final String PASSWORD = env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 供 {@code @EnabledIf} 调用：库连不上就跳过，而不是失败。 */
    static boolean databaseAvailable() {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static SqlExecutor executorWithMaxRows(int maxRows) {
        var properties = new AgentProperties();
        properties.getDb().setMaxRows(maxRows);
        var dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
        return new SqlExecutor(new JdbcTemplate(dataSource), properties);
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：结果超过 maxRows 时 truncated=true，且只返回 maxRows 行")
    void marksTruncatedWhenResultExceedsMaxRows() {
        // geolocation 有 100 万行，用它构造「确实还有更多」的场景最稳。
        QueryResult result = executorWithMaxRows(5).execute("SELECT * FROM geolocation LIMIT 6");

        assertThat(result.truncated()).as("多出来的那一行必须让标志置位").isTrue();
        assertThat(result.rows()).as("对外仍然只返回 maxRows 行").hasSize(5);
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：结果恰好等于 maxRows 时 truncated=false")
    void doesNotMarkTruncatedAtExactLimit() {
        QueryResult result = executorWithMaxRows(5).execute("SELECT * FROM geolocation LIMIT 5");

        assertThat(result.rows()).hasSize(5);
        assertThat(result.truncated()).as("恰好这么多，不该谎报还有更多").isFalse();
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：只读账号可以查，但不能建表")
    void readonlyAccountCannotWrite() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD));

        // 这条断言同时是「三层防线第三层真的生效」的证据：
        // 即使 AST 校验被绕过，数据库层面也写不进去。
        assertThatThrownBy(() -> jdbc.update("CREATE TABLE should_not_exist(id int)"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：校验层补的 LIMIT 必须让执行层能判定截断（端到端）")
    void validatorAppendedLimitStillAllowsTruncationDetection() {
        // 这条用例锁的是那个真实 bug 的**组合行为**，而不是执行层单点：
        // 如果校验层只补 maxRows（200），数据库最多返回 200 行，
        // 执行层再怎么多取一行都取不到，truncated 永远是 false。
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD));
        var properties = new AgentProperties();
        properties.getDb().setMaxRows(200);

        var reader = new com.text2sql.agent.retrieval.DatabaseSchemaReader(jdbc);
        var schema = new com.text2sql.agent.retrieval.SchemaContext(
                reader.readTables(), reader.readForeignKeys(), "", "");

        var validator = new com.text2sql.agent.validation.SqlValidator(properties);
        var validation = validator.validate("SELECT * FROM geolocation", schema);

        assertThat(validation.valid()).isTrue();
        assertThat(validation.rewritten()).as("模型没写 LIMIT，校验层应补上").isTrue();
        assertThat(validation.sql().toUpperCase()).contains("LIMIT 201");

        QueryResult result = new SqlExecutor(jdbc, properties).execute(validation.sql());
        assertThat(result.truncated()).as("必须能判定出还有更多行").isTrue();
        assertThat(result.rows()).hasSize(200);
    }
}
