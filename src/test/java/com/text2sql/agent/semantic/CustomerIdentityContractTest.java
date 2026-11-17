package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DriverManager;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only PostgreSQL counterexamples and an independent Java identity/order-set oracle. */
@EnabledIf("databaseAvailable")
class CustomerIdentityContractTest {
    record Customer(String row, String person) { }
    record Order(String id, String customer) { }
    record Item(String order, String id) { }
    record Scenario(String name, List<Customer> customers, List<Order> orders, List<Item> items) {
        @Override public String toString() { return name; }
    }
    static final List<Customer> CUSTOMERS = List.of(new Customer("c1", "p1"), new Customer("c2", "p1"),
            new Customer("c3", "p2"), new Customer("c4", null), new Customer("c5", "p3"));
    static final List<Order> ORDERS = List.of(new Order("o1", "c1"), new Order("o2", "c2"), new Order("o3", "c3"));

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("same-person-multiple-row-keys", CUSTOMERS, ORDERS, List.of()),
                new Scenario("one-to-many-items-must-not-amplify-order-count", CUSTOMERS, ORDERS,
                        List.of(new Item("o1", "i1"), new Item("o1", "i2"), new Item("o2", "i1"))),
                new Scenario("null-identity-is-not-one-real-person", CUSTOMERS,
                        List.of(new Order("o1", "c1"), new Order("o2", "c4")), List.of()),
                new Scenario("orphan-order-has-no-known-person", CUSTOMERS,
                        List.of(new Order("o1", "c1"), new Order("o2", "missing")), List.of()),
                new Scenario("all-person-identities-null", List.of(new Customer("c1", null)),
                        List.of(new Order("o1", "c1")), List.of()),
                new Scenario("no-orders-zero-order-customers-remain-only-when-requested", CUSTOMERS, List.of(), List.of()),
                new Scenario("no-customers", List.of(), ORDERS, List.of()));
    }

    @ParameterizedTest @MethodSource("scenarios")
    void personGroupingRowGroupingAndZeroOrderScopeHaveSeparateOracles(Scenario scenario) throws Exception {
        var policy = DimensionPolicyTest.registry().entityPolicies().getFirst();
        String prefix = cte(scenario);
        String personSql = "SELECT c." + policy.identityColumn() + ",COUNT(DISTINCT o.order_id) "
                + "FROM orders o JOIN customers c ON c." + policy.rowKey() + "=o.customer_id "
                + "LEFT JOIN order_items i ON i.order_id=o.order_id WHERE c." + policy.identityColumn() + " IS NOT NULL GROUP BY 1";
        String rowSql = "SELECT o.customer_id,COUNT(DISTINCT o.order_id) FROM orders o GROUP BY 1";
        String allSql = "SELECT c." + policy.identityColumn() + ",COUNT(DISTINCT o.order_id) "
                + "FROM customers c LEFT JOIN orders o ON c." + policy.rowKey() + "=o.customer_id "
                + "WHERE c." + policy.identityColumn() + " IS NOT NULL GROUP BY 1";
        var people = new LinkedHashMap<String, Set<String>>();
        var rows = new LinkedHashMap<String, Set<String>>();
        var all = new LinkedHashMap<String, Set<String>>();
        var identity = new HashMap<String, String>();
        for (var c : scenario.customers()) {
            identity.put(c.row(), c.person());
            if (c.person() != null) all.computeIfAbsent(c.person(), k -> new HashSet<>());
        }
        for (var o : scenario.orders()) {
            rows.computeIfAbsent(o.customer(), k -> new HashSet<>()).add(o.id());
            String person = identity.get(o.customer());
            if (person == null) continue;
            people.computeIfAbsent(person, k -> new HashSet<>()).add(o.id());
            all.get(person).add(o.id());
        }
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            assertThat(query(connection, prefix + personSql)).isEqualTo(counts(people));
            assertThat(query(connection, prefix + rowSql)).isEqualTo(counts(rows));
            assertThat(query(connection, prefix + allSql)).isEqualTo(counts(all));
            if (scenario.name().startsWith("same-person") || scenario.name().startsWith("one-to-many")) {
                assertThat(query(connection, prefix + personSql)).containsEntry("p1", 2L);
                assertThat(query(connection, prefix + rowSql)).containsEntry("c1", 1L).containsEntry("c2", 1L);
            }
        }
    }

    private static Map<String, Long> query(java.sql.Connection connection, String sql) throws Exception {
        var result = new TreeMap<String, Long>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(sql)) {
                while (rows.next()) result.put(rows.getString(1), rows.getLong(2));
            }
        }
        return result;
    }
    private static Map<String, Long> counts(Map<String, Set<String>> groups) {
        var result = new TreeMap<String, Long>(); groups.forEach((k, v) -> result.put(k, (long) v.size())); return result;
    }
    private static String cte(Scenario s) {
        return "WITH customers(customer_id,customer_unique_id) AS (" + values(s.customers().stream().map(c -> pair(c.row(), c.person())).toList())
                + "), orders(order_id,customer_id) AS (" + values(s.orders().stream().map(o -> pair(o.id(), o.customer())).toList())
                + "), order_items(order_id,order_item_id) AS (" + values(s.items().stream().map(i -> pair(i.order(), i.id())).toList()) + ") ";
    }
    private static String values(List<String> rows) { return rows.isEmpty() ? "SELECT NULL::text,NULL::text WHERE FALSE" : "VALUES " + String.join(",", rows); }
    private static String pair(String a, String b) { return "(" + literal(a) + "," + literal(b) + ")"; }
    private static String literal(String value) { return value == null ? "NULL::text" : "'" + value.replace("'", "''") + "'::text"; }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
