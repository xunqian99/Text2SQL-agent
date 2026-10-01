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

    public Text2SqlOrchestrator(SchemaProvider schemaProvider, LlmSqlGenerator generator,
                                SqlValidator validator, SqlExecutor executor) {
        this.schemaProvider = schemaProvider;
        this.generator = generator;
        this.validator = validator;
        this.executor = executor;
    }

    /**
     * 跑完整链路。
     *
     * <p>注意这个方法**不抛异常**。所有失败都被翻译成带 {@code status} 的
     * {@link AgentResponse}。原因是评估运行器要连续跑 200 条，任何一条抛异常
     * 都会中断整轮评估；而且「失败类型」本身就是要统计的数据，用异常
     * 传递会迫使调用方写一串 catch，把分类逻辑散到各处。
     */
    public AgentResponse ask(String question) {
        long totalStarted = System.nanoTime();

        long retrievalStarted = System.nanoTime();
        SchemaContext schema = schemaProvider.provide(question);
        long retrievalMs = elapsedMs(retrievalStarted);
        int tableCount = schema.tables().size();

        long generationStarted = System.nanoTime();
        GeneratedSql generated;
        try {
            generated = generator.generate(question, schema);
        } catch (GenerationException e) {
            long generationMs = elapsedMs(generationStarted);
            AgentResponse.Status status = e.getReason() == GenerationException.Reason.NOT_CONFIGURED
                    ? AgentResponse.Status.NOT_CONFIGURED
                    : AgentResponse.Status.GENERATION_FAILED;
            log.warn("生成失败（{}）：{}", e.getReason(), e.getMessage());
            return AgentResponse.failed(question, status, e.getMessage(), null, tableCount,
                    timings(retrievalMs, generationMs, 0, 0, elapsedMs(totalStarted)));
        }
        long generationMs = elapsedMs(generationStarted);

        return validateAndExecute(question, generated.sql(), generated.call(), schema, tableCount,
                retrievalMs, generationMs, totalStarted);
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
                retrievalMs, 0, totalStarted);
    }

    private AgentResponse validateAndExecute(String question, String sql, LlmCallRecord llmCall,
                                             SchemaContext schema, int tableCount,
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
                    timings(retrievalMs, generationMs, validationMs, 0, elapsedMs(totalStarted)));
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
            return AgentResponse.failed(question, AgentResponse.Status.EXECUTION_FAILED, e.getMessage(),
                    llmCall, tableCount,
                    timings(retrievalMs, generationMs, validationMs, executionMs, elapsedMs(totalStarted)));
        }
        long executionMs = elapsedMs(executionStarted);

        AgentResponse.Timings timings = timings(retrievalMs, generationMs, validationMs, executionMs,
                elapsedMs(totalStarted));

        return AgentResponse.success(question, validation.sql(), validation.rewritten(),
                new AgentResponse.QueryOutcome(result.columns(), result.rows(), result.truncated()),
                llmCall, tableCount, timings);
    }

    private static AgentResponse.Timings timings(long retrieval, long generation, long validation,
                                                 long execution, long total) {
        return new AgentResponse.Timings(retrieval, generation, validation, execution, total);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
