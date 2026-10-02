package com.text2sql.agent.validation;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验层测试。
 *
 * <p>这组测试的价值不在「覆盖了多少行代码」，而在它把校验层的**契约**固定下来：
 * 什么必须被拦、什么必须放行、放行时 SQL 会被改成什么样。
 * 阶段 5 会扩充这个类，届时这些用例就是回归基线——如果某个改动让
 * 「DROP TABLE 能被拦」变成通不过，说明护栏被削弱了，会立刻暴露。
 *
 * <p>测试数据用最小的 schema（一张 orders 表），不连数据库。
 * 校验是纯计算，不需要数据库——这是刻意的分层结果：
 * 如果校验层依赖数据库，它就没法被单独测试，也没法在数据库没起来时验证。
 */
class SqlValidatorTest {

    private SqlValidator validator;
    private SchemaContext schema;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        validator = new SqlValidator(properties);
        schema = new SchemaContext(
                List.of(new SchemaContext.Table("orders", "订单表", List.of(
                        new SchemaContext.Column("order_id", "text", false, null),
                        new SchemaContext.Column("order_status", "text", false, null),
                        new SchemaContext.Column("order_purchase_timestamp", "timestamp", false, null)))),
                List.of(),
                "",
                "");
    }

    @Test
    @DisplayName("合法 SELECT 通过校验")
    void allowsPlainSelect() {
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders LIMIT 10", schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.sql()).contains("SELECT");
    }

    @Test
    @DisplayName("DROP 被拦截：prompt 约束不可信，必须靠校验")
    void rejectsDrop() {
        ValidationResult result = validator.validate("DROP TABLE orders", schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .containsExactly(ValidationResult.Code.NOT_SELECT);
    }

    @Test
    @DisplayName("UPDATE / DELETE / INSERT 全部被拦截")
    void rejectsWrites() {
        for (String sql : List.of(
                "UPDATE orders SET order_status = 'x'",
                "DELETE FROM orders",
                "INSERT INTO orders (order_id) VALUES ('1')",
                "TRUNCATE orders")) {
            assertThat(validator.validate(sql, schema).valid())
                    .as("应该拦截：%s", sql)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("多语句被拦截：第二条语句最危险，必须整体拒绝")
    void rejectsMultipleStatements() {
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders LIMIT 1; DROP TABLE orders", schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.MULTIPLE_STATEMENTS);
    }

    @Test
    @DisplayName("字符串字面量里的分号不误判为多语句")
    void semicolonInsideLiteralIsNotMultipleStatements() {
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders WHERE order_status = 'a;b' LIMIT 1", schema);

        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .doesNotContain(ValidationResult.Code.MULTIPLE_STATEMENTS);
    }

    @Test
    @DisplayName("危险函数被拦截，且藏在子查询里也能被找到")
    void rejectsForbiddenFunctionEvenInSubquery() {
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders WHERE order_id IN "
                        + "(SELECT order_id FROM orders WHERE pg_sleep(60) IS NOT NULL) LIMIT 1",
                schema);

        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.FORBIDDEN_FUNCTION);
    }

    @Test
    @DisplayName("危险函数藏在 FROM 子查询、CTE、SELECT 列里同样被拦截")
    void rejectsForbiddenFunctionInEveryPosition() {
        for (String sql : List.of(
                "SELECT x FROM (SELECT pg_sleep(60) AS x FROM orders) t LIMIT 1",
                "WITH slow AS (SELECT pg_sleep(60) AS x FROM orders) SELECT * FROM slow LIMIT 1",
                "SELECT pg_read_file('/etc/passwd') FROM orders LIMIT 1")) {
            assertThat(validator.validate(sql, schema).violations())
                    .as("应该拦截：%s", sql)
                    .extracting(ValidationResult.Violation::code)
                    .contains(ValidationResult.Code.FORBIDDEN_FUNCTION);
        }
    }

    @Test
    @DisplayName("臆造表名被拦截：让错误在执行前暴露")
    void rejectsUnknownTable() {
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orderz LIMIT 1", schema);

        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.UNKNOWN_TABLE);
    }

    @Test
    @DisplayName("缺少 LIMIT 时自动补上，并把改写后的 SQL 返回")
    void appendsLimitWhenMissing() {
        ValidationResult result = validator.validate("SELECT order_id FROM orders", schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.rewritten()).isTrue();
        // 关键断言：返回的 sql 就是真正要执行的 sql，不是原始输入。
        assertThat(result.sql().toUpperCase()).contains("LIMIT 200");
    }

    @Test
    @DisplayName("单行聚合结果不强制补 LIMIT")
    void doesNotRequireLimitForAggregateWithoutGroupBy() {
        ValidationResult result = validator.validate("SELECT COUNT(*) FROM orders", schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.rewritten()).isFalse();
    }

    @Test
    @DisplayName("带 GROUP BY 的聚合仍然需要 LIMIT")
    void requiresLimitForAggregateWithGroupBy() {
        ValidationResult result = validator.validate(
                "SELECT order_status, COUNT(*) FROM orders GROUP BY order_status", schema);

        assertThat(result.rewritten()).isTrue();
        assertThat(result.sql().toUpperCase()).contains("LIMIT");
    }

    @Test
    @DisplayName("UNION 这类非 PlainSelect 语句被补上 LIMIT，而不是抛 ClassCastException")
    void appendsLimitToUnionInsteadOfCrashing() {
        // 回归用例：JSqlParser 的 getPlainSelect() 内部是裸强转，
        // UNION 会被解析成 SetOperationList，强转直接抛 ClassCastException。
        // 这个异常曾经穿透到 ApplicationRunner，让整轮 100 条评估在第 99 条崩掉。
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders UNION SELECT order_id FROM orders", schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.rewritten()).isTrue();
        assertThat(result.sql().toUpperCase()).contains("UNION").contains("LIMIT 200");
    }

    @Test
    @DisplayName("UNION 已经有 LIMIT 时不重复改写")
    void doesNotRewriteUnionThatAlreadyHasLimit() {
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders UNION SELECT order_id FROM orders LIMIT 5", schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.rewritten()).isFalse();
    }

    @Test
    @DisplayName("UNION + ORDER BY + LIMIT 也不重复改写")
    void doesNotRewriteUnionWithOrderByAndLimit() {
        // 解析器在这里是不对称的：带 ORDER BY 时 LIMIT 挂在外层
        // SetOperationList 上，不带 ORDER BY 时挂最后一个子查询上。
        // 两种形态都必须识别为「已有 LIMIT」，否则会被拼成
        // "... LIMIT 5 LIMIT 200"，PostgreSQL 直接报 42601。
        ValidationResult result = validator.validate(
                "SELECT order_id FROM orders UNION SELECT order_id FROM orders ORDER BY order_id LIMIT 5",
                schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.rewritten()).isFalse();
        assertThat(result.sql().toUpperCase()).doesNotContain("LIMIT 200");
    }

    @Test
    @DisplayName("UNION 两侧各自带 LIMIT 时，外层仍会补一个整体 LIMIT")
    void appendsOuterLimitWhenOnlyInnerSelectsHaveLimit() {
        // 两侧的 LIMIT 只限制各自分支，整体结果仍可能很大，所以外层必须补。
        // 这个用例锁住的是「不要为了修重复 LIMIT 而把补 LIMIT 一起关掉」。
        ValidationResult result = validator.validate(
                "(SELECT order_id FROM orders LIMIT 3) UNION (SELECT order_id FROM orders LIMIT 5)",
                schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.rewritten()).isTrue();
        assertThat(result.sql().toUpperCase()).contains("LIMIT 200");
    }

    @Test
    @DisplayName("FROM 中括号包 UNION 时安全函数遍历不崩溃")
    void traversesParenthesizedUnionWithoutCrashing() {
        ValidationResult result = validator.validate(
                "SELECT x.order_id FROM ("
                        + "SELECT order_id FROM orders UNION SELECT order_id FROM orders"
                        + ") x LIMIT 1",
                schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.sql()).contains("LIMIT 1");
    }

    @Test
    @DisplayName("REJECT 模式下缺 LIMIT 被判定非法")
    void rejectsMissingLimitInRejectMode() {
        AgentProperties properties = new AgentProperties();
        properties.getGuard().setLimitMode(AgentProperties.Guard.LimitMode.REJECT);
        SqlValidator rejectValidator = new SqlValidator(properties);

        ValidationResult result = rejectValidator.validate("SELECT order_id FROM orders", schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.MISSING_LIMIT);
    }

    @Test
    @DisplayName("解析不了的 SQL 一律拒绝（fail-closed）")
    void rejectsUnparseableSql() {
        ValidationResult result = validator.validate("SELEC order_id FROM orders", schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.PARSE_ERROR);
    }

    @Test
    @DisplayName("空 SQL 被拒绝")
    void rejectsEmptySql() {
        assertThat(validator.validate("   ", schema).violations())
                .extracting(ValidationResult.Violation::code)
                .contains(ValidationResult.Code.EMPTY);
    }
}
