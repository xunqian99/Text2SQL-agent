package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationEvidenceTest {
    @Test
    void gradesActualFirstAndSelectedAnswersWithoutSendingGoldIntoTrace() throws Exception {
        var item = new EvalItem("test", "T1", List.of(), "多少？", "SELECT 10 AS amount", null);
        var usage = LlmCallRecord.of("test", 10, 20, 40, 1, .001, .004);
        var first = AgentResponse.success(item.question(), "SELECT 10 AS amount", false,
                new AgentResponse.QueryOutcome(List.of("amount"), List.of(List.of("10")), false),
                usage, 1, List.of("orders"), 0, null);
        var second = AgentResponse.success(item.question(), "SELECT 11 AS amount", false,
                new AgentResponse.QueryOutcome(List.of("amount"), List.of(List.of("11")), false),
                usage, 1, List.of("orders"), 0, null);
        EvaluationTrace trace = EvaluationTrace.open();
        try (trace) {
            EvaluationTrace.attempt("INITIAL_SQL", first);
            EvaluationTrace.trigger("TEST_FALSE_ALERT");
            EvaluationTrace.attempt("CORRECTION_SQL", second);
            EvaluationTrace.select(2);
        }
        var evidence = EvaluationEvidence.grade(item,
                new QueryResult(List.of("amount"), List.of(List.of("10")), false, 0),
                AgentResponse.combineAttempts(first, second), trace, 12);
        assertThat(evidence.correct()).isFalse();
        assertThat(evidence.productionAttempts()).extracting(EvaluationEvidence.Attempt::correct)
                .containsExactly(true, false);
        assertThat(evidence.selectedAttempt()).isEqualTo(2);
        assertThat(evidence.totalUsage().promptTokens()).isEqualTo(20);
        assertThat(evidence.wallLatencyMs()).isEqualTo(12);
        assertThat(evidence.goldResultSha256()).hasSize(64);
        var json = new ObjectMapper().valueToTree(evidence);
        assertThat(json.has("rows")).isFalse();
        assertThat(json.get("productionAttempts").get(0).has("rows")).isFalse();
    }
}
