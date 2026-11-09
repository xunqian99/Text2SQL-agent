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
 *
 * <p><b>为什么参数不用一条正则抓完</b>：工具的入参本身就可能含括号，
 * 例如 {@code SAMPLE_QUERY(SELECT COUNT(*) FROM orders)}。用非贪婪的
 * {@code \(([\s\S]*?)\)} 会停在**第一个**右括号上，把参数截成
 * {@code SELECT COUNT(*}——工具随后执行一条残缺 SQL，失败信息还指不到解析层。
 * 所以这里只用一个正则定位「工具名 + 左括号」，参数的结束位置由
 * {@link #findMatchingParen} 按括号配对扫描得出，并跳过单引号字符串里的括号。
 */
public final class ToolParser {

    private static final Pattern FINAL_SQL_PATTERN = Pattern.compile(
            "\\[FINAL_SQL\\]\\s*([\\s\\S]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 只匹配到「[TOOL_CALL] 工具名(」，不负责匹配右括号。
     * 右括号的位置交给括号配对扫描，见类注释。
     */
    private static final Pattern TOOL_CALL_HEAD_PATTERN = Pattern.compile(
            "\\[TOOL_CALL\\]\\s*([A-Z_]+)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private ToolParser() {}

    /**
     * 解析模型输出中的**全部**工具调用。
     *
     * <p>为什么需要这个：实测里模型经常一条消息里连发两个工具调用
     * （{@code [TOOL_CALL] INSPECT_COLUMN(a.b)} 换行 {@code [TOOL_CALL] INSPECT_COLUMN(c.d)}），
     * 旧实现只取第一个，第二个被丢掉；更糟的是括号配对从第一个左括号开始扫，
     * 两个调用会互相干扰，最终 {@code parse} 返回 null，整段文本被当成 SQL
     * 送进校验层，报 {@code PARSE_ERROR}——归因会错写成「模型写错 SQL」。
     *
     * @return 按出现顺序排列的工具调用；没有则返回空列表
     */
    public static java.util.List<ToolCall> parseAll(String text) {
        java.util.List<ToolCall> calls = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return calls;
        }

        // [FINAL_SQL] 优先级最高：出现即视为定稿，同一段里不再解析工具调用。
        Matcher finalMatcher = FINAL_SQL_PATTERN.matcher(text);
        if (finalMatcher.find()) {
            calls.add(new ToolCall(ToolType.FINAL_SQL, finalMatcher.group(1).strip(), text));
            return calls;
        }

        Matcher headMatcher = TOOL_CALL_HEAD_PATTERN.matcher(text);
        int from = 0;
        while (headMatcher.find(from)) {
            String toolName = headMatcher.group(1).strip().toUpperCase();
            int openParenIndex = headMatcher.end() - 1;
            int closeParenIndex = findMatchingParen(text, openParenIndex);
            if (closeParenIndex < 0) {
                // 括号不配对：后面的内容都不可信，停止解析。
                break;
            }
            String arg = text.substring(openParenIndex + 1, closeParenIndex).strip();
            try {
                calls.add(new ToolCall(ToolType.valueOf(toolName), arg, text));
            } catch (IllegalArgumentException e) {
                // 未知工具名：跳过这一条，继续找后面的
            }
            from = closeParenIndex + 1;
        }
        return calls;
    }

    /**
     * 解析模型输出。
     *
     * @param text 模型原始输出
     * @return 解析出的第一个工具调用；若没有显式工具调用标记，返回 null
     */
    public static ToolCall parse(String text) {
        var all = parseAll(text);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 从 {@code openParenIndex} 处的左括号开始，找到与之配对的右括号下标。
     *
     * <p>要跳过单引号字符串里的括号，否则
     * {@code SAMPLE_QUERY(SELECT * FROM orders WHERE status = '(')}
     * 会把字符串里的左括号也算进层级，导致配对错位。
     * 连续两个单引号（{@code ''}）是 SQL 里的转义写法，按转义处理。
     *
     * @return 配对右括号的下标；找不到返回 -1
     */
    private static int findMatchingParen(String text, int openParenIndex) {
        int depth = 0;
        boolean inSingleQuote = false;
        for (int i = openParenIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    // '' 是转义的单引号，不结束字符串
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inSingleQuote = false;
                    }
                }
                continue;
            }
            switch (c) {
                case '\'' -> inSingleQuote = true;
                case '(' -> depth++;
                case ')' -> {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
                default -> {
                    // 普通字符，忽略
                }
            }
        }
        return -1;
    }
}
