package com.text2sql.agent.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DriverManager;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Execute the shipped expressions in read-only PostgreSQL, rather than checking SQL substrings. */
@EnabledIf("databaseAvailable")
class MetricDivisionBoundaryTest {
    record Scenario(String name, String from, String expected) {
        @Override public String toString() { return name; }
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("aov", "input oi CROSS JOIN input o", "100"),
                new Scenario("refund_rate", "input r CROSS JOIN input o", "100"),
                new Scenario("sell_through_rate", "input oi", "100"),
                new Scenario("low_score_rate", "input r", "100"),
                new Scenario("ctr", "input", "100"),
                new Scenario("freight_ratio", "input oi", "20"),
                new Scenario("sla_rate", "input st CROSS JOIN input tc", "100"),
                new Scenario("discount_rate", "input cu CROSS JOIN input oi", "10"),
                new Scenario("commission_rate", "input si", "0.1"),
                new Scenario("conversion_rate", "input", "100"),
                new Scenario("retention_rate", "input", "100"),
                new Scenario("yoy_growth", "(SELECT MAX(current_gmv) AS current_gmv, "
                        + "MAX(previous_gmv) AS previous_gmv FROM input) totals", "50"));
    }

    static Stream<Scenario> zeroScenarios() {
        return scenarios().filter(s -> List.of("aov", "refund_rate", "sell_through_rate", "freight_ratio",
                "discount_rate", "commission_rate", "yoy_growth").contains(s.name()));
    }

    @ParameterizedTest @MethodSource("scenarios")
    void ordinaryInputsKeepTheExpectedValue(Scenario scenario) throws Exception {
        execute(scenario, false, false, scenario.expected());
    }

    @ParameterizedTest @MethodSource("scenarios")
    void emptyPopulationReturnsUndefinedWithoutSqlFailure(Scenario scenario) throws Exception {
        execute(scenario, true, false, null);
    }

    @ParameterizedTest @MethodSource("zeroScenarios")
    void nonemptyZeroDenominatorReturnsUndefinedWithoutSqlFailure(Scenario scenario) throws Exception {
        execute(scenario, false, true, null);
    }

    @Test
    void retentionCountsOnlyTrueAndKeepsFalseAndUnknownInTheCohortDenominator() throws Exception {
        String expression = metric("retention_rate").expression();
        query("WITH cohort(returned) AS (VALUES (TRUE),(FALSE),(NULL::boolean)) SELECT "
                + expression + " FROM cohort", "33.33");
    }

    @ParameterizedTest
    @CsvSource(value = {"50,100,-50", "300,100,200", "10,NULL,NULL", "NULL,100,NULL"}, nullValues = "NULL")
    void growthKeepsItsSignAndDoesNotFabricateMissingComparisons(String current, String previous, String expected) throws Exception {
        query("WITH totals(current_gmv,previous_gmv) AS (VALUES (" + numeric(current) + ","
                + numeric(previous) + ")) SELECT " + metric("yoy_growth").expression() + " FROM totals", expected);
    }

    private static void execute(Scenario scenario, boolean empty, boolean zero, String expected) throws Exception {
        String fixture = "WITH input AS (SELECT " + (zero ? "NULL" : "'order-1'") + "::text AS order_id, "
                + "'product-1'::text AS product_id, " + (zero ? "0" : "80") + "::numeric AS price, "
                + (zero ? "0" : "20") + "::numeric AS freight_value, 1 AS review_score, "
                + "'product-1'::text AS clicked_product_id, TIMESTAMP '2024-02-02' AS resolved_at, "
                + "TIMESTAMP '2024-02-01' AS created_at, 24 AS sla_hours, 10::numeric AS discount_amount, "
                + "10::numeric AS commission_amount, " + (zero ? "0" : "100") + "::numeric AS gross_amount, "
                + "TIMESTAMP '2024-02-02' AS converted_at, TRUE AS returned, 150::numeric AS current_gmv, "
                + (zero ? "0" : "100") + "::numeric AS previous_gmv WHERE " + (empty ? "FALSE" : "TRUE")
                + "), products AS (SELECT product_id FROM input" + (zero ? " WHERE FALSE" : "") + ") ";
        query(fixture + "SELECT " + metric(scenario.name()).expression() + " FROM " + scenario.from(), expected);
    }

    private static Metric metric(String name) {
        return DimensionPolicyTest.registry().all().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static String numeric(String value) { return (value == null ? "NULL" : value) + "::numeric"; }

    private static void query(String sql, String expected) throws Exception {
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery(sql)) {
                    assertThat(rows.next()).isTrue();
                    if (expected == null) assertThat(rows.getBigDecimal(1)).isNull();
                    else assertThat(rows.getBigDecimal(1)).isEqualByComparingTo(expected);
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
