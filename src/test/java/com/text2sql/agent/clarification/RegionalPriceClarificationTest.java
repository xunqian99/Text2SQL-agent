package com.text2sql.agent.clarification;

import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.tool.AgentLoop;
import com.text2sql.agent.validation.ResultChecker;
import com.text2sql.agent.validation.SqlValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RegionalPriceClarificationTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "每个大区的订单数是多少", "按宏观区域统计商品销售额", "GMV按macro_region分组",
            "按客户州还是卖家州统计大区订单量", "大区商品销售额，不按客户所在大区",
            "订单量按大区统计，不用卖家所在州", "是否按客户所在大区统计订单数",
            "客户名称列表附在报告上；每个大区的订单量是多少",
            "先列出客户州字典，然后按大区统计订单数", "大区订单量不按customer_state统计"
    })
    void transactionalRegionsNeedResolvedSubject(String question) {
        assertThat(RepurchaseClarificationTest.loaded().clarificationFor(question)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "按客户所在大区统计商品销售额", "按卖家的宏观区域统计订单数",
            "按仓库所在地统计订单数，分大区展示", "订单数按客户收货州映射宏观区域",
            "按 customer_state 统计每个 macro_region 的订单数", "按seller_state映射大区统计订单量",
            "每个大区有多少个客户？平均每人下多少单？",
            "分别按客户所在大区和卖家所在大区统计商品销售额",
            "按客户所在大区统计商品销售额，不按卖家州"
    })
    void explicitRegionalSubjectDoesNotTriggerAgain(String question) {
        assertThat(RepurchaseClarificationTest.loaded().clarificationFor(question)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "每个大区包含多少个州", "Sudeste大区有多少个州", "每个大区的仓库容量是多少",
            "按宏观区域统计库存变动笔数", "订单量的平均涨幅是多少", "按类目查询价格变动记录数"
    })
    void nonTransactionalOrNonPriceQuestionsDoNotEnterNewRules(String question) {
        assertThat(RepurchaseClarificationTest.loaded().clarificationFor(question)).isEmpty();
        assertThat(RepurchaseClarificationTest.loaded().decisionsFor(question)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "发生过价格变动的商品，按类目统计平均涨跌幅",
            "价格平均涨幅按百分比输出", "价格平均跌幅，每条变动记录等权",
            "价格平均涨跌幅用金额差，每条价格变动记录等权，还是用百分比",
            "价格平均涨跌幅用百分比，不按变动记录平均",
            "价格平均涨幅，是否用百分比，每条变动记录等权",
            "价格平均涨幅，每条变动记录等权；折扣率用百分比输出"
    })
    void priceChangesNeedBothUnitAndGrainWithoutUnresolvedChoice(String question) {
        assertThat(RepurchaseClarificationTest.loaded().clarificationFor(question)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "价格平均涨跌幅按绝对价差计算，每条价格变动记录等权",
            "价格平均涨幅用百分比输出，每条变动记录等权",
            "价格平均跌幅采用新价减旧价，每次变动计一次",
            "价格平均涨跌幅以百分数表示，先按每个商品平均，再按类目平均",
            "价格平均涨幅按相对涨跌率计算，以销量加权",
            "价 格 平 均 涨 幅 用 百 分 比，每 条 变 动 记 录 等 权"
    })
    void explicitPriceDefinitionPasses(String question) {
        var decisions = RepurchaseClarificationTest.loaded().decisionsFor(question);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.getFirst().needsClarification()).isFalse();
        assertThat(decisions.getFirst().evidence()).extracting(AmbiguityDetector.Evidence::group)
                .contains("applicability", "price-change-unit", "averaging-grain");
    }

    @Test
    void triggerEvidenceCannotStandInForRequiredBusinessEvidence() {
        var decision = RepurchaseClarificationTest.loaded().decisionsFor("价格平均涨跌幅是多少").getFirst();
        assertThat(decision.evidence()).allMatch(e -> e.source().equals("trigger-pattern"));
        assertThat(decision.missingGroups()).containsExactly("price-change-unit", "averaging-grain");
        assertThat(decision.needsClarification()).isTrue();
    }

    @Test
    void missingGrainIsRecordedSeparatelyFromKnownUnit() {
        var decision = RepurchaseClarificationTest.loaded().decisionsFor("价格平均涨幅用百分比输出").getFirst();
        assertThat(decision.missingGroups()).containsExactly("averaging-grain");
        assertThat(decision.evidence()).anyMatch(e -> e.group().equals("price-change-unit"));
    }

    @Test
    void initialAmbiguousRequestsStopBeforeSchemaModelAndDatabase() {
        var properties = new AgentProperties(); properties.getClarification().setEnabled(true);
        var provider=mock(SchemaProvider.class); var generator=mock(LlmSqlGenerator.class);
        var validator=mock(SqlValidator.class); var executor=mock(SqlExecutor.class); var loop=mock(AgentLoop.class);
        var orchestrator=new Text2SqlOrchestrator(provider, com.text2sql.agent.retrieval.CatalogFixture.from(provider::fullSchema),generator,validator,executor,
                RepurchaseClarificationTest.loaded(),new SemanticCache(properties),new ResultChecker(),loop,properties);
        for (String question : java.util.List.of("按大区统计商品销售额", "价格平均涨跌幅是多少")) {
            var response=orchestrator.ask(question);
            assertThat(response.status()).isEqualTo(AgentResponse.Status.NEEDS_CLARIFICATION);
            assertThat(response.sql()).isNull();
        }
        verifyNoInteractions(provider,generator,validator,executor,loop);
    }
}
