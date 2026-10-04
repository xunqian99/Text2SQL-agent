package com.text2sql.agent.clarification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 歧义检测测试。**用真实的 ambiguities.yml**，不是内存里造的假数据。
 *
 * <p>理由与 {@code MetricRegistryTest} 相同：这一层的价值全在「规则写得对不对」，
 * 用假数据只能验证遍历逻辑，验证不了「问『销售额是多少』到底会不会触发反问」。
 */
class AmbiguityDetectorTest {

    private static AmbiguityDetector loaded() {
        AmbiguityDetector detector = new AmbiguityDetector();
        detector.load();
        return detector;
    }

    @Test
    @DisplayName("真实词表能加载，且包含销售额这条最常见的口径歧义")
    void loadsRealFile() {
        AmbiguityDetector detector = loaded();

        assertThat(detector.all()).isNotEmpty();
        assertThat(detector.all()).extracting(AmbiguityDetector.Ambiguity::term)
                .contains("销售额");
    }

    @Test
    @DisplayName("不带限定词时触发反问")
    void asksWhenTermIsAmbiguous() {
        AmbiguityDetector detector = loaded();

        assertThat(detector.clarificationFor("2018 年每个月的销售额是多少？")).isPresent();
    }

    @Test
    @DisplayName("带上限定词后不再反问——问一次就够")
    void doesNotAskWhenDisambiguated() {
        AmbiguityDetector detector = loaded();

        assertThat(detector.clarificationFor("2018 年每个月不含运费的销售额是多少？")).isEmpty();
        assertThat(detector.clarificationFor("2018 年每个月的 GMV 是多少？")).isEmpty();
    }

    @Test
    @DisplayName("词中间的空格不影响判定")
    void ignoresWhitespace() {
        AmbiguityDetector detector = loaded();

        assertThat(detector.clarificationFor("销售额 是多少")).isPresent();
        // 限定词里带空格同样要能识别
        assertThat(detector.clarificationFor("销售额是 含运费 的吗？")).isEmpty();
    }

    @Test
    @DisplayName("没有歧义词的问题不反问")
    void staysQuietForClearQuestions() {
        AmbiguityDetector detector = loaded();

        assertThat(detector.clarificationFor("每个月的订单数量是多少？")).isEmpty();
        assertThat(detector.clarificationFor("")).isEmpty();
        assertThat(detector.clarificationFor(null)).isEmpty();
    }

    @Test
    @DisplayName("每条规则的 term、hints、question 都完整")
    void everyRuleIsComplete() {
        AmbiguityDetector detector = loaded();

        for (AmbiguityDetector.Ambiguity a : detector.all()) {
            assertThat(a.term()).as("term 不能为空").isNotBlank();
            // hints 为空会让该词永远触发反问，等于坏规则
            assertThat(a.hints()).as("%s 缺 hints", a.term()).isNotEmpty();
            assertThat(a.question()).as("%s 缺反问文案", a.term()).isNotBlank();
        }
    }
}
