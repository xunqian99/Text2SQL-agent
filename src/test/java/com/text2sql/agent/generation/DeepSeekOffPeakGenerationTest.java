package com.text2sql.agent.generation;

import com.text2sql.agent.budget.DeepSeekOffPeakPolicy;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.cost.CostQuota;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekOffPeakGenerationTest {
    @ParameterizedTest @CsvSource({"generate,false", "correction,false", "messages,false", "tools,false",
            "generate,true", "correction,true", "messages,true", "tools,true"})
    void everyRealClientEntryIsRefusedBeforeQuotaAndCallTrace(String path, boolean byok) {
        var agent = new AgentProperties();
        agent.getLlm().setApiKey(byok ? "" : "fixture-never-sent");
        agent.getObservability().setEnabled(false);
        var quota = mock(CostQuota.class);
        try (var log = new LlmCallLog(agent); var trace = EvaluationTrace.open()) {
            var generator = new LlmSqlGenerator(agent, new PromptTemplate(agent), log, quota);
            ReflectionTestUtils.setField(generator, "offPeak", new DeepSeekOffPeakPolicy(
                    Clock.fixed(Instant.parse("2026-11-17T07:00:00Z"), ZoneOffset.UTC)));
            var schema = new SchemaContext(List.of(), List.of(), "", "fixture");
            String key = byok ? "fixture-byok-never-sent" : null;
            assertThatThrownBy(() -> {
                switch (path) {
                    case "generate" -> generator.generate("fixture", schema, null, key);
                    case "correction" -> generator.generateCorrection("fixture", schema, "SELECT 0", "retry", null, key);
                    case "messages" -> generator.callMessages(List.of(new UserMessage("fixture")), "REWRITE", null, key);
                    case "tools" -> generator.callWithTools(List.of(new UserMessage("fixture")), List.of(), "TOOLS", null, key);
                    default -> throw new AssertionError(path);
                }
            }).isInstanceOf(DeepSeekOffPeakPolicy.Rejected.class)
                    .satisfies(error -> assertThat(((GenerationException) error).getReason()).isEqualTo(GenerationException.Reason.OFF_PEAK_REQUIRED));
            verify(quota, never()).reserve(anyString(), anyInt(), anyString());
            assertThat(trace.calls()).isEmpty();
        }
    }
}
