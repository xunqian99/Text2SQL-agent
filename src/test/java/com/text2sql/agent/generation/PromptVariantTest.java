package com.text2sql.agent.generation;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptVariantTest {
    @Test void contractVersionChangesOnlyUnitAndComparisonRulesFromArchivedP9() throws Exception {
        var template = new PromptTemplate();
        assertThat(template.version()).isEqualTo("p11-result-contract-v11");
        try (var in = getClass().getResourceAsStream("/prompts/p9-system.txt")) {
            assertThat(in).isNotNull();
            String archived = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archived.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("f50464555aceac53dc657613a5887c1a72128ab18ef66a3d41c5413fb2419f0e");
            String current = template.systemPrompt();
            assertThat(current.substring(0, current.indexOf("12. 比例"))).isEqualTo(archived.substring(0, archived.indexOf("12. 比例")));
            assertThat(current.substring(current.indexOf("13. 按时间"), current.indexOf("17. ")))
                    .isEqualTo(archived.substring(archived.indexOf("13. 按时间"), archived.indexOf("17. ")));
            assertThat(current.substring(current.indexOf("18. 复杂"))).isEqualTo(archived.substring(archived.indexOf("18. 复杂")));
            assertThat(current).contains("用户明确指定的单位 > 当前METRIC的默认输出单位", "每个维度值一行", "用户明确要求横表")
                    .doesNotContain("优先使用 COUNT(*) FILTER", "且未显式提及百分比");
        }
    }

    @Test void countComparisonVersionChangesOnlyRule17FromArchivedP10() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/p10-system.txt")) {
            assertThat(in).isNotNull();
            String before = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(before.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("1e166d2c5a9aff16bfd7ba02199236e1d5627cfd19c38d4e7efdcae1aa4b21c8");
            String after = new PromptTemplate().systemPrompt();
            assertThat(after.substring(0, after.indexOf("17. 对比"))).isEqualTo(before.substring(0, before.indexOf("17. 对比")));
            assertThat(after.substring(after.indexOf("18. 复杂"))).isEqualTo(before.substring(before.indexOf("18. 复杂")));
        }
    }

    @Test void candidateChangesOnlyTheArchived259UnitSection() {
        String full = new PromptTemplate().systemPrompt();
        var props = new AgentProperties();
        props.getPrompt().setExplicitReasoningEnabled(false);
        var candidate = new PromptTemplate(props);
        String shortened = candidate.systemPrompt();
        assertThat(full.length() - shortened.length()).isEqualTo(259);
        assertThat(shortened).isEqualTo(full.replace(full.substring(full.indexOf("思考与推理步骤"), full.indexOf("硬性规则")), ""));
        assertThat(shortened.substring(shortened.indexOf("硬性规则")))
                .isEqualTo(full.substring(full.indexOf("硬性规则")));
        assertThat(candidate.version()).isEqualTo("p11-result-contract-v11-no-explicit-steps-v1");
        var schema = new SchemaContext(List.of(), List.of(), "画像", "CREATE TABLE orders(order_id text);");
        assertThat(candidate.userPrompt(schema, "订单有多少？")).isEqualTo(new PromptTemplate().userPrompt(schema, "订单有多少？"));
        assertThat(candidate.correctionPrompt(schema, "订单有多少？", "SELECT 0", "请修正"))
                .isEqualTo(new PromptTemplate().correctionPrompt(schema, "订单有多少？", "SELECT 0", "请修正"));
        assertThat(props.getLlm().getReasoningEffort()).isEqualTo("provider-default");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void springBindsTheSwitchAndInjectsTheSameProperties(boolean enabled) {
        var props = new AgentProperties();
        new Binder(new MapConfigurationPropertySource(Map.of("agent.prompt.explicit-reasoning-enabled", enabled)))
                .bind("agent", Bindable.ofInstance(props));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentProperties.class, () -> props);
            context.register(PromptTemplate.class);
            context.refresh();
            var template = context.getBean(PromptTemplate.class);
            assertThat(props.getPrompt().isExplicitReasoningEnabled()).isEqualTo(enabled);
            assertThat(template.systemPrompt().contains("思考与推理步骤")).isEqualTo(enabled);
        }
    }

    @Test void invalidBooleanFailsInsteadOfSilentlySelectingDefault() {
        var binder = new Binder(new MapConfigurationPropertySource(
                Map.of("agent.prompt.explicit-reasoning-enabled", "typo")));
        assertThatThrownBy(() -> binder.bind("agent", Bindable.ofInstance(new AgentProperties())))
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }
}
