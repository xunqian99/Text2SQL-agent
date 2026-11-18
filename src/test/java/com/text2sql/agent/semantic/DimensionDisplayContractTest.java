package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DriverManager;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Name-only projection must preserve entity identity, missing lookups and fact coverage. */
@EnabledIf("databaseAvailable")
class DimensionDisplayContractTest {
    record Label(String id, String name) { }
    record Fact(String id, String entity, long amount) { }
    record Scenario(String name, List<Label> labels, List<Fact> facts) {
        @Override public String toString() { return name; }
    }
    static Stream<Scenario> scenarios() {
        var labels = List.of(new Label("a", "same"), new Label("b", "same"));
        return Stream.of(
                new Scenario("equal-names-still-denote-two-entities", labels,
                        List.of(new Fact("f1", "a", 100), new Fact("f2", "b", 200))),
                new Scenario("missing-name-lookup-must-not-drop-facts", List.of(new Label("a", "known")),
                        List.of(new Fact("f1", "a", 100), new Fact("f2", "b", 200))),
                new Scenario("null-name-does-not-merge-different-entities", List.of(new Label("a", null), new Label("b", null)),
                        List.of(new Fact("f1", "a", 100), new Fact("f2", "b", 200))),
                new Scenario("one-entity-many-facts-is-one-group", labels,
                        List.of(new Fact("f1", "a", 100), new Fact("f2", "a", 100), new Fact("f3", "b", 200))),
                new Scenario("empty-facts-do-not-create-label-only-groups", labels, List.of()));
    }

    @ParameterizedTest @MethodSource("scenarios")
    void labelsChangeProjectionWithoutChangingEntityPopulation(Scenario scenario) throws Exception {
        String cte = "WITH labels(entity_id,entity_name) AS (" + values(scenario.labels().stream()
                .map(l -> "(" + lit(l.id()) + "," + lit(l.name()) + ")").toList(), "NULL::text,NULL::text")
                + "), facts(fact_id,entity_id,amount) AS (" + values(scenario.facts().stream()
                .map(f -> "(" + lit(f.id()) + "," + lit(f.entity()) + "," + f.amount() + "::bigint)").toList(),
                "NULL::text,NULL::text,NULL::bigint") + ") ";
        var labels = new HashMap<String, String>();
        scenario.labels().forEach(l -> labels.put(l.id(), l.name()));
        var sums = new HashMap<String, Long>();
        scenario.facts().forEach(f -> sums.merge(f.entity(), f.amount(), Long::sum));
        var expected = sums.entrySet().stream().map(e -> row(labels.get(e.getKey()), e.getValue())).sorted().toList();
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            var rows = new ArrayList<String>();
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery(cte + "SELECT l.entity_name,SUM(f.amount) "
                        + "FROM facts f LEFT JOIN labels l ON l.entity_id=f.entity_id GROUP BY f.entity_id,l.entity_name")) {
                    assertThat(result.getMetaData().getColumnCount()).isEqualTo(2); // No added ID output.
                    while (result.next()) rows.add(row(result.getString(1), result.getLong(2)));
                }
            }
            assertThat(rows.stream().sorted().toList()).isEqualTo(expected);
            assertThat(rows).hasSize(sums.size());
            if (scenario.name().startsWith("equal-names")) {
                assertThat(rows).containsExactlyInAnyOrder(row("same", 100), row("same", 200));
            }
        }
    }
    private static String row(String label, long amount) { return (label == null ? "<SQL_NULL>" : label) + ":" + amount; }
    private static String values(List<String> rows, String empty) {
        return rows.isEmpty() ? "SELECT " + empty + " WHERE FALSE" : "VALUES " + String.join(",", rows);
    }
    private static String lit(String s) { return s == null ? "NULL::text" : "'" + s.replace("'", "''") + "'::text"; }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
