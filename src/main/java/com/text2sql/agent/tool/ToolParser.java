package com.text2sql.agent.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责解析大模型返回文本中的工具调用指令。
 *
 * <p>支持格式：
 * <ul>
 *   <li>[TOOL_CALL] INSPECT_TABLE(orders)</li>
 *   <li>[TOOL_CALL] INSPECT_COLUMN(orders.order_status)</li>
 *   <li>[TOOL_CALL] SAMPLE_QUERY(SELECT * FROM orders WHERE ...)</li>
 *   <li>[TOOL_CALL] CHECK_JOIN(orders, customers, orders.customer_id = customers.customer_id)</li>
 *   <li>[FINAL_SQL] SELECT ...</li>
 * </ul>
 */
public final class ToolParser {

    private static final Pattern FINAL_SQL_PATTERN = Pattern.compile(
            "\\[FINAL_SQL\\]\\s*([\\s\\S]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "\\[TOOL_CALL\\]\\s*([A-Z_]+)\\s*\\(([\\s\\S]*?)\\)", Pattern.CASE_INSENSITIVE);

    private ToolParser() {}

    /**
     * 解析模型输出。
     *
     * @param text 模型原始输出
     * @return 解析出的工具调用；若没有显式工具调用标记，返回 null
     */
    public static ToolCall parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // 优先检查是否输出 [FINAL_SQL]
        Matcher finalMatcher = FINAL_SQL_PATTERN.matcher(text);
        if (finalMatcher.find()) {
            String sql = finalMatcher.group(1).strip();
            return new ToolCall(ToolType.FINAL_SQL, sql, text);
        }

        // 检查 [TOOL_CALL]
        Matcher toolMatcher = TOOL_CALL_PATTERN.matcher(text);
        if (toolMatcher.find()) {
            String toolName = toolMatcher.group(1).strip().toUpperCase();
            String arg = toolMatcher.group(2).strip();
            try {
                ToolType type = ToolType.valueOf(toolName);
                return new ToolCall(type, arg, text);
            } catch (IllegalArgumentException e) {
                // 未知工具名
                return null;
            }
        }

        return null;
    }
}
