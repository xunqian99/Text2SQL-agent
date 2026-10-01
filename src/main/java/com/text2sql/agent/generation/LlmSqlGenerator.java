package com.text2sql.agent.generation;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.SchemaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 生成层：调用 LLM，把「问题 + schema」变成 SQL。
 *
 * <p>这个类是整条链路里唯一需要联网、唯一花钱、唯一有不确定性的环节。
 * 所以它做了三件别的类不需要做的事：记录成本、把失败分类、允许未配置时优雅降级。
 *
 * <p><b>为什么不用 Spring AI 的自动配置</b>：{@code spring-ai-starter-model-openai}
 * 的自动配置默认 base-url 指向 api.openai.com，且没有 Key 时会在启动阶段抛异常，
 * 导致「只想跑评估器」或「只想验证校验器」的人被一个网络依赖卡死。
 * 这里改为在构造时判断：有 Key 才建客户端，没有就置空，调用时抛
 * {@code NOT_CONFIGURED}。这样数据库链路、校验链路、评估链路都能离线验证——
 * 这恰恰是「评估器自检」能成立的前提。
 *
 * <p><b>为什么温度设 0</b>：Text2SQL 是确定性任务，不是创作任务。
 * 温度 0 让同样的输入尽量产生同样的输出，评估数字才可复现。代价是
 * 遇到模型「认死理」写错时不会换个写法，这个代价由阶段 5 的自纠错承担。
 */
@Component
public class LlmSqlGenerator {

    private static final Logger log = LoggerFactory.getLogger(LlmSqlGenerator.class);

    private final AgentProperties properties;
    private final PromptTemplate promptTemplate;

    /** 未配置 Key 时为 null。这是刻意的：把「配置缺失」变成一个可检查的状态，而不是异常。 */
    private final ChatModel chatModel;

    public LlmSqlGenerator(AgentProperties properties, PromptTemplate promptTemplate) {
        this.properties = properties;
        this.promptTemplate = promptTemplate;
        this.chatModel = buildChatModel(properties);
        if (this.chatModel == null) {
            log.warn("未配置 agent.llm.api-key，SQL 生成不可用；校验/执行/评估链路仍可正常运行。");
        } else {
            // 把完整端点打出来，而不是只打 baseUrl。厂商之间差别最大的就是路径
            // （通义 /v1/chat/completions、千帆 /v2/chat/completions），
            // 只打 baseUrl 时「路径配错了」在日志里看不出来，要等到 404 才发现。
            log.info("LLM 已就绪：provider={} model={} endpoint={}{}",
                    properties.getLlm().getProvider(),
                    properties.getLlm().getModel(),
                    properties.getLlm().getBaseUrl(),
                    properties.getLlm().getCompletionsPath());
        }
    }

    public boolean isConfigured() {
        return chatModel != null;
    }

    /**
     * 生成 SQL。
     *
     * @param question 用户的中文问题
     * @param schema   检索层给出的 schema 上下文
     */
    public GeneratedSql generate(String question, SchemaContext schema) {
        if (chatModel == null) {
            throw new GenerationException(GenerationException.Reason.NOT_CONFIGURED,
                    "未配置 agent.llm.api-key，无法生成 SQL。请在 application.yml 或环境变量 AGENT_LLM_API_KEY 中配置。");
        }

        String system = promptTemplate.systemPrompt();
        String user = promptTemplate.userPrompt(schema, question);
        int promptChars = system.length() + user.length();

        List<Message> messages = List.of(new SystemMessage(system), new UserMessage(user));
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.getLlm().getModel())
                .temperature(properties.getLlm().getTemperature())
                .maxTokens(properties.getLlm().getMaxTokens())
                .build();

        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(messages, options));
        } catch (Exception e) {
            throw new GenerationException(GenerationException.Reason.CALL_FAILED,
                    "LLM 调用失败：" + e.getMessage(), e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;

        //获取模型的output
        String raw = extractText(response);
        LlmCallRecord record = buildRecord(response, promptChars, latencyMs);
        log.info("LLM 调用完成：{}", record.summary());

        //将模型输出的sql进行提取，因为模型可能会生产sql之外的内容
        String sql = SqlExtractor.extract(raw);
        return new GeneratedSql(sql, raw, record);
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            throw new GenerationException(GenerationException.Reason.CALL_FAILED, "LLM 返回空响应");
        }
        AssistantMessage output = response.getResult().getOutput();
        return output == null ? null : output.getText();
    }

    private LlmCallRecord buildRecord(ChatResponse response, int promptChars, long latencyMs) {
        Integer in = null;
        Integer out = null;
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata != null) {
            Usage usage = metadata.getUsage();
            if (usage != null) {
                in = usage.getPromptTokens();
                out = usage.getCompletionTokens();
            }
        }
        return LlmCallRecord.of(properties.getLlm().getModel(), in, out, promptChars, latencyMs,
                properties.getLlm().getInputPricePer1k(), properties.getLlm().getOutputPricePer1k());
    }

    /**
     * 手工装配 OpenAI 兼容客户端。
     *
     * <p>关键点：{@code baseUrl} 可配置，所以同一份代码既能连 OpenAI，
     * 也能连 DeepSeek（https://api.deepseek.com）、通义兼容模式、本地 vLLM。
     * 这是「OpenAI 兼容协议」这个事实带来的杠杆——换供应商不用改代码。
     *
     * <p>超时单独设置：默认的 RestClient 没有读超时，一次模型卡住会让
     * 整个评估运行器挂在那里，而评估要跑 200 次。给一个明确的 60 秒上限，
     * 失败就记一次失败，继续下一条。
     */
    private static ChatModel buildChatModel(AgentProperties properties) {
        String apiKey = properties.getLlm().getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(properties.getLlm().getTimeoutSeconds()));

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.getLlm().getBaseUrl())
                .completionsPath(properties.getLlm().getCompletionsPath())
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.getLlm().getModel())
                        .temperature(properties.getLlm().getTemperature())
                        .build())
                .build();
    }
}
