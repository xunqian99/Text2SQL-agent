package com.text2sql.agent.validation;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.SchemaContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 校验层：把模型产出的文本变成「可以放心执行的 SQL」。
 *
 * <p><b>为什么必须有这一层，而不是靠 prompt 约束</b>
 *
 * <p>这是整个项目里最容易被问、也最容易答错的一题。很多人会说
 * 「我在 prompt 里写了只能输出 SELECT」——这个回答是危险的，因为
 * prompt 是**概率性约束**：模型 99% 的时候会遵守，剩下 1% 可能给你
 * 一句 {@code DROP TABLE orders}。在生产库里，1% 意味着灾难。
 *
 * <p>正确的心智模型是：**LLM 的输出等价于不可信的用户输入**。
 * 既然不会把用户直接输入的字符串拼进 SQL，也不该把模型输出直接执行。
 * 所以这一层的定位不是「提升准确率」，而是「让错误无害化」。
 *
 * <p><b>为什么用 AST 解析而不是正则匹配关键字</b>
 *
 * <p>被否掉的方案：{@code if (sql.toUpperCase().contains("DROP")) reject;}。
 * 它有三个漏洞：一是注释里出现 DROP 会误杀（假阳性）；
 * 二是把关键字从中间切开（在 SEL 和 ECT 之间插入注释）或大小写混写、
 * Unicode 同形字都能绕过（假阴性）；
 * 三是拿不到结构信息，无法判断有没有 LIMIT、用了哪张表。
 *
 * <p>JSqlParser 把 SQL 解析成 AST 后，「是不是 SELECT」变成一次
 * {@code instanceof} 判断，没有歧义空间。代价是引入了「解析器不认识
 * 某些方言语法」的风险——所以解析失败必须当作**拒绝**而不是放行，
 * 这是安全组件里唯一正确的默认值（fail-closed）。
 */
