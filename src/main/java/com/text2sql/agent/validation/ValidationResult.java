package com.text2sql.agent.validation;

import java.util.List;

/**
 * 校验结果。
 *
 * <p>三个字段各有用途，缺一不可：
 *
 * <ul>
 *   <li>{@code sql} —— 校验后**实际要执行**的 SQL。注意它可能不等于输入：
 *       强制补 LIMIT 会改写语句。把改写结果显式返回，而不是让校验器偷偷改对象，
 *       是为了让「用户看到的 SQL」和「真正执行的 SQL」永远一致。
 *       否则排查问题时会出现「日志里的 SQL 和库里跑的不是一条」这种噩梦。</li>
 *   <li>{@code violations} —— 违规清单，不是布尔值。面试官问「你的拦截逻辑
 *       怎么设计」时，能说出「返回全部违规项而不是遇到第一个就返回」是加分项：
 *       模型一次犯两个错时，一次性告诉它能少一轮重试。</li>
 *   <li>{@code rewritten} —— 是否发生过改写。评估报告里要单独统计，
 *       因为「靠补 LIMIT 救回来的问题」和「模型本来就写对」是两种不同的成功。</li>
 * </ul>
 */
public record ValidationResult(String sql, List<Violation> violations, boolean rewritten) {

    /** 违规类型。分类的意义在于：不同的违规对应不同的修复手段。 */
    public enum Code {
        /** SQL 语法都解析不了，通常是模型输出被截断或掺了杂质。 */
        PARSE_ERROR,
        /** 不是 SELECT 语句（INSERT/UPDATE/DELETE/DROP/...）。 */
        NOT_SELECT,
        /** 使用了禁止的函数。 */
        FORBIDDEN_FUNCTION,
        /** 多语句（用分号拼接的第二条语句）。注入攻击的典型形态。 */
        MULTIPLE_STATEMENTS,
        /** 没有 LIMIT，且配置为拒绝模式。 */
        MISSING_LIMIT,
        /** 引用了 schema 里不存在的表。 */
        UNKNOWN_TABLE,
        /** 引用了 schema 里不存在的列。 */
        UNKNOWN_COLUMN,
        /** 空 SQL。 */
        EMPTY
    }

    public record Violation(Code code, String message) {
    }

    public static ValidationResult ok(String sql) {
        return new ValidationResult(sql, List.of(), false);
    }

    public static ValidationResult rewritten(String sql, List<Violation> violations) {
        return new ValidationResult(sql, List.copyOf(violations), true);
    }

    public boolean valid() {
        return violations.isEmpty();
    }

    /** 给重试提示用的可读描述。阶段 5 会把它回灌给模型。 */
    public String describe() {
        return violations.stream()
                .map(v -> "[" + v.code() + "] " + v.message())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }
}
