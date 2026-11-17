package com.text2sql.agent.access;

import java.util.function.LongSupplier;

/** One bounded token bucket for the one shared user. Never trusts an IP/header as an identity. */
public final class ApiAdmissionGate {
    private final int capacity;
    private final int perMinute;
    private final int maxConcurrent;
    private final LongSupplier clock;
    private double tokens;
    private long lastRefill;
    private int active;

    public ApiAdmissionGate(AccessProperties config) { this(config, System::nanoTime); }

    ApiAdmissionGate(AccessProperties config, LongSupplier clock) {
        capacity = config.getBurst();
        perMinute = config.getRequestsPerMinute();
        maxConcurrent = config.getMaxConcurrent();
        if (capacity < 1 || capacity > 1000 || perMinute < 1 || perMinute > 60000
                || maxConcurrent < 1 || maxConcurrent > 16) {
            throw new IllegalArgumentException("Invalid agent.access limits");
        }
        this.clock = clock;
        tokens = capacity;
        lastRefill = clock.getAsLong();
    }

    public synchronized Admission acquire() {
        long now = clock.getAsLong();
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed / 60_000_000_000.0 * perMinute);
            lastRefill = now;
        }
        if (active >= maxConcurrent) return new Admission(null, "CONCURRENCY_LIMIT", 1);
        if (tokens < 1) {
            return new Admission(null, "RATE_LIMIT", Math.max(1, (int) Math.ceil((1 - tokens) * 60 / perMinute)));
        }
        tokens -= 1;
        active++;
        return new Admission(new Permit(), null, 0);
    }

    public synchronized int activeRequests() { return active; }
    public int capacity() { return capacity; }
    public int requestsPerMinute() { return perMinute; }
    public int maxConcurrent() { return maxConcurrent; }

    public record Admission(Permit permit, String rejection, int retryAfterSeconds) { }

    public final class Permit implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            synchronized (ApiAdmissionGate.this) {
                if (!closed) { closed = true; active--; }
            }
        }
    }
}
