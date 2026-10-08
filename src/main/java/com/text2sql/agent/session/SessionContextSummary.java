package com.text2sql.agent.session;

import java.util.*;

/**
 * 长会话上下文偏好与历史约束摘要对象（Session Context Summary）。
 *
 * <p>解决多轮问答的核心痛点：
 * <ul>
 *   <li>历史轮次线性增长导致 Prompt 上下文爆炸与 Token 激增（$O(N)$ 成本）；</li>
 *   <li>若简单丢弃早期轮次（Hard LRU Eviction），用户在首轮声明的全局约束（如“只看有效订单”、“排除运费”、“圣保罗州”）会被遗忘。</li>
 * </ul>
 *
 * <p>本对象记录滑动窗口之外被淘汰轮次的凝练摘要，将长尾会话开销严格压缩到 $O(1)$，
 * 同时保留全局业务口径偏好。
 *
 * @param accumulatedConstraints 已提取的长期过滤约束与口径偏好（如 "排除已取消订单 (order_status NOT IN ('canceled', 'unavailable'))"）
 * @param accumulatedTables      历史上涉及过的业务实体表集合（如 ["orders", "order_items"]）
 * @param condensedSummaryText   格式化后的偏好描述文本（注入 Prompt 用）
 * @param evictedTurnCount       已压缩淘汰的早期轮次数
 */
public record SessionContextSummary(
        Set<String> accumulatedConstraints,
        Set<String> accumulatedTables,
        String condensedSummaryText,
        int evictedTurnCount) {

    public static SessionContextSummary empty() {
        return new SessionContextSummary(Set.of(), Set.of(), "", 0);
    }

    public boolean isEmpty() {
        return evictedTurnCount == 0 && (condensedSummaryText == null || condensedSummaryText.isBlank());
    }
}
