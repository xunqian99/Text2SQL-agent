package com.text2sql.agent.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyIntentGuardTest {

    @Test
    void blocksChineseDestructiveCommandBeforeModelGeneration() {
        assertThat(ReadOnlyIntentGuard.violation("删除 orders 表中所有数据")).isPresent();
    }

    @Test
    void blocksSqlLikeWriteCommandsRegardlessOfCase() {
        assertThat(ReadOnlyIntentGuard.violation("delete from orders")).isPresent();
        assertThat(ReadOnlyIntentGuard.violation("UPDATE orders SET order_status = 'x'")).isPresent();
        assertThat(ReadOnlyIntentGuard.violation("DROP TABLE orders")).isPresent();
    }

    @Test
    void allowsQuestionsThatAnalyzeHistoricalDeletion() {
        assertThat(ReadOnlyIntentGuard.violation("统计删除订单后的退款金额")).isEmpty();
        assertThat(ReadOnlyIntentGuard.violation("统计删除订单后的退款数据")).isEmpty();
        assertThat(ReadOnlyIntentGuard.violation("删除率按月份的趋势是什么")).isEmpty();
    }

    @Test
    void ignoresBlankQuestions() {
        assertThat(ReadOnlyIntentGuard.violation(null)).isEmpty();
        assertThat(ReadOnlyIntentGuard.violation("  ")).isEmpty();
    }
}
