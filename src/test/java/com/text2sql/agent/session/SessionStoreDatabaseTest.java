package com.text2sql.agent.session;

import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话落库的单元测试。
 *
 * <p>重点锁住三个「曾经错过的」行为：
 * <ol>
 *   <li>正常落库：会话主体 1 条、轮次 N 条；</li>
 *   <li><b>不覆盖历史</b>：复用同一 sessionId 时，新轮次编号要接在已有历史之后，
 *       而不是先 DELETE 再重写；</li>
 *   <li><b>表已存在就不建表</b>：{@code text2sql_rw} 没有 CREATE 权限，
 *       盲目执行 CREATE TABLE 只会刷一条误导性的 "bad SQL grammar" 警告。</li>
 * </ol>
 */
class SessionStoreDatabaseTest {

    private static SessionStore storeWithMockDb(JdbcTemplate mockJdbc) {
        AgentProperties props = new AgentProperties();
        props.getConversation().setEnabled(true);
        SessionStore store = new SessionStore(props);
        ReflectionTestUtils.setField(store, "db", mockJdbc);
        return store;
    }

    private static ConversationSession twoTurnSession(SessionStore store, String sessionId) {
        ConversationSession session = store.getOrCreate(sessionId);
        session.addTurn(ConversationTurn.of(1, "2018年订单总量？", "2018年订单总量？",
                "SELECT COUNT(*) FROM orders", "SUCCESS", "1 row"), 5);
        session.addTurn(ConversationTurn.of(2, "那2017年的呢？", "2017年订单总量？",
                "SELECT COUNT(*) FROM orders", "SUCCESS", "1 row"), 5);
        return session;
    }

    @Test
    @DisplayName("会话结束时落库：会话主体 1 条、轮次 2 条")
    void writesSessionAndTurns() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        SessionStore store = storeWithMockDb(mockJdbc);
        twoTurnSession(store, "session-db-001");

        var closed = store.closeAndPersist("session-db-001");

        assertThat(closed).isPresent();
        assertThat(closed.get().isClosed()).isTrue();

        verify(mockJdbc, atLeastOnce()).execute(contains("CREATE TABLE IF NOT EXISTS conversation_session"));
        verify(mockJdbc, times(1)).update(contains("INSERT INTO conversation_session"),
                any(), any(), any(), any(), any(), any());
        verify(mockJdbc, times(2)).update(contains("INSERT INTO conversation_turn"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("复用同一 sessionId 时，轮次编号接在已有历史之后，绝不 DELETE 覆盖")
    void appendsAfterExistingHistoryInsteadOfOverwriting() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        // 库里已经有 2 轮历史
        when(mockJdbc.queryForObject(contains("COALESCE(MAX(turn_index)"), eq(Integer.class), eq("session-db-002")))
                .thenReturn(2);

        SessionStore store = storeWithMockDb(mockJdbc);
        twoTurnSession(store, "session-db-002");

        store.closeAndPersist("session-db-002");

        // 本次两轮应该编号为 3、4（INSERT 共 8 个占位符：sid, turn_index, question,
        // rewritten_question, sql, status, result_summary, created_at）
        verify(mockJdbc).update(contains("INSERT INTO conversation_turn"),
                eq("session-db-002"), eq(3), any(), any(), any(), any(), any(), any());
        verify(mockJdbc).update(contains("INSERT INTO conversation_turn"),
                eq("session-db-002"), eq(4), any(), any(), any(), any(), any(), any());

        // 关键断言：旧实现这里有一条 DELETE，会把上一段历史整段删掉
        verify(mockJdbc, never()).update(contains("DELETE FROM conversation_turn"), any(Object[].class));
    }

    @Test
    @DisplayName("表已存在时不再尝试建表——rw 账号没有 CREATE 权限，建表只会刷误导性警告")
    void skipsCreateWhenTablesAlreadyExist() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        when(mockJdbc.queryForObject(contains("to_regclass"), eq(Boolean.class))).thenReturn(true);

        SessionStore store = storeWithMockDb(mockJdbc);
        ConversationSession session = store.getOrCreate("session-db-003");
        session.addTurn(ConversationTurn.of(1, "Q", "Q", "SELECT 1", "SUCCESS", "ok"), 5);

        store.closeAndPersist("session-db-003");

        verify(mockJdbc, never()).execute(contains("CREATE TABLE"));
        verify(mockJdbc, times(1)).update(contains("INSERT INTO conversation_session"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("落库成功后会话移出内存，避免列表口径与数据库不一致")
    void removesSessionFromMemoryAfterSuccessfulPersist() {
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        when(mockJdbc.queryForObject(contains("to_regclass"), eq(Boolean.class))).thenReturn(true);

        SessionStore store = storeWithMockDb(mockJdbc);
        ConversationSession session = store.getOrCreate("session-db-004");
        session.addTurn(ConversationTurn.of(1, "Q", "Q", "SELECT 1", "SUCCESS", "ok"), 5);

        store.closeAndPersist("session-db-004");

        assertThat(store.get("session-db-004")).as("已落库的会话不该继续占内存").isEmpty();
        assertThat(store.activeCount()).isZero();
    }
}
