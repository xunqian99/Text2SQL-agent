package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.fewshot.ExampleLoader;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.orchestrator.Text2SqlOrchestrator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptVariantReportTest {
    @ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void actualReportIdentifiesSelectedVariant(boolean explicit, boolean strict) {
        var props = new AgentProperties();
        props.getPrompt().setExplicitReasoningEnabled(explicit);
        props.getFewshot().setRequireAvailableTables(strict);
        props.getEval().setDryRun(true);
        props.getEval().setSuiteManifest("");
        var item = new EvalItem("fixture-1", "single_table_basic", List.of(), "订单数？", "SELECT 1", null);
        var loader = mock(EvalItemLoader.class);
        when(loader.load()).thenReturn(List.of(item));
        when(loader.datasetSha256(props.getEval().getDir())).thenReturn("fixture");
        var executor = mock(SqlExecutor.class);
        when(executor.execute("SELECT 1")).thenReturn(new QueryResult(List.of("n"), List.of(List.of("1")), false, 0));
        var orchestrator = mock(Text2SqlOrchestrator.class);
        when(orchestrator.askWithFixedSql(item.question(), item.goldSql(), null)).thenReturn(
                AgentResponse.success(item.question(), item.goldSql(), false,
                        new AgentResponse.QueryOutcome(List.of("n"), List.of(List.of("1")), false), null, 0, List.of(), 0, null));
        var examples = mock(ExampleLoader.class);
        when(examples.getExamples()).thenReturn(List.of());
        var report = new EvalRunner(props, loader, orchestrator, executor, new ObjectMapper(), null, examples).runEvaluation();
        assertThat(report.overall().correct()).isEqualTo(1);
        assertThat(report.meta().promptVersion()).isEqualTo(new PromptTemplate(props).version());
        assertThat(report.meta().effectiveConfig()).containsEntry("explicitReasoningEnabled", explicit)
                .containsEntry("fewshotRequireAvailableTables", strict)
                .containsEntry("fewshotSelectionPolicy", com.text2sql.agent.fewshot.ExampleSelector.policyVersion(strict))
                .containsEntry("metricDefinitionsSha256", com.text2sql.agent.semantic.MetricRegistry.definitionSha256())
                .containsEntry("configuredReasoningEffort", "provider-default");
    }
}
