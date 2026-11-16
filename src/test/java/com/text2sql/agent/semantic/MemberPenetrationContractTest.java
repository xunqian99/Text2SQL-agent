package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL, read-only VALUES CTEs: no business rows or evaluation Gold are changed. */
@EnabledIf("databaseAvailable")
class MemberPenetrationContractTest {
    record Order(String id, String customer, String status) { }
    record Customer(String id, String person) { }
    record Member(String id, String person) { }
    record Scenario(String name, List<Order> orders, List<Customer> customers, List<Member> members, String expected) {
        @Override public String toString() { return name; }
    }

    static final List<Customer> CUSTOMERS = List.of(new Customer("c1", "p1"), new Customer("c2", "p1"), new Customer("c3", "p2"));
    static final List<Member> MEMBERS = List.of(new Member("m1", "p1"));
    static final List<Order> THREE = List.of(new Order("o1", "c1", "delivered"), new Order("o2", "c2", "delivered"), new Order("o3", "c3", "delivered"));

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("same-member-two-customer-ids", THREE, CUSTOMERS, MEMBERS, "66.67"),
                new Scenario("cancelled-orders-excluded", Stream.concat(THREE.stream(), Stream.of(new Order("o4", "c1", "canceled"), new Order("o5", "c3", "unavailable"))).toList(), CUSTOMERS, MEMBERS, "66.67"),
                new Scenario("no-members", THREE, CUSTOMERS, List.of(), "0.00"),
                new Scenario("all-orders-from-members", THREE.subList(0, 2), CUSTOMERS, MEMBERS, "100.00"),
                new Scenario("only-nonmember-orders", THREE.subList(2, 3), CUSTOMERS, MEMBERS, "0.00"),
                new Scenario("empty-orders", List.of(), CUSTOMERS, MEMBERS, null),
                new Scenario("all-orders-excluded", List.of(new Order("o1", "c1", "canceled"), new Order("o2", "c3", "unavailable")), CUSTOMERS, MEMBERS, null),
                new Scenario("duplicate-member-rows-do-not-amplify-orders", THREE, CUSTOMERS, List.of(new Member("m1", "p1"), new Member("m2", "p1")), "66.67"),
                new Scenario("null-person-is-not-a-member", List.of(new Order("o1", "c1", "delivered"), new Order("o2", "c4", "delivered")), List.of(new Customer("c1", "p1"), new Customer("c4", null)), MEMBERS, "50.00"),
                new Scenario("missing-customer-still-counts-in-denominator", List.of(new Order("o1", "c1", "delivered"), new Order("o2", "missing", "delivered")), CUSTOMERS, MEMBERS, "50.00"),
                new Scenario("rounds-one-of-six", List.of(new Order("o1", "c1", "delivered"), new Order("o2", "c3", "delivered"), new Order("o3", "c3", "delivered"), new Order("o4", "c3", "delivered"), new Order("o5", "c3", "delivered"), new Order("o6", "c3", "delivered")), CUSTOMERS, MEMBERS, "16.67"),
                new Scenario("null-status-excluded-like-sql-not-in", List.of(new Order("o1", "c1", "delivered"), new Order("o2", "c3", null)), CUSTOMERS, MEMBERS, "100.00")
        );
    }

    @ParameterizedTest @MethodSource("scenarios")
    void expressionCountsOrdersAndKeepsNonmembers(Scenario scenario) throws Exception {
        Metric metric = metric();
        String sql = "SELECT " + metric.expression() + " FROM orders o LEFT JOIN customers c ON c.customer_id = o.customer_id "
                + "LEFT JOIN members m ON m.customer_unique_id = c.customer_unique_id WHERE " + metric.filter();
        verify(scenario, sql);
    }

    @ParameterizedTest @MethodSource("scenarios")
    void recommendedPatternHasTheSameBusinessContract(Scenario scenario) throws Exception {
        assertThat(metric().queryPattern()).as("The denominator needs an explicit join scope").isNotBlank();
        verify(scenario, metric().queryPattern());
    }

    private static Metric metric() {
        var registry = new MetricRegistry();
        registry.load();
        return registry.findApplicable("会员订单占比是多少？", Set.of("orders", "customers", "members"))
                .stream().filter(m -> m.name().equals("member_penetration")).findFirst().orElseThrow();
    }

    static BigDecimal oracle(Scenario scenario) {
        var eligible = scenario.orders().stream().filter(o -> o.status() != null && !Set.of("canceled", "unavailable").contains(o.status())).toList();
        long denominator = eligible.stream().map(Order::id).distinct().count();
        if (denominator == 0) return null;
        var memberPeople = scenario.members().stream().map(Member::person).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        var memberCustomers = scenario.customers().stream().filter(c -> c.person() != null && memberPeople.contains(c.person())).map(Customer::id).collect(Collectors.toSet());
        long numerator = eligible.stream().filter(o -> memberCustomers.contains(o.customer())).map(Order::id).distinct().count();
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    static void verify(Scenario scenario, String sql) throws Exception {
        BigDecimal expected = oracle(scenario);
        if (scenario.expected() == null) assertThat(expected).isNull();
        else assertThat(expected).isEqualByComparingTo(scenario.expected());
        try (var connection = DriverManager.getConnection(url(), user(), password())) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery(ctes(scenario) + sql)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getMetaData().getColumnCount()).isEqualTo(1);
                    BigDecimal actual = result.getBigDecimal(1);
                    if (expected == null) assertThat(actual).isNull();
                    else assertThat(actual).isEqualByComparingTo(expected);
                    assertThat(result.next()).isFalse();
                }
            }
        }
    }

    static String ctes(Scenario scenario) {
        return "WITH orders(order_id, customer_id, order_status) AS (" + rows(scenario.orders().stream().map(o -> List.of(value(o.id()), value(o.customer()), value(o.status()))).toList(), 3)
                + "), customers(customer_id, customer_unique_id) AS (" + rows(scenario.customers().stream().map(c -> List.of(value(c.id()), value(c.person()))).toList(), 2)
                + "), members(member_id, customer_unique_id) AS (" + rows(scenario.members().stream().map(m -> List.of(value(m.id()), value(m.person()))).toList(), 2) + ") ";
    }

    private static String rows(List<List<String>> rows, int columns) {
        if (rows.isEmpty()) return "SELECT " + Stream.generate(() -> "NULL::text").limit(columns).collect(Collectors.joining(",")) + " WHERE FALSE";
        return "VALUES " + rows.stream().map(row -> "(" + String.join(",", row) + ")").collect(Collectors.joining(","));
    }

    private static String value(String value) { return value == null ? "NULL::text" : "'" + value.replace("'", "''") + "'::text"; }
    static boolean databaseAvailable() {
        try (var connection = DriverManager.getConnection(url(), user(), password())) { return connection.isValid(2); }
        catch (Exception e) { return false; }
    }
    static String url() { return env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist"); }
    static String user() { return env("TEXT2SQL_TEST_DB_USER", "text2sql_ro"); }
    static String password() { return env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro"); }
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
