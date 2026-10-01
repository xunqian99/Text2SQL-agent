package com.text2sql.agent.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 抽取测试。
 *
 * <p>这个类处理的是「模型的合理但不合要求的行为」：加 markdown 围栏、
 * 附一句解释、加个 {@code SQL:} 前缀。这些都是真实模型的高频输出，
 * 把它们判为失败会人为压低准确率，而且压低的是「模型其实答对了」的那部分。
 *
 * <p>测试的重点因此是：**容错要够宽，但不能把解释文字混进 SQL**。
 * 前者影响准确率，后者影响安全性。
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
    @DisplayName("围栏后带解释时只取第一块")
    void takesOnlyFirstFence() {
        String raw = """
                ```sql
                SELECT COUNT(*) FROM orders
                ```

                以上 SQL 统计了全部订单。
                """;

        assertThat(SqlExtractor.extract(raw)).isEqualTo("SELECT COUNT(*) FROM orders");
    }

    @Test
    @DisplayName("砍掉 SQL 之后的中文解释")
    void cutsTrailingProse() {
        String raw = """
                SELECT COUNT(*) FROM orders

                这条 SQL 统计了订单总数。
                """;

        assertThat(SqlExtractor.extract(raw)).isEqualTo("SELECT COUNT(*) FROM orders");
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

    @Test
    @DisplayName("空内容抛 UNPARSEABLE，且原因归类为模型质量问题")
    void throwsOnEmptyOutput() {
        assertThatThrownBy(() -> SqlExtractor.extract("   "))
                .isInstanceOf(GenerationException.class)
                .extracting(e -> ((GenerationException) e).getReason())
                .isEqualTo(GenerationException.Reason.UNPARSEABLE);
    }
}
