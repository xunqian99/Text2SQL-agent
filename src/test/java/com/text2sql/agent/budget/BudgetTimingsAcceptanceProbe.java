package com.text2sql.agent.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.api.AskController;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real orchestration and generator, synthetic transport/SQL results; never contacts a provider. */
public final class BudgetTimingsAcceptanceProbe {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("Never overwrite evidence");
        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.add(scenario("normal", 2000));
        scenarios.add(scenario("execution-timeout", 500));
        scenarios.add(scenario("correction-timeout", 600));
        scenarios.add(scenario("model-timeout-15s", 15000));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("format", "budget-timings-acceptance-v1");
        evidence.put("provenance", "Synthetic blocking model/SQL fixtures through actual Text2SqlOrchestrator and LlmSqlGenerator");
        evidence.put("providerCalls", 0);
        evidence.put("providerCostYuan", 0);
        evidence.put("scenarios", scenarios);
        Files.writeString(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("BUDGET_TIMINGS scenarios=" + scenarios.size() + " providerCalls=0 providerCost=0");
    }

    private static Map<String, Object> scenario(String name, long timeout) throws Exception {
        var props = new AgentProperties();
        props.setRequestBudget(RequestBudgetTest.config(timeout));
        props.getObservability().setEnabled(false);
        props.getClarification().setEnabled(false);
        props.getCache().setEnabled(false);
        props.getSelfCorrection().setEnabled(name.equals("correction-timeout"));
        props.getResultChecker().setEnabled(name.equals("correction-timeout"));
        props.getSelfCorrection().setMaxAttempts(2);
        var schema = new SchemaContext(List.of(new SchemaContext.Table("orders", null,
                List.of(new SchemaContext.Column("order_id", "text", false, null)))), List.of(), "", "orders");
        var schemas = mock(SchemaProvider.class);
        when(schemas.provide(anyString())).thenAnswer(i -> { Thread.sleep(30); return schema; });
        var validator = mock(SqlValidator.class);
        when(validator.validate(anyString(), any())).thenAnswer(i -> ValidationResult.ok(i.getArgument(0)));
        var executor = mock(SqlExecutor.class);
        when(executor.execute(anyString())).thenAnswer(i -> {
            if (name.equals("execution-timeout")) block();
            Thread.sleep(15);
            return new QueryResult(List.of("order_id"), List.of(List.of("fixture-order")), false, 15);
        });
        var checker = mock(ResultChecker.class);
        when(checker.check(anyString(), anyString(), any())).thenReturn(name.equals("correction-timeout")
                ? CheckResult.suspicious("FIXTURE", "controlled retry trigger") : CheckResult.NORMAL);
        var model = mock(ChatModel.class);
        var transports = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(i -> {
            int call = transports.incrementAndGet();
            if (name.equals("model-timeout-15s") || (name.equals("correction-timeout") && call == 2)) block();
            Thread.sleep(20);
            return BudgetOrchestratorTest.sqlResponse();
        });
        try (var logs = new LlmCallLog(props); var trace = EvaluationTrace.open()) {
            var generator = new LlmSqlGenerator(props, new PromptTemplate(), logs);
            ReflectionTestUtils.setField(generator, "chatModel", model);
            var orchestrator = new Text2SqlOrchestrator(schemas, generator, validator, executor,
                    mock(AmbiguityDetector.class), new SemanticCache(props), checker,
                    mock(com.text2sql.agent.tool.AgentLoop.class), props);
            long started = System.nanoTime();
            AgentResponse response = orchestrator.ask("fixture orders");
            long wall = (System.nanoTime() - started) / 1_000_000;
            var budget = response.budget();
            var timing = response.timings();
            assertThat(timing.totalMs()).isEqualTo(budget.elapsedMs());
            assertThat(timing.retrievalMs()).isGreaterThanOrEqualTo(20);
            assertThat(timing.retrievalMs() + timing.generationMs() + timing.validationMs()
                    + timing.executionMs() + budget.otherMs()).isEqualTo(timing.totalMs());
            assertThat(budget.modelCalls()).isEqualTo(transports.get());
            assertThat(budget.timingsVersion()).isEqualTo(RequestBudget.TIMINGS_VERSION);
            if (name.equals("normal")) {
                assertThat(response.success()).isTrue();
                assertThat(budget.termination()).isEqualTo("COMPLETE");
                assertThat(budget.unknownUsageCalls()).isZero();
            } else {
                assertThat(budget.termination()).isEqualTo("DEADLINE_EXCEEDED");
                assertThat(wall).isBetween(timeout - 30, timeout + 1000);
                assertThat(trace.attempts().getLast().stage()).isEqualTo("REQUEST_STOP");
                assertThat(trace.attempts().getLast().response().timings()).isEqualTo(timing);
                if (name.equals("execution-timeout")) {
                    assertThat(response.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
                    assertThat(budget.stage()).isEqualTo("EXECUTION");
                    assertThat(timing.executionMs()).isGreaterThanOrEqualTo(300);
                    assertThat(budget.unknownUsageCalls()).isZero();
                } else {
                    assertThat(budget.stage()).isEqualTo("MODEL");
                    assertThat(budget.unknownUsageCalls()).isEqualTo(1);
                    assertThat(budget.currentStageMs()).isGreaterThanOrEqualTo(timeout - 250);
                    if (name.equals("correction-timeout")) {
                        assertThat(response.success()).isTrue();
                        assertThat(response.rows()).containsExactly(List.of("fixture-order"));
                        assertThat(transports).hasValue(2);
                        assertThat(trace.selectedAttempt()).isEqualTo(1);
                        assertThat(timing.executionMs()).isGreaterThanOrEqualTo(10);
                    } else {
                        assertThat(response.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
                        assertThat(timing.generationMs()).isGreaterThanOrEqualTo(14500);
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("wallLatencyMs", wall);
            result.put("mockTransportCalls", transports.get());
            result.put("calls", trace.calls());
            result.put("selectedAttempt", trace.selectedAttempt());
            result.put("attemptStages", trace.attempts().stream().map(EvaluationTrace.Attempt::stage).toList());
            result.put("response", AskController.AskResponse.from(response));
            System.out.println(name + " wallMs=" + wall + " timings=" + timing + " stage=" + budget.stage());
            return result;
        }
    }

    private static void block() {
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SqlExecutionException("controlled interrupted fixture", interrupted);
        }
    }
}
