package com.text2sql.agent.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.Text2SqlAgentApplication;
import com.text2sql.agent.budget.RequestBudgetRunner;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.validation.CheckResult;
import com.text2sql.agent.validation.ResultChecker;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explicit local HTTP acceptance, synthetic model and retry trigger, real PostgreSQL/SQL guards. */
public final class SessionOutcomeAcceptanceProbe {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String QUESTION = "2018年有多少笔订单？";
    private static final String SQL = "SELECT COUNT(*) AS order_count FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath();
        Files.createDirectory(dir); // Refuse evidence overwrite.
        var app = new SpringApplication(Text2SqlAgentApplication.class);
        try (var context = (ServletWebServerApplicationContext) app.run(
                "--spring.profiles.active=local,demo", "--server.address=127.0.0.1", "--server.port=0",
                "--agent.llm.api-key=", "--agent.llm.base-url=http://127.0.0.1:1",
                "--agent.llm.model=synthetic-session-acceptance", "--agent.eval.enabled=false",
                "--agent.llm.input-price-per-1k=0", "--agent.llm.output-price-per-1k=0",
                "--agent.llm.cache-hit-price-per-1k=0", "--agent.observability.dir=" + dir.resolve("llm-calls"))) {
            var properties = context.getBean(AgentProperties.class);
            assertThat(properties.getLlm().getApiKey()).isBlank();
            assertThat(properties.getRequestBudget().getTimeoutMs()).isEqualTo(15000);
            assertThat(properties.getRequestBudget().getMaxModelCalls()).isEqualTo(2);
            assertThat(properties.getPersistence().isEnabled()).isFalse();
            var model = mock(ChatModel.class);
            var calls = new AtomicInteger();
            when(model.call(any(Prompt.class))).thenAnswer(i -> { calls.incrementAndGet(); return sql(); });
            ReflectionTestUtils.setField(context.getBean(LlmSqlGenerator.class), "chatModel", model);
            var store = context.getBean(SessionStore.class);
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            write(dir.resolve("health.json"), exchange(base, "GET", "/api/health", null));
            var good = exchange(base, "POST", "/api/ask", Map.of("question", QUESTION, "sessionId", "probe-good"));
            write(dir.resolve("normal.json"), good);
            assertThat(good.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(good.path("body").path("rows").get(0).get(0).asText()).isEqualTo("54011");
            var goodTurns = store.get("probe-good").orElseThrow().turns();
            assertThat(goodTurns).hasSize(1);
            scenario(dir, base, store, model, calls, "generation-timeout", false);

            // Trigger correction deterministically, while keeping validation and execution real.
            var orchestrator = context.getBean(Text2SqlOrchestrator.class);
            Object originalChecker = ReflectionTestUtils.getField(orchestrator, "resultChecker");
            var checker = mock(ResultChecker.class);
            when(checker.check(anyString(), anyString(), any())).thenReturn(
                    CheckResult.suspicious("SYNTHETIC_RETRY", "acceptance-only controlled retry trigger"));
            ReflectionTestUtils.setField(orchestrator, "resultChecker", checker);
            try { scenario(dir, base, store, model, calls, "correction-timeout", true); }
            finally { ReflectionTestUtils.setField(orchestrator, "resultChecker", originalChecker); }
            assertThat(store.get("probe-good").orElseThrow().turns()).isEqualTo(goodTurns);
            write(dir.resolve("sessions-final.json"), exchange(base, "GET", "/api/sessions", null));
            write(dir.resolve("summary.json"), Map.of(
                    "format", "session-outcome-http-v1", "passed", true, "scenarios", 3,
                    "syntheticModelCalls", calls.get(), "externalProviderCalls", 0, "providerCostYuan", 0,
                    "independentGoodSessionUnchanged", true,
                    "limitations", "Synthetic model/usage and retry trigger; real local HTTP, SQL guards and PostgreSQL. Not accuracy or production P95 evidence."));
        }
        System.out.println("SESSION_OUTCOME_HTTP passed=3 externalCalls=0 costYuan=0 serverClosed=true");
    }

