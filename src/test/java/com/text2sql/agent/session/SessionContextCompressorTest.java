package com.text2sql.agent.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContextCompressorTest {

    private final SessionContextCompressor compressor = new SessionContextCompressor();

    @Test
    @DisplayName("压缩单轮包含状态过滤与州偏好的早期轮次")
    void testCompressSingleTurnWithSqlConstraints() {
        ConversationTurn turn = ConversationTurn.of(
                1,
                "查询2018年圣保罗州的有效订单总金额",
                "2018年圣保罗州的有效订单总金额是多少？",
                "SELECT SUM(oi.price) FROM orders o JOIN customers c ON c.customer_id = o.customer_id " +
                        "JOIN order_items oi ON oi.order_id = o.order_id " +
                        "WHERE o.order_status NOT IN ('canceled', 'unavailable') AND c.customer_state = 'SP'",
                "SUCCESS",
                "1 行数据"
        );

        SessionContextSummary summary = compressor.compress(null, turn);

        assertThat(summary).isNotNull();
        assertThat(summary.evictedTurnCount()).isEqualTo(1);
        assertThat(summary.accumulatedConstraints())
                .anyMatch(c -> c.contains("有效订单") && c.contains("order_status NOT IN"))
                .anyMatch(c -> c.contains("SP 州"))
                .anyMatch(c -> c.contains("2018"));
        assertThat(summary.accumulatedTables()).contains("orders", "customers", "order_items");
        assertThat(summary.condensedSummaryText()).contains("会话早期历史偏好与约束摘要");
    }

    @Test
    @DisplayName("连续多轮滚动压缩时增量合并全局偏好")
    void testIncrementalCompressionAcrossMultipleTurns() {
        // 第一轮：声明状态与州
        ConversationTurn turn1 = ConversationTurn.of(
                1,
                "只看有效订单且客户在RJ州的",
                "只看有效订单且客户在RJ州的订单数",
                "SELECT COUNT(*) FROM orders o JOIN customers c ON c.customer_id = o.customer_id " +
                        "WHERE o.order_status NOT IN ('canceled') AND c.customer_state = 'RJ'",
                "SUCCESS",
                "1 行数据"
        );

        // 第二轮：涉及业务指标 GMV 与 2017 年
        ConversationTurn turn2 = ConversationTurn.of(
                2,
                "那2017年的GMV是多少？",
                "2017年的GMV是多少？",
                "SELECT SUM(price) FROM order_items",
                "SUCCESS",
                "1 行数据"
        );

        SessionContextSummary s1 = compressor.compress(null, turn1);
        SessionContextSummary s2 = compressor.compress(s1, turn2);

        assertThat(s2.evictedTurnCount()).isEqualTo(2);
        assertThat(s2.accumulatedConstraints())
                .anyMatch(c -> c.contains("RJ 州"))
                .anyMatch(c -> c.contains("2017"))
                .anyMatch(c -> c.contains("GMV"));
        assertThat(s2.accumulatedTables()).contains("orders", "customers", "order_items");
    }

    @Test
    @DisplayName("长会话下 Token 开销压缩量化（验证 O(1) 紧凑性）")
    void testTokenCompressionRatio() {
        // 模拟 5 个被淘汰的历史轮次
        SessionContextSummary summary = SessionContextSummary.empty();
        StringBuilder rawHistoryBuilder = new StringBuilder();

        for (int i = 1; i <= 5; i++) {
            ConversationTurn turn = ConversationTurn.of(
                    i,
                    "在2018年关于类别" + i + "的有效订单（排除已取消）销售额与客单价是多少？",
                    "在2018年关于类别" + i + "的有效订单（排除已取消）销售额与客单价是多少？",
                    "SELECT c.customer_state, SUM(oi.price) FROM orders o JOIN order_items oi ON oi.order_id = o.order_id " +
                            "JOIN customers c ON c.customer_id = o.customer_id " +
                            "WHERE o.order_status NOT IN ('canceled', 'unavailable') AND c.customer_state = 'SP' GROUP BY 1",
                    "SUCCESS",
                    "共 100 行数据，样例: [SP, 12500.0]"
            );
            rawHistoryBuilder.append("轮次 ").append(i).append(":\n")
                    .append("问题: ").append(turn.question()).append("\n")
                    .append("SQL: ").append(turn.sql()).append("\n")
                    .append("结果: ").append(turn.resultSummary()).append("\n\n");

            summary = compressor.compress(summary, turn);
        }

        int rawChars = rawHistoryBuilder.length();
        int compressedChars = summary.condensedSummaryText().length();

        // 验证压缩率：压缩后字符数显著小于原始历史（通常压缩 60% 以上）
        double compressionRatio = 1.0 - ((double) compressedChars / rawChars);
        assertThat(compressionRatio).isGreaterThan(0.60);
        assertThat(summary.evictedTurnCount()).isEqualTo(5);
    }
}
