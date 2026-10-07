package com.text2sql.agent.tool;

/**
 * 代表大模型解析出的一次工具调用请求。
 *
 * @param type 工具类型
 * @param argument 工具调用参数（如表名、SQL 语句、连表表达式等）
 * @param rawCall LLM 原始的文本调用格式
 */
public record ToolCall(ToolType type, String argument, String rawCall) {

    public static ToolCall finalSql(String sql) {
        return new ToolCall(ToolType.FINAL_SQL, sql, "[FINAL_SQL] " + sql);
    }
}
