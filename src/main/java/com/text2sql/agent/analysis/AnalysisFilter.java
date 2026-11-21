package com.text2sql.agent.analysis;

import java.util.Objects;

/**
 * 分析上下文中的结构化过滤条件。
 *
 * <p>过滤条件不保存为自然语言片段，避免下钻时由前端拼接文本造成口径漂移。
 */
public record AnalysisFilter(String field, Operator operator, String value,
                              AnalysisContext.Source source) {

    public AnalysisFilter {
        field = requireText(field, "filter field");
        operator = Objects.requireNonNull(operator, "filter operator must not be null");
        value = requireText(value, "filter value");
        source = Objects.requireNonNull(source, "filter source must not be null");
    }

    public static AnalysisFilter eq(String field, String value, AnalysisContext.Source source) {
        return new AnalysisFilter(field, Operator.EQ, value, source);
    }

    public enum Operator {
        EQ, NE, IN, GT, GTE, LT, LTE
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
