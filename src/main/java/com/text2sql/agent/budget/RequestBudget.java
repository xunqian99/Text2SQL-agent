package com.text2sql.agent.budget;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.retrieval.SchemaContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One monotonic deadline and one model-call ledger, shared by every nested stage. */
public final class RequestBudget {
    public static final String TIMINGS_VERSION = "request-budget-exclusive-v1";
    private static final ThreadLocal<RequestBudget> CURRENT = new ThreadLocal<>();
    private final String id = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private final long budgetMs;
    private final int maxCalls;
    private final long minRetryMs;
    private final Map<String, Long> stageNanos = new LinkedHashMap<>();
    private final List<Runnable> cancellations = new ArrayList<>();
    private String stage = "ADMISSION";
    private long stageStarted = started;
    private int calls;
    private int knownCalls;
    private LlmCallRecord usage;
    private boolean finished;
    private long finishedAt;
    private int cancellationRejected;
    private String retrySkipped;
    private SchemaContext schema;
    private AgentResponse checkpoint;

    RequestBudget(AgentProperties.RequestBudgetConfig config) {
        budgetMs = config.getTimeoutMs();
        maxCalls = config.getMaxModelCalls();
        minRetryMs = config.getMinRetryRemainingMs();
        if (budgetMs < 1 || budgetMs > 120_000 || maxCalls < 1 || maxCalls > 16
                || minRetryMs < 0 || minRetryMs > budgetMs) {
            throw new IllegalArgumentException("Invalid request budget: timeout 1..120000ms, calls 1..16, retry reserve 0..timeout");
        }
    }

    public static RequestBudget current() { return CURRENT.get(); }

    static AutoCloseable bind(RequestBudget budget) {
        if (CURRENT.get() != null) throw new IllegalStateException("Request budget already bound");
        CURRENT.set(budget);
        return CURRENT::remove;
    }

    public synchronized void check() {
        if (finished || remainingNanos() <= 0) throw new Exceeded("DEADLINE_EXCEEDED");
    }

    public synchronized long remainingNanos() {
        return Math.max(0, budgetMs * 1_000_000 - (System.nanoTime() - started));
    }

    public synchronized int remainingMillis() {
        check();
        return (int) Math.max(1, (remainingNanos() + 999_999) / 1_000_000);
    }

    public static void stage(String next) {
        RequestBudget budget = current();
        if (budget != null) budget.enter(next);
    }

    private synchronized void enter(String next) {
        check();
        long now = System.nanoTime();
        stageNanos.merge(stage, now - stageStarted, Long::sum);
        stage = next;
        stageStarted = now;
    }

    /** Reserve before transport starts, so a timed-out call remains visible as unknown usage. */
    public synchronized void startModelCall(String promptSha256) {
        check();
        if (calls >= maxCalls) throw new Exceeded("MODEL_CALL_LIMIT");
        enter("MODEL");
        calls++;
        EvaluationTrace.pendingCall(promptSha256);
    }

    public synchronized boolean observe(LlmCallRecord record) {
        if (finished) return false;
        if (record != null && record.totalTokens() > 0) {
            knownCalls++;
            usage = LlmCallRecord.combine(usage, record);
        }
        return true;
    }

    /** Finish and response capture must see either both ledger/evidence updates or neither. */
    public synchronized boolean recordCall(LlmCallRecord record, String status, String hash) {
        if (!observe(record)) return false;
        EvaluationTrace.call(record != null && record.totalTokens() > 0 ? record : null, status, hash);
        return true;
    }

    public static boolean allowRetry() {
        RequestBudget budget = current();
        if (budget == null) return true;
        synchronized (budget) {
            if (budget.finished || budget.remainingNanos() < budget.minRetryMs * 1_000_000) {
                budget.retrySkipped = "INSUFFICIENT_TIME";
                return false;
            }
            if (budget.calls >= budget.maxCalls) {
                budget.retrySkipped = "MODEL_CALL_LIMIT";
                return false;
            }
            return true;
        }
    }

    public synchronized AutoCloseable onCancel(Runnable action) {
        check();
        cancellations.add(action);
        return () -> { synchronized (this) { cancellations.remove(action); } };
    }

    public static void schema(SchemaContext value) {
        RequestBudget budget = current();
        if (budget != null) synchronized (budget) { budget.check(); budget.schema = value; }
    }

