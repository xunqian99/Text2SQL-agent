package com.text2sql.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.evaluation.TableRecall;
import com.text2sql.agent.fewshot.ExampleLoader;
import com.text2sql.agent.fewshot.ExampleSelector;
import com.text2sql.agent.generation.PromptFingerprint;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import com.text2sql.agent.semantic.MetricRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Offline candidate diagnosis only. Never wired into SchemaProvider or a model client. */
public final class ContextLeafFeasibilityProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || Files.exists(Path.of(args[0])))
            throw new IllegalArgumentException("Expected a new output file");
        var p = new AgentProperties();
        p.getEval().setSplit("dev");
        p.getRetrieval().setEnabled(true);
        p.getRetrieval().setColumnPruningEnabled(true);
        p.getRetrieval().setJoinHintsEnabled(true);
        p.getRetrieval().setJoinListEnabled(false);
        p.getRetrieval().setJoinPlanEnabled(true);
        p.getSemantic().setEnabled(true);
        p.getFewshot().setEnabled(true);
        p.getFewshot().setMaxExamples(2);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"), env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro")));
        var catalog = new SchemaCatalog(new DatabaseSchemaReader(jdbc), p);
        var full = catalog.full();
        var glossary = new GlossaryLoader(catalog);
        var registry = new MetricRegistry();
        ReflectionTestUtils.invokeMethod(registry, "load");
        var examples = new ExampleLoader();
        examples.init();
        var retriever = new LexicalSchemaRetriever(glossary, catalog, p);
        var values = new ValueRetriever(glossary);
        var provider = new HybridSchemaProvider(catalog, retriever, glossary, p, registry,
                new ExampleSelector(examples), values, new ColumnPruner(glossary));
        var graph = JoinGraph.from(full, glossary.get());
        var template = new PromptTemplate(p);
        var loader = new EvalItemLoader(p);
        var items = loader.load();
        if (items.size() != 100) throw new IllegalStateException("Expected original dev100");
        var rows = new ArrayList<Object>();
        for (var item : items) {
            var context = provider.provide(item.question());
            var retrieval = retriever.retrieve(item.question());
            var available = new LinkedHashSet<>(context.tableNames());
            var direct = new LinkedHashSet<String>();
            retrieval.evidence().keySet().stream().filter(t -> !retrieval.expandedTables().contains(t))
                    .filter(available::contains).forEach(direct::add);
            var metricTables = new LinkedHashSet<String>();
            registry.findApplicable(item.question(), available).forEach(m -> metricTables.addAll(m.tables()));
            var valueTables = new LinkedHashSet<String>();
            values.findMatches(item.question(), available).forEach(v -> valueTables.add(v.table()));
            var exampleTables = new LinkedHashSet<String>();
            context.examples().forEach(e -> exampleTables.addAll(TableRecall.expectedTables(e.sql())));
            var protectedTables = new LinkedHashSet<>(direct);
            protectedTables.addAll(metricTables);
            protectedTables.addAll(valueTables);
            protectedTables.addAll(exampleTables);
            String fallback = retrieval.fellBack() ? "NO_RETRIEVAL_EVIDENCE"
                    : protectedTables.isEmpty() ? "NO_PROTECTED_TABLES"
                    : !available.containsAll(protectedTables) ? "PROTECTED_TABLE_OUTSIDE_CONTEXT" : null;
            var kept = new LinkedHashSet<>(available);
            var rounds = new ArrayList<List<String>>();
            if (fallback == null) {
                while (true) {
                    var leaves = kept.stream().filter(t -> !protectedTables.contains(t)).filter(t ->
                            graph.tableAdjacency().getOrDefault(t, java.util.Map.of()).keySet().stream()
                                    .filter(kept::contains).count() <= 1).sorted().toList();
                    if (leaves.isEmpty()) break;
                    rounds.add(leaves);
                    kept.removeAll(leaves);
                }
            }
            if (!kept.containsAll(protectedTables) && fallback == null) throw new IllegalStateException("Lost protected source");
            var retained = context.tables().stream().filter(t -> kept.contains(t.name())).toList();
            var planTargets = new LinkedHashSet<>(direct);
            planTargets.addAll(metricTables);
            planTargets.addAll(valueTables);
            var plan = JoinPathPlanner.planForTargets(graph, planTargets, retrieval.tableNames());
            String ddl;
            if (plan.isEmpty()) {
                ddl = SchemaDdlRenderer.render(retained, full.foreignKeys().stream()
                        .filter(f -> kept.contains(f.fromTable()) && kept.contains(f.toTable())).toList());
            } else {
                if (plan.edges().stream().anyMatch(e -> !kept.contains(e.fromTable()) || !kept.contains(e.toTable()))) {
                    // A recommended bridge must never be removed just because it is not a direct hit.
                    kept.clear();
                    kept.addAll(available);
                    retained = context.tables();
                    fallback = "RECOMMENDED_BRIDGE_REMOVED";
                    rounds.clear();
                }
                ddl = SchemaDdlRenderer.renderWithJoins(retained, plan.edges(), plan, false, true);
            }
            String originalUser = template.userPrompt(context, item.question());
            String candidateUser = fallback != null ? originalUser : template.userPrompt(context.subset(kept, ddl), item.question());
            // Gold is consulted only AFTER the candidate table decision; it never controls pruning.
            var baselineRecall = TableRecall.evaluate(item.goldSql(), context.tableNames());
            var candidateRecall = TableRecall.evaluate(item.goldSql(), List.copyOf(kept));
            var row = new LinkedHashMap<String, Object>();
            row.put("id", item.id());
            row.put("difficulty", item.difficulty());
            row.put("directTables", direct);
            row.put("expandedTables", retrieval.expandedTables());
            row.put("metricTables", metricTables);
            row.put("valueTables", valueTables);
            row.put("exampleTables", exampleTables);
            row.put("protectedTables", protectedTables);
            row.put("originalTables", available);
            row.put("candidateTables", kept);
            row.put("removedLeafRounds", rounds);
            row.put("fallbackReason", fallback);
            row.put("baselineUserChars", originalUser.length());
            row.put("candidateUserChars", candidateUser.length());
            row.put("baselineUserSha256", PromptFingerprint.textSha256(originalUser));
            row.put("candidateUserSha256", PromptFingerprint.textSha256(candidateUser));
            row.put("expectedTablesFromGold", baselineRecall.expected());
            row.put("baselineMissingTables", baselineRecall.missing());
            row.put("candidateMissingTables", candidateRecall.missing());
            rows.add(row);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("format", "offline-context-leaf-feasibility-v1");
        result.put("split", "dev");
        result.put("datasetSha256", loader.datasetSha256(p.getEval().getDir()));
        result.put("sampleIdsSha256", EvalItemLoader.sampleIdsSha256(items));
        result.put("metricDefinitionsSha256", MetricRegistry.definitionSha256());
        result.put("promptVersion", template.version());
        result.put("items", rows);
        result.put("modelCalls", 0);
        result.put("note", "Offline topology candidate, not a production implementation or measured latency/accuracy improvement.");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), result);
        System.out.println("CONTEXT_LEAF items=100 modelCalls=0");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
