package com.text2sql.agent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LlmCallRecordTest {

    @Test
    @DisplayName("缓存命中/未命中分段计价：hit 用低价，miss 用正常输入价")
    void splitsCacheHitAndMissCost() {
        LlmCallRecord record = LlmCallRecord.of("m", 1000, 100, 800, 200, 100, 10,
                1.0, 4.0, 0.02);

        assertThat(record.cacheHitTokens()).isEqualTo(800);
        assertThat(record.cacheMissTokens()).isEqualTo(200);
        // 200/1000*1.0 + 800/1000*0.02 + 100/1000*4.0 = 0.616
        assertThat(record.costYuan()).isCloseTo(0.616, within(1e-9));
    }

    @Test
    @DisplayName("旧工厂方法：没有 cache 明细时按全部未命中计价，保持向后兼容")
    void oldFactoryTreatsAllPromptAsMiss() {
        LlmCallRecord record = LlmCallRecord.of("m", 1000, 100, 100, 10, 1.0, 4.0);

        assertThat(record.cacheHitTokens()).isZero();
        assertThat(record.cacheMissTokens()).isEqualTo(1000);
        assertThat(record.costYuan()).isCloseTo(1.4, within(1e-9));
    }

    @Test
    @DisplayName("合并两次调用时，cache hit/miss 也要相加")
    void combineSumsCacheFields() {
        LlmCallRecord first = LlmCallRecord.of("m", 1000, 100, 800, 200, 100, 10,
                1.0, 4.0, 0.02);
        LlmCallRecord second = LlmCallRecord.of("m", 500, 50, 400, 100, 50, 5,
                1.0, 4.0, 0.02);

        LlmCallRecord combined = LlmCallRecord.combine(first, second);

        assertThat(combined.cacheHitTokens()).isEqualTo(1200);
        assertThat(combined.cacheMissTokens()).isEqualTo(300);
        assertThat(combined.costYuan()).isCloseTo(0.616 + 0.308, within(1e-9));
    }

    @Test
    @DisplayName("summary 格式化不能抛异常（拆分字符串时容易漏括号）")
    void summaryFormatsWithoutError() {
        LlmCallRecord record = LlmCallRecord.of("m", 1000, 100, 800, 200, 100, 10,
                1.0, 4.0, 0.02);
        assertThat(record.summary()).contains("cacheHit=800").contains("cacheMiss=200");
    }
}
