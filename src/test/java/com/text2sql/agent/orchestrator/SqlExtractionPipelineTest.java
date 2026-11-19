package com.text2sql.agent.orchestrator;

import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.tool.AgentLoop;
import com.text2sql.agent.validation.ResultChecker;
import com.text2sql.agent.validation.SqlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real generation/extraction/validation/orchestration, stubbed model and database; no paid calls. */
class SqlExtractionPipelineTest {
    @Test
    void trailingFenceSucceedsOnFirstAttemptWithCorrectionEnabled() {
        var properties = new AgentProperties();
        properties.getSelfCorrection().setEnabled(true);
        properties.getSelfCorrection().setMaxAttempts(2);
        properties.getRouting().setEnabled(false);
        properties.getClarification().setEnabled(false);
        properties.getCache().setEnabled(false);
        properties.getObservability().setEnabled(false);
        properties.getPersistence().setEnabled(false);
        String sql = "SELECT COUNT(DISTINCT order_status) AS status_count FROM orders;";
        var schema = new SchemaContext(List.of(new SchemaContext.Table("orders", null, List.of(
                new SchemaContext.Column("order_status", "text", false, null)))), List.of(), "", "");
        var provider = mock(SchemaProvider.class);
        when(provider.provide("订单状态有多少种？")).thenReturn(schema);
        when(provider.fullSchema()).thenReturn(schema);
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(sql + "\n```"))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(10, 5)).build()));
        var executor = mock(SqlExecutor.class);
        when(executor.execute(sql)).thenReturn(new QueryResult(
                List.of("status_count"), List.of(List.of("8")), false, 1));
        try (var logs = new LlmCallLog(properties)) {
            var generator = new LlmSqlGenerator(properties, new PromptTemplate(), logs);
            ReflectionTestUtils.setField(generator, "chatModel", model);
            var pipeline = new Text2SqlOrchestrator(provider, generator, new SqlValidator(properties), executor,
                    mock(AmbiguityDetector.class), new SemanticCache(properties), new ResultChecker(),
                    mock(AgentLoop.class), properties);
            try (var trace = EvaluationTrace.open()) {
                AgentResponse response = pipeline.ask("订单状态有多少种？");
                assertThat(response.success()).isTrue();
                assertThat(response.sql()).isEqualTo(sql);
                assertThat(trace.calls()).hasSize(1);
                assertThat(trace.attempts()).hasSize(1);
                assertThat(trace.trigger()).isEqualTo("NONE");
                assertThat(trace.selectedAttempt()).isEqualTo(1);
                assertThat(response.llmCall()).isEqualTo(trace.calls().getFirst().usage());
            }
        }
        verify(model, times(1)).call(any(Prompt.class));
        verify(executor, times(1)).execute(sql);
    }
}
