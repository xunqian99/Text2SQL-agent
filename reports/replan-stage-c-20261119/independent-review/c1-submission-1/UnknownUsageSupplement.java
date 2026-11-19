package independent.c1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.generation.*;
import com.text2sql.agent.observability.*;
import org.springframework.ai.chat.model.ChatResponse;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
/** Targeted adjudication: a zero-valued legacy LlmCallRecord is not the known-usage ledger. */
public final class UnknownUsageSupplement {
  public static void main(String[] args)throws Exception{
    List<Map<String,Object>> rows=new ArrayList<>();
    for(String entry:List.of("generate","correction","messages","tools")){
      try(var f=new IndependentC1Probe.F(true);var trace=EvaluationTrace.open();var realLog=new LlmCallLog(f.p)){
        Field field=LlmSqlGenerator.class.getDeclaredField("callLog");field.setAccessible(true);field.set(f.gen,realLog);
        f.responses=n->new ChatResponse(List.of());
        GenerationException failure=null;
        try{f.entry(entry,IndependentC1Probe.schema("","",List.of(),""),"未知用量边界");}catch(GenerationException e){failure=e;}
        var calls=trace.calls();boolean ok=failure!=null&&f.prompts.size()==1&&calls.size()==1&&calls.get(0).usage()==null&&calls.get(0).status().contains("UNKNOWN_USAGE");
        rows.add(Map.of("entry",entry,"passed",ok,"modelCalls",f.prompts.size(),"traceCalls",calls.size(),"traceStatus",calls.isEmpty()?"MISSING":calls.get(0).status(),"exceptionRecordHasZeroTokens",failure!=null&&failure.getCallRecord()!=null&&failure.getCallRecord().totalTokens()==0));
      }
    }
    var result=Map.of("total",rows.size(),"passed",rows.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count(),"failed",rows.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count(),"rows",rows,"providerCalls",0,"databaseConnections",0,"originalFailedAssertions",4,"originalResultsPreserved",true);
    Files.writeString(Path.of(args[0]).resolve("unknown-usage-supplement-first.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
    System.out.println("UNKNOWN_USAGE_SUPPLEMENT total="+result.get("total")+" passed="+result.get("passed")+" failed="+result.get("failed"));
    if(((Number)result.get("failed")).longValue()>0)System.exit(1);
  }
}
