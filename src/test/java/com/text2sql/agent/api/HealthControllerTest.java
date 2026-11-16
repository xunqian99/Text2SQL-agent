package com.text2sql.agent.api;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {
    private final AgentProperties properties = new AgentProperties();
    private final LlmSqlGenerator generator = mock(LlmSqlGenerator.class);
    private final SchemaProvider schemas = mock(SchemaProvider.class);
    private final MockEnvironment environment = new MockEnvironment();

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                new HealthController(generator, schemas, properties, environment)).build();
    }

    private void schemaAvailable() {
        when(schemas.fullSchema()).thenReturn(new SchemaContext(
                List.of(new SchemaContext.Table("orders", "", List.of())), List.of(), "", ""));
    }

    @Test
    void configuredHealthIsExplicitlyNotAPaidOrLiveDatabaseProbe() throws Exception {
        schemaAvailable();
        when(generator.isGloballyConfigured()).thenReturn(true);
        properties.getLlm().setApiKey("secret-test-key");
        properties.getLlm().setBaseUrl("https://secret-endpoint.invalid");
        properties.getPersistence().setPassword("secret-db-password");
        properties.getRetrieval().setEnabled(true);
        properties.getRequestBudget().setEnabled(true);
        mvc().perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.schemaTableCount").value(1))
                .andExpect(jsonPath("$.providerVerified").value(false))
                .andExpect(jsonPath("$.databaseLiveVerified").value(false))
                .andExpect(jsonPath("$.capabilities.retrieval").value(true))
                .andExpect(jsonPath("$.limits.requestBudgetEnabled").value(true))
                .andExpect(content().string(not(containsString("secret-"))));
        verify(generator).isGloballyConfigured();
        verifyNoMoreInteractions(generator);
        verify(schemas).fullSchema();
        verifyNoMoreInteractions(schemas);
    }

    @Test
    void cachedByokClientDoesNotMakeGlobalReadinessTrue() throws Exception {
        schemaAvailable();
        when(generator.isConfigured()).thenReturn(true);
        when(generator.isGloballyConfigured()).thenReturn(false);
        mvc().perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.llmConfigured").value(false));
        verify(generator, never()).isConfigured();
    }

    @Test
    void schemaExceptionIsDegradedWithoutLeakingException() throws Exception {
        when(generator.isGloballyConfigured()).thenReturn(true);
        when(schemas.fullSchema()).thenThrow(new IllegalStateException("password=secret-test-key"));
        mvc().perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.reason").value("SCHEMA_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("secret-test-key"))));
    }

    @Test
    void emptySchemaCannotBeReady() throws Exception {
        when(generator.isGloballyConfigured()).thenReturn(true);
        when(schemas.fullSchema()).thenReturn(new SchemaContext(List.of(), List.of(), "", ""));
        mvc().perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.schemaAvailable").value(false))
                .andExpect(jsonPath("$.ready").value(false));
    }

    @ParameterizedTest
    @CsvSource({"demo,false,false,demo", "baseline,false,false,baseline",
            "demo,true,true,gold-self-check", "demo,true,false,evaluation"})
    void modeUsesEvaluationFlagsBeforeProfileLabel(String profile, boolean eval, boolean dry, String mode)
            throws Exception {
        schemaAvailable();
        environment.setActiveProfiles(profile);
        properties.getEval().setEnabled(eval);
        properties.getEval().setDryRun(dry);
        mvc().perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value(mode));
    }

    @Test
    void conflictingProfileLabelsRemainCustomAndActualFlagsAreShown() throws Exception {
        schemaAvailable();
        environment.setActiveProfiles("demo", "baseline");
        properties.getConversation().setEnabled(false);
        mvc().perform(get("/api/health"))
                .andExpect(jsonPath("$.mode").value("custom"))
                .andExpect(jsonPath("$.capabilities.conversation").value(false));
    }
}
