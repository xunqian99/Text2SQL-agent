package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.FullSchemaProvider;
import com.text2sql.agent.retrieval.SchemaCatalog;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporalMetricScopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"2023和2024年各月GMV及同比增长率", "2021年成交总额YoY",
            "2025年商品交易总额同比增长率"})
    void gmvGrowthRequiresItsOwnMetricIdentityAndAvailableTables(String question) {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.findApplicable(question, Set.of("orders", "order_items")))
                .extracting(Metric::name).contains("yoy_growth");
        assertThat(registry.findApplicable(question, Set.of("orders")))
                .extracting(Metric::name).doesNotContain("yoy_growth");
    }

    @ParameterizedTest
    @ValueSource(strings = {"订单量同比增长率", "客户数YoY", "退款金额同比增长率", "同比增长率是多少"})
    void otherMeasuresRetainTheRejectedCandidateAndMissingContextReason(String question) {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.findMentioned(question)).extracting(Metric::name).doesNotContain("yoy_growth");
        assertThat(registry.matchEvidence(question)).filteredOn(e -> e.metric().name().equals("yoy_growth"))
                .singleElement().satisfies(evidence -> {
                    assertThat(evidence.decision()).isEqualTo(MetricRegistry.MatchDecision.REQUIRED_CONTEXT_MISSING);
                    assertThat(evidence.requiredContextTerm()).isNull();
                });
    }

    @Test
    void absoluteGmvGrowthDoesNotSelectThePercentageMetric() {
        assertThat(DimensionPolicyTest.registry().findMentioned("2024年GMV相比2023年同比增长金额是多少？"))
                .extracting(Metric::name).contains("gmv_with_freight").doesNotContain("yoy_growth");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2023年首单用户在2024年的留存率，按首单月份统计",
            "2022年各月GMV和相对2021年同月的同比增长率"})
    void finalMessageKeepsRequestYearsWithoutInjectingOldFixedDates(String question) {
        var props = new AgentProperties(); props.getSemantic().setEnabled(true);
        var tables = List.of(new SchemaContext.Table("orders", null, List.of()),
                new SchemaContext.Table("customers", null, List.of()),
                new SchemaContext.Table("order_items", null, List.of()));
        var catalog = mock(SchemaCatalog.class);
        when(catalog.full()).thenReturn(new SchemaContext(tables, List.of(), "", ""));
        var context = new FullSchemaProvider(catalog, DimensionPolicyTest.registry(), props).provide(question);
        assertThat(context.metricsText()).doesNotContain("2017", "2018", "gmv_2017", "gmv_2018");
        assertThat(new PromptTemplate(props).userPrompt(context, question)).endsWith(question + "\n");
    }
}
