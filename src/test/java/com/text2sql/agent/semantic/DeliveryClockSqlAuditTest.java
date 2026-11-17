package com.text2sql.agent.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryClockSqlAuditTest {
    private static final DeliveryClockSqlAudit.Requirement ORDER = new DeliveryClockSqlAudit.Requirement(
            "orders.order_delivered_customer_date", "orders.order_purchase_timestamp");

    @ParameterizedTest @ValueSource(strings = {
            "SELECT s.carrier,ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date-o.order_purchase_timestamp))/86400.0)::numeric,2) FROM shipments s JOIN orders o ON o.order_id=s.order_id GROUP BY s.carrier",
            "SELECT AVG(EXTRACT(EPOCH FROM (order_delivered_customer_date-order_purchase_timestamp))/86400.0) FROM orders",
            "SELECT AVG(EXTRACT(EPOCH FROM (x.order_delivered_customer_date-x.order_purchase_timestamp))/86400.0) FROM public.orders x"})
    void followsActualAliasesAndUnqualifiedSingleTableSources(String sql) {
        assertThat(DeliveryClockSqlAudit.inspect(sql, ORDER).status()).isEqualTo("PASS");
    }

    @Test void realMixedEndpointFailureIsRejectedEvenIfNumbersMatched() {
        var result = DeliveryClockSqlAudit.inspect("SELECT s.carrier, ROUND(AVG(EXTRACT(EPOCH FROM "
                + "(s.delivered_at-o.order_purchase_timestamp))/86400.0)::numeric,2) "
                + "FROM shipments s JOIN orders o ON o.order_id=s.order_id GROUP BY s.carrier", ORDER);
        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.avgColumns()).containsExactly("shipments.delivered_at", "orders.order_purchase_timestamp");
        assertThat(result.tableAliases()).containsEntry("s", "shipments").containsEntry("o", "orders");
    }

    @Test void correctFieldElsewhereCannotDisguiseWrongAggregate() {
        assertThat(DeliveryClockSqlAudit.inspect("SELECT o.order_delivered_customer_date, "
                + "AVG(EXTRACT(EPOCH FROM (s.delivered_at-o.order_purchase_timestamp))/86400.0) "
                + "FROM shipments s JOIN orders o ON o.order_id=s.order_id GROUP BY o.order_delivered_customer_date", ORDER).status())
                .isEqualTo("FAIL");
    }

    @Test void aliasesCannotMasqueradeAsTheOrdersTable() {
        assertThat(DeliveryClockSqlAudit.inspect("SELECT AVG(EXTRACT(EPOCH FROM "
                + "(orders.order_delivered_customer_date-orders.order_purchase_timestamp))/86400.0) FROM shipments orders", ORDER).status())
                .isEqualTo("FAIL");
    }

    @Test void reversedSubtractionFailsAndDistinctCannotChangeGrain() {
        assertThat(DeliveryClockSqlAudit.inspect("SELECT AVG(EXTRACT(EPOCH FROM "
                + "(order_purchase_timestamp-order_delivered_customer_date))/86400.0) FROM orders", ORDER).status()).isEqualTo("FAIL");
        assertThat(DeliveryClockSqlAudit.inspect("SELECT AVG(DISTINCT EXTRACT(EPOCH FROM "
                + "(order_delivered_customer_date-order_purchase_timestamp))/86400.0) FROM orders", ORDER).status()).isEqualTo("FAIL");
    }

    @ParameterizedTest @ValueSource(strings = {
            "WITH x AS (SELECT * FROM orders) SELECT AVG(EXTRACT(EPOCH FROM (order_delivered_customer_date-order_purchase_timestamp))/86400.0) FROM x",
            "SELECT AVG(EXTRACT(EPOCH FROM (order_delivered_customer_date-order_purchase_timestamp))/86400.0) FROM shipments s JOIN orders o ON s.order_id=o.order_id",
            "SELECT AVG(1),AVG(2) FROM orders", "SELECT 1", "not sql",
            "SELECT AVG(EXTRACT(EPOCH FROM (x.order_delivered_customer_date-x.order_purchase_timestamp))/86400.0) FROM (SELECT * FROM orders) x"})
    void unsupportedShapesNeverSilentlyPass(String sql) {
        assertThat(DeliveryClockSqlAudit.inspect(sql, ORDER).status()).isEqualTo("UNSUPPORTED");
    }
}
