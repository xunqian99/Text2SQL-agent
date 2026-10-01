package com.text2sql.agent.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表召回率计算与「期望表提取」的测试。
 *
 * <p>这组测试要钉死的是**期望表的提取口径**。它是整个检索评估的地基：
 * 如果提取错了，召回率数字就是错的，而错误的方向可能是「虚高」
 * （把不该算的表算成期望）或「虚低」（漏掉真实期望），两者都会
 * 让人朝错误的方向优化检索。
 *
 * <p>最值得关注的是 CTE 那两条。评估集里大量使用
 * {@code WITH xxx AS (...)}，如果解析器把 CTE 别名当成真实表，
 * 期望表里就会混进 {@code all_orders}、{@code ranked} 这种虚构名字，
 * 而它们**永远不可能被召回**，召回率会被系统性压低。
 * 实测 JSqlParser 5.4 会自动排除 CTE 别名，这两条测试就是把这个
 * 行为固定下来——它是外部库的行为，升级版本时可能变，必须有测试兜住。
 */
class TableRecallTest {

    @Test
    @DisplayName("单表：解析出唯一表名")
    void extractsSingleTable() {
        assertThat(TableRecall.expectedTables("SELECT COUNT(*) FROM orders"))
                .containsExactly("orders");
    }

    @Test
    @DisplayName("多表 join：全部解析出来")
    void extractsJoinedTables() {
        Set<String> tables = TableRecall.expectedTables("""
                SELECT o.order_id, c.customer_state
                FROM orders o
                JOIN customers c ON c.customer_id = o.customer_id
                LIMIT 10
                """);

        assertThat(tables).containsExactlyInAnyOrder("orders", "customers");
    }

    @Test
    @DisplayName("CTE 别名不能被当成真实表：否则期望表里会混进永远召不回的名字")
    void excludesCteAliases() {
        Set<String> tables = TableRecall.expectedTables("""
                WITH all_orders AS (
                    SELECT order_id FROM orders
                )
                SELECT * FROM all_orders
                """);

        assertThat(tables).containsExactly("orders");
        assertThat(tables).doesNotContain("all_orders");
    }

    @Test
    @DisplayName("CTE 里 join 多张表：CTE 名排除，真实表全部保留")
    void excludesCteAliasesButKeepsRealTables() {
        Set<String> tables = TableRecall.expectedTables("""
                WITH ranked AS (
                    SELECT o.customer_id, ROW_NUMBER() OVER (ORDER BY o.order_id) rn
                    FROM orders o
                )
                SELECT r.rn, c.customer_state
                FROM ranked r
                JOIN customers c ON c.customer_id = r.customer_id
                """);

        assertThat(tables).containsExactlyInAnyOrder("orders", "customers");
        assertThat(tables).doesNotContain("ranked");
    }

    @Test
    @DisplayName("表名统一转小写：避免大小写差异造成假漏召回")
    void lowercasesTableNames() {
        assertThat(TableRecall.expectedTables("SELECT * FROM ORDERS"))
                .containsExactly("orders");
    }

    @Test
    @DisplayName("解析失败返回空集合，而不是抛异常：一条坏 SQL 不该让整轮评估崩掉")
    void returnsEmptyOnParseFailure() {
        assertThat(TableRecall.expectedTables("SELECT FROM WHERE ???")).isEmpty();
        assertThat(TableRecall.expectedTables(null)).isEmpty();
        assertThat(TableRecall.expectedTables("   ")).isEmpty();
    }

    @Test
    @DisplayName("全召回：期望的两张表都在召回结果里")
    void fullRecallWhenAllExpectedTablesRetrieved() {
        TableRecall.Outcome outcome = TableRecall.evaluate(
                "SELECT * FROM orders o JOIN customers c ON c.customer_id = o.customer_id",
                List.of("orders", "customers", "products"));

        assertThat(outcome.fullRecall()).isTrue();
        assertThat(outcome.hit()).isEqualTo(2);
        assertThat(outcome.missing()).isEmpty();
    }

    @Test
    @DisplayName("部分召回：漏了一张表，fullRecall 为 false 但 hit 不为 0")
    void partialRecall() {
        TableRecall.Outcome outcome = TableRecall.evaluate(
                "SELECT * FROM orders o JOIN customers c ON c.customer_id = o.customer_id",
                List.of("orders"));

        assertThat(outcome.fullRecall()).isFalse();
        assertThat(outcome.missed()).isFalse();
        assertThat(outcome.hit()).isEqualTo(1);
        assertThat(outcome.missing()).containsExactly("customers");
    }

    @Test
    @DisplayName("完全漏召回：一张都没命中，这是最严重的失败类型")
    void completelyMissed() {
        TableRecall.Outcome outcome = TableRecall.evaluate(
                "SELECT * FROM orders",
                List.of("products", "brands"));

        assertThat(outcome.missed()).isTrue();
        assertThat(outcome.fullRecall()).isFalse();
        assertThat(outcome.hit()).isZero();
        assertThat(outcome.missing()).containsExactly("orders");
    }

    @Test
    @DisplayName("精确率：召回 3 张表命中 1 张，精确率是 1/3 而不是 1/1")
    void precisionPenalizesNoise() {
        TableRecall.Outcome outcome = TableRecall.evaluate(
                "SELECT * FROM orders",
                List.of("orders", "products", "brands"));

        assertThat(outcome.precision()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("召回的列名大小写不影响判定")
    void retrievedTableNamesAreCaseInsensitive() {
        TableRecall.Outcome outcome = TableRecall.evaluate(
                "SELECT * FROM orders",
                List.of("ORDERS"));

        assertThat(outcome.fullRecall()).isTrue();
    }
}
