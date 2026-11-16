package com.text2sql.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItem;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.fewshot.ExampleLoader;
import com.text2sql.agent.fewshot.ExampleSelector;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.generation.PromptFingerprint;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import com.text2sql.agent.semantic.MetricRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Offline acceptance probe: actual retrieval/prompt components, no generator or model client. */
public final class PromptDeterminismProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected output JSON path and optional dev/eval split");
        String split = args.length == 2 ? args[1] : "eval";
        if (!List.of("dev", "eval").contains(split)) throw new IllegalArgumentException("Expected dev or eval split");
        var props = new AgentProperties();
        props.getEval().setSplit(split);
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
        SchemaContext full = catalog.full(); // Fail instead of accepting an empty/degraded schema.
        if (full.tables().isEmpty()) throw new IllegalStateException("Empty schema");
        var glossary = new GlossaryLoader(catalog);
        var metrics = new MetricRegistry();
        ReflectionTestUtils.invokeMethod(metrics, "load");
        var examples = new ExampleLoader();
        examples.init();
        var retriever = new LexicalSchemaRetriever(glossary, catalog, props);
        var provider = new HybridSchemaProvider(catalog, retriever, glossary, props, metrics,
                new ExampleSelector(examples), new ValueRetriever(glossary), new ColumnPruner(glossary));
        var loader = new EvalItemLoader(props);
        List<EvalItem> items = loader.load();
        if (items.size() != 100) throw new IllegalStateException("Expected frozen 100-item split");
        var template = new PromptTemplate();
        List<Map<String, Object>> baseline = new ArrayList<>();
        for (EvalItem item : items) baseline.add(capture(item, provider, retriever, template));
        List<String> mismatches = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        int rounds = 3;
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < items.size(); i++) {
                long start = System.nanoTime();
                var next = capture(items.get(i), provider, retriever, template);
                times.add((System.nanoTime() - start) / 1_000_000.0);
                if (!baseline.get(i).equals(next)) mismatches.add(round + ":" + items.get(i).id());
            }
        }
        times.sort(Double::compareTo);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", "prompt-audit-v1");
        report.put("componentMeasurementsVersion", "prompt-components-v1");
        report.put("componentLengthUnit", "java-utf16-code-units");
        report.put("split", split);
        report.put("promptHashVersion", PromptFingerprint.VERSION);
        report.put("datasetSha256", loader.datasetSha256(props.getEval().getDir()));
        report.put("sampleIdsSha256", EvalItemLoader.sampleIdsSha256(items));
        report.put("schemaSha256", hash(JSON.writeValueAsString(full)));
        report.put("systemPromptSha256", hash(template.systemPrompt()));
        report.put("retrievalConfig", props.getRetrieval());
        report.put("semanticEnabled", true);
        report.put("fewshotMaxExamples", 2);
        report.put("itemCount", items.size());
        report.put("sameProcessComparisons", rounds * items.size());
        report.put("sameProcessMismatches", mismatches);
        report.put("captureAvgMs", times.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        report.put("captureP95Ms", times.get((int) Math.ceil(times.size() * .95) - 1));
        report.put("modelCallCount", 0);
        report.put("estimatedModelCostYuan", 0);
        report.put("items", baseline);
        Path output = Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.printf("PROMPT_AUDIT items=%d comparisons=%d mismatches=%d output=%s%n",
                items.size(), rounds * items.size(), mismatches.size(), output);
        if (!mismatches.isEmpty()) throw new IllegalStateException("Same-process prompt drift");
    }

    private static Map<String, Object> capture(EvalItem item, HybridSchemaProvider provider,
                                               LexicalSchemaRetriever retriever, PromptTemplate template) throws Exception {
        SchemaContext context = provider.provide(item.question());
        RetrievalResult retrieval = retriever.retrieve(item.question());
        String system = template.systemPrompt();
        String user = template.userPrompt(context, item.question());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", item.id());
        snapshot.put("promptSha256", PromptFingerprint.messagesSha256(List.of(
                new SystemMessage(system), new UserMessage(user))));
        snapshot.put("promptChars", system.length() + user.length());
        // Disjoint measurements of actual rendered content, not token estimates.
        Map<String, Integer> components = new LinkedHashMap<>();
        components.put("system", system.length());
        components.put("ddlAndJoinHints", context.ddlText().length());
        components.put("dataProfile", context.dataProfile() == null || context.dataProfile().isBlank() ? 0 : context.dataProfile().length());
        components.put("metrics", context.hasMetrics() ? context.metricsText().length() : 0);
        components.put("valueHints", context.hasValueHints() ? context.valueHints().length() : 0);
        components.put("exampleContents", context.hasExamples() ? context.examples().stream()
                .mapToInt(e -> e.question().strip().length() + e.sql().strip().length()).sum() : 0);
        components.put("question", item.question().strip().length());
        int framing = system.length() + user.length() - components.values().stream().mapToInt(Integer::intValue).sum();
        if (framing < 0) throw new IllegalStateException("Overlapping prompt components");
        components.put("framingAndInstructions", framing);
        snapshot.put("componentChars", components);
        int reasoningStart = system.indexOf("思考与推理步骤");
        int reasoningEnd = system.indexOf("硬性规则", Math.max(reasoningStart, 0));
        snapshot.put("systemReasoningDirectiveChars", reasoningStart >= 0 && reasoningEnd >= reasoningStart
                ? reasoningEnd - reasoningStart : 0);
        snapshot.put("ddlSha256", hash(context.ddlText()));
        snapshot.put("metricsSha256", hash(context.metricsText()));
        snapshot.put("valueHintsSha256", hash(context.valueHints()));
        snapshot.put("examplesSha256", hash(JSON.writeValueAsString(context.examples().stream()
                .map(e -> List.of(e.id(), e.question(), e.sql())).toList())));
        snapshot.put("tables", context.tableNames());
        snapshot.put("retrievalTables", retrieval.tableNames());
        snapshot.put("scores", new TreeMap<>(retrieval.scores()));
        snapshot.put("expandedTables", retrieval.expandedTables().stream().sorted().toList());
        snapshot.put("evidenceOrder", List.copyOf(retrieval.evidence().keySet()));
        snapshot.put("columns", context.tables().stream().map(t ->
                List.of(t.name(), t.columns().stream().map(SchemaContext.Column::name).toList())).toList());
        return snapshot;
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
