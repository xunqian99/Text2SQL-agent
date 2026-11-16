package com.text2sql.agent.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 抽取测试。
 *
 * 包装可以容错，SQL 正文不允许有损截断。测试同时固定成功、正文保留及拒绝契约。
 */
class SqlExtractorTest {

    @Test
    @DisplayName("纯 SQL 原样返回")
    void extractsPlainSql() {
        assertThat(SqlExtractor.extract("SELECT COUNT(*) FROM orders"))
                .isEqualTo("SELECT COUNT(*) FROM orders");
    }

    @Test
    @DisplayName("剥掉 markdown 代码围栏")
    void stripsMarkdownFence() {
        String raw = """
                ```sql
                SELECT COUNT(*) FROM orders
                ```""";

        assertThat(SqlExtractor.extract(raw)).isEqualTo("SELECT COUNT(*) FROM orders");
    }

    @Test
    @DisplayName("围栏后有解释不再静默取第一块")
    void rejectsProseOutsideFence() {
        String raw = """
                ```sql
                SELECT COUNT(*) FROM orders
                ```

                以上 SQL 统计了全部订单。
                """;

        assertThatThrownBy(() -> SqlExtractor.extract(raw))
                .isInstanceOf(GenerationException.class);
    }

    @Test
    @DisplayName("无围栏的解释保留给校验器拒绝，不猜测空行是 SQL 终点")
    void preservesTrailingProseForValidation() {
        String raw = """
                SELECT COUNT(*) FROM orders

                这条 SQL 统计了订单总数。
                """;

        assertThat(SqlExtractor.extract(raw)).isEqualTo(raw.strip());
        assertThat(validator().validate(SqlExtractor.extract(raw), schema()).valid()).isFalse();
    }

    @Test
    @DisplayName("多段 SQL（CTE）不会被误砍")
    void keepsMultiBlockSql() {
        String raw = """
                WITH paid AS (
                  SELECT order_id FROM payments
                )
                SELECT COUNT(*) FROM paid;
                """;

        String extracted = SqlExtractor.extract(raw);

        assertThat(extracted).contains("WITH paid AS");
        assertThat(extracted).contains("SELECT COUNT(*) FROM paid");
    }

