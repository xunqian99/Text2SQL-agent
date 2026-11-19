package com.text2sql.agent.orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.*;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.tool.*;
import com.text2sql.agent.validation.*;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.text2sql.agent.orchestrator.RealSendBoundaryProbe4.*;
/** Remaining independent constructor, healthy entry, and tool output accounting controls. */
public final class SupplementalBoundaryProbe4 {
  public static void main(String[] args)throws Exception {
    root=Path.of(args[0]);Files.createDirectories(root.resolve("ledgers"));correctedSubset=false;
    var constructorRows=new ArrayList<Map<String,Object>>();
    for(String name:List.of("shared-catalog","generator","orchestrator","tool-executor")) {
      boolean failedClosed=false;String exception="";
      try(var f=new F("required-dependency-"+name,false,false,false)) {
        try {switch(name){
          case "shared-catalog"->new AuthorizedSchemaCatalog(null);
          case "generator"->new LlmSqlGenerator(f.p,new PromptTemplate(f.p),f.log,null);
          case "orchestrator"->new Text2SqlOrchestrator(q->PART,null,f.gen,f.validator,f.db,mock(com.text2sql.agent.clarification.AmbiguityDetector.class),f.cache,f.checker,new AgentLoop(f.gen,new PromptTemplate(f.p),f.tools,f.p),f.p);
          case "tool-executor"->new ToolExecutor(null,f.db,f.validator,f.plans);
        }}catch(NullPointerException e){failedClosed=true;exception=e.toString();}
        constructorRows.add(Map.of("id","mandatory-"+name,"passed",failedClosed&&f.sends.get()==0,"exception",exception,"actualModelCallBoundary",f.sends.get()));
      }
    }
    for(boolean budget:List.of(false,true))for(boolean dynamic:List.of(false,true))for(String entry:List.of("generate","correction","messages","tools")) {
      String id="healthy-direct-"+budget+"-byok"+dynamic+"-"+entry;var f=new F(id,budget,false,dynamic);
      run(id,f,()->f.direct(entry),r->r.success()&&count(f,r,1)&&f.sql()==0&&retained(f,r,1),"all real direct entry methods still send with healthy catalog; budget enabled/cost disabled and BYOK remain usable");
    }
    for(String entry:List.of("generate","correction","messages","tools")) {
      String id="quota-rejects-byok-"+entry;var f=new F(id,true,true,true);f.mode="empty";
      run(id,f,()->f.direct(entry),r->r.status()==AgentResponse.Status.RESOURCE_LIMITED&&count(f,r,0)&&r.budget().termination().equals("BYOK_DISABLED_BY_COST_QUOTA")&&((Number)f.quota.snapshot().get("knownCalls")).intValue()==0&&((Number)f.quota.snapshot().get("unknownCalls")).intValue()==0,"cost quota rejects forbidden BYOK before resolve/send and leaves zero reservation");
    }
    for(boolean budget:List.of(false,true)) {
      String id="healthy-rewrite-fallback-"+budget;var f=new F(id,budget,budget,false);String sid=f.seed();f.answer=(n,p)->response(n==1?"":SQL,true,false,n);
      run(id,f,()->f.orchestrator.ask("那2017年的呢？",sid,null,true),r->r.success()&&count(f,r,2)&&f.sql()==1&&retained(f,r,2)&&f.sessions.get(sid).map(s->s.isClosed()).orElse(false),"healthy natural rewrite parser fallback retains first usage and allows SQL generation");
      id="healthy-auto-correction-"+budget;var g=new F(id,budget,budget,false);g.answer=(n,p)->response(n==1?"SELECT c.invented FROM customers c LIMIT 1":SQL,true,false,n);
      run(id,g,g::ask,r->r.success()&&count(g,r,2)&&g.sql()==1&&retained(g,r,2),"healthy rejected SQL can be corrected by real generator with combined usage");
      id="healthy-agent-round2-"+budget;var h=new F(id,budget,budget,false);h.agent(2);h.answer=(n,p)->n==1?tool("valid-tool"):response(SQL,true,false,n);
      run(id,h,h::ask,r->r.success()&&count(h,r,2)&&h.sql()==2&&retained(h,r,2),"healthy next tool round still works, preserving both calls");
      id="agent-nontool-unparseable-after-call-"+budget;var j=new F(id,budget,budget,false);j.agent(1);j.afterFirstFault="empty";j.answer=(n,p)->response("",true,false,n);
      run(id,j,j::ask,r->unavailable(r)&&count(j,r,1)&&j.sql()==0&&retained(j,r,1),"tool-mode first response without tool calls or SQL still spent first known usage; subsequent directory fault must retain it");
      id="agent-finalize-truncated-after-two-calls-"+budget;var k=new F(id,budget,budget,false);k.agent(1);k.answer=(n,p)->{if(n==1)return tool("unknown-tool");k.mode="empty";return response(SQL,true,true,n);};
      run(id,k,k::ask,r->unavailable(r)&&count(k,r,2)&&k.sql()==0&&retained(k,r,2),"first tool call plus truncated finalize both spent; terminal directory fault must retain both known call records");
    }
    var output=new LinkedHashMap<String,Object>();output.put("candidateSourceCommit","9d0c3908ec461f2d92821e5a41d9aa42a6b50390");output.put("constructorTotal",constructorRows.size());output.put("constructorPassed",constructorRows.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count());output.put("constructors",constructorRows);output.put("total",ROWS.size());output.put("passed",ROWS.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count());output.put("failed",ROWS.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count());output.put("rows",ROWS);output.put("providerCalls",0);output.put("databaseConnections",0);
    Files.writeString(root.resolve("supplemental-results-first.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output),StandardOpenOption.CREATE_NEW);
    System.out.println("SUPPLEMENTAL total="+ROWS.size()+" passed="+output.get("passed")+" failed="+output.get("failed")+" constructors="+output.get("constructorPassed")+"/"+constructorRows.size()+" providerCalls=0");
  }
}
