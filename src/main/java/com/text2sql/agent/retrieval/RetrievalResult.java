package com.text2sql.agent.retrieval;

import java.util.List;
import java.util.Set;

/**
 * 一次 schema 检索的结果，附带**为什么**这么选。
 *
 * <p><b>为什么要把证据留下来，而不是只返回表名列表</b>
 *
 * <p>只返回 {@code [orders, customers, regions]} 的话，召回率低时你只知道
 * 「漏了」，不知道是「词典没收录这个词」还是「分数被别的表挤掉了」还是
 * 「关系扩展没走到」。这三种原因的修法完全不同：补词典、调权重、改图。
 * 留下证据，问题就从「猜」变成「看」。
 *
 * <p>这也是这个项目「可解释」这条主线在检索层的落地：检索不是一个黑盒，
 * 它的每个决定都能被打印出来给人看。面试时可以直接展示
 * 「问『销量最高的 10 个商品』，我命中了 order_items.price，然后沿
 * order_items.product_id -> products.product_id 扩展出 products」。
 *
 * @param tableNames     最终选中的表名（保序：按分数从高到低）
 * @param scores         表名 -> 最终分数
 * @param evidence       表名 -> 命中证据（命中了哪个词、来自哪张表/列）
 * @param expandedTables 靠关系扩展进来（而非直接命中）的表名
 * @param fellBack       是否因为一个词都没命中而回退到全量 schema
 */
public record RetrievalResult(
        List<String> tableNames,
        java.util.Map<String, Double> scores,
        java.util.Map<String, List<String>> evidence,
        Set<String> expandedTables,
        boolean fellBack) {

    public RetrievalResult {
        tableNames = List.copyOf(tableNames);
        scores = java.util.Map.copyOf(scores);
        evidence = java.util.Map.copyOf(evidence);
        expandedTables = Set.copyOf(expandedTables);
    }

    /** 给日志用的一行摘要，避免把整个结构打进日志。 */
    public String summary() {
        return "选中 %d 张表%s：%s".formatted(
                tableNames.size(),
                fellBack ? "（回退全量）" : "",
                tableNames);
    }
}
