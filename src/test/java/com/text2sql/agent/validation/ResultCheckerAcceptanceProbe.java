package com.text2sql.agent.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.PromptFingerprint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replays explicit boundary contracts plus 200 unchanged Gold SQLs, without a model. */
public final class ResultCheckerAcceptanceProbe {
    public record BoundaryCase(String id, String question, List<String> columns,
                               List<List<String>> rows, String expectedRule) {
        QueryResult result() { return new QueryResult(columns, rows, false, 0); }
        String sql() { return "SELECT 1"; } // Other heuristics do not interfere with column-policy tests.
    }

    public static List<BoundaryCase> cases() {
        return List.of(
                normal("negative-yoy", "各月GMV与同比增长率", List.of("gmv_2017", "gmv_2018", "yoy_pct"), "1000", "0.2", "-99.98"),
                normal("positive-yoy-over-100", "GMV同比增长率", List.of("gmv", "yoy_pct"), "1000", "250"),
                normal("negative-mom", "金额与环比", List.of("amount", "mom_rate"), "100", "-20"),
                normal("growth-named-rate", "金额增长率", List.of("amount", "growth_rate"), "100", "-20"),
                normal("signed-net-amount", "净金额是多少", List.of("net_amount"), "-200"),
                normal("signed-delta-count", "订单数量变化", List.of("order_count_change"), "-10"),
                normal("signed-price-difference", "价格差额", List.of("price_diff"), "-5"),
                normal("signed-profit", "利润金额", List.of("profit_amount"), "-15"),
                normal("signed-balance", "余额金额", List.of("balance_amount"), "-10"),
                normal("unknown-negative-column", "GMV与其他指标", List.of("gmv", "mystery"), "100", "-8"),
                normal("negative-numeric-dimension", "订单数量", List.of("bucket_id", "order_count"), "-1", "10"),
                normal("word-containing-rate", "金额与比例", List.of("separate_value", "amount"), "-1", "100"),
                normal("mixed-count-and-ratio", "订单数量和占比", List.of("order_count", "pct"), "40000", "40"),
                normal("chinese-growth", "金额与同比增长率", List.of("金额", "同比增长率"), "100", "-20"),
                normal("chinese-net", "净销售金额", List.of("净销售金额"), "-15"),
                normal("camel-growth", "GMV同比增长率", List.of("gmvYoYPct"), "-50"),
                abnormal("negative-amount", "销售金额", List.of("amount"), List.of(List.of("-320.5")), "NEGATIVE_METRIC_VALUE"),
                abnormal("negative-count-with-growth", "订单数量及同比", List.of("order_count", "yoy_pct"), List.of(List.of("-1", "-30")), "NEGATIVE_METRIC_VALUE"),
                abnormal("negative-gmv-with-growth", "GMV及同比", List.of("gmv_2018", "yoy_pct"), List.of(List.of("-10", "-30")), "NEGATIVE_METRIC_VALUE"),
                abnormal("lowercase-gmv", "gmv是多少", List.of("gmv"), List.of(List.of("-10")), "NEGATIVE_METRIC_VALUE"),
                abnormal("negative-price", "价格是多少", List.of("unit_price"), List.of(List.of("-1")), "NEGATIVE_METRIC_VALUE"),
                abnormal("negative-fee", "费用是多少", List.of("shipping_fee"), List.of(List.of("-1")), "NEGATIVE_METRIC_VALUE"),
                abnormal("negative-quantity", "商品数量", List.of("qty"), List.of(List.of("-1")), "NEGATIVE_METRIC_VALUE"),
                abnormal("mixed-ratio-with-growth", "GMV同比与退款率", List.of("gmv", "yoy_pct", "refund_rate"), List.of(List.of("100", "-30", "150")), "RATIO_OUT_OF_BOUNDS"),
                abnormal("negative-refund-ratio", "退款率", List.of("refund_ratio"), List.of(List.of("-1")), "RATIO_OUT_OF_BOUNDS"),
                abnormal("chinese-negative-count", "订单数量", List.of("订单数"), List.of(List.of("-1")), "NEGATIVE_METRIC_VALUE"),
                abnormal("negative-underflow", "金额是多少", List.of("amount"), List.of(List.of("-1e-400")), "NEGATIVE_METRIC_VALUE"),
                normal("numeric-boundaries", "GMV数量与比例", List.of("gmv", "order_count", "pct"), "0", "0", "100"),
                normal("null-and-nonnumeric", "金额是多少", List.of("amount", "unit_price"), "null", "NaN"),
                normal("sparse-row", "金额及同比", List.of("amount", "yoy_pct"), "1")
        );
    }

