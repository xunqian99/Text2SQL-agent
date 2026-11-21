package com.text2sql.agent.analysis;

import java.time.Instant;
import java.util.Objects;

/** 可按 analysisId 恢复的分析任务快照。 */
public record AnalysisRecord(
        String analysisId,
        String sessionId,
        String clientRequestId,
        int round,
        AnalysisContext context,
        AnalysisStatus status,
        SalesAnalysisResult result,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public AnalysisRecord {
        requireText(analysisId, "analysisId");
        requireText(sessionId, "sessionId");
        requireText(clientRequestId, "clientRequestId");
        if (round < 1 || round > 3) {
            throw new IllegalArgumentException("analysis round must be 1..3");
        }
        context = Objects.requireNonNull(context, "context must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (status == AnalysisStatus.SUCCESS && result == null) {
            throw new IllegalArgumentException("SUCCESS analysis must have a result");
        }
        if (status == AnalysisStatus.FAILED && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException("FAILED analysis must have an error message");
        }
        if (result != null && !analysisId.equals(result.analysisId())) {
            throw new IllegalArgumentException("result analysisId does not match record");
        }
    }

    static AnalysisRecord created(AnalysisContext context, String clientRequestId, int round) {
        Instant now = Instant.now();
        return new AnalysisRecord(context.analysisId(), context.sessionId(), clientRequestId, round,
                context, AnalysisStatus.CREATED, null, null, now, now);
    }

    AnalysisRecord running() {
        requireStatus(AnalysisStatus.CREATED);
        return copy(AnalysisStatus.RUNNING, null, null);
    }

    AnalysisRecord success(SalesAnalysisResult result) {
        requireStatus(AnalysisStatus.RUNNING);
        return copy(AnalysisStatus.SUCCESS, Objects.requireNonNull(result, "result must not be null"), null);
    }

    AnalysisRecord failed(String message) {
        requireStatus(AnalysisStatus.RUNNING);
        return copy(AnalysisStatus.FAILED, null, requireText(message, "errorMessage"));
    }

    private AnalysisRecord copy(AnalysisStatus nextStatus, SalesAnalysisResult nextResult, String nextError) {
        return new AnalysisRecord(analysisId, sessionId, clientRequestId, round, context, nextStatus,
                nextResult, nextError, createdAt, Instant.now());
    }

    private void requireStatus(AnalysisStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Analysis " + analysisId + " is already " + status);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
