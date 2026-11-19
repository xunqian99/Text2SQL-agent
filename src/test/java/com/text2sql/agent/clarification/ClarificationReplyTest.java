package com.text2sql.agent.clarification;

import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.session.ConversationSession;
import com.text2sql.agent.session.QuestionRewriter;
import com.text2sql.agent.session.SessionStore;
import com.text2sql.agent.tool.AgentLoop;
import com.text2sql.agent.validation.ResultChecker;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClarificationReplyTest {
    final AmbiguityDetector detector = RepurchaseClarificationTest.loaded();
    private ClarificationState state(String question) { return new ClarificationState(question, List.of()); }

    @Test void priceNeedsBothRepliesAndKeepsOriginalFacts() {
        var original = state("2018年按类目统计价格平均涨跌幅，只看涨价记录");
        var unit = detector.replyTo(original, "百分比").orElseThrow();
        assertThat(detector.decisionsForState(unit).getFirst().missingGroups()).containsExactly("averaging-grain");
        var complete = detector.replyTo(unit, "每条记录等权").orElseThrow();
        assertThat(detector.clarificationForState(complete)).isEmpty();
        assertThat(complete.effectiveQuestion()).startsWith(original.question()).contains("相对涨跌率", "每条价格变动记录等权");
        assertThat(detector.decisionsForState(complete).getFirst().evidence())
                .filteredOn(e -> e.source().equals("confirmed-reply")).hasSize(2);
    }

    @Test void knownUnitSurvivesGrainReply() {
        var complete = detector.replyTo(state("价格平均涨幅用百分比输出"), "每条记录等权").orElseThrow();
        assertThat(detector.clarificationForState(complete)).isEmpty();
        assertThat(complete.confirmations()).hasSize(1);
    }

    @Test void explicitSelectionOverridesOriginalAlternatives() {
        var complete = detector.replyTo(state("按客户州还是卖家州统计大区订单量"), "按客户").orElseThrow();
        assertThat(detector.clarificationForState(complete)).isEmpty();
        assertThat(detector.decisionsForState(complete).getFirst().evidence())
                .anyMatch(e -> e.source().equals("unresolved-base-pattern"))
                .anyMatch(e -> e.source().equals("confirmed-reply"));
    }

    @Test void originalPriceAlternativesDoNotBecomeConfirmationOfOtherGroups() {
        var unit = detector.replyTo(state("价格平均涨幅用金额差，每条变动记录等权，还是用百分比"), "百分比").orElseThrow();
        assertThat(detector.clarificationForState(unit)).isPresent();
        assertThat(detector.decisionsForState(unit).getFirst().missingGroups()).containsExactly("averaging-grain");
    }

    @ParameterizedTest @ValueSource(strings = {"随便", "不是客户", "客户还是卖家", "不要含运费", "百分比或者金额差", "登录"})
    void unknownOrNegativeRepliesNeverBecomeSelections(String reply) {
        for (var question : List.of("销售额是多少", "按大区统计商品销售额", "价格平均涨跌幅是多少", "活跃用户是多少"))
            assertThat(detector.replyTo(state(question), reply)).isEmpty();
    }

    @Test void salesAlwaysStartsUnresolvedAndEachChoiceIsExplicit() {
        var original = state("2018年每月销售额是多少？");
        assertThat(detector.clarificationForState(original)).isPresent();
        var inclusive = detector.replyTo(original, "含运费。").orElseThrow();
        var exclusive = detector.replyTo(original, "不含运费").orElseThrow();
        assertThat(detector.clarificationForState(inclusive)).isEmpty();
        assertThat(detector.clarificationForState(exclusive)).isEmpty();
        assertThat(inclusive.confirmations().getFirst().canonical()).doesNotContain("不含");
        assertThat(exclusive.confirmations().getFirst().canonical()).contains("不含运费");
    }

    @Test void latestSelectionReplacesOnlyItsGroup() {
        var unit = detector.replyTo(state("价格平均涨幅是多少"), "金额差").orElseThrow();
        var changed = detector.replyTo(unit, "百分比").orElseThrow();
        assertThat(changed.confirmations()).hasSize(1);
        assertThat(changed.effectiveQuestion()).contains("相对涨跌率").doesNotContain("按新价减旧价的金额差");
        assertThat(detector.replyTo(changed, "x".repeat(121))).isEmpty();
    }

    static class Fixture {
        final AgentProperties properties = new AgentProperties();
        final SchemaProvider schemas = mock(SchemaProvider.class);
        final LlmSqlGenerator generator = mock(LlmSqlGenerator.class);
        final SqlValidator validator = mock(SqlValidator.class);
        final SqlExecutor executor = mock(SqlExecutor.class);
        final QuestionRewriter rewriter = mock(QuestionRewriter.class);
        final SessionStore store;
        final Text2SqlOrchestrator orchestrator;
        Fixture(AmbiguityDetector detector) {
            properties.getConversation().setEnabled(true);
            properties.getClarification().setEnabled(true);
            store = new SessionStore(properties);
            var schema = new SchemaContext(List.of(new SchemaContext.Table("orders", null,
                    List.of(new SchemaContext.Column("order_id", "text", false, null)))), List.of(), "", "orders");
            when(schemas.provide(anyString())).thenReturn(schema);
            when(generator.generate(anyString(), any())).thenReturn(new GeneratedSql("SELECT 1", "",
                    LlmCallRecord.of("synthetic", 10, 5, 15, 1, 0, 0)));
            when(validator.validate(anyString(), any())).thenReturn(ValidationResult.ok("SELECT 1"));
            when(executor.execute(anyString())).thenReturn(new QueryResult(List.of("n"), List.of(List.of("1")), false, 1));
            orchestrator = new Text2SqlOrchestrator(schemas, generator, validator, executor, detector,
                    new SemanticCache(properties), new ResultChecker(), mock(AgentLoop.class), store, rewriter, properties);
        }
    }

    @Test void shortReplyKeepsQuestionBypassesRewriteAndRetrievesFreshSchema() {
        var f = new Fixture(detector);
        String original = "2018年按大区统计商品销售额，只看已送达订单";
        assertThat(f.orchestrator.ask(original, "a").status()).isEqualTo(AgentResponse.Status.NEEDS_CLARIFICATION);
        clearInvocations(f.rewriter);
        var response = f.orchestrator.ask("按客户", "a");
        assertThat(response.success()).isTrue();
        assertThat(response.rewrittenQuestion()).startsWith(original).contains("按客户所在大区统计");
        assertThat(response.schemaReused()).isFalse();
        verify(f.schemas).provide(response.rewrittenQuestion());
        verify(f.generator).generate(eq(response.rewrittenQuestion()), any());
        verifyNoInteractions(f.rewriter);
        assertThat(f.store.get("a").orElseThrow().pendingClarification()).isNull();
    }

    @Test void unknownReplyReasksAndOtherSessionsCannotReadPendingQuestion() {
        var f = new Fixture(detector);
        f.orchestrator.ask("销售额是多少", "a");
        assertThat(f.orchestrator.ask("随便", "a").status()).isEqualTo(AgentResponse.Status.NEEDS_CLARIFICATION);
        assertThat(f.orchestrator.ask("活跃用户是多少", "b").status()).isEqualTo(AgentResponse.Status.NEEDS_CLARIFICATION);
        assertThat(f.orchestrator.ask("含运费", "b").status()).isEqualTo(AgentResponse.Status.NEEDS_CLARIFICATION);
        assertThat(f.store.get("a").orElseThrow().pendingClarification().question()).isEqualTo("销售额是多少");
        verifyNoInteractions(f.schemas, f.generator);
    }

    @Test void freshQuestionAndFullRestatementAbandonOldPendingContext() {
        var f = new Fixture(detector);
        for (String next : List.of("查询订单数量", "按卖家所在大区统计商品销售额")) {
            f.orchestrator.ask("2018年按大区统计商品销售额", "a");
            clearInvocations(f.rewriter);
            assertThat(f.orchestrator.ask(next, "a").success()).isTrue();
            verify(f.schemas).provide(next);
            verifyNoInteractions(f.rewriter);
            assertThat(f.store.get("a").orElseThrow().pendingClarification()).isNull();
        }
    }

    @Test void sequentialPriceRepliesDoNotGenerateUntilBothConfirmed() {
        var f = new Fixture(detector);
        f.orchestrator.ask("2018年价格平均涨幅是多少", "a");
        assertThat(f.orchestrator.ask("百分比", "a").status()).isEqualTo(AgentResponse.Status.NEEDS_CLARIFICATION);
        verifyNoInteractions(f.schemas, f.generator);
        var done = f.orchestrator.ask("每条记录等权", "a");
        assertThat(done.success()).isTrue();
        assertThat(done.rewrittenQuestion()).contains("2018年", "相对涨跌率", "每条价格变动记录等权");
        assertThat(f.store.get("a").orElseThrow().turns()).hasSize(3);
    }

    @Test void singleTurnDoesNotCreatePendingSessionAndDeletionDoesNotTransferIt() {
        var f = new Fixture(detector);
        f.orchestrator.ask("销售额是多少");
        assertThat(f.store.listSessions()).isEmpty();
        f.orchestrator.ask("销售额是多少", "a");
        var old = f.store.get("a").orElseThrow();
        f.orchestrator.deleteSession("a");
        var replacement = f.store.getOrCreate("a");
        assertThat(replacement).isNotSameAs(old);
        assertThat(replacement.pendingClarification()).isNull();
    }

    @Test void staleCompletionAndClosedSessionCannotRestorePendingState() {
        var session = new ConversationSession("a");
        long old = session.beginClarificationRequest();
        long current = session.beginClarificationRequest();
        session.finishClarificationRequest(current, null);
        session.finishClarificationRequest(old, state("销售额是多少"));
        assertThat(session.pendingClarification()).isNull();
        long closing = session.beginClarificationRequest();
        session.close();
        session.finishClarificationRequest(closing, state("销售额是多少"));
        assertThat(session.pendingClarification()).isNull();
    }
}
