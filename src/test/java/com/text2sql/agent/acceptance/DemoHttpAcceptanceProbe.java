package com.text2sql.agent.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItem;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.ResultNormalizer;
import com.text2sql.agent.execution.SqlExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Frozen local HTTP acceptance. Does not instantiate a model or change benchmark/production code. */
public final class DemoHttpAcceptanceProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "demo20-";
    private static final List<String> PROTECTED = List.of("src", "pom.xml", "data/eval", "scripts/start_local.ps1");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI base;

    DemoHttpAcceptanceProbe(URI base) {
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost())
                || base.getPort() < 1 || !Set.of("", "/").contains(base.getPath())
                || base.getRawQuery() != null || base.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Acceptance endpoint must be http://127.0.0.1:<port>");
        }
        this.base = base;
    }

    public record Scenario(String id, String sourceId, String sessionId, String question, String goldSql,
                           boolean followUp, QueryResult expected) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("freeze DIR | run DIR URL | summarize DIR");
        Path dir = Path.of(args[1]);
        switch (args[0]) {
            case "freeze" -> freeze(dir);
            case "run" -> new DemoHttpAcceptanceProbe(URI.create(args[2])).run(dir);
            case "summarize" -> summarize(dir);
            default -> throw new IllegalArgumentException("Unknown action");
        }
    }

    private static void freeze(Path dir) throws Exception {
        require(!Files.exists(dir), "Never overwrite an acceptance run");
        requireClean();
        var properties = new AgentProperties();
        var loader = new EvalItemLoader(properties);
        var items = new LinkedHashMap<String, EvalItem>();
        loader.load("data/eval", List.of(), 0).forEach(item -> items.put(item.id(), item));
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"), env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro")));
        var executor = new SqlExecutor(jdbc, properties);
        var cases = new ArrayList<Scenario>();
        // Two topics are interleaved before the exact same follow-up text.
        for (String id : List.of("T1-002", "T6-001")) {
            var item = items.get(id);
            cases.add(scenario(item, item.id(), id.equals("T1-002") ? PREFIX + "orders" : PREFIX + "gmv", false, executor));
        }
        for (int index = 0; index < 2; index++) {
            var parent = cases.get(index);
            var source = items.get(parent.sourceId());
            // Only these two scenario expectations change the existing 2018 bounds to 2017.
            String sql = source.goldSql().replace("2018-01-01", "2017-01-01").replace("2019-01-01", "2018-01-01");
            var derived = new EvalItem("FOLLOWUP-" + source.id(), source.difficulty(), source.tags(),
                    "那2017年的呢？", sql, "Derived scenario only; original benchmark remains unchanged.");
            cases.add(new Scenario(derived.id(), source.id(), parent.sessionId(), derived.question(), sql,
                    true, checkedGold(executor, sql)));
        }
        for (int layer = 1; layer <= 6; layer++) {
            for (int number = 1; number <= 3; number++) {
                String id = "T%d-%03d".formatted(layer, number);
                if (id.equals("T1-002") || id.equals("T6-001")) continue;
                cases.add(scenario(items.get(id), id, PREFIX + id, false, executor));
            }
        }
        require(cases.size() == 20, "Expected 20 requests");
        Map<String, String> objects = new LinkedHashMap<>();
        for (String path : PROTECTED) objects.put(path, git("rev-parse", "HEAD:" + path));
        Map<String, Object> protocol = new LinkedHashMap<>();
        protocol.put("format", "local-demo-http-20-v1");
        protocol.put("frozenAt", Instant.now().toString());
        protocol.put("sourceCommit", git("rev-parse", "HEAD"));
        protocol.put("sourceObjects", objects);
        protocol.put("datasetSha256", loader.datasetSha256("data/eval"));
        protocol.put("localConfigSha256", sha(Files.readAllBytes(Path.of("src/main/resources/application-local.yml"))));
        protocol.put("selection", "first 3 IDs per layer (18 originals) plus two interleaved year follow-ups; not independent blind testing");
        protocol.put("denominator", 20);
        protocol.put("latency", "HTTP wall clock, nearest-rank P95=sorted[ceil(.95*N)-1]; failures included");
        protocol.put("comparison", "unchanged ResultNormalizer; successful non-truncated response vs frozen Gold; aliases not graded");
        protocol.put("unknownUsage", "any unknown or unmatched call prevents complete-cost claim");
        protocol.put("pricesYuanPer1k", Map.of("cacheHit", 0.00002, "cacheMiss", 0.001, "output", 0.004));
        protocol.put("cases", cases);
        Files.createDirectories(dir);
        writeNew(dir.resolve("protocol.json"), protocol);
        Files.writeString(dir.resolve("protocol.sha256"), sha(Files.readAllBytes(dir.resolve("protocol.json"))) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("Frozen 20 cases and 20 Gold results; external model calls=0.");
    }

    private static Scenario scenario(EvalItem item, String id, String sessionId, boolean followUp, SqlExecutor executor) {
        return new Scenario(id, item.id(), sessionId, item.question(), item.goldSql(), followUp, checkedGold(executor, item.goldSql()));
    }

    private static QueryResult checkedGold(SqlExecutor executor, String sql) {
        QueryResult result = executor.execute(sql);
        require(!result.truncated(), "Gold result truncated");
        return result;
    }

    private static JsonNode verifyProtocol(Path dir) throws Exception {
        requireClean();
        byte[] bytes = Files.readAllBytes(dir.resolve("protocol.json"));
        require(sha(bytes).equals(Files.readString(dir.resolve("protocol.sha256")).strip()), "Frozen protocol changed");
        var protocol = JSON.readTree(bytes);
        var objects = protocol.path("sourceObjects").properties().iterator();
        while (objects.hasNext()) {
            var entry = objects.next();
            require(entry.getValue().asText().equals(git("rev-parse", "HEAD:" + entry.getKey())), "Protected source changed: " + entry.getKey());
        }
        require(protocol.path("datasetSha256").asText().equals(new EvalItemLoader(new AgentProperties()).datasetSha256("data/eval")), "Benchmark bytes changed");
        require(protocol.path("localConfigSha256").asText().equals(sha(Files.readAllBytes(Path.of("src/main/resources/application-local.yml")))), "Private local configuration changed");
        return protocol;
    }

    private void run(Path dir) throws Exception {
        JsonNode protocol = verifyProtocol(dir);
        require(!Files.exists(dir.resolve("requests")), "Run already started; never replay paid calls into this archive");
        var initial = exchange("GET", "/api/health", null);
        JsonNode health = initial.path("body");
        require(initial.path("httpStatus").asInt() == 200 && health.path("ready").asBoolean(), "Demo not ready");
        require("demo".equals(health.path("mode").asText()) && "deepseek-flash".equals(health.path("model").asText()), "Unexpected demo model/mode");
        for (String flag : List.of("retrieval", "semantic", "fewshot", "clarification", "selfCorrection", "resultChecker", "conversation", "explainGuard"))
            require(health.path("capabilities").path(flag).asBoolean(), "Missing capability: " + flag);
        for (String flag : List.of("cache", "persistence", "routing", "toolUse"))
            require(!health.path("capabilities").path(flag).asBoolean(), "Unexpected capability: " + flag);
        require(health.path("limits").path("requestBudgetEnabled").asBoolean()
                && health.path("limits").path("requestTimeoutMs").asInt() == 15000
                && health.path("limits").path("maxModelCalls").asInt() == 2, "Unexpected request budget");
        var sessions = exchange("GET", "/api/sessions", null);
        require(sessions.path("httpStatus").asInt() == 200 && sessions.path("body").isEmpty(), "Use a fresh isolated demo process");
        writeNew(dir.resolve("health-before.json"), initial);
        writeNew(dir.resolve("sessions-before.json"), sessions);
        writeNew(dir.resolve("run-start.json"), Map.of("at", Instant.now().toString(), "commit", git("rev-parse", "HEAD"), "baseUrl", base.toString()));
        Files.createDirectory(dir.resolve("requests"));
        int index = 0;
        for (JsonNode raw : protocol.path("cases")) {
            verifyProtocol(dir);
            Scenario item = JSON.treeToValue(raw, Scenario.class);
            var response = exchange("POST", "/api/ask", Map.of("question", item.question(), "sessionId", item.sessionId()));
            JsonNode body = response.path("body");
            var record = new LinkedHashMap<String, Object>();
            record.put("index", ++index);
            record.put("caseId", item.id());
            record.put("request", Map.of("question", item.question(), "sessionId", item.sessionId()));
            record.put("exchange", response);
            record.put("businessCorrect", correct(item, body));
            record.put("healthAfter", exchange("GET", "/api/health", null));
            record.put("sessionsAfter", exchange("GET", "/api/sessions", null));
            writeNew(dir.resolve("requests/%02d.json".formatted(index)), record);
            System.out.printf(Locale.ROOT, "%02d/20 %s HTTP%d %s correct=%s wall=%dms%n", index, item.id(),
                    response.path("httpStatus").asInt(), body.path("status").asText("TRANSPORT_ERROR"),
                    correct(item, body), response.path("httpWallMs").asLong());
        }
        var lifecycle = new ArrayList<Object>();
        for (String id : List.of(PREFIX + "orders", PREFIX + "gmv")) {
            lifecycle.add(Map.of("sessionId", id, "close", exchange("POST", "/api/session/" + id + "/close", null),
                    "sessionsAfterClose", exchange("GET", "/api/sessions", null)));
        }
        // Delete only this run's known sessions; never delete an unrelated user's session.
        for (String id : protocol.path("cases").findValuesAsText("sessionId").stream().distinct().toList())
            lifecycle.add(Map.of("sessionId", id, "delete", exchange("DELETE", "/api/session/" + id, null)));
        writeNew(dir.resolve("lifecycle.json"), lifecycle);
        writeNew(dir.resolve("sessions-after-cleanup.json"), exchange("GET", "/api/sessions", null));
        writeNew(dir.resolve("health-after.json"), exchange("GET", "/api/health", null));
    }

    static boolean correct(Scenario item, JsonNode body) throws Exception {
        if (!"SUCCESS".equals(body.path("status").asText()) || body.path("truncated").asBoolean()) return false;
        var actual = new QueryResult(JSON.convertValue(body.path("columns"), JSON.getTypeFactory().constructCollectionType(List.class, String.class)),
                JSON.convertValue(body.path("rows"), JSON.getTypeFactory().constructCollectionType(List.class,
                        JSON.getTypeFactory().constructCollectionType(List.class, String.class))), false, 0);
        return ResultNormalizer.equivalent(item.expected(), item.goldSql(), actual, body.path("sql").asText());
    }

    private JsonNode exchange(String method, String path, Object body) throws Exception {
        long started = System.nanoTime();
        var result = JSON.createObjectNode();
        result.put("startedAt", Instant.now().toString());
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(35));
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            if (body != null) builder.header("Content-Type", "application/json; charset=utf-8");
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            result.put("httpStatus", response.statusCode());
            result.put("rawBody", response.body());
            result.set("body", response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body()));
        } catch (java.io.IOException transport) {
            result.put("httpStatus", 0);
            result.put("transportError", transport.getClass().getSimpleName());
        }
        result.put("httpWallMs", (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    private static void summarize(Path dir) throws Exception {
        verifyProtocol(dir);
        var protocol = JSON.readTree(dir.resolve("protocol.json").toFile());
        var requests = new ArrayList<JsonNode>();
        try (var paths = Files.list(dir.resolve("requests"))) {
            for (Path path : paths.sorted().toList()) requests.add(JSON.readTree(path.toFile()));
        }
        require(requests.size() == 20, "Incomplete run; do not shrink denominator");
        Map<String, JsonNode> calls = new LinkedHashMap<>();
        int unassigned = 0;
        if (Files.exists(dir.resolve("llm-calls"))) {
            try (var paths = Files.list(dir.resolve("llm-calls"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                    for (String line : Files.readAllLines(path)) {
                        if (line.isBlank()) continue;
                        var call = JSON.readTree(line);
                        if (call.path("requestId").asText().isBlank() || call.path("requestId").isNull()) unassigned++;
                        String key = call.path("requestId").asText() + ":" + call.path("callNumber").asInt();
                        require(calls.putIfAbsent(key, call) == null, "Duplicate model ledger slot: " + key);
                    }
                }
            }
        }
        var ledger = new ArrayList<Object>();
        int httpSuccess = 0, correct = 0, healthy = 0, plannedCalls = 0, snapshotUnknown = 0, unknownSlots = 0, missingBudgets = 0;
        double cost = 0;
        long promptTokens = 0, completionTokens = 0, hitTokens = 0, missTokens = 0;
        boolean responseIdsMatch = true, sessionCountsMatch = true, noUnexpectedSessions = true, followUpsCorrect = true;
        var expectedCounts = new LinkedHashMap<String, Integer>();
        var latencies = new ArrayList<Long>();
        var slots = new HashSet<String>();
        var outcomes = new ArrayList<Object>();
        for (int i = 0; i < requests.size(); i++) {
            var request = requests.get(i);
            var item = JSON.treeToValue(protocol.path("cases").get(i), Scenario.class);
            require(item.id().equals(request.path("caseId").asText()), "Case order changed");
            require(item.question().equals(request.path("request").path("question").asText())
                    && item.sessionId().equals(request.path("request").path("sessionId").asText()), "Request differs from frozen input");
            var body = request.path("exchange").path("body");
            boolean success = request.path("exchange").path("httpStatus").asInt() == 200 && "SUCCESS".equals(body.path("status").asText());
            boolean businessCorrect = correct(item, body);
            if (success) httpSuccess++;
            if (businessCorrect) correct++;
            if (request.path("healthAfter").path("httpStatus").asInt() == 200) healthy++;
            latencies.add(request.path("exchange").path("httpWallMs").asLong());
            responseIdsMatch &= item.sessionId().equals(body.path("sessionId").asText());
            if (item.followUp()) followUpsCorrect &= businessCorrect && body.path("schemaReused").asBoolean()
                    && body.path("rewrittenQuestion").asText().contains("2017");
            expectedCounts.merge(item.sessionId(), 1, Integer::sum);
            var actualCounts = new LinkedHashMap<String, Integer>();
            for (JsonNode session : request.path("sessionsAfter").path("body")) {
                actualCounts.put(session.path("sessionId").asText(), session.path("turnCount").asInt());
                noUnexpectedSessions &= expectedCounts.containsKey(session.path("sessionId").asText());
            }
            sessionCountsMatch &= expectedCounts.equals(actualCounts) && request.path("sessionsAfter").path("httpStatus").asInt() == 200;
            int count = body.path("budget").path("modelCalls").asInt();
            if (!body.path("budget").isObject()) missingBudgets++;
            plannedCalls += count;
            snapshotUnknown += body.path("budget").path("unknownUsageCalls").asInt();
            String id = body.path("budget").path("requestId").asText();
            for (int number = 1; number <= count; number++) {
                String key = id + ":" + number;
                require(slots.add(key), "Duplicate response budget ID");
                JsonNode call = calls.get(key);
                boolean known = call != null && call.path("usageKnown").asBoolean() && call.path("totalTokens").asInt() > 0;
                if (!known) unknownSlots++;
                if (known) {
                    require(call.path("cacheHitTokens").asLong() + call.path("cacheMissTokens").asLong() == call.path("promptTokens").asLong(), "Input cache ledger mismatch");
                    require(call.path("promptTokens").asLong() + call.path("completionTokens").asLong() == call.path("totalTokens").asLong(), "Total token mismatch");
                    double calculated = call.path("cacheHitTokens").asLong() * .00002 / 1000
                            + call.path("cacheMissTokens").asLong() * .001 / 1000 + call.path("completionTokens").asLong() * .004 / 1000;
                    require(Math.abs(calculated - call.path("costYuan").asDouble()) < 1e-9, "Call price differs from frozen protocol");
                    cost += call.path("costYuan").asDouble();
                    promptTokens += call.path("promptTokens").asLong();
                    completionTokens += call.path("completionTokens").asLong();
                    hitTokens += call.path("cacheHitTokens").asLong();
                    missTokens += call.path("cacheMissTokens").asLong();
                }
                var entry = new LinkedHashMap<String, Object>();
                entry.put("caseId", item.id()); entry.put("slot", key); entry.put("usageKnown", known); entry.put("record", call);
                ledger.add(entry);
            }
            outcomes.add(Map.of("caseId", item.id(), "status", body.path("status").asText("TRANSPORT_ERROR"),
                    "businessCorrect", businessCorrect, "httpWallMs", latencies.getLast(), "modelCalls", count));
        }
        long extras = calls.keySet().stream().filter(key -> !slots.contains(key)).count();
        Collections.sort(latencies);
        var cleanupResponse = JSON.readTree(dir.resolve("sessions-after-cleanup.json").toFile());
        boolean cleanup = cleanupResponse.path("httpStatus").asInt() == 200
                && cleanupResponse.path("body").isArray() && cleanupResponse.path("body").isEmpty();
        int closes = 0, deletes = 0;
        boolean lifecyclePassed = true;
        for (JsonNode action : JSON.readTree(dir.resolve("lifecycle.json").toFile())) {
            String sessionId = action.path("sessionId").asText();
            if (action.has("close")) {
                closes++;
                var close = action.path("close");
                lifecyclePassed &= close.path("httpStatus").asInt() == 200
                        && sessionId.equals(close.path("body").path("sessionId").asText())
                        && close.path("body").path("turnCount").asInt() == 2
                        && !close.path("body").path("persisted").asBoolean();
                boolean foundClosed = false;
                for (JsonNode session : action.path("sessionsAfterClose").path("body")) {
                    if (sessionId.equals(session.path("sessionId").asText()))
                        foundClosed = "CLOSED".equals(session.path("status").asText()) && session.path("turnCount").asInt() == 2;
                }
                lifecyclePassed &= foundClosed && action.path("sessionsAfterClose").path("httpStatus").asInt() == 200;
            } else {
                deletes++;
                lifecyclePassed &= action.path("delete").path("httpStatus").asInt() == 204;
            }
        }
        lifecyclePassed &= closes == 2 && deletes == 18;
        boolean completeCost = unknownSlots == 0 && snapshotUnknown == 0 && extras == 0 && unassigned == 0 && missingBudgets == 0;
        var summary = new LinkedHashMap<String, Object>();
        summary.put("total", 20); summary.put("httpSuccess", httpSuccess); summary.put("businessCorrect", correct);
        summary.put("httpSuccessRate", httpSuccess / 20.0); summary.put("businessCorrectRate", correct / 20.0);
        summary.put("healthyAfterRequests", healthy);
        summary.put("meanHttpMs", latencies.stream().mapToLong(Long::longValue).average().orElseThrow());
        summary.put("p95HttpMs", latencies.get((int) Math.ceil(.95 * latencies.size()) - 1));
        summary.put("maxHttpMs", latencies.getLast()); summary.put("modelCalls", plannedCalls);
        summary.put("snapshotUnknownUsageCalls", snapshotUnknown); summary.put("ledgerUnknownUsageSlots", unknownSlots);
        summary.put("unassignedLogRecords", unassigned); summary.put("extraLogSlots", extras);
        summary.put("missingRequestBudgets", missingBudgets);
        summary.put("knownCostYuan", cost); summary.put("completeCost", completeCost);
        summary.put("costMeaning", completeCost ? "configured-price-estimate-not-invoice" : "known-lower-bound-not-complete-cost");
        summary.put("tokens", Map.of("prompt", promptTokens, "completion", completionTokens, "cacheHit", hitTokens, "cacheMiss", missTokens));
        summary.put("sessionIsolation", Map.of("responseIdsMatch", responseIdsMatch, "interleavedFollowUpsCorrect", followUpsCorrect,
                "sessionCountsMatch", sessionCountsMatch, "noUnexpectedSessions", noUnexpectedSessions, "cleanupEmpty", cleanup,
                "closeAndDeletePassed", lifecyclePassed));
        summary.put("outcomes", outcomes);
        writeNew(dir.resolve("ledger.json"), ledger);
        writeNew(dir.resolve("summary.json"), summary);
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    private static void writeNew(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private static void requireClean() throws Exception {
        var args = new ArrayList<>(List.of("status", "--porcelain", "--")); args.addAll(PROTECTED);
        require(git(args.toArray(String[]::new)).isBlank(), "Commit protected sources before acceptance");
    }

    private static String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        require(process.waitFor() == 0, "Git witness failed"); return output;
    }

    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
