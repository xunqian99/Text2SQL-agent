package com.text2sql.agent.generation;

import java.util.regex.Pattern;

/**
 * 规范化模型输出最外层的 SQL 包装，不猜测或改写 SQL 正文。
 *
 * <p>只剥整段回复边界的独立围栏行以及一个 SQL: 标签。兼容缺失单侧围栏，
 * 不全局替换反引号，不截第一个分号，也不搜索并选取回复中间的第一个代码块。
 *
 * <p>不再按空行删除“疑似解释”：SQL 字符串、注释、CTE、OFFSET 等都可能跨空行，
 * 误删会静默改变查询。多语句和多块输出不能通过“只取第一块”被洗成合法单查询。
 * SQL 语法及只读性仍由 SqlValidator 和数据库负责；本类不声称完成 SQL 校验。
 */
public final class SqlExtractor {

    private static final Pattern OPENING_FENCE = Pattern.compile("```(?:sql|postgresql)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_LABEL = Pattern.compile("\\ASQL\\h*[:：]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$(?:[\\p{L}_][\\p{L}\\p{N}_]*)?\\$");

    private SqlExtractor() {
    }

    /**
     * @param raw 模型原始输出
     * @return 清理后的 SQL；提不出任何内容时抛 {@link GenerationException}
     */
    public static String extract(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GenerationException(GenerationException.Reason.UNPARSEABLE, "模型返回空内容");
        }
        String text = raw.strip();

        // 标签可位于围栏外或围栏内，但每个位置只处理一次，不循环“清洗”正文。
        text = stripLabel(text);
        int firstBreak = text.indexOf('\n');
        String firstLine = (firstBreak < 0 ? text : text.substring(0, firstBreak)).strip();
        if (OPENING_FENCE.matcher(firstLine).matches()) {
            text = firstBreak < 0 ? "" : text.substring(firstBreak + 1).strip();
        }
        int lastBreak = text.lastIndexOf('\n');
        String lastLine = text.substring(lastBreak + 1).strip();
        if (lastLine.equals("```")) {
            text = lastBreak < 0 ? "" : text.substring(0, lastBreak).strip();
        }
        text = stripLabel(text);
        if (text.isBlank()) {
            throw new GenerationException(GenerationException.Reason.UNPARSEABLE, "模型返回内容里没有 SQL");
        }
        rejectUnconsumedFences(text);
        return text;
    }

    private static String stripLabel(String text) {
        return LEADING_LABEL.matcher(text).replaceFirst("").strip();
    }

    /**
     * 只用于识别 SQL 词法区域外残留的 Markdown，不解析语法或授权。
     * 字符串/标识符/dollar quote/嵌套注释中的反引号属于正文，必须原样保留。
     * 未闭合的 SQL 由下游解析器/数据库拒绝，不能在这里自动补引号或修语句。
     */
    private static void rejectUnconsumedFences(String sql) {
        char quote = 0;
        boolean escapeString = false;
        boolean lineComment = false;
        int blockDepth = 0;
        String dollarTag = null;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n' || c == '\r') lineComment = false;
            } else if (blockDepth > 0) {
                if (c == '/' && next == '*') { blockDepth++; i++; }
                else if (c == '*' && next == '/') { blockDepth--; i++; }
            } else if (dollarTag != null) {
                if (sql.startsWith(dollarTag, i)) {
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
            } else if (quote != 0) {
                if (escapeString && c == '\\') i++;
                else if (c == quote) {
                    if (next == quote) i++;
                    else quote = 0;
                }
            } else if (c == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockDepth = 1;
                i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
                escapeString = c == '\'' && i > 0
                        && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !identifierPart(sql.charAt(i - 2)));
            } else if (c == '$' && (i == 0 || !identifierPart(sql.charAt(i - 1)))) {
                var tag = DOLLAR_QUOTE.matcher(sql).region(i, sql.length());
                if (tag.lookingAt()) {
                    dollarTag = tag.group();
                    i += dollarTag.length() - 1;
                }
            } else if (sql.startsWith("```", i)) {
                throw new GenerationException(GenerationException.Reason.UNPARSEABLE,
                        "模型输出含非外层代码围栏；只接受一段 SQL，不截取多个代码块或围栏外说明");
            }
        }
    }

    private static boolean identifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
