package com.text2sql.agent.semantic;

import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.ValueRetriever.ValueEntry;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded, metadata-backed default layout; abstains instead of guessing a general query intent. */
public record CountComparisonContract(Decision decision, List<Binding> bindings) {
    public enum Decision { MATCHED, NO_COUNT_INTENT, EXPLICIT_OR_GROUP_LAYOUT, UNSUPPORTED_COMPOSITION,
        INSUFFICIENT_VALUES, AMBIGUOUS_FIELDS, AMBIGUOUS_VALUE_BINDING, TOO_MANY_VALUES }
    public enum Source { GLOSSARY_LABEL, PHYSICAL_VALUE }
    public record Binding(String matchedWord, String table, String column, String value, Source source, int start, int end) { }

    private static final Pattern COUNT = Pattern.compile("多少[笔条个]|数量|订单数|记录数|支付数|发货单数");
    private static final Pattern ENUMERATION = Pattern.compile("和|与|、|及|分别|各");
    private static final Pattern EXPLICIT_OR_GROUP = Pattern.compile("横表|纵表|[一二三四五六七八九十两\\d]+列|每.{0,8}[行列]|分组|分布|每[个种家]|按(?!此顺序|顺序)");
    private static final Pattern UNSUPPORTED = Pattern.compile("率|比例|占比|百分|平均|金额|销售额|GMV|增长|相差|差值|差额|不要|不是|排除|不含|除外|之外|未|非|不等|\\d{4}|季度|每[年月周日天]|最近|同期|同比|环比", Pattern.CASE_INSENSITIVE);

    public CountComparisonContract { bindings = List.copyOf(bindings); }

    public static CountComparisonContract resolve(String question, List<ValueEntry> entries, List<SchemaContext.Table> tables) {
        return resolve(question, entries, tables, binding -> true);
    }

    /** Production label bindings must retain accepted occurrence evidence; explicit physical codes stay explicit. */
    public static CountComparisonContract resolve(String question, List<ValueEntry> entries, List<SchemaContext.Table> tables,
            java.util.function.Predicate<Binding> acceptedLabel) {
        if (question == null || !COUNT.matcher(question).find() || !ENUMERATION.matcher(question).find())
            return new CountComparisonContract(Decision.NO_COUNT_INTENT, List.of());
        if (EXPLICIT_OR_GROUP.matcher(question).find())
            return new CountComparisonContract(Decision.EXPLICIT_OR_GROUP_LAYOUT, List.of());
        if (UNSUPPORTED.matcher(question).find())
            return new CountComparisonContract(Decision.UNSUPPORTED_COMPOSITION, List.of());
        Set<String> availableFields = new HashSet<>();
        for (var table : tables) for (var column : table.columns()) availableFields.add(table.name() + "." + column.name());
        Map<String, Map<String, Binding>> groups = new TreeMap<>();
        Comparator<Binding> order = Comparator.comparingInt(Binding::start)
                .thenComparing(Comparator.comparingInt((Binding b) -> b.end()-b.start()).reversed())
                .thenComparing(b -> b.source().name());
        for (var entry : entries) {
            String field = entry.table() + "." + entry.column();
            if (!availableFields.contains(field)) continue;
            for (var source : Source.values()) {
                String term = source == Source.GLOSSARY_LABEL ? entry.alias() : entry.physicalValue();
                if (term == null || term.isBlank()) continue;
                if (source == Source.PHYSICAL_VALUE && !term.matches("[A-Za-z][A-Za-z0-9_]{2,}")) continue;
                String pattern = Pattern.quote(term);
                if (source == Source.PHYSICAL_VALUE) pattern = "(?<![A-Za-z0-9_])" + pattern + "(?![A-Za-z0-9_])";
                var matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(question);
                while (matcher.find()) {
                    var binding = new Binding(question.substring(matcher.start(), matcher.end()), entry.table(), entry.column(),
                            entry.physicalValue(), source, matcher.start(), matcher.end());
                    if (source == Source.GLOSSARY_LABEL && !acceptedLabel.test(binding)) continue;
                    groups.computeIfAbsent(field, ignored -> new TreeMap<>()).merge(entry.physicalValue(), binding,
                            (a,b) -> order.compare(a,b) <= 0 ? a : b);
                }
            }
        }
        var candidates = groups.values().stream().filter(g -> g.size() >= 2).toList();
        if (candidates.isEmpty()) return new CountComparisonContract(Decision.INSUFFICIENT_VALUES, List.of());
        List<Binding> evidence = candidates.stream().flatMap(g -> g.values().stream()).sorted(order
                .thenComparing(Binding::table).thenComparing(Binding::column).thenComparing(Binding::value)).toList();
        if (candidates.size() != 1) return new CountComparisonContract(Decision.AMBIGUOUS_FIELDS, evidence);
        if (evidence.size() > 8) return new CountComparisonContract(Decision.TOO_MANY_VALUES, evidence);
        for (int i=1; i<evidence.size(); i++) {
            if (evidence.get(i).start() < evidence.get(i-1).end())
                return new CountComparisonContract(Decision.AMBIGUOUS_VALUE_BINDING, evidence);
        }
        return new CountComparisonContract(Decision.MATCHED, evidence);
    }

    public String render() {
        if (decision != Decision.MATCHED) return "";
        StringBuilder result = new StringBuilder("\n\nREQUEST_RESULT_CONTRACT named_filter_counts_v1\n");
        result.append("  本请求未指定分组/形状，按具名计数默认契约返回一行、恰好").append(bindings.size())
                .append("个计数列；列顺序严格按以下原文出现顺序。不要返回枚举值列或GROUP BY枚举字段。\n");
        for (int i=0; i<bindings.size(); i++) {
            var binding = bindings.get(i);
            result.append("  第").append(i+1).append("列：COUNT(*) FILTER (WHERE ")
                    .append(binding.table()).append('.').append(binding.column()).append(" = '")
                    .append(binding.value().replace("'", "''")).append("')")
                    .append("；依据「").append(binding.matchedWord()).append("」[").append(binding.start()).append(',')
                    .append(binding.end()).append(")，来源=").append(binding.source()).append('\n');
        }
        return result.append("  按查询实际表别名改写限定符；各条件独立计数，空子集/空输入计0。此契约不改变过滤范围、事实粒度或用户显式要求。\n").toString().stripTrailing();
    }
}
