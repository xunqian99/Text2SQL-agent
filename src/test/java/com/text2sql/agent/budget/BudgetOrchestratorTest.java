package com.text2sql.agent.budget;

import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutionException;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.validation.CheckResult;
import com.text2sql.agent.validation.ResultChecker;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BudgetOrchestratorTest {
    @Test void successfulResponseWithoutProviderUsageIsNotRecordedAsFree() {
        var props = new AgentProperties();
        props.getObservability().setEnabled(false);
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("SELECT 1")))));
        try (var logs = new LlmCallLog(props); var trace = EvaluationTrace.open()) {
            var generator = new LlmSqlGenerator(props, new PromptTemplate(), logs, com.text2sql.agent.retrieval.CatalogFixture.healthy());
            ReflectionTestUtils.setField(generator, "chatModel", model);
            var result = RequestBudgetRunner.run("orders", null, RequestBudgetTest.config(3000), () -> {
                generator.generate("orders", new SchemaContext(List.of(), List.of(), "", "orders"));
                return RequestBudgetTest.answer();
            });
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
            assertThat(result.llmCall()).isNull();
            assertThat(trace.calls().getFirst().usage()).isNull();
            assertThat(trace.calls().getFirst().status()).contains("UNKNOWN_USAGE");
        }
    }

    static ChatResponse sqlResponse() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("SELECT order_id FROM orders LIMIT 10"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
    }

    @Test void slowExecutionStopsWithinSharedDeadlineAndPreservesAlreadyPaidModelUsage() throws Exception {
        var props = new AgentProperties();
        props.setRequestBudget(RequestBudgetTest.config(500));
        props.getSelfCorrection().setEnabled(true);
        props.getObservability().setEnabled(false);
        var schemas = mock(SchemaProvider.class);
        var validator = mock(SqlValidator.class);
        var executor = mock(SqlExecutor.class);
        var model = mock(ChatModel.class);
        var exited = new CountDownLatch(1);
        var schema = new SchemaContext(List.of(new SchemaContext.Table("orders", null,
                List.of(new SchemaContext.Column("order_id", "text", false, null)))), List.of(), "", "orders");
        when(schemas.provide(anyString())).thenAnswer(i -> { Thread.sleep(30); return schema; });
        when(schemas.fullSchema()).thenReturn(schema);
        when(validator.validate(anyString(), any())).thenAnswer(i -> ValidationResult.ok(i.getArgument(0)));
        when(model.call(any(Prompt.class))).thenReturn(sqlResponse());
        when(executor.execute(anyString())).thenAnswer(i -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException interrupted) { throw new SqlExecutionException("cancelled", interrupted); }
            finally { exited.countDown(); }
            return null;
        });
        try (var logs = new LlmCallLog(props); var trace = EvaluationTrace.open()) {
            var generator = new LlmSqlGenerator(props, new PromptTemplate(), logs, com.text2sql.agent.retrieval.CatalogFixture.healthy());
            ReflectionTestUtils.setField(generator, "chatModel", model);
            var orchestrator = new Text2SqlOrchestrator(schemas, com.text2sql.agent.retrieval.CatalogFixture.from(schemas::fullSchema), generator, validator, executor,
                    mock(AmbiguityDetector.class), new SemanticCache(props), new ResultChecker(),
                    mock(com.text2sql.agent.tool.AgentLoop.class), props);
            long started = System.nanoTime();
            AgentResponse result = orchestrator.ask("订单有哪些");
            assertThat(result.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
            assertThat(result.budget().stage()).isEqualTo("EXECUTION");
            assertThat(result.budget().unknownUsageCalls()).isZero();
            assertThat(result.llmCall().totalTokens()).isEqualTo(15);
            assertThat(result.retrievedTables()).containsExactly("orders");
            assertThat(result.timings().retrievalMs()).isGreaterThanOrEqualTo(20);
            assertThat(result.timings().executionMs()).isGreaterThanOrEqualTo(150);
            assertThat(result.timings().executionMs()).isEqualTo(result.budget().stageMs().get("EXECUTION"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
            assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue();
            verify(model, times(1)).call(any(Prompt.class));
        }
    }

    @Test void everyGeneratorEntryPointSharesOneCallCap() {
        var props = new AgentProperties();
        props.getObservability().setEnabled(false);
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(sqlResponse());
        try (var logs = new LlmCallLog(props); var trace = EvaluationTrace.open()) {
            var generator = new LlmSqlGenerator(props, new PromptTemplate(), logs, com.text2sql.agent.retrieval.CatalogFixture.healthy());
            ReflectionTestUtils.setField(generator, "chatModel", model);
            var schema = new SchemaContext(List.of(), List.of(), "", "orders");
            var options = RequestBudgetTest.config(3000);
            options.setMaxModelCalls(4);
            AgentResponse result = RequestBudgetRunner.run("orders", null, options, () -> {
                generator.generate("orders", schema);
                generator.generateCorrection("orders", schema, "SELECT 0", "retry");
                generator.callMessages(List.of(new org.springframework.ai.chat.messages.UserMessage("orders")), "REWRITE", null);
                generator.callWithTools(List.of(new org.springframework.ai.chat.messages.UserMessage("orders")), List.of(), "TOOLS", null, null);
                generator.generate("one call too many", schema);
                return RequestBudgetTest.answer();
            });
            assertThat(result.budget().termination()).isEqualTo("MODEL_CALL_LIMIT");
            assertThat(result.budget().modelCalls()).isEqualTo(4);
            assertThat(result.budget().unknownUsageCalls()).isZero();
            assertThat(result.llmCall().totalTokens()).isEqualTo(60);
            assertThat(trace.calls()).hasSize(4);
            verify(model, times(4)).call(any(Prompt.class));
        }
    }
}
