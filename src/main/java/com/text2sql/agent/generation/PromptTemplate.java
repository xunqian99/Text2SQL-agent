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
     * 阶段 1 的 prompt 版本号。每次改动这里的文字都要 +1，并写进评估报告。
     * 没有版本号，消融实验的数字就没有可比性。
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
        sb.append("=== 用户问题 ===\n").append(question.strip()).append("\n");
        return sb.toString();
    }
}
