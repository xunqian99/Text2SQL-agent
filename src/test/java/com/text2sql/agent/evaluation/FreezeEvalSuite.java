package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.fewshot.ExampleLoader;
import com.text2sql.agent.generation.PromptFingerprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Freeze an AI-authored candidate only after a no-model Gold preflight; never certifies independent review. */
public final class FreezeEvalSuite {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected suiteDir referenceDir GoldReport auditOutput");
        Path suite = Path.of(args[0]);
        Path sealPath = suite.resolve("manifest.json");
        if (Files.exists(sealPath) || Files.exists(Path.of(args[3]))) throw new IllegalStateException("Never overwrite frozen evidence");
        var properties = new AgentProperties();
        properties.getEval().setDir(args[0]);
        var loader = new EvalItemLoader(properties);
        var items = loader.load();
        var json = new ObjectMapper();
        // Git stores JSON with LF. Hash the same bytes that survive a fresh checkout on any OS.
        var writer = json.writer(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));
        byte[] goldBytes = Files.readAllBytes(Path.of(args[2]));
        var gold = json.readTree(goldBytes);
        String datasetHash = loader.datasetSha256(args[0]);
        if (!gold.path("meta").path("dryRun").asBoolean() || items.size() != 100
                || gold.path("overall").path("total").asInt() != items.size()
                || gold.path("overall").path("correct").asInt() != items.size()
                || !datasetHash.equals(gold.path("meta").path("datasetSha256").asText())
                || gold.path("itemEvidence").size() != items.size()) throw new IllegalStateException("Gold preflight is incomplete or stale");
        for (var evidence : gold.path("itemEvidence")) {
            if (!evidence.path("calls").isArray() || !evidence.path("calls").isEmpty()) {
                throw new IllegalStateException("Freeze accepts no-model preflight only");
            }
            var item = items.stream().filter(i -> i.id().equals(evidence.path("id").asText())).findFirst().orElseThrow();
            if (!evidence.path("questionSha256").asText().equals(PromptFingerprint.textSha256(item.question()))
                    || !evidence.path("goldSqlSha256").asText().equals(PromptFingerprint.textSha256(item.goldSql()))) {
                throw new IllegalStateException("Gold preflight item fingerprint changed");
            }
        }
        var quotas = Map.of("single_table_basic",20,"single_table_agg",20,"time_window",16,
                "multi_join",22,"multi_join_deep",10,"business_semantics",12);
        Map<String,Object> seal = new LinkedHashMap<>();
        seal.put("format","frozen-eval-suite-v1");
        seal.put("suiteId","olist-heldout-candidate-20261116-v1");
        seal.put("purpose","AI-authored frozen heldout candidate; not independently reviewed blind test");
        seal.put("independentReview",false);
        seal.put("frozenAt",Instant.now().toString());
        seal.put("itemCount",items.size());
        seal.put("layerCounts",quotas);
        seal.put("datasetSha256",datasetHash);
        seal.put("sampleIdsSha256",EvalItemLoader.sampleIdsSha256(items));
        seal.put("referenceDir",args[1]);
        seal.put("referenceDatasetSha256",loader.datasetSha256(args[1]));
        seal.put("authoringGoldReportSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(goldBytes)));
        seal.put("accessPolicy","Excluded from application resources/few-shot; no tuning or post-score edits under v1. Source files are not encrypted or OS-isolated.");
        seal.put("reusePolicy","After inspecting failures, v1 is a regression set. Independent reviewer or a new uninspected suite is required for a fresh blind claim.");
        var examples = new ExampleLoader(); examples.init();
        Path temporary = Files.createTempFile(suite, "seal-", ".tmp");
        try {
            writer.writeValue(temporary.toFile(),seal);
            properties.getEval().setSuiteManifest(temporary.toString());
            var identity = EvalSuiteGuard.verify(properties,loader,items,examples.getExamples());
            var reference = loader.load(args[1],List.of(),0);
            Map<String,Object> audit = new LinkedHashMap<>();
            audit.put("format","frozen-suite-authoring-audit-v1");
            audit.put("suiteId",seal.get("suiteId"));
            audit.put("itemCount",items.size());
            audit.put("layerCounts",quotas);
            audit.put("datasetSha256",datasetHash);
            audit.put("referenceItemCount",reference.size());
            audit.put("fewshotItemCount",examples.getExamples().size());
            audit.put("exactQuestionOrIdOverlap",0);
            audit.put("exactSqlTextOverlaps",items.stream().filter(i -> reference.stream().anyMatch(r -> sqlText(i.goldSql()).equals(sqlText(r.goldSql())))).map(EvalItem::id).toList());
            audit.put("byGoldTableCount",items.stream().collect(Collectors.groupingBy(i -> TableRecall.expectedTables(i.goldSql()).size(),Collectors.counting())));
            audit.put("guardIdentity",identity);
            audit.put("independentReview",false);
            audit.put("limits",List.of("Exact text checks do not establish semantic independence or cross-domain generalization.",
                    "The author has seen the old benchmark and existing system; no third-party Gold review was performed.",
                    "Gold executable on one database is not a proof that question and SQL are semantically equivalent."));
            Path auditPath = Path.of(args[3]);
            Files.createDirectories(auditPath.toAbsolutePath().getParent());
            writer.writeValue(auditPath.toFile(),audit);
            Files.move(temporary,sealPath,StandardCopyOption.ATOMIC_MOVE);
            System.out.println("Frozen 100 candidate questions; exact ID/question overlaps 0; independentReview=false");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sqlText(String sql) {
        return sql.strip().replaceAll("\\s+"," ").toLowerCase(java.util.Locale.ROOT);
    }
}