    public static void checkpoint(AgentResponse response) {
        RequestBudget budget = current();
        if (budget != null) synchronized (budget) { budget.check(); budget.checkpoint = response; }
    }

    synchronized List<Runnable> finish() {
        if (!finished) finishedAt = System.nanoTime();
        finished = true;
        List<Runnable> actions = List.copyOf(cancellations);
        cancellations.clear();
        return actions;
    }

    public synchronized Snapshot snapshot(String termination) {
        long now = finished ? finishedAt : System.nanoTime();
        Map<String, Long> timings = new LinkedHashMap<>(stageNanos);
        timings.merge(stage, now - stageStarted, Long::sum);
        timings.replaceAll((key, nanos) -> nanos / 1_000_000);
        long elapsed = (now - started) / 1_000_000;
        AgentResponse.Timings projected = responseTimings(timings, elapsed);
        long other = elapsed - projected.retrievalMs() - projected.generationMs()
                - projected.validationMs() - projected.executionMs();
        return new Snapshot(id, budgetMs, maxCalls, calls, Math.max(0, calls - knownCalls),
                elapsed, termination, stage, retrySkipped, Map.copyOf(timings), cancellationRejected,
                TIMINGS_VERSION, other, (now - stageStarted) / 1_000_000);
    }

    synchronized AgentResponse stopped(String question, String sessionId, String reason) {
        AgentResponse first = checkpoint;
        Snapshot measured = snapshot(reason);
        String message = switch (reason) {
            case "DEADLINE_EXCEEDED" -> "请求已超过时间上限，请缩小查询范围后重试。";
            case "MODEL_CALL_LIMIT" -> "本次问题已用完模型调用次数，请简化问题后重试。";
            default -> "服务繁忙或请求已中断，请稍后重试。";
        };
        AgentResponse stopped = AgentResponse.failed(question,
                reason.equals("DEADLINE_EXCEEDED") ? AgentResponse.Status.REQUEST_TIMEOUT : AgentResponse.Status.RESOURCE_LIMITED,
                first == null ? null : first.sql(), message, usage,
                schema == null ? 0 : schema.tables().size(), schema == null ? List.of() : schema.tableNames(),
                schema == null || schema.ddlText() == null ? 0 : schema.ddlText().length(),
                measured.responseTimings()).withBudget(measured);
        EvaluationTrace.stop(stopped, first != null && first.success());
        AgentResponse selected = first != null && first.success() ? first.withUsage(usage) : stopped;
        return selected.withConversation(sessionId, selected.rewrittenQuestion(), selected.schemaReused())
                .withBudget(measured);
    }

    public String id() { return id; }
    public synchronized int callNumber() { return calls; }
    public synchronized boolean finished() { return finished; }
    synchronized void cancellationRejected() { cancellationRejected++; }
    synchronized LlmCallRecord usage() { return usage; }

    public record Snapshot(String requestId, long timeoutMs, int maxModelCalls, int modelCalls,
                           int unknownUsageCalls, long elapsedMs, String termination, String stage,
                           String retrySkippedReason, Map<String, Long> stageMs, int cancellationRejected,
                           String timingsVersion, long otherMs, long currentStageMs) {
        // stageMs accumulates repeated visits; currentStageMs identifies only the terminal visit.
        // A non-COMPLETE termination marks that visit as interrupted, not a completed operation.
        /** Request clock is authoritative even when a successful first answer survives a retry timeout. */
        public AgentResponse.Timings responseTimings() {
            return RequestBudget.responseTimings(stageMs, elapsedMs);
        }
    }

    // Stage intervals are exclusive and measured on one monotonic clock. Never add attempt timers:
    // an unfinished stage has no returned attempt, and a checkpoint may overlap a later correction.
    private static AgentResponse.Timings responseTimings(Map<String, Long> stages, long elapsed) {
        return new AgentResponse.Timings(stages.getOrDefault("RETRIEVAL", 0L),
                sumStages(stages, "REWRITE", "GENERATION", "MODEL"),
                sumStages(stages, "VALIDATION", "RESULT_CHECK"),
                sumStages(stages, "EXPLAIN", "EXECUTION"), elapsed);
    }

    private static long sumStages(Map<String, Long> stages, String... names) {
        long total = 0;
        for (String name : names) total += stages.getOrDefault(name, 0L);
        return total;
    }

    public static final class Exceeded extends RuntimeException {
        private final String reason;
        public Exceeded(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }
}
