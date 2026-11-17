package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DriverManager;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Relation records and per-group people have independent Java set/count oracles. No model or writes. */
@EnabledIf("databaseAvailable")
class CustomerPopulationDatabaseTest {
    record Customer(String row, String person, String state) { }
    record Scenario(String name, List<Customer> customers) {
        @Override public String toString() { return name; }
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("same-person-repeated-records", List.of(c("c1", "p1", "SP"), c("c2", "p1", "SP"), c("c3", "p2", "SP"))),
                new Scenario("record-and-person-rankings-reverse", List.of(c("c1", "p1", "SP"), c("c2", "p1", "SP"),
                        c("c3", "p1", "SP"), c("c4", "p2", "RJ"), c("c5", "p3", "RJ"))),
                new Scenario("same-person-in-multiple-states", List.of(c("c1", "p1", "SP"), c("c2", "p1", "RJ"), c("c3", "p2", "RJ"))),
                new Scenario("unknown-identities-still-have-records", List.of(c("c1", null, "SP"), c("c2", null, "SP"), c("c3", "p1", "RJ"))),
                new Scenario("unknown-state-is-a-real-group-not-a-person", List.of(c("c1", "p1", null), c("c2", "p1", null), c("c3", "p2", "SP"))),
                new Scenario("all-identities-null", List.of(c("c1", null, "SP"), c("c2", null, "RJ"))),
                new Scenario("no-customers", List.of()),
                new Scenario("singleton", List.of(c("c1", "p1", "SP"))));
    }

    @ParameterizedTest @MethodSource("scenarios")
    void recordsPeopleGeographyAndOneToManyJoinRemainSeparate(Scenario scenario) throws Exception {
        var policy = DimensionPolicyTest.registry().entityPolicies().getFirst();
        assertThat(policy.populationCountUnit()).isEqualTo(EntityPolicy.PopulationCountUnit.ROW_KEY);
        var recordCounts = new TreeMap<String, Long>();
        var personSets = new TreeMap<String, Set<String>>();
        var allPeople = new HashSet<String>();
        for (var customer : scenario.customers()) {
            String state = state(customer.state());
            recordCounts.merge(state, 1L, Long::sum);
            personSets.computeIfAbsent(state, ignored -> new HashSet<>());
            if (customer.person() != null) { personSets.get(state).add(customer.person()); allPeople.add(customer.person()); }
        }
        var personCounts = new TreeMap<String, Long>();
        personSets.forEach((state, people) -> personCounts.put(state, (long) people.size()));
        String prefix = cte(scenario);
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            // This particular reference explicitly preserves all customer records, including zero children.
            String grouped = "SELECT c.customer_state,";
            String from = " FROM customers c GROUP BY 1";
            assertThat(query(connection, prefix + grouped + "COUNT(*)" + from)).isEqualTo(recordCounts);
            assertThat(query(connection, prefix + grouped + "COUNT(DISTINCT c." + policy.identityColumn() + ")" + from)).isEqualTo(personCounts);
            assertThat(query(connection, prefix + grouped + "COUNT(DISTINCT c." + policy.rowKey() + ") FROM customers c "
                    + "LEFT JOIN (VALUES ('x'),('y')) child(id) ON c.customer_id <> 'c1' GROUP BY 1")).isEqualTo(recordCounts);
            assertThat(scalar(connection, prefix + "SELECT COUNT(*) FROM customers")).isEqualTo(scenario.customers().size());
            assertThat(scalar(connection, prefix + "SELECT COUNT(DISTINCT " + policy.identityColumn() + ") FROM customers")).isEqualTo(allPeople.size());
        }
        if (scenario.name().contains("rankings-reverse")) {
            assertThat(recordCounts.get("SP")).isGreaterThan(recordCounts.get("RJ"));
            assertThat(personCounts.get("SP")).isLessThan(personCounts.get("RJ"));
        }
        if (scenario.name().contains("multiple-states")) {
            assertThat(personCounts.values().stream().mapToLong(Long::longValue).sum()).isGreaterThan(allPeople.size());
        }
    }

    private static Map<String, Long> query(java.sql.Connection connection, String sql) throws Exception {
        var result = new TreeMap<String, Long>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(sql)) { while (rows.next()) result.put(state(rows.getString(1)), rows.getLong(2)); }
        }
        return result;
    }
    private static long scalar(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
        }
    }
    private static String cte(Scenario scenario) {
        var rows = scenario.customers().stream().map(c -> "(" + literal(c.row()) + "," + literal(c.person()) + "," + literal(c.state()) + ")").toList();
        return "WITH customers(customer_id,customer_unique_id,customer_state) AS ("
                + (rows.isEmpty() ? "SELECT NULL::text,NULL::text,NULL::text WHERE FALSE" : "VALUES " + String.join(",", rows)) + ") ";
    }
    private static Customer c(String row, String person, String state) { return new Customer(row, person, state); }
    private static String state(String value) { return value == null ? "<SQL_NULL>" : value; }
    private static String literal(String value) { return value == null ? "NULL::text" : "'" + value.replace("'", "''") + "'::text"; }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
