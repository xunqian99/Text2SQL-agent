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
 * @param byTableCount 按「gold SQL 实际用到几张表」分组的指标（阶段 3 新增）
 * @param failures   失败案例明细，按发生顺序
 * @param retrieval  检索层指标（阶段 2）。关闭检索时为空
 * @param correction  自纠错统计（阶段 5）。未开启时仍保留一条 disabled 记录
 */
public record EvalReport(
        Meta meta,
        Overall overall,
        Map<String, Overall> byLayer,
        Map<String, Overall> byTableCount,
        List<Failure> failures,
        Retrieval retrieval,
        Correction correction) {

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
            String split,
            boolean includeDataProfile,
            boolean includeForeignKeys,
            String limitMode,
            int maxRows,
            int schemaTableCount,
            boolean retrievalEnabled,
            int retrievalTopK,
            boolean selfCorrectionEnabled,
            int selfCorrectionMaxAttempts) {
    }

    /**
     * 阶段 5 自纠错统计，单独记录收益和代价，避免把第二次调用藏进准确率。
     *
     * @param trigger            触发条件：{@code ERRORS_ONLY} 为线上可复现，
     *                           {@code ERRORS_AND_MISMATCH} 为评估专用的上限实验
     * @param firstAttemptCorrect 首次生成就答对的条数。最终准确率减去它，
     *                            才是自纠错真正救回来的数量
     * @param retriedCount       实际触发重试的条数
     * @param recoveredCount     重试后由错转对的条数
     * @param extraLatencyMs     重试额外消耗的模型时间合计
     */
    public record Correction(
            boolean enabled,
            int maxAttempts,
            String trigger,
            int eligibleCount,
            int firstAttemptCorrect,
            int retriedCount,
            int recoveredCount,
            int extraPromptTokens,
            int extraCompletionTokens,
            long extraLatencyMs) {
    }

    /**
     * 检索层指标。
     *
     * <p><b>为什么单列一组指标，而不是并进 Overall</b>
     *
     * <p>执行准确率回答「系统答对了多少」，召回率回答「检索选对了多少」。
     * 两者是**不同层**的问题，混在一张表里会让人误以为它们是同一层的指标。
     * 更实际的理由：执行准确率受模型能力影响，召回率只受检索影响——
     * 把召回率单独放，才能做到「换模型时召回率不变」这个应该成立的断言。
     * 如果它们混在一起，换模型后所有数字都在动，就无法归因。
     *
     * <p>ROADMAP 阶段 2 验收标准「表召回率（Top-5）≥ 90%」看的就是
     * {@code fullRecallRate}。
     *
     * @param evaluatedCount  参与统计的样本数（gold_sql 解析失败的会被排除）
     * @param fullRecallRate  全部期望表都被召回的比例。验收主指标
     * @param tableRecallRate 期望表的平均召回比例。全召回率是「严苛版」，
     *                        这个是「宽松版」；两者差距大说明检索经常
     *                        「差一张表」，那通常是关系扩展没走到
     * @param avgPrecision    平均精确率。召回的表里有多少是真正需要的
     * @param avgRetrievedTables 平均召回表数，用来对照 baseline 的 37
     * @param missedCount     一条期望表都没命中的条数。最严重的失败类型
     * @param avgDdlChars     平均 DDL 字符数，token 下降的直接证据
     * @param incompleteCases 所有「有期望表没被召回」的案例，含只差一张表的
     */
    public record Retrieval(
            int evaluatedCount,
            double fullRecallRate,
            double tableRecallRate,
            double avgPrecision,
            double avgRetrievedTables,
            int missedCount,
            double avgDdlChars,
            List<MissedCase> incompleteCases) {
    }

    /**
     * 一条漏召回的案例。
     *
     * <p>只记「漏了哪些表」和「问题长什么样」，不记相似度分数。
     * 因为修法完全取决于漏的是什么：漏 {@code regions} 说明维度表没被识别，
     * 漏 {@code refunds} 说明词典缺「退款」类词，漏 {@code geolocation}
     * 说明长尾表需要特殊照顾。这些判断只能人来做，报告要做的是
     * 把它们**集中列出来**，而不是替人下结论。
     *
     * <p><b>为什么要包含「只差一张表」的案例，而不是只记完全漏召回</b>
     *
     * <p>这是被实测数字逼出来的：第一次跑完 200 条，完全漏召回是 0 条，
     * 但全召回率只有 84.5%。也就是说所有失败都是「差一两张表」。
     * 如果报告只记完全漏召回，就会显示「零失败」——而真实情况是
     * 有 31 条没达标。只盯最严重的失败类型会让人误判检索已经可用。
     */
    public record MissedCase(String id, String question, List<String> missing, List<String> retrieved) {
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
            long latencyMs,
            String firstSql,
            int attempts,
            boolean corrected) {
    }
}
