package com.text2sql.agent.generation;

import com.text2sql.agent.retrieval.SchemaContext;
import org.springframework.stereotype.Component;

/**
 * 把 schema 上下文和用户问题组装成最终 prompt。
 *
 * <p>为什么把「组装 prompt」单独抽成一层，而不是在生成器里拼字符串：
 * 阶段 1–6 改动最频繁的就是这段话。把它独立出来，才能对 prompt 做版本管理——
 * 评估报告里要记录 prompt 版本（ROADMAP 5.5），如果 prompt 散在代码里，
 * 「这次提升到底来自 prompt 改动还是来自检索」就永远说不清。
 *
 * <p>系统提示词里刻意写了几条**约束性规则**（只读、只输出 SQL、必须 LIMIT）。
 * 但注意：这些规则只是「软约束」，模型可能不遵守。真正的硬约束在
 * {@code SqlValidator} 里。这是两层防线，不是重复劳动——prompt 降低出错概率，
 * 校验器保证出错也不会造成后果。
 */
@Component
public class PromptTemplate {

    /**
     * prompt 版本号。每次改动这里的文字都要 +1，并写进评估报告。
     * 没有版本号，消融实验的数字就没有可比性。
     *
     * <p><b>阶段 2 为什么版本号没变</b>
     *
     * <p>这是刻意的，也是消融实验能不能成立的关键。阶段 2 改的是
     * **上下文里有哪些表**，不是**这段文字怎么组织**。如果把版本号一起改掉，
     * 「+schema 检索」那一行的数字就同时包含了「检索」和「prompt 措辞」
     * 两个变量，准确率的提升无法归因给任何一个。
     *
     * <p>所以这里必须保持 {@code p1-full-schema-v1} 不变，让阶段 1 和
     * 阶段 2 的报告在 prompt 这一列上完全一致。如果之后真的改了措辞
     * （比如加一句「优先使用高置信度的表」），那才需要 +1，
     * 并且要重跑阶段 1 的 baseline 才能对比。
     */
    public static final String VERSION = "p7-semantic-refinement-v7";

    public String systemPrompt() {
        return """
                你是一个 PostgreSQL 数据分析助手。你的唯一任务是把用户的中文问题翻译成一条可执行的 SQL。

                思考与推理步骤（Chain-of-Thought 思维链）：
                在编写 SQL 前，请严格按以下四步逻辑进行推导：
                1. 识别实体与目标表：从问题中提取核心业务实体，锁定需要的表；
                2. 规划连接路径：多表查询时，参考 JOIN PLAN 与 FOREIGN KEYS 确定连接键，防止过度连接或笛卡尔积；
                3. 对齐过滤条件：涉及状态、编码或业务指标时，严格按「字段枚举对齐」与 METRICS 表达式对齐物理值；
                4. 确定维度与聚合：确定 GROUP BY 维度、聚合函数与排序规则，仅投影问题真正需要的字段。

                硬性规则：
                1. 只输出一条 SELECT 语句，不要输出 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE。
                2. 不要输出任何代码围栏外解释或闲聊文本，直接输出可执行的 SQL 语句。
                3. 必须带 LIMIT，除非结果是单行聚合值（如 COUNT/SUM/AVG 且无 GROUP BY）。
                4. 只使用下面 schema 里真实存在的表和列，不要臆造字段名。
                5. 表名和列名一律用小写，不要加引号。
                6. 遇到多表查询时，优先参考 JOIN PLAN（推荐的连接顺序）或 FOREIGN KEYS 里给出的关联关系。
                7. 如果下面给出了 METRICS（业务指标定义），**必须照抄它的表达式**，
                   不要自己重新拼条件。这些口径是业务方确认过的，自行发挥会导致答案不一致。
                8. SELECT 列表只放问题真正要求的内容。问题问「多少 / 哪些指标 / 平均是多少」时，
                   只输出维度列和指标列；不要顺手附带 id、名称、编码等展示用列。
                   只有问题明确要求看某个名称或编号时，才把它放进 SELECT。
                9. 聚合粒度必须与问题的「每个…」一致。GROUP BY 的列要和 SELECT 里的维度列对应，
                   不要多分一层，也不要把分组列换成另一张表里的等价列。
                10. 多表关联可能放大行数。只要 join 之后再去重计数，就用 COUNT(DISTINCT ...)；
                   单表且无 join 时才用 COUNT(*)。计数口径错了会让分组结果整体偏移。
                11. 不要为了「信息更全」而加列、加分组或加排序。评估比的是结果集，
                   多一列和少一列都算错。
                12. 比例与精度口径：
                   - 当问题询问「比例 / 占比 / 比率」且未显式提及百分比（%）时，输出 0~1 的小数比率，用 ROUND(分子::NUMERIC / 分母, 4) 保留四位小数。
                   - 当问题明确询问「百分比」或带有「%」时，才输出 ROUND(100.0 * 分子 / 分母, 2)。
                   - 其余一般金额、单价、平均值，统一用 ROUND(表达式, 2) 保留两位小数。
                13. 按时间分组与时间字段选择：
                   - 用 DATE_TRUNC('month'|'week'|'day', 时间列) 作为分组列，不要用 to_char 把时间转成 'YYYY-MM' 这类字符串。
                   - 问题涉及各月/每个月/每周等周期统计时，若表内有自身的业务时间字段（如 refunds.requested_at, support_tickets.created_at, members.register_date, settlements.period_start），优先使用本表对应业务时间，不要无故关联 orders 表。
                14. 时间窗口过滤用 >= 起点 AND < 终点（左闭右开），不要用 BETWEEN：
                   BETWEEN 会把终点那一整天的数据也算进来。
                15. 如果下面给出了「字段枚举与实体取值对齐」，WHERE 过滤条件中涉及该实体时，
                   **必须严格使用对应的物理字段与取值**（例如使用 customer_state = 'SP' 而不是 '圣保罗'）。
                16. 实体与明细字段指引：
                   - 查询订单明细的价格/销售金额时，直接使用 order_items 表及其 price 列；仅在明确提到卖家结算、抽成或对账时才使用 settlements / settlement_items。
                   - 查询承运商配送时长时，直接使用 shipments 表的 (delivered_at - shipped_at)，无需关联轨迹表。
                   - 工单实际处理时长计算用 support_tickets 表的 (resolved_at - created_at)；ticket_categories.sla_hours 仅为时限标准而非实际耗时。
                17. 多个固定枚举值对比（条件聚合）：
                   - 当问题同时询问某维度多个固定枚举值的统计量（如“A和B各有多少笔/多少个”）时，优先使用 COUNT(*) FILTER (WHERE 列 = 'A') AS a, COUNT(*) FILTER (WHERE 列 = 'B') AS b 进行横向条件聚合输出。
                18. 复杂多表关联时，可在 SELECT 语句首行添加一行以 -- 开头的单行注释说明思路，之后紧接完整 SQL。
                """.strip();
    }

