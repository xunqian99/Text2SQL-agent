package com.text2sql.agent.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.Text2SqlAgentApplication;
import com.text2sql.agent.budget.DeepSeekOffPeakPolicy;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.cost.CostQuota;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.ResultNormalizer;
import com.text2sql.agent.execution.SqlExecutor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** One frozen four-case run. No model calls in freeze; run is off-peak only and never overwrites. */
public final class CustomerIdentityHttpProbe {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> PROTECTED = List.of("src/main/java", "src/main/resources",
            "src/test/java", "data/eval", "data/holdout", "pom.xml");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("freeze/run NEW_DIRECTORY");
        Path dir = Path.of(args[1]).toAbsolutePath().normalize();
        if ("freeze".equals(args[0])) { freeze(dir); return; }
        if (!"run".equals(args[0])) throw new IllegalArgumentException("freeze/run");
        verify(dir);
        var offPeak = new DeepSeekOffPeakPolicy(Clock.systemUTC());
        offPeak.requireAllowed();
        Files.createDirectory(dir.resolve("responses")); // A failed run is retained, never replayed.
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        String auth = "Basic " + Base64.getEncoder().encodeToString(("demo:" + token).getBytes(StandardCharsets.UTF_8));
        var app = new SpringApplication(Text2SqlAgentApplication.class);
        app.addInitializers(c -> c.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("probe-auth", Map.of("agent.access.token", token))));
        int correct = 0, calls = 0, unknown = 0, port;
        var outcomes = new ArrayList<Object>();
        Map<String, Object> ledger;
        try (var context = (ServletWebServerApplicationContext) app.run("--spring.profiles.active=local,demo,protected",
                "--server.port=0", "--server.address=127.0.0.1", "--agent.eval.enabled=false",
                "--agent.cost-quota.state-file=" + dir.resolve("cost-ledger.json"),
                "--agent.observability.dir=" + dir.resolve("llm-calls"), "--agent.observability.include-content=false")) {
            port = context.getWebServer().getPort();
            var config = context.getBean(AgentProperties.class);
            if (config.getLlm().getApiKey() == null || config.getLlm().getApiKey().isBlank())
                throw new IllegalStateException("Global credential required");
            if (!config.getRequestBudget().isEnabled() || config.getRequestBudget().getTimeoutMs() != 15000
                    || config.getRequestBudget().getMaxModelCalls() != 2)
                throw new IllegalStateException("Expected frozen 15s / 2-call budget");
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            for (var item : JSON.readTree(dir.resolve("protocol.json").toFile()).path("cases")) {
                verify(dir);
                offPeak.requireAllowed(DeepSeekOffPeakPolicy.horizon(config.getLlm().getTimeoutSeconds()));
                String id = item.path("id").asText();
                write(dir.resolve(id + "-offpeak.json"), offPeak.evaluate(DeepSeekOffPeakPolicy.horizon(config.getLlm().getTimeoutSeconds())));
                String body = JSON.writeValueAsString(Map.of("question", item.path("question").asText(), "sessionId", "identity-" + id));
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ask"))
                        .timeout(Duration.ofSeconds(25)).header("Authorization", auth).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                long start = System.nanoTime();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                long ms = (System.nanoTime() - start) / 1_000_000;
                JsonNode result = JSON.readTree(response.body());
                write(dir.resolve("responses").resolve(id + ".json"), Map.of("id", id, "httpStatus", response.statusCode(), "wallMs", ms, "body", result));
                boolean match = response.statusCode() == 200 && "SUCCESS".equals(result.path("status").asText())
                        && !result.path("truncated").asBoolean() && ResultNormalizer.normalize(query(result), false)
                        .equals(JSON.convertValue(item.path("expectedRows"), List.class));
                if (match) correct++;
                calls += result.path("budget").path("modelCalls").asInt();
                unknown += result.path("budget").path("unknownUsageCalls").asInt();
                outcomes.add(Map.of("id", id, "correct", match, "httpStatus", response.statusCode(), "wallMs", ms));
            }
            ledger = context.getBean(CostQuota.class).snapshot();
            write(dir.resolve("cost-status.json"), ledger);
        }
        write(dir.resolve("summary.json"), Map.ofEntries(Map.entry("correct", correct), Map.entry("cases", 4),
                Map.entry("modelCalls", calls), Map.entry("responseUnknownUsageCalls", unknown), Map.entry("outcomes", outcomes),
                Map.entry("knownConfiguredCostYuan", ledger.get("knownCostYuan")), Map.entry("port", port),
                Map.entry("pid", ProcessHandle.current().pid()), Map.entry("contextClosed", true),
                Map.entry("scope", "Known failure plus cross-wording, explicit row-key override and unrelated total; no blind accuracy/P95 claim.")));
        System.out.println("CUSTOMER_IDENTITY_HTTP matches=" + correct + "/4 calls=" + calls + " unknown=" + unknown);
        if (correct != 4) throw new IllegalStateException("Mismatch retained; do not rerun to select a better score");
    }

    private static void freeze(Path dir) throws Exception {
        clean(); Files.createDirectory(dir);
        var props = new AgentProperties();
        var loader = new EvalItemLoader(props);
        var original = loader.load("data/eval", List.of(), 0).stream().filter(i -> i.id().equals("T4-001")).findFirst().orElseThrow();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"), env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro")));
        var executor = new SqlExecutor(jdbc, props);
        var cases = new ArrayList<Object>();
        for (var item : List.of(List.of("original-customer-top10", original.question(), original.goldSql()),
                List.of("distinct-buyers", "有订单的顾客，按真实自然人去重后共多少人？",
                        "SELECT COUNT(DISTINCT c.customer_unique_id) FROM orders o JOIN customers c ON c.customer_id=o.customer_id"),
                List.of("explicit-row-key", "按 orders.customer_id 分组计数后，返回订单数量和对应的客户关联键数量，按订单数量升序。",
                        "SELECT cnt,COUNT(*) FROM (SELECT customer_id,COUNT(*) AS cnt FROM orders GROUP BY customer_id) t GROUP BY cnt ORDER BY cnt"),
                List.of("orders-total-control", "一共有多少笔订单？", "SELECT COUNT(*) FROM orders"))) {
            QueryResult result = executor.execute(item.get(2));
            if (result.truncated()) throw new IllegalStateException("Truncated reference");
            cases.add(Map.of("id", item.get(0), "question", item.get(1), "referenceSql", item.get(2),
                    "expectedRows", ResultNormalizer.normalize(result, false), "rowCount", result.rowCount()));
        }
        var trees = new LinkedHashMap<String, String>();
        for (String path : PROTECTED) trees.put(path, git("rev-parse", "HEAD:" + path));
        write(dir.resolve("protocol.json"), Map.ofEntries(Map.entry("format", "customer-identity-http-v1"),
                Map.entry("sourceCommit", git("rev-parse", "HEAD")), Map.entry("sourceTrees", trees),
                Map.entry("runtimeFiles", runtimeFiles()), Map.entry("datasetSha256", loader.datasetSha256("data/eval")),
                Map.entry("metricDefinitionsSha256", MetricRegistry.definitionSha256()), Map.entry("localConfigSha256", localHash()),
                Map.entry("cases", cases), Map.entry("scope", "Exactly four targeted requests, once; no benchmark/Gold/scoring changes.")));
        System.out.println("Frozen four references; modelCalls=0");
    }

    private static void verify(Path dir) throws Exception {
        clean(); JsonNode protocol = JSON.readTree(dir.resolve("protocol.json").toFile());
        for (var entry : protocol.path("sourceTrees").properties())
            if (!entry.getValue().asText().equals(git("rev-parse", "HEAD:" + entry.getKey()))) throw new IllegalStateException("Source changed");
        if (!protocol.path("runtimeFiles").equals(JSON.valueToTree(runtimeFiles()))) throw new IllegalStateException("Runtime changed");
        if (!protocol.path("localConfigSha256").asText().equals(localHash())) throw new IllegalStateException("Local config changed");
        String relative = Path.of("").toAbsolutePath().relativize(dir.resolve("protocol.json")).toString().replace('\\', '/');
        if (!git("show", "HEAD:" + relative).equals(Files.readString(dir.resolve("protocol.json")).strip()))
            throw new IllegalStateException("Commit frozen protocol first");
    }
    private static Map<String, String> runtimeFiles() throws Exception {
        var files = new TreeMap<String, String>();
        for (String root : List.of("target/classes", "target/test-classes")) {
            try (var walk = Files.walk(Path.of(root))) {
                for (var path : walk.filter(Files::isRegularFile).sorted().toList())
                    files.put(path.toString().replace('\\', '/'), sha(Files.readAllBytes(path)));
            }
        }
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path path = Path.of(entry); if (Files.isRegularFile(path)) files.put(path.toAbsolutePath().normalize().toString(), sha(Files.readAllBytes(path)));
        }
        return files;
    }
    private static QueryResult query(JsonNode body) {
        List<String> columns = JSON.convertValue(body.path("columns"), JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        List<List<String>> rows = JSON.convertValue(body.path("rows"), JSON.getTypeFactory().constructCollectionType(List.class,
                JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        return new QueryResult(columns, rows, body.path("truncated").asBoolean(), 0);
    }
    private static void clean() throws Exception {
        var args = new ArrayList<String>(List.of("status", "--porcelain", "--")); args.addAll(PROTECTED);
        if (!git(args.toArray(String[]::new)).isBlank()) throw new IllegalStateException("Commit protected sources first");
    }
    private static String git(String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0) throw new IllegalStateException("Git witness failed"); return output;
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String localHash() throws Exception { return sha(Files.readAllBytes(Path.of("src/main/resources/application-local.yml"))); }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    private static void write(Path path, Object body) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(body) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
