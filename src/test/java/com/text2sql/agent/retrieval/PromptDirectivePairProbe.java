package com.text2sql.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItem;
import com.text2sql.agent.evaluation.EvalItemLoader;
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

/** Preflight witnesses of both actual message variants. Reads schema; never creates a model client. */
public final class PromptDirectivePairProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Expected new output path and frozen commit");
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("Never overwrite prompt witnesses");
        var props = new AgentProperties();
        props.getEval().setSplit("dev");
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
                env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"), env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro")));
        var catalog = new SchemaCatalog(new DatabaseSchemaReader(jdbc), props);
        var schema = catalog.full();
        if (schema.tables().isEmpty()) throw new IllegalStateException("Empty schema");
        var glossary = new GlossaryLoader(catalog);
        var metrics = new MetricRegistry();
        ReflectionTestUtils.invokeMethod(metrics, "load");
        var examples = new ExampleLoader();
        examples.init();
        var retriever = new LexicalSchemaRetriever(glossary, catalog, props);
        var provider = new HybridSchemaProvider(catalog, retriever, glossary, props, metrics,
                new ExampleSelector(examples), new ValueRetriever(glossary), new ColumnPruner(glossary));
        var loader = new EvalItemLoader(props);
        var items = loader.load();
        if (items.size() != 100) throw new IllegalStateException("Expected original dev100");
        var full = new PromptTemplate(props);
        var candidateProps = new AgentProperties();
        candidateProps.getPrompt().setExplicitReasoningEnabled(false);
        var candidate = new PromptTemplate(candidateProps);
        String originalSystem = full.systemPrompt();
        String candidateSystem = candidate.systemPrompt();
        int start = originalSystem.indexOf("思考与推理步骤");
        int end = originalSystem.indexOf("硬性规则");
        if (start < 0 || end - start != 259 || !candidateSystem.equals(originalSystem.substring(0, start) + originalSystem.substring(end)))
            throw new IllegalStateException("Unexpected system delta");
        var baseline = new ArrayList<Map<String, Object>>();
        for (var item : items) baseline.add(capture(item, provider, retriever, full, candidate));
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < items.size(); i++) {
                if (!baseline.get(i).equals(capture(items.get(i), provider, retriever, full, candidate)))
                    throw new IllegalStateException("Prompt pair drift: " + items.get(i).id());
            }
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("format", "prompt-directive-pairs-v1");
        report.put("codeCommit", args[1]);
        report.put("split", "dev");
        report.put("datasetSha256", loader.datasetSha256(props.getEval().getDir()));
        report.put("sampleIdsSha256", EvalItemLoader.sampleIdsSha256(items));
        report.put("schemaSha256", hash(JSON.writeValueAsString(schema)));
        report.put("promptHashVersion", PromptFingerprint.VERSION);
        report.put("defaultPromptVersion", full.version());
        report.put("candidatePromptVersion", candidate.version());
        report.put("defaultSystemSha256", hash(originalSystem));
        report.put("candidateSystemSha256", hash(candidateSystem));
        report.put("defaultSystemChars", originalSystem.length());
        report.put("candidateSystemChars", candidateSystem.length());
        report.put("removedChars", 259);
        report.put("modelCalls", 0);
        report.put("sameProcessComparisons", 300);
        report.put("sameProcessMismatches", List.of());
        report.put("items", baseline);
        Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("PROMPT_PAIRS items=100 comparisons=300 mismatches=0 modelCalls=0");
    }

    private static Map<String, Object> capture(EvalItem item, HybridSchemaProvider provider,
                                              LexicalSchemaRetriever retriever, PromptTemplate full,
                                              PromptTemplate candidate) throws Exception {
        var context = provider.provide(item.question());
        var retrieval = retriever.retrieve(item.question());
        String user = full.userPrompt(context, item.question());
        if (!user.equals(candidate.userPrompt(context, item.question()))) throw new IllegalStateException("User message changed");
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("id", item.id());
        snapshot.put("questionSha256", hash(item.question()));
        snapshot.put("commonUserSha256", hash(user));
        snapshot.put("commonUserChars", user.length());
        snapshot.put("defaultPromptSha256", PromptFingerprint.messagesSha256(List.of(new SystemMessage(full.systemPrompt()), new UserMessage(user))));
        snapshot.put("candidatePromptSha256", PromptFingerprint.messagesSha256(List.of(new SystemMessage(candidate.systemPrompt()), new UserMessage(user))));
        snapshot.put("defaultPromptChars", full.systemPrompt().length() + user.length());
        snapshot.put("candidatePromptChars", candidate.systemPrompt().length() + user.length());
        snapshot.put("contextSha256", hash(JSON.writeValueAsString(context)));
        snapshot.put("tables", context.tableNames());
        snapshot.put("scores", new TreeMap<>(retrieval.scores()));
        snapshot.put("expandedTables", retrieval.expandedTables().stream().sorted().toList());
        snapshot.put("evidenceOrder", List.copyOf(retrieval.evidence().keySet()));
        return snapshot;
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
