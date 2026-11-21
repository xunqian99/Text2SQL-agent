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
                .andExpect(jsonPath("$.format").value("evaluation-dashboard-v1"))
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.reports[0].id").value("api"))
                .andExpect(jsonPath("$.reports[0].sourceFiles[0].sha256").isNotEmpty())
                .andExpect(jsonPath("$.reports[0].sampleCount").value(2));
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
}
