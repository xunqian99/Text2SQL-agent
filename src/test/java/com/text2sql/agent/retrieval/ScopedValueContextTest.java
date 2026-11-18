package com.text2sql.agent.retrieval;

import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import com.text2sql.agent.semantic.CountComparisonContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ScopedValueContextTest {
    @ParameterizedTest
    @ValueSource(strings = {"2018年的GMV中，新客和老客各贡献多少？", "平均金额高于100的订单", "价格低于10的商品",
            "工单优先级分布中有多少记录？", "查询高价值用户", "处理中有多少工单？"})
    void incidentalCharactersCannotBecomePriorityFilters(String question) {
        assertThat(values().findMatches(question, Set.of("support_tickets", "user_tags")))
                .noneMatch(v -> v.column().equals("priority"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"优先级为中", "中优先级的工单", "PRIORITY = 中", "紧急程度是中", "紧急程度为中"})
    void aNearbyFieldCueAuthorizesTheShortValue(String question) {
        assertThat(values().findMatches(question, Set.of("support_tickets")))
                .anyMatch(v -> v.column().equals("priority") && v.value().equals("2"));
    }

    @Test void enumerationKeepsDistinctValuesInsteadOfPickingOnePerColumn() {
        assertThat(values().findMatches("优先级为中、高和低的工单分别有多少？", Set.of("support_tickets")))
                .filteredOn(v -> v.column().equals("priority"))
                .extracting(ValueRetriever.ValueMatch::value).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test void rejectedAndAcceptedOccurrencesRetainTheirOwnOriginalOffsets() {
        String question = "İ与😀GMV中，优先级为中";
        var evidence = values().matchEvidence(question, Set.of("support_tickets"));
        assertThat(evidence).filteredOn(e -> e.entry().column().equals("priority") && e.entry().alias().equals("中"))
                .hasSize(2).anySatisfy(e -> {
                    assertThat(e.start()).isEqualTo(question.indexOf('中'));
                    assertThat(e.decision()).isEqualTo(ValueRetriever.Decision.SHORT_VALUE_CONTEXT_MISSING);
                }).anySatisfy(e -> {
                    assertThat(e.start()).isEqualTo(question.lastIndexOf('中'));
                    assertThat(question.substring(e.start(), e.end())).isEqualTo("中");
                    assertThat(e.decision()).isEqualTo(ValueRetriever.Decision.ACCEPTED);
                    assertThat(e.context().term()).isEqualTo("优先级");
                    assertThat(question.substring(e.context().start(), e.context().end())).isEqualTo("优先级");
                });
    }

    @Test void aLaterClauseCannotAuthorizeAnEarlierIncidentalCharacter() {
        assertThat(values().findMatches("GMV中，优先级为高", Set.of("support_tickets")))
                .filteredOn(v -> v.column().equals("priority"))
                .extracting(ValueRetriever.ValueMatch::value).containsExactly("3");
    }

    @Test void shortValueInsideALongerAcceptedLabelIsNotASecondFilter() {
        var evidence = values().matchEvidence("状态为处理中，优先级为高", Set.of("support_tickets"));
        assertThat(evidence).filteredOn(e -> e.entry().alias().equals("中") && e.entry().column().equals("priority"))
                .singleElement().satisfies(e -> assertThat(e.decision()).isEqualTo(ValueRetriever.Decision.INSIDE_LONGER_VALUE));
        assertThat(values().findMatches("状态为处理中，优先级为高", Set.of("support_tickets")))
                .noneMatch(v -> v.column().equals("priority") && v.value().equals("2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"新客标签的用户", "标签为新客的用户", "用户标签中的新客", "标签编码是新客"})
    void explicitTagQueriesKeepTheRealNewUserCode(String question) {
        assertThat(values().findMatches(question, Set.of("user_tags")))
                .anyMatch(v -> v.column().equals("tag_code") && v.value().equals("NEW_USER"));
    }

    @Test void dynamicCohortAndWrongTargetProduceDifferentRejectionEvidence() {
        assertThat(values().matchEvidence("新客的GMV", Set.of("user_tags")))
                .anyMatch(e -> e.entry().physicalValue().equals("NEW_USER")
                        && e.decision() == ValueRetriever.Decision.REQUIRED_CONTEXT_MISSING);
        assertThat(values().matchEvidence("新客标签", Set.of("orders")))
                .anyMatch(e -> e.entry().physicalValue().equals("NEW_USER")
                        && e.decision() == ValueRetriever.Decision.OUTSIDE_TARGET_TABLE);
    }

    @Test void countLayoutCannotResurrectRejectedValuesButStillAcceptsExplicitCodes() {
        var values = values();
        assertThat(values.countComparisonContract("新客和复购用户分别有多少个？", schema().tables()).decision())
                .isNotEqualTo(CountComparisonContract.Decision.MATCHED);
        assertThat(values.countComparisonContract("中和高分别有多少条？", schema().tables()).decision())
                .isNotEqualTo(CountComparisonContract.Decision.MATCHED);
        assertThat(values.countComparisonContract("优先级为中和高分别有多少条？", schema().tables()).bindings())
                .extracting(CountComparisonContract.Binding::value).containsExactly("2", "3");
        assertThat(values.countComparisonContract("新客标签和复购用户分别有多少个？", schema().tables()).bindings())
                .extracting(CountComparisonContract.Binding::value).containsExactly("NEW_USER", "REPEAT_BUYER");
        assertThat(values.countComparisonContract("NEW_USER和REPEAT_BUYER分别有多少个？", schema().tables()).bindings())
                .extracting(CountComparisonContract.Binding::source)
                .containsExactly(CountComparisonContract.Source.PHYSICAL_VALUE, CountComparisonContract.Source.PHYSICAL_VALUE);
    }

    @Test void pruningCannotReintroduceARejectedEnumViaRawSubstringMatching() {
        var catalog = catalog();
        var glossary = new GlossaryLoader(catalog);
        var table = schema().tables().stream().filter(t -> t.name().equals("support_tickets")).toList();
        var pruned = new ColumnPruner(glossary).prune(table, "GMV中有多少", Set.of("support_tickets"),
                List.of(), List.of(), List.of(), 1);
        assertThat(pruned.getFirst().columns()).extracting(SchemaContext.Column::name).doesNotContain("priority");
    }

    static ValueRetriever values() { return new ValueRetriever(new GlossaryLoader(catalog())); }

    static SchemaCatalog catalog() {
        var catalog = mock(SchemaCatalog.class);
        when(catalog.full()).thenReturn(schema());
        var byName = new LinkedHashMap<String, SchemaContext.Table>();
        schema().tables().forEach(t -> byName.put(t.name(), t));
        when(catalog.tablesByName()).thenReturn(byName);
        return catalog;
    }

    static SchemaContext schema() {
        return new SchemaContext(List.of(
                table("orders", "order_id", "customer_id", "order_status", "order_purchase_timestamp"),
                table("customers", "customer_id", "customer_unique_id"),
                table("order_items", "order_item_id", "order_id", "price", "freight_value"),
                table("user_tags", "tag_id", "tag_code", "tag_name"),
                table("user_tag_map", "tag_id", "customer_unique_id"),
                table("support_tickets", "ticket_id", "priority", "status")), List.of(
                new SchemaContext.ForeignKey("orders", "customer_id", "customers", "customer_id"),
                new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id")), "", "");
    }

    private static SchemaContext.Table table(String name, String... columns) {
        return new SchemaContext.Table(name, "", java.util.Arrays.stream(columns)
                .map(c -> new SchemaContext.Column(c, c.equals("price") || c.equals("freight_value") ? "numeric" : "text",
                        true, "", c.equals(columns[0]))).toList());
    }
}
