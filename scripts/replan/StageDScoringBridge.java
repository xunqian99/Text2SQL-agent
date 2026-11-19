import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItem;
import com.text2sql.agent.evaluation.EvalItemLoader;
import com.text2sql.agent.evaluation.TableRecall;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.retrieval.DatabaseSchemaReader;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluation-only bridge: pinned public reference -> read-only Gold; saved HTTP body -> frozen scorer.
 * There is deliberately no model client, HTTP sender, candidate SQL execution, or arbitrary dataset path.
 * Run from the repository root. Compile together with A's unchanged ReferenceContractScorer.java.
 */
public final class StageDScoringBridge {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Path A = Path.of("reports/replan-stage-a-20261119");
    private static final String SCORER_SHA = "5308477a6a81567f4c93b61a75163fb272ab976ea5aefe01ce9e6d0032ff21b5";
    private static final String SCHEMA_SHA = "aa2e72c521c95b6889bdb36bf620de4eff4d5b04db3cb8e55737d25eef02769d";
    private static final String TARGET_SHA = "0da9b8f37804916268cb194bfcb06488e896e31312baa0cc8b75982f63d849dc";
    private static final String NORMALIZER_SHA = "336bfa4a18320e0d550acd9d09d5d2469ab794ceaaf7f4e85a32c8d2249338ef";
    private static final String DEV_SEAL_SHA = "4e76ff2d9c8b16b17048e894a900f77876a98f084eb5c7dcbd7652619a714a79";
    private static final String FORMAL_SEAL_SHA = "76de718b9d0b179a8d1a63bac0408721bc87225e391acd25d415259bab111a7c";
    private static final String ORIGINAL_MODE = "ORIGINAL_TARGET_STRICT_UNORDERED_MULTISET";
    private static final int MAX_GOLD_ROWS = 100_000;
    private static final List<String> YAML_NAMES = List.of("T1_single_table_basic.yaml", "T2_single_table_agg.yaml",
            "T3_time_window.yaml", "T4_multi_join.yaml", "T5_multi_join_deep.yaml", "T6_business_semantics.yaml");

    private record Reference(String id, String difficulty, String question, String sql, JsonNode policy,
                             JsonNode schema, int expectedTableCount) { }
    private record Dataset(String key, String sha256, List<Reference> items, Map<String, JsonNode> originals) { }

