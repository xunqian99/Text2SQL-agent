package com.text2sql.agent.orchestrator;

import com.text2sql.agent.observability.LlmCallRecord;

import java.util.List;

/**
 * 一次问答的完整结果，是编排层对外的唯一输出类型。
 *
 * <p>这个 record 比「只要 SQL 和结果」多出来的字段，才是它存在的理由：
 *
 * <ul>
 *   <li>{@code status} —— 把「失败」拆成五种。只用异常表示失败的话，
 *       评估器就没法统计「多少条是模型没配 Key、多少条是模型写错、
 *       多少条是 SQL 执行不了」。而「错在哪一层」正是这个项目要回答的核心问题。</li>
 *   <li>{@code timings} —— 分阶段耗时。总耗时 6 秒说明不了任何问题，
 *       「生成 5.4 秒、执行 0.1 秒」才说明瓶颈在模型。</li>
 *   <li>{@code llmCall} —— token 与成本。它跟着结果一起返回，而不是写日志，
 *       这样评估报告可以直接聚合，不用去解析日志文本。</li>
 *   <li>{@code rewritten} —— 是否靠补 LIMIT 救回来的。评估时要把
 *       「模型本来就写对」和「校验器帮忙兜底」分开统计，否则护栏的贡献会被算进模型能力。</li>
 * </ul>
 *
 * @param question        用户原始问题
 * @param status          执行结果状态
 * @param sql             实际执行（或尝试执行）的 SQL，已包含校验层的改写
 * @param message         失败时的可读原因，成功时为 null
 * @param violations      校验违规明细，非校验失败时为空列表
 * @param columns         结果列名
 * @param rows            结果数据
 * @param truncated       结果是否因行数上限被截断
 * @param rewritten       校验层是否改写过 SQL
 * @param llmCall         LLM 调用记录；未调用（未配置或检索失败）时为 null
 * @param schemaTableCount 本次请求使用的 schema 表数量，用于对比检索前后的上下文规模
 * @param timings         各阶段耗时
 */
public record AgentResponse(
        String question,
        Status status,
        String sql,
        String message,
        List<String> violations,
        List<String> columns,
        List<List<String>> rows,
        boolean truncated,
        boolean rewritten,
        LlmCallRecord llmCall,
        int schemaTableCount,
        Timings timings) {

    /**
     * 请求的终态。
     *
     * <p>刻意把「模型没配 Key」和「模型写错了」分开：前者是环境问题，
     * 重试一万次也没用；后者是质量问题，是准确率的分母。混成一类，
     * 评估数字就会在换了环境之后莫名其妙地变化。
     */
    public enum Status {
        /** 全链路成功，拿到结果集。 */
        SUCCESS,
        /** 未配置 LLM，压根没发起调用。环境问题，不算模型失败。 */
        NOT_CONFIGURED,
        /** 调用了 LLM 但失败（网络、限流、返回内容提不出 SQL）。模型侧问题。 */
        GENERATION_FAILED,
        /** 生成了 SQL 但没通过安全校验。属于「被护栏拦下」，不是执行错误。 */
        REJECTED,
        /** 校验通过但数据库执行失败（语法方言、表不存在、超时）。 */
        EXECUTION_FAILED
    }

    /**
     * 分阶段耗时。
     *
     * @param retrievalMs  检索层耗时（阶段 1 是缓存命中，接近 0）
     * @param generationMs LLM 调用耗时，通常是绝对大头
     * @param validationMs 校验耗时，纯 CPU 计算，应该远小于 1ms
     * @param executionMs  数据库执行耗时
     * @param totalMs      端到端总耗时
     */
    public record Timings(long retrievalMs, long generationMs, long validationMs, long executionMs, long totalMs) {
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }

    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }

    public static AgentResponse success(String question, String sql, boolean rewritten, QueryOutcome outcome,
                                        LlmCallRecord call, int schemaTableCount, Timings timings) {
        return new AgentResponse(question, Status.SUCCESS, sql, null, List.of(),
                outcome.columns(), outcome.rows(), outcome.truncated(), rewritten,
                call, schemaTableCount, timings);
    }

    public static AgentResponse rejected(String question, String sql, List<String> violations,
                                         LlmCallRecord call, int schemaTableCount, Timings timings) {
        return new AgentResponse(question, Status.REJECTED, sql, String.join("; ", violations), violations,
                List.of(), List.of(), false, false, call, schemaTableCount, timings);
    }

    public static AgentResponse failed(String question, Status status, String message,
                                       LlmCallRecord call, int schemaTableCount, Timings timings) {
        return new AgentResponse(question, status, null, message, List.of(),
                List.of(), List.of(), false, false, call, schemaTableCount, timings);
    }

    /** 执行层结果的投影，避免 orchestrator 直接依赖 execution 包的 record。 */
    public record QueryOutcome(List<String> columns, List<List<String>> rows, boolean truncated) {
    }
}
