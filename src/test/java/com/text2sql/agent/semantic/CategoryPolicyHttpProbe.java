package com.text2sql.agent.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.Text2SqlAgentApplication;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.cost.CostQuota;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.ResultNormalizer;
import com.text2sql.agent.execution.SqlExecutor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit, paid, targeted integration probe. Freeze and commit inputs before invoking run. */
public final class CategoryPolicyHttpProbe {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> PROTECTED = List.of("src/main/java", "src/main/resources", "src/test/java", "data/eval", "data/holdout", "pom.xml");
    private static final String GROUP_SQL = "SELECT p.product_category_name, COUNT(DISTINCT oi.order_id) AS order_cnt FROM order_items oi JOIN products p ON p.product_id=oi.product_id";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[1]).toAbsolutePath().normalize();
        if ("freeze".equals(args[0])) { freeze(dir); return; }
        if (!"run".equals(args[0])) throw new IllegalArgumentException("Expected freeze/run and a fresh report directory");
        verify(dir);
        Path responses = dir.resolve("responses"); Files.createDirectory(responses);
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        String auth = "Basic " + Base64.getEncoder().encodeToString(("demo:" + token).getBytes(StandardCharsets.UTF_8));
        var app = new SpringApplication(Text2SqlAgentApplication.class);
        app.addInitializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe-auth", Map.of("agent.access.token", token))));
        var outcomes = new ArrayList<Object>(); int correct = 0, modelCalls = 0, unknownCalls = 0, port;
        Map<String, Object> ledger;
        try (var context = (ServletWebServerApplicationContext) app.run("--spring.profiles.active=local,demo,protected", "--server.port=0",
                "--server.address=127.0.0.1", "--agent.eval.enabled=false", "--agent.cost-quota.state-file=" + dir.resolve("cost-ledger.json"),
                "--agent.observability.dir=" + dir.resolve("llm-calls"), "--agent.observability.include-content=false")) {
            port = context.getWebServer().getPort();
            var config = context.getBean(AgentProperties.class);
            if (config.getLlm().getApiKey() == null || config.getLlm().getApiKey().isBlank()) throw new IllegalStateException("Global model credential required");
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            for (var item : JSON.readTree(dir.resolve("protocol.json").toFile()).path("cases")) {
                verify(dir);
                String id = item.path("id").asText();
                String body = JSON.writeValueAsString(Map.of("question", item.path("question").asText(), "sessionId", "category-" + id));
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ask"))
                        .timeout(Duration.ofSeconds(25)).header("Authorization", auth).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                long started = System.nanoTime(); var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                long ms = (System.nanoTime() - started) / 1_000_000;
                JsonNode result = JSON.readTree(response.body());
                write(responses.resolve(id + ".json"), Map.of("id", id, "httpStatus", response.statusCode(), "wallMs", ms, "body", result));
                boolean match = response.statusCode() == 200 && "SUCCESS".equals(result.path("status").asText())
                        && !result.path("truncated").asBoolean() && ResultNormalizer.normalize(query(result), false).equals(
                        JSON.convertValue(item.path("expectedRows"), List.class));
                if (match) correct++;
                modelCalls += result.path("budget").path("modelCalls").asInt();
                unknownCalls += result.path("budget").path("unknownUsageCalls").asInt();
                outcomes.add(Map.of("id", id, "httpStatus", response.statusCode(), "correct", match, "wallMs", ms,
                        "modelCalls", result.path("budget").path("modelCalls").asInt(), "unknownUsageCalls", result.path("budget").path("unknownUsageCalls").asInt()));
            }
            ledger = context.getBean(CostQuota.class).snapshot();
            write(dir.resolve("cost-status.json"), ledger);
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("passed", correct == 3); summary.put("cases", 3); summary.put("correct", correct);
        summary.put("modelCalls", modelCalls); summary.put("responseUnknownUsageCalls", unknownCalls);
        summary.put("knownConfiguredCostYuan", ledger.get("knownCostYuan"));
        summary.put("completeCost", unknownCalls == 0 && ((Number) ledger.get("unknownCalls")).longValue() == 0 && ((Number) ledger.get("pendingCalls")).longValue() == 0);
        summary.put("outcomes", outcomes); summary.put("port", port); summary.put("pid", ProcessHandle.current().pid());
        summary.put("contextClosed", true); summary.put("scope", "Three frozen targeted cases with real model/HTTP/PostgreSQL; known failure reproduction, not blind accuracy or production P95.");
        write(dir.resolve("summary.json"), summary);
        System.out.println("CATEGORY_HTTP correct=" + correct + "/3 calls=" + modelCalls + " unknown=" + unknownCalls + " port=" + port);
        if (correct != 3) throw new IllegalStateException("Targeted result mismatch; preserve evidence without retrying");
    }

    private static void freeze(Path dir) throws Exception {
        clean(); Files.createDirectory(dir);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"), env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro")));
        var executor = new SqlExecutor(jdbc, new AgentProperties());
        var cases = new ArrayList<Object>();
        for (var item : List.of(
                List.of("default-category-orders", "每个商品类目的订单数量是多少？", GROUP_SQL + " WHERE p.product_category_name IS NOT NULL GROUP BY 1"),
                List.of("include-null-category-orders", "每个商品类目的订单数量是多少？请包含类目缺失的订单，保留 NULL 分组。", GROUP_SQL + " GROUP BY 1"),
                List.of("total-products-control", "一共有多少个商品？", "SELECT COUNT(*) FROM products"))) {
            QueryResult result = executor.execute(item.get(2));
            if (result.truncated()) throw new IllegalStateException("Truncated reference");
            cases.add(Map.of("id", item.get(0), "question", item.get(1), "referenceSql", item.get(2),
                    "expectedRows", ResultNormalizer.normalize(result, false), "rowCount", result.rowCount()));
        }
        var trees = new LinkedHashMap<String, String>();
        for (String path : PROTECTED) trees.put(path, git("rev-parse", "HEAD:" + path));
        write(dir.resolve("protocol.json"), Map.of("format", "category-targeted-http-v1", "sourceCommit", git("rev-parse", "HEAD"),
                "sourceTrees", trees, "localConfigSha256", localHash(), "cases", cases,
                "scope", "Known original failure plus explicit NULL override and unrelated total; fixed one-run protocol, no retry selection or blind accuracy claim."));
    }

    private static void verify(Path dir) throws Exception {
        clean();
        JsonNode protocol = JSON.readTree(dir.resolve("protocol.json").toFile());
        var it = protocol.path("sourceTrees").fields();
        while (it.hasNext()) { var entry = it.next(); if (!entry.getValue().asText().equals(git("rev-parse", "HEAD:" + entry.getKey()))) throw new IllegalStateException("Source changed"); }
        if (!protocol.path("localConfigSha256").asText().equals(localHash())) throw new IllegalStateException("Local config changed");
        String relative = Path.of("").toAbsolutePath().relativize(dir.resolve("protocol.json")).toString().replace('\\', '/');
        if (!git("show", "HEAD:" + relative).equals(Files.readString(dir.resolve("protocol.json")).strip())) throw new IllegalStateException("Commit frozen protocol first");
    }
    private static QueryResult query(JsonNode body) {
        List<String> columns = JSON.convertValue(body.path("columns"), JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        List<List<String>> rows = JSON.convertValue(body.path("rows"), JSON.getTypeFactory().constructCollectionType(List.class,
                JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        return new QueryResult(columns, rows, body.path("truncated").asBoolean(), 0);
    }
    private static void clean() throws Exception {
        var args = new ArrayList<String>(List.of("status", "--porcelain", "--")); args.addAll(PROTECTED);
        if (!git(args.toArray(String[]::new)).isBlank()) throw new IllegalStateException("Commit protected sources before acceptance");
    }
    private static String git(String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0) throw new IllegalStateException("Git witness failed"); return output;
    }
    private static String localHash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("src/main/resources/application-local.yml"))));
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    private static void write(Path path, Object body) throws Exception { Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(body) + "\n", StandardOpenOption.CREATE_NEW); }
}
