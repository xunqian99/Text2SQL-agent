package com.text2sql.agent.orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static com.text2sql.agent.orchestrator.RealSendBoundaryProbe3.*;
/** Focused confirmation of actual SqlExtractor UNPARSEABLE after known paid-synthetic turns. */
public final class EmptyToolOutputProbe3 {
  public static void main(String[] args)throws Exception {
    root=Path.of(args[0]);Files.createDirectories(root.resolve("ledgers"));correctedSubset=false;
    for(boolean budget:List.of(false,true))for(String stage:List.of("first-turn","finalize")) {
      String id="empty-tool-output-"+stage+"-budget"+budget;var f=new F(id,budget,budget,false);f.agent(1);
      if(stage.equals("first-turn")){f.afterFirstFault="empty";f.answer=(n,p)->response("",true,false,n);}
      else f.answer=(n,p)->{if(n==1)return tool("unknown-tool");f.mode="empty";return response("",true,false,n);};
      int expected=stage.equals("first-turn")?1:2;
      run(id,f,f::ask,r->unavailable(r)&&count(f,r,expected)&&f.sql()==0&&f.explain()==0&&retained(f,r,expected),"empty non-tool output triggers actual SQL extraction failure; known usage of all sent turns remains in terminal directory rejection");
    }
    var result=Map.of("candidateSourceCommit","54d7a93","total",ROWS.size(),"passed",ROWS.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count(),"failed",ROWS.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count(),"rows",ROWS,"providerCalls",0,"databaseConnections",0);
    Files.writeString(root.resolve("empty-tool-output-results-first.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
    System.out.println("EMPTY_TOOL_OUTPUT total="+result.get("total")+" passed="+result.get("passed")+" failed="+result.get("failed")+" providerCalls=0");
  }
}
