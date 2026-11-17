package com.text2sql.agent.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only counterexamples; only the on-time SQL is an executable production expression.
 * Entity/JOIN examples verify the declared semantics, not whether an LLM follows the prompt. */
@EnabledIf("databaseAvailable")
class EntityCoverageDatabaseTest {
    record DeliveryCase(String name, String rows, String expected) {
        @Override public String toString() { return name; }
    }

    static Stream<DeliveryCase> deliveries() {
        return Stream.of(
                new DeliveryCase("status-and-delivery-disagree",
                        "('processing',1,2),('delivered',3,2),('delivered',NULL,2)", "50.00"),
                new DeliveryCase("missing-estimate-stays-in-denominator",
                        "('delivered',1,2),('delivered',1,NULL)", "50.00"),
                new DeliveryCase("delivered-on-deadline", "('delivered',2,2)", "100.00"),
                new DeliveryCase("late-delivery", "('delivered',3,2)", "0.00"),
                new DeliveryCase("no-delivered-orders", "('delivered',NULL::int,2)", null),
                new DeliveryCase("empty-input", "", null));
    }

    @ParameterizedTest @MethodSource("deliveries")
    void productionExpressionOwnsItsDenominatorWithoutAnOuterFilter(DeliveryCase example) throws Exception {
        var registry = new MetricRegistry();
        registry.load();
        var metric = registry.findApplicable("准时率", Set.of("orders")).stream()
                .filter(m -> m.name().equals("on_time_rate")).findFirst().orElseThrow();
        String input = example.rows().isEmpty()
                ? "SELECT NULL::text,NULL::int,NULL::int WHERE FALSE" : "VALUES " + example.rows();
        String sql = "WITH orders(order_status,order_delivered_customer_date,order_estimated_delivery_date) AS ("
                + input + ") SELECT " + metric.expression() + " FROM orders o";
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                if (example.expected() == null) assertThat(result.getBigDecimal(1)).isNull();
                else assertThat(result.getBigDecimal(1)).isEqualByComparingTo(example.expected());
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test void sameNameEntitiesAndEqualAmountsAreNotDeduplicationKeys() throws Exception {
        String fixture = """
                WITH campaigns(id,name,budget) AS (VALUES (1,'sale',70),(2,'sale',70),(3,'sale',40)),
                     details(campaign_id) AS (VALUES (1),(1),(2),(3))
                """;
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(fixture
                    + "SELECT c.id,c.budget FROM campaigns c GROUP BY c.id,c.budget ORDER BY c.id")) {
                for (int budget : new int[]{70,70,40}) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(2)).isEqualTo(budget);
                }
                assertThat(rows.next()).isFalse();
            }
            try (var rows = statement.executeQuery(fixture + "SELECT SUM(c.budget),SUM(DISTINCT c.budget)"
                    + " FROM campaigns c JOIN details d ON d.campaign_id=c.id")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(250); // fanout overcounts the true 180
                assertThat(rows.getInt(2)).isEqualTo(110); // DISTINCT amount undercounts it
            }
        }
    }

    @Test void memberCoverageIsIndependentOfItemCoverageIncludingEmptyGroups() throws Exception {
        String fixture = """
                WITH tags(id,name) AS (VALUES (1,'same'),(2,'same'),(3,'empty')),
                     members(id,tag_id) AS (VALUES (10,1),(20,1),(30,2)),
                     items(member_id,price) AS (VALUES (10,10::numeric),(10,30::numeric))
                """;
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(fixture + """
                    SELECT t.id,COUNT(DISTINCT m.id),ROUND(AVG(i.price),2)
                    FROM tags t LEFT JOIN members m ON m.tag_id=t.id
                    LEFT JOIN items i ON i.member_id=m.id GROUP BY t.id ORDER BY t.id
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(2)).isEqualTo(2); assertThat(rows.getBigDecimal(3)).isEqualByComparingTo("20");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(2)).isEqualTo(1); assertThat(rows.getBigDecimal(3)).isNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(2)).isZero(); assertThat(rows.getBigDecimal(3)).isNull();
                assertThat(rows.next()).isFalse();
            }
            try (var rows = statement.executeQuery(fixture + """
                    SELECT COUNT(DISTINCT m.id) FROM tags t JOIN members m ON m.tag_id=t.id
                    JOIN items i ON i.member_id=m.id WHERE t.id=1
                    """)) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(1); // wrong for all covered members
            }
        }
    }

    private static Connection connection() throws Exception {
        var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password());
        connection.setReadOnly(true);
        return connection;
    }

    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
