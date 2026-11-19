package com.text2sql.agent.session;

import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.tool.AgentLoop;
import com.text2sql.agent.validation.ResultChecker;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MultiTurnOrchestratorTest {

    private SchemaProvider schemaProvider;
    private LlmSqlGenerator generator;
    private SqlValidator validator;
    private SqlExecutor executor;
    private AgentProperties properties;
    private SessionStore sessionStore;
    private QuestionRewriter questionRewriter;
    private Text2SqlOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        schemaProvider = mock(SchemaProvider.class);
        generator = mock(LlmSqlGenerator.class);
        validator = mock(SqlValidator.class);
        executor = mock(SqlExecutor.class);
        properties = new AgentProperties();
        properties.getConversation().setEnabled(true);

        sessionStore = new SessionStore(properties);
        questionRewriter = new QuestionRewriter(generator, properties);

        SchemaContext mockSchema = new SchemaContext(
                List.of(new SchemaContext.Table("orders", null, List.of(
                        new SchemaContext.Column("order_id", "text", false, null)))),
                List.of(), "", "CREATE TABLE orders(order_id text);");
        when(schemaProvider.provide(anyString())).thenReturn(mockSchema);

        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));

        when(executor.execute(anyString())).thenReturn(
                new QueryResult(List.of("cnt"), List.of(List.of("100")), false, 10));

        orchestrator = new Text2SqlOrchestrator(
                schemaProvider, generator, validator, executor,
                mock(AmbiguityDetector.class),
                new com.text2sql.agent.cache.SemanticCache(properties),
                new ResultChecker(),
                mock(AgentLoop.class),
                sessionStore,
                questionRewriter,
                properties
        );
    }

    private static LlmCallRecord call() {
        return LlmCallRecord.of("test-model", 50, 20, 70, 200, 0, 0);
    }

    @Test
    @DisplayName("多轮会话第一轮：正常检索并缓存会话与Schema")
    void testFirstTurn() {
        when(generator.generate(anyString(), any())).thenReturn(
                new GeneratedSql("SELECT COUNT(*) FROM orders WHERE year = 2018", "", call()));

        AgentResponse resp = orchestrator.ask("2018年有多少笔订单？", "sess-1");

        assertThat(resp.status()).isEqualTo(AgentResponse.Status.SUCCESS);
        assertThat(resp.sessionId()).isEqualTo("sess-1");
        assertThat(resp.schemaReused()).isFalse();

        ConversationSession session = sessionStore.get("sess-1").orElseThrow();
        assertThat(session.turns()).hasSize(1);
        assertThat(session.lastSchema()).isNotNull();
        verify(schemaProvider, times(1)).provide(anyString());
    }

    @Test
    @DisplayName("多轮会话第二轮追问：指代消解补全问题 + 复用Schema跳过检索")
    void testSecondTurnFollowUp() {
        when(generator.generate(anyString(), any())).thenReturn(
                new GeneratedSql("SELECT COUNT(*) FROM orders WHERE year = 2018", "", call()),
                new GeneratedSql("SELECT COUNT(*) FROM orders WHERE year = 2017", "", call()));

        // 第一轮
        orchestrator.ask("2018年有多少笔订单？", "sess-2");

        // 第二轮追问
        AgentResponse resp2 = orchestrator.ask("那2017年的呢？", "sess-2");

        assertThat(resp2.status()).isEqualTo(AgentResponse.Status.SUCCESS);
        assertThat(resp2.sessionId()).isEqualTo("sess-2");
        assertThat(resp2.schemaReused()).isTrue();
        assertThat(resp2.rewrittenQuestion()).isEqualTo("2017年有多少笔订单？");

        // 验证 schemaProvider 仅在第一轮被调用了 1 次，第二轮直接复用缓存，没发生重复检索
        verify(schemaProvider, times(1)).provide(anyString());

        ConversationSession session = sessionStore.get("sess-2").orElseThrow();
        assertThat(session.turns()).hasSize(2);
        assertThat(session.turns().get(1).rewrittenQuestion()).isEqualTo("2017年有多少笔订单？");
    }

    @Test
    @DisplayName("显式结束会话时标记关闭并落库")
    void testCloseSession() {
        when(generator.generate(anyString(), any())).thenReturn(
                new GeneratedSql("SELECT 1", "", call()));

        orchestrator.ask("2018年有多少笔订单？", "sess-3");

        var closed = orchestrator.closeSession("sess-3");
        assertThat(closed).isPresent();
        assertThat(closed.get().isClosed()).isTrue();
    }
}
