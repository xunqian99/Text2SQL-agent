package com.text2sql.agent.clarification;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Production HTTP/clarification/session flow. Only ChatModel is synthetic; no accuracy claim. */
public final class ClarificationHttpAcceptanceProbe {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    record Case(String id, String question, boolean needsClarification) { }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath(); Files.createDirectory(dir);
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        String auth = "Basic " + Base64.getEncoder().encodeToString(("demo:" + token).getBytes(StandardCharsets.UTF_8));
        var app = new SpringApplication(Text2SqlAgentApplication.class);
        app.addInitializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe-auth", Map.of("agent.access.token", token))));
        var calls = new AtomicInteger(); var evidence = new ArrayList<Object>(); int port;
        try (var context = (ServletWebServerApplicationContext) app.run("--spring.profiles.active=local,demo,protected", "--server.port=0",
                "--server.address=127.0.0.1", "--agent.eval.enabled=false", "--agent.cost-quota.state-file=" + dir.resolve("ledger.json"),
                "--agent.access.burst=100", "--agent.access.requests-per-minute=60000",
                "--agent.llm.api-key=", "--agent.llm.base-url=http://127.0.0.1:1",
                "--agent.llm.input-price-per-1k=1", "--agent.llm.output-price-per-1k=2",
                "--agent.llm.cache-hit-price-per-1k=0.1", "--agent.observability.enabled=false")) {
            port = context.getWebServer().getPort();
            var model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenAnswer(i -> {
                calls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("SELECT 1 AS controlled_answer"))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
            });
            ReflectionTestUtils.setField(context.getBean(LlmSqlGenerator.class), "chatModel", model);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var cases = List.of(
                    new Case("original-explicit", "复购率是多少？（下单 2 次及以上的用户占全部下单用户的比例）", false),
                    new Case("alias-explicit", "回购率以全部下单用户为分母", false),
                    new Case("bare", "复购率是多少", true),
                    new Case("numerator-only", "复购率只看下单两次及以上的用户", true),
                    new Case("filter-only", "复购率只统计有效订单", true),
                    new Case("choice", "复购率分母是全部下单用户还是注册满30天的用户？", true),
                    new Case("negative", "复购率不要以全部下单用户为分母", true),
                    new Case("legacy-sales", "销售额是多少", true),
                    new Case("regional-bare", "按大区统计商品销售额", true),
                    new Case("regional-explicit", "按客户所在大区统计商品销售额", false),
                    new Case("regional-choice", "大区订单量按客户州还是卖家州", true),
                    new Case("region-dictionary", "每个大区包含多少个州", false),
                    new Case("price-bare", "价格平均涨跌幅是多少", true),
                    new Case("price-unit-only", "价格平均涨幅用百分比输出", true),
                    new Case("price-explicit", "价格平均涨幅用百分比输出，每条变动记录等权", false));
            for (var item : cases) {
                int before = calls.get(); String session = "clarification-" + item.id();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ask"))
                        .timeout(Duration.ofSeconds(8)).header("Authorization", auth).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("question", item.question(), "sessionId", session)))).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString()); var body = JSON.readTree(response.body());
                assertThat(response.statusCode()).isEqualTo(item.needsClarification() ? 400 : 200);
                assertThat(body.path("status").asText()).isEqualTo(item.needsClarification() ? "NEEDS_CLARIFICATION" : "SUCCESS");
                int expectedCalls = item.needsClarification() ? 0 : 1;
                assertThat(calls.get() - before).isEqualTo(expectedCalls);
                assertThat(body.path("budget").path("modelCalls").asInt()).isEqualTo(expectedCalls);
                assertThat(body.path("budget").path("unknownUsageCalls").asInt()).isZero();
                assertThat(context.getBean(SessionStore.class).get(session).orElseThrow().turns()).hasSize(1);
                evidence.add(Map.of("id", item.id(), "question", item.question(), "expectedClarification", item.needsClarification(),
                        "httpStatus", response.statusCode(), "body", body,
                        "decision", context.getBean(AmbiguityDetector.class).decisionsFor(item.question())));
            }
            assertThat(calls).hasValue((int) cases.stream().filter(c -> !c.needsClarification()).count());
        }
        write(dir.resolve("requests.json"), evidence);
        write(dir.resolve("summary.json"), Map.of("passed", true, "cases", evidence.size(), "syntheticModelCalls", calls.get(),
                "externalProviderCalls", 0, "providerCostYuan", 0, "port", port, "pid", ProcessHandle.current().pid(),
                "contextClosed", true, "scope", "Real protected HTTP/clarification/session/PostgreSQL; synthetic SELECT 1 only proves admission, not SQL generation accuracy."));
        System.out.println("CLARIFICATION_HTTP passed=" + evidence.size() + " syntheticCalls=" + calls.get() + " providerCalls=0 port=" + port);
    }
    private static void write(Path path, Object value) throws Exception { Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW); }
}
