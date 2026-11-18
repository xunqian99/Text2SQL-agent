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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DimensionPolicyTest {
    static MetricRegistry registry() { var registry = new MetricRegistry(); registry.load(); return registry; }
    static final SchemaContext.Table PRODUCTS = new SchemaContext.Table("products", "", List.of(
            new SchemaContext.Column("product_id", "text", false, ""),
            new SchemaContext.Column("product_category_name", "text", true, "")));

    @ParameterizedTest
    @ValueSource(strings = {"每个商品类目的订单数量是多少？", "按品类统计商品金额", "各商品分类的商品数", "商品类目统计包含缺失和未知类目"})
    void groupingAliasesCarryDefaultAndExplicitOverrideTogether(String question) {
        var registry = registry();
        String context = registry.appendDimensionPolicies(question, List.of(PRODUCTS), "existing metric");
        assertThat(context).startsWith("existing metric\n\nDIMENSION_POLICY product_category_nulls_v1");
        assertThat(context).contains("products.product_category_name IS NOT NULL", "用户明确要求", "不附加上述过滤", "不改变未按该维度分组的总数或总额");
        assertThat(registry.all()).hasSize(26); // Includes the independently checked late-delivery metric.
    }

    @Test void absentTableOrPrunedColumnCannotBeReintroducedByPolicyText() {
        var registry = registry();
        var pruned = new SchemaContext.Table("products", "", List.of(new SchemaContext.Column("product_id", "text", false, "")));
        assertThat(registry.appendDimensionPolicies("每个商品类目", List.of(), "metric")).isEqualTo("metric");
        assertThat(registry.appendDimensionPolicies("每个商品类目", List.of(pruned), "metric")).isEqualTo("metric");
    }

    @Test void totalsAndUnrelatedQueriesRemainByteIdentical() {
        var registry = registry();
        for (String question : List.of("一共有多少商品？", "订单总金额是多少？", "每个客户的订单数量是多少？", "客服工单分类有多少个？")) {
            assertThat(registry.appendDimensionPolicies(question, List.of(PRODUCTS), "original\nmetric")).isEqualTo("original\nmetric");
        }
        assertThat(registry.appendDimensionPolicies(null, List.of(PRODUCTS), "original")).isEqualTo("original");
    }

    @Test void finalPromptIncludesPolicyOnlyWhenSemanticFeatureIsEnabled() {
        var props = new AgentProperties(); props.getSemantic().setEnabled(true);
        var full = new SchemaContext(List.of(PRODUCTS), List.of(), "", "CREATE TABLE products (...)");
        var catalog = mock(SchemaCatalog.class); when(catalog.full()).thenReturn(full);
        var provider = new FullSchemaProvider(catalog, registry(), props);
        var context = provider.provide("按商品类目统计订单数，包含未知类目");
        assertThat(new PromptTemplate(props).userPrompt(context, "按商品类目统计订单数，包含未知类目"))
                .contains("DIMENSION_POLICY product_category_nulls_v1", "以用户要求为准");
        assertThat(context.tables()).isEqualTo(full.tables());
        props.getSemantic().setEnabled(false);
        assertThat(provider.provide("每个商品类目").metricsText()).isBlank();
    }
}
