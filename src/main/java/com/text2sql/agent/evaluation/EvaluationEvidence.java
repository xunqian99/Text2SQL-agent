package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.ResultNormalizer;
import com.text2sql.agent.generation.PromptFingerprint;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.AgentResponse;

import java.util.List;

/** Gold grading happens after the production call and its capture scope have finished. */
public record EvaluationEvidence(
        String id, String difficulty, String questionSha256, String goldSqlSha256,
        String goldResultSha256, String promptHashVersion, String status, String finalSql,
        boolean correct, long wallLatencyMs, AgentResponse.Timings timings, LlmCallRecord totalUsage,
        String retryTrigger, int selectedAttempt, List<EvaluationTrace.Call> calls,
        List<Attempt> productionAttempts) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static EvaluationEvidence grade(EvalItem item, QueryResult gold, AgentResponse response,
                                          EvaluationTrace trace, long wallLatencyMs) {
        return new EvaluationEvidence(item.id(), item.difficulty(),
                PromptFingerprint.textSha256(item.question()), PromptFingerprint.textSha256(item.goldSql()),
                resultHash(gold), PromptFingerprint.VERSION, response.status().name(), response.sql(),
                correct(item, gold, response), wallLatencyMs, response.timings(), response.llmCall(),
                trace.trigger(), trace.selectedAttempt(), trace.calls(), trace.attempts().stream()
                .map(a -> new Attempt(a.number(), a.stage(), a.response().status().name(), a.response().sql(),
                        a.response().message(), correct(item, gold, a.response()), a.response().columns(),
                        a.response().rowCount(), a.response().truncated(), resultHash(result(a.response()))))
                .toList());
    }

    private static boolean correct(EvalItem item, QueryResult gold, AgentResponse response) {
        return response.success() && ResultNormalizer.equivalent(gold, item.goldSql(), result(response), response.sql());
    }

    private static QueryResult result(AgentResponse response) {
        return new QueryResult(response.columns(), response.rows(), response.truncated(), 0);
    }

    private static String resultHash(QueryResult result) {
        try {
            return PromptFingerprint.textSha256(JSON.writeValueAsString(
                    List.of(result.columns(), result.rows(), result.truncated())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint query result", e);
        }
    }

    public record Attempt(int number, String stage, String status, String sql, String message,
                          boolean correct, List<String> columns, int rowCount, boolean truncated,
                          String resultSha256) { }
}
