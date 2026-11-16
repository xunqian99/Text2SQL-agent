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

    private EvaluationTrace() { }

    public static EvaluationTrace open() {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested evaluation trace is not supported");
        EvaluationTrace trace = new EvaluationTrace();
        CURRENT.set(trace);
        return trace;
    }

    public static void call(LlmCallRecord record, String status, String promptSha256) {
        EvaluationTrace trace = CURRENT.get();
        if (trace != null) trace.calls.add(new Call(trace.calls.size() + 1, status, promptSha256, record));
    }

    public static void attempt(String stage, AgentResponse response) {
        EvaluationTrace trace = CURRENT.get();
        if (trace == null) return;
        trace.attempts.add(new Attempt(trace.attempts.size() + 1, stage, response));
        if (trace.selectedAttempt == 0) trace.selectedAttempt = 1;
    }

    public static void generationFailure(String question, GenerationException error) {
        AgentResponse.Status status = error.getReason() == GenerationException.Reason.NOT_CONFIGURED
                ? AgentResponse.Status.NOT_CONFIGURED : AgentResponse.Status.GENERATION_FAILED;
        if (CURRENT.get() != null) attempt("GENERATION", AgentResponse.failed(question, status, null,
                error.getReason().name(), error.getCallRecord(), 0, List.of(), 0, null));
    }

    public static void trigger(String reason) {
        EvaluationTrace trace = CURRENT.get();
        if (trace != null) trace.trigger = reason;
    }

    public static void select(int number) {
        EvaluationTrace trace = CURRENT.get();
        if (trace != null) trace.selectedAttempt = number;
    }

    public List<Call> calls() { return List.copyOf(calls); }
    public List<Attempt> attempts() { return List.copyOf(attempts); }
    public String trigger() { return trigger; }
    public int selectedAttempt() { return selectedAttempt; }

    @Override public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("Trace closed outside its owning scope");
        CURRENT.remove();
    }

    /** A null usage means the transport failed before usage was returned, never 'free'. */
    public record Call(int number, String status, String promptSha256, LlmCallRecord usage) { }
    public record Attempt(int number, String stage, AgentResponse response) { }
}
