package com.text2sql.agent.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.Text2SqlAgentApplication;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.session.SessionStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explicit real production-server probe: synthetic model only, real API/filter/session/database. */
public final class AccessHttpAcceptanceProbe {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String QUESTION = "2018年有多少笔订单？";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath();
        Files.createDirectory(dir);
        var evidence = new ArrayList<Map<String, Object>>();
        var calls = new AtomicInteger();
        var ports = new ArrayList<Integer>();
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        String auth = "Basic " + Base64.getEncoder().encodeToString(("demo:" + token).getBytes(StandardCharsets.UTF_8));
        try (var context = start(token, 100, 60000)) {
            int port = context.getWebServer().getPort(); ports.add(port);
            String base = "http://127.0.0.1:" + port;
            var model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenAnswer(i -> { calls.incrementAndGet(); return sql(); });
            ReflectionTestUtils.setField(context.getBean(LlmSqlGenerator.class), "chatModel", model);
            var store = context.getBean(SessionStore.class);
            var gate = context.getBean(ApiAdmissionGate.class);
            for (String endpoint : List.of("GET /", "GET /index.html", "GET /api/health", "POST /api/ask",
                    "GET /api/sessions", "POST /api/session/probe/close", "DELETE /api/session/probe",
                    "GET /api/system/logs", "GET /api/system/users", "GET /api/metrics", "GET /api/products",
                    "PUT /api/orders/probe/status", "GET /api/access/status", "GET /unknown")) {
                String[] parts = endpoint.split(" ", 2);
                check(evidence, base, "unauthorized-" + endpoint, parts[0], parts[1], null, null, null, 401);
            }
            check(evidence, base, "byok-is-not-login", "POST", "/api/ask", null, null,
                    Map.of("X-Api-Key", "synthetic-unrelated-provider-key"), 401);
            check(evidence, base, "wrong-password", "GET", "/api/sessions", "Basic ZGVtbzp3cm9uZw==", null, null, 401);
            check(evidence, base, "malformed-basic", "POST", "/api/ask", "Basic %%%%", null, null, 401);
            assertThat(calls).hasValue(0);
            assertThat(store.listSessions()).isEmpty();
            check(evidence, base, "cross-origin", "POST", "/api/ask", auth, request("probe"),
                    Map.of("Origin", "http://untrusted.invalid"), 403);
            check(evidence, base, "cross-site-get", "GET", "/api/sessions", auth, null,
                    Map.of("Sec-Fetch-Site", "cross-site"), 403);
            assertThat(calls).hasValue(0);
            assertThat(store.listSessions()).isEmpty();
            check(evidence, base, "authenticated-index", "GET", "/index.html", auth, null, null, 200);
            check(evidence, base, "authenticated-health", "GET", "/api/health", auth, null, null, 200);
            var status = check(evidence, base, "effective-limits", "GET", "/api/access/status", auth, null, null, 200);
            assertThat(((JsonNode) status.get("body")).path("enabled").asBoolean()).isTrue();
            var normal = check(evidence, base, "same-origin-ask", "POST", "/api/ask", auth,
                    request("probe"), Map.of("Origin", base), 200);
            assertThat(((JsonNode) normal.get("body")).path("rows").get(0).get(0).asText()).isEqualTo("54011");
            var originalTurn = store.get("probe").orElseThrow().turns();
            check(evidence, base, "cannot-delete-without-login", "DELETE", "/api/session/probe", null, null, null, 401);
            check(evidence, base, "cannot-close-without-login", "POST", "/api/session/probe/close", null, null, null, 401);
            assertThat(store.get("probe").orElseThrow().turns()).isEqualTo(originalTurn);
            assertThat(store.get("probe").orElseThrow().isClosed()).isFalse();
            check(evidence, base, "authenticated-sessions", "GET", "/api/sessions", auth, null, null, 200);
            check(evidence, base, "authenticated-close", "POST", "/api/session/probe/close", auth, null, null, 200);
            check(evidence, base, "authenticated-delete", "DELETE", "/api/session/probe", auth, null, null, 204);
            assertThat(store.get("probe")).isEmpty();
            check(evidence, base, "invalid-request", "POST", "/api/ask", auth, Map.of("question", ""), null, 400);
            assertThat(gate.activeRequests()).isZero();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(model.call(any(Prompt.class))).thenAnswer(i -> {
                calls.incrementAndGet(); entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Acceptance release timed out");
                return sql();
            });
            var slow = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return exchange(base, "POST", "/api/ask", auth, request("blocked"), null); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(gate.activeRequests()).isEqualTo(1);
                check(evidence, base, "concurrency-refusal", "GET", "/api/sessions", auth, null,
                        Map.of("X-Forwarded-For", "203.0.113.5"), 429);
                check(evidence, base, "health-during-saturation", "GET", "/api/health", auth, null, null, 200);
            } finally { release.countDown(); }
            var completed = slow.get(4, TimeUnit.SECONDS);
            completed.put("name", "released-blocked-request"); evidence.add(completed);
            assertThat(completed.get("httpStatus")).isEqualTo(200);
            check(evidence, base, "permit-released", "GET", "/api/sessions", auth, null, null, 200);
            assertThat(gate.activeRequests()).isZero();
        }
        try (var context = start(token, 1, 1)) {
            int port = context.getWebServer().getPort(); ports.add(port);
            String base = "http://127.0.0.1:" + port;
            check(evidence, base, "first-rate-token", "GET", "/api/sessions", auth, null, null, 200);
            check(evidence, base, "rate-refusal", "GET", "/api/sessions", auth, null,
                    Map.of("X-Forwarded-For", "198.51.100.1"), 429);
            check(evidence, base, "health-after-rate-refusal", "GET", "/api/health", auth, null, null, 200);
            assertThat(context.getBean(ApiAdmissionGate.class).activeRequests()).isZero();
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("format", "access-http-acceptance-v1"); summary.put("passed", true);
        summary.put("checks", evidence.size()); summary.put("syntheticModelCalls", calls.get());
        summary.put("externalProviderCalls", 0); summary.put("providerCostYuan", 0);
        summary.put("ports", ports); summary.put("contextsClosed", true);
        summary.put("scope", "Actual production Spring Boot configuration, filter, controllers, sessions and PostgreSQL; synthetic ChatModel. No real-model accuracy claim.");
        write(dir.resolve("requests.json"), evidence); write(dir.resolve("summary.json"), summary);
        System.out.println("ACCESS_HTTP checks=" + evidence.size() + " syntheticCalls=" + calls + " externalCalls=0 contextsClosed=true ports=" + ports);
    }

    private static ServletWebServerApplicationContext start(String token, int burst, int rate) {
        var app = new SpringApplication(Text2SqlAgentApplication.class);
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("acceptance-credentials", Map.of("agent.access.token", token))));
        return (ServletWebServerApplicationContext) app.run("--spring.profiles.active=local,demo,protected",
                "--server.port=0", "--server.address=127.0.0.1", "--agent.eval.enabled=false",
                "--agent.access.max-concurrent=1", "--agent.access.burst=" + burst, "--agent.access.requests-per-minute=" + rate,
                "--agent.llm.api-key=", "--agent.llm.base-url=http://127.0.0.1:1",
                "--agent.llm.input-price-per-1k=0", "--agent.llm.output-price-per-1k=0",
                "--agent.llm.cache-hit-price-per-1k=0", "--agent.observability.enabled=false");
    }

    private static Map<String, Object> request(String session) { return Map.of("question", QUESTION, "sessionId", session); }
    private static ChatResponse sql() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(
                "SELECT COUNT(*) AS order_count FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
    }

    private static Map<String, Object> check(List<Map<String, Object>> all, String base, String name, String method,
                                            String path, String auth, Object body, Map<String, String> headers, int expected) throws Exception {
        var result = exchange(base, method, path, auth, body, headers);
        result.put("name", name); all.add(result);
        assertThat(result.get("httpStatus")).as(name).isEqualTo(expected);
        if (expected == 429) assertThat(((Map<?, ?>) result.get("headers")).get("retry-after")).isNotNull();
        return result;
    }

    private static Map<String, Object> exchange(String base, String method, String path, String auth,
                                                 Object body, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8));
        if (auth != null) request.header("Authorization", auth);
        if (headers != null) headers.forEach(request::header);
        request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        long started = System.nanoTime();
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        var item = new LinkedHashMap<String, Object>();
        item.put("method", method); item.put("path", path); item.put("authenticated", auth != null);
        item.put("httpStatus", response.statusCode()); item.put("wallMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        var safeHeaders = new LinkedHashMap<String, String>();
        for (String name : List.of("www-authenticate", "retry-after", "cache-control", "content-type"))
            response.headers().firstValue(name).ifPresent(value -> safeHeaders.put(name, value));
        item.put("headers", safeHeaders);
        if (response.body().isBlank()) item.put("body", null);
        else if (response.headers().firstValue("Content-Type").orElse("").contains("application/json")) item.put("body", JSON.readTree(response.body()));
        else item.put("body", Map.of("contentLengthChars", response.body().length(), "containsHtml", response.body().toLowerCase().contains("<html")));
        return item;
    }
    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
