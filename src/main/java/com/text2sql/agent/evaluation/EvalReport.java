package com.text2sql.agent.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 一次评估运行的完整快照，会序列化成 {@code reports/eval-<时间戳>.json}。
 *
 * <p><b>为什么要把「元信息」和「指标」放在同一份文件里</b>
 *
 * <p>ROADMAP 5.5 要求每次评估都记录代码版本、prompt 版本、模型版本、时间戳。
 * 原因是数字本身没有意义——「准确率 63%」这句话在换了模型或改了 prompt 之后
 * 就不再指向同一件事。把元信息和指标绑在一个文件里，任何一份报告都能独立回答
 * 「这是什么时候、用什么配置、跑出来的数字」。分开存的话，过两周就没人能
 * 对上号了。
 *
 * @param meta       运行元信息（配置快照）
 * @param overall    整体指标
 * @param byLayer    按难度分层的指标
 * @param failures   失败案例明细，按发生顺序
 */
public record EvalReport(
        Meta meta,
        Overall overall,
        Map<String, Overall> byLayer,
        List<Failure> failures) {

    /**
     * 运行元信息。这些字段的共同点是：**改了它们，数字就不可比**。
     * 所以必须随报告一起落盘，而不是指望事后回忆。
     */
    public record Meta(
            String timestamp,
            String promptVersion,
            String model,
            String baseUrl,
            boolean dryRun,
            int itemCount,
            int limit,
            List<String> layers,
            boolean includeDataProfile,
            boolean includeForeignKeys,
            String limitMode,
            int maxRows,
            int schemaTableCount) {
    }

    /**
     * 一组指标。整体和分层用同一个结构，这样「整体」和「某一层」的
     * 字段含义完全一致，写报告时不需要为两者各写一套格式化逻辑。
     *
     * @param total          评估项总数
     * @param executionAccuracy 执行准确率 = 结果集一致的条数 / 总数。主指标
     * @param sqlValidRate    SQL 有效率 = 通过校验且执行成功的比例。用来区分「生成错误」和「执行错误」
     * @param rewrittenCount  靠补 LIMIT 救回来的条数。这部分成功不能算模型能力
     * @param avgPromptTokens 平均 prompt token。阶段 2 要靠它证明检索降低了上下文
     * @param avgLatencyMs    平均端到端延迟
     * @param p50LatencyMs    延迟中位数
     * @param p95LatencyMs    延迟 P95
     * @param avgCostYuan     平均单次成本（元）
     * @param totalCostYuan   本轮总成本（元）
     */
    public record Overall(
            int total,
            int correct,
            double executionAccuracy,
            int sqlValid,
            double sqlValidRate,
            int rewrittenCount,
            double avgPromptTokens,
            double avgLatencyMs,
            long p50LatencyMs,
            long p95LatencyMs,
            double avgCostYuan,
            double totalCostYuan) {
    }

    /**
     * 一条失败案例。
     *
     * <p>存的是「问题 + 生成的 SQL + 状态」，而不是「算出来的相似度」。
     * 因为失败归因必须靠人看具体 SQL 才能做，任何自动化的相似度分数
     * 都无法替代「哦，它把 customer_id 当成客户了」这个判断。
     * 评估报告的价值有一半在于它让这一步变便宜。
     */
    public record Failure(
            String id,
            String difficulty,
            List<String> tags,
            String question,
            String status,
            String generatedSql,
            String message,
            long latencyMs) {
    }
}
