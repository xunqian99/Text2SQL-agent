package com.text2sql.agent.orchestrator;

import com.fasterxml.jackson.databind.*;
import com.text2sql.agent.Text2SqlAgentApplication;
import com.text2sql.agent.access.ApiAdmissionGate;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.session.SessionStore;
import com.text2sql.agent.tool.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real protected HTTP + real read-only SQL, synthetic ChatModel, no provider endpoint. */
public final class CatalogHttpAcceptanceProbe {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static final String SQL="SELECT st.ticket_id, tc.category_name FROM support_tickets st JOIN ticket_categories tc ON st.category_id=tc.category_id LIMIT 5";
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]); Files.createDirectory(dir);
        String token=UUID.randomUUID().toString();
        String auth="Basic "+Base64.getEncoder().encodeToString(("demo:"+token).getBytes(StandardCharsets.UTF_8));
        var app=new SpringApplication(Text2SqlAgentApplication.class);
        app.addInitializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe-token",Map.of("agent.access.token",token))));
        var calls=new AtomicInteger(); var sql=new AtomicReference<>(SQL); var evidence=new ArrayList<Object>();
        try(var c=(ServletWebServerApplicationContext)app.run("--spring.profiles.active=local,demo,protected","--server.port=0",
                "--server.address=127.0.0.1","--agent.eval.enabled=false","--agent.observability.enabled=false",
                "--agent.llm.api-key=","--agent.llm.base-url=http://127.0.0.1:1","--agent.llm.model=synthetic-catalog-probe",
                "--agent.llm.input-price-per-1k=1","--agent.llm.output-price-per-1k=2","--agent.llm.cache-hit-price-per-1k=0.1",
                "--agent.access.burst=100","--agent.access.requests-per-minute=60000",
                "--agent.cost-quota.state-file="+dir.resolve("cost-ledger.json"),"--agent.cost-quota.total-yuan=10",
                "--agent.cost-quota.reservation-yuan=0.10","--agent.self-correction.enabled=false","--agent.result-checker.enabled=false")) {
            String base="http://127.0.0.1:"+c.getWebServer().getPort();
            var model=mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenAnswer(i -> { calls.incrementAndGet(); return new ChatResponse(List.of(new Generation(
                    new AssistantMessage(sql.get()))),ChatResponseMetadata.builder().usage(new DefaultUsage(10,5)).build()); });
            ReflectionTestUtils.setField(c.getBean(LlmSqlGenerator.class),"chatModel",model);
            var orch=c.getBean(Text2SqlOrchestrator.class); var full=c.getBean(SchemaCatalog.class).full();
            assertThat(full.tables()).hasSize(37);
            var t=full.tables().stream().filter(x->x.name().equals("support_tickets")).findFirst().orElseThrow();
            var pruned=new SchemaContext(List.of(new SchemaContext.Table(t.name(),t.comment(),t.columns().stream()
                    .filter(x->x.name().equals("ticket_id")).toList())),List.of(),"","CREATE TABLE support_tickets(ticket_id text);");
            var unavailable=new AtomicBoolean();
            SchemaProvider provider=new SchemaProvider() {
                public SchemaContext provide(String q) { return pruned; }
                public SchemaContext fullSchema() { return unavailable.get()?null:full; }
            };
            ReflectionTestUtils.setField(orch,"schemaProvider",provider);
            var db=spy(c.getBean(SqlExecutor.class)); ReflectionTestUtils.setField(orch,"executor",db);
            var tools=c.getBean(ToolExecutor.class); ReflectionTestUtils.setField(tools,"sqlExecutor",db);
            var body=Map.of("question","列出工单与类别","sessionId","catalog-positive","endSession",true);
            var denied=send(base,null,body); assertThat(denied.path("http").asInt()).isEqualTo(401); assertThat(calls).hasValue(0);
            evidence.add(Map.of("case","unauthenticated","http",401,"modelCalls",0));
            var positive=send(base,auth,body); assertThat(positive.path("http").asInt()).isEqualTo(200);
            assertThat(positive.path("body").path("rows").size()).isEqualTo(5); assertThat(positive.path("body").path("schemaTableCount").asInt()).isEqualTo(1);
            verify(db).execute(SQL); assertThat(c.getBean(SessionStore.class).get("catalog-positive").orElseThrow().isClosed()).isTrue();
            evidence.add(Map.of("case","omitted-table-pruned-column","response",positive,"fullTables",37,"generationTables",1));
            for(var type:List.of(ToolType.SAMPLE_QUERY,ToolType.CHECK_JOIN,ToolType.EXPLAIN_QUERY)) {
                String arg=type==ToolType.CHECK_JOIN ? "support_tickets, ticket_categories, support_tickets.category_id=ticket_categories.category_id":SQL;
                var r=tools.execute(new ToolCall(type,arg,""),pruned); assertThat(r.success()).as(type.toString()).isTrue();
                evidence.add(Map.of("case","real-db-"+type,"success",true));
            }
            for(String bad:List.of("SELECT x.missing FROM ticket_categories x LIMIT 5","SELECT * FROM semantic_cache LIMIT 5",
                    "SELECT * FROM archive.ticket_categories LIMIT 5","SELECT * FROM invented LIMIT 5")) {
                clearInvocations(db); sql.set(bad); var r=send(base,auth,Map.of("question","列出工单类别","sessionId","catalog-negative"));
                assertThat(r.path("http").asInt()).isEqualTo(422); verifyNoInteractions(db); evidence.add(Map.of("case","reject-"+bad,"response",r,"businessExecutions",0));
            }
            clearInvocations(db); unavailable.set(true); sql.set(SQL); int before=calls.get();
            var r=send(base,auth,Map.of("question","列出工单类别","sessionId","catalog-unavailable","endSession",true));
            assertThat(r.path("http").asInt()).isEqualTo(422); assertThat(r.toString()).contains("SCHEMA_UNAVAILABLE");
            assertThat(calls.get()-before).isEqualTo(1); verifyNoInteractions(db);
            assertThat(c.getBean(SessionStore.class).get("catalog-unavailable").orElseThrow().isClosed()).isTrue();
            assertThat(c.getBean(ApiAdmissionGate.class).activeRequests()).isZero();
            evidence.add(Map.of("case","unavailable-terminal-with-session-close","response",r,"mockCalls",1,"businessExecutions",0));
            var quota=c.getBean(com.text2sql.agent.cost.CostQuota.class).snapshot();
            assertThat(quota.get("pendingCalls")).isEqualTo(0L);
            assertThat(quota.get("unknownCalls")).isEqualTo(0L);
            assertThat(((Number)quota.get("knownCalls")).intValue()).isEqualTo(calls.get());
            evidence.add(Map.of("case","cost-reservations-settled","snapshot",quota,"syntheticPricingOnly",true));
            Files.writeString(dir.resolve("summary.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "passed",evidence.size(),"cases",evidence,"providerCalls",0,"mockCalls",calls.get(),"activeRequests",0)),StandardOpenOption.CREATE_NEW);
        }
        System.out.println("CATALOG_HTTP passed="+evidence.size()+" providerCalls=0");
    }
    static JsonNode send(String base,String auth,Object body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+"/api/ask")).header("Content-Type","application/json")
                .timeout(java.time.Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if(auth!=null)b.header("Authorization",auth);
        var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
        return JSON.valueToTree(Map.of("http",r.statusCode(),"body",JSON.readTree(r.body())));
    }
}
