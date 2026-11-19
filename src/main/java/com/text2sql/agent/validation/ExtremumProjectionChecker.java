package com.text2sql.agent.validation;

import com.text2sql.agent.retrieval.SchemaCatalog;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A narrow, advisory contract: a singular entity extremum retains its numeric ranking attribute.
 * Unknown intent or SQL lineage means abstention, never a correctness certificate.
 */
@Component
public final class ExtremumProjectionChecker {
    public static final String POLICY = "entity-extremum-attribute-v1";
    public static final String RULE = "MISSING_EXTREMUM_ATTRIBUTE";
    private static final Pattern ENTITY = Pattern.compile("哪一个|哪个|哪家|哪座|哪笔|哪条|是谁");
    private static final Pattern UNSCOPED = Pattern.compile(
            "不|未|非|否|平均|合计|总|增长|涨|跌|差|环比|同比|占比|比率|比例|分别|或者|还是|以及|并且|且|和|与|[、,，;；]"
                    + "|(?:只|仅)(?:返回|输出|列出|显示|要)|(?:前|top)\\d+|\\d+个|[二三四五六七八九十百]+个");
    private static final String EXTREMUM = "(?:最大|最小|最高|最低|最多|最少)";
    private static final Set<String> NUMERIC = Set.of("smallint", "integer", "bigint", "numeric", "decimal",
            "real", "double precision", "int2", "int4", "int8", "float4", "float8");
    private final Supplier<Glossary> glossary;
    private final Supplier<SchemaContext> schema;

    @Autowired
    public ExtremumProjectionChecker(GlossaryLoader glossary, SchemaCatalog catalog) {
        this(glossary::get, catalog::full);
    }

    ExtremumProjectionChecker(Supplier<Glossary> glossary, Supplier<SchemaContext> schema) {
        this.glossary = glossary;
        this.schema = schema;
    }

    public record Field(String table, String column) {
        @Override public String toString() { return table + "." + column; }
    }
    public record Requirement(String policy, String tableAlias, String columnAlias, Field field) { }
    public enum Status { NOT_APPLICABLE, PRESENT, MISSING, UNKNOWN }
    public record Decision(Status status, Requirement requirement, String reason) { }

    public CheckResult check(String question, String sql) {
        Decision decision = inspect(question, sql);
        if (decision.status() != Status.MISSING) return CheckResult.NORMAL;
        Requirement r = decision.requirement();
        return CheckResult.suspicious(RULE,
                "依据产品契约 " + r.policy() + " 及词典「" + r.tableAlias() + " / " + r.columnAlias()
                        + "」绑定 " + r.field() + "；实体寻优结果的最外层SELECT遗漏该属性原值。"
                        + "请保留实体标识并输出该属性；只修复投影，不改变过滤、分组或排名总体。");
    }

