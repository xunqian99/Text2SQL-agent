package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.retrieval.*;
import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EntityPolicyTest {
    static final SchemaContext.Table CUSTOMERS = new SchemaContext.Table("customers", "", List.of(
            new SchemaContext.Column("customer_id", "text", false, "", true),
            new SchemaContext.Column("customer_unique_id", "text", true, "")));

    @ParameterizedTest
    @ValueSource(strings = {"每个客户的订单数", "顾客的消费次数", "买家总人数", "用户复购次数",
            "同一自然人的订单", "按 CUSTOMER_ID 分组", "customer_unique_id对应几个customer_id"})
    void declaresBothIdentitiesWithoutOverridingExplicitKeys(String question) {
        var registry = DimensionPolicyTest.registry();
        String text = registry.appendPolicies(question, List.of(CUSTOMERS), "existing metric");
        assertThat(text).startsWith("existing metric\n\nENTITY_POLICY customer_identity_v1")
                .contains("customers.customer_id", "customers.customer_unique_id", "用户明确指定", "不擅自改成实体级统计")
                .containsOnlyOnce("ENTITY_POLICY customer_identity_v1");
        assertThat(registry.all()).hasSize(25);
        assertThat(registry.entityPolicies()).hasSize(1);
    }

    @Test void neitherMissingTableNorEitherMissingColumnCanBeClaimedAvailable() {
        var registry = DimensionPolicyTest.registry();
        assertThat(registry.appendPolicies("客户订单数", List.of(), "old")).isEqualTo("old");
        for (var col : CUSTOMERS.columns()) {
            var pruned = new SchemaContext.Table("customers", "", List.of(col));
            assertThat(registry.appendPolicies("客户订单数", List.of(pruned), "old")).isEqualTo("old");
        }
    }

    @Test void unrelatedQuestionsAndEmptyInputPreserveExistingText() {
        var registry = DimensionPolicyTest.registry();
        for (String question : List.of("一共有多少订单", "按商品统计金额", "客服工单分类", "会员等级销售额")) {
            assertThat(registry.appendPolicies(question, List.of(CUSTOMERS), "metric\nline")).isEqualTo("metric\nline");
        }
        assertThat(registry.appendPolicies(null, List.of(CUSTOMERS), null)).isNull();
        assertThat(registry.appendPolicies("   ", List.of(CUSTOMERS), "old")).isEqualTo("old");
    }

    @Test void tableAndColumnIdentityIsCaseInsensitive() {
        var upper = new SchemaContext.Table("CUSTOMERS", "", CUSTOMERS.columns().stream()
                .map(c -> new SchemaContext.Column(c.name().toUpperCase(java.util.Locale.ROOT), "text", false, "")).toList());
        assertThat(DimensionPolicyTest.registry().appendPolicies("客户", List.of(upper), ""))
                .contains("ENTITY_POLICY customer_identity_v1");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fullAndHybridFinalPromptsCarryPolicyAndRespectFeatureSwitch(boolean fallback) {
        var props = new AgentProperties(); props.getSemantic().setEnabled(true);
        var full = new SchemaContext(List.of(CUSTOMERS), List.of(), "", "CREATE TABLE customers (...)");
        var catalog = mock(SchemaCatalog.class); when(catalog.full()).thenReturn(full);
        var retriever = mock(LexicalSchemaRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(new RetrievalResult(List.of("customers"),
                Map.of("customers", 1.0), Map.of("customers", List.of("alias:客户")), Set.of(), fallback));
        var glossary = mock(GlossaryLoader.class); when(glossary.get()).thenReturn(new Glossary(List.of(), Map.of()));
        var registry = DimensionPolicyTest.registry();
        var providers = List.<SchemaProvider>of(new FullSchemaProvider(catalog, registry, props),
                new HybridSchemaProvider(catalog, retriever, glossary, props, registry));
        for (var provider : providers) {
            String question = "每个客户的订单数量";
            var context = provider.provide(question);
            assertThat(new PromptTemplate(props).userPrompt(context, question))
                    .contains("ENTITY_POLICY customer_identity_v1", "customers.customer_unique_id");
            assertThat(context.tables()).isEqualTo(full.tables());
            props.getSemantic().setEnabled(false);
            assertThat(provider.provide(question).metricsText()).isBlank();
            props.getSemantic().setEnabled(true);
        }
        verify(catalog, atLeastOnce()).full();
        assertThat(full.metricsText()).isBlank();
    }
}
