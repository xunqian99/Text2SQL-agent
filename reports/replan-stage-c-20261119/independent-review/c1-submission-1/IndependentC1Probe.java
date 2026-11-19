package independent.c1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.fewshot.Example;
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
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Independent contract-preservation probes, all model calls are in-memory Mockito. */
public final class IndependentC1Probe {
  static final String SQL="SELECT o.order_id FROM orders o LIMIT 1";
  static final String PRINCIPLE="【使用原则】当用户问题涉及以下指标且未指定特殊计算口径时，遵循标准度量的表达式与过滤条件；若用户明确指明了特定的计算口径或聚合逻辑，以用户明确说明为准。\n";
  static final List<Map<String,Object>> rows=new ArrayList<>();
  static final List<Map<String,Object>> captures=new ArrayList<>();
  static int modelCalls=0;
  static SchemaContext.Table table(String n,String... cols){return new SchemaContext.Table(n,"independent",Arrays.stream(cols).map(c->new SchemaContext.Column(c,"text",true,"independent",c.equals("order_id"))).toList());}
  static final SchemaContext AUTH=new SchemaContext(List.of(table("orders","order_id","customer_id"),table("customers","customer_id","customer_unique_id")),List.of(),"AUTH_PROFILE_MUST_NOT_LEAK","AUTH_DDL_MUST_NOT_LEAK");
  static SchemaContext schema(String profile,String metrics,List<Example> ex,String values){return new SchemaContext(List.of(table("orders","order_id")),List.of(),profile,"DDL_𠮷_标识_复合主键_FOREIGN_KEYS",metrics,ex,values);}
  static void check(String id,boolean b){rows.add(Map.of("id",id,"passed",b));}
  static void check(String id,boolean b,String detail){rows.add(Map.of("id",id,"passed",b,"detail",detail));}
  static ChatResponse answer(String text,boolean known){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),known?ChatResponseMetadata.builder().usage(new DefaultUsage(17,3,20)).build():ChatResponseMetadata.builder().build());}
  static class F implements AutoCloseable {
    final AgentProperties p=new AgentProperties();
    final SchemaCatalog cat=mock(SchemaCatalog.class);
    final LlmCallLog log=mock(LlmCallLog.class);
    final ChatModel model=mock(ChatModel.class);
    final List<Prompt> prompts=new ArrayList<>();
    final LlmSqlGenerator gen;
    final PromptTemplate template;
    boolean available=true;
    java.util.function.IntFunction<ChatResponse> responses=n->answer(SQL,true);
    F(boolean reasoning)throws Exception{
      p.getLlm().setApiKey("");p.getLlm().setProvider("independent-memory");p.getLlm().setModel("independent-memory");p.getLlm().setBaseUrl("http://127.0.0.1:1");
      p.getPrompt().setExplicitReasoningEnabled(reasoning);p.getObservability().setEnabled(false);p.getPersistence().setEnabled(false);
      when(cat.full()).thenAnswer(i->available?AUTH:null);
      template=new PromptTemplate(p);gen=new LlmSqlGenerator(p,template,log,new AuthorizedSchemaCatalog(cat));
      Field f=LlmSqlGenerator.class.getDeclaredField("chatModel");f.setAccessible(true);f.set(gen,model);
      when(model.call(any(Prompt.class))).thenAnswer(i->{Prompt prompt=i.getArgument(0);prompts.add(prompt);modelCalls++;return responses.apply(prompts.size());});
    }
    GeneratedSql entry(String entry,SchemaContext s,String q){
      return switch(entry){
        case "generate"->gen.generate(q,s);
        case "correction"->gen.generateCorrection(q,s,SQL,"反馈_𠮷_仅修正单位",null);
        case "messages"->gen.callMessages(List.of(new SystemMessage("独立会话系统"),new UserMessage(q)),"CONVERSATION_REWRITE",null);
        case "tools"->{var t=gen.callWithTools(List.of(new SystemMessage(template.systemPrompt()),new UserMessage(template.userPrompt(s,q))),List.of(),"AGENT_FINALIZE",null,null);yield new GeneratedSql(SQL,t.raw(),t.call());}
        default->throw new IllegalArgumentException(entry);
      };
    }
    public void close(){}
  }
  static List<Map<String,String>> messages(Prompt p){return p.getInstructions().stream().map(m->Map.of("role",m.getMessageType().toString(),"text",m.getText())).toList();}
  public static void main(String[] args)throws Exception{
    Path out=Path.of(args[0]);Files.createDirectories(out);
    var examples=List.of(new Example("independent","boundary","示例问题_𠮷","SELECT order_id FROM orders LIMIT 1"));
    String[] options={null," ","标记_𠮷_NULL_保留"};
    String[] questions={" 按自然人和记录分别计数，保留NULL；不要改成同一分母。𠮷 ","请保留原文引用："+PRINCIPLE};
    int variants=0;
    for(boolean reasoning:List.of(true,false)){
      var p=new AgentProperties();p.getPrompt().setExplicitReasoningEnabled(reasoning);
      var old=new BaselinePromptTemplate(p);var now=new PromptTemplate(p);
      String os=old.systemPrompt(),ns=now.systemPrompt();
      String start="思考与推理步骤",end="硬性规则";
      check("reasoning-"+reasoning+"-switch",ns.contains(start)==reasoning);
      if(reasoning)check("reasoning-four-steps-exact",os.substring(os.indexOf(start),os.indexOf(end)).equals(ns.substring(ns.indexOf(start),ns.indexOf(end))));
      check("reasoning-"+reasoning+"-version-new",!old.version().equals(now.version()));
      for(String profile:options)for(String metric:options)for(String value:options)for(int ex=0;ex<3;ex++)for(String q:questions){
        SchemaContext s=schema(profile,metric,ex==0?null:ex==1?List.of():examples,value);String id="variant-"+(++variants);
        String before=old.userPrompt(s,q);String expected=s.hasMetrics()?before.replaceFirst(java.util.regex.Pattern.quote(PRINCIPLE),""):before;
        String after=now.userPrompt(s,q);
        check(id+"-exact-user",after.equals(expected));
        for(String prev:new String[]{null,SQL+" -- previous_𠮷"})for(String feedback:new String[]{null,"明确要求0到1；保留未匹配事实_𠮷"}){
          String oc=old.correctionPrompt(s,q,prev,feedback),nc=now.correctionPrompt(s,q,prev,feedback);
          check(id+"-correction-"+(prev==null)+"-"+(feedback==null),nc.equals(after+oc.substring(before.length())));
        }
        check(id+"-not-mutated",Objects.equals(s.metricsText(),metric)&&Objects.equals(s.valueHints(),value)&&Objects.equals(s.dataProfile(),profile));
      }
      try(var f=new F(reasoning)){
        SchemaContext s=schema("PROFILE_𠮷","METRIC_custom outputUnit PERCENT 分母全部",examples,"VALUES_NULL");String q="按订单时钟，不按承运商时钟，单位0到1_𠮷";
        for(String entry:List.of("generate","correction","messages","tools")){
          int before=f.prompts.size();GeneratedSql r=f.entry(entry,s,q);Prompt actual=f.prompts.get(before);
          check("entry-"+reasoning+"-"+entry+"-one-call",f.prompts.size()==before+1);
          check("entry-"+reasoning+"-"+entry+"-known-usage",r.call()!=null&&r.call().totalTokens()==20);
          String expectedSystem=entry.equals("messages")?"独立会话系统":f.template.systemPrompt();
          String expectedUser=entry.equals("messages")?q:entry.equals("correction")?f.template.correctionPrompt(s,q,SQL,"反馈_𠮷_仅修正单位"):f.template.userPrompt(s,q);
          check("entry-"+reasoning+"-"+entry+"-actual-system",actual.getInstructions().get(0).getText().equals(expectedSystem));
          check("entry-"+reasoning+"-"+entry+"-actual-user",actual.getInstructions().get(1).getText().equals(expectedUser));
          check("entry-"+reasoning+"-"+entry+"-auth-not-injected",!actual.toString().contains("AUTH_DDL_MUST_NOT_LEAK")&&!actual.toString().contains("AUTH_PROFILE_MUST_NOT_LEAK"));
          captures.add(Map.of("entry",entry,"reasoning",reasoning,"messages",messages(actual)));
        }
      }
      try(var f=new F(reasoning)){
        f.responses=n->n==1?new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("unknown1","function","INDEPENDENT_UNKNOWN","{}"))).build())),answer("",true).getMetadata()):answer(SQL,true);
        var loop=new AgentLoop(f.gen,f.template,mock(ToolExecutor.class),f.p);var s=schema("PROFILE","METRIC_preserved",examples,"VALUES");
        GeneratedSql result=loop.run("明确请求含并列，保留未知",s,1,null);
        check("loop-"+reasoning+"-two-calls",f.prompts.size()==2);
        check("loop-"+reasoning+"-sum-once",result.call()!=null&&result.call().totalTokens()==40);
        for(int n=0;n<f.prompts.size();n++){
          var msgs=f.prompts.get(n).getInstructions();
          check("loop-"+reasoning+"-"+n+"-shared-system",msgs.get(0).getText().startsWith(f.template.systemPrompt()));
          check("loop-"+reasoning+"-"+n+"-shared-user",msgs.get(1).getText().equals(f.template.userPrompt(s,"明确请求含并列，保留未知")));
          captures.add(Map.of("entry",n==0?"AGENT_ROUND_1":"AGENT_FINALIZE","reasoning",reasoning,"messages",messages(f.prompts.get(n))));
        }
      }
      try(var f=new F(reasoning)){
        f.p.getConversation().setRewriteWithLlm(true);f.responses=n->answer("2018年订单数是多少？",true);
        var session=new ConversationSession("independent");session.addTurn(ConversationTurn.of(1,"2017年订单数是多少？",null,SQL,"SUCCESS","1行"),10);
        var r=new QuestionRewriter(f.gen,f.p).rewriteWithOutcome("那2018年的呢？",session);
        check("session-"+reasoning+"-one-rewrite",f.prompts.size()==1&&r.call()!=null&&r.call().totalTokens()==20);
        check("session-"+reasoning+"-rewrite-preserved",r.question().equals("2018年订单数是多少？"));
        check("session-"+reasoning+"-separate-system",!f.prompts.get(0).getInstructions().get(0).getText().contains("硬性规则"));
        f.responses=n->answer(SQL,true);f.gen.generate(r.question(),schema("","METRIC",List.of(),""));
        check("session-"+reasoning+"-generation-shared-system",f.prompts.get(1).getInstructions().get(0).getText().equals(f.template.systemPrompt()));
      }
    }
    for(String entry:List.of("generate","correction","messages","tools"))for(String shape:List.of("healthy","empty-known","empty-unknown","null")){
      try(var f=new F(true)){
        f.responses=n->switch(shape){case "healthy"->answer(SQL,true);case "empty-known"->new ChatResponse(List.of(),answer(SQL,true).getMetadata());case "empty-unknown"->new ChatResponse(List.of());default->null;};
        String id="accounting-"+entry+"-"+shape;GeneratedSql r=null;GenerationException err=null;
        try{r=f.entry(entry,schema("","",List.of(),""),"边界");}catch(GenerationException e){err=e;}
        var record=r!=null?r.call():err==null?null:err.getCallRecord();
        check(id+"-one-send",f.prompts.size()==1);
        check(id+"-known-preserved",shape.equals("healthy")||shape.equals("empty-known")?record!=null&&record.totalTokens()==20:record==null);
        check(id+"-bad-content-not-success",shape.equals("healthy")?r!=null:err!=null);
        f.available=false;int before=f.prompts.size();GenerationException unavailable=null;
        try{f.entry(entry,schema("","",List.of(),""),"目录失败");}catch(GenerationException e){unavailable=e;}
        check(id+"-catalog-no-second-send",f.prompts.size()==before&&unavailable!=null&&unavailable.getReason()==GenerationException.Reason.SCHEMA_UNAVAILABLE);
      }
    }
    var validator=new SqlValidator(new AgentProperties());
    String[] dangerous={"DELETE FROM orders","DROP TABLE orders","SELECT o.order_id FROM orders o;DELETE FROM orders","WITH gone AS (DELETE FROM orders RETURNING *) SELECT * FROM gone","SELECT pg_sleep(1)","SELECT o.order_id FROM archive.orders o LIMIT 1","SELECT * FROM semantic_cache LIMIT 1","SELECT o.fabricated FROM orders o LIMIT 1","SELECT * FROM invented LIMIT 1"};
    for(int i=0;i<dangerous.length;i++)check("safety-negative-"+i,!validator.validate(dangerous[i],AUTH).valid());
    check("safety-positive-authorized",validator.validate(SQL,AUTH).valid());
    check("safety-limit-rewritten",validator.validate("SELECT o.order_id FROM orders o",AUTH).rewritten());
    var result=new LinkedHashMap<String,Object>();result.put("sourceCommit","e114648");result.put("total",rows.size());result.put("passed",rows.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count());result.put("failed",rows.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count());result.put("contextVariants",variants);result.put("inMemoryModelCalls",modelCalls);result.put("providerCalls",0);result.put("databaseConnections",0);result.put("rows",rows);
    var mapper=new ObjectMapper().writerWithDefaultPrettyPrinter();Files.writeString(out.resolve("runtime-results-first.json"),mapper.writeValueAsString(result),StandardOpenOption.CREATE_NEW);Files.writeString(out.resolve("runtime-messages-first.json"),mapper.writeValueAsString(captures),StandardOpenOption.CREATE_NEW);
    System.out.println("INDEPENDENT_C1 total="+result.get("total")+" passed="+result.get("passed")+" failed="+result.get("failed")+" variants="+variants+" modelCalls="+modelCalls+" providerCalls=0");
    if(((Number)result.get("failed")).longValue()!=0)System.exit(1);
  }
}