    private static void scenario(Path dir, String base, SessionStore store, ChatModel model,
                                 AtomicInteger totalCalls, String name, boolean correction) throws Exception {
        var release = new CountDownLatch(1);
        var scenarioCalls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(i -> {
            totalCalls.incrementAndGet();
            if (!correction || scenarioCalls.incrementAndGet() == 2) {
                boolean interrupted = false;
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            return sql();
        });
        try {
            JsonNode response = exchange(base, "POST", "/api/ask", Map.of("question", QUESTION, "sessionId", name));
            var session = store.get(name).orElseThrow();
            var before = session.turns();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("response", response);
            evidence.put("historyBeforeLateAnswer", before);
            evidence.put("sessionsBeforeLateAnswer", exchange(base, "GET", "/api/sessions", null));
            evidence.put("hasSchemaBeforeLateAnswer", session.lastSchema() != null);
            write(dir.resolve(name + "-before.json"), evidence);
            assertThat(response.path("httpStatus").asInt()).isEqualTo(correction ? 200 : 504);
            var body = response.path("body");
            assertThat(body.path("status").asText()).isEqualTo(correction ? "SUCCESS" : "REQUEST_TIMEOUT");
            assertThat(response.path("wallMs").asLong()).isBetween(14900L, 18000L);
            assertThat(body.path("budget").path("termination").asText()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(body.path("budget").path("modelCalls").asInt()).isEqualTo(correction ? 2 : 1);
            assertThat(body.path("budget").path("unknownUsageCalls").asInt()).isEqualTo(1);
            assertThat(before).hasSize(1);
            assertThat(before.getFirst().status()).isEqualTo(body.path("status").asText());
            assertThat(before.getFirst().sql()).isEqualTo(body.path("sql").isNull() ? null : body.path("sql").asText());
            assertThat(session.lastSchema() != null).isEqualTo(correction);
            assertThat(evidence.get("sessionsBeforeLateAnswer")).isNotNull();
            JsonNode sessions = ((JsonNode) evidence.get("sessionsBeforeLateAnswer")).path("body");
            JsonNode summary = java.util.stream.StreamSupport.stream(sessions.spliterator(), false)
                    .filter(s -> name.equals(s.path("sessionId").asText())).findFirst().orElseThrow();
            assertThat(summary.path("turnCount").asInt()).isEqualTo(1);
            release.countDown();
            awaitIdleWorkers(); // Model return alone does not prove the abandoned pipeline has exited.
            assertThat(session.turns()).isEqualTo(before);
            assertThat(session.lastSchema() != null).isEqualTo(correction);
            write(dir.resolve(name + "-after.json"), Map.of("historyAfterLateAnswer", session.turns(),
                    "hasSchemaAfterLateAnswer", session.lastSchema() != null, "workerActiveCount", 0,
                    "sessionsAfterLateAnswer", exchange(base, "GET", "/api/sessions", null)));
            System.out.println(name + " http=" + response.path("httpStatus") + " wallMs=" + response.path("wallMs") + " history=1 lateDuplicate=0");
        } finally { release.countDown(); awaitIdleWorkers(); }
    }

    private static void awaitIdleWorkers() throws InterruptedException {
        var workers = (ThreadPoolExecutor) ReflectionTestUtils.getField(RequestBudgetRunner.class, "WORKERS");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (workers.getActiveCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(workers.getActiveCount()).isZero();
    }

    private static ChatResponse sql() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(SQL))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
    }

    private static JsonNode exchange(String base, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(25));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        request.header("Content-Type", "application/json");
        long started = System.nanoTime();
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return JSON.valueToTree(Map.of("httpStatus", response.statusCode(), "wallMs",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "body", JSON.readTree(response.body())));
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
