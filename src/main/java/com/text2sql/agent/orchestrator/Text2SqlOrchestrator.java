package com.text2sql.agent.orchestrator;

import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutionException;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.GenerationException;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import com.text2sql.agent.session.ConversationSession;
import com.text2sql.agent.session.ConversationTurn;
import com.text2sql.agent.session.SessionStore;
import com.text2sql.agent.session.QuestionRewriter;

/**
 * 编排层：把「检索 → 生成 → 校验 → 执行」四步串成一条链路。
 *
 * <p>对应 ROADMAP 第 4 节阶段 1，也对应 3.1 完整链路图里的 [3][6][7][8] 四步。
 * 上游是接入层传来的自然语言问题，下游把 {@link AgentResponse} 交给接入层，
 * 或者交给评估运行器。
 *
 * <p><b>为什么需要一个独立的编排类，而不是把这些逻辑写进 Controller</b>
 *
 * <p>因为这个项目有两个入口：HTTP 接口和离线评估运行器。它们需要的是
 * **完全相同**的链路——如果编排逻辑写在 Controller 里，评估运行器要么
 * 走 HTTP 绕一圈（慢、还要起服务），要么把链路复制一遍（两份实现必然分叉）。
 * 抽成 Service 之后，两个入口共用同一条链路，评估测出来的就是线上真实行为。
 * 这是「评估可信」的结构性前提，比任何测试都重要。
 *
 * <p><b>为什么阶段 1 不做重试</b>
 *
 * <p>重试是阶段 5 的内容，而且它必须建立在「失败已被正确分类」之上：
 * 只有先能区分「表选错」「语法错」「超时」，重试才有明确的修复方向。
 * 阶段 1 先把每一类失败如实记录下来，用 baseline 数字量化它们各占多少。
 * 如果现在就加一个「失败就重试」的循环，baseline 会变得无法解释——
 * 分不清准确率的提升来自模型变强还是来自多试了几次。
 */
