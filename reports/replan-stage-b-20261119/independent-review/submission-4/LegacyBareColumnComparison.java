package com.text2sql.agent.orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.validation.SqlValidator;
import java.nio.file.*;
import java.util.*;
/** Same SAMPLE_QUERY wrapping and same catalog; run against separately loaded historical/current classes. */
public final class LegacyBareColumnComparison {
  public static void main(String[] args)throws Exception {
    var schema=new SchemaContext(List.of(new SchemaContext.Table("orders",null,List.of(new SchemaContext.Column("order_id","text",false,null),new SchemaContext.Column("customer_id","text",false,null)))),List.of(),"","");
    var validator=new SqlValidator(new AgentProperties());var rows=new ArrayList<Map<String,Object>>();
    for(String inner:List.of("SELECT invented FROM orders LIMIT 1","SELECT o.invented FROM orders o LIMIT 1")) {
      String sql="SELECT * FROM ("+inner+") AS sample_subq LIMIT 5";var r=validator.validate(sql,schema);
      rows.add(Map.of("inner",inner,"wrapped",sql,"valid",r.valid(),"validation",r));
    }
    var output=Map.of("label",args[0],"validatorClassLocation",SqlValidator.class.getProtectionDomain().getCodeSource().getLocation().toString(),"rows",rows,"databaseConnections",0,"providerCalls",0);
    Files.writeString(Path.of(args[1]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output),StandardOpenOption.CREATE_NEW);
    System.out.println(args[0]+" "+rows);
  }
}
