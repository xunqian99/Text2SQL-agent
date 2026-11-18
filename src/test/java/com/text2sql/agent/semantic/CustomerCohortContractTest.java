package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Execute the shipped pattern over read-only counterexamples; Java supplies an independent oracle. */
@EnabledIf("databaseAvailable")
class CustomerCohortContractTest {
    record Order(String id, String customer, String status, LocalDateTime time) { }
    record Customer(String id, String person) { }
    record Scenario(String name, List<Order> orders, List<Customer> customers, YearMonth reportMonth) {
        @Override public String toString() { return name; }
    }

    static final List<Customer> PEOPLE = List.of(new Customer("c1", "p1"), new Customer("c2", "p1"),
            new Customer("c3", "p2"), new Customer("c4", null));

    static Stream<Scenario> scenarios() {
        return Stream.of(
                scenario("history-before-report-and-different-record", List.of(order("a", "c1", "2017-12-01T10:00"), order("b", "c2", "2018-01-02T10:00")), YearMonth.of(2018, 1)),
                scenario("all-orders-in-first-month-new", List.of(order("a", "c1", "2018-01-01T00:00"), order("b", "c2", "2018-01-31T23:59"), order("c", "c1", "2018-02-01T00:00")), null),
                scenario("canceled-and-unavailable-not-first", List.of(new Order("a", "c1", "canceled", time("2017-12-01T00:00")), new Order("b", "c2", "unavailable", time("2017-12-02T00:00")), order("c", "c1", "2018-01-01T00:00")), null),
                scenario("unknown-identities-and-times-retain-facts", List.of(order("a", "c4", "2018-01-01T00:00"), order("b", "missing", "2018-01-01T00:00"), new Order("c", "c1", "delivered", null), new Order("d", "c3", "delivered", null)), null),
                scenario("independent-people", List.of(order("a", "c1", "2017-12-31T23:59"), order("b", "c3", "2018-01-01T00:00"), order("c", "c2", "2018-01-01T00:00")), null),
                scenario("null-status-excluded", List.of(new Order("a", "c1", null, time("2017-12-01T00:00")), order("b", "c2", "2018-01-01T00:00")), null),
                scenario("empty-orders", List.of(), null),
                new Scenario("missing-customer-table-facts-retained", List.of(order("a", "c1", "2018-01-01T00:00")), List.of(), null),
                scenario("report-month-empty", List.of(order("a", "c1", "2017-12-01T00:00")), YearMonth.of(2018, 1))
        );
    }

    @ParameterizedTest @MethodSource("scenarios")
    void patternMatchesNaturalPersonMonthOracle(Scenario scenario) throws Exception {
        var metric = DimensionPolicyTest.registry().findMentioned("新老客").stream()
                .filter(m -> m.name().equals("customer_cohort")).findFirst().orElseThrow();
        var expected = oracle(scenario);
        String filter = scenario.reportMonth() == null ? "" : " WHERE DATE_TRUNC('month', order_purchase_timestamp) = "
                + value(scenario.reportMonth().atDay(1).atStartOfDay().toString()) + "::timestamp";
        String sql = ctes(scenario) + "SELECT order_id, customer_cohort FROM (" + metric.queryPattern() + ") classified" + filter;
        var actual = new TreeMap<String, String>();
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery(sql)) {
                    while (result.next()) {
                        String id = result.getString(1);
                        assertThat(actual).doesNotContainKey(id);
                        actual.put(id, result.getString(2));
                    }
                }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
        if (scenario.name().equals("history-before-report-and-different-record"))
            assertThat(actual).containsEntry("b", "RETURNING").hasSize(1);
        if (scenario.name().equals("unknown-identities-and-times-retain-facts"))
            assertThat(actual).hasSize(4).allSatisfy((id, cohort) -> assertThat(cohort).isNull());
    }

    static Map<String, String> oracle(Scenario scenario) {
        Map<String, String> personByRecord = new HashMap<>();
        scenario.customers().forEach(c -> personByRecord.put(c.id(), c.person()));
        Map<String, LocalDateTime> first = new HashMap<>();
        for (var order : scenario.orders()) {
            String person = personByRecord.get(order.customer());
            if (valid(order) && person != null && order.time() != null)
                first.merge(person, order.time(), (a, b) -> a.isBefore(b) ? a : b);
        }
        Map<String, String> result = new TreeMap<>();
        for (var order : scenario.orders()) {
            if (!valid(order) || scenario.reportMonth() != null
                    && (order.time() == null || !YearMonth.from(order.time()).equals(scenario.reportMonth()))) continue;
            LocalDateTime earliest = first.get(personByRecord.get(order.customer()));
            result.put(order.id(), earliest == null || order.time() == null ? null
                    : YearMonth.from(earliest).equals(YearMonth.from(order.time())) ? "NEW" : "RETURNING");
        }
        return result;
    }

    static boolean valid(Order o) { return o.status() != null && !Set.of("canceled", "unavailable").contains(o.status()); }
    static Scenario scenario(String name, List<Order> orders, YearMonth month) { return new Scenario(name, orders, PEOPLE, month); }
    static Order order(String id, String customer, String time) { return new Order(id, customer, "delivered", time(time)); }
    static LocalDateTime time(String text) { return LocalDateTime.parse(text); }
    static String ctes(Scenario s) {
        return "WITH orders(order_id,customer_id,order_status,order_purchase_timestamp) AS ("
                + rows(s.orders().stream().map(o -> value(o.id()) + "," + value(o.customer()) + "," + value(o.status())
                + "," + value(o.time() == null ? null : o.time().toString()) + "::timestamp").toList(), "NULL::text,NULL::text,NULL::text,NULL::timestamp")
                + "), customers(customer_id,customer_unique_id) AS ("
                + rows(s.customers().stream().map(c -> value(c.id()) + "," + value(c.person())).toList(), "NULL::text,NULL::text") + ") ";
    }
    static String rows(List<String> rows, String empty) {
        return rows.isEmpty() ? "SELECT " + empty + " WHERE FALSE"
                : "VALUES " + rows.stream().map(r -> "(" + r + ")").collect(Collectors.joining(","));
    }
    static String value(String v) { return v == null ? "NULL::text" : "'" + v.replace("'", "''") + "'::text"; }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
