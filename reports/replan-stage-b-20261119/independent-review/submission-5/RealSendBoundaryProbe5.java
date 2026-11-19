package com.text2sql.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.budget.*;
import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.cost.*;
import com.text2sql.agent.execution.*;
import com.text2sql.agent.generation.*;
import com.text2sql.agent.observability.*;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.session.*;
import com.text2sql.agent.tool.*;
import com.text2sql.agent.validation.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Independent real-generator probes. Every ChatModel is in-memory; no vendor transport. */
public final class RealSendBoundaryProbe5 {
  static final String Q="独立发送边界：订单与客户";
  static final String SQL="SELECT o.order_id,c.city FROM orders o JOIN customers c ON o.customer_id=c.customer_id LIMIT 2";
  static SchemaContext.Table t(String name,String... columns){return new SchemaContext.Table(name,null,Arrays.stream(columns).map(c->new SchemaContext.Column(c,"text",false,null)).toList());}
  static final SchemaContext PART=new SchemaContext(List.of(t("orders","order_id")),List.of(),"","ONLY_RETRIEVED_ORDER_ID");
  static final SchemaContext FULL=new SchemaContext(List.of(t("orders","order_id","customer_id"),t("customers","customer_id","city")),List.of(),"","AUTHORIZATION_SECRET_MUST_NOT_APPEAR");
  static final List<String> CORE_FAULTS=List.of("null","empty","throws","unimplemented");
  static final List<String> FAULTS=List.of("null","empty","throws","unimplemented","null-tables","null-table","null-columns","empty-columns","null-column","blank-table","internal-table","cross-schema-table","duplicate-table","blank-column","injection-column","duplicate-column");
  static final List<Map<String,Object>> ROWS=new ArrayList<>();
  static final List<Map<String,Object>> KNOWN_LIMITATIONS=new ArrayList<>();
  static Path root;
  static boolean correctedSubset;

