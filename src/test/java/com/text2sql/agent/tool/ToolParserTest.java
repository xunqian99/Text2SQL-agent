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
    @DisplayName("无工具标签且无 FINAL_SQL 时返回 null")
    void returnsNullForPlainText() {
        String text = "你好，我是助手，没有调用任何工具。";
        ToolCall call = ToolParser.parse(text);
        assertThat(call).isNull();
    }
}
