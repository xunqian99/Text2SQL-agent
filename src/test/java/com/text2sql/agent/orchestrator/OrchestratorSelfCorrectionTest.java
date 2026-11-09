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
import org.mockito.ArgumentCaptor;

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
                mock(AmbiguityDetector.class), cache, new com.text2sql.agent.validation.ResultChecker(),
                mock(com.text2sql.agent.tool.AgentLoop.class), properties);
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

    @Test
    @DisplayName("阶段 B：执行成功但命中启发式异常时，触发自纠错重试")
    void retriesWhenHeuristicCheckerDetectsSuspiciousResult() {
        properties.getSelfCorrection().setEnabled(true);
        properties.getSelfCorrection().setMaxAttempts(2);
        properties.getResultChecker().setEnabled(true);

        // 第一次生成了一条 WHERE 状态过滤导致返回 0 行的 SQL
        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT count(*) FROM orders WHERE order_status = 'BAD_STATUS'", "raw", call(100, 10, 50)));
        // 第二次修正了状态
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT count(*) FROM orders WHERE order_status = 'delivered'", "raw", call(150, 15, 60)));

        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));

        // 第一次执行返回 0 行
        when(executor.execute("SELECT count(*) FROM orders WHERE order_status = 'BAD_STATUS'"))
                .thenReturn(new QueryResult(List.of("cnt"), List.of(), false, 1));
        // 第二次执行返回正常数据
        when(executor.execute("SELECT count(*) FROM orders WHERE order_status = 'delivered'"))
                .thenReturn(new QueryResult(List.of("cnt"), List.of(List.of("9000")), false, 1));

        AgentResponse response = orchestrator().ask("统计已完成订单的总数");

        assertThat(response.success()).isTrue();
        assertThat(response.sql()).contains("delivered");
        verify(generator, times(1))
                .generateCorrection(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("阶段 5 闭环防御：SQL 触发 EXPLAIN 代价/笛卡尔积护栏拦截时，触发自纠错重试并修正")
    void retriesWhenExplainCostGuardFails() {
        properties.getSelfCorrection().setEnabled(true);
        properties.getSelfCorrection().setMaxAttempts(2);
        properties.getGuard().setExplainCostGuardEnabled(true);

        com.text2sql.agent.execution.QueryPlanAnalyzer planAnalyzer = mock(com.text2sql.agent.execution.QueryPlanAnalyzer.class);
        Text2SqlOrchestrator orch = new Text2SqlOrchestrator(schemaProvider, generator, validator, executor,
                mock(AmbiguityDetector.class), cache, new com.text2sql.agent.validation.ResultChecker(),
                mock(com.text2sql.agent.tool.AgentLoop.class),
                new com.text2sql.agent.session.SessionStore(properties),
                new com.text2sql.agent.session.QuestionRewriter(generator, properties),
                planAnalyzer, properties);

        // 第一次生成了无 JOIN 条件的笛卡尔积 SQL
        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT * FROM orders, customers", "raw", call(100, 10, 50)));
        // 第二次修正了连接条件
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT * FROM orders JOIN customers ON orders.customer_id = customers.customer_id", "raw", call(150, 15, 60)));

        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));

        // 第一次 EXPLAIN 判定为笛卡尔积风险
        when(planAnalyzer.analyze("SELECT * FROM orders, customers"))
                .thenReturn(com.text2sql.agent.execution.QueryPlanAnalyzer.PlanAnalysis.risk(
                        250000.0, 999999L, true, "检测到全表笛卡尔积", "{}"));
        // 第二次 EXPLAIN 判定为安全
        when(planAnalyzer.analyze("SELECT * FROM orders JOIN customers ON orders.customer_id = customers.customer_id"))
                .thenReturn(com.text2sql.agent.execution.QueryPlanAnalyzer.PlanAnalysis.safe(
                        100.0, 50L, "{}"));

        when(executor.execute("SELECT * FROM orders JOIN customers ON orders.customer_id = customers.customer_id"))
                .thenReturn(new QueryResult(List.of("order_id"), List.of(List.of("1")), false, 1));

        AgentResponse response = orch.ask("查询所有订单及对应客户");

        assertThat(response.success()).isTrue();
        assertThat(response.sql()).contains("JOIN customers ON orders.customer_id = customers.customer_id");
        verify(generator, times(1))
                .generateCorrection(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("dry-run：trusted gold SQL 用全量 schema 校验，检索指标仍按子集记录")
    void dryRunValidatesGoldSqlAgainstFullSchema() {
        properties.getEval().setDryRun(true);

        SchemaContext retrieved = new SchemaContext(
                List.of(new SchemaContext.Table("orders", null, List.of(
                        new SchemaContext.Column("order_id", "text", false, null)))),
                List.of(), "", "TABLE orders");
        SchemaContext full = new SchemaContext(
                List.of(
                        new SchemaContext.Table("orders", null, List.of(
                                new SchemaContext.Column("order_id", "text", false, null))),
                        new SchemaContext.Table("customers", null, List.of(
                                new SchemaContext.Column("customer_id", "text", false, null)))),
                List.of(), "", "TABLE orders\nTABLE customers");
        when(schemaProvider.provide(anyString())).thenReturn(retrieved);
        when(schemaProvider.fullSchema()).thenReturn(full);
        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(executor.execute(anyString()))
                .thenReturn(new QueryResult(List.of("order_id"), List.of(List.of("1")), false, 1));

        AgentResponse response = orchestrator().askWithFixedSql("有多少订单？",
                "SELECT o.order_id FROM orders o JOIN customers c ON c.customer_id = o.order_id", null);

        assertThat(response.success()).isTrue();
        ArgumentCaptor<SchemaContext> schemaCaptor = ArgumentCaptor.forClass(SchemaContext.class);
        verify(validator).validate(anyString(), schemaCaptor.capture());
        assertThat(schemaCaptor.getValue().tableNames())
                .as("dry-run 必须用全量 schema 校验 trusted gold SQL")
                .containsExactly("orders", "customers");
        assertThat(response.retrievedTables())
                .as("检索指标仍按实际召回的子集记录，不能虚高")
                .containsExactly("orders");
    }

    // ---------------------------------------------------------------
    // 择优而非覆盖：首次「成功但可疑」时，第二次也必须通过同一套校验才配替换。
    // 没有这条规则，一次规则误报就会把本来正确的答案换成错的。
    // ---------------------------------------------------------------

    private void enableSuspiciousRetry() {
        properties.getSelfCorrection().setEnabled(true);
        properties.getSelfCorrection().setMaxAttempts(2);
        properties.getResultChecker().setEnabled(true);
        when(validator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
    }

    /** 带 WHERE 的查询返回 0 行，且问题不是在问「哪些没有」，会命中 EMPTY_RESULT 规则。 */
    private static QueryResult emptyResult() {
        return new QueryResult(List.of("count"), List.of(), false, 1);
    }

    private static QueryResult oneRow() {
        return new QueryResult(List.of("count"), List.of(List.of("42")), false, 1);
    }

    @Test
    @DisplayName("重试结果仍可疑：保留首次，不用一个同样可疑的答案覆盖")
    void keepsFirstWhenRetryStillSuspicious() {
        enableSuspiciousRetry();

        String firstSql = "SELECT COUNT(*) FROM orders WHERE state = 'CA' LIMIT 10";
        String secondSql = "SELECT COUNT(*) FROM orders WHERE state = 'XX' LIMIT 10";
        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql(firstSql, "raw", call(100, 10, 50)));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql(secondSql, "raw", call(200, 20, 70)));
        when(executor.execute(firstSql)).thenReturn(emptyResult());
        when(executor.execute(secondSql)).thenReturn(emptyResult());

        AgentResponse response = orchestrator().ask("加州有多少订单？");

        assertThat(response.sql())
                .as("两次都被判可疑时，应保留首次——第二次没有证据更好")
                .isEqualTo(firstSql);
    }

    @Test
    @DisplayName("重试结果通过校验：采用第二次，说明择优不是一律保留首次")
    void takesRetryWhenRetryPassesCheck() {
        enableSuspiciousRetry();

        String firstSql = "SELECT COUNT(*) FROM orders WHERE state = 'CA' LIMIT 10";
        String secondSql = "SELECT COUNT(*) FROM orders WHERE state = 'CA' AND order_id IS NOT NULL LIMIT 10";
        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql(firstSql, "raw", call(100, 10, 50)));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql(secondSql, "raw", call(200, 20, 70)));
        when(executor.execute(firstSql)).thenReturn(emptyResult());
        when(executor.execute(secondSql)).thenReturn(oneRow());

        AgentResponse response = orchestrator().ask("加州有多少订单？");

        assertThat(response.sql()).isEqualTo(secondSql);
        assertThat(response.success()).isTrue();
        assertThat(response.llmCall().promptTokens())
                .as("两次调用都要记账").isEqualTo(300);
    }

    @Test
    @DisplayName("首次执行失败时不做择优：第二次只要能跑通就采用")
    void takesRetryWhenFirstFailed() {
        enableSuspiciousRetry();

        String secondSql = "SELECT COUNT(*) FROM orders WHERE state = 'CA' LIMIT 10";
        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql("SELECT bad FROM orders", "raw", call(100, 10, 50)));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql(secondSql, "raw", call(200, 20, 70)));
        when(executor.execute("SELECT bad FROM orders"))
                .thenThrow(new SqlExecutionException("column \"bad\" does not exist", null));
        when(executor.execute(secondSql)).thenReturn(emptyResult());

        AgentResponse response = orchestrator().ask("加州有多少订单？");

        assertThat(response.success())
                .as("首次不可用时，第二次即使结果可疑也优于「无结果」")
                .isTrue();
        assertThat(response.sql()).isEqualTo(secondSql);
    }

    @Test
    @DisplayName("校验命中时的反馈要带上规则码、问题原文和实际输出列")
    void correctionFeedbackCarriesEvidence() {
        enableSuspiciousRetry();

        String firstSql = "SELECT COUNT(*) FROM orders WHERE state = 'CA' LIMIT 10";
        when(generator.generate(anyString(), any()))
                .thenReturn(new GeneratedSql(firstSql, "raw", call(100, 10, 50)));
        when(generator.generateCorrection(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new GeneratedSql(firstSql, "raw", call(200, 20, 70)));
        when(executor.execute(firstSql)).thenReturn(emptyResult());

        orchestrator().ask("加州有多少订单？");

        ArgumentCaptor<String> feedbackCaptor = ArgumentCaptor.forClass(String.class);
        verify(generator).generateCorrection(anyString(), any(), anyString(),
                feedbackCaptor.capture(), any());
        String feedback = feedbackCaptor.getValue();
        assertThat(feedback)
                .as("必须让模型看到命中了哪条规则，而不是只说「可疑」")
                .contains("EMPTY_RESULT");
        assertThat(feedback).contains("加州有多少订单？");
        assertThat(feedback)
                .as("把实际输出列摆出来，模型才知道自己返回了什么")
                .contains("count");
        assertThat(feedback)
                .as("要求最小修改，避免把正确的部分一起改坏")
                .contains("最小修改");
    }
}
