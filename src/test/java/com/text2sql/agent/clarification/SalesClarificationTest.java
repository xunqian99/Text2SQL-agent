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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SalesClarificationTest {
    @ParameterizedTest
    @ValueSource(strings = {"销售额按含运费计算", "销售额不含运费", "不含运费的销售额是多少？",
            "销售额按商品金额统计", "每个州的商品销售额是多少？",
            "分别列出含运费和不含运费的销售额", "销售额是 含 运 费 的，按月统计"})
    void resolvedBasisStillPasses(String question) {
        assertThat(RepurchaseClarificationTest.loaded().clarificationFor(question)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"销售额含运费还是不含运费？", "销售额是含运费的吗？",
            "销售额是否包含运费？", "销售额要不要包含运费？", "销售额含不含运费？",
            "销售额按商品金额还是含运费，尚未确认", "销售额不含运费，但我不确定口径",
            "销售额是含运费或者不含运费？", "销售额需不需要算上运费？"})
    void mentioningBasisOptionsDoesNotResolveAChoice(String question) {
        var decision = RepurchaseClarificationTest.loaded().decisionsFor(question).getFirst();
        assertThat(decision.unresolvedEvidence()).isNotEmpty();
        assertThat(decision.needsClarification()).isTrue();
    }

    @Test
    void unresolvedChoiceEvidenceOverridesExistingHintEvidence() {
        var decision = RepurchaseClarificationTest.loaded()
                .decisionsFor("销售额含运费还是不含运费？").getFirst();
        assertThat(decision.evidence()).allSatisfy(evidence -> {
            assertThat(evidence.source()).isEqualTo("legacy-hint");
            assertThat(evidence.group()).isEqualTo("hint");
        }).isNotEmpty();
        assertThat(decision.missingGroups()).isEmpty();
        assertThat(decision.unresolvedEvidence()).isNotEmpty();
        assertThat(decision.needsClarification()).isTrue();
    }

    @Test
    void bareSalesStillNeedsABusinessDecision() {
        assertThat(RepurchaseClarificationTest.loaded().clarificationFor("2018年每月销售额是多少？")).isPresent();
    }

    @Test
    void unresolvedFreightChoiceStopsBeforeSchemaAndModelAndDatabase() {
        var props = new AgentProperties();
        props.getClarification().setEnabled(true);
        var provider = mock(SchemaProvider.class);
        var generator = mock(LlmSqlGenerator.class);
        var validator = mock(SqlValidator.class);
        var executor = mock(SqlExecutor.class);
        var loop = mock(AgentLoop.class);
        var orchestrator = new Text2SqlOrchestrator(provider, com.text2sql.agent.retrieval.CatalogFixture.from(provider::fullSchema), generator, validator, executor,
                RepurchaseClarificationTest.loaded(), new SemanticCache(props), new ResultChecker(), loop, props);
        var response = orchestrator.ask("销售额按含运费还是不含运费统计？");
        assertThat(response.status()).isEqualTo(AgentResponse.Status.NEEDS_CLARIFICATION);
        assertThat(response.sql()).isNull();
        verifyNoInteractions(provider, generator, validator, executor, loop);
    }
}
