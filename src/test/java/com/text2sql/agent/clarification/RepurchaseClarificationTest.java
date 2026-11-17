package com.text2sql.agent.clarification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RepurchaseClarificationTest {
    static AmbiguityDetector loaded() { var detector = new AmbiguityDetector(); detector.load(); return detector; }

    @ParameterizedTest
    @ValueSource(strings = {
            "复购率是多少？（下单 2 次及以上的用户占全部下单用户的比例）",
            "复购率是多少？下单两次及以上的用户占全部下单用户的比例",
            "回购率是多少？所有购买用户作为分母",
            "重复购买率=购买至少2次的用户/全部下单用户数",
            "复购率以全部下单用户为分母",
            "复购率分母是所有下过单的用户",
            "复购率分母采用有过有效订单的全部用户",
            "复购率用注册满30天的用户为分母",
            "复购率分母为注册满三十天的用户",
            "复购率以注册满３０天的用户作为分母",
            "复 购 率 是 下 单 2 次 及 以 上 用 户 占 全 部 下 单 用 户 的 比 例"
    })
    void explicitDenominatorByRelationIsAccepted(String question) {
        var detector = loaded();
        assertThat(detector.clarificationFor(question)).isEmpty();
        var decision = detector.decisionsFor(question).getFirst();
        assertThat(decision.evidence()).isNotEmpty().allMatch(e -> e.source().equals("required-pattern") && e.group().equals("denominator"));
        assertThat(decision.missingGroups()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "复购率是多少？", "回购率是多少？", "重复购买率怎么计算？",
            "复购率只算下单两次及以上的人", "复购率只统计有效订单",
            "复购率分母怎么选？", "复购率注册满多久？", "复购率注册满30天",
            "复购率用注册满的用户为分母", "复购率占全部注册用户的比例",
            "复购率用全部下单用户作为分子",
            "复购率占全部下单用户的比例，但我不确定口径",
            "复购率分母是全部下单用户还是注册满30天的用户？",
            "复购率是否以全部下单用户为分母？",
            "复购率不要以全部下单用户为分母",
            "复购率分母不是全部下单用户"
    })
    void missingOrUnresolvedDenominatorIsNotSilentlyAccepted(String question) {
        assertThat(loaded().clarificationFor(question)).isPresent();
    }

    @Test void blockerEvidenceWinsEvenWhenPositivePhraseIsPresent() {
        var decision = loaded().decisionsFor("复购率分母是全部下单用户还是注册满30天的用户？").getFirst();
        assertThat(decision.evidence()).isNotEmpty();
        assertThat(decision.unresolvedEvidence()).contains("还是");
        assertThat(decision.needsClarification()).isTrue();
    }

    @Test void numeratorAndPopulationFilterAreNotDenominatorEvidence() {
        var decision = loaded().decisionsFor("复购率只统计有效订单，下单两次及以上的用户").getFirst();
        assertThat(decision.evidence()).isEmpty();
        assertThat(decision.missingGroups()).containsExactly("denominator");
    }

    @Test void aliasIdentityAndLegacyHintIdentityRemainSeparate() {
        var detector = loaded();
        assertThat(detector.decisionsFor("回购率以全部下单用户为分母").getFirst().matchedTerm()).isEqualTo("回购率");
        assertThat(detector.decisionsFor("不含运费的销售额是多少").getFirst().evidence()).allMatch(e -> e.source().equals("legacy-hint"));
        assertThat(detector.clarificationFor("不含运费的销售额是多少")).isEmpty();
        assertThat(detector.clarificationFor("销售额是多少")).isPresent();
        assertThat(detector.decisionsFor("订单数量是多少")).isEmpty();
    }
}