    private StageDScoringBridge() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("prepare")) {
            prepare(args[1], Path.of(args[2]));
        } else if (args.length == 4 && args[0].equals("score")) {
            score(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        } else {
            throw new IllegalArgumentException("prepare <dev|formal|targets> <NEW-output.json> OR "
                    + "score <prepared.json> <responses.json> <NEW-output.json>");
        }
    }

    private static Dataset references(String key) throws Exception {
        require(Set.of("dev", "formal", "targets").contains(key), "Only dev, formal, targets are permitted");
        requireSha(A.resolve("ReferenceContractScorer.java"), SCORER_SHA);
        requireSha(A.resolve("output-column-schema-attempt2.json"), SCHEMA_SHA);
        requireSha(A.resolve("original-target-preregistration.json"), TARGET_SHA);
        requireSha(Path.of("src/main/java/com/text2sql/agent/execution/ResultNormalizer.java"), NORMALIZER_SHA);
        var originals = new LinkedHashMap<String, JsonNode>();
        for (var target : array(read(A.resolve("original-target-preregistration.json")), "targets")) {
            String id = text(target, "id");
            require(originals.putIfAbsent(id, target) == null, "Duplicate original target");
            require(sha(text(target, "originalQuestion")).equals(text(target, "originalQuestionSha256")), "Original question drift");
            require(sha(text(target, "originalGoldSql")).equals(text(target, "originalGoldSqlSha256")), "Original SQL drift");
        }
        require(originals.keySet().equals(Set.of("T4-001", "T3-002", "T6-003")), "Original target identities drift");
        if (key.equals("targets")) {
            var items = new ArrayList<Reference>();
            for (String id : List.of("T4-001", "T6-003")) {
                var target = originals.get(id);
                require(text(target, "confirmation").equals("ONE_ORIGINAL_REQUEST_AFTER_DEV_BEFORE_FORMAL")
                        && integer(target, "requestLimit") == 1, "Original request registration drift");
                var policy = JSON.createObjectNode().put("mode", ORIGINAL_MODE).put("sqlAwareTiesProhibited", true)
                        .put("originalTargetPreregistrationSha256", TARGET_SHA);
                String sql = text(target, "originalGoldSql");
                items.add(new Reference(id, id.equals("T4-001") ? "multi_join" : "business_semantics",
                        text(target, "originalQuestion"), sql, policy, null, tableCount(sql)));
            }
            return new Dataset(key, TARGET_SHA, List.copyOf(items), originals);
        }
        String suite = key.equals("dev") ? "development" : "formal";
        Path directory = Path.of("data/benchmarks/olist-v4-" + suite + "-20261119");
        Path sealPath = directory.resolve("reference-seal.json");
        requireSha(sealPath, key.equals("dev") ? DEV_SEAL_SHA : FORMAL_SEAL_SHA);
        var seal = read(sealPath);
        require(text(seal, "suite").equals(suite) && integer(seal, "itemCount") == 100, "Reference suite/denominator drift");
        var expectedFiles = new HashSet<>(YAML_NAMES);
        expectedFiles.add("scoring-policy.json");
        var sealFiles = object(seal, "files");
        require(fieldNames(sealFiles).equals(expectedFiles), "Sealed reference file list drift");
        for (String name : expectedFiles) requireSha(directory.resolve(name), text(sealFiles, name));
        try (var listing = Files.list(directory)) {
            var yamlFiles = listing.map(p -> p.getFileName().toString()).filter(n -> n.matches("T\\d+_.*\\.ya?ml")).toList();
            require(new HashSet<>(yamlFiles).equals(new HashSet<>(YAML_NAMES)), "Unexpected reference YAML file");
        }
        var loader = new EvalItemLoader(new AgentProperties());
        require(loader.datasetSha256(directory.toString()).equals(text(seal, "datasetSha256")), "Reference dataset hash drift");
        List<EvalItem> loaded = loader.load(directory.toString(), List.of(), 0);
        require(loaded.size() == 100 && EvalItemLoader.sampleIdsSha256(loaded).equals(text(seal, "sampleIdsSha256")),
                "Reference IDs/denominator drift");
        var policyRoot = read(directory.resolve("scoring-policy.json"));
        require(text(policyRoot, "suite").equals(suite) && text(policyRoot, "evaluatorSourceSha256").equals(SCORER_SHA)
                && text(policyRoot, "referenceColumnSchemaSha256").equals(SCHEMA_SHA), "Frozen scorer/type binding drift");
        var policies = object(policyRoot, "policies");
        var schemas = object(read(A.resolve("output-column-schema-attempt2.json")), "items");
        require(schemas.size() == 200 && policies.size() == 100, "Frozen metadata denominator drift");
        var items = new ArrayList<Reference>();
        var seen = new HashSet<String>();
        var layers = new LinkedHashMap<String, Integer>();
        for (var item : loaded) {
            require(seen.add(item.id()), "Duplicate reference ID");
            var policy = policies.get(item.id());
            var schema = schemas.get(suite + ":" + item.id());
            require(policy != null && policy.isObject() && schema != null && schema.isObject(), "Missing frozen item metadata");
            require(sha(item.question()).equals(text(schema, "questionSha256"))
                    && sha(item.goldSql()).equals(text(schema, "goldSqlSha256")), "Question/SQL type binding drift: " + item.id());
            var kinds = array(policy, "columnKinds");
            var columns = array(schema, "columns");
            require(kinds.size() == columns.size() && !columns.isEmpty(), "Reference column count drift");
            for (int i = 0; i < columns.size(); i++) {
                require(integer(columns.get(i), "position") == i && kinds.get(i).isTextual()
                        && kinds.get(i).asText().equals(text(columns.get(i), "kind")), "Frozen column-kind binding drift");
            }
            layers.merge(item.difficulty(), 1, Integer::sum);
            items.add(new Reference(item.id(), item.difficulty(), item.question(), item.goldSql(), policy, schema, tableCount(item.goldSql())));
        }
        require(seen.equals(fieldNames(policies)) && JSON.valueToTree(layers).equals(seal.get("layerCounts")), "Policy/layer identity drift");
        if (key.equals("dev")) {
            var t3 = items.stream().filter(i -> i.id().equals("T3-002")).findFirst().orElseThrow();
            requireOriginalReuse(t3, originals.get("T3-002"));
        }
        return new Dataset(key, text(seal, "datasetSha256"), List.copyOf(items), originals);
    }

    private static void requireOriginalReuse(Reference reference, JsonNode target) throws Exception {
        require(text(target, "confirmation").equals("REUSE_FINAL_CANDIDATE_UNIQUE_DEV_RESPONSE")
                && integer(target, "requestLimit") == 0 && target.path("sameQuestion").asBoolean()
                && target.path("sameGoldSql").asBoolean(), "T3 reuse is not registered");
        require(sha(reference.question()).equals(text(target, "originalQuestionSha256"))
                && sha(reference.sql()).equals(text(target, "originalGoldSqlSha256")), "T3 original reuse identity differs");
    }

    private static void prepare(String key, Path output) throws Exception {
        requireNew(output);
        var dataset = references(key);
        String url = env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist");
        String user = env("TEXT2SQL_TEST_DB_USER", "text2sql_ro");
        require(url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):[0-9]{1,5}/olist")
                && user.equals("text2sql_ro"), "Gold preparation requires local olist / text2sql_ro");
        var preparedItems = JSON.createArrayNode();
        try (var connection = DriverManager.getConnection(url, user, env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro"))) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout='8s'");
                    statement.execute("SET LOCAL search_path=public,pg_catalog");
                    try (var rs = statement.executeQuery("SELECT current_user, current_setting('transaction_read_only'), "
                            + "rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                        require(rs.next() && rs.getString(1).equals("text2sql_ro") && rs.getString(2).equals("on"), "Database read-only identity mismatch");
                        for (int i = 3; i <= 7; i++) require(!rs.getBoolean(i), "Privileged Gold database role rejected");
                        require(!rs.next(), "Ambiguous database role identity");
                    }
                }
                var schemaReader = new DatabaseSchemaReader(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
                var schema = new SchemaContext(schemaReader.readTables(), List.of(), "", "");
                require(!schema.tables().isEmpty(), "Business schema unavailable");
                var properties = new AgentProperties();
                properties.getGuard().setLimitMode(AgentProperties.Guard.LimitMode.REJECT);
                var validator = new SqlValidator(properties);
                // Validate every frozen Gold before executing any Gold. A missing LIMIT is not a safety violation:
                // the exact frozen SQL is executed; JDBC bounds rows and a truncated reference fails the whole prepare.
                for (var reference : dataset.items()) {
                    var validation = validator.validate(reference.sql(), schema);
                    require(validation.violations().stream().allMatch(v -> v.code() == ValidationResult.Code.MISSING_LIMIT),
                            "Frozen Gold safety rejected: " + reference.id() + " " + validation.describe());
                }
                for (var reference : dataset.items()) {
                    var gold = executeGold(connection, reference);
                    var item = itemIdentity(reference);
                    item.set("gold", resultNode(gold));
                    preparedItems.add(item);
                }
            } finally {
                connection.rollback();
            }
        }
        var result = JSON.createObjectNode().put("format", "stage-d-prepared-public-reference-v1")
                .put("datasetKey", key).put("datasetSha256", dataset.sha256())
                .put("referenceScorerSha256", SCORER_SHA).put("referenceColumnSchemaSha256", SCHEMA_SHA)
                .put("originalTargetPreregistrationSha256", TARGET_SHA).put("providerCalls", 0);
        result.set("items", preparedItems);
        writeNew(output, result);
        System.out.println("PREPARED dataset=" + key + " items=" + preparedItems.size() + " providerCalls=0");
    }

    private static QueryResult executeGold(Connection connection, Reference reference) throws Exception {
        long started = System.nanoTime();
        var columns = new ArrayList<String>();
        var rows = new ArrayList<List<String>>();
        try (var statement = connection.prepareStatement(reference.sql())) {
            statement.setQueryTimeout(8);
            statement.setMaxRows(MAX_GOLD_ROWS + 1);
            try (var rs = statement.executeQuery()) {
                ResultSetMetaData metadata = rs.getMetaData();
                if (reference.schema() != null) {
                    var frozenColumns = array(reference.schema(), "columns");
                    require(metadata.getColumnCount() == frozenColumns.size(), "Gold output width drift: " + reference.id());
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        var frozen = frozenColumns.get(i - 1);
                        require(metadata.getColumnLabel(i).equals(text(frozen, "name"))
                                && metadata.getColumnType(i) == integer(frozen, "jdbcType")
                                && metadata.getColumnTypeName(i).equals(text(frozen, "postgresType")),
                                "Gold JDBC metadata differs from frozen reference: " + reference.id());
                    }
                }
                for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
                while (rs.next()) {
                    require(rows.size() < MAX_GOLD_ROWS, "Gold exceeds complete-reference row safety bound: " + reference.id());
                    var row = new ArrayList<String>();
                    for (int i = 1; i <= columns.size(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value == null ? null : String.valueOf(value));
                    }
                    rows.add(row);
                }
            }
        }
        var result = new QueryResult(columns, rows, false, (System.nanoTime() - started) / 1_000_000);
        if (reference.schema() != null) {
            require(ReferenceContractScorer.equivalent(result, result, policy(reference)), "Gold violates its own frozen policy");
        }
        return result;
    }

    private static void score(Path preparedPath, Path responsesPath, Path output) throws Exception {
        requireNew(output);
        byte[] preparedBytes = Files.readAllBytes(preparedPath);
        byte[] responseBytes = Files.readAllBytes(responsesPath);
        var prepared = read(preparedBytes);
        var dataset = references(text(prepared, "datasetKey"));
        require(text(prepared, "format").equals("stage-d-prepared-public-reference-v1")
                && text(prepared, "datasetSha256").equals(dataset.sha256())
                && text(prepared, "referenceScorerSha256").equals(SCORER_SHA)
                && text(prepared, "referenceColumnSchemaSha256").equals(SCHEMA_SHA)
                && text(prepared, "originalTargetPreregistrationSha256").equals(TARGET_SHA)
                && integer(prepared, "providerCalls") == 0, "Prepared provenance drift");
        var preparedItems = array(prepared, "items");
        var responses = read(responseBytes);
        var responseItems = array(responses, "items");
        require(preparedItems.size() == dataset.items().size() && responseItems.size() == dataset.items().size(),
                "Prepared/HTTP response denominator mismatch; no missing or extra IDs allowed");
        var golds = new ArrayList<QueryResult>();
        var seenPrepared = new HashSet<String>();
        var seenResponses = new HashSet<String>();
        // Validate all envelopes/identities before deriving any official score.
        for (int i = 0; i < dataset.items().size(); i++) {
            var reference = dataset.items().get(i);
            var p = preparedItems.get(i);
            var response = responseItems.get(i);
            require(reference.id().equals(text(p, "id")) && seenPrepared.add(text(p, "id")), "Prepared ID duplicate/order drift");
            require(reference.id().equals(text(response, "id")) && seenResponses.add(text(response, "id")), "HTTP ID unknown/duplicate/order drift");
            for (var field : itemIdentity(reference).properties()) {
                require(field.getValue().equals(p.get(field.getKey())), "Prepared item identity/policy drift: " + reference.id() + ":" + field.getKey());
            }
            var gold = parseResult(object(p, "gold"), true);
            require(!gold.truncated(), "Truncated Gold reference");
            require(gold.rows().stream().allMatch(r -> r.size() == gold.columns().size()), "Malformed Gold row width");
            if (reference.schema() != null) {
                var frozen = array(reference.schema(), "columns");
                require(gold.columns().size() == frozen.size(), "Prepared Gold column count drift");
                for (int c = 0; c < frozen.size(); c++) require(gold.columns().get(c).equals(text(frozen.get(c), "name")), "Prepared Gold column-name drift");
                require(ReferenceContractScorer.equivalent(gold, gold, policy(reference)), "Prepared Gold violates frozen policy");
            }
            int http = integer(response, "httpStatus");
            require(http >= 0 && http <= 599, "Invalid HTTP status (0 is saved transport failure)");
            if (http == 200) {
                var body = object(response, "body");
                text(body, "status");
                if (text(body, "status").equals("SUCCESS")) parseResult(body, false);
            }
            golds.add(gold);
        }
        int correct = 0;
        var outcomes = JSON.createArrayNode();
        var layers = new LinkedHashMap<String, int[]>();
        for (int i = 0; i < dataset.items().size(); i++) {
            var reference = dataset.items().get(i);
            var response = responseItems.get(i);
            String reason;
            QueryResult actual = null;
            if (integer(response, "httpStatus") != 200) reason = "HTTP_NOT_200";
            else if (!text(response.get("body"), "status").equals("SUCCESS")) reason = "STATUS_NOT_SUCCESS";
            else {
                actual = parseResult(response.get("body"), false);
                reason = actual.truncated() ? "TRUNCATED" : null;
            }
            boolean match = reason == null && (dataset.key().equals("targets")
                    ? ReferenceContractScorer.originalTargetEquivalent(golds.get(i), actual)
                    : ReferenceContractScorer.equivalent(golds.get(i), actual, policy(reference)));
            if (reason == null) reason = match ? "MATCH" : "RESULT_CONTRACT_MISMATCH";
            var outcome = JSON.createObjectNode().put("id", reference.id()).put("correct", match).put("reason", reason);
            if (dataset.key().equals("dev") && reference.id().equals("T3-002")) {
                requireOriginalReuse(reference, dataset.originals().get("T3-002"));
                boolean original = actual != null && !actual.truncated()
                        && ReferenceContractScorer.originalTargetEquivalent(golds.get(i), actual);
                outcome.put("originalTargetCorrect", original).put("originalTargetReason", original ? "MATCH" : reason.equals("MATCH") ? "ORIGINAL_STRICT_MISMATCH" : reason)
                        .put("originalTargetQuestionSha256", sha(reference.question())).put("originalTargetGoldSqlSha256", sha(reference.sql()));
            }
            if (match) correct++;
            var layer = layers.computeIfAbsent(reference.difficulty(), ignored -> new int[2]);
            layer[0]++; if (match) layer[1]++;
            outcomes.add(outcome);
        }
        var byLayer = JSON.createObjectNode();
        layers.forEach((name, values) -> byLayer.set(name, JSON.createObjectNode().put("total", values[0]).put("correct", values[1])));
        var result = JSON.createObjectNode().put("format", "stage-d-http-result-score-v1")
                .put("datasetKey", dataset.key()).put("datasetSha256", dataset.sha256())
                .put("preparedSha256", sha(preparedBytes)).put("responsesSha256", sha(responseBytes))
                .put("referenceScorerSha256", SCORER_SHA).put("total", dataset.items().size()).put("correct", correct)
                .put("providerCalls", 0);
        result.set("items", outcomes); result.set("byLayer", byLayer);
        writeNew(output, result);
        System.out.println("SCORED dataset=" + dataset.key() + " correct=" + correct + "/" + dataset.items().size() + " providerCalls=0");
    }

    private static ObjectNode itemIdentity(Reference reference) throws Exception {
        var item = JSON.createObjectNode().put("id", reference.id()).put("difficulty", reference.difficulty())
                .put("question", reference.question()).put("questionSha256", sha(reference.question()))
                .put("goldSqlSha256", sha(reference.sql())).put("expectedTableCount", reference.expectedTableCount());
        item.set("policy", reference.policy());
        return item;
    }

    private static QueryResult parseResult(JsonNode node, boolean gold) {
        var columns = new ArrayList<String>();
        for (var column : array(node, "columns")) {
            require(column.isTextual(), "Column labels must be strings"); columns.add(column.asText());
        }
        require(!columns.isEmpty(), "Result columns must be explicit and nonempty");
        var rows = new ArrayList<List<String>>();
        for (var raw : array(node, "rows")) {
            require(raw.isArray(), "Every result row must be an array");
            var row = new ArrayList<String>();
            for (var cell : raw) {
                require(cell.isTextual() || cell.isNull(), "Result cells must preserve HTTP string/null values");
                row.add(cell.isNull() ? null : cell.asText());
            }
            rows.add(row);
        }
        require(node.has("truncated") && node.get("truncated").isBoolean(), "Explicit boolean truncated is mandatory");
        long executionMs = 0;
        if (gold) {
            require(node.has("executionMs") && node.get("executionMs").isIntegralNumber()
                    && node.get("executionMs").canConvertToLong() && node.get("executionMs").asLong() >= 0, "Invalid Gold executionMs");
            executionMs = node.get("executionMs").asLong();
        }
        return new QueryResult(columns, rows, node.get("truncated").asBoolean(), executionMs);
    }

    private static ObjectNode resultNode(QueryResult result) {
        var node = JSON.createObjectNode().put("truncated", result.truncated()).put("executionMs", result.elapsedMs());
        node.set("columns", JSON.valueToTree(result.columns())); node.set("rows", JSON.valueToTree(result.rows()));
        return node;
    }
    private static ReferenceContractScorer.Policy policy(Reference reference) throws Exception {
        return JSON.treeToValue(reference.policy(), ReferenceContractScorer.Policy.class);
    }
    private static int tableCount(String sql) {
        var tables = TableRecall.expectedTables(sql);
        require(!tables.isEmpty(), "Frozen Gold physical table extraction failed");
        return tables.size();
    }
    private static JsonNode read(Path path) throws Exception {
        return read(Files.readAllBytes(path));
    }
    private static JsonNode read(byte[] bytes) throws Exception {
        var node = JSON.readTree(bytes);
        require(node != null && node.isObject(), "JSON root must be an object");
        return node;
    }
    private static ArrayNode array(JsonNode node, String key) {
        require(node != null && node.isObject() && node.has(key) && node.get(key).isArray(), "Missing/invalid array: " + key);
        return (ArrayNode) node.get(key);
    }
    private static ObjectNode object(JsonNode node, String key) {
        require(node != null && node.isObject() && node.has(key) && node.get(key).isObject(), "Missing/invalid object: " + key);
        return (ObjectNode) node.get(key);
    }
    private static String text(JsonNode node, String key) {
        require(node != null && node.isObject() && node.has(key) && node.get(key).isTextual() && !node.get(key).asText().isEmpty(), "Missing/invalid string: " + key);
        return node.get(key).asText();
    }
    private static int integer(JsonNode node, String key) {
        require(node != null && node.isObject() && node.has(key) && node.get(key).isIntegralNumber()
                && node.get(key).canConvertToInt(), "Missing/invalid integer: " + key);
        return node.get(key).asInt();
    }
    private static Set<String> fieldNames(JsonNode node) {
        var fields = new HashSet<String>(); node.fieldNames().forEachRemaining(fields::add); return fields;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value;
    }
    private static String sha(String value) throws Exception { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private static void requireSha(Path path, String expected) throws Exception {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path) && sha(Files.readAllBytes(path)).equals(expected), "Frozen file drift: " + path);
    }
    private static void requireNew(Path path) {
        require(!Files.exists(path), "Refuse to overwrite evidence: " + path);
    }
    private static void writeNew(Path path, JsonNode value) throws Exception {
        if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
        try (var stream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            JSON.writerWithDefaultPrettyPrinter().writeValue(stream, value);
        }
    }
}
