package com.text2sql.agent.session;

import com.text2sql.agent.api.AskController;
import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.tool.AgentLoop;
import com.text2sql.agent.validation.CheckResult;
import com.text2sql.agent.validation.ResultChecker;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionOutcomeFinalizationTest {
    static final class Fixture implements AutoCloseable {
        final AgentProperties properties = new AgentProperties();
        final ChatModel model = mock(ChatModel.class);
        final SchemaProvider schemas = mock(SchemaProvider.class);
        final SqlValidator validator = mock(SqlValidator.class);
        final SqlExecutor executor = mock(SqlExecutor.class);
        final ResultChecker checker = mock(ResultChecker.class);
        final SessionStore store;
        final LlmCallLog logs;
        final Text2SqlOrchestrator orchestrator;
        final SchemaContext schema = new SchemaContext(List.of(new SchemaContext.Table("orders", "",
                List.of(new SchemaContext.Column("order_id", "text", false, "")))), List.of(), "", "orders");

        Fixture(long timeout) {
            properties.getConversation().setEnabled(true);
            properties.getConversation().setRewriteWithLlm(false);
            properties.getRequestBudget().setEnabled(true);
            properties.getRequestBudget().setTimeoutMs(timeout);
            properties.getRequestBudget().setMinRetryRemainingMs(0);
            properties.getObservability().setEnabled(false);
            store = spy(new SessionStore(properties));
            logs = new LlmCallLog(properties);
            var generator = new LlmSqlGenerator(properties, new PromptTemplate(properties), logs);
            ReflectionTestUtils.setField(generator, "chatModel", model);
            when(model.call(any(Prompt.class))).thenReturn(sql());
            when(schemas.provide(anyString())).thenReturn(schema);
            when(schemas.fullSchema()).thenReturn(schema);
            when(validator.validate(anyString(), any())).thenAnswer(i -> ValidationResult.ok(i.getArgument(0)));
            when(executor.execute(anyString())).thenReturn(new QueryResult(List.of("order_id"), List.of(List.of("fixture")), false, 1));
            when(checker.check(anyString(), anyString(), any())).thenReturn(CheckResult.NORMAL);
            orchestrator = new Text2SqlOrchestrator(schemas, generator, validator, executor,
                    mock(AmbiguityDetector.class), new SemanticCache(properties), checker, mock(AgentLoop.class),
                    store, new QuestionRewriter(generator, properties), properties);
        }

        public void close() { logs.close(); }
    }

    private static ChatResponse sql() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("SELECT order_id FROM orders LIMIT 1"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
    }

    private static void awaitIgnoringInterrupt(CountDownLatch gate) {
        boolean interrupted = false;
        while (gate.getCount() > 0) {
            try { gate.await(); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static ConversationTurn onlyTurn(Fixture f, String id) {
        var turns = f.store.get(id).orElseThrow().turns();
        assertThat(turns).hasSize(1);
        return turns.getFirst();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void normalCompletionWritesOneTurnWithOrWithoutBudget(boolean enabled) {
        try (var f = new Fixture(3000)) {
            f.properties.getRequestBudget().setEnabled(enabled);
            var result = f.orchestrator.ask("订单有哪些？", "normal");
            assertThat(result.status()).isEqualTo(AgentResponse.Status.SUCCESS);
            assertThat(onlyTurn(f, "normal").sql()).isEqualTo(result.sql());
            assertThat(f.store.get("normal").orElseThrow().lastSchema()).isSameAs(f.schema);
        }
    }

    @Test
    void httpTimeoutHasHistoryBeforeAnUncooperativeModelReturnsAndLateAnswerCannotAppend() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var f = new Fixture(400)) {
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> {
                try { awaitIgnoringInterrupt(release); return sql(); } finally { exited.countDown(); }
            });
            var controller = new AskController(f.orchestrator);
            long started = System.nanoTime();
            var http = controller.ask(new AskController.AskRequest("订单有哪些？", "slow", false), null);
            assertThat(http.getStatusCode().value()).isEqualTo(504);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1800);
            var response = http.getBody();
            var turn = onlyTurn(f, "slow");
            assertThat(turn.status()).isEqualTo(response.status()).isEqualTo("REQUEST_TIMEOUT");
            assertThat(turn.sql()).isNull();
            assertThat(turn.question()).isEqualTo("订单有哪些？");
            assertThat(f.store.get("slow").orElseThrow().lastSchema()).isNull();
            assertThat(response.budget().modelCalls()).isEqualTo(1);
            assertThat(response.budget().unknownUsageCalls()).isEqualTo(1);
            release.countDown();
            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            // Drain the worker's post-model code by observing its only generator entry point.
            assertThat(onlyTurn(f, "slow")).isEqualTo(turn);
        } finally { release.countDown(); }
    }

    @Test
    void correctionTimeoutRecordsTheReturnedFirstAnswerRatherThanTheAbandonedCorrection() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var f = new Fixture(500)) {
            f.properties.getSelfCorrection().setEnabled(true);
            f.properties.getSelfCorrection().setMaxAttempts(2);
            f.properties.getResultChecker().setEnabled(true);
            when(f.checker.check(anyString(), anyString(), any())).thenReturn(CheckResult.suspicious("fixture", "retry fixture"));
            var calls = new AtomicInteger();
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> {
                if (calls.incrementAndGet() == 1) return sql();
                try { awaitIgnoringInterrupt(release); return sql(); } finally { exited.countDown(); }
            });
            var result = f.orchestrator.ask("订单有哪些？", "correction");
            assertThat(result.status()).isEqualTo(AgentResponse.Status.SUCCESS);
            assertThat(result.budget().termination()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(result.budget().modelCalls()).isEqualTo(2);
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
            assertThat(onlyTurn(f, "correction").sql()).isEqualTo(result.sql());
            assertThat(onlyTurn(f, "correction").status()).isEqualTo("SUCCESS");
            assertThat(f.store.get("correction").orElseThrow().lastSchema()).isSameAs(f.schema);
            release.countDown();
            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(f.store.get("correction").orElseThrow().turns()).hasSize(1);
        } finally { release.countDown(); }
    }

    @Test
    void callCapSkipsCorrectionAndRecordsTheReturnedRejectionWithoutCountingAnUnsentCall() {
        try (var f = new Fixture(3000)) {
            f.properties.getSelfCorrection().setEnabled(true);
            f.properties.getSelfCorrection().setMaxAttempts(2);
            f.properties.getResultChecker().setEnabled(true);
            f.properties.getRequestBudget().setMaxModelCalls(1);
            when(f.validator.validate(anyString(), any())).thenReturn(new ValidationResult("SELECT wrong FROM orders",
                    List.of(new ValidationResult.Violation(ValidationResult.Code.UNKNOWN_COLUMN, "fixture")), false));
            var result = f.orchestrator.ask("订单有哪些？", "cap");
            assertThat(result.status()).isEqualTo(AgentResponse.Status.REJECTED);
            assertThat(result.budget().retrySkippedReason()).isEqualTo("MODEL_CALL_LIMIT");
            assertThat(result.budget().modelCalls()).isEqualTo(1);
            assertThat(result.budget().unknownUsageCalls()).isZero();
            assertThat(onlyTurn(f, "cap").status()).isEqualTo("REJECTED");
            verify(f.model, times(1)).call(any(Prompt.class));
        }
    }

    @Test
    void rewriteUsesTheOnlyCallAndResourceLimitedGenerationIsRecordedWithItsRewrite() {
        try (var f = new Fixture(3000)) {
            assertThat(f.orchestrator.ask("2018年订单有哪些？", "rewrite-cap").success()).isTrue();
            f.properties.getConversation().setRewriteWithLlm(true);
            f.properties.getRequestBudget().setMaxModelCalls(1);
            when(f.model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                    List.of(new Generation(new AssistantMessage("2017年订单有哪些？"))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build()));
            var result = f.orchestrator.ask("那2017年的呢？", "rewrite-cap");
            var session = f.store.get("rewrite-cap").orElseThrow();
            assertThat(result.status()).isEqualTo(AgentResponse.Status.RESOURCE_LIMITED);
            assertThat(result.budget().termination()).isEqualTo("MODEL_CALL_LIMIT");
            assertThat(result.budget().modelCalls()).isEqualTo(1);
            assertThat(result.budget().unknownUsageCalls()).isZero();
            assertThat(result.rewrittenQuestion()).isEqualTo("2017年订单有哪些？");
            assertThat(result.schemaReused()).isTrue();
            assertThat(session.turns()).hasSize(2);
            assertThat(session.lastTurn().status()).isEqualTo("RESOURCE_LIMITED");
            assertThat(session.lastTurn().rewrittenQuestion()).isEqualTo(result.rewrittenQuestion());
            verify(f.model, times(2)).call(any(Prompt.class));
        }
    }

    @Test
    void rewriteTimeoutKeepsPriorTurnAndItsSchemaWithoutInventingACompletedRewrite() {
        var release = new CountDownLatch(1);
        try (var f = new Fixture(400)) {
            var first = f.orchestrator.ask("2018年订单有哪些？", "rewrite");
            assertThat(first.success()).isTrue();
            f.properties.getConversation().setRewriteWithLlm(true);
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> { awaitIgnoringInterrupt(release); return sql(); });
            var result = f.orchestrator.ask("那2017年的呢？", "rewrite");
            var session = f.store.get("rewrite").orElseThrow();
            assertThat(result.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
            assertThat(result.rewrittenQuestion()).isNull();
            assertThat(session.turns()).hasSize(2);
            assertThat(session.lastTurn().status()).isEqualTo("REQUEST_TIMEOUT");
            assertThat(session.lastTurn().question()).isEqualTo("那2017年的呢？");
            assertThat(session.lastSchema()).isSameAs(f.schema);
        } finally { release.countDown(); }
    }

    @Test
    void deletedAndRecreatedSessionDoesNotReceiveTheOldRequestsTimeoutOrLateAnswer() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CompletableFuture<AgentResponse>();
        try (var f = new Fixture(600)) {
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> { entered.countDown(); awaitIgnoringInterrupt(release); return sql(); });
            var caller = Thread.ofPlatform().daemon().start(() -> {
                try { done.complete(f.orchestrator.ask("旧请求", "reused")); } catch (Throwable failure) { done.completeExceptionally(failure); }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            f.store.deleteSession("reused");
            var replacement = f.store.getOrCreate("reused");
            assertThat(done.get(2, TimeUnit.SECONDS).status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
            assertThat(replacement.turns()).isEmpty();
            assertThat(replacement.lastSchema()).isNull();
            release.countDown();
            caller.join(1000);
            assertThat(f.store.get("reused").orElseThrow()).isSameAs(replacement);
            assertThat(replacement.turns()).isEmpty();
        } finally { release.countDown(); }
    }

    @Test
    void finalizedRejectionAndHistorySurviveSlowEndSessionPersistenceWithoutBecomingTimeout() throws Exception {
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        try (var f = new Fixture(400)) {
            when(f.validator.validate(anyString(), any())).thenReturn(new ValidationResult("SELECT wrong FROM orders",
                    List.of(new ValidationResult.Violation(ValidationResult.Code.UNKNOWN_COLUMN, "fixture")), false));
            doAnswer(i -> {
                try { awaitIgnoringInterrupt(release); return i.callRealMethod(); } finally { closed.countDown(); }
            }).when(f.store).closeAndPersist("close");
            var result = f.orchestrator.ask("订单有哪些？", "close", null, true);
            assertThat(result.status()).isEqualTo(AgentResponse.Status.REJECTED);
            assertThat(result.budget().termination()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(onlyTurn(f, "close").status()).isEqualTo("REJECTED");
            assertThat(onlyTurn(f, "close").sql()).isEqualTo(result.sql());
            release.countDown();
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(f.store.get("close").orElseThrow().isClosed()).isTrue();
            assertThat(f.store.get("close").orElseThrow().turns()).hasSize(1);
        } finally { release.countDown(); }
    }

    @Test
    void otherSessionsSuccessfulHistoryCannotBeOverwrittenByATimeout() {
        var release = new CountDownLatch(1);
        try (var f = new Fixture(400)) {
            var good = f.orchestrator.ask("订单有哪些？", "good");
            var original = onlyTurn(f, "good");
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> { awaitIgnoringInterrupt(release); return sql(); });
            var bad = f.orchestrator.ask("别的订单问题", "bad");
            assertThat(bad.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
            assertThat(onlyTurn(f, "good")).isEqualTo(original);
            assertThat(original.sql()).isEqualTo(good.sql());
            assertThat(onlyTurn(f, "bad").status()).isEqualTo("REQUEST_TIMEOUT");
        } finally { release.countDown(); }
    }
}
