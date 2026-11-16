package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.fewshot.Example;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Frozen-suite integrity and exact-overlap checks, never an assertion of semantic novelty or blind review. */
public final class EvalSuiteGuard {
    private EvalSuiteGuard() { }

    public static Map<String, Object> verify(AgentProperties properties, EvalItemLoader loader,
                                            List<EvalItem> items, List<Example> examples) {
        var eval = properties.getEval();
        if (eval.getSuiteManifest() == null || eval.getSuiteManifest().isBlank()) return Map.of();
        require("all".equalsIgnoreCase(eval.getSplit()) && eval.getLimit() == 0
                && eval.getLayers().isEmpty() && eval.getRepeat() == 1,
                "Frozen suite requires split=all, limit=0, no layer filter and repeat=1");
        require(!eval.isSelfCorrectionEnabled(), "Gold-aware evaluator retry is prohibited for frozen suites");
        try {
            byte[] bytes = Files.readAllBytes(Path.of(eval.getSuiteManifest()));
            JsonNode seal = new ObjectMapper().readTree(bytes);
            require("frozen-eval-suite-v1".equals(seal.path("format").asText()), "Unknown suite format");
            for (String field : List.of("suiteId", "purpose", "frozenAt", "datasetSha256", "sampleIdsSha256")) {
                require(seal.path(field).isTextual() && !seal.path(field).asText().isBlank(), "Missing " + field);
            }
            require(!items.isEmpty() && items.size() == seal.path("itemCount").asInt(), "Suite count changed");
            require(loader.datasetSha256(eval.getDir()).equals(seal.path("datasetSha256").asText()),
                    "Suite YAML fingerprint changed");
            require(EvalItemLoader.sampleIdsSha256(items).equals(seal.path("sampleIdsSha256").asText()),
                    "Suite sample IDs changed");
            require(items.stream().map(EvalItem::id).distinct().count() == items.size(), "Duplicate suite ID");
            Set<String> questions = new HashSet<>();
            for (EvalItem item : items) require(questions.add(normalize(item.question())), "Duplicate suite question");
            Map<String, Long> layers = items.stream().collect(Collectors.groupingBy(EvalItem::difficulty, Collectors.counting()));
            var quotas = seal.path("layerCounts");
            require(quotas.isObject() && quotas.size() == layers.size(), "Layer quota set changed");
            for (var entry : layers.entrySet()) {
                require(quotas.path(entry.getKey()).asLong(-1) == entry.getValue(), "Layer quota changed: " + entry.getKey());
            }
            String referenceDir = seal.path("referenceDir").asText();
            require(!referenceDir.isBlank(), "Missing reference dataset");
            require(loader.datasetSha256(referenceDir).equals(seal.path("referenceDatasetSha256").asText()),
                    "Reference dataset changed");
            List<EvalItem> reference = loader.load(referenceDir, List.of(), 0);
            Set<String> ids = items.stream().map(EvalItem::id).collect(Collectors.toSet());
            for (EvalItem item : reference) {
                require(!ids.contains(item.id()) && !questions.contains(normalize(item.question())),
                        "Suite overlaps reference IDs/questions: " + item.id());
            }
            require(!examples.isEmpty(), "Few-shot library is unavailable; cannot certify overlap check");
            for (Example example : examples) {
                require(!ids.contains(example.id()) && !questions.contains(normalize(example.question())),
                        "Suite overlaps actual loaded few-shot: " + example.id());
            }
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("suiteId", seal.path("suiteId").asText());
            identity.put("purpose", seal.path("purpose").asText());
            identity.put("frozenAt", seal.path("frozenAt").asText());
            identity.put("manifestSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            // This is declared provenance, not an independently verified approval or access-control guarantee.
            identity.put("declaredIndependentReview", seal.path("independentReview").asBoolean(false));
            identity.put("exactOverlapCheckPassed", true);
            return Map.copyOf(identity);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read frozen evaluation manifest", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String normalize(String question) {
        return Normalizer.normalize(question, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{P}]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
