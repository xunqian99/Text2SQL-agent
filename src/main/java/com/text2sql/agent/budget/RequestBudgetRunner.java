package com.text2sql.agent.budget;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.orchestrator.AgentResponse;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Bounded workers + real cancellation hooks. A timed-out worker retains its slot until it exits. */
public final class RequestBudgetRunner {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("text2sql-request-", 0).factory());
    private static final ThreadPoolExecutor CANCEL = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("text2sql-cancel-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    private RequestBudgetRunner() { }

    public static AgentResponse run(String question, String sessionId, AgentProperties.RequestBudgetConfig config,
                                    Supplier<AgentResponse> action) {
        return run(question, sessionId, config, action, UnaryOperator.identity());
    }

    public static AgentResponse run(String question, String sessionId, AgentProperties.RequestBudgetConfig config,
                                    Supplier<AgentResponse> action, UnaryOperator<AgentResponse> finalizer) {
        // Correction and tool calls reuse the current budget; they never reset its clock.
        if (!config.isEnabled() || RequestBudget.current() != null) return finalizer.apply(action.get());
        RequestBudget budget = new RequestBudget(config);
        budget.onOutcome(finalizer);
        EvaluationTrace trace = EvaluationTrace.current();
        Future<AgentResponse> task;
        try {
            task = WORKERS.submit(() -> {
                try (var scope = RequestBudget.bind(budget); var evidence = EvaluationTrace.bind(trace)) {
                    budget.check();
                    AgentResponse result = action.get();
                    budget.check();
                    return result;
                }
            });
        } catch (RejectedExecutionException busy) {
            budget.finish();
            return budget.stopped(question, sessionId, "WORKER_CAPACITY");
        }
        try {
            AgentResponse result = task.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
            budget.finish();
            return budget.selectOutcome(result).withUsage(budget.usage()).withBudget(budget.snapshot("COMPLETE"));
        } catch (TimeoutException timeout) {
            return stop(task, budget, question, sessionId, "DEADLINE_EXCEEDED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return stop(task, budget, question, sessionId, "CALLER_INTERRUPTED");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RequestBudget.Exceeded exceeded) {
                return stop(task, budget, question, sessionId, exceeded.reason());
            }
            budget.finish();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Request worker failed", cause);
        }
    }

    private static AgentResponse stop(Future<?> task, RequestBudget budget, String question,
                                       String sessionId, String reason) {
        List<Runnable> actions = budget.finish();
        task.cancel(true);
        // Never let a blocking driver cancellation hold the HTTP caller past the deadline.
        for (Runnable action : actions) {
            try {
                CANCEL.execute(() -> {
                    try { action.run(); } catch (Exception ignored) { /* driver/socket timeout remains a backstop */ }
                });
            } catch (RejectedExecutionException saturated) {
                // Keep caller bounded; report incomplete cleanup rather than silently dropping it.
                budget.cancellationRejected();
            }
        }
        return budget.stopped(question, sessionId, reason);
    }
}
