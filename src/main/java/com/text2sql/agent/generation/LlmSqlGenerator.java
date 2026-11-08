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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final com.text2sql.agent.observability.LlmCallLog callLog;

    /** 未配置 Key 时为 null。这是刻意的：把「配置缺失」变成一个可检查的状态，而不是异常。 */
    private final ChatModel chatModel;
    /**
     * BYOK 动态客户端池：按用户自带的 API Key 缓存 ChatModel。
     *
     * <p><b>为什么必须有容量上限</b>：这个 Map 的键来自请求头
     * {@code X-Api-Key}，是**调用方可完全控制**的值。早先这里用的是无界
     * {@code ConcurrentHashMap}，每次换一个不同的 Key 就会新建一个客户端并
     * 永久留在内存里——持续发不同 Key 就能把堆撑爆，而这条路径不需要任何
     * 鉴权。属于典型的「输入决定内存占用」型缺陷。
     *
     * <p>改为 LRU + 上限后，超限时淘汰最久未使用的客户端。容量取 32 是够用的：
     * 正常场景里同时活跃的 BYOK Key 只有个位数，32 已经留了充足余量。
     */
    private static final int MAX_DYNAMIC_MODELS = 32;

    private final Map<String, ChatModel> dynamicChatModels = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ChatModel> eldest) {
                    return size() > MAX_DYNAMIC_MODELS;
                }
            });

    public LlmSqlGenerator(AgentProperties properties, PromptTemplate promptTemplate,
                           com.text2sql.agent.observability.LlmCallLog callLog) {
        this.properties = properties;
        this.promptTemplate = promptTemplate;
        this.callLog = callLog;
        this.chatModel = buildChatModel(properties, properties.getLlm().getApiKey());
        if (this.chatModel == null) {
            log.warn("未配置全局 agent.llm.api-key，进入 BYOK（用户自带密钥）模式；用户可在前端输入个人 API Key 体验。");
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
        return chatModel != null || !dynamicChatModels.isEmpty();
    }

    public boolean isConfigured(String apiKeyOverride) {
        return resolveChatModel(apiKeyOverride) != null;
    }

    public ChatModel resolveChatModel(String apiKeyOverride) {
        if (!StringUtils.hasText(apiKeyOverride)) {
            return this.chatModel;
        }
        String key = apiKeyOverride.strip();
        ChatModel cached = dynamicChatModels.get(key);
        if (cached != null) {
            return cached;
        }
        // 构建客户端有开销，放在锁外做；只有写入时才加锁，并在锁内二次检查，
        // 避免同一个 Key 在并发首用时被重复构建。重复构建本身不致命，
        // 但会浪费连接与对象，而且后写的会覆盖先写的。
        ChatModel built = buildChatModel(properties, key);
        if (built == null) {
            return null;
        }
        synchronized (dynamicChatModels) {
            ChatModel existing = dynamicChatModels.get(key);
            if (existing != null) {
                return existing;
            }
            dynamicChatModels.put(key, built);
            return built;
        }
    }

    /**
     * 生成 SQL。
     *
     * @param question 用户的中文问题
     * @param schema   检索层给出的 schema 上下文
     */
    public GeneratedSql generate(String question, SchemaContext schema) {
        return generate(question, schema, null, null);
    }

    /**
     * 生成 SQL。
     *
     * @param modelOverride 非空时用这个模型替代配置里的默认模型（阶段 6 的升级路由）
     */
    public GeneratedSql generate(String question, SchemaContext schema, String modelOverride) {
        return generate(question, schema, modelOverride, null);
    }

    /**
     * 生成 SQL（支持动态 API Key 覆盖）。
     */
    public GeneratedSql generate(String question, SchemaContext schema, String modelOverride, String apiKeyOverride) {
        ChatModel targetModel = resolveChatModel(apiKeyOverride);
        if (targetModel == null) {
            throw new GenerationException(GenerationException.Reason.NOT_CONFIGURED,
                    "未配置 API Key，无法生成 SQL。请在页面右上角【⚙️配置 API Key】中输入你的 DeepSeek 密钥。");
        }

        String system = promptTemplate.systemPrompt();
        String user = promptTemplate.userPrompt(schema, question);
        int promptChars = system.length() + user.length();

        List<Message> messages = List.of(new SystemMessage(system), new UserMessage(user));
        String model = (modelOverride == null || modelOverride.isBlank())
                ? properties.getLlm().getModel() : modelOverride;
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(properties.getLlm().getTemperature())
                .maxTokens(properties.getLlm().getMaxTokens())
                .build();

        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = targetModel.call(new Prompt(messages, options));
        } catch (Exception e) {
            throw new GenerationException(GenerationException.Reason.CALL_FAILED,
                    "LLM 调用失败：" + e.getMessage(), e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;

        //获取模型的output
        String raw = extractText(response);
        LlmCallRecord record = buildRecord(response, promptChars, latencyMs, model);
        log.info("LLM 调用完成：{}", record.summary());
        callLog.record(record, "OK", user, raw);

        //将模型输出的sql进行提取，因为模型可能会生产sql之外的内容
        String sql = SqlExtractor.extract(raw);
        return new GeneratedSql(sql, raw, record);
    }

    /**
     * 阶段 5 自纠错：把上一条 SQL 和结构化反馈一起交给模型，只生成一条修正版 SQL。
     */
    public GeneratedSql generateCorrection(String question, SchemaContext schema,
                                            String previousSql, String feedback) {
        return generateCorrection(question, schema, previousSql, feedback, null, null);
    }

    /**
     * 自纠错 / 升级重试。
     *
     * @param modelOverride 非空时换用这个模型——阶段 6 的「失败后升级到强模型」就走这里。
     *                      它和自纠错共用同一次重试机会：既带上错误信息，也可以换模型。
     */
    public GeneratedSql generateCorrection(String question, SchemaContext schema,
                                            String previousSql, String feedback,
                                            String modelOverride) {
        return generateCorrection(question, schema, previousSql, feedback, modelOverride, null);
    }

    /**
     * 自纠错 / 升级重试（支持动态 API Key 覆盖）。
     */
    public GeneratedSql generateCorrection(String question, SchemaContext schema,
                                            String previousSql, String feedback,
                                            String modelOverride, String apiKeyOverride) {
        ChatModel targetModel = resolveChatModel(apiKeyOverride);
        if (targetModel == null) {
            throw new GenerationException(GenerationException.Reason.NOT_CONFIGURED,
                    "未配置 API Key，无法执行自纠错。");
        }

        String system = promptTemplate.systemPrompt();
        String user = promptTemplate.correctionPrompt(schema, question, previousSql, feedback);
        int promptChars = system.length() + user.length();
        List<Message> messages = List.of(new SystemMessage(system), new UserMessage(user));
        String model = (modelOverride == null || modelOverride.isBlank())
                ? properties.getLlm().getModel() : modelOverride;
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(properties.getLlm().getTemperature())
                .maxTokens(properties.getLlm().getMaxTokens())
                .build();

        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = targetModel.call(new Prompt(messages, options));
        } catch (Exception e) {
            throw new GenerationException(GenerationException.Reason.CALL_FAILED,
                    "LLM 自纠错调用失败：" + e.getMessage(), e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String raw = extractText(response);
        LlmCallRecord record = buildRecord(response, promptChars, latencyMs, model);
        log.info("LLM 自纠错完成：{}", record.summary());
        callLog.record(record, "CORRECTION", user, raw);
        return new GeneratedSql(SqlExtractor.extract(raw), raw, record);
    }

    /**
     * 支持 Agent 多轮对话交互的通用 LLM 调用接口。
     */
    public GeneratedSql callMessages(List<Message> messages, String logTag, String modelOverride) {
        return callMessages(messages, logTag, modelOverride, null);
    }

    /**
     * 支持 Agent 多轮对话交互的通用 LLM 调用接口（支持动态 API Key 覆盖）。
     */
    public GeneratedSql callMessages(List<Message> messages, String logTag, String modelOverride, String apiKeyOverride) {
        ChatModel targetModel = resolveChatModel(apiKeyOverride);
        if (targetModel == null) {
            throw new GenerationException(GenerationException.Reason.NOT_CONFIGURED,
                    "未配置 API Key，无法调用 LLM。");
        }

        int promptChars = messages.stream().mapToInt(m -> m.getText() == null ? 0 : m.getText().length()).sum();
        String model = (modelOverride == null || modelOverride.isBlank())
                ? properties.getLlm().getModel() : modelOverride;
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(properties.getLlm().getTemperature())
                .maxTokens(properties.getLlm().getMaxTokens())
                .build();

        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = targetModel.call(new Prompt(messages, options));
        } catch (Exception e) {
            throw new GenerationException(GenerationException.Reason.CALL_FAILED,
                    "LLM 调用失败：" + e.getMessage(), e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String raw = extractText(response);
        LlmCallRecord record = buildRecord(response, promptChars, latencyMs, model);
        log.info("LLM [{}] 调用完成：{}", logTag, record.summary());
        callLog.record(record, logTag, "messages[" + messages.size() + "]", raw);
        return new GeneratedSql(SqlExtractor.extract(raw), raw, record);
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            throw new GenerationException(GenerationException.Reason.CALL_FAILED, "LLM 返回空响应");
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output == null) {
            return null;
        }
        String text = output.getText();
        if ((text == null || text.isBlank()) && output.getMetadata() != null) {
            Object reasoning = output.getMetadata().get("reasoning_content");
            if (reasoning instanceof String rs && !rs.isBlank()) {
                log.info("LLM 主内容为空，尝试从 reasoning_content 提取 SQL");
                return rs;
            }
        }
        return text;
    }

    private LlmCallRecord buildRecord(ChatResponse response, int promptChars, long latencyMs, String model) {
        Integer in = null;
        Integer out = null;
        Integer cacheHit = null;
        Integer cacheMiss = null;
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata != null) {
            Usage usage = metadata.getUsage();
            if (usage != null) {
                in = usage.getPromptTokens();
                out = usage.getCompletionTokens();
                // DeepSeek 在 prompt_tokens_details.cached_tokens 里给出缓存命中数；
                // Spring AI 的 OpenAiApi.Usage 会把它映射到 PromptTokensDetails。
                if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage
                        && nativeUsage.promptTokensDetails() != null) {
                    cacheHit = nativeUsage.promptTokensDetails().cachedTokens();
                    if (cacheHit != null && in != null) {
                        cacheMiss = Math.max(0, in - cacheHit);
                    }
                }
            }
        }
        return LlmCallRecord.of(model, in, out, cacheHit, cacheMiss, promptChars, latencyMs,
                properties.getLlm().getInputPricePer1k(),
                properties.getLlm().getOutputPricePer1k(),
                properties.getLlm().getCacheHitPricePer1k());
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
    private static ChatModel buildChatModel(AgentProperties properties, String apiKey) {
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
