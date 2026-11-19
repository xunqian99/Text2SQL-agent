package com.text2sql.agent.orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.observability.EvaluationTrace;
import org.springframework.ai.chat.model.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static com.text2sql.agent.orchestrator.RealSendBoundaryProbe5.*;
/** Independent received-response accounting at each real generator entry, never a model transport. */
public final class FourEntryUsageProbe5 {
  record Config(String id,boolean budget,boolean quota,boolean byok){}
  static final List<Config> CONFIGS=List.of(new Config("sync-global",false,false,false),new Config("budget-global",true,false,false),new Config("budget-quota-global",true,true,false),new Config("sync-byok",false,false,true),new Config("budget-byok",true,false,true));
  static ChatResponse abnormal(String shape,int n){
    if(shape.equals("null-response"))return null;
    var metadata=response(SQL,!shape.equals("empty-choices-unknown"),false,n).getMetadata();
    if(shape.equals("null-message-known")){Generation generation=mock(Generation.class);return new ChatResponse(List.of(generation),metadata);}
    return new ChatResponse(List.of(),metadata);
  }
  static boolean unknown(F f,AgentResponse r){
    var calls=EvaluationTrace.current().calls();
    if(calls.size()!=1||calls.get(0).usage()!=null||!calls.get(0).status().contains("UNKNOWN_USAGE"))return false;
    if(f.budgetEnabled&&(r.budget()==null||r.budget().unknownUsageCalls()!=1))return false;
    if(f.quotaEnabled){var q=f.quota.snapshot();return ((Number)q.get("knownCalls")).intValue()==0&&((Number)q.get("unknownCalls")).intValue()==1&&((Number)q.get("pendingCalls")).intValue()==0&&new BigDecimal(q.get("heldYuan").toString()).compareTo(new BigDecimal("0.10"))==0;}
    return true;
  }
  public static void main(String[] args)throws Exception {
    root=Path.of(args[0]);Files.createDirectories(root.resolve("ledgers"));correctedSubset=false;
    for(var config:CONFIGS)for(String entry:List.of("generate","correction","messages","tools"))for(String shape:List.of("empty-choices-known","empty-choices-unknown","null-response","null-message-known")){
      String id="received-usage-"+config.id()+"-"+entry+"-"+shape;var f=new F(id,config.budget(),config.quota(),config.byok());f.afterFirstFault="empty";f.answer=(n,p)->abnormal(shape,n);
      boolean known=shape.endsWith("-known");
      run(id,f,()->f.direct(entry),r->{var calls=EvaluationTrace.current().calls();boolean record=known?retained(f,r,1)&&calls.size()==1&&calls.get(0).usage()!=null&&calls.get(0).usage().totalTokens()==26&&(!f.budgetEnabled||r.budget().unknownUsageCalls()==0):unknown(f,r);return count(f,r,1)&&f.sql()==0&&f.explain()==0&&r.violations().stream().anyMatch(v->v.startsWith("[CALL_FAILED]"))&&record;},"nonnull known metadata must produce exactly one known call record before content failure; absent usage/null response stays explicitly unknown; directory fault after call cannot cause second send");
    }
    var result=Map.of("candidateSourceCommit","37017182d67a1fba7d16b399cc616565a784aac0","total",ROWS.size(),"passed",ROWS.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count(),"failed",ROWS.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count(),"rows",ROWS,"providerCalls",0,"databaseConnections",0);
    Files.writeString(root.resolve("four-entry-usage-results-first.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
    System.out.println("FOUR_ENTRY_USAGE total="+result.get("total")+" passed="+result.get("passed")+" failed="+result.get("failed")+" providerCalls=0");
  }
}
