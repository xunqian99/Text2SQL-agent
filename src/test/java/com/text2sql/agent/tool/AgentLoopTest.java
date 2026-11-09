package com.text2sql.agent.tool;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent 循环的编排语义测试。
 *
 * <p>工具调用改走原生 function calling 之后，这里测的不再是「文本标记解析得对不对」，
 * 而是三条**协议层保证、但编排写错就会失效**的规则：
 * <ol>
 *   <li>最后一轮必须不传 tools——模型物理上没有工具可调，收口才可靠；</li>
 *   <li>一轮里多个 tool_calls 必须全部执行，不能只跑第一个；</li>
 *   <li>工具执行失败要变成 Observation 回给模型，而不是让整次请求失败。</li>
 * </ol>
 */
class AgentLoopTest {

    private LlmSqlGenerator generator;
    private PromptTemplate promptTemplate;
    private ToolExecutor toolExecutor;
    private AgentLoop agentLoop;
    private AgentProperties properties;
    private SchemaContext mockSchema;

    @BeforeEach
    void setUp() {
        generator = mock(LlmSqlGenerator.class);
        promptTemplate = new PromptTemplate();
        toolExecutor = mock(ToolExecutor.class);
        properties = new AgentProperties();
        agentLoop = new AgentLoop(generator, promptTemplate, toolExecutor, properties);

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

    /** 模型这一轮只返回文本（通常是最终 SQL）。 */
    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 模型这一轮返回若干结构化工具调用。 */
    private static ChatResponse toolResponse(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }

    private static AssistantMessage.ToolCall toolCall(String id, String name, String argsJson) {
        return new AssistantMessage.ToolCall(id, "function", name, argsJson);
    }

    private static LlmSqlGenerator.AgentTurn turn(ChatResponse response, int promptTokens, int completionTokens) {
        return new LlmSqlGenerator.AgentTurn(response, call(promptTokens, completionTokens),
                response.getResult().getOutput().getText());
    }

    @Test
    @DisplayName("首轮直接给 SQL：单步终止，不调用任何工具")
    void testImmediateFinalSql() {
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_1"), any(), any()))
                .thenReturn(turn(textResponse("SELECT count(*) FROM orders"), 200, 25));

        GeneratedSql result = agentLoop.run("订单有多少", mockSchema, 3, null);

        assertThat(result.sql()).isEqualTo("SELECT count(*) FROM orders");
        assertThat(result.call().promptTokens()).isEqualTo(200);
        verify(toolExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("一轮调工具、二轮定稿：工具被执行，token 按两轮累加")
    void testOneToolRoundThenFinalize() {
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_1"), any(), any()))
                .thenReturn(turn(toolResponse(toolCall("c1", "INSPECT_COLUMN",
                        "{\"tableColumn\":\"orders.order_status\"}")), 200, 20));
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_2"), any(), any()))
                .thenReturn(turn(textResponse("SELECT count(*) FROM orders WHERE order_status = 'delivered'"), 300, 30));
        when(toolExecutor.execute(any(ToolCall.class), eq(mockSchema)))
                .thenReturn(ToolResult.ok("delivered: 96478", List.of("v", "c"), List.of()));

        GeneratedSql result = agentLoop.run("已签收订单有多少", mockSchema, 3, null);

        assertThat(result.sql()).contains("order_status = 'delivered'");
        assertThat(result.call().promptTokens()).isEqualTo(500);
        assertThat(result.call().completionTokens()).isEqualTo(50);
        verify(toolExecutor, times(1)).execute(
                argThat(c -> c.type() == ToolType.INSPECT_COLUMN
                        && "orders.order_status".equals(c.argument())), eq(mockSchema));
    }

    @Test
    @DisplayName("轮次用尽仍在侦察：执行完最后一批工具，再要一次强制收口（收口轮不给工具）")
    void testFinalizeAfterRoundsExhausted() {
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_1"), any(), any()))
                .thenReturn(turn(toolResponse(toolCall("c1", "INSPECT_TABLE", "{\"table\":\"orders\"}")), 200, 20));
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_2"), any(), any()))
                .thenReturn(turn(toolResponse(toolCall("c2", "INSPECT_COLUMN",
                        "{\"tableColumn\":\"orders.order_status\"}")), 200, 20));
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_FINALIZE"), any(), any()))
                .thenReturn(turn(textResponse("SELECT count(*) FROM orders"), 200, 20));
        when(toolExecutor.execute(any(ToolCall.class), eq(mockSchema)))
                .thenReturn(ToolResult.ok("ok", List.of(), List.of()));

        GeneratedSql result = agentLoop.run("问题", mockSchema, 2, null);

        assertThat(result.sql()).isEqualTo("SELECT count(*) FROM orders");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallback>> captor = ArgumentCaptor.forClass(List.class);
        // 前两轮都要给工具：最后一轮抽掉工具会让模型把工具调用当文本吐出来（DSML 标记）
        verify(generator).callWithTools(anyList(), captor.capture(), eq("AGENT_ROUND_1"), any(), any());
        assertThat(captor.getValue()).hasSize(5);
        verify(generator).callWithTools(anyList(), captor.capture(), eq("AGENT_ROUND_2"), any(), any());
        assertThat(captor.getValue()).as("收口前的正常轮次仍提供工具").hasSize(5);
        // 收口轮才真正不给工具
        verify(generator).callWithTools(anyList(), captor.capture(), eq("AGENT_FINALIZE"), any(), any());
        assertThat(captor.getValue()).as("收口轮不提供工具").isEmpty();
    }

    @Test
    @DisplayName("收口轮吐出 DSML 工具标记：剥掉标记再抽 SQL，不能把标记当 SQL")
    void testStripsDsmlMarkupOnFinalize() {
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_1"), any(), any()))
                .thenReturn(turn(toolResponse(toolCall("c1", "INSPECT_TABLE", "{\"table\":\"orders\"}")), 200, 20));
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_2"), any(), any()))
                .thenReturn(turn(toolResponse(toolCall("c2", "INSPECT_TABLE", "{\"table\":\"orders\"}")), 200, 20));
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_FINALIZE"), any(), any()))
                .thenReturn(turn(textResponse(
                        "SELECT count(*) FROM orders\n<｜｜DSML｜｜ calls> <｜｜DSML｜｜ invoke name=\"X\">"), 200, 20));
        when(toolExecutor.execute(any(ToolCall.class), eq(mockSchema)))
                .thenReturn(ToolResult.ok("ok", List.of(), List.of()));

        GeneratedSql result = agentLoop.run("问题", mockSchema, 2, null);

        assertThat(result.sql()).isEqualTo("SELECT count(*) FROM orders");
        assertThat(result.sql()).doesNotContain("DSML");
    }

    @Test
    @DisplayName("一轮里多个 tool_calls：全部执行，一个都不丢")
    void testMultipleToolCallsInOneRound() {
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_1"), any(), any()))
                .thenReturn(turn(toolResponse(
                        toolCall("c1", "INSPECT_COLUMN", "{\"tableColumn\":\"orders.order_status\"}"),
                        toolCall("c2", "INSPECT_TABLE", "{\"table\":\"orders\"}")), 200, 20));
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_2"), any(), any()))
                .thenReturn(turn(textResponse("SELECT count(*) FROM orders"), 200, 20));
        when(toolExecutor.execute(any(ToolCall.class), eq(mockSchema)))
                .thenReturn(ToolResult.ok("ok", List.of(), List.of()));

        agentLoop.run("订单有多少", mockSchema, 3, null);

        verify(toolExecutor, times(1)).execute(
                argThat(c -> c.type() == ToolType.INSPECT_COLUMN), eq(mockSchema));
        verify(toolExecutor, times(1)).execute(
                argThat(c -> c.type() == ToolType.INSPECT_TABLE), eq(mockSchema));
    }

    @Test
    @DisplayName("工具执行抛异常：变成 Observation 回给模型，不让整次请求失败")
    void testToolFailureBecomesObservation() {
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_1"), any(), any()))
                .thenReturn(turn(toolResponse(toolCall("c1", "INSPECT_TABLE", "{\"table\":\"nope\"}")), 200, 20));
        when(generator.callWithTools(anyList(), anyList(), eq("AGENT_ROUND_2"), any(), any()))
                .thenReturn(turn(textResponse("SELECT count(*) FROM orders"), 200, 20));
        when(toolExecutor.execute(any(ToolCall.class), eq(mockSchema)))
                .thenThrow(new RuntimeException("表 nope 不存在"));

        GeneratedSql result = agentLoop.run("订单有多少", mockSchema, 3, null);

        assertThat(result.sql()).as("工具失败不应中断循环").isEqualTo("SELECT count(*) FROM orders");
    }
}
