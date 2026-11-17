package com.text2sql.agent.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Freeze four references before one paid run; evidence mode is entirely offline. */
public final class DeliveryScopeHttpProbe {
    record Match(String metric, String alias, String requiredContextTerm, String excludedContextTerm, String preferredContextTerm,
                 MetricRegistry.MatchDecision decision) { }
    record Item(String id, List<Match> matches) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("freeze/run/evidence/audit NEW_PATH");
        Path path = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("run")) { CustomerIdentityHttpProbe.runFrozen(path); return; }
        if (args[0].equals("audit")) { audit(path); return; }
        var loader = new EvalItemLoader(new AgentProperties());
        var inputs = loader.load("data/eval", List.of(), 0);
        if (args[0].equals("evidence")) {
            var registry = new MetricRegistry(); registry.load();
            var evidence = inputs.stream().map(i -> new Item(i.id(), registry.matchEvidence(i.question()).stream()
                    .map(e -> new Match(e.metric().name(), e.alias(), e.requiredContextTerm(), e.excludedContextTerm(), e.preferredContextTerm(), e.decision())).toList())).toList();
            Files.writeString(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "datasetSha256", loader.datasetSha256("data/eval"), "metricDefinitionsSha256", MetricRegistry.definitionSha256(),
                    "items", evidence, "providerCalls", 0)) + "\n", StandardOpenOption.CREATE_NEW);
            return;
        }
        if (!args[0].equals("freeze")) throw new IllegalArgumentException("freeze/run/evidence");
        var items = inputs.stream().collect(Collectors.toMap(i -> i.id(), Function.identity()));
        var carrier = items.get("T4-026"); var states = items.get("T3-018"); var counts = items.get("T2-028");
        CustomerIdentityHttpProbe.freezeReferences(path, List.of(
                List.of("original-carrier-clock", carrier.question(), carrier.goldSql()),
                List.of("original-order-clock-control", states.question(), states.goldSql()),
                List.of("explicit-order-clock-by-carrier", carrier.question() + "明确使用订单下单到签收的时长，每条运输记录计一次。",
                        "SELECT s.carrier,ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date-o.order_purchase_timestamp))/86400.0),2) "
                                + "FROM shipments s JOIN orders o ON o.order_id=s.order_id WHERE o.order_delivered_customer_date IS NOT NULL GROUP BY s.carrier"),
                List.of("original-carrier-count-control", counts.question(), counts.goldSql())),
                "delivery-clock-scope-http-v2", "Exactly four requests once; numeric matches and three separate AVG clock provenance checks; no latency or blind-test claim.",
                Map.of("clockRequirements", Map.of(
                        "original-carrier-clock", new DeliveryClockSqlAudit.Requirement("shipments.delivered_at", "shipments.shipped_at"),
                        "original-order-clock-control", new DeliveryClockSqlAudit.Requirement("orders.order_delivered_customer_date", "orders.order_purchase_timestamp"),
                        "explicit-order-clock-by-carrier", new DeliveryClockSqlAudit.Requirement("orders.order_delivered_customer_date", "orders.order_purchase_timestamp")),
                        "clockAuditScope", "Direct base-table SELECT with exactly one projected AVG containing EPOCH(end-start); aliases resolved to sources; unsupported is not a pass. Numeric comparison separately checks values; no general SQL equivalence or full grain proof."));
    }

    private static void audit(Path directory) throws Exception {
        var json = new ObjectMapper();
        var protocol = json.readTree(directory.resolve("protocol.json").toFile());
        if (!"delivery-clock-scope-http-v2".equals(protocol.path("format").asText()))
            throw new IllegalArgumentException("A v2 protocol with preregistered clock requirements is required");
        var results = new java.util.LinkedHashMap<String, DeliveryClockSqlAudit.Result>();
        for (var entry : protocol.path("clockRequirements").properties()) {
            var response = json.readTree(directory.resolve("responses").resolve(entry.getKey() + ".json").toFile());
            var requirement = json.treeToValue(entry.getValue(), DeliveryClockSqlAudit.Requirement.class);
            results.put(entry.getKey(), DeliveryClockSqlAudit.inspect(response.path("body").path("sql").asText(), requirement));
        }
        long passed = results.values().stream().filter(r -> r.status().equals("PASS")).count();
        Files.writeString(directory.resolve("clock-audit.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("clockCases", results.size(), "passed", passed, "results", results, "providerCalls", 0)) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("CLOCK_AUDIT " + passed + "/" + results.size());
        if (results.size() != 3 || passed != results.size()) throw new IllegalStateException("Clock provenance failure retained");
    }
}