@Service
public class Text2SqlOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlOrchestrator.class);

    private final SchemaProvider schemaProvider;
    private final LlmSqlGenerator generator;
    private final SqlValidator validator;
    private final SqlExecutor executor;
    private final com.text2sql.agent.clarification.AmbiguityDetector ambiguityDetector;
    private final com.text2sql.agent.cache.SemanticCache cache;
    private final com.text2sql.agent.validation.ResultChecker resultChecker;
    private final com.text2sql.agent.tool.AgentLoop agentLoop;
    private final com.text2sql.agent.session.SessionStore sessionStore;
    private final com.text2sql.agent.session.QuestionRewriter questionRewriter;
    private final com.text2sql.agent.execution.QueryPlanAnalyzer queryPlanAnalyzer;
    private final com.text2sql.agent.config.AgentProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public Text2SqlOrchestrator(SchemaProvider schemaProvider, LlmSqlGenerator generator,
                                SqlValidator validator, SqlExecutor executor,
                                com.text2sql.agent.clarification.AmbiguityDetector ambiguityDetector,
                                com.text2sql.agent.cache.SemanticCache cache,
                                com.text2sql.agent.validation.ResultChecker resultChecker,
                                com.text2sql.agent.tool.AgentLoop agentLoop,
                                com.text2sql.agent.session.SessionStore sessionStore,
                                com.text2sql.agent.session.QuestionRewriter questionRewriter,
                                com.text2sql.agent.execution.QueryPlanAnalyzer queryPlanAnalyzer,
                                com.text2sql.agent.config.AgentProperties properties) {
        this.schemaProvider = schemaProvider;
        this.generator = generator;
        this.validator = validator;
        this.executor = executor;
        this.ambiguityDetector = ambiguityDetector;
        this.cache = cache;
        this.resultChecker = resultChecker;
        this.agentLoop = agentLoop;
        this.sessionStore = sessionStore;
        this.questionRewriter = questionRewriter;
        this.queryPlanAnalyzer = queryPlanAnalyzer;
        this.properties = properties;
    }

    public Text2SqlOrchestrator(SchemaProvider schemaProvider, LlmSqlGenerator generator,
                                SqlValidator validator, SqlExecutor executor,
                                com.text2sql.agent.clarification.AmbiguityDetector ambiguityDetector,
                                com.text2sql.agent.cache.SemanticCache cache,
                                com.text2sql.agent.validation.ResultChecker resultChecker,
                                com.text2sql.agent.tool.AgentLoop agentLoop,
                                com.text2sql.agent.session.SessionStore sessionStore,
                                com.text2sql.agent.session.QuestionRewriter questionRewriter,
                                com.text2sql.agent.config.AgentProperties properties) {
        this(schemaProvider, generator, validator, executor, ambiguityDetector, cache,
                resultChecker, agentLoop, sessionStore, questionRewriter, null, properties);
    }

    public Text2SqlOrchestrator(SchemaProvider schemaProvider, LlmSqlGenerator generator,
                                SqlValidator validator, SqlExecutor executor,
                                com.text2sql.agent.clarification.AmbiguityDetector ambiguityDetector,
                                com.text2sql.agent.cache.SemanticCache cache,
                                com.text2sql.agent.validation.ResultChecker resultChecker,
                                com.text2sql.agent.tool.AgentLoop agentLoop,
                                com.text2sql.agent.config.AgentProperties properties) {
        this(schemaProvider, generator, validator, executor, ambiguityDetector, cache,
                resultChecker, agentLoop, new com.text2sql.agent.session.SessionStore(properties),
                new com.text2sql.agent.session.QuestionRewriter(generator, properties), null, properties);
    }

    /**
     * 跑完整链路。
     *
     * <p>注意这个方法**不抛异常**。所有失败都被翻译成带 {@code status} 的
     * {@link AgentResponse}。原因是评估运行器要连续跑 200 条，任何一条抛异常
     * 都会中断整轮评估；而且「失败类型」本身就是要统计的数据，用异常
     * 传递会迫使调用方写一串 catch，把分类逻辑散到各处。
     */
    /**
     * 跑完整链路（单轮无会话模式）。
     */
    public AgentResponse ask(String question) {
        return ask(question, null);
    }

    /**
     * 跑完整链路（支持多轮会话记忆、指代消解与 Schema 复用）。
     *
     * <p>注意这个方法**不抛异常**。所有失败都被翻译成带 {@code status} 的
     * {@link AgentResponse}。
     *
     * @param question  用户问题原文
     * @param sessionId 会话 ID，为 null 时以单轮模式运行
     */
    public AgentResponse ask(String question, String sessionId) {
        long totalStarted = System.nanoTime();

        boolean convEnabled = properties.getConversation().isEnabled()
                && sessionId != null && !sessionId.isBlank();
        ConversationSession session = convEnabled ? sessionStore.getOrCreate(sessionId) : null;

        String effectiveQuestion = question;
        String rewrittenQuestion = null;
        boolean schemaReused = false;
        SchemaContext schema = null;
        long retrievalMs = 0;

        if (convEnabled && questionRewriter.isFollowUp(question, session)) {
            rewrittenQuestion = questionRewriter.rewrite(question, session);
            effectiveQuestion = rewrittenQuestion;
            if (questionRewriter.canReuseSchema(question, session) && session.lastSchema() != null) {
                schema = session.lastSchema();
                schemaReused = true;
                log.info("多轮会话复用上一轮 SchemaContext，涉及表: {}", schema.tableNames());
            }
        }

        // 阶段 5：口径没定就先问，不要猜。
        // 放在最前面，是因为它要在花掉一次模型调用之前生效。
        if (properties.getClarification().isEnabled()) {
            var prompt = ambiguityDetector.clarificationFor(effectiveQuestion);
            if (prompt.isPresent()) {
                log.info("问题存在口径歧义，主动反问：{}", prompt.get());
                AgentResponse clar = AgentResponse.clarification(question, prompt.get());
                if (convEnabled) {
                    recordConversationTurn(session, question, rewrittenQuestion, clar);
                    return clar.withConversation(sessionId, rewrittenQuestion, schemaReused);
                }
                return clar;
            }
        }

        if (schema == null) {
            long retrievalStarted = System.nanoTime();
            schema = schemaProvider.provide(effectiveQuestion);
            retrievalMs = elapsedMs(retrievalStarted);
        }

        int tableCount = schema.tables().size();
        List<String> retrievedTables = schema.tableNames();
        int ddlChars = schema.ddlText() == null ? 0 : schema.ddlText().length();

        // 阶段 6：语义缓存。命中就跳过模型调用，直接拿旧 SQL 走校验和执行。
        String cacheKey = cache.enabled() ? cache.key(effectiveQuestion, cacheSignature()) : null;
        if (cacheKey != null) {
            var cachedSql = cache.get(cacheKey);
            if (cachedSql.isPresent()) {
                log.info("语义缓存命中，跳过模型调用");
                AgentResponse hit = validateAndExecute(question, cachedSql.get(), null, schema,
                        tableCount, retrievedTables, ddlChars, retrievalMs, 0, totalStarted);
                if (hit.success()) {
                    if (convEnabled) {
                        recordConversationTurn(session, question, rewrittenQuestion, hit);
                        return hit.withConversation(sessionId, rewrittenQuestion, schemaReused);
                    }
                    return hit;
                }
                log.warn("缓存中的 SQL 这次没跑通（{}），回退到重新生成", hit.status());
            }
        }

        long generationStarted = System.nanoTime();
        GeneratedSql generated;
        try {
            boolean useAgentMode = properties.getToolUse().isEnabled()
                    && tableCount >= properties.getToolUse().getTableThreshold();

            if (useAgentMode) {
                log.info("触发 Agent 侦察工具循环模式（涉及 {} 张表 >= 阈值 {}）",
                        tableCount, properties.getToolUse().getTableThreshold());
                generated = agentLoop.run(effectiveQuestion, schema, properties.getToolUse().getMaxRounds(), null);
            } else {
                generated = generator.generate(effectiveQuestion, schema);
            }
        } catch (GenerationException e) {
            long generationMs = elapsedMs(generationStarted);
            AgentResponse.Status status = e.getReason() == GenerationException.Reason.NOT_CONFIGURED
                    ? AgentResponse.Status.NOT_CONFIGURED
                    : AgentResponse.Status.GENERATION_FAILED;
            log.warn("生成失败（{}）：{}", e.getReason(), e.getMessage());
            AgentResponse fail = AgentResponse.failed(question, status, null, e.getMessage(), null,
                    tableCount, retrievedTables, ddlChars,
                    timings(retrievalMs, generationMs, 0, 0, elapsedMs(totalStarted)));
            if (convEnabled) {
                recordConversationTurn(session, question, rewrittenQuestion, fail);
                return fail.withConversation(sessionId, rewrittenQuestion, schemaReused);
            }
            return fail;
        }
        long generationMs = elapsedMs(generationStarted);

        AgentResponse first = validateAndExecute(question, generated.sql(), generated.call(), schema,
                tableCount, retrievedTables, ddlChars, retrievalMs, generationMs, totalStarted);
        AgentResponse result = retryIfFailed(effectiveQuestion, first);
        if (cacheKey != null && result.success()) {
            cache.put(cacheKey, result.sql());
        }

        if (convEnabled) {
            if (result.success()) {
                session.setLastSchema(schema);
            }
            recordConversationTurn(session, question, rewrittenQuestion, result);
            return result.withConversation(sessionId, rewrittenQuestion, schemaReused);
        }

        return result;
    }

    public Optional<ConversationSession> closeSession(String sessionId) {
        return sessionStore.closeAndPersist(sessionId);
    }

    public List<SessionStore.SessionSummary> listSessions() {
        return sessionStore.listSessions();
    }

    public boolean deleteSession(String sessionId) {
        return sessionStore.deleteSession(sessionId);
    }

    public SessionStore getSessionStore() {
        return sessionStore;
    }

    private void recordConversationTurn(ConversationSession session, String rawQuestion,
                                        String rewrittenQuestion, AgentResponse response) {
        if (session == null) {
            return;
        }
        String summary = summarizeResponse(response);
        ConversationTurn turn = ConversationTurn.of(
                session.turns().size() + 1,
                rawQuestion,
                rewrittenQuestion != null ? rewrittenQuestion : rawQuestion,
                response.sql(),
                response.status().name(),
                summary
        );
        session.addTurn(turn, properties.getConversation().getMaxTurns());
    }

    private String summarizeResponse(AgentResponse response) {
        if (!response.success()) {
            return response.status().name() + (response.message() != null ? ": " + response.message() : "");
        }
        int count = response.rowCount();
        if (count == 0) {
            return "0 行数据";
        }
        List<String> cols = response.columns();
        String colsDesc = (cols == null || cols.isEmpty()) ? "" : String.join(", ", cols);
        String preview = "";
        if (response.rows() != null && !response.rows().isEmpty()) {
            preview = " 样例: " + response.rows().get(0).toString();
        }
        return "共 " + count + " 行数据 (列: " + colsDesc + ")" + preview;
    }

    /**
     * 配置指纹。
     *
     * <p>把「改了它，同一个问题就该得到不同 SQL」的开关都编进来：模型、prompt 版本、
     * 检索开关与 Top-K、口径注入开关、LIMIT 模式。
     *
     * <p>漏掉任何一个的后果都一样且难查：改了配置，旧答案继续被命中，
     * 表现成「配置改了但数字一点没动」。历史评估报告里因此一直记着 prompt 版本，
     * 这里用的是同一个理由。
     */
    private String cacheSignature() {
        return "%s|%s|r=%b:%d|s=%b|limit=%s|esc=%s".formatted(
                properties.getLlm().getModel(),
                com.text2sql.agent.generation.PromptTemplate.VERSION,
                properties.getRetrieval().isEnabled(),
                properties.getRetrieval().getTopK(),
                properties.getSemantic().isEnabled(),
                properties.getGuard().getLimitMode().name(),
                properties.getRouting().isEnabled() ? properties.getRouting().getEscalationModel() : "-");
    }

    /**
     * 线上自纠错：第一次没成功执行时，把真实错误回灌给模型再试一次。
     *
     * <p><b>只在「有错误信号」时重试</b>。这里判断的是 {@code !success()}，
     * 也就是校验拒绝 / 执行失败 / 模型返回空内容——这三类都是推理时确实存在的信息。
     * 而「执行成功但答案不对」在线上没有信号，所以这里**不重试**，
     * 那一档只能放在评估器里当上限实验（见 {@code Eval.selfCorrectionTrigger}）。
     *
     * <p><b>重试失败时保留首次结果</b>。这是刻意的：把一次糟糕的重试当成最终答案，
     * 会让系统的表现比不重试更差——实测里出现过「首次能跑通、重试反而执行失败」
     * 的情况。所以只有第二次**成功**才用它，否则原样返回第一次的结果。
     *
     * <p>代价必须可查：{@code combineAttempts} 会把两次调用的 token 和延迟相加，
     * 这样「每次调用花了多少」这个数字不会被重试悄悄稀释。
     */
    private AgentResponse retryIfFailed(String question, AgentResponse first) {
        var config = properties.getSelfCorrection();
        var routing = properties.getRouting();
        boolean escalate = routing.isEnabled() && !routing.getEscalationModel().isBlank();
        boolean retryAllowed = (config.isEnabled() && config.getMaxAttempts() > 1) || escalate;

        boolean shouldRetry = false;
        String feedback = null;

        if (retryAllowed) {
            if (!first.success()) {
                shouldRetry = true;
                feedback = retryFeedback(first);
            } else if (properties.getResultChecker().isEnabled()) {
                // 阶段 B：启发式结果校验
                QueryResult qr = new QueryResult(first.columns(), first.rows(), first.truncated(), 0L);
                var check = resultChecker.check(question, first.sql(), qr);
                if (check.suspicious()) {
                    shouldRetry = true;
                    log.info("启发式结果校验命中异常（规则：{}）：{}", check.rule(), check.reason());
                    feedback = "上一条 SQL 执行成功，但经系统启发式规则检测发现结果可疑：\n"
                            + check.reason() + "\n上一条 SQL：\n" + first.sql()
                            + "\n请仔细核对计算逻辑、关联条件与过滤条件，重新生成正确的 SQL。";
                }
            }
        }

        if (!shouldRetry) {
            return first;
        }

        // 阶段 6：路由开启时换强模型重试。两个开关的作用不同——
        // 自纠错是「同一个模型看错误信息再试」，路由是「换一个更强的模型」。
        // 同时打开时这次重试两者兼具。
        String model = escalate ? routing.getEscalationModel() : null;
        log.info("触发自纠错重试（首次状态：{}）{}", first.status(),
                escalate ? "，并升级到 " + model : "");
        AgentResponse second = askWithCorrection(question, first.sql(), feedback, model);
        if (second.success()) {
            log.info("重试成功，采用第二次结果");
            return AgentResponse.combineAttempts(first, second);
        }
        log.warn("重试仍未成功（{}），保留首次结果", second.status());
        return first;
    }

    /** 把首次失败的真实原因整理成反馈。**只包含模型自己能观察到的信息，不含标准答案。** */
    private static String retryFeedback(AgentResponse first) {
        StringBuilder sb = new StringBuilder();
        sb.append("上一条 SQL 执行结果是：").append(first.status()).append('\n');
        if (first.sql() != null && !first.sql().isBlank()) {
            sb.append("上一条 SQL：\n").append(first.sql()).append('\n');
        }
        if (first.message() != null && !first.message().isBlank()) {
            sb.append("具体错误：").append(first.message()).append('\n');
        }
        sb.append("请修正后重新给出 SQL：只使用上面 schema 里真实存在的表和列，"
                + "只保留问题要求的输出列，数值统一 ROUND 到两位小数，"
                + "按时间分组用 DATE_TRUNC，并保留 LIMIT。\n");
        return sb.toString();
    }

    /**
     * 跳过生成，直接跑「校验 → 执行」。
     *
     * <p>存在的唯一目的是评估器自检（dry-run）：拿评估集里的 gold_sql 当输入，
     * 跑一遍下游链路。如果连标准 SQL 都跑不到 100% 一致，说明问题出在
     * 规范化规则或比对逻辑上，此时任何准确率数字都不可信。
     *
     * <p>这是「把评估器自身的 bug 和模型不准分开」的具体手段。没有这一步，
     * 第一次跑出 60% 的时候你无法判断这个 60% 是不是假的。
     *
     * @param llmCall 自检时传 null；正常链路传入真实调用记录
     */
    public AgentResponse askWithFixedSql(String question, String sql, LlmCallRecord llmCall) {
        long totalStarted = System.nanoTime();
        long retrievalStarted = System.nanoTime();
        SchemaContext schema = schemaProvider.provide(question);
        long retrievalMs = elapsedMs(retrievalStarted);
        return validateAndExecute(question, sql, llmCall, schema, schema.tables().size(),
                schema.tableNames(), schema.ddlText() == null ? 0 : schema.ddlText().length(),
                retrievalMs, 0, totalStarted);
    }

    /**
     * 阶段 5 自纠错入口：保留第一次 SQL，由评估器或上层调用方提供反馈，
     * 再走一次「生成 → 校验 → 执行」完整链路。
     */
    public AgentResponse askWithCorrection(String question, String previousSql, String feedback) {
        return askWithCorrection(question, previousSql, feedback, null);
    }

    /**
     * @param modelOverride 非空时换用这个模型重试（阶段 6 的升级路由）
     */
    public AgentResponse askWithCorrection(String question, String previousSql, String feedback,
                                           String modelOverride) {
        long totalStarted = System.nanoTime();
        long retrievalStarted = System.nanoTime();
        SchemaContext schema = schemaProvider.provide(question);
        long retrievalMs = elapsedMs(retrievalStarted);
        int tableCount = schema.tables().size();
        List<String> retrievedTables = schema.tableNames();
        int ddlChars = schema.ddlText() == null ? 0 : schema.ddlText().length();

        long generationStarted = System.nanoTime();
        GeneratedSql generated;
        try {
            generated = generator.generateCorrection(question, schema, previousSql, feedback, modelOverride);
        } catch (GenerationException e) {
            long generationMs = elapsedMs(generationStarted);
            AgentResponse.Status status = e.getReason() == GenerationException.Reason.NOT_CONFIGURED
                    ? AgentResponse.Status.NOT_CONFIGURED
                    : AgentResponse.Status.GENERATION_FAILED;
            return AgentResponse.failed(question, status, null, e.getMessage(), null, tableCount,
                    retrievedTables, ddlChars,
                    timings(retrievalMs, generationMs, 0, 0, elapsedMs(totalStarted)));
        }
        long generationMs = elapsedMs(generationStarted);
        return validateAndExecute(question, generated.sql(), generated.call(), schema, tableCount,
                retrievedTables, ddlChars, retrievalMs, generationMs, totalStarted);
    }

    private AgentResponse validateAndExecute(String question, String sql, LlmCallRecord llmCall,
                                             SchemaContext schema, int tableCount,
                                             List<String> retrievedTables,
                                             int ddlChars,
                                             long retrievalMs, long generationMs, long totalStarted) {
        long validationStarted = System.nanoTime();
        ValidationResult validation = validator.validate(sql, schema);
        long validationMs = elapsedMs(validationStarted);

        if (!validation.valid()) {
            List<String> violations = validation.violations().stream()
                    .map(v -> "[" + v.code() + "] " + v.message())
                    .toList();
            log.info("SQL 被校验层拦下：{}", validation.describe());
            return AgentResponse.rejected(question, validation.sql(), violations, llmCall, tableCount,
                    retrievedTables, ddlChars,
                    timings(retrievalMs, generationMs, validationMs, 0, elapsedMs(totalStarted)));
        }

        // 阶段 5 闭环防御：EXPLAIN 执行代价与全表笛卡尔积预执行护栏
        if (properties.getGuard().isExplainCostGuardEnabled() && queryPlanAnalyzer != null) {
            var analysis = queryPlanAnalyzer.analyze(validation.sql());
            if (!analysis.safe()) {
                log.warn("SQL 触发高危执行计划护栏拦截：{}", analysis.riskDescription());
                List<String> violations = List.of("[HIGH_RISK_COST] " + analysis.riskDescription());
                return AgentResponse.rejected(question, validation.sql(), violations, llmCall, tableCount,
                        retrievedTables, ddlChars,
                        timings(retrievalMs, generationMs, validationMs, 0, elapsedMs(totalStarted)));
            }
        }

        long executionStarted = System.nanoTime();
        QueryResult result;
        try {
            // 用 validation.sql() 而不是原始 sql：校验层可能补了 LIMIT，
            // 执行的和记录的必须是同一条语句。
            result = executor.execute(validation.sql());
        } catch (SqlExecutionException e) {
            long executionMs = elapsedMs(executionStarted);
            log.warn("SQL 执行失败：{}", e.getMessage());
            // 必须带上 validation.sql()：执行失败的 SQL 正是排查失败原因的唯一线索，
            // 早先这里传 null，导致报告里 19 条执行失败看不到 SQL。
            return AgentResponse.failed(question, AgentResponse.Status.EXECUTION_FAILED, validation.sql(),
                    e.getMessage(), llmCall, tableCount,
                    retrievedTables, ddlChars,
                    timings(retrievalMs, generationMs, validationMs, executionMs, elapsedMs(totalStarted)));
        }
        long executionMs = elapsedMs(executionStarted);

        AgentResponse.Timings timings = timings(retrievalMs, generationMs, validationMs, executionMs,
                elapsedMs(totalStarted));

        return AgentResponse.success(question, validation.sql(), validation.rewritten(),
                new AgentResponse.QueryOutcome(result.columns(), result.rows(), result.truncated()),
                llmCall, tableCount, retrievedTables, ddlChars, timings);
    }

    private static AgentResponse.Timings timings(long retrieval, long generation, long validation,
                                                 long execution, long total) {
        return new AgentResponse.Timings(retrieval, generation, validation, execution, total);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
