package com.text2sql.agent.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.Text2SqlAgentApplication;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.session.SessionStore;
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
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real protected HTTP server and PostgreSQL; synthetic usage/prices/model, never a provider call. */
public final class CostHttpAcceptanceProbe {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String QUESTION = "2018年有多少笔订单？";
    private static final String SQL = "SELECT COUNT(*) AS order_count FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath();
        if (args.length == 1) {
            Files.createDirectory(dir);
            for (String phase : List.of("initial", "restart", "correction")) {
                var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                        "-cp", System.getProperty("java.class.path"), CostHttpAcceptanceProbe.class.getName(), dir.toString(), phase)
                        .redirectErrorStream(true).redirectOutput(dir.resolve(phase + ".log").toFile()).start();
                if (!process.waitFor(45, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Probe child did not exit");
                    throw new IllegalStateException("Probe phase timed out: " + phase);
                }
                if (process.exitValue() != 0) throw new IllegalStateException("Probe phase failed: " + phase + "; see its log");
            }
            var ports = new ArrayList<Integer>(); var pids = new ArrayList<Long>();
            for (String phase : List.of("initial", "restart", "correction")) {
                var result = JSON.readTree(dir.resolve(phase + "-summary.json").toFile());
                ports.add(result.path("port").asInt()); pids.add(result.path("pid").asLong());
            }
            write(dir.resolve("summary.json"), Map.of("passed", true, "scenarios", 4, "ports", ports, "pids", pids,
                    "syntheticModelCalls", 3, "externalProviderCalls", 0, "providerCostYuan", 0,
                    "contextsClosed", true, "scope", "Known/unknown usage, restart in a new JVM, correction first-answer fallback; real protected HTTP/PostgreSQL with synthetic usage/prices/model."));
            System.out.println("COST_HTTP passed=4 syntheticCalls=3 externalCalls=0 childProcessesExited=true ports=" + ports);
            return;
        }
        String phase = args[1];
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        String auth = "Basic " + Base64.getEncoder().encodeToString(("demo:" + token).getBytes(StandardCharsets.UTF_8));
        var ports = new ArrayList<Integer>(); var modelCalls = new AtomicInteger();
        if ("initial".equals(phase)) try (var context = start(dir.resolve("ledger.json"), "0.12", token)) {
            ports.add(context.getWebServer().getPort()); String base = base(context);
            var model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenAnswer(i -> {
                int call = modelCalls.incrementAndGet();
                return call == 1 ? knownSql() : new ChatResponse(List.of(new Generation(new AssistantMessage(SQL))));
            });
            ReflectionTestUtils.setField(context.getBean(LlmSqlGenerator.class), "chatModel", model);
            var quota = context.getBean(CostQuota.class);
            var unauthorized = exchange(base, "GET", "/api/cost/status", null, null);
            assertThat(unauthorized.path("httpStatus").asInt()).isEqualTo(401);
            write(dir.resolve("unauthorized.json"), unauthorized);
            var byok = exchange(base, "POST", "/api/ask", auth, Map.of("question", QUESTION, "apiKey", "synthetic-override-disallowed"));
            assertThat(byok.path("body").path("budget").path("termination").asText()).isEqualTo("BYOK_DISABLED_BY_COST_QUOTA");
            assertThat(byok.path("body").path("budget").path("modelCalls").asInt()).isZero();
            assertThat(modelCalls).hasValue(0); write(dir.resolve("byok-rejected.json"), byok);
            var known = ask(base, auth, "known");
            assertThat(known.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(known.path("body").path("rows").get(0).get(0).asText()).isEqualTo("54011");
            assertThat((BigDecimal) quota.snapshot().get("knownCostYuan")).isEqualByComparingTo("0.02");
            write(dir.resolve("known-response.json"), known);
            var unknown = ask(base, auth, "unknown");
            assertThat(unknown.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(unknown.path("body").path("budget").path("unknownUsageCalls").asInt()).isEqualTo(1);
            write(dir.resolve("unknown-response.json"), unknown);
            var status = exchange(base, "GET", "/api/cost/status", auth, null);
            assertThat(status.path("body").path("committedYuan").decimalValue()).isEqualByComparingTo("0.12");
            assertThat(status.path("body").path("unknownCalls").asInt()).isEqualTo(1);
            assertThat(status.path("body").path("canAdmitCall").asBoolean()).isFalse();
            write(dir.resolve("status-before-restart.json"), status);
            var rejected = ask(base, auth, "rejected"); assertQuotaRefusal(rejected);
            write(dir.resolve("quota-rejected.json"), rejected);
            var store = context.getBean(SessionStore.class);
            assertThat(store.get("rejected").orElseThrow().lastTurn().status()).isEqualTo("RESOURCE_LIMITED");
            assertThat(modelCalls).hasValue(2);
            write(dir.resolve("sessions-before-restart.json"), exchange(base, "GET", "/api/sessions", auth, null));
            write(dir.resolve("health-at-cap.json"), exchange(base, "GET", "/api/health", auth, null));
        }
        if ("restart".equals(phase)) try (var context = start(dir.resolve("ledger.json"), "0.12", token)) {
            ports.add(context.getWebServer().getPort()); String base = base(context);
            String budgetId = JSON.readTree(dir.resolve("status-before-restart.json").toFile()).path("body").path("budgetId").asText();
            assertThat(context.getBean(CostQuota.class).snapshot().get("budgetId")).isEqualTo(budgetId);
            var model = mock(ChatModel.class);
            ReflectionTestUtils.setField(context.getBean(LlmSqlGenerator.class), "chatModel", model);
            var rejected = ask(base, auth, "restart-rejected"); assertQuotaRefusal(rejected);
            write(dir.resolve("restart-rejected.json"), rejected);
            write(dir.resolve("status-after-restart.json"), exchange(base, "GET", "/api/cost/status", auth, null));
            verifyNoInteractions(model);
        }
        if ("correction".equals(phase)) try (var context = start(dir.resolve("correction-ledger.json"), "0.10", token)) {
            ports.add(context.getWebServer().getPort()); String base = base(context);
            var model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenAnswer(i -> { modelCalls.incrementAndGet(); return knownSql(); });
            ReflectionTestUtils.setField(context.getBean(LlmSqlGenerator.class), "chatModel", model);
            var checker = mock(ResultChecker.class);
            when(checker.check(anyString(), anyString(), any())).thenReturn(CheckResult.suspicious("SYNTHETIC", "controlled retry"));
            ReflectionTestUtils.setField(context.getBean(Text2SqlOrchestrator.class), "resultChecker", checker);
            var result = ask(base, auth, "correction");
            assertThat(result.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(result.path("body").path("status").asText()).isEqualTo("SUCCESS");
            assertThat(result.path("body").path("budget").path("termination").asText()).isEqualTo("COST_QUOTA_EXHAUSTED");
            assertThat(result.path("body").path("budget").path("modelCalls").asInt()).isEqualTo(1);
            assertThat(result.path("body").path("budget").path("unknownUsageCalls").asInt()).isZero();
            assertThat(context.getBean(SessionStore.class).get("correction").orElseThrow().turns()).hasSize(1);
            assertThat(context.getBean(SessionStore.class).get("correction").orElseThrow().lastTurn().sql()).isEqualTo(result.path("body").path("sql").asText());
            write(dir.resolve("correction-fallback.json"), result);
            write(dir.resolve("correction-status.json"), exchange(base, "GET", "/api/cost/status", auth, null));
            verify(model, times(1)).call(any(Prompt.class));
        }
        assertThat(ports).hasSize(1);
        write(dir.resolve(phase + "-summary.json"), Map.of("phase", phase, "passed", true, "port", ports.getFirst(),
                "pid", ProcessHandle.current().pid(), "syntheticModelCalls", modelCalls.get(), "externalProviderCalls", 0, "contextsClosed", true));
    }

    private static void assertQuotaRefusal(JsonNode response) {
        assertThat(response.path("httpStatus").asInt()).isEqualTo(503);
        assertThat(response.path("body").path("budget").path("termination").asText()).isEqualTo("COST_QUOTA_EXHAUSTED");
        assertThat(response.path("body").path("budget").path("modelCalls").asInt()).isZero();
        assertThat(response.path("body").path("budget").path("unknownUsageCalls").asInt()).isZero();
    }
    private static ServletWebServerApplicationContext start(Path ledger, String total, String token) {
        var app = new SpringApplication(Text2SqlAgentApplication.class);
        app.addInitializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe-credentials", Map.of("agent.access.token", token))));
        return (ServletWebServerApplicationContext) app.run("--spring.profiles.active=local,demo,protected", "--server.port=0",
                "--server.address=127.0.0.1", "--agent.eval.enabled=false", "--agent.cost-quota.state-file=" + ledger,
                "--agent.cost-quota.total-yuan=" + total, "--agent.cost-quota.reservation-yuan=0.10",
                "--agent.access.burst=100", "--agent.access.requests-per-minute=60000",
                "--agent.llm.api-key=", "--agent.llm.base-url=http://127.0.0.1:1", "--agent.llm.model=synthetic-cost-probe",
                "--agent.llm.input-price-per-1k=1", "--agent.llm.output-price-per-1k=2", "--agent.llm.cache-hit-price-per-1k=0.1",
                "--agent.observability.enabled=false");
    }
    private static String base(ServletWebServerApplicationContext c) { return "http://127.0.0.1:" + c.getWebServer().getPort(); }
    private static ChatResponse knownSql() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(SQL))), ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
    }
    private static JsonNode ask(String base, String auth, String session) throws Exception {
        return exchange(base, "POST", "/api/ask", auth, Map.of("question", QUESTION, "sessionId", session));
    }
    private static JsonNode exchange(String base, String method, String path, String auth, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8));
        if (auth != null) request.header("Authorization", auth);
        request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        long started = System.nanoTime(); var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return JSON.valueToTree(Map.of("httpStatus", response.statusCode(), "wallMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "body", JSON.readTree(response.body())));
    }
    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
