package com.text2sql.agent.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.budget.RequestBudget;
import com.text2sql.agent.config.AgentProperties;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable configured-price admission ledger. This is not a provider-enforced billing cap. */
public final class CostQuota implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final boolean enabled;
    private BigDecimal limit;
    private BigDecimal reservation;
    private BigDecimal inputPrice;
    private BigDecimal outputPrice;
    private BigDecimal hitPrice;
    private String pricingIdentity;
    private Path path;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private State state;
    private boolean failed;
    private boolean closed;

    public record Hold(String requestId, int callNumber, String promptSha256, String status) { }
    public record State(int version, String budgetId, String pricingIdentity, BigDecimal limitYuan,
                        BigDecimal reservationYuan, BigDecimal knownCostYuan, long knownCalls,
                        boolean reservationBreached, Map<String, Hold> holds) { }

    public static CostQuota disabled() { return new CostQuota(new CostQuotaProperties(), new AgentProperties()); }

    public CostQuota(CostQuotaProperties config, AgentProperties agent) {
        enabled = config.isEnabled();
        if (!enabled) return;
        limit = config.getTotalYuan(); reservation = config.getReservationYuan();
        if (limit == null || reservation == null || limit.signum() <= 0 || reservation.signum() <= 0
                || reservation.compareTo(limit) > 0 || limit.compareTo(new BigDecimal("10000")) > 0
                || limit.scale() > 8 || reservation.scale() > 8 || !agent.getRequestBudget().isEnabled()) {
            throw new IllegalArgumentException("Invalid agent.cost-quota amounts or disabled request budget");
        }
        inputPrice = price(agent.getLlm().getInputPricePer1k(), true);
        outputPrice = price(agent.getLlm().getOutputPricePer1k(), true);
        hitPrice = price(agent.getLlm().getCacheHitPricePer1k(), false);
        try {
            String identity = agent.getLlm().getBaseUrl() + "\n" + agent.getLlm().getCompletionsPath() + "\n"
                    + agent.getLlm().getModel() + "\n" + inputPrice + "\n" + outputPrice + "\n" + hitPrice;
            pricingIdentity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            path = Path.of(config.getStateFile()).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            lockChannel = FileChannel.open(Path.of(path + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = lockChannel.tryLock();
            if (fileLock == null) throw new IOException("Ledger already owned");
            if (Files.exists(path)) {
                if (Files.size(path) > 4_000_000) throw new IOException("Ledger too large");
                state = JSON.readValue(path.toFile(), State.class);
                validateLoaded();
                var restored = new LinkedHashMap<String, Hold>();
                state.holds().forEach((id, hold) -> restored.put(id, new Hold(hold.requestId(), hold.callNumber(), hold.promptSha256(), "UNKNOWN")));
                state = copy(state.knownCostYuan(), state.knownCalls(), state.reservationBreached(), restored);
            } else {
                state = new State(1, UUID.randomUUID().toString(), pricingIdentity, limit, reservation,
                        BigDecimal.ZERO, 0, false, Map.of());
            }
            persist(); // Interrupted previous reservations stay charged after restart.
        } catch (Exception initializationFailure) {
            close();
            throw new IllegalStateException("Cost quota ledger cannot be opened or verified; no model calls allowed");
        }
    }

    private static BigDecimal price(double value, boolean positive) {
        if (!Double.isFinite(value) || value < 0 || (positive && value == 0))
            throw new IllegalArgumentException("Cost quota requires valid nonzero input/output prices");
        return BigDecimal.valueOf(value);
    }

    private void validateLoaded() throws IOException {
        if (state == null || state.version() != 1 || state.budgetId() == null || state.budgetId().isBlank()
                || !pricingIdentity.equals(state.pricingIdentity()) || state.limitYuan() == null || state.reservationYuan() == null
                || limit.compareTo(state.limitYuan()) != 0 || reservation.compareTo(state.reservationYuan()) != 0
                || state.knownCostYuan() == null || state.knownCostYuan().signum() < 0 || state.knownCalls() < 0
                || state.holds() == null || state.holds().size() > 10000
                || state.holds().entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null
                    || e.getValue().requestId() == null || e.getValue().callNumber() < 1
                    || !("PENDING".equals(e.getValue().status()) || "UNKNOWN".equals(e.getValue().status())))) {
            throw new IOException("Invalid ledger identity/state");
        }
        if (!state.reservationBreached() && committed().compareTo(limit) > 0) throw new IOException("Invalid committed amount");
    }

    public boolean enabled() { return enabled; }

    public synchronized Lease reserve(String requestId, int callNumber, String hash) {
        if (!enabled) return new Lease(null);
        ensureAvailable();
        if (state.reservationBreached() || state.holds().size() >= 10000
                || committed().add(reservation).compareTo(limit) > 0) throw new RequestBudget.Exceeded("COST_QUOTA_EXHAUSTED");
        String id = UUID.randomUUID().toString();
        var holds = new LinkedHashMap<>(state.holds());
        holds.put(id, new Hold(requestId, callNumber, hash, "PENDING"));
        state = copy(state.knownCostYuan(), state.knownCalls(), state.reservationBreached(), holds);
        persist(); // Admission is durable before the transport is invoked.
        return new Lease(id);
    }

    private BigDecimal committed() { return state.knownCostYuan().add(reservation.multiply(BigDecimal.valueOf(state.holds().size()))); }
    private State copy(BigDecimal cost, long calls, boolean breached, Map<String, Hold> holds) {
        return new State(1, state.budgetId(), pricingIdentity, limit, reservation, cost, calls, breached, Map.copyOf(holds));
    }
    private void ensureAvailable() {
        if (failed || closed) throw new RequestBudget.Exceeded("COST_LEDGER_UNAVAILABLE");
    }
    private void persist() {
        // A late model may restore an earlier cancellation flag. Preserve it, but do not let an
        // already-set flag prevent durable accounting of a response that has actually arrived.
        boolean interrupted = Thread.interrupted();
        try {
            byte[] bytes = JSON.writeValueAsBytes(state);
            Path temporary = Path.of(path + ".tmp");
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException cannotPersist) {
            failed = true;
            throw new RequestBudget.Exceeded("COST_LEDGER_UNAVAILABLE");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public synchronized Map<String, Object> snapshot() {
        if (!enabled) return Map.of("enabled", false, "meaning", "disabled");
        long pending = state.holds().values().stream().filter(h -> "PENDING".equals(h.status())).count();
        var body = new LinkedHashMap<String, Object>();
        body.put("enabled", true); body.put("budgetId", state.budgetId());
        body.put("meaning", "durable-configured-price-admission-not-provider-billing-cap");
        body.put("limitYuan", limit); body.put("reservationPerCallYuan", reservation);
        body.put("knownCostYuan", state.knownCostYuan()); body.put("knownCalls", state.knownCalls());
        body.put("heldYuan", reservation.multiply(BigDecimal.valueOf(state.holds().size())));
        body.put("committedYuan", committed()); body.put("availableYuan", limit.subtract(committed()).max(BigDecimal.ZERO));
        body.put("pendingCalls", pending); body.put("unknownCalls", state.holds().size() - pending);
        body.put("reservationBreached", state.reservationBreached()); body.put("ledgerAvailable", !failed && !closed);
        body.put("canAdmitCall", !failed && !closed && !state.reservationBreached() && state.holds().size() < 10000
                && committed().add(reservation).compareTo(limit) <= 0);
        return Map.copyOf(body);
    }

    public final class Lease implements AutoCloseable {
        private final String id;
        private boolean settled;
        private Lease(String id) { this.id = id; }

        /** Called only when budget admission rejects before model.call: proven unsent, so safe to refund. */
        public void cancelUnsent() { finish(null, true); }

        public void complete(ChatResponse response) {
            if (id == null) { settled = true; return; }
            var metadata = response == null ? null : response.getMetadata();
            var usage = metadata == null ? null : metadata.getUsage();
            if (usage == null || usage.getPromptTokens() == null || usage.getCompletionTokens() == null
                    || usage.getPromptTokens() < 0 || usage.getCompletionTokens() < 0
                    || (long) usage.getPromptTokens() + usage.getCompletionTokens() <= 0) { close(); return; }
            int hit = 0;
            if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage && nativeUsage.promptTokensDetails() != null
                    && nativeUsage.promptTokensDetails().cachedTokens() != null) hit = nativeUsage.promptTokensDetails().cachedTokens();
            if (hit < 0 || hit > usage.getPromptTokens()) { close(); return; }
            BigDecimal cost = inputPrice.multiply(BigDecimal.valueOf(usage.getPromptTokens() - hit))
                    .add(hitPrice.multiply(BigDecimal.valueOf(hit)))
                    .add(outputPrice.multiply(BigDecimal.valueOf(usage.getCompletionTokens()))).movePointLeft(3);
            finish(cost, false);
        }

        private void finish(BigDecimal cost, boolean unsent) {
            synchronized (CostQuota.this) {
                if (settled || id == null) { settled = true; return; }
                ensureAvailable();
                var holds = new LinkedHashMap<>(state.holds());
                if (holds.remove(id) == null) throw new IllegalStateException("Unknown cost reservation");
                boolean breached = state.reservationBreached() || (cost != null && cost.compareTo(reservation) > 0);
                state = copy(state.knownCostYuan().add(cost == null ? BigDecimal.ZERO : cost),
                        state.knownCalls() + (unsent ? 0 : 1), breached, holds);
                persist(); settled = true;
            }
        }

        @Override public void close() {
            synchronized (CostQuota.this) {
                if (settled || id == null) return;
                // No refund on exceptions/missing usage. A dead thread/restart cannot erase the hold.
                if (!closed && !failed) {
                    var holds = new LinkedHashMap<>(state.holds());
                    var old = holds.get(id);
                    holds.put(id, new Hold(old.requestId(), old.callNumber(), old.promptSha256(), "UNKNOWN"));
                    state = copy(state.knownCostYuan(), state.knownCalls(), state.reservationBreached(), holds);
                    persist();
                }
                settled = true;
            }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        try { if (fileLock != null && fileLock.isValid()) fileLock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
}
