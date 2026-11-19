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
    @Test void archivedP14ChangesOnlyRule10FromFrozenP13() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/p13-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes=in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("7a32eb2445d67237f712f9a818b29b925762d9c64c310ed1a93c5c7e0d975448");
            String before=new String(bytes,StandardCharsets.UTF_8),after=frozenP14();
            assertThat(withoutCountRule(after)).isEqualTo(withoutCountRule(before));
            assertThat(after).doesNotContain("如订单与评价", "只有在多对多或一对多连接")
                    .contains("仅有外键不保证子表关联键唯一", "评价记录数保留每条评价，订单数只计一次",
                            "组合主键须保留全部分量", "不以是否存在JOIN为前提");
        }
    }

    @Test void archivedP14PreservesUnaffectedRulesFromP9() throws Exception {
        var template = new PromptTemplate();
        assertThat(template.version()).isEqualTo("p21-period-ranking-v21");
        try (var in = getClass().getResourceAsStream("/prompts/p9-system.txt")) {
            assertThat(in).isNotNull();
            String archived = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archived.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("f50464555aceac53dc657613a5887c1a72128ab18ef66a3d41c5413fb2419f0e");
            String current = frozenP14();
            assertThat(withoutCountRule(current).substring(0, withoutCountRule(current).indexOf("12. 比例")))
                    .isEqualTo(withoutCountRule(archived).substring(0, withoutCountRule(archived).indexOf("12. 比例")));
            assertThat(current.substring(current.indexOf("14. 时间窗口"), current.indexOf("17. ")))
                    .isEqualTo(archived.substring(archived.indexOf("14. 时间窗口"), archived.indexOf("17. ")));
            assertThat(current.substring(current.indexOf("18. 复杂"))).isEqualTo(archived.substring(archived.indexOf("18. 复杂")));
            assertThat(current).contains("用户明确指定的单位 > 当前METRIC的默认输出单位", "每个维度值一行", "用户明确要求横表")
                    .doesNotContain("优先使用 COUNT(*) FILTER", "且未显式提及百分比");
        }
    }

    @Test void archivedP14PreservesUnaffectedRulesFromP10() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/p10-system.txt")) {
            assertThat(in).isNotNull();
            String before = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(before.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("1e166d2c5a9aff16bfd7ba02199236e1d5627cfd19c38d4e7efdcae1aa4b21c8");
            String after = frozenP14();
            assertThat(withoutCountRule(after).substring(0, withoutCountRule(after).indexOf("13. 按时间")))
                    .isEqualTo(withoutCountRule(before).substring(0, withoutCountRule(before).indexOf("13. 按时间")));
            assertThat(after.substring(after.indexOf("14. 时间窗口"), after.indexOf("17. 对比")))
                    .isEqualTo(before.substring(before.indexOf("14. 时间窗口"), before.indexOf("17. 对比")));
            assertThat(after.substring(after.indexOf("18. 复杂"))).isEqualTo(before.substring(before.indexOf("18. 复杂")));
        }
    }

    @Test void archivedP14PreservesUnaffectedRulesFromP11() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/p11-system.txt")) {
            assertThat(in).isNotNull();
            String before = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(before.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo("eb1b028e0bb827d12ccbdcbaf4e7ed55965595b3e9ccc6cf4a31e7b45194bbd8");
            String after = frozenP14();
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
        assertThat(candidate.version()).isEqualTo("p21-period-ranking-v21-no-explicit-steps-v1");
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

    @Test void archivedP15EntityCoverageBatchChangesOnlyRule9FromFrozenP14() throws Exception {
        String before = frozenP14(), after = frozenP15();
        assertThat(withoutEntityRule(after)).isEqualTo(withoutEntityRule(before));
        assertThat(after).contains("名称是展示属性，不保证唯一", "SUM(DISTINCT 金额)",
                "会员数包括无订单会员", "只有用户明确要求有订单/有商品的会员时才限缩",
                "右表条件放ON或对应聚合内", "没有价格时均价为NULL");
    }

    @Test void archivedP16ChangesOnlyRule9WithoutSpreadingTheMemberExample() throws Exception {
        String before = frozenP15(), after;
        try (var in = getClass().getResourceAsStream("/prompts/p16-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes = in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("8d6fb246e1c38553c877c72c5b9955d556fdbc78c5a80ca8dd08c11e189d5b8e");
            after = new String(bytes, StandardCharsets.UTF_8);
        }
        assertThat(withoutEntityRule(after)).isEqualTo(withoutEntityRule(before));
        assertThat(after).contains("内部去重/分组键不等于输出列", "投影仍遵循规则8",
                "不能用SUM(DISTINCT 金额)", "每个指标按用户要求与其业务契约分别确定覆盖范围",
                "只有需要保留未匹配对象时使用LEFT JOIN", "没有明细时均价为NULL，不补0")
                .doesNotContain("统计标签覆盖会员数及其商品均价时", "会员数包括无订单会员");
        assertThat(after.length()).isLessThan(before.length());
    }

    @Test void archivedP17KeepsP15RulesOutsideRule13AndRejectsP16EntityChanges() throws Exception {
        String current = frozenP17(), before = frozenP15();
        assertThat(withoutTimeRule(current)).isEqualTo(withoutTimeRule(before));
        assertThat(current).contains("会员数包括无订单会员", "统计标签覆盖会员数及其商品均价时");
    }

    @Test void archivedP18ChangesOnlyRule8AndPreservesInternalEntityIdentity() throws Exception {
        String current = frozenP18(), before = frozenP17();
        assertThat(withoutProjectionRule(current)).isEqualTo(withoutProjectionRule(before));
        assertThat(current).contains("默认用名称作为该维度的一个输出列，不同时附加ID",
                "内部仍按完整实体主键区分", "只有明确按名称汇总时才合并", "名称不可用时输出当前已有标识列",
                "用户明确指定维度名称、编号、编码或输出列时", "保留未匹配维表的事实及NULL名称",
                "重复维表记录或名称过滤改变统计覆盖与金额", "状态、州代码、分类值等本身有业务含义的维度沿用原值")
                .doesNotContain("优先使用主表自身的标识列分组与输出");
    }

    @Test void requestScopeBatchChangesOnlyRules7And8FromFrozenP18() throws Exception {
        String current = frozenP19(), before = frozenP18();
        assertThat(withoutScopeAndProjection(current)).isEqualTo(withoutScopeAndProjection(before));
        assertThat(current).contains("事实筛选必须有用户请求或当前业务契约的依据",
                "明确请求或业务契约已有的过滤继续执行", "schema列出某状态枚举，不代表问题要求只统计该状态",
                "用户未明确要求名称时，默认使用当前事实或实体表已有的标识列",
                "筛选条件、分组身份和中间计算字段不自动成为输出列",
                "只问两期差额或增长率时，不附加两期原始数值", "明确要求两期数值及差额/增长率时才全部输出",
                "内部仍按完整实体主键区分", "保留未匹配事实及NULL名称")
                .doesNotContain("默认用名称作为该维度的一个输出列");
    }

    @Test void archivedCalendarWindowBatchPreservesEveryOtherSystemRule() throws Exception {
        String current = frozenP20(), before = frozenP19();
        assertThat(withoutWindowRule(current)).isEqualTo(withoutWindowRule(before));
    }

    @Test void periodAndRankingBatchChangesOnlyRules8And13() throws Exception {
        String current = new PromptTemplate().systemPrompt(), before = frozenP20();
        assertThat(withoutTimeRule(withoutProjectionRule(current)))
                .isEqualTo(withoutTimeRule(withoutProjectionRule(before)));
        assertThat(new PromptTemplate().version()).isEqualTo("p21-period-ranking-v21");
    }

    @Test void timeDimensionDefaultsAreDistinctFromExplicitDisplayOverrides() {
        String prompt = new PromptTemplate().systemPrompt();
        String rule = prompt.substring(prompt.indexOf("13. 按时间"), prompt.indexOf("14. 时间窗口"));
        assertThat(rule).contains("DATE_TRUNC('quarter', 时间列)", "不仅用季度编号合并不同年份",
                "EXTRACT(DOW FROM 时间列)::int", "周日0、周一1至周六6",
                "用户明确要求ISO编号", "或中文名称时按要求输出", "用户明确要求年/季度编号或标签时按要求输出");
    }

    private static String frozenP15() throws Exception {
        try (var in = PromptVariantTest.class.getResourceAsStream("/prompts/p15-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes = in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("23608183c2f8f3b03e2c6c948aa5963d3a93a24317590102e8f5894ab6893c71");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String frozenP17() throws Exception {
        try (var in = PromptVariantTest.class.getResourceAsStream("/prompts/p17-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes = in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("2886da41d0dd92b3bf17ac066e81550f907d290ef97fd2e11d81820e79277b0c");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String frozenP18() throws Exception {
        try (var in = PromptVariantTest.class.getResourceAsStream("/prompts/p18-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes = in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("08ce3688f049865aaec558c2ef1aa00b212ad5ce006697376f4b33ba34ebd2a0");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String frozenP19() throws Exception {
        try (var in = PromptVariantTest.class.getResourceAsStream("/prompts/p19-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes = in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("4693860a86ae7986437f3996fa6d0f002501de32695e2eb0623a198d09c1a834");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String frozenP20() throws Exception {
        try (var in = PromptVariantTest.class.getResourceAsStream("/prompts/p20-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes = in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("89c906efe61c9e0d8b0c8737eb6a5019dfd94618fe991455b2de9fd3a5701882");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String withoutWindowRule(String prompt) {
        return prompt.substring(0, prompt.indexOf("14. 时间窗口")) + prompt.substring(prompt.indexOf("15. 如果"));
    }

    private static String withoutScopeAndProjection(String prompt) {
        return prompt.substring(0, prompt.indexOf("7. 如果")) + prompt.substring(prompt.indexOf("9. 聚合"));
    }

    private static String withoutProjectionRule(String prompt) {
        return prompt.substring(0, prompt.indexOf("8. SELECT")) + prompt.substring(prompt.indexOf("9. 聚合"));
    }

    private static String frozenP14() throws Exception {
        try (var in = PromptVariantTest.class.getResourceAsStream("/prompts/p14-system.txt")) {
            assertThat(in).isNotNull();
            byte[] bytes = in.readAllBytes();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo("7d8ab50e6c398e445cd37d74542ca05f4a902ad557002ae7f8a145b9b406eb81");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String withoutEntityRule(String prompt) {
        return prompt.substring(0, prompt.indexOf("9. 聚合")) + prompt.substring(prompt.indexOf("10. 计数"));
    }

    private static String withoutTimeRule(String prompt) {
        return prompt.substring(0, prompt.indexOf("13. 按时间")) + prompt.substring(prompt.indexOf("14. 时间窗口"));
    }

    private static String withoutCountRule(String prompt) {
        return prompt.substring(0,prompt.indexOf("10. 计数")) + prompt.substring(prompt.indexOf("11. 不要"));
    }
}
