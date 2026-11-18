package com.text2sql.agent.retrieval;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Actual dictionary -> retrieval -> pruning -> model context, without a model call. */
class FulfillmentTemporalContextTest {
    @ParameterizedTest
    @ValueSource(strings = {"尚未送达客户的订单有多少？", "逾期送达订单占比是多少？",
            "已经签收的订单有哪些？", "哪些订单尚未发货？", "订单收货情况如何？"})
    void implicitEventsKeepActualAndPromisedTimesWithoutReplacingFallbackFields(String question) {
        var fixture = fixture(true);
        var retrieval = fixture.retriever().retrieve(question);
        assertThat(retrieval.fellBack()).isFalse();
        assertThat(retrieval.tableNames()).contains("orders");
        assertThat(retrieval.expandedTables()).doesNotContain("orders");
        var context = fixture.provider().provide(question);
        assertThat(columns(context, "orders")).contains("order_id", "customer_id", "order_status",
                "order_delivered_customer_date", "order_estimated_delivery_date",
                "order_delivered_carrier_date");
        assertThat(new PromptTemplate().userPrompt(context, question))
                .contains("order_delivered_customer_date", "order_estimated_delivery_date")
                .endsWith(question + "\n");
    }

    @Test
    void shipmentEventsKeepTheirOwnPhysicalClockAndKeys() {
        var context = fixture(true).provider().provide("订单的发货单中哪些尚未送达？");
        assertThat(columns(context, "shipments"))
                .contains("shipment_id", "order_id", "shipped_at", "delivered_at")
                .doesNotContain("carrier");
    }

    @Test
    void statusOnlyRequestStillPrunesUnrelatedClocks() {
        var context = fixture(true).provider().provide("统计各状态订单总数");
        assertThat(columns(context, "orders"))
                .contains("order_id", "customer_id", "order_status")
                .doesNotContain("order_delivered_carrier_date", "order_delivered_customer_date",
                        "order_estimated_delivery_date");
    }

    @Test
    void eventLanguageCannotInventAnAbsentPhysicalColumn() {
        var context = fixture(false).provider().provide("订单逾期送达比例是多少？");
        assertThat(columns(context, "orders")).contains("order_delivered_customer_date")
                .doesNotContain("order_estimated_delivery_date");
        assertThat(new PromptTemplate().userPrompt(context, "订单逾期送达比例是多少？"))
                .doesNotContain("order_estimated_delivery_date");
    }

    private List<String> columns(SchemaContext context, String table) {
        return context.tables().stream().filter(t -> t.name().equals(table)).findFirst().orElseThrow()
                .columns().stream().map(SchemaContext.Column::name).toList();
    }

    private Fixture fixture(boolean withPromisedTime) {
        var columns = new java.util.ArrayList<>(List.of(
                column("order_id", "text", true), column("customer_id", "text", false),
                column("order_status", "text", false), column("order_purchase_timestamp", "timestamp", false),
                column("order_approved_at", "timestamp", false),
                column("order_delivered_carrier_date", "timestamp", false),
                column("order_delivered_customer_date", "timestamp", false)));
        if (withPromisedTime) columns.add(column("order_estimated_delivery_date", "timestamp", false));
        var orders = new SchemaContext.Table("orders", null, columns);
        var shipments = new SchemaContext.Table("shipments", null, List.of(
                column("shipment_id", "text", true), column("order_id", "text", false),
                column("status", "integer", false), column("shipped_at", "timestamp", false),
                column("delivered_at", "timestamp", false), column("carrier", "text", false)));
        var full = new SchemaContext(List.of(orders, shipments), List.of(
                new SchemaContext.ForeignKey("shipments", "order_id", "orders", "order_id")), "", "");
        var catalog = mock(SchemaCatalog.class);
        var byName = new LinkedHashMap<String, SchemaContext.Table>();
        full.tables().forEach(t -> byName.put(t.name(), t));
        when(catalog.full()).thenReturn(full);
        when(catalog.tablesByName()).thenReturn(byName);
        var props = new AgentProperties();
        props.getSemantic().setEnabled(false);
        props.getRetrieval().setColumnPruningEnabled(true);
        var glossary = new GlossaryLoader(catalog);
        var retriever = new LexicalSchemaRetriever(glossary, catalog, props);
        var provider = new HybridSchemaProvider(catalog, retriever, glossary, props,
                null, null, new ValueRetriever(glossary), new ColumnPruner(glossary));
        return new Fixture(retriever, provider);
    }

    private SchemaContext.Column column(String name, String type, boolean key) {
        return new SchemaContext.Column(name, type, true, null, key);
    }

    private record Fixture(LexicalSchemaRetriever retriever, HybridSchemaProvider provider) {}
}
