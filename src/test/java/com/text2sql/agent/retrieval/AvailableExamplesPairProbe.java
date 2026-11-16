package com.text2sql.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItem;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.fewshot.Example;
import com.text2sql.agent.fewshot.ExampleLoader;
import com.text2sql.agent.fewshot.ExampleSelector;
import com.text2sql.agent.generation.PromptFingerprint;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import com.text2sql.agent.semantic.MetricRegistry;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pre-call witnesses: actual providers and messages, only example policy differs. No model client. */
public final class AvailableExamplesPairProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}") || Files.exists(Path.of(args[0])))
            throw new IllegalArgumentException("Expected new output path and frozen commit");
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
        if (catalog.full().tables().isEmpty()) throw new IllegalStateException("Empty schema");
        var glossary = new GlossaryLoader(catalog);
        var metrics = new MetricRegistry();
        ReflectionTestUtils.invokeMethod(metrics, "load");
        var examples = new ExampleLoader();
        examples.init();
        var selector = new ExampleSelector(examples);
        var provider = new HybridSchemaProvider(catalog, new LexicalSchemaRetriever(glossary, catalog, p), glossary,
                p, metrics, selector, new ValueRetriever(glossary), new ColumnPruner(glossary));
        var loader = new EvalItemLoader(p);
        var items = loader.load();
        if (items.size() != 100) throw new IllegalStateException("Expected original dev100");
        var template = new PromptTemplate(p);
        var captures = new ArrayList<Map<String, Object>>();
        for (var item : items) captures.add(capture(item, p, provider, template));
        for (int round = 0; round < 3; round++)
            for (int i = 0; i < items.size(); i++)
                require(captures.get(i).equals(capture(items.get(i), p, provider, template)), "Same-process selection drift");

        // The full-schema path must consume the same flag, not only the hybrid path used by the model screen.
        var full = new FullSchemaProvider(catalog, metrics, selector, p);
        int fullChecks = 0;
        for (boolean strict : List.of(false, true)) {
            p.getFewshot().setRequireAvailableTables(strict);
            String question = "订单总数是多少？";
            var context = full.provide(question);
            require(context.examples().equals(selector.select(question, Set.copyOf(context.tableNames()), 2, strict)),
                    "Full provider did not pass selection policy");
            fullChecks++;
        }
        p.getFewshot().setRequireAvailableTables(false);
        var prior = JSON.readTree(Path.of("reports/latency-routing-feasibility-20261116/context-leaves.json").toFile());
        var priorHashes = new java.util.HashMap<String, String>();
        prior.path("items").forEach(i -> priorHashes.put(i.path("id").asText(), i.path("baselineUserSha256").asText()));
        for (var row : captures) require(row.get("defaultUserSha256").equals(priorHashes.get(row.get("id"))), "Legacy message changed");

        var report = new LinkedHashMap<String, Object>();
        report.put("format", "available-examples-pairs-v1");
        report.put("codeCommit", args[1]);
        report.put("split", "dev");
        report.put("datasetSha256", loader.datasetSha256(p.getEval().getDir()));
        report.put("sampleIdsSha256", EvalItemLoader.sampleIdsSha256(items));
        report.put("promptHashVersion", PromptFingerprint.VERSION);
        report.put("promptVersion", template.version());
        report.put("systemSha256", PromptFingerprint.textSha256(template.systemPrompt()));
        report.put("systemChars", template.systemPrompt().length());
        report.put("metricDefinitionsSha256", MetricRegistry.definitionSha256());
        report.put("defaultPolicy", ExampleSelector.policyVersion(false));
        report.put("candidatePolicy", ExampleSelector.policyVersion(true));
        report.put("examplesLibrary", witnesses(examples.getExamples(), catalog.full().tableNames()));
        report.put("modelCalls", 0);
        report.put("sameProcessComparisons", 300);
        report.put("sameProcessMismatches", List.of());
        report.put("fullProviderChecks", fullChecks);
        report.put("legacyMessagesMatchPreviousWitness", 100);
        report.put("items", captures);
        Files.createDirectories(Path.of(args[0]).toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), report);
        System.out.println("AVAILABLE_EXAMPLES pairs=100 comparisons=300 fullChecks=2 legacyMatches=100 modelCalls=0");
    }

    private static Map<String, Object> capture(EvalItem item, AgentProperties p, HybridSchemaProvider provider,
                                               PromptTemplate template) throws Exception {
        p.getFewshot().setRequireAvailableTables(false);
        var original = provider.provide(item.question());
        p.getFewshot().setRequireAvailableTables(true);
        var candidate = provider.provide(item.question());
        p.getFewshot().setRequireAvailableTables(false);
        String originalCommon = JSON.writeValueAsString(original.withExamples(List.of()));
        require(originalCommon.equals(JSON.writeValueAsString(candidate.withExamples(List.of()))), "Non-example context changed");
        require(candidate.examples().size() <= 2, "Too many examples");
        for (var ex : candidate.examples()) {
            require(ex.tables() != null && !ex.tables().isEmpty()
                    && Set.copyOf(candidate.tableNames()).containsAll(ex.tables()), "Incompatible selected example");
            require(!ex.question().strip().equalsIgnoreCase(item.question().strip()), "Self example selected");
        }
        String originalUser = template.userPrompt(original, item.question());
        String candidateUser = template.userPrompt(candidate, item.question());
        var row = new LinkedHashMap<String, Object>();
        row.put("id", item.id());
        row.put("questionSha256", PromptFingerprint.textSha256(item.question()));
        row.put("commonContextSha256", PromptFingerprint.textSha256(originalCommon));
        row.put("tables", original.tableNames());
        row.put("defaultExamples", witnesses(original.examples(), original.tableNames()));
        row.put("candidateExamples", witnesses(candidate.examples(), candidate.tableNames()));
        row.put("defaultUserSha256", PromptFingerprint.textSha256(originalUser));
        row.put("candidateUserSha256", PromptFingerprint.textSha256(candidateUser));
        row.put("defaultPromptSha256", PromptFingerprint.messagesSha256(List.of(new SystemMessage(template.systemPrompt()), new UserMessage(originalUser))));
        row.put("candidatePromptSha256", PromptFingerprint.messagesSha256(List.of(new SystemMessage(template.systemPrompt()), new UserMessage(candidateUser))));
        row.put("defaultPromptChars", template.systemPrompt().length() + originalUser.length());
        row.put("candidatePromptChars", template.systemPrompt().length() + candidateUser.length());
        return row;
    }

    private static List<Map<String, Object>> witnesses(List<Example> examples, List<String> tables) {
        return examples.stream().map(e -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", e.id());
            row.put("questionSha256", PromptFingerprint.textSha256(e.question()));
            row.put("sqlSha256", PromptFingerprint.textSha256(e.sql()));
            row.put("tables", e.tables().stream().sorted().toList());
            row.put("missingTables", e.tables().stream().filter(t -> !tables.contains(t)).sorted().toList());
            return (Map<String, Object>) row;
        }).toList();
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
