package com.text2sql.agent.tool;

import java.util.List;

/**
 * 工具在安全沙箱环境中的执行结果。
 *
 * @param success 是否成功执行
 * @param output 返回给大模型的观察文本（Observation）
 * @param columns 列名（若有）
 * @param rows 样例数据（若有）
 */
public record ToolResult(boolean success, String output, List<String> columns, List<List<String>> rows,
                         boolean schemaUnavailable) {

    public static ToolResult ok(String output, List<String> columns, List<List<String>> rows) {
        return new ToolResult(true, output, columns, rows, false);
    }

    public static ToolResult error(String errorMsg) {
        return new ToolResult(false, "工具执行失败：" + errorMsg, List.of(), List.of(), false);
    }

    public static ToolResult catalogUnavailable() {
        return new ToolResult(false, "[SCHEMA_UNAVAILABLE] 完整业务 Schema 不可用，未执行 SQL",
                List.of(), List.of(), true);
    }
}
