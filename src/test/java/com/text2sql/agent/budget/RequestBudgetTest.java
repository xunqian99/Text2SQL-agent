package com.text2sql.agent.budget;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBudgetTest {
    private static final String HASH = "a".repeat(64);
    private static final LlmCallRecord USAGE = LlmCallRecord.of("fixture", 10, 5, 20, 1, 1, 1);

    static AgentProperties.RequestBudgetConfig config(long millis) {
        var config = new AgentProperties.RequestBudgetConfig();
        config.setEnabled(true);
        config.setTimeoutMs(millis);
        config.setMinRetryRemainingMs(0);
        return config;
    }

    static AgentResponse answer() {
        return AgentResponse.success("orders", "SELECT 1", false,
                new AgentResponse.QueryOutcome(List.of("count"), List.of(List.of("1")), false),
                USAGE, 0, List.of(), 0, null);
    }

    @Test void recordsKnownUsageAndTransfersEvidenceWithoutLeakingThreadContext() {
        try (var trace = EvaluationTrace.open()) {
            AgentResponse result = RequestBudgetRunner.run("orders", null, config(1000), () -> {
                RequestBudget budget = RequestBudget.current();
                budget.startModelCall(HASH);
                budget.observe(USAGE);
                EvaluationTrace.call(USAGE, "OK", HASH);
                EvaluationTrace.attempt("INITIAL_SQL", answer());
                return answer();
            });
            assertThat(result.budget().modelCalls()).isEqualTo(1);
            assertThat(result.budget().unknownUsageCalls()).isZero();
            assertThat(trace.calls()).hasSize(1);
            assertThat(trace.calls().getFirst().usage()).isEqualTo(USAGE);
            assertThat(trace.attempts()).hasSize(1);
            assertThat(RequestBudget.current()).isNull();
        }
    }

    @Test void deadlineReturnsWhileUncooperativeWorkRetainsItsSlotAndLateEvidenceIsSealed() throws Exception {
        var release = new CountDownLatch(1);
        var hook = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var trace = EvaluationTrace.open()) {
            long started = System.nanoTime();
            AgentResponse result = RequestBudgetRunner.run("orders", null, config(150), () -> {
                RequestBudget budget = RequestBudget.current();
                budget.startModelCall(HASH);
                budget.onCancel(hook::countDown);
                try {
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    EvaluationTrace.call(USAGE, "LATE_OK", HASH);
                    EvaluationTrace.attempt("LATE_SQL", answer());
                    return answer();
                } finally { exited.countDown(); }
            });
            assertThat(result.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
            assertThat(hook.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(trace.calls()).hasSize(1);
            assertThat(trace.calls().getFirst().usage()).isNull();
            List<EvaluationTrace.Attempt> sealed = trace.attempts();
            release.countDown();
            assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(trace.attempts()).isEqualTo(sealed);
            assertThat(trace.calls().getFirst().usage()).isNull();
        } finally { release.countDown(); }
    }

    @Test void successfulFirstAnswerSurvivesCorrectionTimeoutButUnknownSecondCallIsNotFree() {
        try (var trace = EvaluationTrace.open()) {
            AgentResponse result = RequestBudgetRunner.run("orders", null, config(150), () -> {
                RequestBudget budget = RequestBudget.current();
                budget.startModelCall(HASH);
                budget.observe(USAGE);
                EvaluationTrace.call(USAGE, "OK", HASH);
                AgentResponse first = answer();
                EvaluationTrace.attempt("INITIAL_SQL", first);
                RequestBudget.checkpoint(first);
                budget.startModelCall("b".repeat(64));
                try { new CountDownLatch(1).await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                budget.check();
                return answer();
            });
            assertThat(result.success()).isTrue();
            assertThat(result.llmCall()).isEqualTo(USAGE);
            assertThat(result.budget().termination()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(result.budget().modelCalls()).isEqualTo(2);
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
            assertThat(trace.selectedAttempt()).isEqualTo(1);
            assertThat(result.timings().totalMs()).isEqualTo(result.budget().elapsedMs()).isGreaterThanOrEqualTo(140);
        }
    }

    @Test void saturatedCancellationQueueIsReportedAndDoesNotBlockTheCaller() throws Exception {
        var release = new CountDownLatch(1);
        var drained = new CountDownLatch(66);
        try {
            AgentResponse result = RequestBudgetRunner.run("cancel overload", null, config(150), () -> {
                for (int i = 0; i < 70; i++) RequestBudget.current().onCancel(() -> {
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    finally { drained.countDown(); }
                });
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return answer();
            });
            assertThat(result.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
            assertThat(result.budget().cancellationRejected()).isEqualTo(4);
            assertThat(result.budget().elapsedMs()).isLessThan(1000);
        } finally {
            release.countDown();
            assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void lateUsageRemainsReconcilableWithoutRewritingTheSealedResponse(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var props = new AgentProperties();
        props.getObservability().setEnabled(true);
        props.getObservability().setDir(dir.toString());
        var release = new CountDownLatch(1);
        var written = new CountDownLatch(1);
        try (var logs = new com.text2sql.agent.observability.LlmCallLog(props); var trace = EvaluationTrace.open()) {
            AgentResponse result = RequestBudgetRunner.run("late billing", null, config(150), () -> {
                RequestBudget.current().startModelCall(HASH);
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                try { logs.record(USAGE, "OK", "prompt", "response", HASH); }
                finally { written.countDown(); }
                return answer();
            });
            release.countDown();
            assertThat(written.await(2, TimeUnit.SECONDS)).isTrue();
            try (var files = java.nio.file.Files.list(dir)) {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(files.findFirst().orElseThrow()));
                assertThat(json.path("status").asText()).isEqualTo("LATE_OK");
                assertThat(json.path("requestId").asText()).isEqualTo(result.budget().requestId());
                assertThat(json.path("callNumber").asInt()).isEqualTo(1);
                assertThat(json.path("usageKnown").asBoolean()).isTrue();
            }
            assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
            assertThat(trace.calls().getFirst().usage()).isNull();
        } finally { release.countDown(); }
    }

    @Test void nestedEntryCannotResetClockOrModelCallBudget() {
        AtomicInteger transports = new AtomicInteger();
        AgentResponse result = RequestBudgetRunner.run("orders", null, config(1000), () -> {
            RequestBudget outer = RequestBudget.current();
            outer.startModelCall(HASH); transports.incrementAndGet();
            return RequestBudgetRunner.run("correction", null, config(120000), () -> {
                assertThat(RequestBudget.current()).isSameAs(outer);
                outer.startModelCall(HASH); transports.incrementAndGet();
                outer.startModelCall(HASH); transports.incrementAndGet();
                return answer();
            });
        });
        assertThat(result.status()).isEqualTo(AgentResponse.Status.RESOURCE_LIMITED);
        assertThat(result.budget().termination()).isEqualTo("MODEL_CALL_LIMIT");
        assertThat(transports).hasValue(2);
    }

    @Test void insufficientReserveSkipsRetryWithoutStartingAnotherCall() {
        var options = config(1000);
        options.setMinRetryRemainingMs(1000);
        AgentResponse result = RequestBudgetRunner.run("orders", null, options, () -> {
            RequestBudget.current().startModelCall(HASH);
            RequestBudget.current().observe(USAGE);
            assertThat(RequestBudget.allowRetry()).isFalse();
            return answer();
        });
        assertThat(result.budget().modelCalls()).isEqualTo(1);
        assertThat(result.budget().retrySkippedReason()).isEqualTo("INSUFFICIENT_TIME");
    }

    @Test void completedStatementDeregistersAbortBeforeConnectionCanReturnToPool() throws Exception {
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var statement = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        var budget = new RequestBudget(config(1000));
        try (var scope = RequestBudget.bind(budget)) {
            var protectedStatement = BudgetJdbcGuard.protect(connection, statement, 10);
            protectedStatement.close();
            assertThat(budget.finish()).isEmpty();
        }
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).abort(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(statement).close();
    }

    @Test void sixteenOccupiedWorkersRejectTheNextRequestInsteadOfGrowingAnUnboundedQueue() throws Exception {
        var entered = new CountDownLatch(16);
        var release = new CountDownLatch(1);
        var jobs = new java.util.ArrayList<java.util.concurrent.Future<AgentResponse>>();
        try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < 16; i++) jobs.add(callers.submit(() ->
                        RequestBudgetRunner.run("occupy", null, config(5000), () -> {
                            entered.countDown();
                            try { release.await(); }
                            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                            return answer();
                        })));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var overflow = RequestBudgetRunner.run("overflow", null, config(1000), () -> {
                    throw new AssertionError("capacity-rejected work must not start");
                });
                assertThat(overflow.status()).isEqualTo(AgentResponse.Status.RESOURCE_LIMITED);
                assertThat(overflow.budget().termination()).isEqualTo("WORKER_CAPACITY");
                assertThat(overflow.budget().modelCalls()).isZero();
            } finally {
                release.countDown();
                for (var job : jobs) job.get(3, TimeUnit.SECONDS);
            }
        }
    }
}
