package com.text2sql.agent.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisStoreTest {

    @Test
    @DisplayName("相同幂等键只执行一次并能按 analysisId 恢复成功结果")
    void executesIdempotentRequestOnceAndRestoresIt() {
        AnalysisStore store = new AnalysisStore();
        AtomicInteger executions = new AtomicInteger();

        AnalysisRecord first = store.execute(context("analysis-001"), "request-001", context -> {
            executions.incrementAndGet();
            return result(context.analysisId());
        });
        AnalysisRecord duplicate = store.execute(context("analysis-001"), "request-001", context -> {
            executions.incrementAndGet();
            return result(context.analysisId());
        });

        assertThat(executions).hasValue(1);
        assertThat(duplicate).isEqualTo(first);
        assertThat(first.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(store.find(first.analysisId())).contains(first);
    }

    @Test
    @DisplayName("执行失败也持久化 FAILED，重复请求不再次执行")
    void persistsFailedTerminalState() {
        AnalysisStore store = new AnalysisStore();
        AtomicInteger executions = new AtomicInteger();

        AnalysisRecord failed = store.execute(context("analysis-failed"), "request-failed", context -> {
            executions.incrementAndGet();
            throw new IllegalStateException("database timeout");
        });
        AnalysisRecord duplicate = store.execute(context("analysis-failed"), "request-failed", context -> {
            executions.incrementAndGet();
            return result(context.analysisId());
        });

        assertThat(executions).hasValue(1);
        assertThat(failed.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.errorMessage()).contains("database timeout");
        assertThat(duplicate).isEqualTo(failed);
    }

    @Test
    @DisplayName("同一会话最多三轮，第四轮拒绝")
    void limitsSessionToThreeRounds() {
        AnalysisStore store = new AnalysisStore();

        for (int round = 1; round <= 3; round++) {
            AnalysisRecord record = store.execute(context("analysis-" + round), "request-" + round,
                    context -> result(context.analysisId()));
            assertThat(record.round()).isEqualTo(round);
        }

        assertThatThrownBy(() -> store.execute(context("analysis-004"), "request-004",
                context -> result(context.analysisId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 rounds");
    }

    @Test
    @DisplayName("关闭会话后不能追加分析")
    void closedSessionRejectsNewAnalysis() {
        AnalysisStore store = new AnalysisStore();
        store.closeSession("session-001");

        assertThatThrownBy(() -> store.execute(context("analysis-closed"), "request-closed",
                context -> result(context.analysisId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("并发重复请求仍只有一个执行者")
    void concurrentDuplicateRequestsExecuteOnce() throws Exception {
        AnalysisStore store = new AnalysisStore();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return store.execute(context("analysis-concurrent"), "request-concurrent", context -> {
                    executions.incrementAndGet();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return result(context.analysisId());
                });
            });
            var second = pool.submit(() -> {
                start.await();
                return store.execute(context("analysis-concurrent"), "request-concurrent", context -> {
                    executions.incrementAndGet();
                    return result(context.analysisId());
                });
            });
            start.countDown();
            assertThat(first.get().analysisId()).isEqualTo("analysis-concurrent");
            assertThat(second.get().analysisId()).isEqualTo("analysis-concurrent");
        }
        assertThat(executions).hasValue(1);
    }

    private static AnalysisContext context(String analysisId) {
        return new AnalysisContext(
                "session-001", analysisId,
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                "sha256:metric-v1");
    }

    private static SalesAnalysisResult result(String analysisId) {
        return new SalesAnalysisResult("session-001", analysisId, SalesAnalysisView.TOTAL,
                List.of("metric_value"), List.of(List.of("1.00")), false, 1);
    }
}
