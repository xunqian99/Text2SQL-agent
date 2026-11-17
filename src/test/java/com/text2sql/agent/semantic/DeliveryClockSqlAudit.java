package com.text2sql.agent.semantic;

import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;

/** Test/probe-only provenance audit of the single AVG clock, not a general SQL equivalence checker. */
final class DeliveryClockSqlAudit {
    record Requirement(String end, String start) { }
    record Result(String status, String reason, Map<String, String> tableAliases, List<String> avgColumns) { }

    static Result inspect(String sql, Requirement expected) {
        Map<String, String> aliases = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>();
        try {
            var statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof PlainSelect select) || select.getWithItemsList() != null)
                return unsupported("Only a direct SELECT with base tables is audited", aliases, columns);
            if (!addTable(select.getFromItem(), aliases))
                return unsupported("Unsupported or ambiguous FROM source", aliases, columns);
            if (select.getJoins() != null) for (var join : select.getJoins()) {
                if (!addTable(join.getRightItem(), aliases))
                    return unsupported("Unsupported or ambiguous JOIN source", aliases, columns);
            }
            List<Function> averages = new ArrayList<>();
            boolean[] nested = {false};
            var collector = new ExpressionVisitorAdapter<Void>() {
                @Override public <S> Void visit(Function function, S context) {
                    if ("AVG".equalsIgnoreCase(function.getName())) averages.add(function);
                    return super.visit(function, context);
                }
                @Override public <S> Void visit(ParenthesedSelect query, S context) { nested[0] = true; return null; }
            };
            for (var item : select.getSelectItems()) item.getExpression().accept(collector, null);
            if (nested[0]) return unsupported("Nested projection queries are not audited", aliases, columns);
            if (averages.size() != 1) return unsupported("Expected exactly one projected AVG", aliases, columns);
            var average = averages.getFirst();
            if (average.isDistinct()) return new Result("FAIL", "DISTINCT changes averaging grain", aliases, columns);
            List<Subtraction> differences = new ArrayList<>();
            List<ExtractExpression> epochs = new ArrayList<>();
            average.accept(new ExpressionVisitorAdapter<Void>() {
                @Override public <S> Void visit(Subtraction difference, S context) {
                    differences.add(difference); return super.visit(difference, context);
                }
                @Override public <S> Void visit(ExtractExpression extract, S context) {
                    if ("EPOCH".equalsIgnoreCase(extract.getName())) epochs.add(extract);
                    return super.visit(extract, context);
                }
                @Override public <S> Void visit(Column column, S context) {
                    columns.add(resolve(column, aliases)); return null;
                }
            }, null);
            if (differences.size() != 1 || epochs.size() != 1
                    || unwrap(epochs.getFirst().getExpression()) != differences.getFirst())
                return unsupported("Expected one EPOCH(end-start) inside AVG", aliases, columns);
            var difference = differences.getFirst();
            String end = resolve(unwrap(difference.getLeftExpression()), aliases);
            String start = resolve(unwrap(difference.getRightExpression()), aliases);
            if (end == null || start == null || columns.contains(null))
                return unsupported("Clock columns must resolve to direct base-table sources", aliases, columns);
            boolean pass = end.equals(expected.end()) && start.equals(expected.start())
                    && columns.equals(List.of(expected.end(), expected.start()));
            return new Result(pass ? "PASS" : "FAIL", pass ? "AVG clock matches required sources"
                    : "AVG clock sources differ from the frozen requirement", aliases, columns);
        } catch (Exception exception) {
            return unsupported("Parse/structure unsupported: " + exception.getClass().getSimpleName(), aliases, columns);
        }
    }

    private static boolean addTable(FromItem item, Map<String, String> aliases) {
        if (!(item instanceof Table table) || (table.getSchemaName() != null
                && !"public".equalsIgnoreCase(table.getSchemaName()))) return false;
        String name = identifier(table.getName());
        String alias = table.getAlias() == null ? name : identifier(table.getAlias().getName());
        return aliases.putIfAbsent(alias, name) == null;
    }

    private static Expression unwrap(Expression expression) {
        while (true) {
            if (expression instanceof CastExpression cast) expression = cast.getLeftExpression();
            else if (expression instanceof ParenthesedExpressionList<?> list && list.size() == 1) expression = list.getFirst();
            else return expression;
        }
    }

    private static String resolve(Expression expression, Map<String, String> aliases) {
        if (!(expression instanceof Column column)) return null;
        String qualifier = column.getTable() == null ? null : column.getTable().getName();
        String table = qualifier == null || qualifier.isBlank()
                ? (aliases.size() == 1 ? aliases.values().iterator().next() : null) : aliases.get(identifier(qualifier));
        return table == null ? null : table + "." + identifier(column.getColumnName());
    }

    private static String identifier(String value) { return value.replace("\"", "").toLowerCase(Locale.ROOT); }
    private static Result unsupported(String reason, Map<String, String> aliases, List<String> columns) {
        return new Result("UNSUPPORTED", reason, aliases, columns);
    }
}