    private static BoundaryCase normal(String id, String q, List<String> columns, String... values) {
        return new BoundaryCase(id, q, columns, List.of(List.of(values)), null);
    }
    private static BoundaryCase abnormal(String id, String q, List<String> columns, List<List<String>> rows, String rule) {
        return new BoundaryCase(id, q, columns, rows, rule);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output JSON path");
        var checker = new ResultChecker();
        boolean baseline = Boolean.getBoolean("result.checker.baseline");
        var json = new ObjectMapper();
        var props = new AgentProperties();
        props.getEval().setSplit("all");
        var loader = new EvalItemLoader(props);
        var items = loader.load();
        if (items.size() != 200) throw new IllegalStateException("Expected 200 unchanged Gold SQLs");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"),
                env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"),
                env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro")));
        var executor = new SqlExecutor(jdbc, props);
        List<Map<String, Object>> boundaries = new ArrayList<>();
        for (var sample : cases()) {
            var check = checker.check(sample.question(), sample.sql(), sample.result());
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", sample.id());
            entry.put("question", sample.question());
            entry.put("sql", sample.sql());
            entry.put("columns", sample.columns());
            if (!baseline) entry.put("columnPolicies", ResultColumnPolicy.classify(sample.columns()));
            entry.put("rows", sample.rows());
            entry.put("expectedRule", sample.expectedRule());
            entry.put("actualRule", check.rule());
            entry.put("reason", check.reason());
            entry.put("matchedContract", java.util.Objects.equals(sample.expectedRule(), check.rule()));
            boundaries.add(entry);
        }
        List<Map<String, Object>> gold = new ArrayList<>();
        List<Double> checkerTimes = new ArrayList<>();
        for (var item : items) {
            QueryResult result = executor.execute(item.goldSql()); // Same read-only guard as normal execution.
            long start = System.nanoTime();
            var check = checker.check(item.question(), item.goldSql(), result);
            checkerTimes.add((System.nanoTime() - start) / 1_000_000.0);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", item.id());
            entry.put("sqlSha256", PromptFingerprint.textSha256(item.goldSql()));
            entry.put("resultSha256", PromptFingerprint.textSha256(json.writeValueAsString(List.of(result.columns(), result.rows()))));
            entry.put("columns", result.columns());
            if (!baseline) entry.put("columnPolicies", ResultColumnPolicy.classify(result.columns()));
            entry.put("rows", result.rowCount());
            entry.put("truncated", result.truncated());
            entry.put("suspicious", check.suspicious());
            entry.put("rule", check.rule());
            entry.put("reason", check.reason());
            gold.add(entry);
        }
        checkerTimes.sort(Double::compareTo);
        var report = new LinkedHashMap<String, Object>();
        report.put("format", "result-checker-audit-v1");
        report.put("columnPolicyVersion", baseline ? "legacy-question-wide" : ResultColumnPolicy.VERSION);
        try (var bytes = ResultChecker.class.getResourceAsStream("ResultChecker.class")) {
            report.put("checkerClassSha256", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
        }
        report.put("datasetSha256", loader.datasetSha256(props.getEval().getDir()));
        report.put("sampleIdsSha256", EvalItemLoader.sampleIdsSha256(items));
        report.put("boundaryTotal", boundaries.size());
        report.put("boundaryContractsMatched", boundaries.stream().filter(e -> Boolean.TRUE.equals(e.get("matchedContract"))).count());
        report.put("boundaries", boundaries);
        report.put("goldTotal", gold.size());
        report.put("goldSuspicious", gold.stream().filter(e -> Boolean.TRUE.equals(e.get("suspicious"))).count());
        report.put("gold", gold);
        report.put("checkerAvgMs", checkerTimes.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        report.put("checkerP95Ms", checkerTimes.get((int) Math.ceil(checkerTimes.size() * .95) - 1));
        report.put("modelCallCount", 0);
        report.put("modelTokens", 0);
        report.put("estimatedModelCostYuan", 0);
        Path path = Path.of(args[0]);
        Files.createDirectories(path.toAbsolutePath().getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        System.out.printf("RESULT_CHECK_AUDIT boundaries=%s/%d goldAlerts=%s/%d output=%s%n",
                report.get("boundaryContractsMatched"), boundaries.size(), report.get("goldSuspicious"), gold.size(), path);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
