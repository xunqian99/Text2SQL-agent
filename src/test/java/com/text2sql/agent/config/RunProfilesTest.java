package com.text2sql.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RunProfilesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesOnly.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class PropertiesOnly { }

    @Test
    void demoLoadsActualYamlWithBoundedCallsAndWithoutAutomaticEvaluation() {
        runner.withPropertyValues("spring.profiles.active=demo").run(context -> {
            assertThat(context).hasNotFailed();
            var p = context.getBean(AgentProperties.class);
            assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(p.getEval().isEnabled()).isFalse();
            assertThat(p.getRetrieval().isEnabled()).isTrue();
            assertThat(p.getSelfCorrection().isEnabled()).isTrue();
            assertThat(p.getResultChecker().isEnabled()).isTrue();
            assertThat(p.getConversation().isEnabled()).isTrue();
            assertThat(p.getRequestBudget().isEnabled()).isTrue();
            assertThat(p.getRequestBudget().getTimeoutMs()).isEqualTo(15000);
            assertThat(p.getRequestBudget().getMaxModelCalls()).isEqualTo(2);
            assertThat(p.getCache().isEnabled()).isFalse();
            assertThat(p.getPersistence().isEnabled()).isFalse();
            assertThat(p.getToolUse().isEnabled()).isFalse();
            assertThat(p.getRouting().isEnabled()).isFalse();
        });
    }

    @Test
    void baselineOverridesEarlierDemoFlagsButKeepsSqlSafeguards() {
        runner.withPropertyValues("spring.profiles.active=demo,baseline").run(context -> {
            assertThat(context).hasNotFailed();
            var p = context.getBean(AgentProperties.class);
            assertThat(p.getRetrieval().isEnabled()).isFalse();
            assertThat(p.getSemantic().isEnabled()).isFalse();
            assertThat(p.getFewshot().isEnabled()).isFalse();
            assertThat(p.getClarification().isEnabled()).isFalse();
            assertThat(p.getSelfCorrection().isEnabled()).isFalse();
            assertThat(p.getResultChecker().isEnabled()).isFalse();
            assertThat(p.getConversation().isEnabled()).isFalse();
            assertThat(p.getGuard().isExplainCostGuardEnabled()).isTrue();
            assertThat(p.getGuard().isRejectCartesianProduct()).isTrue();
            assertThat(p.getDb().getMaxRows()).isEqualTo(200);
            assertThat(p.getDb().getQueryTimeoutSeconds()).isEqualTo(10);
            assertThat(p.getRequestBudget().isEnabled()).isTrue();
        });
    }

    @Test
    void explicitOverridesRemainVisibleRatherThanTreatingProfileAsAConfigurationCertificate() {
        runner.withPropertyValues("spring.profiles.active=demo", "agent.conversation.enabled=false",
                "agent.request-budget.max-model-calls=1").run(context -> {
            assertThat(context).hasNotFailed();
            var p = context.getBean(AgentProperties.class);
            assertThat(p.getConversation().isEnabled()).isFalse();
            assertThat(p.getRequestBudget().getMaxModelCalls()).isEqualTo(1);
        });
    }
}