    /**
     * 组装用户消息。
     *
     * <p>顺序是「schema → 数据画像 → 问题」。把问题放在最后是刻意的：
     * 长上下文里模型对末尾内容注意力更强，问题放末尾能减少「答非所问」。
     * 这是 prompt 工程里少数几条有稳定收益的经验之一。
     */
    public String userPrompt(SchemaContext schema, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 数据库 Schema ===\n").append(schema.ddlText()).append("\n\n");
        if (schema.dataProfile() != null && !schema.dataProfile().isBlank()) {
            sb.append("=== 数据画像 ===\n").append(schema.dataProfile()).append("\n\n");
        }
        // 业务指标放在 schema 之后、问题之前。
        //
        // 位置是刻意的：指标是「怎么算」的规则，属于背景知识，应该和 schema 相邻；
        // 而问题必须在最后（模型对末尾注意力更强）。放在 schema 和问题中间，
        // 既在背景区，又离问题最近——需要引用时最容易够到。
        if (schema.hasMetrics()) {
            sb.append("=== 业务指标定义（必须照抄表达式）===\n")
                    .append(schema.metricsText()).append("\n\n");
        }
        if (schema.hasValueHints()) {
            sb.append("=== 字段枚举与实体取值对齐（请直接使用以下库内真实物理值）===\n")
                    .append(schema.valueHints()).append("\n\n");
        }
        if (schema.hasExamples()) {
            sb.append("=== 参考示例 ===\n");
            for (var ex : schema.examples()) {
                sb.append("问题: ").append(ex.question().strip()).append("\n")
                        .append("SQL: ").append(ex.sql().strip()).append("\n---\n");
            }
            sb.append("\n");
        }
        sb.append("=== 用户问题 ===\n").append(question.strip()).append("\n");
        return sb.toString();
    }

    /**
     * 组装一次自纠错请求。
     *
     * <p>这是阶段 5 的第二次生成入口。反馈放在用户问题之后，模型先看到
     * 原始 schema 和问题，再看到「上一版哪里不对」，更容易只修 SQL 的错误部分。
     * 约束里明确要求只返回 SQL，避免模型把分析过程当成下一次 SQL。
     */
    public String correctionPrompt(SchemaContext schema, String question,
                                   String previousSql, String feedback) {
        return userPrompt(schema, question)
                + "\n\n=== 上一次 SQL 与评估反馈 ===\n"
                + "上一条 SQL:\n"
                + (previousSql == null ? "（没有生成出 SQL）" : previousSql.strip())
                + "\n\n反馈:\n"
                + (feedback == null ? "请检查上一条 SQL 的表、字段、连接、聚合粒度和输出列。"
                : feedback.strip())
                + "\n\n请只输出修正后的 SQL，不要输出解释、markdown 或多个候选。\n";
    }
}
