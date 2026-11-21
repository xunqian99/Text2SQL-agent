package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.text2sql.agent.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 只读评测报告读取器。
 *
 * <p>报告路径来自 {@code agent.eval.dashboard.reports} 配置白名单，
 * 不接受 HTTP 参数，也不调用模型或业务数据库。一个面板条目可以绑定
 * 多个 JSON 报告，读取器会按原始文件重新聚合，避免把三轮正式结果误读成
 * 「挑最好的一轮」。JSONL 作为逐行记录格式同样支持，便于后续接入调用账本。
 */
@Component
public class EvaluationReportReader {

    private final ObjectMapper mapper;
    private final AgentProperties properties;
    private final Clock clock;

    @Autowired
    public EvaluationReportReader(ObjectMapper mapper, AgentProperties properties) {
        this(mapper, properties, Clock.systemUTC());
    }

    EvaluationReportReader(ObjectMapper mapper, AgentProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    public DashboardSnapshot read() {
        List<ReportSummary> reports = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        AgentProperties.Eval eval = properties.getEval();
        AgentProperties.Eval.Dashboard dashboard = eval == null ? null : eval.getDashboard();
        List<AgentProperties.Eval.DashboardReport> configured = dashboard == null
                ? List.of() : dashboard.getReports();
        if (configured == null || configured.isEmpty()) {
            warnings.add("未配置离线评测报告白名单");
        } else {
            for (AgentProperties.Eval.DashboardReport spec : configured) {
                if (spec == null || spec.getPaths() == null || spec.getPaths().isEmpty()) {
                    warnings.add("跳过没有文件路径的评测条目：" + safe(spec == null ? "" : spec.getId()));
                    continue;
                }
                List<ReportFile> files = new ArrayList<>();
                for (String configuredPath : spec.getPaths()) {
                    if (configuredPath == null || configuredPath.isBlank()) {
                        warnings.add("跳过空评测文件路径：" + safe(spec.getId()));
                        continue;
                    }
                    try {
                        files.add(readFile(configuredPath));
                    } catch (IOException | RuntimeException error) {
                        warnings.add("无法读取评测文件 " + configuredPath + "：" + error.getMessage());
                    }
                }
                if (!files.isEmpty()) {
                    reports.add(summarize(spec, files, warnings));
                }
            }
        }
        return new DashboardSnapshot("evaluation-dashboard-v1", true,
                Instant.now(clock).toString(), reports, warnings);
    }

    private ReportFile readFile(String configuredPath) throws IOException {
        Path path = Path.of(configuredPath).normalize();
        byte[] bytes = Files.readAllBytes(path);
        String lower = configuredPath.toLowerCase(Locale.ROOT);
        JsonNode root;
        if (lower.endsWith(".jsonl")) {
            ArrayNode records = mapper.createArrayNode();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) records.add(mapper.readTree(line));
            }
            root = records;
        } else {
            root = mapper.readTree(bytes);
        }
        if (root == null) throw new IOException("报告内容为空");
        return new ReportFile(configuredPath, sha256(bytes), root);
    }

    private ReportSummary summarize(AgentProperties.Eval.DashboardReport spec,
                                    List<ReportFile> files,
                                    List<String> warnings) {
        Counters counters = new Counters();
        Map<String, LayerCounter> layers = new LinkedHashMap<>();
        List<FileFingerprint> fingerprints = new ArrayList<>();
        JsonNode firstMeta = null;
        String format = "unknown";
        Boolean evidenceAudit = null;

        for (ReportFile file : files) {
            fingerprints.add(new FileFingerprint(file.path(), file.sha256()));
            JsonNode root = file.root();
            if (root.isArray()) {
                format = format.equals("unknown") ? "jsonl" : format;
                counters.total += root.size();
                counters.correct += countCorrect(root);
                counters.sqlValid += inferSqlValid(root);
                counters.promptTokens += sumUsage(root, "promptTokens");
                counters.completionTokens += sumUsage(root, "completionTokens");
                counters.totalTokens += sumUsage(root, "totalTokens");
                counters.costYuan += sumUsageDouble(root, "costYuan");
                aggregateItems(root, counters, layers);
                continue;
            }
            format = format.equals("unknown") ? "json" : format;
            if (!root.path("format").asText("").isBlank()) format = root.path("format").asText();
            JsonNode meta = root.path("meta");
            if (firstMeta == null && meta.isObject()) firstMeta = meta;
            if (root.has("passedEvidenceAudit")) {
                boolean current = root.path("passedEvidenceAudit").asBoolean(false);
                evidenceAudit = evidenceAudit == null ? current : evidenceAudit && current;
            }
            aggregateRoot(root, counters, layers);
        }

        if (counters.total == 0) {
            warnings.add("评测文件没有可识别的题目记录：" + safe(spec.getId()));
        }
        String id = safe(spec.getId());
        String label = spec.getLabel() == null || spec.getLabel().isBlank() ? id : spec.getLabel();
        return new ReportSummary(
                id,
                label,
                format,
                text(firstMeta, "timestamp"),
                text(firstMeta, "promptVersion"),
                text(firstMeta, "model"),
                text(firstMeta, "gitCommit"),
                text(firstMeta, "datasetSha256"),
                text(firstMeta, "sampleIdsSha256"),
                booleanValue(firstMeta, "dryRun"),
                counters.total,
                counters.correct,
                rate(counters.correct, counters.total),
                counters.sqlValid,
                rate(counters.sqlValid, counters.total),
                counters.promptTokens,
                counters.completionTokens,
                counters.totalTokens,
                counters.costYuan,
                average(counters.p50Values),
                average(counters.p95Values),
                counters.timeout,
                counters.clarification,
                counters.validationRejected,
                counters.executionFailed,
                counters.generationFailed,
                counters.resultMismatch,
                counters.unknownUsage,
                evidenceAudit,
                fingerprints,
                toLayerSummaries(layers));
    }

    private void aggregateRoot(JsonNode root, Counters counters,
                               Map<String, LayerCounter> layers) {
        JsonNode overall = root.path("overall");
        JsonNode items = root.path("itemEvidence");
        int itemCount = items.isArray() ? items.size() : 0;
        int total = intValue(overall, "total", itemCount);
        counters.total += total;
        counters.correct += intValue(overall, "correct", countCorrect(items));
        counters.sqlValid += intValue(overall, "sqlValid", inferSqlValid(items));
        counters.promptTokens += longValue(overall, "totalPromptTokens", sumUsage(items, "promptTokens"));
        counters.completionTokens += longValue(overall, "totalCompletionTokens", sumUsage(items, "completionTokens"));
        counters.totalTokens += longValue(overall, "totalTokens", sumUsage(items, "totalTokens"));
        counters.costYuan += doubleValue(overall, "totalCostYuan", sumUsageDouble(items, "costYuan"));
        addIfNumber(counters.p50Values, overall, "p50LatencyMs");
        addIfNumber(counters.p95Values, overall, "p95LatencyMs");
        if (items.isArray() && !items.isEmpty()) {
            aggregateItems(items, counters, layers);
        } else {
            aggregateLayers(root.path("byLayer"), layers);
        }
    }

    private void aggregateItems(JsonNode items, Counters counters,
                                Map<String, LayerCounter> layers) {
        if (items == null || !items.isArray()) return;
        for (JsonNode item : items) {
            String status = item.path("status").asText("").toUpperCase(Locale.ROOT);
            boolean correct = item.path("correct").asBoolean(false);
            if (status.equals("REQUEST_TIMEOUT")) counters.timeout++;
            else if (status.equals("NEEDS_CLARIFICATION")) counters.clarification++;
            else if (status.equals("REJECTED")) counters.validationRejected++;
            else if (status.equals("EXECUTION_FAILED")) counters.executionFailed++;
            else if (status.equals("GENERATION_FAILED")) counters.generationFailed++;
            else if (status.equals("SUCCESS") && !correct) counters.resultMismatch++;
            counters.unknownUsage += intValue(item.path("budget"), "unknownUsageCalls", 0);
            String difficulty = item.path("difficulty").asText("");
            if (!difficulty.isBlank()) {
                LayerCounter layer = layers.computeIfAbsent(difficulty, ignored -> new LayerCounter());
                layer.total++;
                if (correct) layer.correct++;
                if (status.equals("SUCCESS") || status.equals("EXECUTION_FAILED")) layer.sqlValid++;
            }
        }
    }

    private void aggregateLayers(JsonNode node, Map<String, LayerCounter> layers) {
        if (node == null || !node.isObject()) return;
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            LayerCounter layer = layers.computeIfAbsent(entry.getKey(), ignored -> new LayerCounter());
            layer.total += intValue(value, "total", 0);
            layer.correct += intValue(value, "correct", 0);
            layer.sqlValid += intValue(value, "sqlValid", 0);
            addIfNumber(layer.p95Values, value, "p95LatencyMs");
        });
    }

    private Map<String, LayerSummary> toLayerSummaries(Map<String, LayerCounter> counters) {
        Map<String, LayerSummary> result = new LinkedHashMap<>();
        counters.forEach((name, counter) -> result.put(name, new LayerSummary(
                counter.total, counter.correct, rate(counter.correct, counter.total),
                counter.sqlValid, average(counter.p95Values))));
        return result;
    }

    private static int countCorrect(JsonNode items) {
        if (items == null || !items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) if (item.path("correct").asBoolean(false)) count++;
        return count;
    }

    private static int inferSqlValid(JsonNode items) {
        if (items == null || !items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) {
            String status = item.path("status").asText("");
            if (status.equals("SUCCESS") || status.equals("EXECUTION_FAILED")) count++;
        }
        return count;
    }

    private static long sumUsage(JsonNode items, String field) {
        if (items == null || !items.isArray()) return 0;
        long sum = 0;
        for (JsonNode item : items) sum += longValue(item.path("totalUsage"), field, 0);
        return sum;
    }

    private static double sumUsageDouble(JsonNode items, String field) {
        if (items == null || !items.isArray()) return 0;
        double sum = 0;
        for (JsonNode item : items) sum += doubleValue(item.path("totalUsage"), field, 0);
        return sum;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return "";
        return node.path(field).asText("");
    }

    private static boolean booleanValue(JsonNode node, String field) {
        return node != null && node.path(field).asBoolean(false);
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        return node != null && node.has(field) && node.path(field).canConvertToInt()
                ? node.path(field).asInt() : fallback;
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        return node != null && node.has(field) && node.path(field).canConvertToLong()
                ? node.path(field).asLong() : fallback;
    }

    private static double doubleValue(JsonNode node, String field, double fallback) {
        return node != null && node.has(field) && node.path(field).isNumber()
                ? node.path(field).asDouble() : fallback;
    }

    private static void addIfNumber(List<Double> values, JsonNode node, String field) {
        if (node != null && node.has(field) && node.path(field).isNumber()) values.add(node.path(field).asDouble());
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static long average(List<Double> values) {
        if (values.isEmpty()) return 0;
        return Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未命名" : value;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static final class ReportFile {
        private final String path;
        private final String sha256;
        private final JsonNode root;

        private ReportFile(String path, String sha256, JsonNode root) {
            this.path = path;
            this.sha256 = sha256;
            this.root = root;
        }

        String path() { return path; }
        String sha256() { return sha256; }
        JsonNode root() { return root; }
    }

    private static final class Counters {
        int total;
        int correct;
        int sqlValid;
        long promptTokens;
        long completionTokens;
        long totalTokens;
        double costYuan;
        int timeout;
        int clarification;
        int validationRejected;
        int executionFailed;
        int generationFailed;
        int resultMismatch;
        int unknownUsage;
        List<Double> p50Values = new ArrayList<>();
        List<Double> p95Values = new ArrayList<>();
    }

    private static final class LayerCounter {
        int total;
        int correct;
        int sqlValid;
        List<Double> p95Values = new ArrayList<>();
    }

    public record DashboardSnapshot(String format, boolean readOnly, String generatedAt,
                                    List<ReportSummary> reports, List<String> warnings) {
    }

    public record ReportSummary(String id, String label, String format, String timestamp,
                                String promptVersion, String model, String codeCommit,
                                String datasetSha256, String sampleIdsSha256, boolean dryRun,
                                int sampleCount, int correct, double accuracy,
                                int sqlValid, double sqlValidRate,
                                long promptTokens, long completionTokens, long totalTokens,
                                double costYuan, long p50LatencyMs, long p95LatencyMs,
                                int timeoutCount, int clarificationCount,
                                int validationRejectedCount, int executionFailedCount,
                                int generationFailedCount, int resultMismatchCount,
                                int unknownUsageCalls, Boolean passedEvidenceAudit,
                                List<FileFingerprint> sourceFiles,
                                Map<String, LayerSummary> byLayer) {
    }

    public record FileFingerprint(String path, String sha256) {
    }

    public record LayerSummary(int sampleCount, int correct, double accuracy,
                               int sqlValid, long p95LatencyMs) {
    }
}
