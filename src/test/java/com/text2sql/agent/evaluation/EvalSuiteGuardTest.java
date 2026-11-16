package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.fewshot.Example;
import com.text2sql.agent.fewshot.ExampleLoader;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvalSuiteGuardTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private AgentProperties properties;
    private EvalItemLoader loader;
    private Path suite;
    private Path reference;
    private Path manifest;
    private Map<String, Object> seal;
    private final List<Example> examples = List.of(new Example("DEV-1", "single_table_basic", "开发问题", "SELECT 2", Set.of()));

    @BeforeEach
    void setUp() throws Exception {
        suite = Files.createDirectory(root.resolve("suite"));
        reference = Files.createDirectory(root.resolve("reference"));
        manifest = root.resolve("manifest.json");
        writeItem(suite, "T1-H001", "新问题");
        writeItem(reference, "T1-001", "旧问题");
        properties = new AgentProperties();
        properties.getEval().setDir(suite.toString());
        properties.getEval().setSuiteManifest(manifest.toString());
        loader = new EvalItemLoader(properties);
        seal = new LinkedHashMap<>(Map.of("format", "frozen-eval-suite-v1", "suiteId", "holdout-test-v1",
                "purpose", "heldout-candidate", "frozenAt", "2026-11-16", "independentReview", false,
                "itemCount", 1, "layerCounts", Map.of("single_table_basic", 1),
                "referenceDir", reference.toString(), "referenceDatasetSha256", loader.datasetSha256(reference.toString())));
        reseal();
    }

    @Test
    void verifiesIdentityAndKeepsReviewAsDeclaredProvenance() {
        var identity = EvalSuiteGuard.verify(properties, loader, loader.load(), examples);
        assertThat(identity).containsEntry("suiteId", "holdout-test-v1")
                .containsEntry("declaredIndependentReview", false).containsEntry("exactOverlapCheckPassed", true);
        assertThat(identity.get("manifestSha256").toString()).hasSize(64);
    }

    @Test
    void ordinaryEvalRemainsOptInAndUnchanged() {
        properties.getEval().setSuiteManifest("");
        assertThat(EvalSuiteGuard.verify(properties, loader, loader.load(), List.of())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"split", "limit", "layers", "repeat", "oracleRetry"})
    void rejectsFilteredOrOracleAssistedRuns(String change) {
        switch (change) {
            case "split" -> properties.getEval().setSplit("eval");
            case "limit" -> properties.getEval().setLimit(1);
            case "layers" -> properties.getEval().setLayers(List.of("T1"));
            case "repeat" -> properties.getEval().setRepeat(2);
            case "oracleRetry" -> properties.getEval().setSelfCorrectionEnabled(true);
            default -> throw new IllegalArgumentException(change);
        }
        rejects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fingerprint", "count", "ids", "quota", "reference", "format", "purpose"})
    void rejectsChangedSealOrReference(String change) throws Exception {
        switch (change) {
            case "fingerprint" -> Files.writeString(suite.resolve("T1_test.yaml"), "\n", java.nio.file.StandardOpenOption.APPEND);
            case "count" -> seal.put("itemCount", 2);
            case "ids" -> seal.put("sampleIdsSha256", "different");
            case "quota" -> seal.put("layerCounts", Map.of("other", 1));
            case "reference" -> Files.writeString(reference.resolve("T1_test.yaml"), "\n", java.nio.file.StandardOpenOption.APPEND);
            case "format" -> seal.put("format", "unknown");
            case "purpose" -> seal.remove("purpose");
            default -> throw new IllegalArgumentException(change);
        }
        json.writeValue(manifest.toFile(), seal);
        rejects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference-id", "reference-question", "example-id", "example-question"})
    void rejectsNormalizedExactLeakage(String type) throws Exception {
        String id = type.equals("reference-id") ? "T1-001" : type.equals("example-id") ? "DEV-1" : "T1-H001";
        String question = type.equals("reference-question") ? "旧 问题？" : type.equals("example-question") ? "开发 问题！" : "新问题";
        writeItem(suite, id, question);
        reseal();
        rejects();
    }

    @Test
    void missingExamplesCannotBeCertifiedAsZeroOverlap() {
        assertThatThrownBy(() -> EvalSuiteGuard.verify(properties, loader, loader.load(), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Few-shot");
    }

    @Test
    void duplicateQuestionsAreRejectedEvenWithFreshFingerprints() throws Exception {
        json.writeValue(suite.resolve("T1_test.yaml").toFile(), List.of(
                item("T1-H001", "新问题"), item("T1-H002", "新 问题？")));
        seal.put("itemCount", 2);
        seal.put("layerCounts", Map.of("single_table_basic", 2));
        reseal();
        rejects();
    }

    @Test
    void runnerStopsBeforeGoldAndModelWhenSealFails() throws Exception {
        seal.put("datasetSha256", "changed");
        json.writeValue(manifest.toFile(), seal);
        var orchestrator = mock(Text2SqlOrchestrator.class);
        var executor = mock(SqlExecutor.class);
        var exampleLoader = mock(ExampleLoader.class);
        when(exampleLoader.getExamples()).thenReturn(examples);
        var runner = new EvalRunner(properties, loader, orchestrator, executor, json, null, exampleLoader);
        assertThatThrownBy(runner::runEvaluation).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(orchestrator, executor);
    }

    private void rejects() {
        assertThatThrownBy(() -> EvalSuiteGuard.verify(properties, loader, loader.load(), examples))
                .isInstanceOf(IllegalStateException.class);
    }

    private void reseal() throws Exception {
        seal.put("datasetSha256", loader.datasetSha256(suite.toString()));
        seal.put("sampleIdsSha256", EvalItemLoader.sampleIdsSha256(loader.load()));
        json.writeValue(manifest.toFile(), seal);
    }

    private void writeItem(Path directory, String id, String question) throws Exception {
        json.writeValue(directory.resolve("T1_test.yaml").toFile(), List.of(item(id, question)));
    }

    private Map<String, Object> item(String id, String question) {
        return Map.of("id", id, "difficulty", "single_table_basic", "question", question, "gold_sql", "SELECT 1");
    }
}
