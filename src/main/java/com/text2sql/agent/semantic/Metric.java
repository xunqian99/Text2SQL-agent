package com.text2sql.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 一个业务指标的定义，对应 {@code schema/metrics.yml} 里的一条。
 *
 * <p><b>为什么指标要单独建一层，而不是塞进 glossary.yml</b>
 *
 * <p>两者回答的问题不同、变更节奏也不同：
 *
 * <ul>
 *   <li>{@code glossary} 回答「用户说的词对应哪张表、哪一列」——**结构知识**。
 *       数据库改表结构时才需要改。</li>
 *   <li>{@code metrics} 回答「这个业务指标该怎么算」——**口径知识**。
 *       业务定义变了就要改，与表结构无关。</li>
 * </ul>
 *
 * <p>混在一起会让两件事互相干扰：改一个口径定义却要重新审阅整份 schema 词典。
 *
 * @param name        指标的唯一标识，如 {@code repeat_rate}
 * @param aliases     用户可能怎么问，如「复购率」「回购率」
 * @param description 一句话说明这个指标是什么
 * @param expression  可以直接抄进 SQL 的片段。**不是伪代码**——模型要照抄的
 * @param filter      必须附加的过滤条件（如有效订单），会渲染成独立一行
 * @param tables      这个指标依赖哪些表，检索时用来判断是否适用
 * @param notes       口径说明：为什么这么定义、边界在哪
 * @param outputRule 输出列、单位、舍入和排序要求
 * @param queryPattern 复杂指标的推荐查询结构；简单指标为空
 * @param requiredContextAny 别名命中后至少还须命中一个上下文词；空列表不限制
 * @param excludedContextAny 任一词命中即排除该指标，除非显式区间命中preferredContextAny
 * @param preferredContextAny 显式区间覆盖一般上下文排除；不绕过别名、required或依赖表检查
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Metric(
        String name,
        List<String> aliases,
        String description,
        String expression,
        String filter,
        List<String> tables,
        String notes,
        String outputRule,
        String queryPattern,
        OutputUnit outputUnit,
        Integer outputScale,
        List<String> requiredContextAny,
        List<String> excludedContextAny,
        List<String> preferredContextAny) {

    /** Unit belongs to this metric, never to every column in the question. */
    public enum OutputUnit { PERCENT, DECIMAL_RATIO }

    public Metric(String name, List<String> aliases, String description, String expression,
                  String filter, List<String> tables, String notes, String outputRule, String queryPattern) {
        this(name, aliases, description, expression, filter, tables, notes, outputRule, queryPattern, null, null, null, null, null);
    }

    public Metric(String name, List<String> aliases, String description, String expression,
                  String filter, List<String> tables, String notes, String outputRule, String queryPattern,
                  OutputUnit outputUnit, Integer outputScale) {
        this(name, aliases, description, expression, filter, tables, notes, outputRule, queryPattern,
                outputUnit, outputScale, null, null, null);
    }

    public Metric(String name, List<String> aliases, String description, String expression,
                  String filter, List<String> tables, String notes, String outputRule, String queryPattern,
                  OutputUnit outputUnit, Integer outputScale,
                  List<String> requiredContextAny, List<String> excludedContextAny) {
        this(name, aliases, description, expression, filter, tables, notes, outputRule, queryPattern,
                outputUnit, outputScale, requiredContextAny, excludedContextAny, null);
    }

    public Metric {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        tables = tables == null ? List.of() : List.copyOf(tables);
        requiredContextAny = checkedContext(requiredContextAny);
        excludedContextAny = checkedContext(excludedContextAny);
        preferredContextAny = checkedContext(preferredContextAny);
        if ((outputUnit == null) != (outputScale == null)
                || (outputScale != null && (outputScale < 0 || outputScale > 8))) {
            throw new IllegalArgumentException("Metric output unit and scale must be defined together, scale 0..8");
        }
    }

    private static List<String> checkedContext(List<String> values) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(v -> v == null || v.isBlank())) {
            throw new IllegalArgumentException("Metric context terms must not be blank");
        }
        return List.copyOf(values);
    }

    /**
     * 渲染成给模型看的一段文本。
     *
     * <p><b>为什么要把 notes 也带上，而不是只给表达式</b>
     *
     * <p>表达式只回答「怎么写」，notes 回答「为什么这么写、什么情况下不适用」。
     * 实测的失败模式里，模型经常写对语法但用错分母（比如准时率把未送达订单
     * 也算进分母）——那类错误只有 notes 能防住。
     *
     * <p>代价是 token：一条指标约 100–200 字符。所以只注入**命中的**指标，
     * 不做全量注入——那会把 20 条指标约 3KB 全塞进每次请求。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("METRIC ").append(name).append("  -- ").append(description).append('\n');
        sb.append("  表达式: ").append(expression.strip()).append('\n');
        if (outputUnit != null) {
            sb.append("  默认输出单位: ").append(outputUnit).append("；保留 ").append(outputScale).append(" 位小数。\n");
            sb.append(outputUnit == OutputUnit.PERCENT
                    ? "    百分数按100倍比值输出纯数值，例如比值0.25输出25，不附加%字符；增长率可为负或超过100。\n"
                    : "    小数比率按原始比值输出纯数值，例如比值0.25输出0.25，不额外乘100。\n");
            sb.append("    此单位仅约束该指标，不影响同一结果的金额/数量/维度列；用户明确指定单位时覆盖默认值并同步转换表达式、精度和列别名，示例结构不能覆盖用户要求。\n");
        }
        // 过滤条件必须单独成行，而不是揉进表达式片段。
        //
        // 【这条是实测踩出来的】第一版把过滤条件省了，结果 T6 层 12 条全错：
        // T6-008 动销率，gold 是 99.33（分子排除了取消/不可用订单的商品），
        // 模型算出 100.00——因为它只看到 SUM(...) 这样的表达式片段，
        // 不知道还要加 WHERE 条件。而 25 道 T6 题里 17 道都需要这个过滤。
        //
        // 为什么不把 WHERE 塞进 expression 片段：表达式是「算哪个值」，
        // 过滤是「算哪些行」，两者混在一个字符串里，模型很容易把 WHERE
        // 放到错误的层级（比如放进聚合函数内部）。分开写，各归其位。
        if (filter != null && !filter.isBlank()) {
            sb.append("  过滤条件（默认附加）: ").append(filter.strip()).append('\n');
            // 例外必须来自当前指标或显式覆盖，不能由“已经有其他过滤”推导。
            // 例如退款集合的低分率有独立约定，但类目/年份筛选并不替代有效订单条件。
            sb.append("    —— 例外：只有用户明确覆盖此过滤，或当前指标说明明确规定该场景例外时才省略。"
                    + "类目、年份等其他维度限定不能自动取消此过滤；与它共同限定事实集合。\n");
        }
        if (notes != null && !notes.isBlank()) {
            // notes 是多行文本，缩进两格保持可读性。
            for (String line : notes.strip().split("\n")) {
                sb.append("  ").append(line.strip()).append('\n');
            }
        }
        if (outputRule != null && !outputRule.isBlank()) {
            sb.append("  输出约束（必须遵守）: ").append(outputRule.strip()).append('\n');
        }
        if (queryPattern != null && !queryPattern.isBlank()) {
            sb.append("  推荐查询结构（按此结构改写，不要自行发明更慢的等价查询）:\n");
            for (String line : queryPattern.strip().split("\\n")) {
                sb.append("    ").append(line.strip()).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }
}