    public Decision inspect(String question, String sql) {
        String q = normalizeQuestion(question);
        // Resolve only one numeric entity attribute. Do not use retrieval ranking to break ambiguity.
        if (q.length() > 500 || !ENTITY.matcher(q).find() || UNSCOPED.matcher(q).find() || !q.matches(".*" + EXTREMUM + ".*")) {
            return new Decision(Status.NOT_APPLICABLE, null, "outside singular attribute contract");
        }
        SchemaContext context = schema.get();
        Map<String, SchemaContext.Table> tables = new LinkedHashMap<>();
        context.tables().forEach(t -> tables.put(identifier(t.name()), t));
        Map<Field, Requirement> bindings = new LinkedHashMap<>();
        glossary.get().tables().forEach((tableName, entry) -> {
            SchemaContext.Table table = tables.get(identifier(tableName));
            if (table == null) return;
            String entity = longestMatch(q, entry.aliases());
            if (entity == null) return;
            for (SchemaContext.Column column : table.columns()) {
                Glossary.ColumnEntry words = entry.columns().get(column.name());
                if (words == null || !numeric(column.type())) continue;
                String attribute = words.aliases().stream().filter(a -> !a.isBlank())
                        .map(ExtremumProjectionChecker::normalizeQuestion)
                        .filter(a -> matchesRequest(q, entity, a))
                        .max(Comparator.comparingInt(String::length)).orElse(null);
                if (attribute != null) {
                    Field field = new Field(identifier(table.name()), identifier(column.name()));
                    bindings.put(field, new Requirement(POLICY, entity, attribute, field));
                }
            }
        });
        if (bindings.size() != 1) return new Decision(Status.NOT_APPLICABLE, null, "no unique glossary binding");
        Requirement requirement = bindings.values().iterator().next();
        if (sql == null || sql.length() > 20_000) return new Decision(Status.UNKNOWN, requirement, "SQL outside analysis bound");
        try {
            if (!(CCJSqlParserUtil.parse(sql) instanceof Select select)) {
                return new Decision(Status.UNKNOWN, requirement, "not a SELECT");
            }
            Projection projection = project(select, Map.of(), tables, 0);
            if (projection.outputs().stream().anyMatch(o -> requirement.field().equals(o.trace().field()))) {
                return new Decision(Status.PRESENT, requirement, "direct projected source resolved");
            }
            if (!projection.complete() || projection.outputs().stream().anyMatch(o -> !o.trace().known())) {
                return new Decision(Status.UNKNOWN, requirement, "projection lineage incomplete");
            }
            return new Decision(Status.MISSING, requirement, "resolved outer projection lacks attribute");
        } catch (Exception unsupported) {
            // Safety SQL validation already runs independently. This semantic hint must not reject dialects.
            return new Decision(Status.UNKNOWN, requirement, "SQL lineage unavailable");
        }
    }

    private record Trace(Field field, boolean known) {
        static Trace unknown() { return new Trace(null, false); }
        static Trace constant() { return new Trace(null, true); }
    }
    private record Output(String label, Trace trace) { }
    private record Projection(List<Output> outputs, boolean complete) {
        static Projection unknown() { return new Projection(List.of(), false); }
    }
    private record Relation(String name, Projection projection) { }

