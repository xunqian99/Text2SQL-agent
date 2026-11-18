package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only counterexamples: entity identity does not choose a fact's attribute-bearing record. */
@EnabledIf("databaseAvailable")
class FactAttributeSourceContractTest {
    record Customer(String row, String person, String state) { }
    record Fact(String id, String customer, String memberPerson, long amount) { }
    record Scenario(String name, List<Customer> customers, List<Fact> facts, boolean reverseMustDiffer) {
        @Override public String toString() { return name; }
    }

    static Stream<Scenario> scenarios() {
        var twoStates = List.of(new Customer("c1", "p1", "SP"), new Customer("c2", "p1", "RJ"));
        return Stream.of(
                new Scenario("one-person-two-states-does-not-duplicate-fact-across-states", twoStates,
                        List.of(new Fact("f1", "c1", "p1", 100)), true),
                new Scenario("two-facts-use-their-own-associated-customer-record", twoStates,
                        List.of(new Fact("f1", "c1", "p1", 100), new Fact("f2", "c2", "p1", 200)), true),
                new Scenario("same-state-distinct-count-still-cannot-repair-sum", List.of(
                        new Customer("c1", "p1", "SP"), new Customer("c2", "p1", "SP")),
                        List.of(new Fact("f1", "c1", "p1", 100)), true),
                new Scenario("member-and-fact-customer-need-not-denote-same-person", List.of(
                        new Customer("c1", "p1", "SP"), new Customer("c2", "p2", "RJ")),
                        List.of(new Fact("f1", "c1", "p2", 100)), true),
                new Scenario("known-fact-record-survives-null-natural-person", List.of(new Customer("c1", null, "SP")),
                        List.of(new Fact("f1", "c1", null, 100)), true),
                new Scenario("unknown-source-state-does-not-inherit-other-record-state", List.of(
                        new Customer("c1", "p1", null), new Customer("c2", "p1", "SP")),
                        List.of(new Fact("f1", "c1", "p1", 100)), true),
                new Scenario("orphan-source-is-not-recovered-through-member-identity", twoStates,
                        List.of(new Fact("f1", "missing", "p1", 100)), true),
                new Scenario("empty-facts-do-not-create-groups", twoStates, List.of(), false));
    }

    @ParameterizedTest @MethodSource("scenarios")
    void sourceRecordAttributesMatchIndependentOracleAndIdentityShortcutFails(Scenario scenario) throws Exception {
        var policy = DimensionPolicyTest.registry().entityPolicies().getFirst();
        // The policy describes the choice; it does not rewrite or enforce the model's SQL.
        assertThat(policy.render()).contains("沿该事实的关联键定位customers.customer_id对应记录");
        String cte = "WITH customers(customer_id,customer_unique_id,customer_state) AS ("
                + values(scenario.customers().stream().map(c -> "(" + lit(c.row()) + "," + lit(c.person()) + "," + lit(c.state()) + ")").toList(),
                "NULL::text,NULL::text,NULL::text")
                + "), facts(fact_id,customer_id,member_person,amount) AS ("
                + values(scenario.facts().stream().map(f -> "(" + lit(f.id()) + "," + lit(f.customer()) + "," + lit(f.memberPerson()) + "," + f.amount() + "::bigint)").toList(),
                "NULL::text,NULL::text,NULL::text,NULL::bigint") + ") ";
        String aggregation = "SELECT c.customer_state,COUNT(DISTINCT f.fact_id),SUM(f.amount) FROM facts f JOIN customers c ON ";
        var customers = new HashMap<String, Customer>();
        scenario.customers().forEach(c -> customers.put(c.row(), c));
        var ids = new TreeMap<String, Set<String>>();
        var sums = new TreeMap<String, Long>();
        for (var f : scenario.facts()) {
            var c = customers.get(f.customer());
            if (c == null) continue; // Both SQL references use INNER JOIN; no new coverage default.
            String state = key(c.state());
            ids.computeIfAbsent(state, ignored -> new HashSet<>()).add(f.id());
            sums.merge(state, f.amount(), Long::sum);
        }
        var expected = new TreeMap<String, List<Long>>();
        ids.forEach((state, facts) -> expected.put(state, List.of((long) facts.size(), sums.get(state))));
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            var actual = query(connection, cte + aggregation + "c." + policy.rowKey() + "=f.customer_id GROUP BY 1");
            var reverse = query(connection, cte + aggregation + "c." + policy.identityColumn() + "=f.member_person GROUP BY 1");
            assertThat(actual).isEqualTo(expected);
            if (scenario.reverseMustDiffer()) assertThat(reverse).isNotEqualTo(expected);
            else assertThat(reverse).isEqualTo(expected);
            if (scenario.name().startsWith("same-state")) {
                assertThat(actual.get("SP")).containsExactly(1L, 100L);
                assertThat(reverse.get("SP")).containsExactly(1L, 200L);
            }
        }
    }

    private static Map<String, List<Long>> query(Connection connection, String sql) throws Exception {
        var result = new TreeMap<String, List<Long>>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(sql)) {
                while (rows.next()) result.put(key(rows.getString(1)), List.of(rows.getLong(2), rows.getLong(3)));
            }
        }
        return result;
    }
    private static String values(List<String> rows, String empty) {
        return rows.isEmpty() ? "SELECT " + empty + " WHERE FALSE" : "VALUES " + String.join(",", rows);
    }
    private static String lit(String s) { return s == null ? "NULL::text" : "'" + s.replace("'", "''") + "'::text"; }
    private static String key(String s) { return s == null ? "<SQL_NULL>" : s; }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
