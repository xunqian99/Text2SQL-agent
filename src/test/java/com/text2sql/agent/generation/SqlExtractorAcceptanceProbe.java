package com.text2sql.agent.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline replay of stored SQL, not raw model responses and not a model-accuracy evaluation. */
public final class SqlExtractorAcceptanceProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected matrix directory and output JSON");
        Path matrix = Path.of(args[0]);
        var manifest = JSON.readTree(matrix.resolve("manifest.json").toFile());
        var checks = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (String input : SqlExtractorTest.outerWrappers().toList()) {
            checks.add(check("wrapper-" + ++index, input, "SELECT 1;", false));
        }
        for (String sql : SqlExtractorTest.sqlBodies().toList()) {
            for (String input : wrappers(sql)) {
                checks.add(check("body-" + ++index, input, sql, false));
            }
        }
        for (String input : List.of(
                "```sql\nSELECT 1;\n```\n\n```sql\nDROP TABLE orders;\n```",
                "SELECT 1;\n```sql\nSELECT 2;\n```",
                "```sql\nSELECT 1;\n```\nDROP TABLE orders;",
                "```sql\nSELECT 1;\n```\n\n以上 SQL 统计订单。",
                "```python\nSELECT 1;\n```", "```sql SELECT 1;```")) {
            checks.add(check("ambiguous-" + ++index, input, null, true));
        }
        var replay = new ArrayList<Map<String, Object>>();
        int storedSqlCount = 0;
        int knownTrailingFenceCount = 0;
        var corpus = MessageDigest.getInstance("SHA-256");
        for (JsonNode run : manifest.path("runs")) {
            Path reportPath = matrix.resolve(Path.of(run.path("directory").asText()).getFileName())
                    .resolve(run.path("report").asText());
            byte[] bytes = Files.readAllBytes(reportPath);
            corpus.update(bytes);
            for (JsonNode item : JSON.readTree(bytes).path("itemEvidence")) {
                for (JsonNode attempt : item.path("productionAttempts")) {
                    if (!attempt.path("sql").isTextual()) continue;
                    storedSqlCount++;
                    String stored = attempt.path("sql").asText().strip();
                    // The archived corpus contains one known residual closing line. Do not edit the archive.
                    String expected = stored;
                    if (stored.endsWith("\n```")) {
                        knownTrailingFenceCount++;
                        expected = stored.substring(0, stored.length() - 4).stripTrailing();
                    }
                    String identity = run.path("round").asInt() + ":" + run.path("group").asText()
                            + ":" + item.path("id").asText() + ":" + attempt.path("number").asInt();
                    replay.add(check(identity + ":stored", stored, expected, false));
                    int variant = 0;
                    for (String input : wrappers(expected)) {
                        replay.add(check(identity + ":synthetic-wrapper-" + ++variant, input, expected, false));
                    }
                }
            }
        }
        byte[] classBytes;
        try (var stream = SqlExtractor.class.getResourceAsStream("SqlExtractor.class")) {
            if (stream == null) throw new IllegalStateException("Extractor bytecode unavailable");
            classBytes = stream.readAllBytes();
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", "sql-extraction-replay-v1");
        report.put("extractorClassSha256", hash(classBytes));
        report.put("corpusSha256", HexFormat.of().formatHex(corpus.digest()));
        report.put("boundaryTotal", checks.size());
        report.put("boundaryMatched", checks.stream().filter(c -> Boolean.TRUE.equals(c.get("matched"))).count());
        report.put("storedSqlCount", storedSqlCount);
        report.put("knownResidualClosingLines", knownTrailingFenceCount);
        report.put("replayTotal", replay.size());
        report.put("replayMatched", replay.stream().filter(c -> Boolean.TRUE.equals(c.get("matched"))).count());
        report.put("boundaryChecks", checks);
        report.put("replayChecks", replay);
        report.put("modelCalls", 0);
        report.put("modelTokens", 0);
        report.put("estimatedModelCostYuan", 0);
        report.put("limits", List.of("Replays stored extracted SQL, not original raw model output.",
                "Synthetic wrappers and repeated stored queries are not independent questions.",
                "Exact SQL preservation is not database result equivalence or model accuracy.",
                "No timing/accuracy benefit is extrapolated from this replay."));
        Path output = Path.of(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Boundary matched " + report.get("boundaryMatched") + "/" + checks.size()
                + "; replay matched " + report.get("replayMatched") + "/" + replay.size());
    }

    private static List<String> wrappers(String sql) {
        return List.of("```sql\n" + sql + "\n```", sql + "\n```", "```sql\n" + sql);
    }

    private static Map<String, Object> check(String id, String input, String expected, boolean reject) throws Exception {
        String actual = null;
        String reason = null;
        try { actual = SqlExtractor.extract(input); }
        catch (GenerationException e) { reason = e.getReason().name(); }
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("id", id);
        check.put("inputSha256", hash(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check.put("expectedSqlSha256", expected == null ? null : hash(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check.put("actualSqlSha256", actual == null ? null : hash(actual.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check.put("expectedRejection", reject);
        check.put("actualRejection", reason);
        check.put("matched", reject ? "UNPARSEABLE".equals(reason) : expected.equals(actual));
        return check;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
