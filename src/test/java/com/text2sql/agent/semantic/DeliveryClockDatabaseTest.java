package com.text2sql.agent.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only VALUES counterexamples: model-independent clock and averaging definition. */
@EnabledIf("databaseAvailable")
class DeliveryClockDatabaseTest {
    @ParameterizedTest @CsvSource({"2018-01-01 00:00:00,2018-02-10 12:00:00,40.50",
            "2018-01-01 00:00:00,2018-01-01 06:00:00,0.25",
            "2018-01-01 00:00:00,2018-01-01 00:00:00,0.00"})
    void timestampsKeepMonthsAndFractionalDays(String start, String end, String expected) throws Exception {
        String source = "(VALUES (TIMESTAMP '" + start + "',TIMESTAMP '" + end + "')) AS s(shipped_at,delivered_at)";
        assertThat(value(source)).isEqualByComparingTo(expected);
    }

    @Test void unfinishedAndMissingStartDoNotDiluteMean() throws Exception {
        String source = "(VALUES (TIMESTAMP '2018-01-01',TIMESTAMP '2018-01-03'),"
                + "(TIMESTAMP '2018-01-01',NULL::timestamp),(NULL::timestamp,TIMESTAMP '2018-01-03')) AS s(shipped_at,delivered_at)";
        assertThat(value(source)).isEqualByComparingTo("2.00");
    }

    @Test void noCompletedShipmentReturnsUndefinedRatherThanZero() throws Exception {
        assertThat(value("(SELECT NULL::timestamp AS shipped_at,NULL::timestamp AS delivered_at WHERE FALSE) AS s"))
                .isNull();
        assertThat(value("(VALUES (TIMESTAMP '2018-01-01',NULL::timestamp)) AS s(shipped_at,delivered_at)"))
                .isNull();
    }

    @Test void orderClockAndShipmentClockReallyDifferOnSameDelivery() throws Exception {
        String from = "(VALUES (TIMESTAMP '2018-01-19',TIMESTAMP '2018-01-21')) AS s(shipped_at,delivered_at)";
        assertThat(value(from)).isEqualByComparingTo("2.00");
        // Purchase was Jan 1; an order-based implementation would return 20, despite legal SQL.
        assertThat(value("(VALUES (TIMESTAMP '2018-01-01',TIMESTAMP '2018-01-21')) AS s(shipped_at,delivered_at)"))
                .isEqualByComparingTo("20.00");
    }

    @Test void differentEndpointsExposeMixedClockEvenWhenProductionValuesCoincide() throws Exception {
        String source = "(VALUES (TIMESTAMP '2018-01-01',TIMESTAMP '2018-01-21')) "
                + "AS o(order_purchase_timestamp,order_delivered_customer_date) CROSS JOIN "
                + "(VALUES (TIMESTAMP '2018-01-11')) AS s(delivered_at)";
        String correct = MetricUnitContractTest.metric("delivery_days").expression();
        String mixed = correct.replace("o.order_delivered_customer_date", "s.delivered_at");
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery("SELECT " + correct + "," + mixed + " FROM " + source)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBigDecimal(1)).isEqualByComparingTo("20.00");
                    assertThat(result.getBigDecimal(2)).isEqualByComparingTo("10.00");
                    assertThat(result.next()).isFalse();
                }
            }
        }
    }

    private static java.math.BigDecimal value(String source) throws Exception {
        var metric = MetricUnitContractTest.metric("shipment_delivery_days");
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery("SELECT " + metric.expression() + " FROM " + source + " WHERE " + metric.filter())) {
                    assertThat(result.next()).isTrue();
                    var actual = result.getBigDecimal(1);
                    assertThat(result.next()).isFalse(); return actual;
                }
            }
        }
    }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