@Component
public class SqlValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlValidator.class);

    private final AgentProperties properties;

    public SqlValidator(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验 SQL。
     *
     * @param sql    模型生成的 SQL
     * @param schema 本次请求的 schema 上下文，用于表白名单校验
     */
    public ValidationResult validate(String sql, SchemaContext schema) {
        List<ValidationResult.Violation> violations = new ArrayList<>();

        if (sql == null || sql.isBlank()) {
            return new ValidationResult("", List.of(
                    new ValidationResult.Violation(ValidationResult.Code.EMPTY, "SQL 为空")), false);
        }

        // ---- 1) 先拦多语句。必须在解析之前做，因为 parseStatements 会把
        //         "SELECT 1; DROP TABLE orders" 解析成两条合法语句，如果只
        //         校验第一条就会漏掉第二条。
        if (containsMultipleStatements(sql)) {
            return new ValidationResult(sql, List.of(
                    new ValidationResult.Violation(ValidationResult.Code.MULTIPLE_STATEMENTS,
                            "只允许单条语句，检测到分号分隔的多条语句")), false);
        }

        // ---- 2) 解析成 AST。解析失败一律拒绝（fail-closed）。
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return new ValidationResult(sql, List.of(
                    new ValidationResult.Violation(ValidationResult.Code.PARSE_ERROR,
                            "SQL 无法解析：" + e.getMessage())), false);
        }

        // ---- 3) 必须是 SELECT。这一条挡住 DROP/UPDATE/DELETE/INSERT/ALTER/TRUNCATE...
        if (!(statement instanceof Select select)) {
            return new ValidationResult(sql, List.of(
                    new ValidationResult.Violation(ValidationResult.Code.NOT_SELECT,
                            "只允许 SELECT 语句，实际是 " + statement.getClass().getSimpleName())), false);
        }

        // ---- 4) 危险函数黑名单。
        collectForbiddenFunctions(select, violations);

        // ---- 5) 表白名单：只能查 schema 里真实存在的表。
        //        这一步既防攻击，也能提前发现「模型臆造表名」，让错误在
        //        执行前暴露，而不是等 PostgreSQL 报 relation does not exist。
        Set<String> allowedTables = new LinkedHashSet<>();
        schema.tables().forEach(t -> allowedTables.add(t.name().toLowerCase(Locale.ROOT)));
        // 用已经解析好的 AST 取表名，而不是 findTables(String)：
        // 后者会再解析一遍 SQL，且声明了受检异常，纯属重复劳动。
        for (String table : new TablesNamesFinder<Void>().getTableList(statement)) {
            String normalized = table.toLowerCase(Locale.ROOT);
            if (!allowedTables.contains(normalized)) {
                violations.add(new ValidationResult.Violation(ValidationResult.Code.UNKNOWN_TABLE,
                        "引用了不存在的表：" + table));
            }
        }

        // ---- 5b) 列白名单。
        //        只检查**带表别名前缀的列**（如 o.customer_id），不检查裸列名。
        //
        //        【为什么不做裸列名的检查】裸列名的合法来源太多：SELECT 别名、
        //        GROUP BY 位置、CTE 输出列、派生表列、集合操作两侧的同名列……
        //        一旦判错，正常查询会被拦下，护栏就从「防护」变成「故障源」。
        //        带前缀的列可以精确归属到某张表，判定是确定的，所以先做这一半。
        //
        //        【它挡住的真实错误】多表题里模型常把维度表猜错，例如写
        //        sellers.region_name（sellers 没有这一列，region_name 在 regions 上）。
        //        这类 SQL 在 PostgreSQL 里会直接报 column does not exist，
        //        能在执行前拦下，就省掉一次数据库往返。
        if (properties.getGuard().isColumnWhitelistEnabled()) {
            collectUnknownColumns(select, schema, violations);
        }

        // ---- 6) 强制 LIMIT。
        String finalSql = sql;
        boolean rewritten = false;
        if (!violations.isEmpty()) {
            return new ValidationResult(sql, violations, false);
        }

        if (needsLimit(select) && !hasLimit(select)) {
            if (properties.getGuard().getLimitMode() == AgentProperties.Guard.LimitMode.REJECT) {
                violations.add(new ValidationResult.Violation(ValidationResult.Code.MISSING_LIMIT,
                        "结果集可能很大，必须显式指定 LIMIT"));
            } else {
                applyLimit(select, properties.getDb().getMaxRows());
                finalSql = select.toString();
                rewritten = true;
                log.debug("已自动补 LIMIT {}", properties.getDb().getMaxRows());
            }
        }

        return new ValidationResult(finalSql, List.copyOf(violations), rewritten);
    }

    /**
     * 判断是否是多语句。
     *
     * <p>用解析器而不是数分号，因为分号可能出现在字符串字面量里
     * （{@code WHERE name = 'a;b'}）。解析器会正确处理引号与转义。
     * 但解析器对「结尾多一个分号」也会算成一条语句，所以先剥掉尾部分号再数。
     */
    private boolean containsMultipleStatements(String sql) {
        String stripped = sql.strip();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(stripped);
            return statements.size() > 1;
        } catch (Exception e) {
            // 解析失败交给后面的 parse 报更准确的信息。
            return false;
        }
    }

    /**
     * 递归收集所有函数名，与黑名单比对。
     *
     * <p>为什么要遍历整棵 AST 而不是只看最外层：危险函数常常藏在子查询、
     * CTE、或 WHERE 条件里。例如
     * {@code SELECT 1 WHERE pg_sleep(60) IS NOT NULL}——最外层看着完全无害。
     */
    private void collectForbiddenFunctions(Select select, List<ValidationResult.Violation> violations) {
        Set<String> forbidden = properties.getGuard().getForbiddenFunctions().stream()
                .map(f -> f.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        if (forbidden.isEmpty()) {
            return;
        }

        Set<String> hit = new LinkedHashSet<>();
        // 用 visitor 遍历整棵树，而不是只看最外层：危险函数常藏在子查询 / CTE / WHERE 里。
        // 例如 SELECT 1 WHERE pg_sleep(60) IS NOT NULL —— 最外层看着完全无害。
        net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Void> expressionVisitor =
                new net.sf.jsqlparser.expression.ExpressionVisitorAdapter<>() {
                    @Override
                    public <S> Void visit(Function function, S context) {
                        String name = function.getName() == null ? "" : function.getName().toLowerCase(Locale.ROOT);
                        if (forbidden.contains(name)) {
                            hit.add(name);
                        }
                        return super.visit(function, context);
                    }
                };
        select.accept(linkVisitors(expressionVisitor), null);

        hit.forEach(name -> violations.add(new ValidationResult.Violation(
                ValidationResult.Code.FORBIDDEN_FUNCTION, "使用了禁止的函数：" + name)));
    }

    /**
     * 列级白名单：带别名前缀的列，必须真的属于它声明的那张表。
     *
     * <p><b>只做一半，是刻意的</b>：裸列名（没有 {@code o.} 这种前缀）不检查。
     * 裸列名的合法来源太多——SELECT 别名、CTE 输出列、派生表列、集合操作两侧的同名列——
     * 一旦判错，正常查询会被拦下，护栏就从「防护」变成「故障源」。
     * 带前缀的列能精确归属到某张表，判定是确定的，所以先把这一半做扎实。
     *
     * <p><b>它挡住的真实错误</b>：多表题里模型常把维度表猜错，写出
     * {@code sellers.region_name}（sellers 没有这一列，它在 regions 上）。
     * 这类 SQL 到 PostgreSQL 会报 column does not exist，能在执行前拦下就省一次往返。
     *
     * <p><b>别名的作用域问题（这条是被 dry-run 抓出来的）</b>
     *
     * <p>SQL 里的别名是**按作用域**生效的，但这里遍历得到的是一张扁平表。
     * 两者对冲就会出事：T6-016 的标准 SQL 里，外层写 {@code FROM brands bp}，
     * 而 CTE 内部写 {@code FROM brand_products bp}——同一个 {@code bp} 指两张不同的关系。
     * 压平之后 {@code bp.product_id} 被当成 {@code brands.product_id}，正常查询被误杀。
     *
     * <p>修法不是去实现完整的词法作用域解析（那要按子查询层级维护符号表，复杂度陡增），
     * 而是承认「这个简化模型有能力边界」：**只要某个别名在语句里被绑定到过
     * CTE 或派生表，就无法确定它指向哪个关系，该别名的列一律不判定。**
     * 少拦几条，换不误杀——护栏的第一原则是别把正常流量打挂。
     *
     * <p>多个作用域把同一别名绑到不同 schema 表时也类似处理：只有当所有候选表
     * 都没有这一列时才报错。
     */
    private void collectUnknownColumns(Select select, SchemaContext schema,
                                       List<ValidationResult.Violation> violations) {
        Map<String, Set<String>> columnsOfTable = new java.util.HashMap<>();
        for (SchemaContext.Table table : schema.tables()) {
            Set<String> cols = new LinkedHashSet<>();
            table.columns().forEach(c -> cols.add(c.name().toLowerCase(Locale.ROOT)));
            columnsOfTable.put(table.name().toLowerCase(Locale.ROOT), cols);
        }

        Map<String, Set<String>> aliasToTables = new java.util.HashMap<>();
        Set<String> aliasesWithUnknownRelation = new LinkedHashSet<>();
        List<net.sf.jsqlparser.schema.Column> columns = new ArrayList<>();

        net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Void> expressionVisitor =
                new net.sf.jsqlparser.expression.ExpressionVisitorAdapter<>() {
                    @Override
                    public <S> Void visit(net.sf.jsqlparser.schema.Column column, S context) {
                        columns.add(column);
                        return super.visit(column, context);
                    }
                };
        net.sf.jsqlparser.statement.select.FromItemVisitorAdapter<Void> fromItemVisitor =
                new net.sf.jsqlparser.statement.select.FromItemVisitorAdapter<>() {
                    @Override
                    public <S> Void visit(net.sf.jsqlparser.schema.Table table, S context) {
                        if (table.getName() != null) {
                            String name = table.getName().toLowerCase(Locale.ROOT);
                            String alias = table.getAlias() != null
                                    ? table.getAlias().getName().toLowerCase(Locale.ROOT) : name;
                            if (columnsOfTable.containsKey(name)) {
                                aliasToTables.computeIfAbsent(alias, k -> new LinkedHashSet<>()).add(name);
                            } else {
                                // CTE 名、派生表、或不在本次上下文里的表：无法确定指向，
                                // 把这个别名整体标成不可判定。
                                aliasesWithUnknownRelation.add(alias);
                            }
                        }
                        return super.visit(table, context);
                    }

                    @Override
                    public <S> Void visit(net.sf.jsqlparser.statement.select.ParenthesedSelect nested,
                                          S context) {
                        Select inner = nested.getSelect();
                        return inner == null ? null : inner.accept(getSelectVisitor(), context);
                    }
                };
        net.sf.jsqlparser.statement.select.SelectVisitorAdapter<Void> selectVisitor =
                new net.sf.jsqlparser.statement.select.SelectVisitorAdapter<>(expressionVisitor, fromItemVisitor);
        expressionVisitor.setSelectVisitor(selectVisitor);
        fromItemVisitor.setSelectVisitor(selectVisitor).setExpressionVisitor(expressionVisitor);

        // 先走完整棵树收集别名和列引用，再统一判定——避免「列先出现、别名后注册」的顺序问题。
        select.accept(selectVisitor, null);

        Set<String> reported = new LinkedHashSet<>();
        for (net.sf.jsqlparser.schema.Column column : columns) {
            if (column.getTable() == null || column.getTable().getName() == null) {
                continue; // 裸列名：跳过，理由见方法注释
            }
            String qualifier = column.getTable().getName().toLowerCase(Locale.ROOT);
            if (aliasesWithUnknownRelation.contains(qualifier)) {
                continue; // 该别名至少有一处指向 CTE / 派生表，无法确定，不判定
            }
            Set<String> tables = aliasToTables.get(qualifier);
            if (tables == null || tables.isEmpty()) {
                continue; // 别名指向未识别来源，不判定
            }
            String name = column.getColumnName() == null ? "" : column.getColumnName().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            // 只有当所有候选表都没有这一列时才报错：有的有、有的没有，说明
            // 别名跨作用域指向了不同表，此时无法判定，宁可放过。
            boolean anyHasIt = tables.stream().anyMatch(t -> columnsOfTable.getOrDefault(t, Set.of()).contains(name));
            if (!anyHasIt && reported.add(qualifier + "." + name)) {
                violations.add(new ValidationResult.Violation(ValidationResult.Code.UNKNOWN_COLUMN,
                        "表 " + String.join("/", tables) + " 没有列 " + column.getColumnName()));
            }
        }
    }

    /**
     * 把几个 visitor 互相接上，组成一棵能走完整棵 AST 的访问链。
     *
     * <p><b>这里有一个真实踩过的坑，值得记住</b>：只写
     * {@code new SelectVisitorAdapter<>(expressionVisitor)} 然后
     * {@code select.accept(adapter)}，看起来能遍历，实际会**漏掉子查询里的内容**。
     * 原因是访问链是单向的：SelectVisitor 知道怎么把表达式交给
     * ExpressionVisitor，但 ExpressionVisitor 不知道遇到子查询时该回头
     * 找谁继续往下走，于是子查询内部被整块跳过。
     *
     * <p>后果很严重：{@code SELECT 1 WHERE pg_sleep(60) IS NOT NULL} 这种
     * 藏在子查询里的危险函数会被放行——护栏看着有，实际是个洞。
     * 这个 bug 是靠 {@code SqlValidatorTest.rejectsForbiddenFunctionEvenInSubquery}
     * 抓出来的，那条测试因此不能删。
     *
     * <p>修法是把双向关系都接上：ExpressionVisitor 也要知道 SelectVisitor
     * 和 FromItemVisitor 是谁（子查询出现在 WHERE 里和出现在 FROM 里，
     * 走的是两条不同的路径）。这是典型的「安全组件必须靠测试证明，
     * 不能靠读代码感觉没问题」的例子。
     */
    private static net.sf.jsqlparser.statement.select.SelectVisitorAdapter<Void> linkVisitors(
            net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Void> expressionVisitor) {
        net.sf.jsqlparser.statement.select.FromItemVisitorAdapter<Void> fromItemVisitor =
                new net.sf.jsqlparser.statement.select.FromItemVisitorAdapter<>() {
                    @Override
                    public <S> Void visit(net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesedSelect,
                                          S context) {
                        net.sf.jsqlparser.statement.select.Select nested = parenthesedSelect.getSelect();
                        return nested == null ? null : nested.accept(getSelectVisitor(), context);
                    }
                };
        net.sf.jsqlparser.statement.select.SelectVisitorAdapter<Void> selectVisitor =
                new net.sf.jsqlparser.statement.select.SelectVisitorAdapter<>(expressionVisitor, fromItemVisitor);
        expressionVisitor.setSelectVisitor(selectVisitor);
        fromItemVisitor.setSelectVisitor(selectVisitor).setExpressionVisitor(expressionVisitor);
        return selectVisitor;
    }

    /** 只有「可能返回多行」的查询才需要 LIMIT。单行聚合值不需要。 */
    private boolean needsLimit(Select select) {
        // 必须用 instanceof，不能用 select.getPlainSelect()：JSqlParser 5.4 的
        // getPlainSelect() 内部是裸强转（checkcast PlainSelect），遇到 UNION
        // （SetOperationList）会直接抛 ClassCastException，而不是返回 null。
        // 之前按「返回 null」写的分支从未生效，模型一旦生成 UNION 就会中断整轮评估。
        if (!(select instanceof PlainSelect plain)) {
            return true; // UNION 等结构保守处理，要求有 LIMIT
        }
        // 没有 GROUP BY 且所有 select item 都是聚合函数 → 一定只有一行
        if (plain.getGroupBy() == null && plain.getDistinct() == null) {
            boolean allAggregate = !plain.getSelectItems().isEmpty()
                    && plain.getSelectItems().stream().allMatch(this::isAggregateItem);
            if (allAggregate) {
                return false;
            }
        }
        return true;
    }

    private boolean isAggregateItem(net.sf.jsqlparser.statement.select.SelectItem<?> item) {
        Expression expression = item.getExpression();
        return expression instanceof Function fn && isAggregateFunction(fn.getName());
    }

    private boolean isAggregateFunction(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "count", "sum", "avg", "min", "max", "stddev", "variance", "array_agg", "string_agg",
                 "bool_and", "bool_or", "every" -> true;
            default -> false;
        };
    }

    /**
     * 判断语句是否已经有 LIMIT。
     *
     * <p><b>为什么不能只看 {@code select.getLimit()}</b>
     *
     * <p>PostgreSQL 里 {@code A UNION B LIMIT 5} 的 LIMIT 作用于合并后的整体结果，
     * 但 JSqlParser 会把这条 LIMIT 挂在**最后一个子查询**上，外层
     * {@code SetOperationList.getLimit()} 仍然是 null。
     *
     * <p>只看外层会漏判，于是进入补 LIMIT 分支，在 AST 外层再设一个 limit，
     * toString() 出来就是：
     * <pre>... UNION ... LIMIT 5 LIMIT 200</pre>
     * 这是语法错误，PostgreSQL 直接报 42601。表现为日志里一条
     * {@code bad SQL grammar}，而模型写的 SQL 其实完全正确——
     * 错误是校验层自己制造的。所以这里必须递归看最后一个子查询。
     */
    private boolean hasLimit(Select select) {
        if (select.getLimit() != null) {
            return true;
        }
        if (select instanceof SetOperationList setOperationList) {
            List<Select> selects = setOperationList.getSelects();
            if (!selects.isEmpty()) {
                return hasLimit(selects.get(selects.size() - 1));
            }
        }
        return false;
    }

    /**
     * 给 SQL 补上 LIMIT。
     *
     * <p>选择在 AST 上改写而不是字符串拼接，是因为拼接要考虑「SQL 末尾有没有
     * 分号」「末尾有没有注释」「有没有 OFFSET」这些情况，很容易拼出语法错误的语句。
     * 在 AST 上设置 limit 字段，再 toString() 回 SQL，由解析器保证语法正确。
     */
    private void applyLimit(Select select, int maxRows) {
        Limit limit = new Limit();
        limit.setRowCount(new LongValue(maxRows));
        select.setLimit(limit);
    }
}
