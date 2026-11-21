package com.text2sql.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AskResponseTraceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaContext.SourceProvenance PROVENANCE =
            new SchemaContext.SourceProvenance(
                    Set.of("orders"), Set.of("customers"), Set.of("orders"), Set.of("customers"),
                    false, true);
    private static final LlmCallRecord CALL =
            LlmCallRecord.of("fixture", 12, 8, 1, 4, 20, 0.0004);

    @Test
    void allFiveUiTerminalKindsExposeOneTraceContract() {
        List<AgentResponse> responses = List.of(
                AgentResponse.success("q-success", "SELECT 1", false,
                        new AgentResponse.QueryOutcome(List.of("n"), List.of(List.of("1")), false),
                        CALL, 2, List.of("orders", "customers"), 128,
                        new AgentResponse.Timings(2, 10, 1, 3, 20)).withSourceProvenance(PROVENANCE),
                AgentResponse.clarification("q-clarify", "请确认统计口径"),
                AgentResponse.rejected("q-rejected", "DELETE FROM orders", List.of("[READ_ONLY] 禁止写操作"),
                        CALL, 2, List.of("orders"), 128, new AgentResponse.Timings(2, 10, 1, 0, 14)),
                AgentResponse.failed("q-execution", AgentResponse.Status.EXECUTION_FAILED, "SELECT missing",
                        "relation does not exist", CALL, 1, List.of("orders"), 64,
                        new AgentResponse.Timings(2, 10, 1, 3, 20)),
                AgentResponse.failed("q-timeout", AgentResponse.Status.REQUEST_TIMEOUT, null,
                        "请求超时", null, 0, List.of(), 0,
                        new AgentResponse.Timings(2, 1000, 0, 0, 1002))
        );

        for (AgentResponse response : responses) {
            JsonNode json = JSON.valueToTree(AskController.AskResponse.from(response));
            assertThat(json.path("question").asText()).isEqualTo(response.question());
            assertThat(json.path("status").asText()).isEqualTo(response.status().name());
            assertThat(json.path("provenance").isObject()).isTrue();
            assertThat(json.path("provenance").has("directHits")).isTrue();
            assertThat(json.path("provenance").has("expandedTables")).isTrue();
            assertThat(json.path("provenance").has("metricDependencies")).isTrue();
            assertThat(json.path("provenance").has("safetyBridges")).isTrue();
            assertThat(json.path("provenance").has("traceable")).isTrue();
        }
    }

    @Test
    void retryCombinationKeepsSelectedAttemptSourceIdentity() {
        AgentResponse first = AgentResponse.success("q", "SELECT 1", false,
                new AgentResponse.QueryOutcome(List.of("n"), List.of(List.of("1")), false),
                CALL, 2, List.of("orders"), 64, new AgentResponse.Timings(1, 2, 1, 1, 5))
                .withSourceProvenance(PROVENANCE);
        AgentResponse second = AgentResponse.success("q", "SELECT 2", false,
                new AgentResponse.QueryOutcome(List.of("n"), List.of(List.of("2")), false),
                CALL, 1, List.of("customers"), 32, new AgentResponse.Timings(1, 3, 1, 1, 6))
                .withSourceProvenance(PROVENANCE);

        assertThat(AgentResponse.combineAttempts(first, second).sourceProvenance())
                .isEqualTo(PROVENANCE);
    }
}
