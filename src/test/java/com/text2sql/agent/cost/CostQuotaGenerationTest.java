package com.text2sql.agent.cost;

import com.text2sql.agent.budget.RequestBudgetRunner;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.GenerationException;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CostQuotaGenerationTest {
    @TempDir Path temp;
    static final SchemaContext SCHEMA = new SchemaContext(List.of(), List.of(), "", "fixture");
    static ChatResponse sql() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("SELECT 1"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
    }
    final class Fixture implements AutoCloseable {
        final AgentProperties agent = CostQuotaTest.agent();
        final ChatModel model = mock(ChatModel.class);
        final CostQuota quota;
        final LlmCallLog logs;
        final LlmSqlGenerator generator;
        Fixture(String total) {
            agent.getRequestBudget().setTimeoutMs(3000);
            agent.getRequestBudget().setMinRetryRemainingMs(0);
            agent.getObservability().setEnabled(false);
            var cfg = new CostQuotaProperties(); cfg.setEnabled(true);
            cfg.setTotalYuan(new BigDecimal(total)); cfg.setStateFile(temp.resolve("ledger.json").toString());
            quota = new CostQuota(cfg, agent);
            logs = new LlmCallLog(agent);
            generator = new LlmSqlGenerator(agent, new PromptTemplate(agent), logs, quota, com.text2sql.agent.retrieval.CatalogFixture.healthy());
            ReflectionTestUtils.setField(generator, "chatModel", model);
            when(model.call(any(Prompt.class))).thenReturn(sql());
        }
        AgentResponse run(Runnable action) {
            return RequestBudgetRunner.run("fixture", null, agent.getRequestBudget(), () -> {
                action.run(); return AgentResponse.clarification("fixture", "controlled completion marker");
            });
        }
        public void close() { logs.close(); quota.close(); }
    }

    @Test void allFourModelEntryPointsUseTheSameLedgerIncludingRewriteAndToolMessages() {
        try (var f = new Fixture("1.00")) {
            f.agent.getRequestBudget().setMaxModelCalls(4);
            var result = f.run(() -> {
                f.generator.generate("fixture", SCHEMA);
                f.generator.generateCorrection("fixture", SCHEMA, "SELECT wrong", "fix");
                f.generator.callMessages(List.of(new UserMessage("rewrite fixture")), "REWRITE", null);
                f.generator.callWithTools(List.of(new UserMessage("tool fixture")), List.of(), "TOOLS", null, null);
            });
            assertThat(result.budget().modelCalls()).isEqualTo(4);
            assertThat(f.quota.snapshot().get("knownCalls")).isEqualTo(4L);
            assertThat((BigDecimal) f.quota.snapshot().get("knownCostYuan")).isEqualByComparingTo("0.08");
            verify(f.model, times(4)).call(any(Prompt.class));
        }
    }

    @Test void rejectedReservationDoesNotCreateAnUnsentModelCallInRequestLedger() {
        try (var f = new Fixture("0.10")) {
            f.quota.reserve("prior-unknown", 1, "hash").close();
            var result = f.run(() -> f.generator.generate("fixture", SCHEMA));
            assertThat(result.status()).isEqualTo(AgentResponse.Status.RESOURCE_LIMITED);
            assertThat(result.budget().termination()).isEqualTo("COST_QUOTA_EXHAUSTED");
            assertThat(result.budget().modelCalls()).isZero();
            assertThat(result.budget().unknownUsageCalls()).isZero();
            verifyNoInteractions(f.model);
        }
    }

    @Test void requestCallCapRefundsTheSecondUnsentReservation() {
        try (var f = new Fixture("1.00")) {
            f.agent.getRequestBudget().setMaxModelCalls(1);
            var result = f.run(() -> { f.generator.generate("one", SCHEMA); f.generator.generate("two", SCHEMA); });
            assertThat(result.budget().termination()).isEqualTo("MODEL_CALL_LIMIT");
            assertThat(result.budget().modelCalls()).isEqualTo(1);
            assertThat((BigDecimal) f.quota.snapshot().get("heldYuan")).isEqualByComparingTo("0");
            assertThat((BigDecimal) f.quota.snapshot().get("knownCostYuan")).isEqualByComparingTo("0.02");
            verify(f.model, times(1)).call(any(Prompt.class));
        }
    }

    @Test void transportFailureHoldsFullReservationAndDoesNotEchoProviderCredential() {
        try (var f = new Fixture("0.10")) {
            when(f.model.call(any(Prompt.class))).thenThrow(new IllegalStateException("Bearer synthetic-provider-secret"));
            assertThatThrownBy(() -> f.generator.generate("fixture", SCHEMA)).isInstanceOf(GenerationException.class)
                    .hasMessage("模型服务调用失败，请核对服务配置或稍后重试。");
            assertThat(f.quota.snapshot().get("unknownCalls")).isEqualTo(1L);
            assertThat((BigDecimal) f.quota.snapshot().get("heldYuan")).isEqualByComparingTo("0.10");
        }
    }

    @Test void knownUsageIsSettledEvenIfSqlCannotBeParsed() {
        try (var f = new Fixture("1.00")) {
            when(f.model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build()));
            assertThatThrownBy(() -> f.generator.generate("fixture", SCHEMA)).isInstanceOf(GenerationException.class);
            assertThat(f.quota.snapshot().get("knownCalls")).isEqualTo(1L);
            assertThat((BigDecimal) f.quota.snapshot().get("knownCostYuan")).isEqualByComparingTo("0.02");
        }
    }

    @Test void globalPriceQuotaRejectsByokAndAlternateModelBeforeAnyTransport() {
        try (var f = new Fixture("1.00")) {
            var byok = f.run(() -> f.generator.generate("fixture", SCHEMA, null, "synthetic-other-key"));
            assertThat(byok.budget().termination()).isEqualTo("BYOK_DISABLED_BY_COST_QUOTA");
            var model = f.run(() -> f.generator.generate("fixture", SCHEMA, "other-model"));
            assertThat(model.budget().termination()).isEqualTo("MODEL_DISABLED_BY_COST_QUOTA");
            assertThat(f.quota.snapshot().get("knownCalls")).isEqualTo(0L);
            verifyNoInteractions(f.model);
        }
    }

    @Test void timeoutKeepsReservationUntilLateKnownResultAndDoesNotRewriteAlreadyReturnedUnknownUsage() throws Exception {
        var release = new CountDownLatch(1);
        var settled = new CountDownLatch(1);
        try (var f = new Fixture("0.10")) {
            f.agent.getRequestBudget().setTimeoutMs(400);
            when(f.model.call(any(Prompt.class))).thenAnswer(i -> {
                boolean interrupted = false;
                while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
                return sql();
            });
            var result = f.run(() -> {
                try { f.generator.generate("slow", SCHEMA); } finally { settled.countDown(); }
            });
            assertThat(result.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
            assertThat(f.quota.snapshot().get("pendingCalls")).isEqualTo(1L);
            assertThat((BigDecimal) f.quota.snapshot().get("heldYuan")).isEqualByComparingTo("0.10");
            release.countDown();
            assertThat(settled.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(f.quota.snapshot().get("ledgerAvailable")).isEqualTo(true);
            assertThat(f.quota.snapshot().get("knownCalls")).isEqualTo(1L);
            assertThat((BigDecimal) f.quota.snapshot().get("heldYuan")).isEqualByComparingTo("0");
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
        } finally { release.countDown(); }
    }
}
