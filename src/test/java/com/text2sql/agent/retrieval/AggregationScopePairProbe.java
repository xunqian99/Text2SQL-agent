package com.text2sql.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Captures actual dev messages before either arm; never creates a model client. */
public final class AggregationScopePairProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("New witness JSON path required");
        var props = new AgentProperties();
        props.getEval().setSplit("dev");
        props.getRetrieval().setEnabled(true); props.getRetrieval().setColumnPruningEnabled(true);
        props.getRetrieval().setJoinHintsEnabled(true); props.getRetrieval().setJoinListEnabled(false);
        props.getRetrieval().setJoinPlanEnabled(true); props.getSemantic().setEnabled(true);
        props.getFewshot().setEnabled(true); props.getFewshot().setMaxExamples(2);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MemberDb.url(), MemberDb.user(), MemberDb.password()));
        var catalog = new SchemaCatalog(new DatabaseSchemaReader(jdbc), props);
        var glossary = new GlossaryLoader(catalog);
        var metrics = new MetricRegistry(); ReflectionTestUtils.invokeMethod(metrics, "load");
        var examples = new ExampleLoader(); examples.init();
        var provider = new HybridSchemaProvider(catalog, new LexicalSchemaRetriever(glossary, catalog, props),
                glossary, props, metrics, new ExampleSelector(examples), new ValueRetriever(glossary), new ColumnPruner(glossary));
        var loader = new EvalItemLoader(props); var items = loader.load();
        if (items.size() != 100) throw new IllegalStateException("Expected original dev100");
        var template = new PromptTemplate(props);
        String expected, actual = template.systemPrompt();
        try (var in = AggregationScopePairProbe.class.getResourceAsStream("/prompts/p15-system.txt")) {
            if (in == null) throw new IllegalStateException("Missing p15 snapshot");
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // With the baseline class first on its classpath, the baseline arm must emit the archived bytes.
        boolean baseline = template.version().equals("p15-entity-coverage-v15");
        if (baseline && !actual.equals(expected)) throw new IllegalStateException("Baseline source/snapshot differs");
        if (!baseline && !template.version().equals("p16-aggregation-scope-v16")) throw new IllegalStateException("Wrong template");
        var evidence = new ArrayList<Object>();
        for (var item : items) {
            var context = provider.provide(item.question()); String user = template.userPrompt(context, item.question());
            evidence.add(Map.of("id", item.id(), "questionSha256", sha(item.question()),
                    "userSha256", sha(user), "contextSha256", sha(new ObjectMapper().writeValueAsString(context)),
                    "promptSha256", PromptFingerprint.messagesSha256(List.of(new SystemMessage(actual), new UserMessage(user))),
                    "promptChars", actual.length() + user.length()));
        }
        var result = Map.of("promptVersion", template.version(), "systemSha256", sha(actual), "systemChars", actual.length(),
                "datasetSha256", loader.datasetSha256(props.getEval().getDir()), "sampleIdsSha256", EvalItemLoader.sampleIdsSha256(items),
                "modelCalls", 0, "items", evidence);
        Files.writeString(Path.of(args[0]), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("SCOPE_MESSAGES " + template.version() + " items=100 modelCalls=0");
    }
    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static final class MemberDb {
        static String url() { return System.getenv().getOrDefault("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"); }
        static String user() { return System.getenv().getOrDefault("TEXT2SQL_TEST_DB_USER", "text2sql_ro"); }
        static String password() { return System.getenv().getOrDefault("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro"); }
    }
}
