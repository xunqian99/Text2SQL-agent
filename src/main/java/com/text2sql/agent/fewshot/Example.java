package com.text2sql.agent.fewshot;

import java.util.Set;

/**
 * 代表一条 Few-shot 参考示例。
 *
 * @param id 示例 ID（如 T4-003）
 * @param difficulty 难度分层
 * @param question 中文问题
 * @param sql 标准 SQL
 * @param tables 涉及的真实表名列表（解析得到或 YAML 配置）
 */
public record Example(
        String id,
        String difficulty,
        String question,
        String sql,
        Set<String> tables) {

    public Example(String id, String difficulty, String question, String sql) {
        this(id, difficulty, question, sql, Set.of());
    }

    public Example withTables(Set<String> parsedTables) {
        return new Example(id, difficulty, question, sql, parsedTables);
    }
}
