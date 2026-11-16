package com.text2sql.agent.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.api.AskController;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetTimingsTest {
    private static final String HASH = "c".repeat(64);
    private static final LlmCallRecord USAGE = LlmCallRecord.of("fixture", 10, 5, 20, 1, 1, 1);

    @Test void modelTimeoutRetainsCompletedRetrievalAndTheInterruptedModelWait() {
        try (var trace = EvaluationTrace.open()) {
            var result = RequestBudgetRunner.run("fixture", null, RequestBudgetTest.config(400), () -> {
                RequestBudget.stage("RETRIEVAL");
                pause(30);
                RequestBudget.stage("GENERATION");
                RequestBudget.current().startModelCall(HASH);
                awaitCancellation();
                return RequestBudgetTest.answer();
            });
            assertThat(result.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
            assertThat(result.budget().stage()).isEqualTo("MODEL");
            assertThat(result.timings().retrievalMs()).isGreaterThanOrEqualTo(20);
            assertThat(result.timings().generationMs()).isGreaterThanOrEqualTo(250);
            assertThat(result.timings().executionMs()).isZero();
            assertThat(result.budget().modelCalls()).isEqualTo(1);
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
            assertThat(trace.attempts().getLast().response().timings()).isEqualTo(result.timings());
            assertThat(trace.attempts().getLast().response().budget()).isEqualTo(result.budget());
            assertPartition(result);
        }
    }

    @Test void completedRequestProjectsExclusiveStagesInsteadOfAddingAttemptTimers() throws Exception {
        var result = RequestBudgetRunner.run("fixture", null, RequestBudgetTest.config(3000), () -> {
            for (String stage : new String[]{"RETRIEVAL", "REWRITE", "GENERATION", "VALIDATION",
                    "RESULT_CHECK", "EXPLAIN", "EXECUTION", "CACHE_WRITE", "TOOL"}) {
                RequestBudget.stage(stage);
                pause(10);
            }
            // These deliberately wrong legacy values must not contaminate the shared request clock.
            return legacyAnswer();
        });
        assertThat(result.budget().termination()).isEqualTo("COMPLETE");
        assertThat(result.timings().retrievalMs()).isGreaterThanOrEqualTo(5);
        assertThat(result.timings().generationMs()).isGreaterThanOrEqualTo(10);
        assertThat(result.timings().validationMs()).isGreaterThanOrEqualTo(10);
        assertThat(result.timings().executionMs()).isGreaterThanOrEqualTo(10);
        assertThat(result.timings().totalMs()).isLessThan(1500);
        assertPartition(result);
        var json = new ObjectMapper().valueToTree(AskController.AskResponse.from(result));
        assertThat(json.path("budget").path("timingsVersion").asText()).isEqualTo("request-budget-exclusive-v1");
        assertThat(json.path("budget").path("otherMs").asLong()).isGreaterThanOrEqualTo(10);
        assertThat(json.path("timings").path("generationMs").asLong()).isEqualTo(result.timings().generationMs());
    }

    @Test void correctionTimeoutKeepsFirstAnswerButIncludesBothCallsInRequestTimings() {
        try (var trace = EvaluationTrace.open()) {
            var result = RequestBudgetRunner.run("fixture", null, RequestBudgetTest.config(400), () -> {
                var budget = RequestBudget.current();
                RequestBudget.stage("RETRIEVAL");
                pause(20);
                budget.startModelCall(HASH);
                pause(20);
                budget.recordCall(USAGE, "OK", HASH);
                RequestBudget.stage("EXECUTION");
                pause(20);
                var first = RequestBudgetTest.answer();
                EvaluationTrace.attempt("INITIAL_SQL", first);
                RequestBudget.checkpoint(first);
                RequestBudget.stage("RETRIEVAL");
                pause(20);
                budget.startModelCall("d".repeat(64));
                awaitCancellation();
                return first;
            });
            assertThat(result.success()).isTrue();
            assertThat(result.sql()).isEqualTo("SELECT 1");
            assertThat(result.budget().modelCalls()).isEqualTo(2);
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
            assertThat(result.llmCall()).isEqualTo(USAGE);
            assertThat(result.timings().generationMs()).isGreaterThanOrEqualTo(200);
            assertThat(result.timings().retrievalMs()).isGreaterThanOrEqualTo(30);
            assertThat(result.timings().executionMs()).isGreaterThanOrEqualTo(10);
            assertThat(result.budget().currentStageMs()).isGreaterThanOrEqualTo(150);
            assertThat(result.budget().stageMs().get("MODEL") - result.budget().currentStageMs())
                    .as("completed first call remains distinct from the interrupted second visit")
                    .isGreaterThanOrEqualTo(10);
            assertThat(trace.selectedAttempt()).isEqualTo(1);
            assertThat(trace.attempts().getLast().response().timings()).isEqualTo(result.timings());
            assertPartition(result);
        }
    }

    @Test void callerInterruptionPreservesItsStageAndInterruptFlag() throws Exception {
        var entered = new CountDownLatch(1);
        var result = new AtomicReference<AgentResponse>();
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().daemon().start(() -> {
            try {
                result.set(RequestBudgetRunner.run("fixture", null, RequestBudgetTest.config(5000), () -> {
                    RequestBudget.stage("RETRIEVAL");
                    pause(20);
                    RequestBudget.current().startModelCall(HASH);
                    entered.countDown();
                    awaitCancellation();
                    return RequestBudgetTest.answer();
                }));
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable problem) { failure.set(problem); }
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            pause(30);
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(interrupted).isTrue();
            var response = result.get();
            assertThat(response.budget().termination()).isEqualTo("CALLER_INTERRUPTED");
            assertThat(response.budget().stage()).isEqualTo("MODEL");
            assertThat(response.timings().retrievalMs()).isGreaterThanOrEqualTo(10);
            assertThat(response.timings().generationMs()).isGreaterThanOrEqualTo(20);
            assertPartition(response);
        } finally { caller.interrupt(); caller.join(2000); }
    }

    @Test void lateUncooperativeWorkerCannotGrowOrRewriteTheFrozenTimings() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var budgetRef = new AtomicReference<RequestBudget>();
        try {
            var result = RequestBudgetRunner.run("fixture", null, RequestBudgetTest.config(200), () -> {
                var budget = RequestBudget.current();
                budgetRef.set(budget);
                budget.startModelCall(HASH);
                try {
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    return RequestBudgetTest.answer();
                } finally { exited.countDown(); }
            });
            assertThat(result.timings().generationMs()).isGreaterThanOrEqualTo(150);
            var frozen = result.budget();
            pause(30);
            assertThat(budgetRef.get().snapshot(frozen.termination())).isEqualTo(frozen);
            release.countDown();
            assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(budgetRef.get().snapshot(frozen.termination())).isEqualTo(frozen);
            assertPartition(result);
        } finally { release.countDown(); }
    }

    @Test void nestedCorrectionUsesOneClockRatherThanSummingOverlappingAttempts() {
        var result = RequestBudgetRunner.run("fixture", null, RequestBudgetTest.config(3000), () -> {
            var outer = RequestBudget.current();
            RequestBudget.stage("RETRIEVAL");
            pause(15);
            outer.startModelCall(HASH);
            pause(15);
            outer.observe(USAGE);
            var second = RequestBudgetRunner.run("correction", null, RequestBudgetTest.config(120000), () -> {
                assertThat(RequestBudget.current()).isSameAs(outer);
                RequestBudget.stage("RETRIEVAL");
                pause(15);
                outer.startModelCall("e".repeat(64));
                pause(15);
                outer.observe(USAGE);
                return legacyAnswer();
            });
            return AgentResponse.combineAttempts(legacyAnswer(), second);
        });
        assertThat(result.budget().modelCalls()).isEqualTo(2);
        assertThat(result.llmCall().totalTokens()).isEqualTo(30);
        assertThat(result.timings().totalMs()).isLessThan(1500);
        assertThat(result.timings().retrievalMs()).isGreaterThanOrEqualTo(20);
        assertThat(result.timings().generationMs()).isGreaterThanOrEqualTo(20);
        assertPartition(result);
    }

    @Test void disabledBudgetLeavesLegacyTimingsUntouched() {
        var config = RequestBudgetTest.config(1000);
        config.setEnabled(false);
        var legacy = legacyAnswer();
        var result = RequestBudgetRunner.run("fixture", null, config, () -> legacy);
        assertThat(result).isSameAs(legacy);
        assertThat(result.budget()).isNull();
        assertThat(result.withBudget(null).timings()).isEqualTo(legacy.timings());
    }

    @Test void timeoutDuringRetrievalDoesNotInventAModelCall() {
        var result = RequestBudgetRunner.run("fixture", null, RequestBudgetTest.config(200), () -> {
            RequestBudget.stage("RETRIEVAL");
            awaitCancellation();
            return RequestBudgetTest.answer();
        });
        assertThat(result.budget().stage()).isEqualTo("RETRIEVAL");
        assertThat(result.timings().retrievalMs()).isGreaterThanOrEqualTo(150);
        assertThat(result.timings().generationMs()).isZero();
        assertThat(result.budget().modelCalls()).isZero();
        assertPartition(result);
    }

    @Test void timeoutPreparingGenerationIsDistinctFromTransportWaitAndHasNoUnknownUsage() {
        var result = RequestBudgetRunner.run("fixture", null, RequestBudgetTest.config(200), () -> {
            RequestBudget.stage("RETRIEVAL");
            pause(20);
            RequestBudget.stage("GENERATION");
            awaitCancellation();
            return RequestBudgetTest.answer();
        });
        assertThat(result.budget().stage()).isEqualTo("GENERATION");
        assertThat(result.timings().generationMs()).isGreaterThanOrEqualTo(100);
        assertThat(result.budget().stageMs()).doesNotContainKey("MODEL");
        assertThat(result.budget().modelCalls()).isZero();
        assertThat(result.budget().unknownUsageCalls()).isZero();
        assertPartition(result);
    }

    private static AgentResponse legacyAnswer() {
        return AgentResponse.success("fixture", "SELECT 1", false,
                new AgentResponse.QueryOutcome(java.util.List.of("count"), java.util.List.of(java.util.List.of("1")), false),
                USAGE, 0, java.util.List.of(), 0, new AgentResponse.Timings(9000, 9000, 9000, 9000, 36000));
    }

    private static void assertPartition(AgentResponse response) {
        var b = response.budget();
        var t = response.timings();
        assertThat(t.totalMs()).isEqualTo(b.elapsedMs());
        assertThat(t.retrievalMs()).isEqualTo(b.stageMs().getOrDefault("RETRIEVAL", 0L));
        assertThat(t.generationMs()).isEqualTo(sum(b, "REWRITE", "GENERATION", "MODEL"));
        assertThat(t.validationMs()).isEqualTo(sum(b, "VALIDATION", "RESULT_CHECK"));
        assertThat(t.executionMs()).isEqualTo(sum(b, "EXPLAIN", "EXECUTION"));
        long projected = t.retrievalMs() + t.generationMs() + t.validationMs() + t.executionMs();
        assertThat(projected).isLessThanOrEqualTo(t.totalMs());
        assertThat(b.timingsVersion()).isEqualTo(RequestBudget.TIMINGS_VERSION);
        assertThat(b.otherMs()).isEqualTo(t.totalMs() - projected).isGreaterThanOrEqualTo(0);
        assertThat(b.currentStageMs()).isBetween(0L, b.stageMs().get(b.stage()));
        long exclusiveStages = b.stageMs().values().stream().mapToLong(Long::longValue).sum();
        // Only per-stage millisecond flooring may separate the raw partition from the whole clock.
        assertThat(t.totalMs() - exclusiveStages).isBetween(0L, (long) b.stageMs().size());
    }

    private static long sum(RequestBudget.Snapshot b, String... stages) {
        long total = 0;
        for (String stage : stages) total += b.stageMs().getOrDefault(stage, 0L);
        return total;
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixture interrupted", interrupted);
        }
    }

    private static void awaitCancellation() {
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        RequestBudget.current().check();
    }
}
