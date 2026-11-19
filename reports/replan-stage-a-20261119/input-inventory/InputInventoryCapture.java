import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItem;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.fewshot.ExampleLoader;
import com.text2sql.agent.fewshot.ExampleSelector;
import com.text2sql.agent.generation.PromptFingerprint;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import com.text2sql.agent.semantic.MetricRegistry;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Read-only public-input inventory. No Spring context, generator, or model client is constructed. */
public final class InputInventoryCapture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Integer> ALLOWED = Map.of(
            "data/eval", 200,
            "data/benchmarks/olist-v3-dev-runtime-candidate", 100,
            "data/benchmarks/olist-v3-candidate", 100);
    private static final String OUTPUT = "reports/replan-stage-a-20261119/input-inventory";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected allowed dataset directory and output JSON filename");
        String dataset = args[0].replace('\\', '/');
        if (!ALLOWED.containsKey(dataset)) throw new IllegalArgumentException("Dataset outside public whitelist");
        Path outputRoot = Path.of(OUTPUT).toAbsolutePath().normalize();
        Path output = outputRoot.resolve(args[1]).normalize();
        if (!output.getParent().equals(outputRoot) || !output.getFileName().toString().endsWith(".json"))
            throw new IllegalArgumentException("Output must be a JSON filename in input-inventory");
        if (Files.exists(output)) throw new IllegalArgumentException("Refuse to overwrite capture: " + output);

        var props = new AgentProperties();
        props.getEval().setDir(dataset);
        props.getEval().setSplit("all");
        props.getRetrieval().setEnabled(true);
        props.getRetrieval().setColumnPruningEnabled(true);
        props.getRetrieval().setJoinHintsEnabled(true);
        props.getRetrieval().setJoinListEnabled(false);
        props.getRetrieval().setJoinPlanEnabled(true);
        props.getSemantic().setEnabled(true);
        props.getFewshot().setEnabled(true);
        props.getFewshot().setMaxExamples(2);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"),
                env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro")));
        var catalog = new SchemaCatalog(new DatabaseSchemaReader(jdbc), props);
        var full = catalog.full();
        if (full.tables().isEmpty()) throw new IllegalStateException("Empty schema");
        var glossary = new GlossaryLoader(catalog);
        var metrics = new MetricRegistry();
        var load = MetricRegistry.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(metrics);
        var examples = new ExampleLoader();
        examples.init();
        var retriever = new LexicalSchemaRetriever(glossary, catalog, props);
        var provider = new HybridSchemaProvider(catalog, retriever, glossary, props, metrics,
                new ExampleSelector(examples), new ValueRetriever(glossary), new ColumnPruner(glossary));
        var loader = new EvalItemLoader(props);
        var items = loader.load();
        if (items.size() != ALLOWED.get(dataset)) throw new IllegalStateException("Unexpected public item count");
        var template = new PromptTemplate(props);
        var rows = new ArrayList<Map<String, Object>>();
        for (var item : items) rows.add(capture(item, provider, retriever, metrics, template));

        var report = new LinkedHashMap<String, Object>();
        report.put("format", "replan-stage-a-input-inventory-v1");
        report.put("datasetDirectory", dataset);
        report.put("datasetSha256", loader.datasetSha256(dataset));
        report.put("sampleIdsSha256", EvalItemLoader.sampleIdsSha256(items));
        report.put("lengthUnit", "java-utf16-code-units");
        report.put("promptVersion", template.version());
        report.put("promptHashVersion", PromptFingerprint.VERSION);
        report.put("retrievalConfig", props.getRetrieval());
        report.put("semanticEnabled", props.getSemantic().isEnabled());
        report.put("fewshotConfig", props.getFewshot());
        report.put("modelCalls", 0);
        report.put("modelCostYuan", 0);
        report.put("captureScope", "offline reconstruction; actual sent equality requires historical fingerprint match");
        report.put("schemaSha256", sha(JSON.writeValueAsString(full)));
        report.put("fullSchema", full);
        report.put("systemPrompt", text(template.systemPrompt(), "src/main/java/com/text2sql/agent/generation/PromptTemplate.java#systemPrompt"));
        report.put("metricDefinitions", metrics.all().stream().map(m -> definition("metric", m.name(), m.render(), m)).toList());
        report.put("dimensionPolicies", metrics.dimensionPolicies().stream().map(m -> definition("dimensionPolicy", m.name(), m.render(), m)).toList());
        report.put("entityPolicies", metrics.entityPolicies().stream().map(m -> definition("entityPolicy", m.name(), m.render(), m)).toList());
        report.put("allExamples", examples.getExamples());
        var resources = new LinkedHashMap<String, Object>();
        for (String name : List.of("schema/metrics.yml", "schema/glossary.yml", "fewshot/examples.yml")) {
            try (var in = InputInventoryCapture.class.getClassLoader().getResourceAsStream(name)) {
                if (in == null) throw new IllegalStateException("Missing input resource: " + name);
                resources.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }
        }
        report.put("resourceSha256", resources);
        report.put("itemCount", rows.size());
        report.put("items", rows);
        Files.createDirectories(outputRoot);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("INPUT_INVENTORY items=" + rows.size() + " modelCalls=0 output=" + output);
    }

    private static Map<String, Object> capture(EvalItem item, HybridSchemaProvider provider,
            LexicalSchemaRetriever retriever, MetricRegistry registry, PromptTemplate template) throws Exception {
        var context = provider.provide(item.question());
        var retrieval = retriever.retrieve(item.question());
        var row = new LinkedHashMap<String, Object>();
        String system = template.systemPrompt(), user = template.userPrompt(context, item.question());
        row.put("id", item.id());
        row.put("question", item.question());
        row.put("questionSha256", sha(item.question()));
        row.put("difficulty", item.difficulty());
        row.put("promptChars", system.length() + user.length());
        row.put("promptSha256", PromptFingerprint.messagesSha256(List.of(new SystemMessage(system), new UserMessage(user))));
        row.put("userPrompt", text(user, "src/main/java/com/text2sql/agent/generation/PromptTemplate.java#userPrompt"));
        var parts = new LinkedHashMap<String, Integer>();
        parts.put("system", system.length());
        parts.put("ddlAndJoinHints", context.ddlText().length());
        parts.put("dataProfile", empty(context.dataProfile()) ? 0 : context.dataProfile().length());
        parts.put("metrics", context.hasMetrics() ? context.metricsText().length() : 0);
        parts.put("valueHints", context.hasValueHints() ? context.valueHints().length() : 0);
        parts.put("exampleContents", context.hasExamples() ? context.examples().stream()
                .mapToInt(e -> e.question().strip().length() + e.sql().strip().length()).sum() : 0);
        parts.put("question", item.question().strip().length());
        int framing = row.get("promptChars") instanceof Integer n ? n - parts.values().stream().mapToInt(Integer::intValue).sum() : -1;
        if (framing < 0) throw new IllegalStateException("Overlapping components");
        parts.put("framingAndInstructions", framing);
        row.put("componentChars", parts);
        row.put("ddlAndJoinHints", text(context.ddlText(), "live database metadata -> SchemaDdlRenderer + HybridSchemaProvider#renderDdl"));
        row.put("dataProfile", text(context.dataProfile(), "src/main/java/com/text2sql/agent/retrieval/DatabaseSchemaReader.java"));
        row.put("metrics", text(context.metricsText(), "src/main/resources/schema/metrics.yml -> MetricRegistry + Metric/DimensionPolicy/EntityPolicy.render"));
        row.put("valueHints", text(context.valueHints(), "src/main/resources/schema/glossary.yml -> ValueRetriever"));
        row.put("selectedExamples", context.examples());
        row.put("schemaTablesAfterPruning", context.tables());
        row.put("retrieval", retrieval);
        row.put("providerAddedTables", context.tableNames().stream().filter(t -> !retrieval.tableNames().contains(t)).toList());
        row.put("directHitsInPublishedEvidence", retrieval.evidence().keySet().stream()
                .filter(t -> !retrieval.expandedTables().contains(t)).toList());
        row.put("directHitInferenceLimit", "Derived only from published evidence minus expandedTables; fallback has no lexical direct hits. Original method-local directHits is not exported by production.");
        row.put("metricMatchEvidence", registry.matchEvidence(item.question()));
        var injected = new ArrayList<Map<String, Object>>();
        for (var m : registry.all()) if (context.metricsText().contains(m.render().stripTrailing())) injected.add(definition("metric", m.name(), m.render(), m));
        for (var m : registry.dimensionPolicies()) if (context.metricsText().contains(m.render().stripTrailing())) injected.add(definition("dimensionPolicy", m.name(), m.render(), m));
        for (var m : registry.entityPolicies()) if (context.metricsText().contains(m.render().stripTrailing())) injected.add(definition("entityPolicy", m.name(), m.render(), m));
        row.put("injectedDefinitionIdentities", injected);
        return row;
    }

    private static Map<String, Object> definition(String kind, String name, String rendered, Object fields) {
        var row = new LinkedHashMap<String, Object>();
        row.put("kind", kind); row.put("name", name); row.put("fields", fields);
        row.put("rendered", text(rendered, "src/main/resources/schema/metrics.yml#" + name));
        return row;
    }

    private static Map<String, Object> text(String value, String source) {
        var row = new LinkedHashMap<String, Object>();
        row.put("text", value); row.put("chars", value == null ? null : value.length());
        row.put("sha256", value == null ? null : sha(value)); row.put("source", source);
        return row;
    }

    private static boolean empty(String value) { return value == null || value.isBlank(); }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String env(String key, String fallback) { String value = System.getenv(key); return value == null || value.isBlank() ? fallback : value; }
}
