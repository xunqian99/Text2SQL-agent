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
import java.util.regex.Matcher;

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

        // 用 catalog 里的真实表名拼 SQL，而不是模型给的原始字符串。
        // 两者在忽略大小写后等价，但只有前者能保证不把奇怪字符带进语句里。
        String canonicalTable = tableOpt.get().name();
        String sampleSql = "SELECT * FROM " + canonicalTable + " LIMIT 5";
        QueryResult qr = sqlExecutor.execute(sampleSql);
        String formatted = formatResult("表 " + canonicalTable + " 样例数据 (前 5 行)", qr);
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
        String table = parts[0].strip().toLowerCase(Locale.ROOT);
        String col = parts[1].strip().toLowerCase(Locale.ROOT);

        // 表名和列名都必须先在 catalog 里命中，才允许拼进 SQL。
        //
        // 这一步不能省：本工具的入参来自**模型输出**，而项目的前提是
        // 「模型输出等价于不可信的用户输入」。直接把它拼进语句，等于开了
        // 一个注入面——而且这里是五个工具里唯一一个既拼接、又不过 SQL 校验器的
        // （sampleQuery / checkJoin / explainQuery 都会走 SqlValidator）。
        // 用 catalog 里的规范名回填，既堵住注入，也顺手纠正大小写。
        var tableOpt = catalog.full().tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(table))
                .findFirst();
        if (tableOpt.isEmpty()) {
            return ToolResult.error("表 " + table + " 不存在");
        }
        var tableMeta = tableOpt.get();
        var colOpt = tableMeta.columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(col))
                .findFirst();
        if (colOpt.isEmpty()) {
            return ToolResult.error("表 " + tableMeta.name() + " 上没有列 " + col);
        }
        String canonicalTable = tableMeta.name();
        String canonicalCol = colOpt.get().name();

        String statSql = "SELECT " + canonicalCol + ", COUNT(*) AS cnt FROM " + canonicalTable
                + " GROUP BY " + canonicalCol + " ORDER BY cnt DESC LIMIT 10";

        QueryResult qr = sqlExecutor.execute(statSql);
        String formatted = formatResult("列 " + canonicalTable + "." + canonicalCol + " 的高频值分布", qr);
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

        // 表名必须命中 catalog：用规范名回填，防止模型给出不存在的表，
        // 也避免表名被直接拼进语句。
        var t1Opt = catalog.full().tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(t1))
                .findFirst();
        if (t1Opt.isEmpty()) {
            return ToolResult.error("表 " + t1 + " 不存在");
        }
        var t2Opt = catalog.full().tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(t2))
                .findFirst();
        if (t2Opt.isEmpty()) {
            return ToolResult.error("表 " + t2 + " 不存在");
        }
        if (t1Opt.get().name().equalsIgnoreCase(t2Opt.get().name())) {
            return ToolResult.error("连表的两表不能相同");
        }
        var table1 = t1Opt.get();
        var table2 = t2Opt.get();
        String canonT1 = table1.name();
        String canonT2 = table2.name();

        // 连接条件必须是「表.列 = 表.列」的等值形式。
        //
        // 为什么不直接拼接模型给的 onCondition：它来自模型输出，等于不可信输入。
        // 直接拼进 ON 子句会开一个注入面（任意 SQL 片段、ON 1=1 笛卡尔积）。
        // 这里强制等值形式，并逐个校验列是否真实存在，否则拒绝。
        Matcher m = JOIN_CONDITION_PATTERN.matcher(onCondition);
        if (!m.matches()) {
            return ToolResult.error(
                    "连接条件必须是「表.列 = 表.列」的等值形式，例如 "
                            + canonT1 + "." + primaryKeyOf(table1) + " = " + canonT2 + "." + foreignKeyGuess(table2, table1)
                            + "；不支持 ON 1=1 这类笛卡尔积或任意表达式");
        }
        String leftTable = m.group(1);
        String leftCol = m.group(2);
        String rightTable = m.group(3);
        String rightCol = m.group(4);

        if (!isJoinColumn(leftTable, leftCol, canonT1, table1, canonT2, table2)
                || !isJoinColumn(rightTable, rightCol, canonT1, table1, canonT2, table2)) {
            return ToolResult.error("连接条件里的表名/列名不匹配，请检查两侧分别属于哪张表");
        }
        // 等值两侧必须分别来自两张表，否则等于自己连自己。
        boolean leftIsT1 = leftTable.equalsIgnoreCase(canonT1);
        boolean rightIsT1 = rightTable.equalsIgnoreCase(canonT1);
        if (leftIsT1 == rightIsT1) {
            return ToolResult.error("等值连接的两侧必须分别来自 " + canonT1 + " 和 " + canonT2);
        }

        String testSql = "SELECT COUNT(*) AS match_count FROM " + canonT1
                + " JOIN " + canonT2 + " ON " + leftTable + "." + leftCol + " = " + rightTable + "." + rightCol
                + " LIMIT 1";
        var valResult = sqlValidator.validate(testSql, schema);
        if (!valResult.valid()) {
            return ToolResult.error("Join 语法或表校验未通过：" + valResult.describe());
        }

        QueryResult qr = sqlExecutor.execute(valResult.sql());
        String count = (qr.rowCount() > 0 && !qr.rows().get(0).isEmpty()) ? qr.rows().get(0).get(0) : "0";
        String out = "连表测试 (" + canonT1 + " JOIN " + canonT2 + " ON "
                + leftTable + "." + leftCol + " = " + rightTable + "." + rightCol + ") 匹配行数: " + count;
        return ToolResult.ok(out, qr.columns(), qr.rows());
    }

    /** 连接条件的等值形式：表.列 = 表.列 */
    private static final java.util.regex.Pattern JOIN_CONDITION_PATTERN = java.util.regex.Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)$");

    /** 校验某个「表.列」引用：表名必须命中两表之一，且列确实存在于该表。 */
    private boolean isJoinColumn(String refTable, String refCol,
                                 String canonT1, SchemaContext.Table table1,
                                 String canonT2, SchemaContext.Table table2) {
        SchemaContext.Table target;
        if (refTable.equalsIgnoreCase(canonT1)) {
            target = table1;
        } else if (refTable.equalsIgnoreCase(canonT2)) {
            target = table2;
        } else {
            return false;
        }
        return target.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(refCol));
    }

    /** 取表的主键列名，用于错误提示里给出可照抄的例子。 */
    private static String primaryKeyOf(SchemaContext.Table table) {
        return table.columns().stream()
                .filter(SchemaContext.Column::primaryKey)
                .map(SchemaContext.Column::name)
                .findFirst()
                .orElse("id");
    }

    /** 猜测另一张表上可能的外键列名，仅用于错误提示，不参与任何安全判断。 */
    private static String foreignKeyGuess(SchemaContext.Table table, SchemaContext.Table other) {
        String otherPk = primaryKeyOf(other);
        return table.columns().stream()
                .map(SchemaContext.Column::name)
                .filter(c -> c.equalsIgnoreCase(otherPk) || c.toLowerCase(Locale.ROOT).endsWith("_id"))
                .findFirst()
                .orElse("id");
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
