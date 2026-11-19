package com.text2sql.agent.budget;

import com.text2sql.agent.generation.*;

import com.text2sql.agent.budget.RequestBudget;
import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.cost.*;
import com.text2sql.agent.execution.*;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.orchestrator.*;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.session.*;
import com.text2sql.agent.tool.*;
import com.text2sql.agent.validation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Count real LlmSqlGenerator -> ChatModel.call, never mock the generator itself. */
class CatalogSendBoundaryTest {
    @TempDir Path temp;
    static final SchemaContext FULL = new SchemaContext(List.of(new SchemaContext.Table("orders", null,
            List.of(new SchemaContext.Column("order_id", "text", false, null)))), List.of(), "", "FULL");
    static final SchemaContext PRUNED = new SchemaContext(List.of(new SchemaContext.Table("orders", null,
            List.of(new SchemaContext.Column("order_id", "text", false, null)))), List.of(), "", "PRUNED");
    static final String SQL = "SELECT order_id FROM orders LIMIT 5";
    static ChatResponse response(String text, boolean known) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), known
                ? ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build()
                : ChatResponseMetadata.builder().build());
    }

    final class Fixture implements AutoCloseable {
        final AgentProperties p = new AgentProperties();
        final AtomicReference<String> fault = new AtomicReference<>("healthy");
        final AtomicInteger sends = new AtomicInteger();
        final SchemaCatalog source = mock(SchemaCatalog.class);
        final AuthorizedSchemaCatalog catalog = new AuthorizedSchemaCatalog(source);
        final ChatModel model = mock(ChatModel.class);
        final SqlExecutor db = mock(SqlExecutor.class);
        final QueryPlanAnalyzer plans = mock(QueryPlanAnalyzer.class);
        final LlmCallLog logs;
        final CostQuota quota;
        final LlmSqlGenerator gen;
        final ToolExecutor tools;
        final AgentLoop loop;
        final SessionStore sessions;
        final Text2SqlOrchestrator orch;

        Fixture(boolean requestBudget, boolean costQuota, boolean byok) {
            p.getRequestBudget().setEnabled(requestBudget);
            p.getRequestBudget().setTimeoutMs(5000); p.getRequestBudget().setMinRetryRemainingMs(0);
            p.getSelfCorrection().setEnabled(true); p.getSelfCorrection().setMaxAttempts(2);
            p.getClarification().setEnabled(false); p.getResultChecker().setEnabled(false);
            p.getObservability().setEnabled(false); p.getLlm().setBaseUrl("http://127.0.0.1:1");
            p.getLlm().setApiKey(""); p.getLlm().setInputPricePer1k(1); p.getLlm().setOutputPricePer1k(2);
            when(source.full()).thenAnswer(i -> switch (fault.get()) {
                case "null" -> null;
                case "empty" -> new SchemaContext(List.of(), List.of(), "", "");
                case "throws" -> throw new IllegalStateException("secret-db-detail");
                case "unimplemented" -> throw new UnsupportedOperationException("not implemented");
                case "malformed" -> new SchemaContext(List.of(new SchemaContext.Table("orders", null, List.of())), List.of(), "", "");
                default -> FULL;
            });
            var q = new CostQuotaProperties(); q.setEnabled(costQuota);
            q.setTotalYuan(new BigDecimal("10")); q.setStateFile(temp.resolve(UUID.randomUUID()+".json").toString());
            quota = new CostQuota(q, p); logs = new LlmCallLog(p);
            gen = new LlmSqlGenerator(p, new PromptTemplate(p), logs, quota, catalog);
            ReflectionTestUtils.setField(gen, "chatModel", model);
            if (byok) {
                @SuppressWarnings("unchecked") var pool = (Map<String, ChatModel>)ReflectionTestUtils.getField(gen, "dynamicChatModels");
                pool.put("local-fixture-key", model);
            }
            when(model.call(any(Prompt.class))).thenAnswer(i -> { sends.incrementAndGet(); return response(SQL, true); });
            when(db.execute(anyString())).thenReturn(new QueryResult(List.of("order_id"), List.of(List.of("o1")), false, 1));
            when(plans.analyze(anyString())).thenReturn(QueryPlanAnalyzer.PlanAnalysis.safe(1, 1, "{}"));
            var validator = new SqlValidator(p);
            tools = new ToolExecutor(catalog, db, validator, plans);
            loop = new AgentLoop(gen, new PromptTemplate(p), tools, p);
            sessions = new SessionStore(p);
            SchemaProvider retrieval = question -> PRUNED; // fullSchema intentionally unimplemented: execution never asks retrieval for authority.
            orch = new Text2SqlOrchestrator(retrieval, catalog, gen, validator, db, mock(AmbiguityDetector.class),
                    new SemanticCache(p), new ResultChecker(), loop, sessions, new QuestionRewriter(gen, p), plans, p);
        }
        void entry(String path, String key) {
            switch (path) {
                case "generate" -> gen.generate("orders", PRUNED, null, key);
                case "correction" -> gen.generateCorrection("orders", PRUNED, "SELECT wrong", "fix", null, key);
                case "rewrite" -> gen.callMessages(List.of(new UserMessage("rewrite")), "REWRITE", null, key);
                case "tool" -> gen.callWithTools(List.of(new UserMessage("tools")), List.of(), "AGENT_FINALIZE", null, key);
                default -> throw new AssertionError(path);
            }
        }
        public void close() { logs.close(); quota.close(); }
    }

    static Stream<Arguments> unavailableEntries() {
        var cases = new ArrayList<Arguments>();
        for (String path : List.of("generate", "correction", "rewrite", "tool"))
            for (String fault : List.of("null", "empty", "throws", "unimplemented", "malformed"))
                for (String config : List.of("no-budget", "budget", "quota", "both", "byok", "byok-budget"))
                    cases.add(Arguments.of(path, fault, config));
        return cases.stream();
    }

    @ParameterizedTest @MethodSource("unavailableEntries")
    void everyEntryRejectsBeforeSendReservationAndCallCount(String path, String fault, String config) throws Exception {
        boolean budgetOn = List.of("budget", "both", "byok-budget").contains(config);
        boolean quotaOn = List.of("quota", "both").contains(config);
        boolean byok = config.startsWith("byok");
        try (var f = new Fixture(budgetOn || quotaOn, quotaOn, byok)) {
            f.fault.set(fault);
            var budget = new RequestBudget(f.p.getRequestBudget());
            try (var scope = RequestBudget.bind(budgetOn ? budget : null)) {
                assertThatThrownBy(() -> f.entry(path, byok ? "local-fixture-key" : null))
                        .isInstanceOfSatisfying(GenerationException.class, e ->
                                assertThat(e.getReason()).isEqualTo(GenerationException.Reason.SCHEMA_UNAVAILABLE))
                        .hasMessageNotContaining("secret-db-detail");
            }
            assertThat(f.sends).hasValue(0); verifyNoInteractions(f.model, f.db, f.plans);
            assertThat(budget.snapshot("CHECK").modelCalls()).isZero();
            if (quotaOn) {
                assertThat(f.quota.snapshot()).containsEntry("knownCalls", 0L).containsEntry("unknownCalls", 0L).containsEntry("pendingCalls", 0L);
                assertThat((BigDecimal)f.quota.snapshot().get("heldYuan")).isEqualByComparingTo("0");
            }
        }
    }

    static void unavailable(AgentResponse result) {
        assertThat(result.status()).isEqualTo(AgentResponse.Status.REJECTED);
        assertThat(result.violations()).anyMatch(v -> v.startsWith("[SCHEMA_UNAVAILABLE]"));
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void firstGenerationFailureKeepsKnownOrUnknownUsageWithoutRetry(boolean known) {
        try (var f = new Fixture(true, true, false)) {
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> {
                f.sends.incrementAndGet(); f.fault.set("null");
                if (!known) throw new IllegalStateException("synthetic transport failed after start");
                return response("no sql here", true);
            });
            var r = f.orch.ask("列出订单"); unavailable(r);
            assertThat(f.sends).hasValue(1); assertThat(r.budget().modelCalls()).isEqualTo(1);
            assertThat(r.budget().unknownUsageCalls()).isEqualTo(known ? 0 : 1);
            assertThat(f.quota.snapshot()).containsEntry("knownCalls", known ? 1L : 0L)
                    .containsEntry("unknownCalls", known ? 0L : 1L).containsEntry("pendingCalls", 0L);
            if (known) assertThat(r.llmCall().totalTokens()).isEqualTo(15);
            verifyNoInteractions(f.db, f.plans);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void automaticCorrectionStopsAfterFirstResponseAndPreservesItsUsage(boolean budgetOn) {
        try (var f = new Fixture(budgetOn, budgetOn, false)) {
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> {
                f.sends.incrementAndGet(); f.fault.set("null"); return response(SQL, true);
            });
            var r = f.orch.ask("列出订单"); unavailable(r);
            assertThat(f.sends).hasValue(1); assertThat(r.llmCall().totalTokens()).isEqualTo(15);
            verifyNoInteractions(f.db, f.plans);
        }
    }

    static Stream<Arguments> toolTransitions() {
        var cases = new ArrayList<Arguments>();
        for (String tool : List.of("SAMPLE_QUERY", "UNKNOWN_TOOL"))
            for (boolean budget : List.of(false, true))
                for (int rounds : List.of(1, 2))
                    for (boolean failAfterSend : List.of(false, true))
                        cases.add(Arguments.of(tool, budget, rounds, failAfterSend));
        return cases.stream();
    }

    @ParameterizedTest @MethodSource("toolTransitions")
    void malformedAndUnknownToolsCannotBypassSendGate(String tool, boolean budgetOn, int rounds, boolean failAfterSend) throws Exception {
        try (var f = new Fixture(budgetOn, budgetOn, false)) {
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> {
                int n = f.sends.incrementAndGet();
                if (n > 1) return response(SQL, true);
                if (failAfterSend) f.fault.set("null");
                var output = AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("a", "function", tool, "{broken-json"))).build();
                return new ChatResponse(List.of(new Generation(output)),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(10,5)).build());
            });
            var budget = new RequestBudget(f.p.getRequestBudget());
            try (var scope = RequestBudget.bind(budgetOn ? budget : null)) {
                if (failAfterSend) {
                    assertThatThrownBy(() -> f.loop.run("orders", PRUNED, rounds, null))
                            .isInstanceOfSatisfying(GenerationException.class, e -> {
                                assertThat(e.getReason()).isEqualTo(GenerationException.Reason.SCHEMA_UNAVAILABLE);
                                assertThat(e.getCallRecord().totalTokens()).isEqualTo(15);
                            });
                    assertThat(f.sends).hasValue(1);
                } else {
                    var r = f.loop.run("orders", PRUNED, rounds, null);
                    assertThat(r.sql()).isEqualTo(SQL); assertThat(r.call().totalTokens()).isEqualTo(30);
                    assertThat(f.sends).hasValue(2);
                }
            }
            if (f.quota.enabled()) assertThat(f.quota.snapshot()).containsEntry("pendingCalls", 0L).containsEntry("unknownCalls", 0L);
            verifyNoInteractions(f.db, f.plans);
        }
    }

    @Test void unimplementedRetrievalFullSchemaDoesNotReplaceSharedCatalogAuthority() {
        try (var f = new Fixture(true, false, false)) {
            assertThat(f.orch.ask("列出订单").success()).isTrue();
            assertThat(f.sends).hasValue(1); verify(f.db).execute(SQL);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void explicitCorrectionRevalidatesAndKeepsAlreadySentUsage(boolean budgetOn) {
        try (var f = new Fixture(budgetOn, budgetOn, false)) {
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> {
                f.sends.incrementAndGet(); f.fault.set("null"); return response(SQL, true);
            });
            var result = f.orch.askWithCorrection("列出订单", "SELECT bad", "feedback");
            unavailable(result); assertThat(f.sends).hasValue(1);
            assertThat(result.llmCall().totalTokens()).isEqualTo(15); verifyNoInteractions(f.db, f.plans);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void rewriteFallbackRetainsItsUsageWhenCatalogFailsAfterFirstCall(boolean budgetOn) {
        try (var f = new Fixture(budgetOn, budgetOn, false)) {
            f.p.getConversation().setEnabled(true); f.p.getConversation().setRewriteWithLlm(true);
            var session = f.sessions.getOrCreate("rewrite-test");
            session.addTurn(ConversationTurn.of(1, "2018年有多少订单", "2018年有多少订单", SQL, "SUCCESS", "1 row"), 5);
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> {
                f.sends.incrementAndGet(); f.fault.set("null");
                // Existing callMessages SQL extraction fails on prose; rule fallback
                // must retain this paid record before the next catalog gate terminates.
                return response("2017年有多少订单", true);
            });
            var result = f.orch.ask("那2017年的呢？", "rewrite-test");
            unavailable(result); assertThat(f.sends).hasValue(1);
            assertThat(result.llmCall().totalTokens()).isEqualTo(15); verifyNoInteractions(f.db, f.plans);
        }
    }

    @Test void rewriteCatalogFailureIsTerminalWithoutRuleFallbackOrGeneration() {
        try (var f = new Fixture(true, true, false)) {
            f.p.getConversation().setEnabled(true); f.p.getConversation().setRewriteWithLlm(true);
            var session = f.sessions.getOrCreate("rewrite-unavailable");
            session.addTurn(ConversationTurn.of(1, "2018年有多少订单", "2018年有多少订单", SQL, "SUCCESS", "1 row"), 5);
            f.fault.set("null");
            var result = f.orch.ask("那2017年的呢？", "rewrite-unavailable");
            unavailable(result); assertThat(result.budget().modelCalls()).isZero();
            verifyNoInteractions(f.model, f.db, f.plans);
            assertThat(f.quota.snapshot()).containsEntry("knownCalls", 0L).containsEntry("unknownCalls", 0L).containsEntry("pendingCalls", 0L);
        }
    }
}
