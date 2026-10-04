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
    public static final String VERSION = "p1-full-schema-v1";

    public String systemPrompt() {
        return """
                你是一个 PostgreSQL 数据分析助手。你的唯一任务是把用户的中文问题翻译成一条可执行的 SQL。

                硬性规则：
                1. 只输出一条 SELECT 语句，不要输出 INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE。
                2. 不要输出任何解释、注释、markdown 围栏，直接输出 SQL 本身。
                3. 必须带 LIMIT，除非结果是单行聚合值（如 COUNT/SUM/AVG 且无 GROUP BY）。
                4. 只使用下面 schema 里真实存在的表和列，不要臆造字段名。
                5. 表名和列名一律用小写，不要加引号。
                6. 遇到多表查询时，优先使用 FOREIGN KEYS 里给出的关联关系。
                7. 如果下面给出了 METRICS（业务指标定义），**必须照抄它的表达式**，
                   不要自己重新拼条件。这些口径是业务方确认过的，自行发挥会导致答案不一致。
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
        sb.append("=== 用户问题 ===\n").append(question.strip()).append("\n");
        return sb.toString();
    }
}
