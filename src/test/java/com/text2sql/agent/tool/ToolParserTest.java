package com.text2sql.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolParserTest {

    @Test
    @DisplayName("正确解析 [FINAL_SQL] 标签")
    void parsesFinalSql() {
        String text = """
                我分析完毕了，这是最终答案：
                [FINAL_SQL] SELECT customer_id, count(*) FROM orders GROUP BY customer_id
                """;
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNotNull();
        assertThat(call.type()).isEqualTo(ToolType.FINAL_SQL);
        assertThat(call.argument()).isEqualTo("SELECT customer_id, count(*) FROM orders GROUP BY customer_id");
    }

    @Test
    @DisplayName("正确解析 [TOOL_CALL] INSPECT_TABLE")
    void parsesInspectTable() {
        String text = "[TOOL_CALL] INSPECT_TABLE(orders)";
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNotNull();
        assertThat(call.type()).isEqualTo(ToolType.INSPECT_TABLE);
        assertThat(call.argument()).isEqualTo("orders");
    }

    @Test
    @DisplayName("正确解析 [TOOL_CALL] INSPECT_COLUMN")
    void parsesInspectColumn() {
        String text = "我需要先看看列信息\n[TOOL_CALL] INSPECT_COLUMN(orders.order_status)";
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNotNull();
        assertThat(call.type()).isEqualTo(ToolType.INSPECT_COLUMN);
        assertThat(call.argument()).isEqualTo("orders.order_status");
    }

    @Test
    @DisplayName("正确解析 [TOOL_CALL] CHECK_JOIN")
    void parsesCheckJoin() {
        String text = "[TOOL_CALL] CHECK_JOIN(orders, customers, orders.customer_id = customers.customer_id)";
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNotNull();
        assertThat(call.type()).isEqualTo(ToolType.CHECK_JOIN);
        assertThat(call.argument()).isEqualTo("orders, customers, orders.customer_id = customers.customer_id");
    }

    @Test
    @DisplayName("正确解析 [TOOL_CALL] EXPLAIN_QUERY")
    void parsesExplainQuery() {
        String text = "[TOOL_CALL] EXPLAIN_QUERY(SELECT * FROM orders JOIN customers ON orders.customer_id = customers.customer_id)";
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNotNull();
        assertThat(call.type()).isEqualTo(ToolType.EXPLAIN_QUERY);
        assertThat(call.argument()).isEqualTo("SELECT * FROM orders JOIN customers ON orders.customer_id = customers.customer_id");
    }

    @Test
    @DisplayName("工具参数里含括号时不能被截断（COUNT(*) 会被非贪婪正则截断）")
    void parsesArgumentWithNestedParentheses() {
        String text = "[TOOL_CALL] SAMPLE_QUERY(SELECT COUNT(*) FROM orders)";
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNotNull();
        assertThat(call.type()).isEqualTo(ToolType.SAMPLE_QUERY);
        assertThat(call.argument()).isEqualTo("SELECT COUNT(*) FROM orders");
    }

    @Test
    @DisplayName("参数里的字符串常量含右括号时不能提前结束")
    void parsesArgumentWithParenInsideStringLiteral() {
        String text = "[TOOL_CALL] SAMPLE_QUERY(SELECT * FROM orders WHERE order_status = 'a)b')";
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNotNull();
        assertThat(call.argument()).isEqualTo("SELECT * FROM orders WHERE order_status = 'a)b'");
    }

    @Test
    @DisplayName("无工具标签且无 FINAL_SQL 时返回 null")
    void returnsNullForPlainText() {
        String text = "你好，我是助手，没有调用任何工具。";
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNull();
    }

    @Test
    @DisplayName("一条消息里连发两个 [TOOL_CALL]：两个都要解析出来")
    void parsesAllToolCallsInOneMessage() {
        String text = """
                [TOOL_CALL] INSPECT_COLUMN(orders.order_status)
                [TOOL_CALL] INSPECT_TABLE(orders)""";
        var calls = ToolParser.parseAll(text);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).type()).isEqualTo(ToolType.INSPECT_COLUMN);
        assertThat(calls.get(0).argument()).isEqualTo("orders.order_status");
        assertThat(calls.get(1).type()).isEqualTo(ToolType.INSPECT_TABLE);
        assertThat(calls.get(1).argument()).isEqualTo("orders");
    }

    @Test
    @DisplayName("[FINAL_SQL] 出现即定稿，同一段里的 [TOOL_CALL] 不再解析")
    void finalSqlWinsOverToolCalls() {
        String text = """
                [TOOL_CALL] INSPECT_TABLE(orders)
                [FINAL_SQL] SELECT 1""";
        var calls = ToolParser.parseAll(text);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).type()).isEqualTo(ToolType.FINAL_SQL);
    }

    @Test
    @DisplayName("两个调用各含括号参数：不能互相干扰")
    void parsesMultipleCallsWithNestedParens() {
        String text = """
                [TOOL_CALL] SAMPLE_QUERY(SELECT COUNT(*) FROM orders)
                [TOOL_CALL] CHECK_JOIN(orders, customers, orders.customer_id = customers.customer_id)""";
        var calls = ToolParser.parseAll(text);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).argument()).isEqualTo("SELECT COUNT(*) FROM orders");
        assertThat(calls.get(1).argument()).contains("orders.customer_id = customers.customer_id");
    }
}
