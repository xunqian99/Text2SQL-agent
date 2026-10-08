package com.text2sql.agent.tool;

import com.text2sql.agent.execution.QueryPlanAnalyzer;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.retrieval.SchemaCatalog;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.validation.SqlValidator;
import com.text2sql.agent.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ToolExecutorTest {

    private SchemaCatalog catalog;
    private SqlExecutor sqlExecutor;
    private SqlValidator sqlValidator;
    private QueryPlanAnalyzer planAnalyzer;
    private ToolExecutor executor;
    private SchemaContext mockSchema;

    @BeforeEach
    void setUp() {
        catalog = mock(SchemaCatalog.class);
        sqlExecutor = mock(SqlExecutor.class);
        sqlValidator = mock(SqlValidator.class);
        planAnalyzer = mock(QueryPlanAnalyzer.class);
        executor = new ToolExecutor(catalog, sqlExecutor, sqlValidator, planAnalyzer);

        mockSchema = new SchemaContext(
                List.of(new SchemaContext.Table("orders", null, List.of(
                        new SchemaContext.Column("order_id", "text", false, null),
                        new SchemaContext.Column("order_status", "text", false, null)))),
                List.of(), "", "CREATE TABLE orders(order_id text, order_status text);");

        when(catalog.full()).thenReturn(mockSchema);
    }

    @Test
    @DisplayName("INSPECT_TABLE: 正常查看表前5行样例数据")
    void testInspectTableSuccess() {
        when(sqlExecutor.execute(anyString())).thenReturn(
                new QueryResult(List.of("order_id", "order_status"),
                        List.of(List.of("ord-1", "delivered")), false, 10));

        ToolCall call = new ToolCall(ToolType.INSPECT_TABLE, "orders", "");
        ToolResult result = executor.execute(call, mockSchema);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("表 orders 样例数据");
        assertThat(result.output()).contains("delivered");
        verify(sqlExecutor).execute("SELECT * FROM orders LIMIT 5");
    }

    @Test
    @DisplayName("INSPECT_TABLE: 表不存在时返回友好错误，应用不崩")
    void testInspectTableNotFound() {
        ToolCall call = new ToolCall(ToolType.INSPECT_TABLE, "unknown_table", "");
        ToolResult result = executor.execute(call, mockSchema);

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("表 unknown_table 不存在");
        verify(sqlExecutor, never()).execute(anyString());
    }

    @Test
    @DisplayName("INSPECT_COLUMN: 参数格式错误容错处理")
    void testInspectColumnInvalidArgs() {
        ToolCall call = new ToolCall(ToolType.INSPECT_COLUMN, "only_column_name_without_table", "");
        ToolResult result = executor.execute(call, mockSchema);

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("参数格式错误");
    }

    @Test
    @DisplayName("INSPECT_COLUMN: 正常探查列的高频值分布")
    void testInspectColumnSuccess() {
        when(sqlExecutor.execute(anyString())).thenReturn(
                new QueryResult(List.of("order_status", "cnt"),
                        List.of(List.of("delivered", "96478"), List.of("shipped", "1107")), false, 15));

        ToolCall call = new ToolCall(ToolType.INSPECT_COLUMN, "orders.order_status", "");
        ToolResult result = executor.execute(call, mockSchema);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("列 orders.order_status 的高频值分布");
        assertThat(result.output()).contains("96478");
    }

    @Test
    @DisplayName("SAMPLE_QUERY: 自动包装为 LIMIT 5 并强制 AST 白名单校验")
    void testSampleQueryGuardsAndLimits() {
        when(sqlValidator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(sqlExecutor.execute(anyString())).thenReturn(
                new QueryResult(List.of("cnt"), List.of(List.of("5")), false, 5));

        ToolCall call = new ToolCall(ToolType.SAMPLE_QUERY, "SELECT count(*) FROM orders", "");
        ToolResult result = executor.execute(call, mockSchema);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("验证查询执行结果");
        // 验证强制包装了 LIMIT 5 子查询
        verify(sqlValidator).validate(argThat(sql -> sql.contains("LIMIT 5") && sql.contains("sample_subq")), any());
    }

    @Test
    @DisplayName("SAMPLE_QUERY: 恶意写操作被 AST 校验器硬拦截")
    void testSampleQueryBlocksMaliciousSql() {
        when(sqlValidator.validate(anyString(), any())).thenReturn(
                new ValidationResult(null, List.of(new ValidationResult.Violation(
                        ValidationResult.Code.NOT_SELECT, "写操作拦截: DROP 不被允许")), false));

        ToolCall call = new ToolCall(ToolType.SAMPLE_QUERY, "DROP TABLE orders", "");
        ToolResult result = executor.execute(call, mockSchema);

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("验证 SQL 校验未通过");
        verify(sqlExecutor, never()).execute(anyString());
    }

    @Test
    @DisplayName("CHECK_JOIN: 正常测试两表连接匹配行数")
    void testCheckJoinSuccess() {
        when(sqlValidator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(sqlExecutor.execute(anyString())).thenReturn(
                new QueryResult(List.of("match_count"), List.of(List.of("1")), false, 10));

        ToolCall call = new ToolCall(ToolType.CHECK_JOIN,
                "orders, customers, orders.customer_id = customers.customer_id", "");
        ToolResult result = executor.execute(call, mockSchema);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("匹配行数: 1");
    }

    @Test
    @DisplayName("EXPLAIN_QUERY: 预执行分析执行计划与全表笛卡尔积拦截提示")
    void testExplainQueryDetection() {
        when(sqlValidator.validate(anyString(), any())).thenAnswer(inv ->
                ValidationResult.ok(inv.getArgument(0)));
        when(planAnalyzer.analyze(anyString())).thenReturn(
                new QueryPlanAnalyzer.PlanAnalysis(false, 150000.0, 99000000L, true, "高危警告: 存在无约束全表笛卡尔积！", "{}"));

        ToolCall call = new ToolCall(ToolType.EXPLAIN_QUERY, "SELECT * FROM orders, customers", "");
        ToolResult result = executor.execute(call, mockSchema);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("EXPLAIN 执行计划探测结果");
        assertThat(result.output()).contains("高危警告: 存在无约束全表笛卡尔积！");
        assertThat(result.output()).contains("拦截 (HIGH_RISK)");
    }

}
