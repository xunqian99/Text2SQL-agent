package com.text2sql.agent.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 实际经过 Spring AI 序列化和 HTTP 传输，不能用捕获 builder 对象替代。 */
class LlmGenerationModeTest {
    @TempDir Path logsDir;
    private final ObjectMapper json = new ObjectMapper();

    static Stream<Arguments> wireCases() {
        return Stream.of("provider-default", "none").flatMap(mode ->
                Stream.of(false, true).flatMap(byok -> Stream.of("generate", "correction", "messages", "tools")
                        .map(path -> Arguments.of(mode, byok, path))));
    }

    @ParameterizedTest(name = "{0}, BYOK={1}, path={2}")
    @MethodSource("wireCases")
    void modeReachesWireAndAuditOnAllEntrypoints(String mode, boolean byok, String path) throws Exception {
        capture(mode, byok, path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "high", "max"})
    void explicitThinkingEffortsAreForwarded(String mode) throws Exception {
        capture(mode, false, "generate");
    }

    static Stream<Arguments> promptCases() {
        return Stream.of(false, true).flatMap(explicit -> Stream.of(false, true).flatMap(byok ->
                Stream.of("generate", "correction", "messages", "tools")
                        .map(path -> Arguments.of(explicit, byok, path))));
    }

    @ParameterizedTest(name = "explicit={0}, BYOK={1}, path={2}")
    @MethodSource("promptCases")
    void selectedPromptReachesWireWithoutChangingProviderMode(boolean explicit, boolean byok, String path) throws Exception {
        capture("provider-default", byok, path, explicit);
    }

    private void capture(String mode, boolean byok, String path) throws Exception {
        capture(mode, byok, path, true);
    }

    private void capture(String mode, boolean byok, String path, boolean explicit) throws Exception {
        var bodies = new LinkedBlockingQueue<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            // 不读取或记录请求头；本地测试也使用假 Key。
            bodies.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {"id":"fixture","object":"chat.completion","created":1,"model":"fixture-override",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"SELECT 1"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        var props = new AgentProperties();
        props.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.getLlm().setApiKey(byok ? "" : "fixture-global-key");
        props.getLlm().setMaxTokens(8192);
        props.getLlm().setReasoningEffort(mode);
        props.getPrompt().setExplicitReasoningEnabled(explicit);
        props.getFewshot().setRequireAvailableTables(byok);
        props.getObservability().setDir(logsDir.toString());
        props.getObservability().setIncludeContent(false);
        var template = new PromptTemplate(props);
        try (var log = new LlmCallLog(props)) {
            var generator = new LlmSqlGenerator(props, template, log, com.text2sql.agent.retrieval.CatalogFixture.healthy());
            String overrideKey = byok ? "fixture-byok-key" : null;
            String overrideModel = "fixture-override";
            var schema = new SchemaContext(List.of(), List.of(), "", "fixture schema");
            var messages = List.<org.springframework.ai.chat.messages.Message>of(
                    new SystemMessage(template.systemPrompt()), new UserMessage("count orders"));
            switch (path) {
                case "generate" -> assertThat(generator.generate("count orders", schema, overrideModel, overrideKey).sql()).isEqualTo("SELECT 1");
                case "correction" -> generator.generateCorrection("count orders", schema, "SELECT 0", "retry", overrideModel, overrideKey);
                case "messages" -> generator.callMessages(messages, "HISTORY", overrideModel, overrideKey);
                case "tools" -> generator.callWithTools(messages, List.of(), "TOOLS", overrideModel, overrideKey);
                default -> throw new AssertionError(path);
            }
        } finally { server.stop(0); }
        JsonNode body = bodies.poll(1, TimeUnit.SECONDS);
        assertThat(body).isNotNull();
        assertThat(bodies).isEmpty();
        assertThat(body.path("model").asText()).isEqualTo("fixture-override");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(body.path("temperature").asDouble()).isZero();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo(template.systemPrompt());
        assertThat(template.systemPrompt().contains("思考与推理步骤")).isEqualTo(explicit);
        assertThat(template.systemPrompt()).contains("硬性规则：", "18. 复杂多表关联时");
        if ("provider-default".equals(mode)) assertThat(body.has("reasoning_effort")).isFalse();
        else assertThat(body.path("reasoning_effort").asText()).isEqualTo(mode);
        try (var files = Files.list(logsDir)) {
            var lines = Files.readAllLines(files.findFirst().orElseThrow());
            assertThat(lines).hasSize(1);
            var entry = json.readTree(lines.getFirst());
            assertThat(entry.path("configuredReasoningEffort").asText()).isEqualTo(mode);
            assertThat(entry.path("configuredPromptVersion").asText()).isEqualTo(template.version());
            assertThat(entry.path("configuredSemanticEnabled").asBoolean()).isEqualTo(props.getSemantic().isEnabled());
            assertThat(entry.path("configuredMetricDefinitionsSha256").asText())
                    .isEqualTo(com.text2sql.agent.semantic.MetricRegistry.definitionSha256());
            assertThat(entry.path("configuredFewshotRequireAvailableTables").asBoolean()).isEqualTo(byok);
            assertThat(entry.path("configuredFewshotSelectionPolicy").asText())
                    .isEqualTo(com.text2sql.agent.fewshot.ExampleSelector.policyVersion(byok));
            assertThat(entry.path("promptSha256").asText()).hasSize(64);
            assertThat(entry.path("totalTokens").asInt()).isEqualTo(14);
            assertThat(entry.path("prompt").isNull()).isTrue();
            assertThat(entry.path("response").isNull()).isTrue();
            assertThat(lines.getFirst()).doesNotContain("fixture-global-key", "fixture-byok-key");
        }
    }

    @Test void absentAndBlankMeanOmissionAndCaseIsNormalized() {
        var llm = new AgentProperties.Llm();
        assertThat(llm.getReasoningEffort()).isEqualTo("provider-default");
        llm.setReasoningEffort(" NONE ");
        assertThat(llm.getReasoningEffort()).isEqualTo("none");
        llm.setReasoningEffort(null);
        assertThat(llm.getReasoningEffort()).isEqualTo("provider-default");
        llm.setReasoningEffort(" ");
        assertThat(llm.getReasoningEffort()).isEqualTo("provider-default");
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "false", "typo"})
    void invalidConfigurationFailsBeforeNetwork(String invalid) {
        var llm = new AgentProperties.Llm();
        assertThatThrownBy(() -> llm.setReasoningEffort(invalid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reasoning-effort");
        assertThat(llm.getReasoningEffort()).isEqualTo("provider-default");
    }
}
