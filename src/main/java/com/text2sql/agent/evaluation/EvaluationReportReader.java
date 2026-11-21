package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.text2sql.agent.config.AgentProperties;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.Set;
import java.util.stream.Stream;

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
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
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
                for (int sourceIndex = 0; sourceIndex < spec.getPaths().size(); sourceIndex++) {
                    String configuredPath = spec.getPaths().get(sourceIndex);
                    if (configuredPath == null || configuredPath.isBlank()) {
                        warnings.add("跳过空评测文件路径：" + safe(spec.getId()));
                        continue;
                    }
                    try {
                        files.add(readFile(sourceIndex, configuredPath));
                    } catch (IOException | RuntimeException error) {
                        warnings.add("无法读取评测文件 " + configuredPath + "：" + error.getMessage());
                    }
                }
                if (!files.isEmpty()) {
                    reports.add(summarize(spec, files, warnings));
                }
            }
        }
        return new DashboardSnapshot("evaluation-dashboard-v2", true,
                Instant.now(clock).toString(), reports, warnings);
    }

    /**
     * 读取一条已落盘评测证据并拼出离线回放快照。
     *
     * <p>reportId、sourceIndex 和 itemId 只用于选择配置白名单中的已有文件；
     * 这个方法不会创建模型客户端、不会访问 JdbcTemplate，也不会执行任何 SQL。
     */
    public ReplaySnapshot replay(String reportId, int sourceIndex, String itemId) {
        AgentProperties.Eval.DashboardReport spec = configuredReport(reportId);
        if (sourceIndex < 0 || sourceIndex >= spec.getPaths().size()) {
            throw replayUnavailable("源报告索引不存在：" + sourceIndex);
        }
        String configuredPath = spec.getPaths().get(sourceIndex);
        if (configuredPath == null || configuredPath.isBlank()) {
            throw replayUnavailable("源报告路径为空：" + sourceIndex);
        }
        final ReportFile file;
        try {
            file = readFile(sourceIndex, configuredPath);
        } catch (IOException | RuntimeException error) {
            throw replayUnavailable("无法读取源报告：" + configuredPath, error);
        }
        if (!file.root().isObject()) {
            throw replayUnavailable("源报告不是对象格式，无法回放：" + configuredPath);
        }
        JsonNode evidence = findItemEvidence(file.root(), itemId);
        if (evidence == null) {
            throw replayUnavailable("源报告中没有题目证据：" + itemId);
        }

        String normalizedItemId = evidence.path("id").asText("");
        if (normalizedItemId.isBlank()) {
            throw replayUnavailable("题目证据缺少 id：" + itemId);
        }
        GoldFixture gold = readGoldFixture(normalizedItemId);
        String category = classify(evidence.path("status").asText("").toUpperCase(Locale.ROOT),
                evidence.path("correct").asBoolean(false), retrievalIds(file.root()).contains(normalizedItemId));
        if (category == null) category = "RESULT_MISMATCH";
        return new ReplaySnapshot(
                "evaluation-replay-v1",
                true,
                safe(spec.getId()),
                sourceIndex,
                file.path(),
                file.sha256(),
                normalizedItemId,
                category,
                evidence.path("status").asText(""),
                gold.difficulty(),
                gold.question(),
                gold.goldSql(),
                gold.result(),
                gold.resultSha256(),
                firstNonBlank(evidence.path("finalSql").asText(""), evidence.path("generatedSql").asText("")),
                evidence.path("message").isNull() ? "" : evidence.path("message").asText(""),
                evidence.path("correct").asBoolean(false),
                firstNonBlank(evidence.path("observedResultSha256").asText(""),
                        evidence.path("resultSha256").asText("")),
                replayAttempts(evidence));
    }

    private AgentProperties.Eval.DashboardReport configuredReport(String reportId) {
        AgentProperties.Eval eval = properties.getEval();
        AgentProperties.Eval.Dashboard dashboard = eval == null ? null : eval.getDashboard();
        List<AgentProperties.Eval.DashboardReport> reports = dashboard == null ? List.of() : dashboard.getReports();
        if (reports != null) {
            for (AgentProperties.Eval.DashboardReport report : reports) {
                if (report != null && safe(report.getId()).equals(safe(reportId))) return report;
            }
        }
        throw replayUnavailable("评测版本不在配置白名单中：" + reportId);
    }

    private JsonNode findItemEvidence(JsonNode root, String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        JsonNode items = root.path("itemEvidence");
        if (!items.isArray()) return null;
        for (JsonNode item : items) {
            if (itemId.equals(item.path("id").asText(""))) return item;
        }
        return null;
    }

    private GoldFixture readGoldFixture(String itemId) {
        String evalDir = properties.getEval() == null ? "data/eval" : properties.getEval().getDir();
        if (evalDir == null || evalDir.isBlank()) throw replayUnavailable("评测集目录未配置");
        Path base = Path.of(evalDir).normalize();
        Path resultsPath = base.resolve("gold_results.json").normalize();
        try {
            if (!Files.isRegularFile(resultsPath)) {
                throw replayUnavailable("缺少 Gold 结果文件：" + resultsPath);
            }
            JsonNode results = mapper.readTree(Files.readAllBytes(resultsPath));
            JsonNode result = results.path(itemId);
            if (!result.isObject() || !result.has("rows")) {
                throw replayUnavailable("Gold 结果中没有题目：" + itemId);
            }
            String yamlName = result.path("file").asText("");
            Path yamlPath = yamlName.isBlank() ? findYaml(base, itemId) : base.resolve(yamlName).normalize();
            if (!yamlPath.startsWith(base) || !Files.isRegularFile(yamlPath)) {
                throw replayUnavailable("缺少题目 Gold YAML：" + yamlPath);
            }
            JsonNode yamlRoot = yamlMapper.readTree(Files.readAllBytes(yamlPath));
            JsonNode item = findYamlItem(yamlRoot, itemId);
            if (item == null) throw replayUnavailable("Gold YAML 中没有题目：" + itemId);
            String question = item.path("question").asText("");
            String goldSql = item.path("gold_sql").asText("");
            String difficulty = item.path("difficulty").asText("");
            if (question.isBlank() || goldSql.isBlank() || difficulty.isBlank()) {
                throw replayUnavailable("Gold YAML 证据不完整：" + itemId);
            }
            String digest = firstNonBlank(result.path("sha256").asText(""), result.path("digest").asText(""));
            return new GoldFixture(question, goldSql, difficulty, result, digest);
        } catch (IOException | RuntimeException error) {
            if (error instanceof ReplayUnavailableException unavailable) throw unavailable;
            throw replayUnavailable("读取 Gold 证据失败：" + itemId, error);
        }
    }

    private Path findYaml(Path base, String itemId) throws IOException {
        try (Stream<Path> paths = Files.list(base)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .filter(path -> {
                        try {
                            return findYamlItem(yamlMapper.readTree(Files.readAllBytes(path)), itemId) != null;
                        } catch (IOException ignored) {
                            return false;
                        }
                    }).findFirst().orElse(base.resolve("missing-" + itemId + ".yaml"));
        }
    }

    private JsonNode findYamlItem(JsonNode root, String itemId) {
        if (root == null || !root.isArray()) return null;
        for (JsonNode item : root) if (itemId.equals(item.path("id").asText(""))) return item;
        return null;
    }

    private List<ReplayAttempt> replayAttempts(JsonNode evidence) {
        JsonNode attempts = evidence.path("productionAttempts");
        if (!attempts.isArray()) return List.of();
        List<ReplayAttempt> result = new ArrayList<>();
        for (JsonNode attempt : attempts) {
            result.add(new ReplayAttempt(
                    intValue(attempt, "number", result.size() + 1),
                    attempt.path("stage").asText(""),
                    attempt.path("status").asText(""),
                    attempt.path("sql").isNull() ? "" : attempt.path("sql").asText(""),
                    attempt.path("message").isNull() ? "" : attempt.path("message").asText(""),
                    attempt.path("correct").asBoolean(false),
                    firstNonBlank(attempt.path("resultSha256").asText(""), attempt.path("observedResultSha256").asText(""))));
        }
        return List.copyOf(result);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static ReplayUnavailableException replayUnavailable(String message) {
        return new ReplayUnavailableException(message);
    }

    private static ReplayUnavailableException replayUnavailable(String message, Throwable cause) {
        return new ReplayUnavailableException(message, cause);
    }

    private ReportFile readFile(int sourceIndex, String configuredPath) throws IOException {
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
        return new ReportFile(sourceIndex, configuredPath, sha256(bytes), root);
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
            Set<String> retrievalIds = retrievalIds(root);
            if (root.isArray()) {
                format = format.equals("unknown") ? "jsonl" : format;
                counters.total += root.size();
                counters.correct += countCorrect(root);
                counters.sqlValid += inferSqlValid(root);
                counters.promptTokens += sumUsage(root, "promptTokens");
                counters.completionTokens += sumUsage(root, "completionTokens");
                counters.totalTokens += sumUsage(root, "totalTokens");
                counters.costYuan += sumUsageDouble(root, "costYuan");
                aggregateItems(root, counters, layers, retrievalIds, file);
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
            aggregateRoot(root, counters, layers, retrievalIds, file);
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
                toLayerSummaries(layers),
                orderedAttributions(counters.attribution),
                List.copyOf(counters.replayItems));
    }

    private void aggregateRoot(JsonNode root, Counters counters,
                               Map<String, LayerCounter> layers,
                               Set<String> retrievalIds,
                               ReportFile file) {
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
            aggregateItems(items, counters, layers, retrievalIds, file);
        } else {
            aggregateLayers(root.path("byLayer"), layers);
        }
    }

    private void aggregateItems(JsonNode items, Counters counters,
                                Map<String, LayerCounter> layers,
                                Set<String> retrievalIds,
                                ReportFile file) {
        if (items == null || !items.isArray()) return;
        for (JsonNode item : items) {
            String status = item.path("status").asText("").toUpperCase(Locale.ROOT);
            boolean correct = item.path("correct").asBoolean(false);
            if (status.equals("REQUEST_TIMEOUT") || status.equals("RESOURCE_LIMITED")) counters.timeout++;
            else if (status.equals("NEEDS_CLARIFICATION")) counters.clarification++;
            else if (status.equals("REJECTED")) counters.validationRejected++;
            else if (status.equals("EXECUTION_FAILED")) counters.executionFailed++;
            else if (status.equals("GENERATION_FAILED") || status.equals("NOT_CONFIGURED")) counters.generationFailed++;
            else if (status.equals("SUCCESS") && !correct) counters.resultMismatch++;
            String category = classify(status, correct, retrievalIds.contains(item.path("id").asText("")));
            if (category != null) {
                counters.attribution.merge(category, 1, Integer::sum);
                counters.replayItems.add(new ReplayIndex(file.sourceIndex(), file.path(),
                        item.path("id").asText(""), category, status,
                        correct, longValue(item, "wallLatencyMs", 0)));
            }
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

    private Set<String> retrievalIds(JsonNode root) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode incomplete = root.isObject() ? root.path("retrieval").path("incompleteCases") : null;
        if (incomplete != null && incomplete.isArray()) {
            for (JsonNode entry : incomplete) {
                String id = entry.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    private static String classify(String status, boolean correct, boolean retrievalMiss) {
        if (status.equals("REQUEST_TIMEOUT") || status.equals("RESOURCE_LIMITED")) return "TIMEOUT";
        if (status.equals("NEEDS_CLARIFICATION")) return "CLARIFICATION";
        if (status.equals("REJECTED")) return "VALIDATION";
        if (status.equals("EXECUTION_FAILED")) return "EXECUTION";
        if (status.equals("GENERATION_FAILED") || status.equals("NOT_CONFIGURED")) return "GENERATION";
        if (status.equals("SUCCESS") && !correct) return retrievalMiss ? "RETRIEVAL" : "RESULT_MISMATCH";
        return null;
    }

    private static Map<String, Integer> orderedAttributions(Map<String, Integer> values) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String category : List.of("RETRIEVAL", "GENERATION", "VALIDATION", "EXECUTION",
                "TIMEOUT", "CLARIFICATION", "RESULT_MISMATCH")) {
            ordered.put(category, values.getOrDefault(category, 0));
        }
        return ordered;
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
        private final int sourceIndex;
        private final String path;
        private final String sha256;
        private final JsonNode root;

        private ReportFile(int sourceIndex, String path, String sha256, JsonNode root) {
            this.sourceIndex = sourceIndex;
            this.path = path;
            this.sha256 = sha256;
            this.root = root;
        }

        int sourceIndex() { return sourceIndex; }
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
        Map<String, Integer> attribution = new LinkedHashMap<>();
        List<ReplayIndex> replayItems = new ArrayList<>();
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
                                Map<String, LayerSummary> byLayer,
                                Map<String, Integer> failureAttribution,
                                List<ReplayIndex> replayItems) {
    }

    public record FileFingerprint(String path, String sha256) {
    }

    public record LayerSummary(int sampleCount, int correct, double accuracy,
                               int sqlValid, long p95LatencyMs) {
    }

    public record ReplayIndex(int sourceIndex, String sourceFile, String itemId,
                              String category, String status, boolean correct,
                              long latencyMs) {
    }

    public record ReplaySnapshot(String format, boolean offlineReplay,
                                 String reportId, int sourceIndex, String sourceFile,
                                 String sourceSha256, String itemId, String category,
                                 String status, String difficulty, String question,
                                 String goldSql, JsonNode goldResult,
                                 String goldResultSha256, String generatedSql,
                                 String message, boolean correct,
                                 String observedResultSha256,
                                 List<ReplayAttempt> attempts) {
    }

    public record ReplayAttempt(int number, String stage, String status,
                                String sql, String message, boolean correct,
                                String resultSha256) {
    }

    private record GoldFixture(String question, String goldSql, String difficulty,
                               JsonNode result, String resultSha256) {
    }

    public static class ReplayUnavailableException extends RuntimeException {
        public ReplayUnavailableException(String message) {
            super(message);
        }

        public ReplayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
