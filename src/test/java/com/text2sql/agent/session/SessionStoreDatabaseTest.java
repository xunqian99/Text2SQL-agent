package com.text2sql.agent.session;

import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class SessionStoreDatabaseTest {

    @Test
    @DisplayName("会话结束时落库：当数据库连接就绪，调用 closeAndPersist 正确执行 DDL 与 SQL 入库")
    void testCloseAndPersistWritesToDatabase() {
        AgentProperties props = new AgentProperties();
        props.getConversation().setEnabled(true);

        SessionStore store = new SessionStore(props);

        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(store, "db", mockJdbc);

        ConversationSession session = store.getOrCreate("session-db-001");
        session.addTurn(ConversationTurn.of(1, "2018年订单总量？", "2018年订单总量？",
                "SELECT COUNT(*) FROM orders", "SUCCESS", "1 row"), 5);
        session.addTurn(ConversationTurn.of(2, "那2017年的呢？", "2017年订单总量？",
                "SELECT COUNT(*) FROM orders", "SUCCESS", "1 row"), 5);

        var closed = store.closeAndPersist("session-db-001");

        assertThat(closed).isPresent();
        assertThat(closed.get().isClosed()).isTrue();

        // 验证执行了建表DDL
        verify(mockJdbc, atLeastOnce()).execute(contains("CREATE TABLE IF NOT EXISTS conversation_session"));

        // 验证执行了会话主体更新
        verify(mockJdbc, times(1)).update(contains("INSERT INTO conversation_session"),
                any(), any(), any(), any(), any(), any());

        // 验证执行了轮次更新（2轮）
        verify(mockJdbc, times(2)).update(contains("INSERT INTO conversation_turn"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }
}
