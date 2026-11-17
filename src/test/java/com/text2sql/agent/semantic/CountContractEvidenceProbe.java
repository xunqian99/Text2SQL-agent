package com.text2sql.agent.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.fewshot.*;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.util.*;

/** Real provider + actual pruned columns; no generator or model client. */
public final class CountContractEvidenceProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("NEW_JSON");
        var props=new AgentProperties();
        props.getRetrieval().setEnabled(true);props.getRetrieval().setColumnPruningEnabled(true);
        props.getRetrieval().setJoinHintsEnabled(true);props.getRetrieval().setJoinListEnabled(false);props.getRetrieval().setJoinPlanEnabled(true);
        props.getSemantic().setEnabled(true);props.getFewshot().setEnabled(true);props.getFewshot().setMaxExamples(2);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(env("TEXT2SQL_TEST_DB_URL","jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER","text2sql_ro"),env("TEXT2SQL_TEST_DB_PASSWORD","text2sql_ro")));
        var catalog=new SchemaCatalog(new DatabaseSchemaReader(jdbc),props);
        var glossary=new GlossaryLoader(catalog);var values=new ValueRetriever(glossary);
        var registry=new MetricRegistry();registry.load();
        var examples=new ExampleLoader();examples.init();
        var provider=new HybridSchemaProvider(catalog,new LexicalSchemaRetriever(glossary,catalog,props),glossary,props,registry,
                new ExampleSelector(examples),values,new ColumnPruner(glossary));
        var loader=new EvalItemLoader(props);var items=loader.load("data/eval",List.of(),0);
        var evidence=new ArrayList<Object>();
        for(var item:items) {
            var context=provider.provide(item.question());
            var contract=values.countComparisonContract(item.question(),context.tables());
            var legacy=values.renderValueHints(values.findMatches(item.question(),Set.copyOf(context.tableNames())));
            String expected=(legacy==null?"":legacy)+contract.render();
            if(!Objects.equals(context.valueHints(),expected))throw new IllegalStateException("Value provenance mismatch: "+item.id());
            evidence.add(Map.of("id",item.id(),"contract",contract,"legacyValueHints",legacy==null?"":legacy,
                    "actualValueHints",context.valueHints(),"contractText",contract.render()));
        }
        Files.writeString(Path.of(args[0]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "datasetSha256",loader.datasetSha256("data/eval"),"items",evidence,"providerCalls",0)) + "\n",StandardOpenOption.CREATE_NEW);
        System.out.println("COUNT_CONTRACT_EVIDENCE items="+evidence.size()+" modelCalls=0");
    }
    private static String env(String name,String fallback) {return System.getenv().getOrDefault(name,fallback);}
}
