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
    @Test void defaultRetainsArchivedSystemBytes() throws Exception {
        var template = new PromptTemplate();
        assertThat(template.version()).isEqualTo("p9-mdl-alignment-v9");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(template.systemPrompt().getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("f50464555aceac53dc657613a5887c1a72128ab18ef66a3d41c5413fb2419f0e");
    }

    @Test void candidateChangesOnlyTheArchived259UnitSection() {
        String full = new PromptTemplate().systemPrompt();
        var props = new AgentProperties();
        props.getPrompt().setExplicitReasoningEnabled(false);
        var candidate = new PromptTemplate(props);
        String shortened = candidate.systemPrompt();
        assertThat(shortened.length()).isEqualTo(2536);
        assertThat(full.length() - shortened.length()).isEqualTo(259);
        assertThat(shortened).isEqualTo(full.replace(full.substring(full.indexOf("思考与推理步骤"), full.indexOf("硬性规则")), ""));
        assertThat(shortened.substring(shortened.indexOf("硬性规则")))
                .isEqualTo(full.substring(full.indexOf("硬性规则")));
        assertThat(candidate.version()).isEqualTo("p9-mdl-alignment-v9-no-explicit-steps-v1");
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
