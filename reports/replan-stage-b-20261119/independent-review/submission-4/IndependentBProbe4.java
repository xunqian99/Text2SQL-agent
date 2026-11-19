package com.text2sql.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.cache.SemanticCache;
import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.*;
import com.text2sql.agent.generation.*;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.orchestrator.*;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.tool.*;
import com.text2sql.agent.validation.*;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Independent synthesized boundary probes: no Spring application, transport, or vendor client. */
public final class IndependentBProbe4 {
  static final String Q="独立探针：订单与客户";
  static final String SQL="SELECT o.order_id,c.city FROM orders o JOIN customers c ON o.customer_id=c.customer_id LIMIT 2";
  static final LlmCallRecord CALL=LlmCallRecord.of("in-memory-independent",11,3,14,1,0,0);
  static SchemaContext.Table t(String n,String... cs){return new SchemaContext.Table(n,null,Arrays.stream(cs).map(c->new SchemaContext.Column(c,"text",false,null)).toList());}
  static final SchemaContext PART=new SchemaContext(List.of(t("orders","order_id")),List.of(),"","PARTIAL_ONLY");
  static final SchemaContext FULL=new SchemaContext(List.of(t("orders","order_id","customer_id"),t("customers","customer_id","city")),List.of(),"","FULL_SECRET_MARKER");
  static final List<Map<String,Object>> ROWS=new ArrayList<>();
  static final class F {
    final AgentProperties p=new AgentProperties();
    SchemaProvider sp=mock(SchemaProvider.class);
    final SchemaCatalog catalog=mock(SchemaCatalog.class);
    final LlmSqlGenerator gen=mock(LlmSqlGenerator.class);
    final SqlExecutor db=mock(SqlExecutor.class);
    final QueryPlanAnalyzer plan=mock(QueryPlanAnalyzer.class);
    final SqlValidator validator=new SqlValidator(p);
    final SemanticCache cache=new SemanticCache(p);
    final AuthorizedSchemaCatalog auth=new AuthorizedSchemaCatalog(catalog);
    final ToolExecutor tools=new ToolExecutor(auth,db,validator,plan);
    final List<String> events=new ArrayList<>();
    F(){
      p.getSelfCorrection().setEnabled(true);p.getSelfCorrection().setMaxAttempts(2);
      p.getClarification().setEnabled(false);p.getResultChecker().setEnabled(false);
      when(sp.provide(anyString())).thenReturn(PART);
      when(sp.fullSchema()).thenAnswer(i->{events.add("fullSchema");return FULL;});
      when(catalog.full()).thenAnswer(i->{events.add("fullSchema");return FULL;});
      when(gen.generate(anyString(),any())).thenAnswer(i->{events.add("generate");return new GeneratedSql(SQL,SQL,CALL);});
      when(gen.generateCorrection(anyString(),any(),anyString(),anyString(),any())).thenReturn(new GeneratedSql(SQL,SQL,CALL));
      when(db.execute(anyString())).thenReturn(new QueryResult(List.of("order_id","city"),List.of(List.of("o1","c1")),false,1));
      when(plan.analyze(anyString())).thenReturn(QueryPlanAnalyzer.PlanAnalysis.safe(1,1,"{}"));
      events.clear();clearInvocations(gen,sp,catalog,db,plan);
    }
    Text2SqlOrchestrator o(){return new Text2SqlOrchestrator(sp,auth,gen,validator,db,mock(AmbiguityDetector.class),cache,new ResultChecker(),new AgentLoop(gen,new PromptTemplate(p),tools,p),p);}
    void mode(String m){
      if(m.equals("unimplemented"))sp=q->PART;
      else doAnswer(i->{events.add("fullSchema");return fault(m);}).when(sp).fullSchema();
      doAnswer(i->fault(m)).when(catalog).full();
      events.clear();clearInvocations(gen,catalog,db,plan);
    }
    Object fault(String m){return switch(m){case "null"->null;case "empty"->new SchemaContext(List.of(),List.of(),"","");case "throws"->throw new IllegalStateException("INDEPENDENT_CONNECTION_SECRET");case "unimplemented"->throw new UnsupportedOperationException("unimplemented");default->throw new IllegalArgumentException(m);};}
    long calls(Object obj,String method){return mockingDetails(obj).getInvocations().stream().filter(i->i.getMethod().getName().equals(method)).count();}
    long model(){return mockingDetails(gen).getInvocations().stream().filter(i->Set.of("generate","generateCorrection","callWithTools").contains(i.getMethod().getName())).count();}
    long exec(){return calls(db,"execute");}long plans(){return calls(plan,"analyze");}
  }
  static boolean unavailable(AgentResponse r){return r.violations().stream().anyMatch(v->v.startsWith("[SCHEMA_UNAVAILABLE]"));}
  static void row(String id,boolean pass,F f,Object result,String expected){
    var r=new LinkedHashMap<String,Object>();r.put("id",id);r.put("passed",pass);r.put("expected",expected);
    r.put("modelCalls",f.model());r.put("sqlExecutions",f.exec());r.put("planCalls",f.plans());
    r.put("events",new ArrayList<>(f.events));r.put("result",result);ROWS.add(r);
  }
  public static void main(String[] args)throws Exception{
    for(String mode:List.of("null","empty","throws","unimplemented")){
      var f=new F();f.mode(mode);var r=f.o().ask(Q);
      row("normal-unavailable-"+mode,unavailable(r)&&f.model()<=1&&f.exec()==0&&f.plans()==0,f,r,"SCHEMA_UNAVAILABLE; model<=1; SQL/EXPLAIN=0");
      f=new F();f.mode(mode);f.p.getCache().setEnabled(true);var o=f.o();f.cache.put(f.cache.key(Q,o.cacheSignature()),SQL);r=o.ask(Q);
      row("cache-unavailable-"+mode,unavailable(r)&&f.model()==0&&f.exec()==0&&f.plans()==0,f,r,"SCHEMA_UNAVAILABLE; model/SQL/EXPLAIN=0");
      for(var reason:List.of(GenerationException.Reason.CALL_FAILED,GenerationException.Reason.UNPARSEABLE)){
        f=new F();f.mode(mode);final F observed=f;final int[] n={0};
        doAnswer(i->{observed.events.add("generate-"+(++n[0]));if(n[0]==1)throw new GenerationException(reason,"independent first generation failed",CALL);return new GeneratedSql(SQL,SQL,CALL);}).when(f.gen).generate(anyString(),any());
        r=f.o().ask(Q);
        row("generation-fallback-unavailable-"+mode+"-"+reason,unavailable(r)&&f.model()<=1&&f.exec()==0&&f.plans()==0,f,r,"Unavailable directory must not admit second model generation; model<=1; SQL/EXPLAIN=0");
      }
      f=new F();f.mode(mode);r=f.o().askWithFixedSql(Q,SQL,null);
      row("fixed-unavailable-"+mode,unavailable(r)&&f.model()==0&&f.exec()==0&&f.plans()==0,f,r,"SCHEMA_UNAVAILABLE; model/SQL/EXPLAIN=0");
      for(var type:List.of(ToolType.INSPECT_TABLE,ToolType.INSPECT_COLUMN,ToolType.SAMPLE_QUERY,ToolType.CHECK_JOIN,ToolType.EXPLAIN_QUERY)){
        f=new F();f.mode(mode);var tr=f.tools.execute(new ToolCall(type,SQL,""),PART);
        row("tool-unavailable-"+mode+"-"+type,tr.schemaUnavailable()&&f.model()==0&&f.exec()==0&&f.plans()==0,f,tr,"Fatal catalog marker; model/SQL/EXPLAIN=0");
      }
    }
    for(String sql:List.of("SELECT x.id FROM invented x LIMIT 1","SELECT o.city FROM orders o LIMIT 1","SELECT * FROM semantic_cache LIMIT 1","SELECT * FROM llm_call_log LIMIT 1","SELECT * FROM conversation_session LIMIT 1","SELECT * FROM conversation_turn LIMIT 1","SELECT * FROM schema_chunks LIMIT 1","SELECT * FROM archive.customers LIMIT 1","SELECT * FROM pg_catalog.pg_class LIMIT 1","SELECT * FROM information_schema.tables LIMIT 1","SELECT * FROM orders UNION ALL SELECT * FROM semantic_cache LIMIT 1","SELECT * FROM orders WHERE EXISTS(SELECT 1 FROM semantic_cache) LIMIT 1","WITH safe AS (SELECT * FROM archive.customers) SELECT * FROM safe LIMIT 1","SELECT * FROM customers c JOIN semantic_cache sc ON TRUE LIMIT 1","SELECT * FROM \"semantic_cache\" LIMIT 1","SELECT * FROM \"archive\".\"customers\" LIMIT 1")){
      var f=new F();var r=f.o().askWithFixedSql(Q,sql,null);
      row("deny-final-"+(ROWS.size()+1),!r.success()&&f.exec()==0&&f.plans()==0,f,r,"Unauthorized/missing/misowned object rejected before SQL/EXPLAIN");
      for(var type:List.of(ToolType.SAMPLE_QUERY,ToolType.EXPLAIN_QUERY)){
        f=new F();var tr=f.tools.execute(new ToolCall(type,sql,""),PART);
        row("deny-tool-"+type+"-"+(ROWS.size()+1),!tr.success()&&f.exec()==0&&f.plans()==0,f,tr,"Unauthorized/missing/misowned object rejected before SQL/EXPLAIN");
      }
    }
    for(var type:List.of(ToolType.INSPECT_TABLE,ToolType.INSPECT_COLUMN,ToolType.CHECK_JOIN))for(String bad:List.of("semantic_cache","archive.customers","invented")){
      var f=new F();String arg=type==ToolType.CHECK_JOIN?"orders,"+bad+",orders.customer_id="+bad+".customer_id":type==ToolType.INSPECT_COLUMN?bad+".id":bad;
      var r=f.tools.execute(new ToolCall(type,arg,""),PART);
      row("deny-special-tool-"+type+"-"+bad,!r.success()&&f.exec()==0&&f.plans()==0,f,r,"Invalid table rejected; SQL/EXPLAIN=0");
    }
    var f=new F();var r=f.o().ask(Q);row("positive-omitted-alias-join",r.success()&&r.schemaTableCount()==1&&f.model()==1&&f.exec()==1,f,r,"Missing retrieved table and column accepted through full catalog; retrieval count=1");
    for(var type:List.of(ToolType.SAMPLE_QUERY,ToolType.CHECK_JOIN,ToolType.EXPLAIN_QUERY)){
      f=new F();String arg=type==ToolType.CHECK_JOIN?"orders,customers,orders.customer_id=customers.customer_id":SQL;var tr=f.tools.execute(new ToolCall(type,arg,""),PART);
      row("positive-tool-"+type,tr.success(),f,tr,"Full authorized table/columns available to tool");
    }
    var root=new LinkedHashMap<String,Object>();root.put("total",ROWS.size());root.put("passed",ROWS.stream().filter(x->Boolean.TRUE.equals(x.get("passed"))).count());root.put("failed",ROWS.stream().filter(x->Boolean.FALSE.equals(x.get("passed"))).count());root.put("providerCalls",0);root.put("databaseConnections",0);root.put("rows",ROWS);
    Files.writeString(Path.of(args[0]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root),StandardOpenOption.CREATE_NEW);
    System.out.println("INDEPENDENT_PROBE total="+root.get("total")+" passed="+root.get("passed")+" failed="+root.get("failed")+" providerCalls=0");
  }
}
