package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.api.EvaluationReportController;
import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvaluationReportReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void aggregatesMultipleJsonRunsAndKeepsSourceFingerprints() throws Exception {
        Path first = writeJson("run-1.json", report("2026-11-21T10:00:00Z", true, false));
        Path second = writeJson("run-2.json", report("2026-11-21T10:01:00Z", false, true));

        AgentProperties properties = propertiesFor(new AgentProperties.Eval.DashboardReport(
                "fixture", "固定候选", List.of(first.toString(), second.toString())));
        EvaluationReportReader reader = new EvaluationReportReader(JSON, properties,
                Clock.fixed(Instant.parse("2026-11-21T11:00:00Z"), ZoneOffset.UTC));

        EvaluationReportReader.DashboardSnapshot snapshot = reader.read();
        EvaluationReportReader.ReportSummary summary = snapshot.reports().get(0);

        assertThat(snapshot.readOnly()).isTrue();
        assertThat(snapshot.generatedAt()).isEqualTo("2026-11-21T11:00:00Z");
        assertThat(summary.sampleCount()).isEqualTo(4);
        assertThat(summary.correct()).isEqualTo(2);
        assertThat(summary.accuracy()).isEqualTo(0.5);
        assertThat(summary.sqlValid()).isEqualTo(4);
        assertThat(summary.timeoutCount()).isEqualTo(1);
        assertThat(summary.resultMismatchCount()).isEqualTo(1);
        assertThat(summary.unknownUsageCalls()).isEqualTo(1);
        assertThat(summary.sourceFiles()).hasSize(2);
        assertThat(summary.datasetSha256()).isEqualTo("dataset-fixture");
        assertThat(summary.byLayer()).containsKey("single_table_basic");
        assertThat(summary.byLayer().get("single_table_basic").sqlValid()).isEqualTo(3);
        assertThat(snapshot.warnings()).isEmpty();
    }

    @Test
    void readsJsonlAndReportsMissingFilesWithoutWritingOrCallingExternalServices() throws Exception {
        Path jsonl = temp.resolve("calls.jsonl");
        Files.writeString(jsonl, JSON.writeValueAsString(item("SUCCESS", true)) + "\n"
                + JSON.writeValueAsString(item("REQUEST_TIMEOUT", false)) + "\n");
        AgentProperties.Eval.DashboardReport spec = new AgentProperties.Eval.DashboardReport(
                "jsonl", "JSONL 记录", List.of(jsonl.toString(), temp.resolve("missing.json").toString()));
        EvaluationReportReader reader = new EvaluationReportReader(JSON, propertiesFor(spec),
                Clock.systemUTC());

        EvaluationReportReader.DashboardSnapshot snapshot = reader.read();

        assertThat(snapshot.reports()).hasSize(1);
        assertThat(snapshot.reports().get(0).format()).isEqualTo("jsonl");
        assertThat(snapshot.reports().get(0).sampleCount()).isEqualTo(2);
        assertThat(snapshot.reports().get(0).timeoutCount()).isEqualTo(1);
        assertThat(snapshot.warnings()).anyMatch(warning -> warning.contains("missing.json"));
        assertThat(Files.exists(temp.resolve("missing.json"))).isFalse();
    }

    @Test
    void controllerExposesReadOnlyVersionAndShaContract() throws Exception {
        Path report = writeJson("api.json", report("2026-11-21T10:00:00Z", true, false));
        EvaluationReportReader reader = new EvaluationReportReader(JSON,
                propertiesFor(new AgentProperties.Eval.DashboardReport("api", "API fixture", List.of(report.toString()))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvaluationReportController(reader)).build();

        mvc.perform(get("/api/evaluation/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("evaluation-dashboard-v2"))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.reports[0].id").value("api"))
                .andExpect(jsonPath("$.reports[0].sourceFiles[0].sha256").isNotEmpty())
                .andExpect(jsonPath("$.reports[0].sampleCount").value(2));
    }

    @Test
    void attributesFailuresIntoAllStableCategoriesAndKeepsSourceIndex() throws Exception {
        Path first = writeJson("round-1.json", Map.of(
                "overall", Map.of("total", 7, "correct", 0, "sqlValid", 6),
                "retrieval", Map.of("incompleteCases", List.of(Map.of("id", "T1-001"))),
                "itemEvidence", List.of(
                        evidence("T1-001", "SUCCESS", false),
                        evidence("T1-002", "GENERATION_FAILED", false),
                        evidence("T1-003", "REJECTED", false),
                        evidence("T1-004", "EXECUTION_FAILED", false),
                        evidence("T1-005", "REQUEST_TIMEOUT", false),
                        evidence("T1-006", "NEEDS_CLARIFICATION", false),
                        evidence("T1-007", "SUCCESS", false))));
        Path second = writeJson("round-2.json", Map.of(
                "overall", Map.of("total", 1, "correct", 0, "sqlValid", 1),
                "itemEvidence", List.of(evidence("T1-001", "SUCCESS", false))));

        EvaluationReportReader reader = new EvaluationReportReader(JSON,
                propertiesFor(new AgentProperties.Eval.DashboardReport("fixture", "fixture",
                        List.of(first.toString(), second.toString()))));
        EvaluationReportReader.ReportSummary summary = reader.read().reports().get(0);

        assertThat(summary.failureAttribution()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "RETRIEVAL", 1, "GENERATION", 1, "VALIDATION", 1,
                "EXECUTION", 1, "TIMEOUT", 1, "CLARIFICATION", 1,
                "RESULT_MISMATCH", 2));
        assertThat(summary.replayItems()).extracting(EvaluationReportReader.ReplayIndex::sourceIndex)
                .containsExactly(0, 0, 0, 0, 0, 0, 0, 1);
        assertThat(summary.replayItems()).extracting(EvaluationReportReader.ReplayIndex::itemId)
                .containsExactly("T1-001", "T1-002", "T1-003", "T1-004", "T1-005", "T1-006", "T1-007", "T1-001");
    }

    @Test
    void replaysSavedEvidenceAndGoldWithoutCallingModelOrDatabase() throws Exception {
        Path evalDir = temp.resolve("eval");
        Files.createDirectories(evalDir);
        Files.writeString(evalDir.resolve("T1_fixture.yaml"), "- id: T1-001\n"
                + "  difficulty: single_table_basic\n"
                + "  question: 一共有多少笔订单？\n"
                + "  gold_sql: SELECT COUNT(*) FROM orders;\n");
        Files.writeString(evalDir.resolve("gold_results.json"), "{\"T1-001\":{"
                + "\"file\":\"T1_fixture.yaml\",\"digest\":\"gold-digest\","
                + "\"rows\":[[\"100\"]],\"row_count\":1}}\n");
        Path report = writeJson("replay.json", Map.of(
                "overall", Map.of("total", 1, "correct", 0, "sqlValid", 1),
                "itemEvidence", List.of(Map.of(
                        "id", "T1-001", "status", "SUCCESS", "correct", false,
                        "finalSql", "SELECT COUNT(*) FROM orders WHERE order_status = 'canceled';",
                        "message", "结果不一致", "wallLatencyMs", 42,
                        "productionAttempts", List.of(Map.of("number", 1, "stage", "INITIAL_SQL",
                                "status", "SUCCESS", "sql", "SELECT COUNT(*) FROM orders WHERE order_status = 'canceled';",
                                "message", "", "correct", false, "resultSha256", "observed-sha"))))));

        AgentProperties properties = propertiesFor(new AgentProperties.Eval.DashboardReport(
                "fixture", "fixture", List.of(report.toString())));
        properties.getEval().setDir(evalDir.toString());
        EvaluationReportReader reader = new EvaluationReportReader(JSON, properties,
                Clock.fixed(Instant.parse("2026-11-21T11:00:00Z"), ZoneOffset.UTC));

        EvaluationReportReader.ReplaySnapshot replay = reader.replay("fixture", 0, "T1-001");
        assertThat(replay.offlineReplay()).isTrue();
        assertThat(replay.sourceIndex()).isZero();
        assertThat(replay.question()).isEqualTo("一共有多少笔订单？");
        assertThat(replay.goldSql()).contains("COUNT(*)");
        assertThat(replay.goldResult().path("rows").get(0).get(0).asText()).isEqualTo("100");
        assertThat(replay.goldResultSha256()).isEqualTo("gold-digest");
        assertThat(replay.generatedSql()).contains("canceled");
        assertThat(replay.attempts()).hasSize(1);
        assertThat(replay.attempts().get(0).resultSha256()).isEqualTo("observed-sha");
        assertThat(replay.observedResultSha256()).isEqualTo("observed-sha");
        assertThat(replay.category()).isEqualTo("RESULT_MISMATCH");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvaluationReportController(reader)).build();
        mvc.perform(get("/api/evaluation/reports/fixture/sources/0/items/T1-001/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offlineReplay").value(true))
                .andExpect(jsonPath("$.goldSql").value("SELECT COUNT(*) FROM orders;"))
                .andExpect(jsonPath("$.attempts[0].resultSha256").value("observed-sha"));
    }

    @Test
    void replayFailsClosedWhenGoldEvidenceIsMissingAndControllerReturnsNotFound() throws Exception {
        Path report = writeJson("missing-gold.json", Map.of(
                "itemEvidence", List.of(evidence("T1-001", "SUCCESS", false))));
        AgentProperties properties = propertiesFor(new AgentProperties.Eval.DashboardReport(
                "fixture", "fixture", List.of(report.toString())));
        properties.getEval().setDir(temp.resolve("missing-eval").toString());
        EvaluationReportReader reader = new EvaluationReportReader(JSON, properties);

        assertThatThrownBy(() -> reader.replay("fixture", 0, "T1-001"))
                .isInstanceOf(EvaluationReportReader.ReplayUnavailableException.class)
                .hasMessageContaining("Gold");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvaluationReportController(reader)).build();
        mvc.perform(get("/api/evaluation/reports/fixture/sources/0/items/T1-001/replay"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPLAY_UNAVAILABLE"));
    }

    @Test
    void historicalFormalReportsRetainTheirMeasuredAccuracyAndDistinctSha() {
        AgentProperties properties = new AgentProperties();
        properties.getEval().getDashboard().setReports(List.of(
                new AgentProperties.Eval.DashboardReport("p15", "p15", List.of(
                        "reports/stage2-p15-regression-20261117/r1/eval-20261117-234816.json",
                        "reports/stage2-p15-regression-20261117/r2/eval-20261117-235412.json",
                        "reports/stage2-p15-regression-20261117/r3/eval-20261117-235952.json")),
                new AgentProperties.Eval.DashboardReport("p18", "p18", List.of(
                        "reports/stage2-dimension-display-regression-20261118/r1/eval-20261118-223447.json",
                        "reports/stage2-dimension-display-regression-20261118/r2/eval-20261118-224116.json",
                        "reports/stage2-dimension-display-regression-20261118/r3/eval-20261118-224744.json"))));

        EvaluationReportReader.DashboardSnapshot snapshot = new EvaluationReportReader(JSON, properties).read();

        assertThat(snapshot.warnings()).isEmpty();
        assertThat(snapshot.reports()).extracting(EvaluationReportReader.ReportSummary::id)
                .containsExactly("p15", "p18");
        assertThat(snapshot.reports()).extracting(EvaluationReportReader.ReportSummary::format)
                .containsExactly("json", "json");
        assertThat(snapshot.reports().get(0).accuracy()).isCloseTo(256d / 300d,
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(snapshot.reports().get(1).accuracy()).isCloseTo(234d / 300d,
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(snapshot.reports().get(0).sourceFiles()).allSatisfy(file ->
                assertThat(file.sha256()).hasSize(64));
    }

    private AgentProperties propertiesFor(AgentProperties.Eval.DashboardReport report) {
        AgentProperties properties = new AgentProperties();
        properties.getEval().getDashboard().setReports(List.of(report));
        return properties;
    }

    private Path writeJson(String name, Map<String, Object> value) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, JSON.writeValueAsString(value));
        return path;
    }

    private Map<String, Object> report(String timestamp, boolean correct, boolean timeout) {
        String status = timeout ? "REQUEST_TIMEOUT" : "SUCCESS";
        boolean firstCorrect = timeout ? false : correct;
        boolean secondCorrect = timeout || !correct;
        return Map.of(
                "meta", Map.of("timestamp", timestamp, "promptVersion", "fixture-v1", "model", "fixture",
                        "gitCommit", "fixture-commit", "datasetSha256", "dataset-fixture",
                        "sampleIdsSha256", "samples-fixture", "dryRun", false),
                "overall", Map.of("total", 2, "correct", (firstCorrect ? 1 : 0) + (secondCorrect ? 1 : 0), "sqlValid", 2,
                        "p50LatencyMs", 10, "p95LatencyMs", 20,
                        "totalPromptTokens", 20, "totalCompletionTokens", 5,
                        "totalTokens", 25, "totalCostYuan", 0.01),
                "itemEvidence", List.of(item(status, firstCorrect), item("SUCCESS", secondCorrect)),
                "byLayer", Map.of("single_table_basic", Map.of("total", 2, "correct", (firstCorrect ? 1 : 0) + (secondCorrect ? 1 : 0),
                        "sqlValid", 2, "p95LatencyMs", 20)));
    }

    private Map<String, Object> item(String status, boolean correct) {
        return Map.of("id", "T1-001", "difficulty", "single_table_basic", "status", status,
                "correct", correct, "totalUsage", Map.of("promptTokens", 10, "completionTokens", 2,
                        "totalTokens", 12, "costYuan", 0.005),
                "budget", Map.of("unknownUsageCalls", status.equals("REQUEST_TIMEOUT") ? 1 : 0));
    }

    private Map<String, Object> evidence(String id, String status, boolean correct) {
        return Map.of("id", id, "difficulty", "single_table_basic", "status", status,
                "correct", correct, "finalSql", correct ? "SELECT 1" : "SELECT 2",
                "wallLatencyMs", 12);
    }
}
