package com.text2sql.agent.validation;

import com.text2sql.agent.execution.QueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultCheckerTest {

    private ResultChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ResultChecker();
    }

    @Test
    @DisplayName("规则 1：过滤查询返回 0 行，判定为异常")
    void emptyResultWithWhereClause() {
        QueryResult empty = new QueryResult(List.of("id"), List.of(), false, 5L);
        CheckResult res = checker.check("统计有效订单数", "SELECT * FROM orders WHERE status = 'X'", empty);

        assertThat(res.suspicious()).isTrue();
        assertThat(res.rule()).isEqualTo("EMPTY_RESULT");
    }

    @Test
    @DisplayName("规则 2：非分组聚合返回 NULL，判定为异常")
    void pureAggregateReturnsNull() {
        QueryResult nullRow = new QueryResult(List.of("sum"), List.of(List.of("null")), false, 5L);
        CheckResult res = checker.check("所有商品的总金额", "SELECT SUM(price) FROM order_items", nullRow);

        assertThat(res.suspicious()).isTrue();
        assertThat(res.rule()).isEqualTo("ALL_NULL_AGGREGATE");
    }

    @Test
    @DisplayName("规则 3：比例/比率计算结果超出 [0, 100]，判定为异常")
    void ratioOutOfBounds() {
        QueryResult outOfBounds = new QueryResult(List.of("pct"), List.of(List.of("150.25")), false, 5L);
        CheckResult res = checker.check("各卖家的退款率是多少？", "SELECT 150.25 FROM sellers", outOfBounds);

        assertThat(res.suspicious()).isTrue();
        assertThat(res.rule()).isEqualTo("RATIO_OUT_OF_BOUNDS");
    }

    @Test
    @DisplayName("规则 4：金额、数量等指标出现负数，判定为异常")
    void negativeMetricValue() {
        QueryResult neg = new QueryResult(List.of("amount"), List.of(List.of("-320.5")), false, 5L);
        CheckResult res = checker.check("计算各区域的销售总金额", "SELECT -320.5 FROM orders", neg);

        assertThat(res.suspicious()).isTrue();
        assertThat(res.rule()).isEqualTo("NEGATIVE_METRIC_VALUE");
    }

    @Test
    @DisplayName("规则 5：JOIN 缺少 ON/USING 关联条件，判定为疑似笛卡尔积")
    void missingJoinCondition() {
        QueryResult rows = new QueryResult(List.of("id"), List.of(List.of("1")), false, 5L);
        CheckResult res = checker.check("查询所有客户", "SELECT * FROM orders JOIN customers", rows);

        assertThat(res.suspicious()).isTrue();
        assertThat(res.rule()).isEqualTo("MISSING_JOIN_CONDITION");
    }

    @Test
    @DisplayName("规则 6：问题要求按月统计但缺少时间维度与分组，判定为异常")
    void missingTimeDimension() {
        QueryResult rows = new QueryResult(List.of("rev"), List.of(List.of("1000.0")), false, 5L);
        CheckResult res = checker.check("每个月的佣金收入是多少？", "SELECT SUM(commission) FROM settlements", rows);

        assertThat(res.suspicious()).isTrue();
        assertThat(res.rule()).isEqualTo("MISSING_TIME_DIMENSION");
    }

    @Test
    @DisplayName("规则 7：问题询问金额但仅用 COUNT 未用 SUM，判定为异常")
    void countInsteadOfSumForMoney() {
        QueryResult rows = new QueryResult(List.of("type", "cnt"), List.of(List.of("credit_card", "100")), false, 5L);
        CheckResult res = checker.check("每个支付方式收上来的钱合计是多少？", "SELECT payment_type, COUNT(*) FROM order_payments GROUP BY 1", rows);

        assertThat(res.suspicious()).isTrue();
        assertThat(res.rule()).isEqualTo("COUNT_INSTEAD_OF_SUM");
    }

    @Test
    @DisplayName("规则 8：退款金额误聚合数量列 refund_qty，判定为异常")
    void sumQtyInsteadOfValue() {
        QueryResult rows = new QueryResult(List.of("amt"), List.of(List.of("50")), false, 5L);
        CheckResult res = checker.check("退款总金额是多少？", "SELECT SUM(refund_qty) FROM refund_items", rows);

        assertThat(res.suspicious()).isTrue();
        assertThat(res.rule()).isEqualTo("SUM_QTY_INSTEAD_OF_VALUE");
    }

    @Test
    @DisplayName("正常结果集判定为通过")
    void normalQueryResultPasses() {
        QueryResult ok = new QueryResult(List.of("state", "cnt"), List.of(List.of("SP", "4000")), false, 5L);
        CheckResult res = checker.check("各州的订单量", "SELECT customer_state, COUNT(*) FROM customers GROUP BY customer_state", ok);

        assertThat(res.suspicious()).isFalse();
    }
}
