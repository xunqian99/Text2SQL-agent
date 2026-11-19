package com.text2sql.agent.validation;

import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.tool.AgentLoop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExtremumCorrectionPipelineTest {
    private static final String QUESTION = "容量最大的仓库是哪一个？";
    private static final String OMITTED = "SELECT warehouse_id FROM warehouses ORDER BY capacity DESC LIMIT 1";
    private static final String FIXED = "SELECT warehouse_id,capacity AS volume FROM warehouses ORDER BY capacity DESC LIMIT 1";
    private final LlmSqlGenerator generator = mock(LlmSqlGenerator.class);
    private final SqlExecutor executor = mock(SqlExecutor.class);
    private AgentProperties properties;
    private SemanticCache cache;
    private Text2SqlOrchestrator orchestrator;

    @BeforeEach
    void setup() {
        properties = new AgentProperties();
        properties.getSelfCorrection().setEnabled(true);
        properties.getSelfCorrection().setMaxAttempts(2);
        properties.getResultChecker().setEnabled(true);
        cache = new SemanticCache(properties);
        var schema = new SchemaContext(List.of(new SchemaContext.Table("warehouses", "", List.of(
                new SchemaContext.Column("warehouse_id", "integer", false, "", true),
                new SchemaContext.Column("capacity", "integer", true, "")))), List.of(), "", "");
        var glossary = new Glossary(List.of(), Map.of("warehouses", new Glossary.TableEntry(List.of("仓库"), "",
                Map.of("capacity", new Glossary.ColumnEntry(List.of("容量"), List.of())))));
        var checker = new ResultChecker(new ExtremumProjectionChecker(() -> glossary, () -> schema));
        var provider = mock(SchemaProvider.class);
        when(provider.provide(anyString())).thenReturn(schema);
        when(provider.fullSchema()).thenReturn(schema);
        var validator = mock(SqlValidator.class);
        when(validator.validate(anyString(), any())).thenAnswer(i -> ValidationResult.ok(i.getArgument(0)));
        when(generator.generate(anyString(), any())).thenReturn(generated(OMITTED, 100));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any())).thenReturn(generated(FIXED, 200));
        when(executor.execute(OMITTED)).thenReturn(new QueryResult(List.of("warehouse_id"), List.of(List.of("1")), false, 1));
        when(executor.execute(FIXED)).thenReturn(new QueryResult(List.of("warehouse_id", "volume"), List.of(List.of("1", "50000")), false, 1));
        orchestrator = new Text2SqlOrchestrator(provider, generator, validator, executor,
                mock(AmbiguityDetector.class), cache, checker, mock(AgentLoop.class), properties);
    }

    private static GeneratedSql generated(String sql, int tokens) {
        return new GeneratedSql(sql, sql, LlmCallRecord.of("test-model", tokens, 10, 1, 10, 0, 0));
    }

    @Test
    void correctsMissingSourceAndChargesBothAttempts() {
        var response = orchestrator.ask(QUESTION);
        assertThat(response.success()).isTrue();
        assertThat(response.sql()).isEqualTo(FIXED);
        assertThat(response.llmCall().promptTokens()).isEqualTo(300);
        assertThat(response.llmCall().completionTokens()).isEqualTo(20);
        var feedback = ArgumentCaptor.forClass(String.class);
        verify(generator).generateCorrection(eq(QUESTION), any(), eq(OMITTED), feedback.capture(), isNull());
        assertThat(feedback.getValue()).contains("MISSING_EXTREMUM_ATTRIBUTE", "warehouses.capacity", ExtremumProjectionChecker.POLICY);
        verify(generator,times(1)).generate(anyString(),any());
        verify(generator,times(1)).generateCorrection(anyString(),any(),anyString(),anyString(),any());
    }

    @Test
    void misleadingCorrectionLabelCannotReplaceFirstAnswer() {
        String mislabeled = "SELECT warehouse_id AS capacity FROM warehouses LIMIT 1";
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any())).thenReturn(generated(mislabeled, 200));
        when(executor.execute(mislabeled)).thenReturn(new QueryResult(List.of("capacity"), List.of(List.of("1")), false, 1));
        var response = orchestrator.ask(QUESTION);
        assertThat(response.sql()).isEqualTo(OMITTED);
        assertThat(response.llmCall().promptTokens()).isEqualTo(300);
    }

    @Test
    void unresolvedCorrectionCannotPretendOmissionWasFixed() {
        String unresolved = "SELECT warehouse_id,capacity*0 AS capacity FROM warehouses LIMIT 1";
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any())).thenReturn(generated(unresolved,200));
        when(executor.execute(unresolved)).thenReturn(new QueryResult(List.of("warehouse_id","capacity"),
                List.of(List.of("1","0")),false,1));
        var response = orchestrator.ask(QUESTION);
        assertThat(response.sql()).isEqualTo(OMITTED);
        assertThat(response.llmCall().promptTokens()).isEqualTo(300);
    }

    @Test
    void disabledCorrectionDoesNotAddCall() {
        properties.getSelfCorrection().setEnabled(false);
        var response = orchestrator.ask(QUESTION);
        assertThat(response.sql()).isEqualTo(OMITTED);
        verify(generator,never()).generateCorrection(anyString(),any(),anyString(),anyString(),any());
    }

    @Test
    void cachedMissingAttributeCannotBypassCurrentChecker() {
        properties.getCache().setEnabled(true);
        when(generator.generate(anyString(),any())).thenReturn(generated(FIXED,100));
        // Discover the actual key without reflecting into the orchestrator's cache signature.
        var first = orchestrator.ask(QUESTION);
        assertThat(first.sql()).isEqualTo(FIXED);
        @SuppressWarnings("unchecked")
        var storage = (Map<String,String>) org.springframework.test.util.ReflectionTestUtils.getField(cache,"store");
        assertThat(storage).isNotNull();
        assertThat(storage).hasSize(1);
        String key = storage.keySet().iterator().next();
        assertThat(key).contains("rc=true:",ResultChecker.POLICY_VERSION);
        storage.put(key,OMITTED);
        var second = orchestrator.ask(QUESTION);
        assertThat(second.sql()).isEqualTo(FIXED);
        verify(generator,times(2)).generate(anyString(),any());
        assertThat(storage.get(key)).isEqualTo(FIXED);
    }

    @Test
    void unfixedAnswerIsNotInsertedIntoCache() {
        properties.getCache().setEnabled(true);
        properties.getSelfCorrection().setEnabled(false);
        assertThat(orchestrator.ask(QUESTION).sql()).isEqualTo(OMITTED);
        assertThat(orchestrator.ask(QUESTION).sql()).isEqualTo(OMITTED);
        verify(generator,times(2)).generate(anyString(),any());
    }

    @Test
    void summaryIsModelContextAndCannotEraseCurrentRequestContract() {
        properties.getConversation().setEnabled(true);
        var sessions = (com.text2sql.agent.session.SessionStore)
                org.springframework.test.util.ReflectionTestUtils.getField(orchestrator,"sessionStore");
        sessions.getOrCreate("summary").setContextSummary(new com.text2sql.agent.session.SessionContextSummary(
                java.util.Set.of(),java.util.Set.of(),"历史仅作上下文，曾查订单合计",1));
        var response = orchestrator.ask(QUESTION,"summary");
        assertThat(response.sql()).isEqualTo(FIXED);
        var question = ArgumentCaptor.forClass(String.class);
        verify(generator).generate(question.capture(),any());
        assertThat(question.getValue()).contains(QUESTION,"历史仅作上下文");
        verify(generator).generateCorrection(eq(question.getValue()),any(),eq(OMITTED),contains("warehouses.capacity"),isNull());
    }
}