  static SchemaContext fault(String mode) {
    return switch(mode) {
      case "healthy" -> FULL;
      case "null" -> null;
      case "empty" -> new SchemaContext(List.of(),List.of(),"","");
      case "throws" -> throw new IllegalStateException("INDEPENDENT_FAULT_CONNECTION_SECRET");
      case "unimplemented" -> throw new UnsupportedOperationException("INDEPENDENT_UNIMPLEMENTED_SECRET");
      case "null-tables" -> new SchemaContext(null,List.of(),"","");
      case "null-table" -> new SchemaContext(Arrays.asList((SchemaContext.Table)null),List.of(),"","");
      case "null-columns" -> new SchemaContext(List.of(new SchemaContext.Table("orders",null,null)),List.of(),"","");
      case "empty-columns" -> new SchemaContext(List.of(t("orders")),List.of(),"","");
      case "null-column" -> new SchemaContext(List.of(new SchemaContext.Table("orders",null,Arrays.asList((SchemaContext.Column)null))),List.of(),"","");
      case "blank-table" -> new SchemaContext(List.of(t(" ","id")),List.of(),"","");
      case "internal-table" -> new SchemaContext(List.of(t("semantic_cache","id")),List.of(),"","");
      case "cross-schema-table" -> new SchemaContext(List.of(t("archive.customers","id")),List.of(),"","");
      case "duplicate-table" -> new SchemaContext(List.of(t("orders","id"),t("ORDERS","id")),List.of(),"","");
      case "blank-column" -> new SchemaContext(List.of(t("orders"," ")),List.of(),"","");
      case "injection-column" -> new SchemaContext(List.of(t("orders","id;DROP TABLE orders")),List.of(),"","");
      case "duplicate-column" -> new SchemaContext(List.of(t("orders","order_id","ORDER_ID")),List.of(),"","");
      default -> throw new IllegalArgumentException(mode);
    };
  }
  static ChatResponse response(String text,boolean known,boolean truncated,int number) {
    int in=number==1?19:23,out=number==1?7:11,hit=number==1?5:9;
    var usage=known?new DefaultUsage(in,out,in+out,new OpenAiApi.Usage(out,in,in+out,new OpenAiApi.Usage.PromptTokensDetails(0,hit),null)):null;
    var metadata=ChatResponseMetadata.builder();if(known)metadata.usage(usage);
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text),ChatGenerationMetadata.builder().finishReason(truncated?"length":"stop").build())),metadata.build());
  }
  static ChatResponse tool(String kind) {
    String name=kind.equals("unknown-tool")?"NONEXISTENT_INDEPENDENT_TOOL":"SAMPLE_QUERY";
    String args=kind.equals("malformed-tool-json")?"{":kind.equals("invalid-tool-sql")?"{\"sql\":\"SELECT o.invented FROM orders o LIMIT 1\"}":"{\"sql\":\"SELECT order_id FROM orders LIMIT 1\"}";
    var base=response("",true,false,1);
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("independent","function",name,args))).build())),base.getMetadata());
  }
  static void field(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}

  static final class F implements AutoCloseable {
    final AgentProperties p=new AgentProperties();
    final SchemaCatalog catalog=mock(SchemaCatalog.class);
    final AuthorizedSchemaCatalog auth=new AuthorizedSchemaCatalog(catalog);
    final SqlExecutor db=mock(SqlExecutor.class);
    final QueryPlanAnalyzer plans=mock(QueryPlanAnalyzer.class);
    final SqlValidator validator=spy(new SqlValidator(p));
    final ResultChecker checker=mock(ResultChecker.class);
    final ChatModel model=mock(ChatModel.class);
    final List<String> events=new CopyOnWriteArrayList<>();
    final List<Prompt> prompts=new CopyOnWriteArrayList<>();
    final AtomicInteger sends=new AtomicInteger();
    final AtomicInteger retrievals=new AtomicInteger();
    final CostQuota quota;
    final LlmCallLog log;
    final LlmSqlGenerator gen;
    final ToolExecutor tools;
    final SemanticCache cache;
    final SessionStore sessions;
    final Text2SqlOrchestrator orchestrator;
    volatile String mode="healthy",afterFirstFault=null,afterDbFault=null,afterRetrievalFault=null;
    volatile BiFunction<Integer,Prompt,ChatResponse> answer=(n,prompt)->response(SQL,true,false,n);
    final boolean budgetEnabled,quotaEnabled,byok;
    final String key;
    F(String id,boolean budget,boolean cost,boolean dynamic)throws Exception {
      budgetEnabled=budget;quotaEnabled=cost;byok=dynamic;key=dynamic?"independent-in-memory-key":null;
      p.getLlm().setApiKey("");p.getLlm().setModel("independent-memory-model");
      p.getLlm().setBaseUrl("http://127.0.0.1:1");
      p.getLlm().setInputPricePer1k(1.2);p.getLlm().setOutputPricePer1k(2.4);p.getLlm().setCacheHitPricePer1k(0.3);
      p.getRequestBudget().setEnabled(budget);p.getRequestBudget().setTimeoutMs(15000);p.getRequestBudget().setMinRetryRemainingMs(0);p.getRequestBudget().setMaxModelCalls(3);
      p.getObservability().setEnabled(false);p.getPersistence().setEnabled(false);
      p.getClarification().setEnabled(false);p.getResultChecker().setEnabled(false);
      p.getSelfCorrection().setEnabled(true);p.getSelfCorrection().setMaxAttempts(2);
      var cp=new CostQuotaProperties();cp.setEnabled(cost);cp.setTotalYuan(new BigDecimal("10"));cp.setReservationYuan(new BigDecimal("0.10"));cp.setStateFile(root.resolve("ledgers").resolve(id+".json").toString());
      quota=new CostQuota(cp,p);log=new LlmCallLog(p);
      gen=new LlmSqlGenerator(p,new PromptTemplate(p),log,quota,auth);
      if(dynamic){Field f=LlmSqlGenerator.class.getDeclaredField("dynamicChatModels");f.setAccessible(true);((Map<String,ChatModel>)f.get(gen)).put(key,model);}
      else field(gen,"chatModel",model);
      when(catalog.full()).thenAnswer(i->{events.add("catalog:"+mode);return fault(mode);});
      when(model.call(any(Prompt.class))).thenAnswer(i->{int n=sends.incrementAndGet();Prompt prompt=i.getArgument(0);prompts.add(prompt);events.add("model.call:"+n+":budget="+(RequestBudget.current()==null?"disabled":RequestBudget.current().callNumber()));try{return answer.apply(n,prompt);}finally{if(n==1&&afterFirstFault!=null){mode=afterFirstFault;events.add("fault-after-first-call:"+mode);}}});
      when(db.execute(anyString())).thenAnswer(i->{events.add("sql:"+i.getArgument(0));if(afterDbFault!=null)mode=afterDbFault;return new QueryResult(List.of("order_id","city"),List.of(List.of("o1","c1")),false,1);});
      when(plans.analyze(anyString())).thenAnswer(i->{events.add("explain");return QueryPlanAnalyzer.PlanAnalysis.safe(1,1,"{}");});
      when(checker.check(anyString(),anyString(),any())).thenReturn(CheckResult.NORMAL);
      SchemaProvider provider=q->{retrievals.incrementAndGet();events.add("provide-only");if(afterRetrievalFault!=null)mode=afterRetrievalFault;return PART;};
      tools=new ToolExecutor(auth,db,validator,plans);cache=new SemanticCache(p);sessions=new SessionStore(p);
      orchestrator=new Text2SqlOrchestrator(provider,auth,gen,validator,db,mock(AmbiguityDetector.class),cache,checker,new AgentLoop(gen,new PromptTemplate(p),tools,p),sessions,new QuestionRewriter(gen,p),plans,p);
      clearInvocations(catalog,model,db,plans,validator,checker);
    }
    AgentResponse ask(){return orchestrator.ask(Q,null,key);}
    long sql(){return mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("execute")).count();}
    long explain(){return mockingDetails(plans).getInvocations().stream().filter(i->i.getMethod().getName().equals("analyze")).count();}
    boolean noSecret(){return prompts.stream().flatMap(x->x.getInstructions().stream()).noneMatch(m->m.getText()!=null&&m.getText().contains("AUTHORIZATION_SECRET_MUST_NOT_APPEAR"));}
    AgentResponse direct(String entry){return RequestBudgetRunner.run(Q,null,p.getRequestBudget(),()->{
      try {
        LlmCallRecord record=switch(entry){
          case "generate"->gen.generate(Q,PART,null,key).call();
          case "correction"->gen.generateCorrection(Q,PART,SQL,"independent",null,key).call();
          case "messages"->gen.callMessages(List.of(new UserMessage(Q)),"INDEPENDENT_MESSAGES",null,key).call();
          case "tools"->gen.callWithTools(List.of(new UserMessage(Q)),List.of(),"INDEPENDENT_TOOLS",null,key).call();
          default->throw new IllegalArgumentException(entry);
        };
        return AgentResponse.success(Q,SQL,false,new AgentResponse.QueryOutcome(List.of(),List.of(),false),record,1,List.of("orders"),PART.ddlText().length(),null);
      }catch(GenerationException e){return AgentResponse.rejected(Q,null,List.of("["+e.getReason()+"] "+e.getMessage()),e.getCallRecord(),1,List.of("orders"),PART.ddlText().length(),null);}
    });}
    void agent(int rounds){p.getToolUse().setEnabled(true);p.getToolUse().setTableThreshold(1);p.getToolUse().setMaxRounds(rounds);}
    String seed(){p.getConversation().setEnabled(true);p.getConversation().setRewriteWithLlm(true);String id="independent-session";var s=sessions.getOrCreate(id);s.addTurn(ConversationTurn.of(1,"2018年订单", "2018年订单",SQL,"SUCCESS","先前结果"),10);s.setLastSchema(PART);return id;}
    public void close(){log.close();quota.close();}
  }
  static boolean unavailable(AgentResponse r){return r!=null&&r.violations().stream().anyMatch(x->x.startsWith("[SCHEMA_UNAVAILABLE]"));}
  static boolean count(F f,AgentResponse r,int n){return f.sends.get()==n&&(!f.budgetEnabled||(r.budget()!=null&&r.budget().modelCalls()==n));}
  static boolean retained(F f,AgentResponse r,int n){
    var a=r.llmCall();if(a==null)return false;
    int in=n==1?19:42,out=n==1?7:18,hit=n==1?5:14;
    double expected=((in-hit)*1.2+hit*0.3+out*2.4)/1000;
    var snap=f.quota.snapshot();
    return a.promptTokens()==in&&a.completionTokens()==out&&a.totalTokens()==in+out&&a.cacheHitTokens()==hit&&a.cacheMissTokens()==in-hit&&a.promptChars()>0&&a.latencyMs()>=0&&Math.abs(a.costYuan()-expected)<1e-10
      &&(!f.quotaEnabled||(((Number)snap.get("knownCalls")).intValue()==n&&((Number)snap.get("unknownCalls")).intValue()==0&&new BigDecimal(snap.get("heldYuan").toString()).signum()==0&&Math.abs(((Number)snap.get("knownCostYuan")).doubleValue()-expected)<1e-10));
  }
  static boolean unsent(F f,AgentResponse r){var s=f.quota.snapshot();return unavailable(r)&&count(f,r,0)&&f.sql()==0&&f.explain()==0&&r.llmCall()==null&&(!f.quotaEnabled||((Number)s.get("knownCalls")).intValue()==0&&((Number)s.get("pendingCalls")).intValue()==0&&((Number)s.get("unknownCalls")).intValue()==0&&new BigDecimal(s.get("heldYuan").toString()).signum()==0);}
  static void run(String id,F f,Supplier<AgentResponse> action,Predicate<AgentResponse> assertion,String expected)throws Exception {
    if(correctedSubset&&!(id.startsWith("rewrite-post-call-")||(id.startsWith("orchestrator-constant-true-")&&id.endsWith("-fixed"))||(id.startsWith("healthy-tools-")&&id.endsWith("-invalid-tool-sql")))){f.close();return;}
    try(f;var trace=EvaluationTrace.open()){
      var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("expected",expected);row.put("budgetEnabled",f.budgetEnabled);row.put("quotaEnabled",f.quotaEnabled);row.put("byok",f.byok);
      try{AgentResponse r=action.get();row.put("response",r);row.put("passed",assertion.test(r)&&f.noSecret());}catch(Throwable e){row.put("passed",false);row.put("exception",e.toString());}
      row.put("actualModelCallBoundary",f.sends.get());row.put("sqlExecutions",f.sql());row.put("explainCalls",f.explain());row.put("retrievals",f.retrievals.get());row.put("events",new ArrayList<>(f.events));row.put("costQuota",f.quota.snapshot());row.put("traceCalls",trace.calls());row.put("promptContainsAuthorizationSecret",!f.noSecret());
      if(id.startsWith("known-limitation-bare-")){row.put("observationMatched",row.remove("passed"));row.put("scope","preexisting bare-column limitation; not a B passing assertion");KNOWN_LIMITATIONS.add(row);}else ROWS.add(row);
    }
  }
  public static void main(String[] args)throws Exception {
    root=Path.of(args[0]);correctedSubset=args.length>1&&args[1].equals("corrected-subset");Files.createDirectories(root.resolve("ledgers"));
    // Direct generator entry points must enforce the shared gate even without an orchestrator.
    for(boolean budget:List.of(false,true))for(String mode:FAULTS)for(String entry:List.of("generate","correction","messages","tools")){
      String id="direct-constant-"+budget+"-"+mode+"-"+entry;var f=new F(id,budget,budget,false);f.mode=mode;
      run(id,f,()->f.direct(entry),r->unsent(f,r),"typed directory fault; actual model.call=0; call ledger=0; reservations=0; SQL/EXPLAIN=0");
    }
    for(boolean budget:List.of(false,true))for(String mode:CORE_FAULTS)for(String entry:List.of("generate","correction","messages","tools")){
      String id="byok-constant-"+budget+"-"+mode+"-"+entry;var f=new F(id,budget,false,true);f.mode=mode;
      run(id,f,()->f.direct(entry),r->unsent(f,r),"dynamic in-memory BYOK resolution must not bypass directory gate");
    }
    for(boolean budget:List.of(false,true))for(String mode:CORE_FAULTS)for(String kind:List.of("valid-tool","malformed-tool-json","unknown-tool"))for(int rounds:List.of(1,2)){
      String id="post-call-tool-"+budget+"-"+mode+"-"+kind+"-rounds"+rounds;var f=new F(id,budget,budget,false);f.agent(rounds);f.afterFirstFault=mode;f.answer=(n,p)->n==1?tool(kind):response(SQL,true,false,n);
      run(id,f,f::ask,r->unavailable(r)&&count(f,r,1)&&f.sql()==0&&f.explain()==0&&retained(f,r,1),"after first real call, directory fault stops next round/finalize, even callback preprocessing failures; preserve first known usage");
    }
    for(boolean budget:List.of(false,true))for(String mode:CORE_FAULTS)for(String failure:List.of("CALL_FAILED","UNPARSEABLE","OUTPUT_TRUNCATED")){
      String id="post-call-fallback-"+budget+"-"+mode+"-"+failure;var f=new F(id,budget,budget,false);f.afterFirstFault=mode;
      f.answer=(n,p)->{if(n==1&&failure.equals("CALL_FAILED"))throw new IllegalStateException("independent transport failed after send");return response(n==1&&failure.equals("UNPARSEABLE")?"":SQL,true,n==1&&failure.equals("OUTPUT_TRUNCATED"),n);};
      run(id,f,f::ask,r->unavailable(r)&&count(f,r,1)&&f.sql()==0&&f.explain()==0&&(failure.equals("CALL_FAILED")?r.llmCall()==null&&(!budget||(r.budget().unknownUsageCalls()==1&&((Number)f.quota.snapshot().get("unknownCalls")).intValue()==1)):retained(f,r,1)),"sent failure cannot trigger second generation when catalog becomes unavailable; known or unknown usage stays recorded");
    }
    for(boolean budget:List.of(false,true))for(String mode:CORE_FAULTS)for(boolean suspicious:List.of(false,true)){
      String id="auto-correction-"+budget+"-"+mode+"-suspicious"+suspicious;var f=new F(id,budget,budget,false);
      if(suspicious){f.p.getResultChecker().setEnabled(true);when(f.checker.check(anyString(),anyString(),any())).thenAnswer(i->{f.mode=mode;return CheckResult.suspicious("independent","independent suspicious");});}
      else {f.answer=(n,p)->response(n==1?"SELECT c.invented FROM customers c LIMIT 1":SQL,true,false,n);doAnswer(i->{var v=(ValidationResult)i.callRealMethod();if(!v.valid()){f.mode=mode;f.events.add("fault-after-invalid-validation");}return v;}).when(f.validator).validate(anyString(),any());}
      run(id,f,f::ask,r->unavailable(r)&&count(f,r,1)&&f.sql()==(suspicious?1:0)&&retained(f,r,1),"fault activated by actual SQL validation/result check, not catalog read number; no correction send");
    }
    for(boolean budget:List.of(false,true))for(String mode:CORE_FAULTS){
      String id="explicit-post-call-"+budget+"-"+mode;var f=new F(id,budget,budget,false);f.afterFirstFault=mode;
      run(id,f,()->f.orchestrator.askWithCorrection(Q,SQL,"independent feedback"),r->unavailable(r)&&count(f,r,1)&&f.sql()==0&&retained(f,r,1),"explicit correction admission cannot predict later fault; spent usage retained; execute0");
      id="rewrite-post-call-"+budget+"-"+mode;var g=new F(id,budget,budget,false);String sid=g.seed();g.afterFirstFault=mode;g.answer=(n,p)->response(n==1?"2017年订单与客户":SQL,true,false,n);
      run(id,g,()->g.orchestrator.ask("那2017年的呢？",sid,null,true),r->unavailable(r)&&count(g,r,1)&&g.sql()==0&&retained(g,r,1)&&g.sessions.get(sid).map(ConversationSession::isClosed).orElse(false),"natural rewrite fallback retains sent usage; directory fault blocks generation; disabled persistence retains closed session in memory");
    }
    for(boolean budget:List.of(false,true))for(String mode:CORE_FAULTS)for(String route:List.of("normal","cache","fixed","explicit","rewrite","tools")){
      String id="orchestrator-constant-"+budget+"-"+mode+"-"+route;var f=new F(id,budget,budget,false);f.mode=mode;String sid=route.equals("rewrite")?f.seed():null;
      if(route.equals("cache")){f.p.getCache().setEnabled(true);f.cache.put(f.cache.key(Q,f.orchestrator.cacheSignature()),SQL);}
      if(route.equals("tools"))f.agent(1);
      Supplier<AgentResponse> action=switch(route){case "fixed"->()->RequestBudgetRunner.run(Q,null,f.p.getRequestBudget(),()->f.orchestrator.askWithFixedSql(Q,SQL,null));case "explicit"->()->f.orchestrator.askWithCorrection(Q,SQL,"independent");case "rewrite"->()->f.orchestrator.ask("那2017年的呢？",sid);default->f::ask;};
      run(id,f,action,r->unsent(f,r)&&f.retrievals.get()==0,"all constant-fault routes stop before retrieval/send; cache/fixed/correction/rewrite/tool paths fail closed");
    }
    // Healthy controls prove the gate does not disable legitimate retries, BYOK, tools or omitted objects.
    for(boolean budget:List.of(false,true))for(String failure:List.of("CALL_FAILED","UNPARSEABLE","OUTPUT_TRUNCATED")){
      String id="healthy-retry-"+budget+"-"+failure;var f=new F(id,budget,budget,false);
      f.answer=(n,p)->{if(n==1&&failure.equals("CALL_FAILED"))throw new IllegalStateException("independent sent failure");return response(n==1&&failure.equals("UNPARSEABLE")?"":SQL,true,n==1&&failure.equals("OUTPUT_TRUNCATED"),n);};
      run(id,f,f::ask,r->r.success()&&count(f,r,2)&&f.sql()==1&&(failure.equals("CALL_FAILED")?r.llmCall()!=null&&r.llmCall().totalTokens()==34&&(!budget||r.budget().unknownUsageCalls()==1):retained(f,r,2)),"healthy catalog permits second send and preserves both known usage or one unknown plus known");
    }
    for(boolean budget:List.of(false,true))for(String kind:List.of("valid-tool","malformed-tool-json","unknown-tool","invalid-tool-sql")){
      String id="healthy-tools-"+budget+"-"+kind;var f=new F(id,budget,budget,false);f.agent(1);f.answer=(n,p)->n==1?tool(kind):response(SQL,true,false,n);
      run(id,f,f::ask,r->r.success()&&count(f,r,2)&&f.sql()==(kind.equals("valid-tool")?2:1)&&retained(f,r,2),"healthy tools and recoverable callback errors still reach final SQL; exactly two sends");
    }
    for(boolean budget:List.of(false,true))for(boolean byok:List.of(false,true)){
      String id="healthy-provider-only-"+budget+"-byok"+byok;var f=new F(id,budget,false,byok);
      run(id,f,f::ask,r->r.success()&&count(f,r,1)&&f.sql()==1&&r.schemaTableCount()==1&&r.retrievedTables().equals(List.of("orders"))&&retained(f,r,1),"provide-only retrieval interface succeeds using independent full authorized catalog; no fullSchema fallback");
    }
    for(boolean budget:List.of(false,true)){
      String id="healthy-explicit-"+budget;var f=new F(id,budget,budget,false);
      run(id,f,()->f.orchestrator.askWithCorrection(Q,SQL,"independent"),r->r.success()&&count(f,r,1)&&f.sql()==1&&retained(f,r,1),"healthy explicit correction stays available");
      id="unknown-usage-after-first-"+budget;var g=new F(id,budget,budget,false);g.afterFirstFault="empty";g.answer=(n,p)->response(SQL,false,false,n);
      run(id,g,g::ask,r->unavailable(r)&&count(g,r,1)&&g.sql()==0&&(!budget||(r.budget().unknownUsageCalls()==1&&((Number)g.quota.snapshot().get("unknownCalls")).intValue()==1&&new BigDecimal(g.quota.snapshot().get("heldYuan").toString()).compareTo(new BigDecimal("0.10"))==0)),"missing usage remains unknown; already-sent reservation is not refunded");
      id="cache-after-retrieval-fault-"+budget;var h=new F(id,budget,budget,false);h.p.getCache().setEnabled(true);h.cache.put(h.cache.key(Q,h.orchestrator.cacheSignature()),SQL);h.afterRetrievalFault="empty";
      run(id,h,h::ask,r->unsent(h,r)&&h.retrievals.get()==1,"cache must acquire same authorized catalog after retrieval; no stale whitelist bypass");
      id="tool-fault-after-execution-"+budget;var j=new F(id,budget,budget,false);j.agent(1);j.afterDbFault="empty";j.answer=(n,p)->n==1?tool("valid-tool"):response(SQL,true,false,n);
      run(id,j,j::ask,r->unavailable(r)&&count(j,r,1)&&j.sql()==1&&retained(j,r,1),"completed healthy tool SQL remains counted; fault before finalize blocks second send");
    }
    for(boolean budget:List.of(false,true)){
      String id="known-limitation-bare-"+budget;var f=new F(id,budget,budget,false);f.agent(1);
      f.answer=(n,p)->{if(n!=1)return response(SQL,true,false,n);var base=tool("valid-tool");var message=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("bare-limit","function","SAMPLE_QUERY","{\"sql\":\"SELECT invented FROM orders LIMIT 1\"}"))).build();return new ChatResponse(List.of(new Generation(message)),base.getMetadata());};
      run(id,f,f::ask,r->r.success()&&count(f,r,2)&&f.sql()==2&&retained(f,r,2),"known bare-column SAMPLE limitation remains explicitly observed; real PostgreSQL must reject undefined column; not counted as B pass");
    }
    var result=new LinkedHashMap<String,Object>();result.put("candidateSourceCommit","37017182d67a1fba7d16b399cc616565a784aac0");result.put("actualSendBoundary","real LlmSqlGenerator.invoke -> in-memory ChatModel.call");result.put("total",ROWS.size());result.put("passed",ROWS.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count());result.put("failed",ROWS.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count());result.put("knownLimitationObservations",KNOWN_LIMITATIONS);result.put("providerCalls",0);result.put("providerTokens",0);result.put("providerCostYuan",0);result.put("databaseConnections",0);result.put("rows",ROWS);
    Files.writeString(root.resolve("real-send-results-first.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
    System.out.println("REAL_SEND_BOUNDARY total="+result.get("total")+" passed="+result.get("passed")+" failed="+result.get("failed")+" providerCalls=0");
  }
}
