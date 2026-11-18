package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.ColumnPruner;
import com.text2sql.agent.retrieval.FullSchemaProvider;
import com.text2sql.agent.retrieval.HybridSchemaProvider;
import com.text2sql.agent.retrieval.LexicalSchemaRetriever;
import com.text2sql.agent.retrieval.SchemaCatalog;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.retrieval.ValueRetriever;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FreightMetricScopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"不含运费销售额是多少？", "不含运费的销售额是多少？",
            "不包含运费的退款金额是多少？", "包含运费的退款金额是多少？",
            "含运费的销售额是多少？", "分别统计含运费与不含运费的销售额"})
    void freightModifierCannotIntroduceAnUnrequestedGmvMetric(String question) {
        assertThat(DimensionPolicyTest.registry().findMentioned(question))
                .extracting(Metric::name).doesNotContain("gmv_with_freight");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GMV是多少？", "成交总额是多少？", "商品交易总额是多少？"})
    void namedGmvKeepsItsDefinitionAndFilter(String question) {
        assertThat(DimensionPolicyTest.registry().findMentioned(question)).singleElement().satisfies(metric -> {
            assertThat(metric.name()).isEqualTo("gmv_with_freight");
            assertThat(metric.expression()).isEqualTo("SUM(oi.price + oi.freight_value)");
            assertThat(metric.filter()).isEqualTo("o.order_status NOT IN ('canceled', 'unavailable')");
        });
    }

    @Test
    void explicitGmvAndNetSalesRemainTwoDistinctMetrics() {
        assertThat(DimensionPolicyTest.registry().findMentioned("GMV和不含运费销售额分别是多少？"))
                .extracting(Metric::name).containsExactly("gmv_with_freight", "sales_without_freight");
    }

    @Test
    void bothProvidersKeepNetSalesSourceWithoutInjectingGmvOrItsFilter() {
        var props = new AgentProperties();
        props.getSemantic().setEnabled(true);
        props.getRetrieval().setColumnPruningEnabled(true);
        props.getRetrieval().setColumnPruningMinColumns(1);
        var orders = new SchemaContext.Table("orders", null, List.of(
                new SchemaContext.Column("order_id", "text", false, null, true),
                new SchemaContext.Column("order_status", "text", false, null)));
        var items = new SchemaContext.Table("order_items", null, List.of(
                new SchemaContext.Column("order_id", "text", false, null, true),
                new SchemaContext.Column("order_item_id", "integer", false, null, true),
                new SchemaContext.Column("price", "numeric", false, null),
                new SchemaContext.Column("freight_value", "numeric", false, null)));
        var full = new SchemaContext(List.of(orders, items), List.of(
                new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id")), "", "");
        var catalog = mock(SchemaCatalog.class);
        when(catalog.full()).thenReturn(full);
        var byName = new LinkedHashMap<String, SchemaContext.Table>();
        full.tables().forEach(table -> byName.put(table.name(), table));
        when(catalog.tablesByName()).thenReturn(byName);
        var registry = DimensionPolicyTest.registry();
        var glossary = new GlossaryLoader(catalog);
        var providers = List.<SchemaProvider>of(new FullSchemaProvider(catalog, registry, props),
                new HybridSchemaProvider(catalog, new LexicalSchemaRetriever(glossary, catalog, props),
                        glossary, props, registry, null, new ValueRetriever(glossary), new ColumnPruner(glossary)));
        String question = "不含运费销售额是多少？";
        for (var provider : providers) {
            var context = provider.provide(question);
            assertThat(context.metricsText()).contains("METRIC sales_without_freight", "SUM(oi.price)")
                    .doesNotContain("METRIC gmv_with_freight", "过滤条件（默认附加）");
            assertThat(context.tables().stream().filter(t -> t.name().equals("order_items"))
                    .findFirst().orElseThrow().columns()).extracting(SchemaContext.Column::name).contains("price");
            assertThat(new PromptTemplate(props).userPrompt(context, question))
                    .contains("METRIC sales_without_freight").doesNotContain("METRIC gmv_with_freight")
                    .endsWith(question + "\n");
        }
    }
}
