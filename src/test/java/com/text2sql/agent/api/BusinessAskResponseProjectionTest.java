package com.text2sql.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessAskResponseProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LlmCallRecord CALL =
            LlmCallRecord.of("fixture", 120, 30, 10, 20, 50, 0.001);

    @Test
    void successfulSalesResultContainsBusinessFieldsOnly() {
        AgentResponse source = AgentResponse.success(
                "2018年销售额是多少", "SELECT ...", false,
                new AgentResponse.QueryOutcome(
                        List.of("gmv_with_freight"), List.of(List.of("8593303.96")), false),
                CALL, 8, List.of("orders", "order_items"), 2204,
                new AgentResponse.Timings(76, 2088, 10, 118, 2248))
                .withConversation("session-001", "2018年销售额是多少", false);

        AskController.BusinessAskResponse result = AskController.BusinessAskResponse.from(source);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.title()).isEqualTo("成交总额 GMV");
        assertThat(result.unit()).isEqualTo("CNY");
        assertThat(result.summary()).isEqualTo("¥8,593,303.96");
        assertThat(result.availableDrilldowns()).containsExactly("MONTH", "CATEGORY", "REGION");
        assertThat(result.dataAsOf()).isEqualTo("Olist 本地分析快照");
        assertThat(result.sessionId()).isEqualTo("session-001");

        JsonNode json = JSON.valueToTree(result);
        assertThat(json.has("sql")).isFalse();
        assertThat(json.has("llm")).isFalse();
        assertThat(json.has("timings")).isFalse();
        assertThat(json.has("retrievedTables")).isFalse();
        assertThat(json.has("provenance")).isFalse();
    }

    @Test
    void clarificationRemainsActionableWithoutLeakingDiagnostics() {
        AskController.BusinessAskResponse result = AskController.BusinessAskResponse.from(
                AgentResponse.clarification("2018年销售额是多少", "请确认含不含运费"));

        assertThat(result.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(result.message()).isEqualTo("请确认含不含运费");
        assertThat(result.summary()).isNull();
        assertThat(result.rows()).isEmpty();
        assertThat(JSON.valueToTree(result).has("violations")).isFalse();
    }

    @Test
    void modelAliasGmvIsShownAsBusinessLabel() {
        AgentResponse source = AgentResponse.success(
                "2018年销售额是多少", "SELECT ...", false,
                new AgentResponse.QueryOutcome(
                        List.of("gmv"), List.of(List.of("8593303.96")), false),
                CALL, 1, List.of("orders"), 64,
                new AgentResponse.Timings(1, 2, 1, 3, 7));

        AskController.BusinessAskResponse result = AskController.BusinessAskResponse.from(source);

        assertThat(result.title()).isEqualTo("成交总额 GMV");
        assertThat(result.columns()).containsExactly("成交总额 GMV");
    }

    @Test
    void internalFailureBecomesBusinessSafeMessage() {
        AgentResponse source = AgentResponse.failed(
                "查销售额", AgentResponse.Status.EXECUTION_FAILED, "SELECT secret",
                "relation does not exist", CALL, 1, List.of("orders"), 64,
                new AgentResponse.Timings(1, 2, 1, 3, 7));

        AskController.BusinessAskResponse result = AskController.BusinessAskResponse.from(source);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).isEqualTo("暂时无法完成分析，请稍后重试。");
        assertThat(result.message()).doesNotContain("relation does not exist");
        assertThat(result.message()).doesNotContain("SQL");
    }
}
