package com.text2sql.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长会话滑动窗口上下文压缩器（Session Context Compressor）。
 *
 * <p><b>核心架构与算法机制</b>：
 * <ol>
 *   <li><b>滑动窗口裁剪（Sliding Window Pruning）</b>：保持最近 $K$ 轮（默认 3 轮）完整上下文，支持精确指代消解；</li>
 *   <li><b>实体与偏好提取（Constraint & Entity Extraction）</b>：当早期轮次滑出窗口时，解析其 SQL 与提问，
 *       自动抽取“状态过滤条件”、“时间窗口”、“地域偏好”以及“业务指标口径偏好”；</li>
 *   <li><b>常数级压缩（$O(1)$ Token Bounding）</b>：将早期历史增量凝练为结构化摘要文本（通常仅 40~70 tokens），
 *       替换掉长达数百 tokens 的原始问答历史，杜绝长会话 Token 膨胀。</li>
 * </ol>
 */
@Component
public class SessionContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(SessionContextCompressor.class);

    private static final Pattern ORDER_STATUS_FILTER = Pattern.compile(
            "(?i)order_status\\s+(not\\s+in\\s*\\([^)]+\\)|=\\s*'[^']+')");

    private static final Pattern STATE_FILTER = Pattern.compile(
            "(?i)(customer_state|state)\\s*=\\s*'([A-Z]{2})'");

    private static final Pattern YEAR_FILTER = Pattern.compile(
            "(?i)(20\\d{2})\\s*(年|[-/])?");

    private static final Pattern TABLE_EXTRACTOR = Pattern.compile(
            "(?i)\\b(from|join)\\s+([a-z_][a-z0-9_]*)");

    private static final List<String> METRIC_KEYWORDS = List.of(
            "GMV", "销售额", "客单价", "退款率", "动销率", "净额", "复购率", "运费");

    /**
     * 将滑出窗口的早期轮次增量压缩至会话摘要中。
     *
     * @param existingCurrent 当前已有摘要（若无则传 null 或 empty）
     * @param evictedTurn     滑出滑动窗口的早期轮次
     * @return 更新后的上下文摘要对象
     */
    public SessionContextSummary compress(SessionContextSummary existingCurrent, ConversationTurn evictedTurn) {
        if (evictedTurn == null) {
            return existingCurrent != null ? existingCurrent : SessionContextSummary.empty();
        }

        Set<String> constraints = new LinkedHashSet<>(
                existingCurrent != null ? existingCurrent.accumulatedConstraints() : Set.of());
        Set<String> tables = new LinkedHashSet<>(
                existingCurrent != null ? existingCurrent.accumulatedTables() : Set.of());
        int count = existingCurrent != null ? existingCurrent.evictedTurnCount() + 1 : 1;

        String q = (evictedTurn.rewrittenQuestion() != null && !evictedTurn.rewrittenQuestion().isBlank())
                ? evictedTurn.rewrittenQuestion()
                : evictedTurn.question();
        String sql = evictedTurn.sql();

        // 1. 抽取 SQL 中的关键全局约束
        if (sql != null && !sql.isBlank()) {
            Matcher statusMatcher = ORDER_STATUS_FILTER.matcher(sql);
            if (statusMatcher.find()) {
                String matched = statusMatcher.group(0);
                if (matched.toLowerCase(Locale.ROOT).contains("not in")) {
                    constraints.add("状态约束：有效订单（排除取消/不可用订单: " + matched + "）");
                } else {
                    constraints.add("状态约束：" + matched);
                }
            }

            Matcher stateMatcher = STATE_FILTER.matcher(sql);
            if (stateMatcher.find()) {
                constraints.add("地区偏好：" + stateMatcher.group(2) + " 州");
            }

            Matcher tblMatcher = TABLE_EXTRACTOR.matcher(sql);
            while (tblMatcher.find()) {
                String tbl = tblMatcher.group(2).toLowerCase(Locale.ROOT);
                if (!tbl.startsWith("pg_") && !tbl.equals("select") && !tbl.equals("where")) {
                    tables.add(tbl);
                }
            }
        }

        // 2. 抽取提问中的业务指标口径偏好与年份
        if (q != null && !q.isBlank()) {
            Matcher yearMatcher = YEAR_FILTER.matcher(q);
            if (yearMatcher.find()) {
                constraints.add("时间偏好：基准年份 " + yearMatcher.group(1));
            }

            for (String kw : METRIC_KEYWORDS) {
                if (q.contains(kw)) {
                    constraints.add("业务指标偏好：关注 " + kw);
                }
            }
        }

        // 3. 构建凝练摘要文本
        StringBuilder sb = new StringBuilder();
        sb.append("【会话早期历史偏好与约束摘要（已压缩 ").append(count).append(" 轮）】\n");
        if (!constraints.isEmpty()) {
            for (String c : constraints) {
                sb.append("• ").append(c).append("\n");
            }
        }
        if (!tables.isEmpty()) {
            sb.append("• 涉及实体表: ").append(String.join(", ", tables)).append("\n");
        }

        String summaryText = sb.toString().trim();
        log.info("多轮会话早期轮次已压缩入摘要（累计已压缩 {} 轮，保留约束项 {} 条）", count, constraints.size());
        return new SessionContextSummary(constraints, tables, summaryText, count);
    }
}
