package com.text2sql.agent.access;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiAdmissionGateTest {
    @Test void burstIsBoundedAndRefillsAtConfiguredRateWithoutAccumulatingPastCapacity() {
        var config = new AccessProperties();
        config.setBurst(2);
        config.setRequestsPerMinute(2);
        var now = new AtomicLong();
        var gate = new ApiAdmissionGate(config, now::get);
        gate.acquire().permit().close();
        gate.acquire().permit().close();
        assertThat(gate.acquire().rejection()).isEqualTo("RATE_LIMIT");
        assertThat(gate.acquire().retryAfterSeconds()).isEqualTo(30);
        now.set(TimeUnit.SECONDS.toNanos(29));
        assertThat(gate.acquire().permit()).isNull();
        now.set(TimeUnit.SECONDS.toNanos(30));
        gate.acquire().permit().close();
        assertThat(gate.acquire().permit()).isNull();
        now.set(TimeUnit.DAYS.toNanos(1));
        gate.acquire().permit().close();
        gate.acquire().permit().close();
        assertThat(gate.acquire().permit()).isNull();
    }

    @Test void concurrencyRefusalDoesNotConsumeAnotherTokenAndPermitCloseIsIdempotent() {
        var config = new AccessProperties();
        config.setBurst(2);
        config.setMaxConcurrent(1);
        var gate = new ApiAdmissionGate(config, () -> 0);
        var first = gate.acquire().permit();
        assertThat(gate.acquire().rejection()).isEqualTo("CONCURRENCY_LIMIT");
        first.close(); first.close();
        assertThat(gate.activeRequests()).isZero();
        gate.acquire().permit().close();
        assertThat(gate.acquire().rejection()).isEqualTo("RATE_LIMIT");
    }

    @Test void simultaneousCallersCannotOversubscribeConcurrency() throws Exception {
        var config = new AccessProperties();
        config.setBurst(20);
        config.setMaxConcurrent(2);
        var gate = new ApiAdmissionGate(config, () -> 0);
        var ready = new CountDownLatch(16);
        var release = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) tasks.add(pool.submit(() -> {
                var result = gate.acquire();
                if (result.permit() != null) admitted.incrementAndGet();
                ready.countDown();
                if (result.permit() != null) {
                    try (var permit = result.permit()) { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }));
            try {
                assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(admitted).hasValue(2);
                assertThat(gate.activeRequests()).isEqualTo(2);
            } finally { release.countDown(); }
            for (var task : tasks) task.get(3, TimeUnit.SECONDS);
        }
        assertThat(gate.activeRequests()).isZero();
    }

    @Test void invalidLimitsFailInsteadOfDisablingProtection() {
        var config = new AccessProperties();
        config.setMaxConcurrent(0);
        assertThatThrownBy(() -> new ApiAdmissionGate(config)).hasMessage("Invalid agent.access limits");
    }
}
