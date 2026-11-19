import com.fasterxml.jackson.databind.*;
import com.text2sql.agent.Text2SqlAgentApplication;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.validation.ExtremumProjectionChecker;
import com.text2sql.agent.validation.ResultChecker;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real protected HTTP/DB/checker wiring; model is always a local Mockito substitute. */
public class ProjectionAcceptance {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final String QUESTION = "容量最大的仓库是哪一个？";
    static final String OMITTED = "SELECT warehouse_id FROM warehouses ORDER BY capacity DESC LIMIT 1";
    static final String FIXED = "SELECT warehouse_id,capacity FROM warehouses ORDER BY capacity DESC LIMIT 1";
    record Case(String id,String question,String firstSql,String correctionSql,int calls,boolean repaired) { }
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]).toAbsolutePath(); Files.createDirectory(dir);
        String token=UUID.randomUUID().toString()+UUID.randomUUID();
        String auth="Basic "+Base64.getEncoder().encodeToString(("demo:"+token).getBytes(StandardCharsets.UTF_8));
        var app=new SpringApplication(Text2SqlAgentApplication.class);
        app.addInitializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe-auth",Map.of("agent.access.token",token))));
        var calls=new AtomicInteger(); var localCalls=new AtomicInteger();
        var selected=new AtomicReference<Case>();
        List<Object> requests=new ArrayList<>();
        try(var context=(ServletWebServerApplicationContext)app.run(
                "--spring.profiles.active=local,demo,protected","--server.port=0","--server.address=127.0.0.1",
                "--agent.eval.enabled=false","--agent.cache.enabled=false","--agent.conversation.enabled=false",
                "--agent.self-correction.enabled=true","--agent.self-correction.max-attempts=2","--agent.result-checker.enabled=true",
                "--agent.llm.api-key=","--agent.llm.base-url=http://127.0.0.1:1",
                "--agent.cost-quota.state-file="+dir.resolve("ledger.json"),"--agent.access.burst=100",
                "--agent.access.requests-per-minute=60000","--agent.observability.enabled=false")) {
            var checker=context.getBean(ExtremumProjectionChecker.class);
            var model=mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenAnswer(i -> {
                calls.incrementAndGet(); int n=localCalls.incrementAndGet(); Case test=selected.get();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(n==1?test.firstSql():test.correctionSql()))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(10,5)).build());
            });
            ReflectionTestUtils.setField(context.getBean(LlmSqlGenerator.class),"chatModel",model);
            var cases=List.of(
                new Case("omission-repaired",QUESTION,OMITTED,FIXED,2,true),
                new Case("mislabel-retains-first",QUESTION,OMITTED,"SELECT warehouse_id AS capacity FROM warehouses LIMIT 1",2,false),
                new Case("unknown-lineage-retains-first",QUESTION,OMITTED,"SELECT warehouse_id,capacity*0 AS capacity FROM warehouses LIMIT 1",2,false),
                new Case("cte-repaired",QUESTION,OMITTED,"WITH ranked AS(SELECT warehouse_id,capacity AS n FROM warehouses ORDER BY capacity DESC LIMIT 1) SELECT warehouse_id,n FROM ranked",2,true),
                new Case("explicit-id-only",QUESTION+"只返回仓库编号",OMITTED,FIXED,1,false),
                new Case("already-complete",QUESTION,FIXED,FIXED,1,true));
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            int port=context.getWebServer().getPort();
            for(Case test:cases) {
                selected.set(test);localCalls.set(0);
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ask"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization",auth).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("question",test.question())))).build();
                var response=http.send(request,HttpResponse.BodyHandlers.ofString());
                var body=JSON.readTree(response.body());
                assertThat(response.statusCode()).as(test.id()).isEqualTo(200);
                assertThat(body.path("status").asText()).isEqualTo("SUCCESS");
                assertThat(localCalls.get()).as(test.id()).isEqualTo(test.calls());
                assertThat(body.path("budget").path("modelCalls").asInt()).isEqualTo(test.calls());
                assertThat(body.path("budget").path("unknownUsageCalls").asInt()).isZero();
                String sql=body.path("sql").asText();
                if(test.repaired()) assertThat(checker.inspect(QUESTION,sql).status()).isEqualTo(ExtremumProjectionChecker.Status.PRESENT);
                else assertThat(sql).as(test.id()).isEqualTo(OMITTED);
                requests.add(Map.of("id",test.id(),"question",test.question(),"httpStatus",response.statusCode(),
                    "syntheticCalls",localCalls.get(),"response",body));
            }
            write(dir.resolve("requests.json"),requests);

            var props=new AgentProperties();props.getEval().setSplit("all");
            var originals=new EvalItemLoader(props).load();
            assertThat(originals).hasSize(200);
            Map<String,String> questions=new HashMap<>();
            List<Object> gold=new ArrayList<>();
            for(var item:originals) {
                questions.put(item.id(),item.question());
                var decision=checker.inspect(item.question(),item.goldSql());
                assertThat(decision.status()).as("gold "+item.id()).isNotEqualTo(ExtremumProjectionChecker.Status.MISSING);
                gold.add(Map.of("id",item.id(),"question",item.question(),"decision",decision));
            }
            Path historical=Path.of("reports/clarification-candidate-dev-screen-20261119/eval-20261119-085723.json");
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(historical)));
            assertThat(hash).isEqualTo("6d7629616a57d67c21d43460246ff79d2893420de9f8883101f2a4e9cfe83e2a");
            List<Object> replay=new ArrayList<>(); List<String> missing=new ArrayList<>();
            for(var item:JSON.readTree(historical.toFile()).path("itemEvidence")) {
                if(!item.path("status").asText().equals("SUCCESS"))continue;
                String id=item.path("id").asText();
                var decision=checker.inspect(questions.get(id),item.path("finalSql").asText());
                if(decision.status()==ExtremumProjectionChecker.Status.MISSING)missing.add(id);
                replay.add(Map.of("id",id,"historicalCorrect",item.path("correct").asBoolean(),"decision",decision));
            }
            assertThat(replay).hasSize(82);assertThat(missing).containsExactly("T1-028");
            write(dir.resolve("offline-replay.json"),Map.of("policy",ResultChecker.POLICY_VERSION,
                "historicalReportSha256",hash,"gold",gold,"executedReplay",replay,"missing",missing,
                "rescored",false,"externalProviderCalls",0));
        }
        write(dir.resolve("summary.json"),Map.of("passed",true,"httpCases",requests.size(),"syntheticModelCalls",calls.get(),
            "externalProviderCalls",0,"providerTokens",0,"providerCostYuan",0,"contextClosed",true,
            "scope","Real protected HTTP and PostgreSQL with local synthetic model; no model accuracy claim."));
        System.out.println("PROJECTION_ACCEPTANCE cases="+requests.size()+" synthetic="+calls.get()+" external=0");
    }
    static void write(Path path,Object value)throws Exception {
        Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n",StandardOpenOption.CREATE_NEW);
    }
}
