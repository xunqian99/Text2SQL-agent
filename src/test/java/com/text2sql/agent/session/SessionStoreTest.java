package com.text2sql.agent.session;

import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    private SessionStore store;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getConversation().setEnabled(true);
        store = new SessionStore(properties);
    }

    @Test
    @DisplayName("会话创建与内存获取")
    void testGetOrCreate() {
        ConversationSession session = store.getOrCreate("session-101");
        assertThat(session).isNotNull();
        assertThat(session.sessionId()).isEqualTo("session-101");
        assertThat(session.isClosed()).isFalse();

        ConversationSession same = store.getOrCreate("session-101");
        assertThat(same).isSameAs(session);
    }

    @Test
    @DisplayName("会话轮次上限限制（LRU修剪防止内存过载）")
    void testMaxTurnsLimit() {
        ConversationSession session = store.getOrCreate("session-102");
        int maxTurns = 3;
        for (int i = 1; i <= 5; i++) {
            session.addTurn(ConversationTurn.of(i, "Q" + i, "Q" + i, "SELECT " + i, "SUCCESS", "ok"), maxTurns);
        }

        assertThat(session.turns()).hasSize(3);
        assertThat(session.turns().get(0).turnIndex()).isEqualTo(3);
        assertThat(session.turns().get(2).turnIndex()).isEqualTo(5);
    }

    @Test
    @DisplayName("滑动窗口修剪时自动压缩早期淘汰轮次为结构化摘要")
    void testSlidingWindowWithSummaryCompression() {
        ConversationSession session = store.getOrCreate("session-102-compress");
        SessionContextCompressor compressor = new SessionContextCompressor();
        int maxTurns = 2;

        session.addTurn(ConversationTurn.of(1, "2018年有效订单", "2018年有效订单",
                "SELECT * FROM orders WHERE order_status NOT IN ('canceled') AND customer_state = 'SP'",
                "SUCCESS", "ok"), maxTurns, compressor);
        session.addTurn(ConversationTurn.of(2, "Q2", "Q2", "SELECT 2", "SUCCESS", "ok"), maxTurns, compressor);
        session.addTurn(ConversationTurn.of(3, "Q3", "Q3", "SELECT 3", "SUCCESS", "ok"), maxTurns, compressor);

        assertThat(session.turns()).hasSize(2);
        assertThat(session.contextSummary().isEmpty()).isFalse();
        assertThat(session.contextSummary().evictedTurnCount()).isEqualTo(1);
        assertThat(session.contextSummary().accumulatedConstraints())
                .anyMatch(c -> c.contains("有效订单") || c.contains("SP 州"));
    }


    @Test
    @DisplayName("关闭会话时触发状态切换与优雅降级持久化")
    void testCloseAndPersist() {
        ConversationSession session = store.getOrCreate("session-103");
        session.addTurn(ConversationTurn.of(1, "Q1", "Q1", "SELECT 1", "SUCCESS", "1 row"), 5);

        var closed = store.closeAndPersist("session-103");
        assertThat(closed).isPresent();
        assertThat(closed.get().isClosed()).isTrue();
    }

    @Test
    @DisplayName("清理超时过期会话")
    void testCleanExpired() throws InterruptedException {
        ConversationSession session = store.getOrCreate("session-104");
        assertThat(store.activeCount()).isEqualTo(1);

        // 使用零超时时长模拟超时淘汰
        int evicted = store.cleanExpired(Duration.ofMillis(0));
        assertThat(evicted).isEqualTo(1);
        assertThat(store.get("session-104")).isEmpty();
    }
}
