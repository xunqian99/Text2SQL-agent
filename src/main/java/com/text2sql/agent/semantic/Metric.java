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
 * @param requiredPhrases 命中别名后还必须出现的限定词；为空表示不额外限制
 * @param excludedPhrases 出现这些词时不命中该指标，用于处理同一术语的不同业务口径
 * @param outputRule 输出列、单位、舍入和排序要求
 * @param queryPattern 复杂指标的推荐查询结构；简单指标为空
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
        List<String> requiredPhrases,
        List<String> excludedPhrases,
        String outputRule,
        String queryPattern) {

    public Metric {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        tables = tables == null ? List.of() : List.copyOf(tables);
        requiredPhrases = requiredPhrases == null ? List.of() : List.copyOf(requiredPhrases);
        excludedPhrases = excludedPhrases == null ? List.of() : List.copyOf(excludedPhrases);
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
            // 例外说明是必要的：同一条指标在不同场景下口径可能不同。
            // 实测例子：low_score_rate 在全站统计（T6-009 按州看低分率）时要排除
            // 取消/不可用订单；但 T6-024 问的是「退款订单的低分率」，
            // 那个查询已经把范围限定在退款订单集合内，再叠一层过滤就错了。
            //
            // 不把这句写进 YAML 的每条 filter 里，是因为它对所有指标都成立，
            // 写一遍比写 12 遍更不容易漏。
            sb.append("    —— 例外：若题目已把范围限定在某个子集内（如「仅退款订单」"
                    + "「仅某类商品」），则不要重复附加这个过滤。\n");
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
