package com.text2sql.agent.analysis;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 分析任务的内存状态和幂等存储。
 *
 * <p>C-1 先固定状态机和并发语义，后续再把同一接口接到已有持久化能力。状态更新集中在
 * 会话锁内，所以相同会话的重复请求不会启动第二次执行；断线恢复只读取已保存快照。
 */
@Service
public class AnalysisStore {

    private static final int MAX_ROUNDS = 3;

    private final ConcurrentMap<String, AnalysisRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> sessionRounds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final Set<String> closedSessions = ConcurrentHashMap.newKeySet();

    public AnalysisRecord execute(AnalysisContext context, String clientRequestId,
                                  Function<AnalysisContext, SalesAnalysisResult> work) {
        Objects.requireNonNull(context, "context must not be null");
        requireText(clientRequestId, "clientRequestId");
        Objects.requireNonNull(work, "work must not be null");

        Object sessionLock = sessionLocks.computeIfAbsent(context.sessionId(), ignored -> new Object());
        synchronized (sessionLock) {
            ensureOpen(context.sessionId());
            String idempotencyKey = idempotencyKey(context.sessionId(), clientRequestId);
            String existingAnalysisId = idempotencyIndex.get(idempotencyKey);
            if (existingAnalysisId != null) {
                return records.get(existingAnalysisId);
            }

            int round = sessionRounds.getOrDefault(context.sessionId(), 0) + 1;
            if (round > MAX_ROUNDS) {
                throw new IllegalStateException("A session supports at most 3 rounds");
            }
            sessionRounds.put(context.sessionId(), round);

            AnalysisRecord current = AnalysisRecord.created(context, clientRequestId, round);
            records.put(current.analysisId(), current);
            idempotencyIndex.put(idempotencyKey, current.analysisId());
            current = replace(current.running());
            try {
                SalesAnalysisResult result = work.apply(context);
                current = replace(current.success(result));
            } catch (RuntimeException failure) {
                current = replace(current.failed(message(failure)));
            }
            return current;
        }
    }

    public Optional<AnalysisRecord> find(String analysisId) {
        if (analysisId == null || analysisId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.get(analysisId));
    }

    public void closeSession(String sessionId) {
        requireText(sessionId, "sessionId");
        Object sessionLock = sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
        synchronized (sessionLock) {
            closedSessions.add(sessionId);
        }
    }

    public boolean isClosed(String sessionId) {
        return sessionId != null && closedSessions.contains(sessionId);
    }

    private AnalysisRecord replace(AnalysisRecord record) {
        records.put(record.analysisId(), record);
        return record;
    }

    private void ensureOpen(String sessionId) {
        if (closedSessions.contains(sessionId)) {
            throw new IllegalStateException("Analysis session is closed: " + sessionId);
        }
    }

    private static String idempotencyKey(String sessionId, String clientRequestId) {
        return sessionId + '\u0000' + clientRequestId;
    }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
