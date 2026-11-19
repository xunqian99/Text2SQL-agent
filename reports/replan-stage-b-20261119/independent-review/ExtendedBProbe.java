package com.text2sql.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.generation.*;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.tool.*;
import com.text2sql.agent.validation.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.text2sql.agent.orchestrator.IndependentBProbe.*;

/** Additional independent admission-order and spent-usage checks for submission 2. */
public final class ExtendedBProbe {
  static final LlmCallRecord A=new LlmCallRecord("paid-synthetic-A",19,7,26,140,8,0.125,5,14);
  static final LlmCallRecord B=new LlmCallRecord("paid-synthetic-B",23,11,34,170,10,0.25,9,14);
  static final List<Map<String,Object>> rows=new ArrayList<>();
  static void add(String id,boolean pass,F f,AgentResponse r,String expected){
    var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("passed",pass);row.put("expected",expected);
    row.put("modelCalls",f.model());row.put("sqlExecutions",f.exec());row.put("planCalls",f.plans());row.put("events",new ArrayList<>(f.events));row.put("response",r);rows.add(row);
  }
  static boolean usage(AgentResponse r,LlmCallRecord expected){return expected.equals(r.llmCall());}
  public static void main(String[] args)throws Exception{
    for(String mode:List.of("null","empty","throws","unimplemented")){
      var f=new F();f.mode(mode);
      doThrow(new GenerationException(GenerationException.Reason.OUTPUT_TRUNCATED,"independent truncated",A)).when(f.gen).generate(anyString(),any());
      var r=f.o().ask(Q);
      add("truncated-directory-admission-"+mode,unavailable(r)&&f.model()==1&&f.exec()==0&&f.plans()==0&&usage(r,A),f,r,"No second call; preserve first nonzero synthetic cost/cache/token usage");
      f=new F();f.mode(mode);r=f.o().askWithCorrection(Q,SQL,"independent explicit");
      add("explicit-correction-before-model-"+mode,unavailable(r)&&f.model()==0&&f.exec()==0&&f.plans()==0&&r.llmCall()==null,f,r,"Unavailable explicit correction has model/SQL/EXPLAIN=0");
    }
    for(String mode:List.of("null","empty","throws","unimplemented"))for(boolean suspicious:List.of(false,true)){
      var f=new F();final F observed=f;final int[] seen={0};
      doAnswer(i->{observed.events.add("fullSchema-"+(++seen[0]));return seen[0]==1?FULL:observed.fault(mode);}).when(f.sp).fullSchema();
      final String initial=suspicious?"SELECT o.order_id FROM orders o WHERE 1=0 LIMIT 1":"SELECT c.invented FROM customers c LIMIT 1";
      doAnswer(i->{observed.events.add("initial-generate");return new GeneratedSql(initial,initial,A);}).when(f.gen).generate(anyString(),any());
      var o=f.o();
      if(suspicious){
        f.p.getResultChecker().setEnabled(true);
        var checker=mock(ResultChecker.class);
        when(checker.check(anyString(),anyString(),any())).thenReturn(CheckResult.suspicious("independent","independent suspicious result"));
        o=new Text2SqlOrchestrator(f.sp,f.gen,f.validator,f.db,mock(com.text2sql.agent.clarification.AmbiguityDetector.class),f.cache,checker,new AgentLoop(f.gen,new PromptTemplate(f.p),f.tools,f.p),f.p);
      }
      var r=o.ask(Q);
      boolean pass=unavailable(r)&&f.model()==1&&f.exec()==(suspicious?1:0)&&usage(r,A);
      add("automatic-correction-admission-"+mode+"-"+suspicious,pass,f,r,"Second directory fault blocks model correction; first usage and any first execution remain");
    }
    for(var reason:List.of(GenerationException.Reason.CALL_FAILED,GenerationException.Reason.UNPARSEABLE,GenerationException.Reason.OUTPUT_TRUNCATED)){
      var f=new F();final F observed=f;final int[] seen={0};
      doAnswer(i->{observed.events.add("generate-"+(++seen[0]));if(seen[0]==1)throw new GenerationException(reason,"independent recoverable",A);return new GeneratedSql(SQL,SQL,B);}).when(f.gen).generate(anyString(),any());
      var r=f.o().ask(Q);
      add("healthy-recoverable-"+reason,r.success()&&f.model()==2&&f.exec()==1&&usage(r,LlmCallRecord.combine(A,B))&&f.events.indexOf("fullSchema")<f.events.indexOf("generate-2"),f,r,"Healthy directory checked before retry; exactly two model calls; combined nonzero usage");
    }
    {
      var f=new F();doReturn(new GeneratedSql(SQL,SQL,A)).when(f.gen).generateCorrection(anyString(),any(),anyString(),anyString(),any());
      var r=f.o().askWithCorrection(Q,SQL,"independent healthy");
      add("healthy-explicit-correction",r.success()&&f.model()==1&&f.exec()==1&&usage(r,A),f,r,"Healthy explicit correction still works with original generation context");
    }
    {
      var f=new F();final F observed=f;final int[] seen={0};
      doAnswer(i->{observed.events.add("fullSchema-"+(++seen[0]));return seen[0]==1?FULL:null;}).when(f.sp).fullSchema();
      doReturn(new GeneratedSql(SQL,SQL,A)).when(f.gen).generateCorrection(anyString(),any(),anyString(),anyString(),any());
      var r=f.o().askWithCorrection(Q,SQL,"independent becomes unavailable after model");
      add("fault-after-explicit-admission-keeps-spent-usage",unavailable(r)&&f.model()==1&&f.exec()==0&&usage(r,A),f,r,"Admission cannot predict later fault; retain completed call but never execute");
    }
    for(String mode:List.of("null","empty","throws","unimplemented"))for(String kind:List.of("valid-tool","malformed-tool-json","unknown-tool")){
      var f=new F();f.mode(mode);f.p.getToolUse().setEnabled(true);f.p.getToolUse().setTableThreshold(1);f.p.getToolUse().setMaxRounds(1);
      String name=kind.equals("unknown-tool")?"NONEXISTENT_INDEPENDENT_TOOL":"SAMPLE_QUERY";
      String arguments=kind.equals("malformed-tool-json")?"{":"{\"sql\":\"SELECT order_id FROM orders LIMIT 1\"}";
      var first=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("independent","function",name,arguments))).build())));
      var second=new ChatResponse(List.of(new Generation(new AssistantMessage(SQL))));
      final F observed=f;final int[] n={0};
      doAnswer(i->{observed.events.add("tool-model-"+(++n[0])+":"+i.getArgument(2));return new LlmSqlGenerator.AgentTurn(n[0]==1?first:second,n[0]==1?A:B,"");}).when(f.gen).callWithTools(anyList(),anyList(),anyString(),any(),any());
      var r=f.o().ask(Q);
      add("agent-optional-call-unavailable-"+mode+"-"+kind,unavailable(r)&&f.model()==1&&f.exec()==0&&f.plans()==0&&usage(r,A),f,r,"Unavailable catalog must stop optional agent round/finalization even if tool invocation fails before executor");
    }
    var result=new LinkedHashMap<String,Object>();result.put("total",rows.size());result.put("passed",rows.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count());result.put("failed",rows.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count());result.put("providerCalls",0);result.put("databaseConnections",0);result.put("rows",rows);
    Files.writeString(Path.of(args[0]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
    System.out.println("EXTENDED_B_PROBE total="+result.get("total")+" passed="+result.get("passed")+" failed="+result.get("failed")+" providerCalls=0");
  }
}
