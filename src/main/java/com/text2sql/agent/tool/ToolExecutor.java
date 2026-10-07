package com.text2sql.agent.tool;

import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.SqlExecutor;
import com.text2sql.agent.retrieval.SchemaCatalog;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.validation.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 侦察工具执行中心。
 *
 * <p>所有工具调用强制运行在“只读连接 + 严格 AST 校验 + 强制限制 5 行”的安全沙箱中。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final SchemaCatalog catalog;
    private final SqlExecutor sqlExecutor;
    private final SqlValidator sqlValidator;
    private final com.text2sql.agent.execution.QueryPlanAnalyzer planAnalyzer;

    public ToolExecutor(SchemaCatalog catalog, SqlExecutor sqlExecutor, SqlValidator sqlValidator,
                        com.text2sql.agent.execution.QueryPlanAnalyzer planAnalyzer) {
        this.catalog = catalog;
        this.sqlExecutor = sqlExecutor;
        this.sqlValidator = sqlValidator;
        this.planAnalyzer = planAnalyzer;
    }

    /**
     * 在沙箱环境中执行一个工具调用。
     */
    public ToolResult execute(ToolCall call, SchemaContext schema) {
        if (call == null || call.type() == null) {
            return ToolResult.error("未知的工具调用");
        }

        try {
            return switch (call.type()) {
                case INSPECT_TABLE -> inspectTable(call.argument());
                case INSPECT_COLUMN -> inspectColumn(call.argument());
                case SAMPLE_QUERY -> sampleQuery(call.argument(), schema);
                case CHECK_JOIN -> checkJoin(call.argument(), schema);
                case EXPLAIN_QUERY -> explainQuery(call.argument(), schema);
                case FINAL_SQL -> ToolResult.ok(call.argument(), List.of(), List.of());
            };
        } catch (Exception e) {
            log.warn("工具 {} 执行异常：{}", call.type(), e.getMessage());
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * 工具 1：查看指定表的前 5 行真实数据。
     */
    private ToolResult inspectTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return ToolResult.error("缺少表名参数");
        }
        String cleanTable = tableName.strip().toLowerCase(Locale.ROOT);
        var tableOpt = catalog.full().tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(cleanTable))
                .findFirst();

        if (tableOpt.isEmpty()) {
            return ToolResult.error("表 " + cleanTable + " 不存在");
        }

        String sampleSql = "SELECT * FROM " + cleanTable + " LIMIT 5";
        QueryResult qr = sqlExecutor.execute(sampleSql);
        String formatted = formatResult("表 " + cleanTable + " 样例数据 (前 5 行)", qr);
        return ToolResult.ok(formatted, qr.columns(), qr.rows());
    }

    /**
     * 工具 2：查看指定列的值分布与常见枚举。
     * 参数格式：table.column 或 table column
     */
    private ToolResult inspectColumn(String tableColumn) {
        if (tableColumn == null || tableColumn.isBlank()) {
            return ToolResult.error("缺少列名参数，格式如: orders.order_status");
        }
        String clean = tableColumn.strip().replace(" ", ".");
        String[] parts = clean.split("\\.");
        if (parts.length != 2) {
            return ToolResult.error("参数格式错误，请使用: 表名.列名，例如 orders.order_status");
        }
        String table = parts[0].toLowerCase(Locale.ROOT);
        String col = parts[1].toLowerCase(Locale.ROOT);

        String statSql = "SELECT " + col + ", COUNT(*) AS cnt FROM " + table
                + " GROUP BY " + col + " ORDER BY cnt DESC LIMIT 10";

        QueryResult qr = sqlExecutor.execute(statSql);
        String formatted = formatResult("列 " + clean + " 的高频值分布", qr);
        return ToolResult.ok(formatted, qr.columns(), qr.rows());
    }

    /**
     * 工具 3：执行验证查询，强制 AST 校验并限制 5 行。
     */
    private ToolResult sampleQuery(String sql, SchemaContext schema) {
        if (sql == null || sql.isBlank()) {
            return ToolResult.error("缺少查询 SQL");
        }
        String cleanSql = sql.strip();
        if (cleanSql.endsWith(";")) {
            cleanSql = cleanSql.substring(0, cleanSql.length() - 1);
        }

        // 强行包装为 LIMIT 5
        String limitedSql = "SELECT * FROM (" + cleanSql + ") AS sample_subq LIMIT 5";
        var valResult = sqlValidator.validate(limitedSql, schema);
        if (!valResult.valid()) {
            return ToolResult.error("验证 SQL 校验未通过：" + valResult.describe());
        }

        QueryResult qr = sqlExecutor.execute(valResult.sql());
        String formatted = formatResult("验证查询执行结果 (前 5 行)", qr);
        return ToolResult.ok(formatted, qr.columns(), qr.rows());
    }

    /**
     * 工具 4：检查两个表的连表条件是否有匹配记录。
     * 参数格式：表1, 表2, 连接条件，如 orders, customers, orders.customer_id = customers.customer_id
     */
    private ToolResult checkJoin(String arg, SchemaContext schema) {
        if (arg == null || arg.isBlank()) {
            return ToolResult.error("缺少连表参数，格式如: orders, customers, orders.customer_id = customers.customer_id");
        }
        String[] parts = arg.split(",", 3);
        if (parts.length < 3) {
            return ToolResult.error("参数不足，请提供: 表1, 表2, 连接条件");
        }
        String t1 = parts[0].strip().toLowerCase(Locale.ROOT);
        String t2 = parts[1].strip().toLowerCase(Locale.ROOT);
        String onCondition = parts[2].strip();

        String testSql = "SELECT COUNT(*) AS match_count FROM " + t1 + " JOIN " + t2 + " ON " + onCondition + " LIMIT 1";
        var valResult = sqlValidator.validate(testSql, schema);
        if (!valResult.valid()) {
            return ToolResult.error("Join 语法或表校验未通过：" + valResult.describe());
        }

        QueryResult qr = sqlExecutor.execute(valResult.sql());
        String count = (qr.rowCount() > 0 && !qr.rows().get(0).isEmpty()) ? qr.rows().get(0).get(0) : "0";
        String out = "连表测试 (" + t1 + " JOIN " + t2 + " ON " + onCondition + ") 匹配行数: " + count;
        return ToolResult.ok(out, qr.columns(), qr.rows());
    }

    /**
     * 工具 5：执行 EXPLAIN 执行计划分析与代价检测。
     */
    private ToolResult explainQuery(String sql, SchemaContext schema) {
        if (sql == null || sql.isBlank()) {
            return ToolResult.error("缺少待分析的 SQL 参数");
        }
        String cleanSql = sql.strip();
        if (cleanSql.endsWith(";")) {
            cleanSql = cleanSql.substring(0, cleanSql.length() - 1);
        }

        var valResult = sqlValidator.validate(cleanSql, schema);
        if (!valResult.valid()) {
            return ToolResult.error("待分析 SQL 校验未通过：" + valResult.describe());
        }

        if (planAnalyzer == null) {
            return ToolResult.error("查询计划分析器未初始化");
        }

        var analysis = planAnalyzer.analyze(valResult.sql());
        StringBuilder sb = new StringBuilder();
        sb.append("--- EXPLAIN 执行计划探测结果 ---\n");
        sb.append("SQL: ").append(valResult.sql()).append("\n");
        sb.append("预估总代价 (Total Cost): ").append(String.format(Locale.ROOT, "%.2f", analysis.totalCost())).append("\n");
        sb.append("预估行数 (Plan Rows): ").append(analysis.planRows()).append("\n");
        sb.append("全表笛卡尔积风险: ").append(analysis.hasCartesianProduct() ? "【高危警告: 存在无约束全表笛卡尔积！】" : "无").append("\n");
        sb.append("是否满足安全护栏: ").append(analysis.safe() ? "通过 (SAFE)" : "拦截 (HIGH_RISK)").append("\n");
        if (!analysis.safe() && analysis.riskDescription() != null) {
            sb.append("风险诊断: ").append(analysis.riskDescription()).append("\n");
        }
        return ToolResult.ok(sb.toString(), List.of("metric", "value"), List.of());
    }

    private static String formatResult(String title, QueryResult qr) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(title).append(" ---\n");
        sb.append("列: ").append(String.join(", ", qr.columns())).append("\n");
        sb.append("数据行 (").append(qr.rowCount()).append(" 行):\n");
        for (List<String> row : qr.rows()) {
            sb.append("  ").append(row).append("\n");
        }
        return sb.toString();
    }
}
