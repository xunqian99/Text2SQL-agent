package com.text2sql.agent.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果规范化测试。
 *
 * <p>这组测试防的是**假失败**——模型答对了，但评估器判错。
 * 假失败比假成功更危险：它会让你去改一个本来正确的环节，
 * 把系统越改越差，而且过程中所有指标看起来都在「改善」。
 *
 * <p>三条规则各对应一类真实假失败来源，每条都单独测：
 * 浮点尾数、NULL 表示、行顺序。
 */
class ResultNormalizerTest {

    @Test
    @DisplayName("浮点尾数差异被抹平：15843553.24 与 15843553.240000001 视为相同")
    void roundsFloatNoise() {
        assertThat(ResultNormalizer.normalizeValue("15843553.240000001"))
                .isEqualTo(ResultNormalizer.normalizeValue("15843553.24"));
    }

    @Test
    @DisplayName("整数不出现科学计数法：610 不能变成 6.1E+2")
    void neverProducesScientificNotation() {
        String normalized = ResultNormalizer.normalizeValue("610");

        assertThat(normalized).isEqualTo("610");
        assertThat(normalized).doesNotContainIgnoringCase("E");
    }

    @Test
    @DisplayName("小数末尾的零被去掉：1.5000 与 1.5 视为相同")
    void stripsTrailingZeros() {
        assertThat(ResultNormalizer.normalizeValue("1.5000")).isEqualTo("1.5");
        assertThat(ResultNormalizer.normalizeValue("2.0000")).isEqualTo("2");
    }

    @Test
    @DisplayName("NULL 统一成字面量，与空字符串区分开")
    void normalizesNullDistinctlyFromEmptyString() {
        assertThat(ResultNormalizer.normalizeValue(null)).isEqualTo("NULL");
        // 空串保持空串。如果把空串也变成 NULL，「该字段没有值」和
        // 「该字段的值是空字符串」这两件不同的事就会被混为一谈。
        assertThat(ResultNormalizer.normalizeValue("")).isEmpty();
    }

    @Test
    @DisplayName("非数字文本原样保留，只去首尾空白")
    void keepsNonNumericText() {
        assertThat(ResultNormalizer.normalizeValue("  delivered  ")).isEqualTo("delivered");
        assertThat(ResultNormalizer.normalizeValue("2018-01-01 00:00:00"))
                .isEqualTo("2018-01-01 00:00:00");
    }

    @Test
    @DisplayName("无序结果按行排序后比较：行序不同不算错")
    void sortsRowsWhenOrderDoesNotMatter() {
        QueryResult a = new QueryResult(List.of("status"), List.of(
                List.of("delivered"), List.of("canceled")), false, 0);
        QueryResult b = new QueryResult(List.of("status"), List.of(
                List.of("canceled"), List.of("delivered")), false, 0);

        assertThat(ResultNormalizer.normalize(a, false))
                .isEqualTo(ResultNormalizer.normalize(b, false));
    }

    @Test
    @DisplayName("有序结果保留行序：Top-N 榜单顺序错了就是错了")
    void keepsOrderWhenItMatters() {
        QueryResult a = new QueryResult(List.of("id"), List.of(
                List.of("1"), List.of("2")), false, 0);
        QueryResult b = new QueryResult(List.of("id"), List.of(
                List.of("2"), List.of("1")), false, 0);

        assertThat(ResultNormalizer.normalize(a, true))
                .isNotEqualTo(ResultNormalizer.normalize(b, true));
    }

    @Test
    @DisplayName("只有 ORDER BY + LIMIT 同时出现才认为顺序承载语义")
    void orderMattersOnlyWithLimit() {
        assertThat(ResultNormalizer.orderMatters("SELECT a FROM t ORDER BY a")).isFalse();
        assertThat(ResultNormalizer.orderMatters("SELECT a FROM t ORDER BY a LIMIT 10")).isTrue();
        assertThat(ResultNormalizer.orderMatters("SELECT a FROM t LIMIT 10")).isFalse();
    }

    @Test
    @DisplayName("规范化后相等即判定等价")
    void equivalentAfterNormalization() {
        QueryResult actual = new QueryResult(List.of("cnt"), List.of(List.of("99441")), false, 0);
        QueryResult expected = new QueryResult(List.of("cnt"), List.of(List.of("99441.0")), false, 0);

        assertThat(ResultNormalizer.equivalent(
                ResultNormalizer.normalize(expected, false),
                ResultNormalizer.normalize(actual, false))).isTrue();
    }
}
