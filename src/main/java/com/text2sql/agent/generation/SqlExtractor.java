package com.text2sql.agent.generation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型的自由文本里抠出 SQL。
 *
 * <p>为什么需要这一步：prompt 里要求「只输出 SQL」，但实测模型经常附带解释、
 * 加 markdown 围栏、或者在 SQL 后面补一句"以上 SQL 已排除取消订单"。
 * 这些都是**合理的模型行为**，不该被判为失败。所以容错放在这里，
 * 而不是靠反复调 prompt 去逼模型——调 prompt 是碰运气，写解析是确定性工程。
 *
 * <p>被否掉的方案：用正则匹配 `SELECT ... ;`。看着简单，但 SQL 里可能
 * 包含字符串字面量、子查询、CTE，正则很容易在第一个分号处截断，
 * 或者把注释里的内容当成 SQL。这里改为「先剥围栏，再取第一个语句块」。
 */
public final class SqlExtractor {

    private static final Pattern FENCED = Pattern.compile("```(?:sql|postgresql|SQL)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern LEADING_LABEL = Pattern.compile("^\\s*(?:SQL|sql)\\s*[:：]\\s*", Pattern.MULTILINE);

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

        // 1) 有代码围栏就取围栏内的内容。只取第一块：后面通常是解释文字或示例。
        Matcher m = FENCED.matcher(text);
        if (m.find()) {
            text = m.group(1);
        }

        text = LEADING_LABEL.matcher(text.strip()).replaceFirst("");
        text = text.strip();

        if (text.isEmpty()) {
            throw new GenerationException(GenerationException.Reason.UNPARSEABLE, "模型返回内容里没有 SQL");
        }

        // 2) 如果模型在 SQL 后面附了解释，砍掉解释。判据是出现空行且后面不再有 SQL 关键字。
        String trimmed = cutTrailingProse(text);
        if (trimmed.isBlank()) {
            throw new GenerationException(GenerationException.Reason.UNPARSEABLE, "模型返回内容里没有 SQL");
        }
        return trimmed.strip();
    }

    /**
     * 砍掉 SQL 之后的中文解释。
     *
     * <p>策略保守：只在「空行」处分段，且只保留仍然以 SQL 关键字开头、
     * 或明显是 SQL 续行（以 , ) ; 或关键字开头）的段落。宁可少砍，
     * 也不要把合法的 CTE 尾部砍掉——多留一点解释顶多让校验器报错，
     * 砍错了会静默生成一条语义不同的 SQL，那更糟。
     */
    private static String cutTrailingProse(String text) {
        String[] blocks = text.split("\\n\\s*\\n");
        StringBuilder kept = new StringBuilder();
        for (String block : blocks) {
            if (kept.length() > 0 && !looksLikeSqlContinuation(block)) {
                break;
            }
            if (kept.length() > 0) {
                kept.append("\n\n");
            }
            kept.append(block.strip());
        }
        return kept.toString();
    }

    private static boolean looksLikeSqlContinuation(String block) {
        String s = block.stripLeading().toUpperCase();
        if (s.isEmpty()) {
            return false;
        }
        for (String kw : new String[]{"SELECT", "WITH", "FROM", "WHERE", "GROUP", "ORDER", "HAVING",
                "LIMIT", "JOIN", "LEFT", "RIGHT", "INNER", "FULL", "CROSS", "UNION", "ON", "AND", "OR"}) {
            if (s.startsWith(kw)) {
                return true;
            }
        }
        char first = block.stripLeading().charAt(0);
        return first == ',' || first == ')' || first == ';';
    }
}
