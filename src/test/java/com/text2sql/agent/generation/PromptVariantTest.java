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
    @Test void countGrainVersionChangesOnlyRule10FromFrozenP13() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/p13-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes=in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("7a32eb2445d67237f712f9a818b29b925762d9c64c310ed1a93c5c7e0d975448");
            String before=new String(bytes,StandardCharsets.UTF_8),after=new PromptTemplate().systemPrompt();
            assertThat(withoutCountRule(after)).isEqualTo(withoutCountRule(before));
            assertThat(after).doesNotContain("如订单与评价", "只有在多对多或一对多连接")
                    .contains("仅有外键不保证子表关联键唯一", "评价记录数保留每条评价，订单数只计一次",
                            "组合主键须保留全部分量", "不以是否存在JOIN为前提");
        }
    }

    @Test void contractVersionChangesOnlyCountUnitTimeAndComparisonRulesFromArchivedP9() throws Exception {
        var template = new PromptTemplate();
        assertThat(template.version()).isEqualTo("p14-count-grain-v14");
        try (var in = getClass().getResourceAsStream("/prompts/p9-system.txt")) {
            assertThat(in).isNotNull();
            String archived = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archived.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("f50464555aceac53dc657613a5887c1a72128ab18ef66a3d41c5413fb2419f0e");
            String current = template.systemPrompt();
            assertThat(withoutCountRule(current).substring(0, withoutCountRule(current).indexOf("12. 比例")))
                    .isEqualTo(withoutCountRule(archived).substring(0, withoutCountRule(archived).indexOf("12. 比例")));
            assertThat(current.substring(current.indexOf("14. 时间窗口"), current.indexOf("17. ")))
                    .isEqualTo(archived.substring(archived.indexOf("14. 时间窗口"), archived.indexOf("17. ")));
            assertThat(current.substring(current.indexOf("18. 复杂"))).isEqualTo(archived.substring(archived.indexOf("18. 复杂")));
            assertThat(current).contains("用户明确指定的单位 > 当前METRIC的默认输出单位", "每个维度值一行", "用户明确要求横表")
                    .doesNotContain("优先使用 COUNT(*) FILTER", "且未显式提及百分比");
        }
    }

    @Test void countComparisonAndAnnualVersionChangeOnlyRules10And13And17FromArchivedP10() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/p10-system.txt")) {
            assertThat(in).isNotNull();
            String before = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(before.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("1e166d2c5a9aff16bfd7ba02199236e1d5627cfd19c38d4e7efdcae1aa4b21c8");
            String after = new PromptTemplate().systemPrompt();
            assertThat(withoutCountRule(after).substring(0, withoutCountRule(after).indexOf("13. 按时间")))
                    .isEqualTo(withoutCountRule(before).substring(0, withoutCountRule(before).indexOf("13. 按时间")));
            assertThat(after.substring(after.indexOf("14. 时间窗口"), after.indexOf("17. 对比")))
                    .isEqualTo(before.substring(before.indexOf("14. 时间窗口"), before.indexOf("17. 对比")));
            assertThat(after.substring(after.indexOf("18. 复杂"))).isEqualTo(before.substring(before.indexOf("18. 复杂")));
        }
    }

    @Test void annualAndCountVersionsChangeOnlyRules10And13FromArchivedP11() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/p11-system.txt")) {
            assertThat(in).isNotNull();
            String before = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(before.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("eb1b028e0bb827d12ccbdcbaf4e7ed55965595b3e9ccc6cf4a31e7b45194bbd8");
            String after = new PromptTemplate().systemPrompt();
            assertThat(withoutCountRule(after).substring(0, withoutCountRule(after).indexOf("13. 按时间")))
                    .isEqualTo(withoutCountRule(before).substring(0, withoutCountRule(before).indexOf("13. 按时间")));
            assertThat(after.substring(after.indexOf("14. 时间窗口"))).isEqualTo(before.substring(before.indexOf("14. 时间窗口")));
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
        assertThat(candidate.version()).isEqualTo("p14-count-grain-v14-no-explicit-steps-v1");
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

    private static String withoutCountRule(String prompt) {
        return prompt.substring(0,prompt.indexOf("10. 计数")) + prompt.substring(prompt.indexOf("11. 不要"));
    }
}
