package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CustomerCohortContextTest {
    @ParameterizedTest
    @ValueSource(strings = {"每个月新客的GMV", "新老客各有多少订单", "老客贡献的GMV",
            "新客标签用户中，按首次下单月区分的新客和老客GMV"})
    void dynamicRequestsRetainASeparateCohortDefinition(String question) {
        assertThat(DimensionPolicyTest.registry().findMentioned(question))
                .extracting(Metric::name).contains("customer_cohort");
    }

    @ParameterizedTest
    @ValueSource(strings = {"带新客标签的用户GMV", "标签为新客的用户", "老客标签有多少人", "新客标记的用户数"})
    void explicitTagRequestsDoNotSilentlyAcquireFirstOrderSemantics(String question) {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.findMentioned(question)).extracting(Metric::name).doesNotContain("customer_cohort");
        assertThat(registry.matchEvidence(question)).filteredOn(e -> e.metric().name().equals("customer_cohort"))
                .singleElement().satisfies(e -> {
                    assertThat(e.decision()).isEqualTo(MetricRegistry.MatchDecision.EXCLUDED_CONTEXT);
                    assertThat(e.excludedContextTerm()).isNotBlank();
                });
    }

    @Test void aSeparateUnscopedOccurrenceSurvivesAnEarlierTagMention() {
        var match = DimensionPolicyTest.registry().matchEvidence("新客标签与每月老客的贡献分别是多少")
                .stream().filter(e -> e.metric().name().equals("customer_cohort")).findFirst().orElseThrow();
        assertThat(match.decision()).isEqualTo(MetricRegistry.MatchDecision.MATCHED);
        assertThat(match.alias()).isEqualTo("老客");
    }

    @Test void theDefinitionCannotBeSelectedWithoutItsRealIdentityAndOrderTables() {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.findApplicable("新客订单", Set.of("orders"))).extracting(Metric::name).doesNotContain("customer_cohort");
        assertThat(registry.findApplicable("新客订单", Set.of("customers"))).extracting(Metric::name).doesNotContain("customer_cohort");
        assertThat(registry.findApplicable("新客订单", Set.of("customers", "orders")))
                .extracting(Metric::name).contains("customer_cohort");
    }

    @Test void bothProvidersDeliverTheDynamicDefinitionWithoutFalseFiltersAndKeepItsColumns() {
        var fixture = fixture();
        String question = "2018年每个月的GMV中，新客和老客各贡献多少？";
        for (var provider : fixture.providers()) {
            var context = provider.provide(question);
            assertThat(context.metricsText()).contains("METRIC customer_cohort", "完整历史有效订单", "MIN(o.order_purchase_timestamp)",
                    "first_orders.first_order_month", "customers.customer_unique_id");
            assertThat(context.valueHints() == null ? "" : context.valueHints())
                    .doesNotContain("tag_code = 'NEW_USER'", "priority = '2'");
            assertThat(new PromptTemplate(fixture.props()).userPrompt(context, question))
                    .contains("METRIC customer_cohort", "首次有效下单月");
            assertThat(context.tables().stream().filter(t -> t.name().equals("orders")).findFirst().orElseThrow().columns())
                    .extracting(SchemaContext.Column::name).contains("order_id", "customer_id", "order_status", "order_purchase_timestamp");
            assertThat(context.tables().stream().filter(t -> t.name().equals("customers")).findFirst().orElseThrow().columns())
                    .extracting(SchemaContext.Column::name).contains("customer_id", "customer_unique_id");
        }
    }

    @Test void bareCohortQuestionsKeepTimeColumnsEvenWithoutTemporalWords() {
        var fixture = fixture();
        var context = fixture.providers().get(1).provide("新客有多少人？");
        assertThat(context.tables().stream().filter(t -> t.name().equals("orders")).findFirst().orElseThrow().columns())
                .extracting(SchemaContext.Column::name).contains("order_purchase_timestamp", "order_status");
    }

    @Test void explicitTagsAndSemanticDisabledModeKeepTheirOwnBehavior() {
        var fixture = fixture();
        for (var provider : fixture.providers()) {
            var tagged = provider.provide("带新客标签的用户的GMV");
            assertThat(tagged.valueHints()).contains("tag_code = 'NEW_USER'");
            assertThat(tagged.metricsText()).doesNotContain("METRIC customer_cohort");
        }
        fixture.props().getSemantic().setEnabled(false);
        for (var provider : fixture.providers()) {
            var dynamic = provider.provide("GMV中新客和老客的贡献");
            assertThat(dynamic.metricsText()).isBlank();
            assertThat(dynamic.valueHints() == null ? "" : dynamic.valueHints()).doesNotContain("tag_code = 'NEW_USER'", "priority = '2'");
        }
    }

    private record Fixture(AgentProperties props, List<SchemaProvider> providers) { }

    private static Fixture fixture() {
        var full = new SchemaContext(List.of(
                table("orders", "order_id", "customer_id", "order_status", "order_purchase_timestamp"),
                table("customers", "customer_id", "customer_unique_id"),
                table("order_items", "order_item_id", "order_id", "price", "freight_value"),
                table("user_tags", "tag_id", "tag_code", "tag_name"),
                table("user_tag_map", "tag_id", "customer_unique_id"),
                table("support_tickets", "ticket_id", "priority", "status")), List.of(
                new SchemaContext.ForeignKey("orders", "customer_id", "customers", "customer_id"),
                new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id")), "", "");
        var catalog = mock(SchemaCatalog.class);
        when(catalog.full()).thenReturn(full);
        var byName = new LinkedHashMap<String, SchemaContext.Table>();
        full.tables().forEach(t -> byName.put(t.name(), t));
        when(catalog.tablesByName()).thenReturn(byName);
        var props = new AgentProperties();
        props.getSemantic().setEnabled(true);
        props.getRetrieval().setTopK(8);
        props.getRetrieval().setColumnPruningEnabled(true);
        props.getRetrieval().setColumnPruningMinColumns(1);
        var glossary = new GlossaryLoader(catalog);
        var values = new ValueRetriever(glossary);
        var registry = DimensionPolicyTest.registry();
        return new Fixture(props, List.of(new FullSchemaProvider(catalog, registry, null, props, values),
                new HybridSchemaProvider(catalog, new LexicalSchemaRetriever(glossary, catalog, props),
                        glossary, props, registry, null, values, new ColumnPruner(glossary))));
    }

    private static SchemaContext.Table table(String name, String... columns) {
        return new SchemaContext.Table(name, "", java.util.Arrays.stream(columns)
                .map(c -> new SchemaContext.Column(c, c.equals("price") || c.equals("freight_value") ? "numeric" : "text",
                        true, "", c.equals(columns[0]))).toList());
    }
}
