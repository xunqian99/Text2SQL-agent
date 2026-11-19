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
public record ToolResult(boolean success, String output, List<String> columns, List<List<String>> rows) {

    public static ToolResult ok(String output, List<String> columns, List<List<String>> rows) {
        return new ToolResult(true, output, columns, rows);
    }

    public static ToolResult error(String errorMsg) {
        return new ToolResult(false, "工具执行失败：" + errorMsg, List.of(), List.of());
    }
}