    @Test
    @DisplayName("剥掉 SQL: 前缀")
    void stripsLeadingLabel() {
        assertThat(SqlExtractor.extract("SQL: SELECT 1")).isEqualTo("SELECT 1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "```", "```sql\n```", "SQL:", "```postgresql"})
    @DisplayName("空内容抛 UNPARSEABLE，且原因归类为模型质量问题")
    void throwsOnEmptyOutput(String raw) {
        assertThatThrownBy(() -> SqlExtractor.extract(raw))
                .isInstanceOf(GenerationException.class)
                .extracting(e -> ((GenerationException) e).getReason())
                .isEqualTo(GenerationException.Reason.UNPARSEABLE);
    }

    @ParameterizedTest
    @MethodSource("outerWrappers")
    @DisplayName("配对、单侧、大小写、CRLF 和标签包装均保留同一条 SQL")
    void normalizesOnlyOuterWrapper(String raw) {
        assertThat(SqlExtractor.extract(raw)).isEqualTo("SELECT 1;");
    }

    static Stream<String> outerWrappers() {
        return Stream.of("SELECT 1;", "SELECT 1;\n```", "```sql\nSELECT 1;",
                "```\nSELECT 1;\n```", "```sql\nSELECT 1;\n```",
                "```SQL\nSELECT 1;\n```", "```PostgreSQL\r\nSELECT 1;\r\n```",
                " \t```sql \r\nSELECT 1;\r\n \t``` \r\n",
                "Sql：SELECT 1;\n```", "SQL:\n```sql\nSELECT 1;\n```",
                "```sql\nSQL: SELECT 1;\n```", "SQL: SELECT 1;");
    }

    @ParameterizedTest
    @MethodSource("sqlBodies")
    @DisplayName("多行字面量、注释、续行和多语句正文原样保留")
    void preservesBody(String sql) {
        assertThat(SqlExtractor.extract(sql)).isEqualTo(sql);
        assertThat(SqlExtractor.extract("```sql\n" + sql + "\n```")).isEqualTo(sql);
        assertThat(SqlExtractor.extract(sql + "\n```")).isEqualTo(sql);
    }

    static Stream<String> sqlBodies() {
        return Stream.of(
                "SELECT 'a; DROP TABLE orders; ```' AS text;",
                "SELECT 'first\n\nsecond\n```\nlast' AS text;",
                "SELECT 'it''s ```' AS text;",
                "SELECT E'escaped\\\'```' AS text;",
                "SELECT 'C:\\' AS path, '```' AS text;",
                "SELECT $$first\n\n```\nsecond$$ AS text;",
                "SELECT $body$first\n```\n$other$second$body$ AS text;",
                "SELECT 1 AS \"a\"\"```b\";",
                "SELECT 1 /* outer /* nested */\n```\nend */;",
                "SELECT 1 -- ```\n;",
                "SELECT order_id FROM orders\n\nOFFSET 2 LIMIT 3;",
                "WITH paid AS (SELECT 1 AS id)\n\nSELECT * FROM paid;",
                "SELECT 1\n\nEXCEPT SELECT 2;",
                "SELECT 1;\n\nDROP TABLE orders;",
                "SELECT 1;\n\n-- explanation\nDELETE FROM orders;");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "```sql\nSELECT 1;\n```\n\n```sql\nDROP TABLE orders;\n```",
            "SELECT 1;\n```sql\nSELECT 2;\n```",
            "```sql\nSELECT 1;\n```\nDROP TABLE orders;",
            "说明：\n```sql\nSELECT 1;\n```",
            "```python\nSELECT 1;\n```",
            "```sql SELECT 1;```",
            "SELECT 1;\n```\nSQL: SELECT 2;"
    })
    @DisplayName("多个代码块、非边界围栏和未知语言均拒绝，不挑可通过的片段")
    void rejectsAmbiguousEnvelopes(String raw) {
        assertThatThrownBy(() -> SqlExtractor.extract(raw))
                .isInstanceOf(GenerationException.class)
                .extracting(e -> ((GenerationException) e).getReason())
                .isEqualTo(GenerationException.Reason.UNPARSEABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP TABLE orders;", "DELETE FROM orders;",
            "WITH removed AS (DELETE FROM orders RETURNING *) SELECT * FROM removed;",
            "SELECT 1;\n\nDELETE FROM orders;", "SELECT pg_sleep(10);"})
    @DisplayName("包装归一化不将危险 SQL 变成可执行查询")
    void keepsValidatorInControl(String sql) {
        String extracted = SqlExtractor.extract("```sql\n" + sql + "\n```");
        assertThat(extracted).isEqualTo(sql);
        assertThat(validator().validate(extracted, schema()).valid()).isFalse();
    }

    @Test
    @DisplayName("真实残留围栏样本清理后通过校验，不需要模型修正 COUNT DISTINCT")
    void recoversObservedCountDistinctWithoutChangingQuery() {
        String sql = "SELECT COUNT(DISTINCT order_status) AS status_count FROM orders;";
        ValidationResult result = validator().validate(SqlExtractor.extract(sql + "\n```"), schema());
        assertThat(result.valid()).isTrue();
        assertThat(result.sql()).isEqualTo(sql);
    }

    private static SqlValidator validator() {
        return new SqlValidator(new AgentProperties());
    }

    private static SchemaContext schema() {
        return new SchemaContext(List.of(new SchemaContext.Table("orders", null, List.of(
                new SchemaContext.Column("order_status", "text", false, null)))), List.of(), "", "");
    }
}
