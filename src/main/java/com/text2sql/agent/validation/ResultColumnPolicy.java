package com.text2sql.agent.validation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative output-label hints, not SQL lineage or proof of a metric's domain.
 * Index is the identity: dimensions and unrelated measures never inherit a question-wide rule.
 */
public record ResultColumnPolicy(int index, String label, Kind kind, Source source, String rule) {
    public static final String VERSION = "output-label-v1";
    public enum Kind { GROWTH, SIGNED, BOUNDED_RATIO, NON_NEGATIVE, UNKNOWN }
    public enum Source { OUTPUT_LABEL, NONE }

    private static final Set<String> GROWTH = Set.of("growth", "yoy", "mom", "qoq", "wow", "dod", "cagr",
            "increase", "decrease", "increment", "decrement");
    private static final Set<String> SIGNED = Set.of("net", "profit", "loss", "balance", "delta", "diff",
            "difference", "change", "variance", "adjustment");
    private static final Set<String> RATIOS = Set.of("rate", "ratio", "pct", "percent", "percentage", "share", "proportion");
    private static final Set<String> NON_NEGATIVE = Set.of("gmv", "amount", "price", "fee", "fees", "cost",
            "revenue", "sales", "quantity", "qty", "count", "cnt");

    public static List<ResultColumnPolicy> classify(List<String> labels) {
        if (labels == null) return List.of();
        List<ResultColumnPolicy> policies = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) policies.add(classify(index, labels.get(index)));
        return List.copyOf(policies);
    }

    private static ResultColumnPolicy classify(int index, String label) {
        String original = label == null ? "" : label.strip();
        String lower = original.toLowerCase(Locale.ROOT);
        // Respect word boundaries (separate_value is not a rate). Also accept normal camelCase labels.
        String normalized = original.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        List<String> words = Arrays.stream(normalized.split("[^a-z0-9]+"))
                .filter(word -> !word.isEmpty()).toList();
        String compact = lower.replaceAll("[^a-z0-9]", "");
        // Signed/growth hints deliberately precede money/count hints in mixed labels.
        if (has(words, GROWTH) || contains(lower, "同比", "环比", "增长", "增幅", "增速")
                || compact.matches("(?:gmv|amount|revenue|sales|orders?|count|cnt)?(?:yoy|mom|qoq|wow|dod|cagr)(?:pct|percent|percentage|rate|ratio|growth)?")) {
            return known(index, original, Kind.GROWTH, "GROWTH_LABEL");
        }
        if (has(words, SIGNED) || contains(lower, "差额", "差值", "差异", "变化", "增减", "净", "利润", "盈亏", "余额")) {
            return known(index, original, Kind.SIGNED, "SIGNED_LABEL");
        }
        if (has(words, RATIOS) || contains(lower, "占比", "比例", "比率", "率")) {
            return known(index, original, Kind.BOUNDED_RATIO, "RATIO_LABEL");
        }
        if (has(words, NON_NEGATIVE) || words.stream().anyMatch(word -> word.matches("gmv[0-9]{4}"))
                || contains(lower, "金额", "销售额", "总额", "费用", "单价", "价格", "数量", "笔数", "人数", "订单数", "客户数", "退款额")) {
            return known(index, original, Kind.NON_NEGATIVE, "NON_NEGATIVE_LABEL");
        }
        return new ResultColumnPolicy(index, original, Kind.UNKNOWN, Source.NONE, "UNRECOGNIZED_LABEL");
    }

    private static ResultColumnPolicy known(int index, String label, Kind kind, String rule) {
        return new ResultColumnPolicy(index, label, kind, Source.OUTPUT_LABEL, rule);
    }
    private static boolean has(List<String> words, Set<String> terms) {
        return words.stream().anyMatch(terms::contains);
    }
    private static boolean contains(String text, String... terms) {
        return Arrays.stream(terms).anyMatch(text::contains);
    }
}
