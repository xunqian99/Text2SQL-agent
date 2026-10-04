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
 * @param tables      这个指标依赖哪些表，检索时用来判断是否适用
 * @param notes       口径说明：为什么这么定义、边界在哪
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Metric(
        String name,
        List<String> aliases,
        String description,
        String expression,
        List<String> tables,
        String notes) {

    public Metric {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        tables = tables == null ? List.of() : List.copyOf(tables);
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
        if (notes != null && !notes.isBlank()) {
            // notes 是多行文本，缩进两格保持可读性。
            for (String line : notes.strip().split("\n")) {
                sb.append("  ").append(line.strip()).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }
}