    private static Projection project(Select select, Map<String, Projection> inherited,
                                      Map<String, SchemaContext.Table> tables, int depth) {
        if (depth > 12) return Projection.unknown();
        Map<String, Projection> ctes = new LinkedHashMap<>(inherited);
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item.isRecursive() || !(item.getStatement() instanceof ParenthesedSelect body)) return Projection.unknown();
                String name = identifier(item.getAliasName());
                // Shadow the base table/outer CTE before analyzing; self references stay unknown.
                ctes.put(name, Projection.unknown());
                Projection inner = project(body.getSelect(), ctes, tables, depth + 1);
                if (item.getWithItemList() != null && !item.getWithItemList().isEmpty()) {
                    if (!inner.complete() || item.getWithItemList().size() != inner.outputs().size()) return Projection.unknown();
                    List<Output> renamed = new ArrayList<>();
                    for (int i = 0; i < inner.outputs().size(); i++) {
                        if (!(item.getWithItemList().get(i).getExpression() instanceof Column column)) return Projection.unknown();
                        renamed.add(new Output(identifier(column.getColumnName()), inner.outputs().get(i).trace()));
                    }
                    inner = new Projection(List.copyOf(renamed), true);
                }
                ctes.put(name, inner);
            }
        }
        if (select instanceof ParenthesedSelect nested) return project(nested.getSelect(), ctes, tables, depth + 1);
        if (!(select instanceof PlainSelect plain)) return Projection.unknown();
        List<Relation> scope = new ArrayList<>();
        if (plain.getFromItem() != null) scope.add(relation(plain.getFromItem(), ctes, tables, depth + 1));
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                // USING/NATURAL merge columns; abstain rather than guess the exposed identity.
                if (join.isNatural() || (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty())) return Projection.unknown();
                scope.add(relation(join.getRightItem(), ctes, tables, depth + 1));
            }
        }
        List<Output> outputs = new ArrayList<>();
        boolean complete = true;
        for (SelectItem<?> item : plain.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns star) {
                if (star.getExceptColumns() != null || star.getReplaceExpressions() != null) return Projection.unknown();
                List<Relation> expanded = expression instanceof AllTableColumns qualified
                        ? scope.stream().filter(r -> r.name().equals(identifier(qualified.getTable().getName()))).toList() : scope;
                if (expanded.isEmpty()) complete = false;
                for (Relation r : expanded) {
                    outputs.addAll(r.projection().outputs());
                    complete &= r.projection().complete();
                }
            } else {
                String label = item.getAlias() != null ? identifier(item.getAliasName())
                        : expression instanceof Column c ? identifier(c.getColumnName()) : "";
                Trace trace = expression instanceof Column column ? resolve(column, scope)
                        : expression instanceof LongValue || expression instanceof DoubleValue
                        || expression instanceof StringValue || expression instanceof NullValue
                        ? Trace.constant() : Trace.unknown();
                outputs.add(new Output(label, trace));
            }
        }
        return new Projection(List.copyOf(outputs), complete);
    }

    private static Relation relation(FromItem from, Map<String, Projection> ctes,
                                     Map<String, SchemaContext.Table> tables, int depth) {
        String alias = from.getAlias() == null ? "" : identifier(from.getAlias().getName());
        if (from.getAlias() != null && from.getAlias().getAliasColumns() != null
                && !from.getAlias().getAliasColumns().isEmpty()) return new Relation(alias, Projection.unknown());
        if (from instanceof Table table) {
            String name = identifier(table.getName());
            String exposed = alias.isEmpty() ? name : alias;
            String namespace = identifier(table.getSchemaName());
            if (!namespace.isEmpty() && !namespace.equals("public")) return new Relation(exposed, Projection.unknown());
            if (namespace.isEmpty() && ctes.containsKey(name)) return new Relation(exposed, ctes.get(name));
            SchemaContext.Table metadata = tables.get(name);
            if (metadata == null) return new Relation(exposed, Projection.unknown());
            return new Relation(exposed, new Projection(metadata.columns().stream()
                    .map(c -> new Output(identifier(c.name()), new Trace(new Field(name, identifier(c.name())), true)))
                    .toList(), true));
        }
        if (from instanceof ParenthesedSelect nested) return new Relation(alias, project(nested.getSelect(), ctes, tables, depth + 1));
        return new Relation(alias, Projection.unknown());
    }

    private static Trace resolve(Column column, List<Relation> scope) {
        String qualifier = column.getTable() == null ? "" : identifier(column.getTable().getName());
        List<Relation> candidates = qualifier.isEmpty() ? scope
                : scope.stream().filter(r -> r.name().equals(qualifier)).toList();
        if (candidates.isEmpty() || candidates.stream().anyMatch(r -> !r.projection().complete())) return Trace.unknown();
        List<Output> matches = candidates.stream().flatMap(r -> r.projection().outputs().stream())
                .filter(o -> o.label().equals(identifier(column.getColumnName()))).toList();
        return matches.size() == 1 ? matches.get(0).trace() : Trace.unknown();
    }

    private static boolean numeric(String type) {
        return type != null && NUMERIC.contains(type.toLowerCase(Locale.ROOT).replaceAll("\\(.*", "").strip());
    }
    private static boolean matchesRequest(String question, String entity, String attribute) {
        String e = Pattern.quote(entity), a = Pattern.quote(attribute);
        String which = "(?:哪一个|哪个|哪家|哪座|哪笔|哪条|谁)";
        String request = "(?:" + a + EXTREMUM + "的?" + e + "(?:是|为)?" + which
                + "|" + which + e + "的?" + a + EXTREMUM
                + "|" + e + "的?" + a + EXTREMUM + "的?(?:是|为)?" + which + ")";
        // Full-clause matching avoids transferring a warehouse ranking requirement to a nested member question.
        return question.matches("(?:请问|请查询|查询|查找|找出|请找出)?" + request + "[?？。.!！]*");
    }
    private static String longestMatch(String question, List<String> aliases) {
        return aliases.stream().filter(a -> a != null && !a.isBlank()).map(ExtremumProjectionChecker::normalizeQuestion)
                .filter(question::contains).max(Comparator.comparingInt(String::length)).orElse(null);
    }
    private static String normalizeQuestion(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
    private static String identifier(String value) {
        if (value == null) return "";
        return value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1).replace("\"\"", "\"") : value.toLowerCase(Locale.ROOT);
    }
}
