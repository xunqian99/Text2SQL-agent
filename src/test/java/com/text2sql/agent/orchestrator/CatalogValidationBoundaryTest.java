package com.text2sql.agent.orchestrator;

import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.*;
import com.text2sql.agent.generation.*;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.tool.*;
import com.text2sql.agent.validation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executable B contract: actual validator and tool callbacks, synthetic model/execution only. */
class CatalogValidationBoundaryTest {
    static final String Q = "列出订单的客户城市";
    static final String SQL = "SELECT o.order_id, c.city FROM orders o JOIN customers c ON o.customer_id=c.customer_id LIMIT 5";
    static final LlmCallRecord CALL = LlmCallRecord.of("synthetic", 100, 20, 120, 1, 0, 0);
    static SchemaContext.Table table(String name, String... cols) {
        return new SchemaContext.Table(name, null, java.util.Arrays.stream(cols)
                .map(c -> new SchemaContext.Column(c, "text", false, null)).toList());
    }
    static final SchemaContext RETRIEVED = new SchemaContext(List.of(table("orders", "order_id")), List.of(), "", "DDL_RETRIEVED_ONLY");
    static final SchemaContext FULL = new SchemaContext(List.of(table("orders", "order_id", "customer_id"),
            table("customers", "customer_id", "city")), List.of(), "", "DDL_FULL_MUST_NOT_REACH_PROMPT");
    static class Fixture {
        final AgentProperties p = new AgentProperties();
        final SchemaProvider provider = mock(SchemaProvider.class);
        final LlmSqlGenerator gen = mock(LlmSqlGenerator.class);
        final SqlExecutor db = mock(SqlExecutor.class);
        final SchemaCatalog catalog = mock(SchemaCatalog.class);
        final QueryPlanAnalyzer plans = mock(QueryPlanAnalyzer.class);
        final SqlValidator validator = new SqlValidator(p);
        final SemanticCache cache = new SemanticCache(p);
        final ToolExecutor tools = new ToolExecutor(new com.text2sql.agent.retrieval.AuthorizedSchemaCatalog(catalog), db, validator, plans);
        final AgentLoop loop = new AgentLoop(gen, new PromptTemplate(p), tools, p);
        Fixture() {
            p.getSelfCorrection().setEnabled(true); p.getSelfCorrection().setMaxAttempts(2);
            p.getClarification().setEnabled(false); p.getResultChecker().setEnabled(false);
            when(provider.provide(anyString())).thenReturn(RETRIEVED);
            when(provider.fullSchema()).thenReturn(FULL); when(catalog.full()).thenReturn(FULL);
            when(gen.generate(anyString(), any())).thenReturn(new GeneratedSql(SQL, SQL, CALL));
            when(gen.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                    .thenReturn(new GeneratedSql(SQL, SQL, CALL));
            when(db.execute(anyString())).thenReturn(new QueryResult(List.of("order_id", "city"), List.of(List.of("o1", "city1")), false, 1));
            when(plans.analyze(anyString())).thenReturn(QueryPlanAnalyzer.PlanAnalysis.safe(1, 1, "{}"));
        }
        Text2SqlOrchestrator orch() { return orch(provider); }
        Text2SqlOrchestrator orch(SchemaProvider source) {
            return new Text2SqlOrchestrator(source, com.text2sql.agent.retrieval.CatalogFixture.from(source::fullSchema), gen, validator, db, mock(AmbiguityDetector.class),
                    cache, new ResultChecker(), loop, p);
        }
        void unavailable(String mode) {
            switch(mode) {
                case "null" -> { when(provider.fullSchema()).thenReturn(null); when(catalog.full()).thenReturn(null); }
                case "empty" -> { var empty = new SchemaContext(List.of(), List.of(), "", "");
                    when(provider.fullSchema()).thenReturn(empty); when(catalog.full()).thenReturn(empty); }
                case "throws" -> { when(provider.fullSchema()).thenThrow(new IllegalStateException("secret-db-detail"));
                    when(catalog.full()).thenThrow(new IllegalStateException("secret-db-detail")); }
                default -> throw new IllegalArgumentException(mode);
            }
        }
    }
    static void unavailable(AgentResponse r) {
        assertThat(r.status()).isEqualTo(AgentResponse.Status.REJECTED);
        assertThat(r.violations()).anyMatch(v -> v.startsWith("[SCHEMA_UNAVAILABLE]"));
        assertThat(r.message()).doesNotContain("secret-db-detail");
    }
    @Test void omittedTableAndPrunedColumnPassWithoutExpandingGenerationContext() {
        var f = new Fixture();
        assertThat(f.validator.validate(SQL, RETRIEVED).valid()).isFalse();
        var r = f.orch().ask(Q); assertThat(r.success()).isTrue();
        assertThat(r.schemaTableCount()).isEqualTo(1); assertThat(r.retrievedTables()).containsExactly("orders");
        assertThat(r.schemaDdlChars()).isEqualTo(RETRIEVED.ddlText().length());
        verify(f.gen).generate(Q, RETRIEVED); verify(f.gen, never()).generateCorrection(anyString(), any(), anyString(), anyString(), any());
        verify(f.db).execute(SQL);
    }
    @ParameterizedTest @ValueSource(strings={"normal", "dry-run", "correction", "cache"})
    void everyExecutionEntryUsesFullCatalog(String path) {
        var f = new Fixture(); var o = f.orch(); AgentResponse r;
        switch(path) {
            case "cache" -> { f.p.getCache().setEnabled(true); f.cache.put(f.cache.key(Q,o.cacheSignature()),SQL); r=o.ask(Q); verifyNoInteractions(f.gen); }
            case "correction" -> r=o.askWithCorrection(Q,"SELECT invalid", "feedback");
            default -> { f.p.getEval().setDryRun(path.equals("dry-run")); r=o.askWithFixedSql(Q,SQL,null); verifyNoInteractions(f.gen); }
        }
        assertThat(r.success()).isTrue(); assertThat(r.retrievedTables()).containsExactly("orders"); verify(f.db).execute(SQL);
    }
    @ParameterizedTest @ValueSource(strings={"null", "empty", "throws", "unimplemented"})
    void unavailableCatalogCannotTriggerCorrectionOrCacheFallback(String mode) {
        var f=new Fixture(); SchemaProvider source=f.provider;
        if(mode.equals("unimplemented")) source=q -> RETRIEVED; else f.unavailable(mode);
        var o=f.orch(source); var first=o.ask(Q); unavailable(first);
        verify(f.gen, never()).generate(Q,RETRIEVED);
        verify(f.gen,never()).generateCorrection(anyString(),any(),anyString(),anyString(),any());
        clearInvocations(f.gen); f.p.getCache().setEnabled(true); f.cache.put(f.cache.key(Q,o.cacheSignature()),SQL);
        unavailable(o.ask(Q)); verifyNoInteractions(f.gen); verifyNoInteractions(f.db,f.plans);
        unavailable(o.askWithFixedSql(Q,SQL,null)); verifyNoInteractions(f.db);
    }
    @Test void correctionCatalogFailureIsTerminalRatherThanReturningEarlierSuccess() {
        var f=new Fixture(); f.p.getResultChecker().setEnabled(true);
        var checker=mock(ResultChecker.class);
        when(checker.check(anyString(),anyString(),any())).thenReturn(CheckResult.suspicious("test", "retry"));
        when(f.gen.generateCorrection(anyString(), any(), anyString(), anyString(), any())).thenAnswer(i -> {
            when(f.provider.fullSchema()).thenReturn(null);
            return new GeneratedSql(SQL, SQL, CALL);
        });
        var o=new Text2SqlOrchestrator(f.provider, com.text2sql.agent.retrieval.CatalogFixture.from(f.provider::fullSchema),f.gen,f.validator,f.db,mock(AmbiguityDetector.class),f.cache,checker,f.loop,f.p);
        var r=o.ask(Q); unavailable(r); verify(f.db,times(1)).execute(SQL);
        assertThat(r.llmCall().promptTokens()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings={"null", "empty", "throws", "unimplemented"})
    void unavailableCatalogBlocksAllRepairableGenerationFailureRetries(String mode) {
        for(var reason:List.of(GenerationException.Reason.CALL_FAILED,GenerationException.Reason.UNPARSEABLE,
                GenerationException.Reason.OUTPUT_TRUNCATED)) {
            var f=new Fixture(); SchemaProvider source=f.provider;
            if(mode.equals("unimplemented"))source=q->RETRIEVED; else f.unavailable(mode);
            when(f.gen.generate(anyString(),any())).thenThrow(new GenerationException(reason,"synthetic failed call",CALL))
                    .thenReturn(new GeneratedSql(SQL,SQL,CALL));
            var r=f.orch(source).ask(Q); unavailable(r); assertThat(r.llmCall()).isNull();
            verify(f.gen,never()).generate(Q,RETRIEVED); verifyNoInteractions(f.db,f.plans);
            verify(f.gen,never()).generateCorrection(anyString(),any(),anyString(),anyString(),any());
        }
    }
    @ParameterizedTest @ValueSource(strings={"null", "empty", "throws", "unimplemented"})
    void explicitCorrectionRejectsUnavailableCatalogBeforeModelCall(String mode) {
        var f=new Fixture(); SchemaProvider source=f.provider;
        if(mode.equals("unimplemented"))source=q->RETRIEVED; else f.unavailable(mode);
        unavailable(f.orch(source).askWithCorrection(Q,"SELECT bad", "synthetic-feedback"));
        verifyNoInteractions(f.gen,f.db,f.plans);
    }
    @Test void catalogFailureBeforeAutomaticCorrectionKeepsFirstUsageAndSkipsSecondCall() {
        var f=new Fixture();
        when(f.gen.generate(anyString(),any())).thenAnswer(i -> {
            when(f.provider.fullSchema()).thenReturn(null);
            return new GeneratedSql("SELECT c.invented FROM customers c LIMIT 5","",CALL);
        });
        var r=f.orch().ask(Q); unavailable(r); assertThat(r.llmCall()).isEqualTo(CALL);
        verify(f.gen,times(1)).generate(Q,RETRIEVED); verifyNoInteractions(f.db,f.plans);
        verify(f.gen,never()).generateCorrection(anyString(),any(),anyString(),anyString(),any());
    }
    @Test void availableCatalogStillAllowsRecoverableGenerationRetryWithinExistingLimit() {
        var f=new Fixture(); when(f.gen.generate(anyString(),any()))
                .thenThrow(new GenerationException(GenerationException.Reason.UNPARSEABLE,"synthetic-first",CALL))
                .thenReturn(new GeneratedSql(SQL,SQL,CALL));
        var r=f.orch().ask(Q); assertThat(r.success()).isTrue(); assertThat(r.llmCall().promptTokens()).isEqualTo(200);
        verify(f.gen,times(2)).generate(Q,RETRIEVED); verify(f.db).execute(SQL);
    }
    @ParameterizedTest @ValueSource(strings={"null-tables", "null-table", "blank-table", "empty-columns", "null-column", "blank-column"})
    void malformedCatalogIsAnEnvironmentFailure(String kind) {
        var f=new Fixture();
        List<SchemaContext.Table> tables=switch(kind) {
            case "null-tables" -> null;
            case "null-table" -> java.util.Collections.singletonList(null);
            case "blank-table" -> List.of(table(" ","id"));
            case "empty-columns" -> List.of(table("orders"));
            case "null-column" -> List.of(new SchemaContext.Table("orders",null,java.util.Collections.singletonList(null)));
            default -> List.of(table("orders"," "));
        };
        var broken=new SchemaContext(tables,List.of(),"","");
        when(f.provider.fullSchema()).thenReturn(broken); when(f.catalog.full()).thenReturn(broken);
        unavailable(f.orch().ask(Q)); verifyNoInteractions(f.db,f.plans);
        assertThat(f.tools.execute(new ToolCall(ToolType.SAMPLE_QUERY,SQL,""),RETRIEVED).schemaUnavailable()).isTrue();
    }
    @Test void rejectedCacheFallsBackThroughSameFullCatalogBoundary() {
        var f=new Fixture(); f.p.getCache().setEnabled(true); var o=f.orch();
        f.cache.put(f.cache.key(Q,o.cacheSignature()),"SELECT c.invented FROM customers c LIMIT 5");
        var r=o.ask(Q); assertThat(r.success()).isTrue(); verify(f.gen).generate(Q,RETRIEVED); verify(f.db).execute(SQL);
    }
    @ParameterizedTest @ValueSource(strings={
            "SELECT x.id FROM invented x LIMIT 5",
            "SELECT c.invented FROM customers c LIMIT 5",
            "SELECT x.id FROM semantic_cache x LIMIT 5",
            "SELECT x.id FROM llm_call_log x LIMIT 5",
            "SELECT x.id FROM conversation_session x LIMIT 5",
            "SELECT x.id FROM conversation_turn x LIMIT 5",
            "SELECT x.id FROM schema_chunks x LIMIT 5",
            "SELECT c.city FROM archive.customers c LIMIT 5",
            "SELECT c.city FROM public.customers c LIMIT 5",
            "SELECT * FROM pg_catalog.pg_class LIMIT 5",
            "SELECT * FROM information_schema.tables LIMIT 5",
            "DELETE FROM customers", "SELECT pg_sleep(1) FROM orders LIMIT 5"})
    void fullCatalogDoesNotAuthorizeUnsafeObjects(String sql) {
        var f=new Fixture(); assertThat(f.orch().askWithFixedSql(Q,sql,null).success()).isFalse(); verifyNoInteractions(f.db,f.plans);
        for(var type:List.of(ToolType.SAMPLE_QUERY,ToolType.EXPLAIN_QUERY)) {
            assertThat(f.tools.execute(new ToolCall(type,sql,""),RETRIEVED).success()).as(type.toString()).isFalse();
        }
        verifyNoInteractions(f.db,f.plans);
    }
    @ParameterizedTest @ValueSource(strings={"SAMPLE_QUERY", "CHECK_JOIN", "EXPLAIN_QUERY"})
    void toolsUseCompleteColumnsAndOmittedTables(String name) {
        var f=new Fixture(); ToolType type=ToolType.valueOf(name);
        String arg=type==ToolType.CHECK_JOIN ? "orders, customers, orders.customer_id=customers.customer_id" : SQL;
        assertThat(f.tools.execute(new ToolCall(type,arg,""),RETRIEVED).success()).isTrue();
        verify(f.catalog,times(1)).full();
    }
    @ParameterizedTest @ValueSource(strings={"null", "empty", "throws"})
    void allDatabaseToolsFailClosed(String mode) {
        var f=new Fixture(); f.unavailable(mode);
        for(var type:List.of(ToolType.INSPECT_TABLE,ToolType.INSPECT_COLUMN,ToolType.SAMPLE_QUERY,ToolType.CHECK_JOIN,ToolType.EXPLAIN_QUERY)) {
            var r=f.tools.execute(new ToolCall(type,SQL,""),RETRIEVED);
            assertThat(r.schemaUnavailable()).as(type.toString()).isTrue(); assertThat(r.output()).doesNotContain("secret-db-detail");
        }
        verifyNoInteractions(f.db,f.plans);
    }
    @Test void realToolCallbackStopsLoopBeforeOtherToolsOrFinalizationAndKeepsUsage() {
        var f=new Fixture(); f.p.getToolUse().setEnabled(true); f.p.getToolUse().setTableThreshold(1);
        var response=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("a","function","SAMPLE_QUERY","{\"sql\":\"SELECT order_id FROM orders\"}"),
                        new AssistantMessage.ToolCall("b","function","INSPECT_TABLE","{\"table\":\"orders\"}"))).build())));
        when(f.gen.callWithTools(anyList(),anyList(),anyString(),any(),any()))
                .thenAnswer(i -> { f.unavailable("empty"); return new LlmSqlGenerator.AgentTurn(response,CALL,""); });
        unavailable(f.orch().ask(Q)); verify(f.gen,times(1)).callWithTools(anyList(),anyList(),anyString(),any(),any());
        verify(f.catalog,times(1)).full(); verifyNoInteractions(f.db,f.plans);
        verify(f.gen,never()).generate(anyString(),any()); verify(f.gen,never()).generateCorrection(anyString(),any(),anyString(),anyString(),any());
    }
}
