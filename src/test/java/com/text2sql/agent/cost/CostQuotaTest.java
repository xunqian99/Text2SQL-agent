package com.text2sql.agent.cost;

import com.text2sql.agent.budget.RequestBudget;
import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CostQuotaTest {
    @TempDir Path temp;

    static AgentProperties agent() {
        var p = new AgentProperties();
        p.getRequestBudget().setEnabled(true);
        p.getLlm().setInputPricePer1k(1);
        p.getLlm().setOutputPricePer1k(2);
        p.getLlm().setCacheHitPricePer1k(0.1);
        return p;
    }
    CostQuotaProperties config(String total) {
        var c = new CostQuotaProperties(); c.setEnabled(true);
        c.setStateFile(temp.resolve("ledger.json").toString()); c.setTotalYuan(new BigDecimal(total));
        return c;
    }
    static ChatResponse usage(int in, int out) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(new DefaultUsage(in, out)).build());
    }

    @Test void settlementReleasesOnlyUnusedReservationAndIsIdempotent() {
        try (var quota = new CostQuota(config("0.30"), agent())) {
            var first = quota.reserve("request-a", 1, "hash");
            assertThat(quota.snapshot().get("heldYuan")).isEqualTo(new BigDecimal("0.10"));
            first.complete(usage(20, 10)); first.complete(usage(9999, 9999)); first.close();
            assertThat((BigDecimal) quota.snapshot().get("knownCostYuan")).isEqualByComparingTo("0.04");
            assertThat(quota.snapshot().get("knownCalls")).isEqualTo(1L);
            var second = quota.reserve("request-b", 1, "hash");
            var third = quota.reserve("request-c", 1, "hash");
            assertThatThrownBy(() -> quota.reserve("request-d", 1, "hash")).isInstanceOf(RequestBudget.Exceeded.class).hasMessage("COST_QUOTA_EXHAUSTED");
            second.close(); third.close();
            assertThat((BigDecimal) quota.snapshot().get("committedYuan")).isEqualByComparingTo("0.24");
        }
    }

    @Test void unknownUsageAndExceptionsRemainFullyChargedAcrossRestart() {
        var cfg = config("0.20");
        String id;
        try (var quota = new CostQuota(cfg, agent())) {
            id = quota.snapshot().get("budgetId").toString();
            quota.reserve("timeout", 1, "hash").close();
            quota.reserve("missing-usage", 2, "hash").complete(new ChatResponse(List.of()));
            assertThat(quota.snapshot().get("unknownCalls")).isEqualTo(2L);
        }
        try (var restored = new CostQuota(cfg, agent())) {
            assertThat(restored.snapshot().get("budgetId")).isEqualTo(id);
            assertThat(restored.snapshot().get("unknownCalls")).isEqualTo(2L);
            assertThatThrownBy(() -> restored.reserve("new", 1, "hash")).hasMessage("COST_QUOTA_EXHAUSTED");
        }
    }

    @Test void abandonedPendingReservationIsUnknownOnRestartAndOldLeaseCannotChangeReopenedState() {
        var cfg = config("0.20");
        var quota = new CostQuota(cfg, agent());
        var lease = quota.reserve("old-process", 1, "hash");
        assertThat(quota.snapshot().get("pendingCalls")).isEqualTo(1L);
        quota.close();
        try (var restored = new CostQuota(cfg, agent())) {
            assertThat(restored.snapshot().get("pendingCalls")).isEqualTo(0L);
            assertThat(restored.snapshot().get("unknownCalls")).isEqualTo(1L);
            assertThatThrownBy(() -> lease.complete(usage(10, 5))).hasMessage("COST_LEDGER_UNAVAILABLE");
            lease.close();
            assertThat(restored.snapshot().get("unknownCalls")).isEqualTo(1L);
        }
    }

    @Test void provenUnsentCallCanRefundButCannotRefundAfterSettlement() {
        try (var quota = new CostQuota(config("0.10"), agent())) {
            var lease = quota.reserve("not-sent", 1, "hash"); lease.cancelUnsent(); lease.close();
            assertThat(quota.snapshot().get("knownCalls")).isEqualTo(0L);
            assertThat((BigDecimal) quota.snapshot().get("committedYuan")).isEqualByComparingTo("0");
            var sent = quota.reserve("sent", 1, "hash"); sent.complete(usage(10, 5)); sent.cancelUnsent();
            assertThat((BigDecimal) quota.snapshot().get("committedYuan")).isEqualByComparingTo("0.02");
        }
    }

    @Test void actualCostAboveReservationIsNotHiddenAndClosesFurtherAdmission() {
        var cfg = config("1.00");
        try (var quota = new CostQuota(cfg, agent())) {
            quota.reserve("underestimated", 1, "hash").complete(usage(10, 1000));
            assertThat((BigDecimal) quota.snapshot().get("knownCostYuan")).isEqualByComparingTo("2.01");
            assertThat(quota.snapshot().get("reservationBreached")).isEqualTo(true);
            assertThat(quota.snapshot().get("canAdmitCall")).isEqualTo(false);
            assertThatThrownBy(() -> quota.reserve("new", 1, "hash")).hasMessage("COST_QUOTA_EXHAUSTED");
        }
        try (var restored = new CostQuota(cfg, agent())) {
            assertThatThrownBy(() -> restored.reserve("new", 1, "hash")).hasMessage("COST_QUOTA_EXHAUSTED");
        }
    }

    @Test void exactConfiguredCapIsAdmissibleAndParallelRequestsCannotOverspendReservations() throws Exception {
        try (var quota = new CostQuota(config("0.20"), agent()); var pool = Executors.newFixedThreadPool(16)) {
            var ready = new CountDownLatch(16); var release = new CountDownLatch(1); var admitted = new AtomicInteger();
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) tasks.add(pool.submit(() -> {
                CostQuota.Lease lease = null;
                try { lease = quota.reserve("parallel", 1, "hash"); admitted.incrementAndGet(); }
                catch (RequestBudget.Exceeded expected) { assertThat(expected.reason()).isEqualTo("COST_QUOTA_EXHAUSTED"); }
                finally { ready.countDown(); }
                if (lease != null) {
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    finally { lease.close(); }
                }
            }));
            try {
                assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(admitted).hasValue(2);
                assertThat((BigDecimal) quota.snapshot().get("committedYuan")).isEqualByComparingTo("0.20");
            } finally { release.countDown(); }
            for (var task : tasks) task.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void secondProcessOwnerIsRejectedAndLockIsReleasedOnClose() {
        var cfg = config("1.00");
        try (var quota = new CostQuota(cfg, agent())) {
            assertThatThrownBy(() -> new CostQuota(cfg, agent())).hasMessageContaining("cannot be opened or verified");
        }
        try (var reopened = new CostQuota(cfg, agent())) { assertThat(reopened.enabled()).isTrue(); }
    }

    @Test void corruptFileNeverResetsQuotaAndFailedStartupReleasesLock() throws Exception {
        var cfg = config("1.00");
        Files.writeString(Path.of(cfg.getStateFile()), "invalid-json");
        assertThatThrownBy(() -> new CostQuota(cfg, agent())).hasMessageContaining("cannot be opened or verified");
        assertThat(Files.readString(Path.of(cfg.getStateFile()))).isEqualTo("invalid-json");
        assertThatThrownBy(() -> new CostQuota(cfg, agent())).hasMessageContaining("cannot be opened or verified");
    }

    @Test void changedPricesOrLimitCannotReuseLedgerAsANewFreeBudget() {
        var cfg = config("1.00");
        try (var quota = new CostQuota(cfg, agent())) { quota.reserve("one", 1, "hash").close(); }
        var changed = agent(); changed.getLlm().setOutputPricePer1k(3);
        assertThatThrownBy(() -> new CostQuota(cfg, changed)).hasMessageContaining("cannot be opened or verified");
        cfg.setTotalYuan(new BigDecimal("2.00"));
        assertThatThrownBy(() -> new CostQuota(cfg, agent())).hasMessageContaining("cannot be opened or verified");
    }

    @Test void diskFailureDuringAdmissionBlocksFurtherCallsAndPreservesOldDurableState() throws Exception {
        var cfg = config("1.00");
        try (var quota = new CostQuota(cfg, agent())) {
            var before = Files.readString(Path.of(cfg.getStateFile()));
            Files.createDirectory(Path.of(cfg.getStateFile() + ".tmp"));
            assertThatThrownBy(() -> quota.reserve("one", 1, "hash")).hasMessage("COST_LEDGER_UNAVAILABLE");
            assertThat(quota.snapshot().get("ledgerAvailable")).isEqualTo(false);
            assertThatThrownBy(() -> quota.reserve("two", 1, "hash")).hasMessage("COST_LEDGER_UNAVAILABLE");
            assertThat(Files.readString(Path.of(cfg.getStateFile()))).isEqualTo(before);
        }
    }

    @Test void cachedInputUsesSeparateConfiguredPrice() {
        var nativeUsage = mock(OpenAiApi.Usage.class, RETURNS_DEEP_STUBS);
        when(nativeUsage.promptTokensDetails().cachedTokens()).thenReturn(8);
        var usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(10); when(usage.getCompletionTokens()).thenReturn(5);
        when(usage.getNativeUsage()).thenReturn(nativeUsage);
        var response = new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(usage).build());
        try (var quota = new CostQuota(config("1.00"), agent())) {
            quota.reserve("cache", 1, "hash").complete(response);
            assertThat((BigDecimal) quota.snapshot().get("knownCostYuan")).isEqualByComparingTo("0.0128");
        }
    }

    @Test void invalidUsageRemainsUnknownAndZeroPriceCannotEnableQuota() {
        try (var quota = new CostQuota(config("1.00"), agent())) {
            quota.reserve("zero", 1, "hash").complete(usage(0, 0));
            quota.reserve("negative", 1, "hash").complete(usage(-1, 10));
            assertThat(quota.snapshot().get("unknownCalls")).isEqualTo(2L);
        }
        var bad = agent(); bad.getLlm().setInputPricePer1k(0);
        assertThatThrownBy(() -> new CostQuota(config("1.00"), bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void disabledQuotaDoesNotCreateAFileOrInterpretZeroTestPrices() {
        var cfg = config("1.00"); cfg.setEnabled(false);
        try (var quota = new CostQuota(cfg, new AgentProperties())) {
            quota.reserve("ignored", 1, "hash").complete(usage(99999, 99999));
            assertThat(quota.snapshot().get("enabled")).isEqualTo(false);
            assertThat(Path.of(cfg.getStateFile())).doesNotExist();
        }
    }
}
