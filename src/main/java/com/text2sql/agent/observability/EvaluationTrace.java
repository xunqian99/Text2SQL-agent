package com.text2sql.agent.observability;

import com.text2sql.agent.generation.GenerationException;
import com.text2sql.agent.orchestrator.AgentResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Request-scoped, in-memory evidence for the synchronous evaluation runner.
 * No Gold, callbacks, disk I/O or change to answer selection in the production path.
 * ThreadLocal is deliberately not inherited: asynchronous execution must supply its own context.
 */
public final class EvaluationTrace implements AutoCloseable {
    private static final ThreadLocal<EvaluationTrace> CURRENT = new ThreadLocal<>();
    private final List<Call> calls = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private String trigger = "NONE";
    private int selectedAttempt;
    private boolean sealed;

    private EvaluationTrace() { }

    public static EvaluationTrace open() {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested evaluation trace is not supported");
        EvaluationTrace trace = new EvaluationTrace();
        CURRENT.set(trace);
        return trace;
    }

    public static void call(LlmCallRecord record, String status, String promptSha256) {
        EvaluationTrace trace = CURRENT.get();
        if (trace == null) return;
        synchronized (trace) {
            if (trace.sealed) return;
            int last = trace.calls.size() - 1;
            if (last >= 0 && trace.calls.get(last).status().equals("IN_FLIGHT")
                    && trace.calls.get(last).promptSha256().equals(promptSha256)) {
                trace.calls.set(last, new Call(last + 1, status, promptSha256, record));
            } else trace.calls.add(new Call(trace.calls.size() + 1, status, promptSha256, record));
        }
    }

    public static EvaluationTrace current() { return CURRENT.get(); }

    /** Explicit transfer for the bounded request worker; never use inheritable thread locals. */
    public static AutoCloseable bind(EvaluationTrace trace) {
        if (CURRENT.get() != null) throw new IllegalStateException("Trace already bound");
        if (trace != null) CURRENT.set(trace);
        return CURRENT::remove;
    }

    public static void pendingCall(String hash) {
        EvaluationTrace trace = CURRENT.get();
        if (trace != null) synchronized (trace) {
            if (!trace.sealed) trace.calls.add(new Call(trace.calls.size() + 1, "IN_FLIGHT", hash, null));
        }
    }

    /** Called on the waiting thread before grading; late worker writes can no longer change evidence. */
    public static void stop(AgentResponse stopped, boolean keepFirst) {
        EvaluationTrace trace = CURRENT.get();
        if (trace != null) synchronized (trace) {
            if (trace.sealed) return;
            trace.attempts.add(new Attempt(trace.attempts.size() + 1, "REQUEST_STOP", stopped));
            trace.selectedAttempt = keepFirst && trace.selectedAttempt > 0 ? trace.selectedAttempt : trace.attempts.size();
            trace.sealed = true;
        }
    }

    public static void attempt(String stage, AgentResponse response) {
        EvaluationTrace trace = CURRENT.get();
        if (trace == null) return;
        synchronized (trace) {
            if (trace.sealed) return;
            trace.attempts.add(new Attempt(trace.attempts.size() + 1, stage, response));
            if (trace.selectedAttempt == 0) trace.selectedAttempt = 1;
        }
    }

    public static void generationFailure(String question, GenerationException error) {
        AgentResponse.Status status = error.getReason() == GenerationException.Reason.NOT_CONFIGURED
                ? AgentResponse.Status.NOT_CONFIGURED : AgentResponse.Status.GENERATION_FAILED;
        if (CURRENT.get() != null) attempt("GENERATION", AgentResponse.failed(question, status, null,
                error.getReason().name(), error.getCallRecord(), 0, List.of(), 0, null));
    }

    public static void trigger(String reason) {
        EvaluationTrace trace = CURRENT.get();
        if (trace != null) synchronized (trace) { if (!trace.sealed) trace.trigger = reason; }
    }

    public static void select(int number) {
        EvaluationTrace trace = CURRENT.get();
        if (trace != null) synchronized (trace) { if (!trace.sealed) trace.selectedAttempt = number; }
    }

    public synchronized List<Call> calls() { return List.copyOf(calls); }
    public synchronized List<Attempt> attempts() { return List.copyOf(attempts); }
    public synchronized String trigger() { return trigger; }
    public synchronized int selectedAttempt() { return selectedAttempt; }

    @Override public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("Trace closed outside its owning scope");
        CURRENT.remove();
    }

    /** A null usage means the transport failed before usage was returned, never 'free'. */
    public record Call(int number, String status, String promptSha256, LlmCallRecord usage) { }
    public record Attempt(int number, String stage, AgentResponse response) { }
}
