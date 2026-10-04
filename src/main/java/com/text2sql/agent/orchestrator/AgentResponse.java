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
 * @param retrievedTables 本次检索实际选中的表名。**表召回率就靠它算**——
 *                        没有这个字段，评估器只能看到「表数量是 8」，
 *                        无法回答「该用的那张表在不在里面」。
 *                        注意：全量 provider 下它就是全部表名，
 *                        因此这个字段在两种配置下语义一致（都是「模型看到了哪些表」），
 *                        可以直接用来对比。
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
        List<String> retrievedTables,
        int schemaDdlChars,
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
        EXECUTION_FAILED,
        /**
         * 问题本身有多种合理口径，系统主动反问，未生成 SQL（阶段 5）。
         *
         * <p>它和 REJECTED 的区别是**责任方不同**：REJECTED 是模型写错了，
         * NEEDS_CLARIFICATION 是问题没问清。归因时如果把两者混成一类，
         * 会得出「模型爱写违规 SQL」的错误结论。
         */
        NEEDS_CLARIFICATION
    }

    /**
     * 构造「需要澄清」的响应。
     *
     * <p>注意 {@code sql} 传 null、{@code message} 放反问内容：这一轮**没有**
     * 生成 SQL，编造一条出来会让评估的 SQL 有效率虚高。
     */
    public static AgentResponse clarification(String question, String prompt) {
        return new AgentResponse(question, Status.NEEDS_CLARIFICATION, null, prompt, List.of(),
                List.of(), List.of(), false, false, null, 0, List.of(), 0, null);
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
                                        LlmCallRecord call, int schemaTableCount,
                                        List<String> retrievedTables, int schemaDdlChars, Timings timings) {
        return new AgentResponse(question, Status.SUCCESS, sql, null, List.of(),
                outcome.columns(), outcome.rows(), outcome.truncated(), rewritten,
                call, schemaTableCount, retrievedTables, schemaDdlChars, timings);
    }

    public static AgentResponse rejected(String question, String sql, List<String> violations,
                                         LlmCallRecord call, int schemaTableCount,
                                         List<String> retrievedTables, int schemaDdlChars, Timings timings) {
        return new AgentResponse(question, Status.REJECTED, sql, String.join("; ", violations), violations,
                List.of(), List.of(), false, false, call, schemaTableCount, retrievedTables,
                schemaDdlChars, timings);
    }

    /**
     * 构造一个失败响应。
     *
     * <p><b>为什么 {@code sql} 必须是参数，而不是硬编码 null</b>
     *
     * <p>这里踩过一个真实的坑：早期版本把 sql 固定成 null，理由是「都失败了，
     * SQL 也没意义」。结果评估报告里 19 条 {@code EXECUTION_FAILED} 的
     * {@code generatedSql} 全是空——**恰恰是最需要看 SQL 的那类失败，反而没有 SQL**。
     * 同时 Spring 的异常翻译器因为拿不到 SQL，把错误信息渲染成
     * {@code bad SQL grammar []}，方括号里空无一物，连「模型写歪了」还是
     * 「校验层改坏了」都无法区分。
     *
     * <p>失败分类是这个项目的核心产出，而分类的前提是能看到失败的那条语句。
     * 所以除了「压根没生成出 SQL」的生成失败，其余失败都必须带上 SQL。
     *
     * @param sql 实际尝试执行的 SQL；生成阶段就失败（没有 SQL）时传 null
     */
    public static AgentResponse failed(String question, Status status, String sql, String message,
                                       LlmCallRecord call, int schemaTableCount,
                                       List<String> retrievedTables, int schemaDdlChars, Timings timings) {
        return new AgentResponse(question, status, sql, message, List.of(),
                List.of(), List.of(), false, false, call, schemaTableCount, retrievedTables,
                schemaDdlChars, timings);
    }

    /** 执行层结果的投影，避免 orchestrator 直接依赖 execution 包的 record。 */
    public record QueryOutcome(List<String> columns, List<List<String>> rows, boolean truncated) {
    }
}
