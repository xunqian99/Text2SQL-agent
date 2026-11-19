package com.text2sql.agent.orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatResponse;
import java.nio.file.*;
import java.util.*;
import static com.text2sql.agent.orchestrator.RealSendBoundaryProbe5.*;
/** Known response metadata with empty choices is distinct from an unsent call or unknown provider usage. */
public final class EmptyChoicesUsageProbe5 {
  public static void main(String[] args)throws Exception {
    root=Path.of(args[0]);Files.createDirectories(root.resolve("ledgers"));correctedSubset=false;
    for(boolean budget:List.of(false,true)){
      String id="empty-choices-known-usage-budget"+budget;var f=new F(id,budget,budget,false);f.agent(1);f.afterFirstFault="empty";
      f.answer=(n,p)->new ChatResponse(List.of(),response(SQL,true,false,n).getMetadata());
      run(id,f,f::ask,r->unavailable(r)&&count(f,r,1)&&f.sql()==0&&f.explain()==0&&retained(f,r,1),"response metadata contains known usage A, even with empty choices; catalog fault after real send must retain known tokens and cost, with no optional second call");
    }
    var result=Map.of("candidateSourceCommit","37017182d67a1fba7d16b399cc616565a784aac0","total",ROWS.size(),"passed",ROWS.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count(),"failed",ROWS.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count(),"rows",ROWS,"providerCalls",0,"databaseConnections",0);
    Files.writeString(root.resolve("empty-choices-results-first.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
    System.out.println("EMPTY_CHOICES_USAGE total="+result.get("total")+" passed="+result.get("passed")+" failed="+result.get("failed")+" providerCalls=0");
  }
}
