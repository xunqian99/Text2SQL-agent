package com.text2sql.agent.orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
/** Public test read-only account only. No private config, no model client. */
public final class LocalColumnSqlStateProbe {
  public static void main(String[] args)throws Exception {
    var rows=new ArrayList<Map<String,Object>>();
    try(var connection=DriverManager.getConnection("jdbc:postgresql://localhost:5432/olist","text2sql_ro","text2sql_ro")) {
      connection.setReadOnly(true);connection.setAutoCommit(false);
      for(String inner:List.of("SELECT invented FROM orders LIMIT 1","SELECT o.invented FROM orders o LIMIT 1")) {
        String sql="SELECT * FROM ("+inner+") AS sample_subq LIMIT 5";var row=new LinkedHashMap<String,Object>();row.put("sql",sql);row.put("readOnly",connection.isReadOnly());
        try(var statement=connection.createStatement()) {
          statement.setQueryTimeout(5);
          try(var result=statement.executeQuery(sql)){int n=0;while(result.next())n++;row.put("success",true);row.put("returnedRows",n);row.put("passed",false);}
        }catch(SQLException error){row.put("success",false);row.put("sqlState",error.getSQLState());row.put("errorClass",error.getClass().getName());row.put("returnedRows",0);row.put("passed","42703".equals(error.getSQLState()));}
        finally{connection.rollback();}
        rows.add(row);
      }
    }
    var output=Map.of("databaseConnections",1,"readonlySelectAttempts",2,"providerCalls",0,"rows",rows,"passed",rows.stream().allMatch(r->Boolean.TRUE.equals(r.get("passed"))));
    Files.writeString(Path.of(args[0]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output),StandardOpenOption.CREATE_NEW);
    System.out.println("LOCAL_READONLY_SQLSTATE "+output);
  }
}
