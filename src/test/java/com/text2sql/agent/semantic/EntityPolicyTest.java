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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EntityPolicyTest {
    static final SchemaContext.Table CUSTOMERS = new SchemaContext.Table("customers", "", List.of(
            new SchemaContext.Column("customer_id", "text", false, "", true),
            new SchemaContext.Column("customer_unique_id", "text", true, "")));

    @Test void populationScopeIsExplicitAndSeparateFromEntityActivityAndUserOverrides() {
        var policy = DimensionPolicyTest.registry().entityPolicies().getFirst();
        assertThat(policy.populationCountUnit()).isEqualTo(EntityPolicy.PopulationCountUnit.ROW_KEY);
        assertThat(policy.render()).contains("populationCountUnit=ROW_KEY", "默认统计存储记录", "列名应体现记录单位",
                "明确问自然人、人数", "按业务实体身份分组", "分别计算", "不覆盖已注册指标的分子分母", "不授权改变过滤或JOIN覆盖");
        assertThat(policy.render()).contains("沿该事实的关联键定位customers.customer_id对应记录",
                "customers.customer_unique_id用于身份去重", "COUNT(DISTINCT 事实主键)只能消除同组重复",
                "不能修复错误属性分组或金额放大", "用户明确要求跨记录属性分布");
        var entityDefault = new EntityPolicy("other", "accounts", "row_id", "person_id",
                EntityPolicy.PopulationCountUnit.ENTITY_IDENTITY, List.of("账户"), "sample");
        assertThat(entityDefault.render()).contains("COUNT(DISTINCT accounts.person_id)")
                .doesNotContain("默认统计存储记录", "customers.");
        assertThatThrownBy(() -> new EntityPolicy("other", "accounts", "row_id", "person_id",
                null, List.of("账户"), "sample")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void archivedEntityKeysAliasesAndOtherPoliciesStayIntact() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var before = mapper.readTree(getClass().getResourceAsStream("/contracts/metrics-before-result-contract.yml"));
        var after = mapper.readTree(getClass().getResourceAsStream("/schema/metrics.yml"));
        assertThat(after.path("dimensionPolicies")).isEqualTo(before.path("dimensionPolicies"));
        var oldPolicy = (com.fasterxml.jackson.databind.node.ObjectNode) before.path("entityPolicies").get(0);
        var newPolicy = (com.fasterxml.jackson.databind.node.ObjectNode) after.path("entityPolicies").get(0);
        assertThat(after.path("entityPolicies").size()).isEqualTo(1);
        assertThat(newPolicy.path("name").asText()).isEqualTo("customer_identity_v2");
        assertThat(newPolicy.path("populationCountUnit").asText()).isEqualTo("ROW_KEY");
        assertThat(newPolicy.path("notes").asText()).contains("产品默认", "每州内独立去重", "不能把各州人数相加");
        oldPolicy.remove(List.of("name", "notes"));
        newPolicy.remove(List.of("name", "notes", "populationCountUnit"));
        assertThat(newPolicy).isEqualTo(oldPolicy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"每个客户的订单数", "顾客的消费次数", "买家总人数", "用户复购次数",
            "同一自然人的订单", "按 CUSTOMER_ID 分组", "customer_unique_id对应几个customer_id"})
    void declaresBothIdentitiesWithoutOverridingExplicitKeys(String question) {
        var registry = DimensionPolicyTest.registry();
        String text = registry.appendPolicies(question, List.of(CUSTOMERS), "existing metric");
        assertThat(text).startsWith("existing metric\n\nENTITY_POLICY customer_identity_v2")
                .contains("customers.customer_id", "customers.customer_unique_id", "用户明确指定", "不擅自改成实体级统计")
                .containsOnlyOnce("ENTITY_POLICY customer_identity_v2");
        assertThat(registry.all()).hasSize(26); // Includes the independently checked late-delivery metric.
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
                .contains("ENTITY_POLICY customer_identity_v2");
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
                    .contains("ENTITY_POLICY customer_identity_v2", "customers.customer_unique_id");
            assertThat(context.tables()).isEqualTo(full.tables());
            props.getSemantic().setEnabled(false);
            assertThat(provider.provide(question).metricsText()).isBlank();
            props.getSemantic().setEnabled(true);
        }
        verify(catalog, atLeastOnce()).full();
        assertThat(full.metricsText()).isBlank();
    }
}
