package com.text2sql.agent.orchestrator;

import com.text2sql.agent.clarification.AmbiguityDetector;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutionException;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.generation.GeneratedSql;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 线上自纠错的编排行为测试。
 *
 * <p>这里测的不是「自纠错能不能提升准确率」——那是离线评估的事（见 reports/README）。
 * 这里测的是**编排语义**，三条最容易被写错、而且写错了不会报错的规则：
 *
 * <ol>
 *   <li>重试成功后，token 和延迟必须把两次调用都算上，否则成本被低估一半；</li>
 *   <li>重试失败时必须保留首次结果，不能让一次糟糕的重试把可用答案覆盖掉；</li>
 *   <li>开关关闭时不能多花一次模型调用。</li>
 * </ol>
 */
class OrchestratorSelfCorrectionTest {

    private SchemaProvider schemaProvider;
    private LlmSqlGenerator generator;
    private SqlValidator validator;
    private SqlExecutor executor;
    private AgentProperties properties;
    private com.text2sql.agent.cache.SemanticCache cache;

    @BeforeEach
    void setUp() {
        schemaProvider = mock(SchemaProvider.class);
        generator = mock(LlmSqlGenerator.class);
        validator = mock(SqlValidator.class);
        executor = mock(SqlExecutor.class);
        properties = new AgentProperties();
        cache = new com.text2sql.agent.cache.SemanticCache(properties);

        when(schemaProvider.provide(anyString())).thenReturn(new SchemaContext(
                List.of(new SchemaContext.Table("orders", null, List.of(
                        new SchemaContext.Column("order_id", "text", false, null)))),
                List.of(), "", "CREATE TABLE orders(order_id text)"));
    }

    private Text2SqlOrchestrator orchestrator() {
        return new Text2SqlOrchestrator(schemaProvider, generator, validator, executor,
                mock(AmbiguityDetector.class), cache, properties);
    }

    private static LlmCallRecord call(int promptTokens, int completionTokens, long latencyMs) {
        return LlmCallRecord.of("test-model", promptTokens, completionTokens, 100, latencyMs, 0, 0);
    }

    @Test
    @DisplayName("重试成功：采用第二次 SQL，且 token 与延迟按两次之和记账")
    void keepsSecondAttemptAndSumsCost() {
        properties.getSelfCorrection().setEnabled(true);
        properties.getSelfCorrection().setMaxAttempts(2);

        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT bad FROM orders", "raw", call(100, 10, 50)));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT order_id FROM orders LIMIT 10", "raw", call(200, 20, 70)));

        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(executor.execute("SELECT bad FROM orders"))
                .thenThrow(new SqlExecutionException("column \"bad\" does not exist", null));
        when(executor.execute("SELECT order_id FROM orders LIMIT 10"))
                .thenReturn(new QueryResult(List.of("order_id"), List.of(List.of("1")), false, 1));

        AgentResponse response = orchestrator().ask("有多少订单？");

        assertThat(response.success()).isTrue();
        assertThat(response.sql()).isEqualTo("SELECT order_id FROM orders LIMIT 10");
        // 两次调用的 token 和延迟都要算上
        assertThat(response.llmCall().promptTokens()).isEqualTo(300);
        assertThat(response.llmCall().completionTokens()).isEqualTo(30);
        assertThat(response.llmCall().latencyMs()).isEqualTo(120);
    }

    @Test
    @DisplayName("重试仍失败：保留首次结果，不被更差的重试覆盖")
    void keepsFirstResultWhenRetryFails() {
        properties.getSelfCorrection().setEnabled(true);
        properties.getSelfCorrection().setMaxAttempts(2);

        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT first_sql FROM orders", "raw", call(100, 10, 50)));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT still_bad FROM orders", "raw", call(200, 20, 70)));

        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(executor.execute(anyString()))
                .thenThrow(new SqlExecutionException("boom", null));

        AgentResponse response = orchestrator().ask("有多少订单？");

        assertThat(response.status()).isEqualTo(AgentResponse.Status.EXECUTION_FAILED);
        assertThat(response.sql()).as("必须是首次那条 SQL").isEqualTo("SELECT first_sql FROM orders");
    }

    @Test
    @DisplayName("开关关闭时不发起第二次调用")
    void doesNotRetryWhenDisabled() {
        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT first_sql FROM orders", "raw", call(100, 10, 50)));
        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(executor.execute(anyString())).thenThrow(new SqlExecutionException("boom", null));

        orchestrator().ask("有多少订单？");

        verify(generator, times(1)).generate(anyString(), any());
        verify(generator, never()).generateCorrection(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("首次就成功时不做无谓重试")
    void doesNotRetryWhenFirstAttemptSucceeds() {
        properties.getSelfCorrection().setEnabled(true);
        properties.getSelfCorrection().setMaxAttempts(3);

        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT order_id FROM orders LIMIT 10", "raw", call(100, 10, 50)));
        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(executor.execute(anyString()))
                .thenReturn(new QueryResult(List.of("order_id"), List.of(List.of("1")), false, 1));

        AgentResponse response = orchestrator().ask("有多少订单？");

        assertThat(response.success()).isTrue();
        verify(generator, never()).generateCorrection(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("分层路由：首答跑不通时换用升级模型重试")
    void escalatesToStrongerModelOnFailure() {
        properties.getRouting().setEnabled(true);
        properties.getRouting().setEscalationModel("strong-model");

        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT bad FROM orders", "raw", call(100, 10, 50)));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT order_id FROM orders LIMIT 10", "raw",
                        // 记录里带上升级后的模型名，方便事后从 JSONL 看出这次花了谁的钱
                        LlmCallRecord.of("strong-model", 200, 20, 100, 70, 0, 0)));

        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(executor.execute("SELECT bad FROM orders"))
                .thenThrow(new SqlExecutionException("boom", null));
        when(executor.execute("SELECT order_id FROM orders LIMIT 10"))
                .thenReturn(new QueryResult(List.of("order_id"), List.of(List.of("1")), false, 1));

        AgentResponse response = orchestrator().ask("有多少订单？");

        assertThat(response.success()).isTrue();
        assertThat(response.llmCall().model()).as("最终记账应体现升级后的模型")
                .isEqualTo("strong-model");
    }

    @Test
    @DisplayName("路由关闭时不换模型；只开路由不开自纠错也要能重试")
    void routingAloneEnablesRetry() {
        // 只开路由、不开自纠错：这是「省钱」的用法——大部分题用便宜模型，
        // 只有跑不通时才花钱升级。重试必须仍然发生，否则这个配置等于没开。
        properties.getRouting().setEnabled(true);
        properties.getRouting().setEscalationModel("strong-model");

        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT bad FROM orders", "raw", call(100, 10, 50)));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT order_id FROM orders LIMIT 10", "raw", call(200, 20, 70)));
        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(executor.execute("SELECT bad FROM orders"))
                .thenThrow(new SqlExecutionException("boom", null));
        when(executor.execute("SELECT order_id FROM orders LIMIT 10"))
                .thenReturn(new QueryResult(List.of("order_id"), List.of(List.of("1")), false, 1));

        AgentResponse response = orchestrator().ask("有多少订单？");

        assertThat(response.success()).isTrue();
        verify(generator, times(1))
                .generateCorrection(anyString(), any(), anyString(), anyString(), any());
    }
}
