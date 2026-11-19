package com.text2sql.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.fewshot.*;
import com.text2sql.agent.generation.*;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import com.text2sql.agent.semantic.MetricRegistry;
import com.text2sql.agent.execution.*;
import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.tool.AgentLoop;
import com.text2sql.agent.validation.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Captures actual first ChatModel.call messages through ask(), with an in-process fake model. */
public final class CatalogFirstCallCapture {
    public static void main(String[] args) throws Exception {
        var allowed=Map.of("data/eval",200,"data/benchmarks/olist-v4-development-20261119",100,
                "data/benchmarks/olist-v4-formal-20261119",100);
        if(args.length!=2 || !allowed.containsKey(args[0])) throw new IllegalArgumentException("Public dataset only");
        Path output=Path.of(args[1]); if(Files.exists(output)) throw new IllegalStateException("Never overwrite captures");
        var p=new AgentProperties(); p.getEval().setDir(args[0]); p.getEval().setSplit("all");
        p.getRetrieval().setEnabled(true); p.getRetrieval().setColumnPruningEnabled(true);
        p.getRetrieval().setJoinHintsEnabled(true); p.getRetrieval().setJoinListEnabled(false); p.getRetrieval().setJoinPlanEnabled(true);
        p.getSemantic().setEnabled(true); p.getFewshot().setEnabled(true); p.getFewshot().setMaxExamples(2);
        p.getLlm().setBaseUrl("http://127.0.0.1:1"); p.getLlm().setApiKey(""); p.getLlm().setModel("synthetic-capture");
        p.getObservability().setEnabled(false); p.getSelfCorrection().setEnabled(false);
        p.getClarification().setEnabled(false); p.getResultChecker().setEnabled(false);
        p.getCache().setEnabled(false); p.getToolUse().setEnabled(false); p.getRouting().setEnabled(false);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:5432/olist","text2sql_ro","text2sql_ro"));
        var catalog=new SchemaCatalog(new DatabaseSchemaReader(jdbc),p); var full=catalog.full();
        if(full.tables().size()!=37) throw new IllegalStateException("Catalog identity changed");
        var glossary=new GlossaryLoader(catalog); var registry=new MetricRegistry(); ReflectionTestUtils.invokeMethod(registry,"load");
        var examples=new ExampleLoader(); examples.init();
        var provider=new HybridSchemaProvider(catalog,new LexicalSchemaRetriever(glossary,catalog,p),glossary,p,registry,
                new ExampleSelector(examples),new ValueRetriever(glossary),new ColumnPruner(glossary));
        var model=mock(ChatModel.class); var db=mock(SqlExecutor.class);
        when(db.execute(anyString())).thenReturn(new QueryResult(List.of("order_id"),List.of(List.of("synthetic")),false,0));
        var captures=new ArrayList<Object>(); var current=new ArrayList<Object>();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt=inv.getArgument(0);
            current.add(prompt.getInstructions().stream().map(m -> Map.of("role",m.getMessageType().name(),"text",m.getText())).toList());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("SELECT order_id FROM orders LIMIT 1"))));
        });
        try(var logs=new LlmCallLog(p)) {
            var generator=new LlmSqlGenerator(p,new PromptTemplate(p),logs); ReflectionTestUtils.setField(generator,"chatModel",model);
            var o=new Text2SqlOrchestrator(provider,generator,new SqlValidator(p),db,mock(AmbiguityDetector.class),
                    new SemanticCache(p),new ResultChecker(),mock(AgentLoop.class),p);
            var items=new EvalItemLoader(p).load(); if(items.size()!=allowed.get(args[0])) throw new AssertionError("Wrong count");
            for(var item:items) {
                current.clear(); var response=o.ask(item.question());
                if(current.size()!=1) throw new AssertionError("Expected exactly one synthetic first call: "+item.id());
                captures.add(Map.of("id",item.id(),"question",item.question(),"messages",current.getFirst(),
                        "retrievedTables",response.retrievedTables(),"schemaDdlChars",response.schemaDdlChars()));
            }
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "dataset",args[0],"items",captures,"mockFirstCalls",captures.size(),"providerCalls",0,
                "boundary","actual ask -> generator -> in-process ChatModel; not provider transport")),StandardOpenOption.CREATE_NEW);
        System.out.println("FIRST_CALL_CAPTURE "+captures.size()+" providerCalls=0");
    }
}
