package com.text2sql.agent.budget;

import com.text2sql.agent.generation.GenerationException;

import java.net.URI;
import java.time.*;
import java.util.Locale;

/** Local admission rule, not a promise about the provider's eventual billing timestamp. */
public final class DeepSeekOffPeakPolicy {
    public static final String VERSION = "deepseek-off-peak-weekday-conservative-v1";
    public static final String SOURCE = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/";
    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private final Clock clock;

    public DeepSeekOffPeakPolicy(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    public record Decision(String policy, Instant checkedAt, String beijingTime, boolean allowed,
                           String reason, Instant nextAllowedAt, long guardSeconds) { }

    public static Duration horizon(int timeoutSeconds) {
        return Duration.ofSeconds(Math.max(120L, timeoutSeconds + 10L));
    }

    /** Official hosts always qualify. Remote proxies qualify when configured with a DeepSeek model. */
    public static boolean appliesTo(URI endpoint, String model) {
        String host = endpoint.getHost();
        if (host == null) throw new IllegalArgumentException("Model endpoint must have a host");
        host = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (host.equals("deepseek.com") || host.endsWith(".deepseek.com")) return true;
        boolean local = host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("[::1]") || host.equals("::1");
        return !local && model != null && model.toLowerCase(Locale.ROOT).startsWith("deepseek");
    }

    public Decision evaluate(Duration guard) {
        if (guard.isNegative() || guard.isZero() || guard.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Off-peak guard must be positive and at most one day");
        Instant now = clock.instant();
        Instant end = now.plus(guard);
        ZonedDateTime local = now.atZone(BEIJING);
        // No unverified holiday allowlist: weekday holidays are conservatively refused too.
        for (LocalDate day = local.toLocalDate(); !day.isAfter(end.atZone(BEIJING).toLocalDate()); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            for (int[] hours : new int[][]{{9, 12}, {14, 18}}) {
                Instant peakStart = day.atTime(hours[0], 0).atZone(BEIJING).toInstant();
                Instant peakEnd = day.atTime(hours[1], 0).atZone(BEIJING).toInstant();
                if (now.isBefore(peakEnd) && !end.isBefore(peakStart)) {
                    String reason = now.isBefore(peakStart) ? "PEAK_BOUNDARY_RESERVE" : "PEAK_HOURS";
                    return new Decision(VERSION, now, local.toString(), false, reason, peakEnd, guard.toSeconds());
                }
            }
        }
        return new Decision(VERSION, now, local.toString(), true, "OFF_PEAK", now, guard.toSeconds());
    }

    public void requireAllowed() { requireAllowed(Duration.ofMinutes(2)); }

    public void requireAllowed(Duration guard) {
        Decision decision = evaluate(guard);
        if (!decision.allowed()) throw new Rejected(decision);
    }

    public static final class Rejected extends GenerationException {
        private final Decision decision;
        public Rejected(Decision decision) {
            super(Reason.OFF_PEAK_REQUIRED, "DeepSeek评测仅允许空闲时段调用；本次未发送。原因="
                    + decision.reason() + "，最早接续=" + decision.nextAllowedAt().atZone(BEIJING));
            this.decision = decision;
        }
        public Decision decision() { return decision; }
    }
}
