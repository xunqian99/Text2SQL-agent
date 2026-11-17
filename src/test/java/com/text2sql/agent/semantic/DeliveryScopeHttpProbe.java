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
    record Match(String metric, String alias, String requiredContextTerm, String excludedContextTerm,
                 MetricRegistry.MatchDecision decision) { }
    record Item(String id, List<Match> matches) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("freeze/run/evidence NEW_PATH");
        Path path = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("run")) { CustomerIdentityHttpProbe.runFrozen(path); return; }
        var loader = new EvalItemLoader(new AgentProperties());
        var inputs = loader.load("data/eval", List.of(), 0);
        if (args[0].equals("evidence")) {
            var registry = new MetricRegistry(); registry.load();
            var evidence = inputs.stream().map(i -> new Item(i.id(), registry.matchEvidence(i.question()).stream()
                    .map(e -> new Match(e.metric().name(), e.alias(), e.requiredContextTerm(), e.excludedContextTerm(), e.decision())).toList())).toList();
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
                "delivery-clock-scope-http-v1", "Exactly four requests once; carrier clock, order clock, explicit override and carrier count; no latency or blind-test claim.");
    }
}
