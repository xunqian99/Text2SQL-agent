package com.text2sql.agent.tool;

import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    private LlmSqlGenerator generator;
    private PromptTemplate promptTemplate;
    private ToolExecutor toolExecutor;
    private AgentLoop agentLoop;
    private SchemaContext mockSchema;

    @BeforeEach
    void setUp() {
        generator = mock(LlmSqlGenerator.class);
        promptTemplate = new PromptTemplate();
        toolExecutor = mock(ToolExecutor.class);
        agentLoop = new AgentLoop(generator, promptTemplate, toolExecutor);

        mockSchema = new SchemaContext(
                List.of(new SchemaContext.Table("orders", null, List.of(
                        new SchemaContext.Column("order_id", "text", false, null),
                        new SchemaContext.Column("order_status", "text", false, null)))),
                List.of(), "", "CREATE TABLE orders(order_id text, order_status text);");
    }

    private static LlmCallRecord call(int promptTokens, int completionTokens) {
        return LlmCallRecord.of("deepseek-chat", promptTokens, completionTokens,
                promptTokens + completionTokens, 500, 0, 0);
    }

    @Test
    @DisplayName("首轮直接输出 [FINAL_SQL]：循环单步终止并返回正确结果")
    void testImmediateFinalSql() {
        String output = "[FINAL_SQL] SELECT count(*) FROM orders WHERE order_status = 'delivered'";
        when(generator.callMessages(anyList(), anyString(), any())).thenReturn(
                new GeneratedSql("SELECT count(*) FROM orders WHERE order_status = 'delivered'", output, call(200, 25)));

        GeneratedSql result = agentLoop.run("已签收订单有多少？", mockSchema, 3, null);

        assertThat(result.sql()).isEqualTo("SELECT count(*) FROM orders WHERE order_status = 'delivered'");
        assertThat(result.call().promptTokens()).isEqualTo(200);
        assertThat(result.call().completionTokens()).isEqualTo(25);
        verify(toolExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("两轮 ReAct 侦察：先调用探查工具获取数据分布，再输出最终 SQL")
    void testTwoRoundReActLoop() {
        // 第一轮：调用探查工具
        String round1Output = "我需要先看看真实枚举值：\n[TOOL_CALL] INSPECT_COLUMN(orders.order_status)";
        GeneratedSql step1 = new GeneratedSql(null, round1Output, call(300, 30));

        // 第二轮：基于 Observation 输出最终 SQL
        String round2Output = "根据真实枚举，已签收对应 delivered：\n[FINAL_SQL] SELECT count(*) FROM orders WHERE order_status = 'delivered'";
        GeneratedSql step2 = new GeneratedSql("SELECT count(*) FROM orders WHERE order_status = 'delivered'", round2Output, call(450, 40));

        when(generator.callMessages(anyList(), eq("AGENT_ROUND_1"), any())).thenReturn(step1);
        when(generator.callMessages(anyList(), eq("AGENT_ROUND_2"), any())).thenReturn(step2);

        when(toolExecutor.execute(any(ToolCall.class), eq(mockSchema))).thenReturn(
                ToolResult.ok("--- 列 orders.order_status 的高频值分布 ---\ndelivered: 96478, shipped: 1107",
                        List.of("order_status", "cnt"), List.of()));

        GeneratedSql result = agentLoop.run("已签收订单有多少？", mockSchema, 3, null);

        assertThat(result.sql()).isEqualTo("SELECT count(*) FROM orders WHERE order_status = 'delivered'");
        // 验证 Token 正确累加（300 + 450 = 750, 30 + 40 = 70）
        assertThat(result.call().promptTokens()).isEqualTo(750);
        assertThat(result.call().completionTokens()).isEqualTo(70);
        assertThat(result.call().totalTokens()).isEqualTo(820);

        verify(toolExecutor, times(1)).execute(argThat(c -> c.type() == ToolType.INSPECT_COLUMN), eq(mockSchema));
    }

    @Test
    @DisplayName("模型未带标签直接输出裸 SQL：成功兜底识别并终止")
    void testPlainSqlWithoutTag() {
        String plain = "SELECT count(*) FROM orders;";
        GeneratedSql step = new GeneratedSql(plain, plain, call(200, 20));
        when(generator.callMessages(anyList(), anyString(), any())).thenReturn(step);

        GeneratedSql result = agentLoop.run("统计订单数", mockSchema, 3, null);

        assertThat(result.sql()).isEqualTo("SELECT count(*) FROM orders;");
        verify(toolExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("最后一轮仍在调工具：不执行工具，走强制收口拿到 SQL，而不是把 [TOOL_CALL] 当 SQL")
    void testFinalRoundStillCallingToolsFallsBackToForcedFinalize() {
        String toolOnly = "[TOOL_CALL] INSPECT_COLUMN(orders.order_status)";
        when(generator.callMessages(anyList(), startsWith("AGENT_ROUND_"), any()))
                .thenReturn(new GeneratedSql(null, toolOnly, call(200, 20)));

        String finalizeOutput = "[FINAL_SQL] SELECT count(*) FROM orders";
        when(generator.callMessages(anyList(), eq("AGENT_FINALIZE"), any()))
                .thenReturn(new GeneratedSql("SELECT count(*) FROM orders", finalizeOutput, call(100, 10)));
        when(toolExecutor.execute(any(ToolCall.class), eq(mockSchema)))
                .thenReturn(ToolResult.ok("delivered: 96478", List.of("order_status", "cnt"), List.of()));

        GeneratedSql result = agentLoop.run("订单有多少", mockSchema, 3, null);

        assertThat(result.sql())
                .as("必须拿到真实 SQL，不能是 [TOOL_CALL] 文本")
                .isEqualTo("SELECT count(*) FROM orders");
        assertThat(result.sql()).doesNotContain("TOOL_CALL");
        // 最后一轮不再执行工具（执行了也没下一轮消费结果）
        verify(toolExecutor, times(2)).execute(any(), any());
        verify(generator).callMessages(anyList(), eq("AGENT_FINALIZE"), any());
    }

    @Test
    @DisplayName("一轮里连发两个 [TOOL_CALL]：两个都执行，不被丢弃")
    void testMultipleToolCallsInOneRound() {
        String twoCalls = """
                [TOOL_CALL] INSPECT_COLUMN(orders.order_status)
                [TOOL_CALL] INSPECT_TABLE(orders)""";
        when(generator.callMessages(anyList(), eq("AGENT_ROUND_1"), any()))
                .thenReturn(new GeneratedSql(null, twoCalls, call(200, 20)));
        when(generator.callMessages(anyList(), eq("AGENT_ROUND_2"), any()))
                .thenReturn(new GeneratedSql("SELECT count(*) FROM orders",
                        "[FINAL_SQL] SELECT count(*) FROM orders", call(200, 20)));
        when(toolExecutor.execute(any(ToolCall.class), eq(mockSchema)))
                .thenReturn(ToolResult.ok("ok", List.of("c"), List.of()));

        GeneratedSql result = agentLoop.run("订单有多少", mockSchema, 3, null);

        assertThat(result.sql()).isEqualTo("SELECT count(*) FROM orders");
        verify(toolExecutor, times(1)).execute(argThat(c -> c.type() == ToolType.INSPECT_COLUMN), eq(mockSchema));
        verify(toolExecutor, times(1)).execute(argThat(c -> c.type() == ToolType.INSPECT_TABLE), eq(mockSchema));
    }
}
