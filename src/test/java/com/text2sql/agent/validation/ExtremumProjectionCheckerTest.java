package com.text2sql.agent.validation;

import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.retrieval.SchemaContext;
import com.text2sql.agent.retrieval.glossary.Glossary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static com.text2sql.agent.validation.ExtremumProjectionChecker.Status.*;

class ExtremumProjectionCheckerTest {
    private static final String QUESTION = "容量最大的仓库是哪一个？";
    private static final SchemaContext SCHEMA = new SchemaContext(List.of(
            table("warehouses", "warehouse_id", "warehouse_name", "capacity"),
            table("members", "member_id", "name", "points")), List.of(), "", "");
    private static final Glossary GLOSSARY = new Glossary(List.of(), Map.of(
            "warehouses", entry("仓库", "capacity", "容量"), "members", entry("会员", "points", "积分")));

    private static SchemaContext.Table table(String name, String id, String label, String measure) {
        return new SchemaContext.Table(name, "", List.of(
                new SchemaContext.Column(id, "integer", false, "", true),
                new SchemaContext.Column(label, "text", true, ""),
                new SchemaContext.Column(measure, "numeric(12,2)", true, "")));
    }
    private static Glossary.TableEntry entry(String entity, String field, String attribute) {
        return new Glossary.TableEntry(List.of(entity), "",
                Map.of(field, new Glossary.ColumnEntry(List.of(attribute), List.of())));
    }
    private static ExtremumProjectionChecker checker() {
        return new ExtremumProjectionChecker(() -> GLOSSARY, () -> SCHEMA);
    }

    @Test
    void reportsPolicyAndGlossaryOriginsWithoutUsingOutputLabels() {
        var d = checker().inspect(QUESTION,
                "SELECT warehouse_id,warehouse_name FROM warehouses ORDER BY capacity DESC LIMIT 1");
        assertThat(d.status()).isEqualTo(MISSING);
        assertThat(d.requirement()).isEqualTo(new ExtremumProjectionChecker.Requirement(
                ExtremumProjectionChecker.POLICY, "仓库", "容量",
                new ExtremumProjectionChecker.Field("warehouses", "capacity")));
        var feedback = checker().check(QUESTION, "SELECT warehouse_id FROM warehouses ORDER BY capacity DESC LIMIT 1");
        assertThat(feedback.rule()).isEqualTo("MISSING_EXTREMUM_ATTRIBUTE");
        assertThat(feedback.reason()).contains("warehouses.capacity", "词典", ExtremumProjectionChecker.POLICY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT warehouse_id,capacity AS arbitrary_name FROM warehouses ORDER BY capacity DESC LIMIT 1",
            "SELECT w.warehouse_id,w.capacity FROM public.warehouses w ORDER BY w.capacity DESC LIMIT 1",
            "SELECT * FROM warehouses ORDER BY capacity DESC LIMIT 1",
            "SELECT w.* FROM warehouses w ORDER BY capacity DESC LIMIT 1",
            "SELECT \"capacity\" AS \"Capacity\" FROM \"warehouses\" LIMIT 1",
            "WITH ranked AS (SELECT warehouse_id,capacity AS n FROM warehouses) SELECT warehouse_id,n FROM ranked LIMIT 1",
            "WITH ranked(i,n) AS (SELECT warehouse_id,capacity FROM warehouses) SELECT i,n FROM ranked LIMIT 1",
            "WITH a AS(SELECT capacity AS n FROM warehouses),b AS(SELECT n AS x FROM a) SELECT x FROM b LIMIT 1",
            "SELECT d.c FROM (SELECT warehouse_id,capacity AS c FROM warehouses) d LIMIT 1",
            "SELECT w.capacity,ROUND(1.2) AS other FROM warehouses w LIMIT 1"
    })
    void recognizesDirectProjectionThroughAliasesCtesAndStars(String sql) {
        assertThat(checker().inspect(QUESTION, sql).status()).isEqualTo(PRESENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT warehouse_name AS capacity FROM warehouses ORDER BY capacity DESC LIMIT 1",
            "SELECT warehouse_id,'capacity' AS capacity FROM warehouses ORDER BY warehouse_id LIMIT 1",
            "SELECT w.warehouse_id,m.points AS capacity FROM warehouses w JOIN members m ON m.member_id=w.warehouse_id LIMIT 1",
            "WITH ranked AS(SELECT warehouse_id,capacity AS c FROM warehouses) SELECT warehouse_id FROM ranked LIMIT 1",
            "WITH ranked AS(SELECT warehouse_id,0 AS c FROM warehouses) SELECT warehouse_id,c AS capacity FROM ranked LIMIT 1",
            "SELECT d.warehouse_id FROM (SELECT warehouse_id,capacity FROM warehouses) d LIMIT 1",
            "SELECT warehouse_id FROM warehouses WHERE capacity>0 ORDER BY capacity DESC LIMIT 1",
            "SELECT warehouse_id FROM warehouses GROUP BY warehouse_id,capacity ORDER BY capacity DESC LIMIT 1"
    })
    void innerOrderFilterGroupOrMisleadingAliasDoNotSatisfyOutput(String sql) {
        assertThat(checker().inspect(QUESTION, sql).status()).isEqualTo(MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT ROUND(capacity) FROM warehouses LIMIT 1",
            "SELECT CAST(capacity AS text) FROM warehouses LIMIT 1",
            "SELECT CASE WHEN capacity IS NULL THEN 0 ELSE capacity END FROM warehouses LIMIT 1",
            "SELECT w.warehouse_id,unknown_function(w.warehouse_name) FROM warehouses w LIMIT 1",
            "SELECT (SELECT capacity FROM warehouses LIMIT 1) AS n FROM warehouses LIMIT 1",
            "SELECT warehouse_id FROM warehouses UNION ALL SELECT member_id FROM members LIMIT 1",
            "WITH RECURSIVE a AS(SELECT warehouse_id FROM warehouses) SELECT * FROM a LIMIT 1",
            "SELECT m.* FROM unknown_schema.members m LIMIT 1",
            "SELECT m.* FROM warehouse_function() m LIMIT 1",
            "SELECT warehouse_id FROM warehouses NATURAL JOIN members LIMIT 1",
            "SELECT name FROM members a JOIN members b ON a.member_id=b.member_id LIMIT 1",
            "WITH a AS(SELECT capacity AS x,warehouse_name AS x FROM warehouses) SELECT x FROM a LIMIT 1",
            "WITH warehouses AS(SELECT warehouse_id FROM warehouses) SELECT warehouse_id FROM warehouses LIMIT 1",
            "SELECT * FROM (SELECT capacity FROM warehouses) d(x) LIMIT 1",
            "invalid SQL"
    })
    void unresolvedLineageAbstainsInsteadOfTriggeringCorrection(String sql) {
        assertThat(checker().inspect(QUESTION, sql).status()).isEqualTo(UNKNOWN);
        assertThat(checker().check(QUESTION, sql).suspicious()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "仓库容量最大的是哪一个？", "哪个仓库容量最大？", "请查询容量最大的仓库是哪一个？",
            "容量最小的仓库是哪一个？", " 容量最大 的仓库是 哪一个？ "
    })
    void supportsDeclaredSingularGrammar(String question) {
        assertThat(checker().inspect(question, "SELECT warehouse_id FROM warehouses LIMIT 1").status()).isEqualTo(MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "容量最大的仓库是哪一个？只返回仓库编号", "容量最大的仓库是哪一个？不要输出容量",
            "容量最大的10个仓库是哪些？", "容量最大的十个仓库是谁？", "最大容量是多少？",
            "平均容量最大的仓库是哪一个？", "容量增长率最高的仓库是哪一个？",
            "容量最大还是最小的仓库是哪一个？", "积分最高的会员和容量最大的仓库各是谁？",
            "容量最大的仓库里哪个会员积分最高？", "容量最大的仓库是哪一个？按地区分别列出",
            "编号为10的仓库容量最大吗？", "不看容量，积分最高的会员是谁？"
    })
    void explicitProjectionOverridesCompositeIntentAndUncoveredGrammarAbstain(String question) {
        assertThat(checker().inspect(question, "SELECT warehouse_id FROM warehouses LIMIT 1").status()).isEqualTo(NOT_APPLICABLE);
    }

    @Test
    void usesOtherGlossaryFieldsWithoutWarehouseSpecificCode() {
        var d = checker().inspect("积分最高的会员是谁？", "SELECT member_id FROM members ORDER BY points DESC LIMIT 1");
        assertThat(d.status()).isEqualTo(MISSING);
        assertThat(d.requirement().field()).isEqualTo(new ExtremumProjectionChecker.Field("members", "points"));
    }

    @Test
    void neverBreaksEqualBindingsWithRetrievalOrIterationOrder() {
        var ambiguous = new Glossary(List.of(), Map.of(
                "warehouses", entry("仓库", "capacity", "容量"), "members", entry("仓库", "points", "容量")));
        var checker = new ExtremumProjectionChecker(() -> ambiguous, () -> SCHEMA);
        assertThat(checker.inspect(QUESTION, "SELECT warehouse_id FROM warehouses LIMIT 1").status()).isEqualTo(NOT_APPLICABLE);
    }

    @Test
    void nonNumericAndMissingMetadataDoNotCreateRequirements() {
        var noNumeric = new Glossary(List.of(), Map.of("warehouses", entry("仓库", "warehouse_name", "容量")));
        assertThat(new ExtremumProjectionChecker(() -> noNumeric, () -> SCHEMA)
                .inspect(QUESTION, "SELECT warehouse_id FROM warehouses LIMIT 1").status()).isEqualTo(NOT_APPLICABLE);
        assertThat(new ExtremumProjectionChecker(Glossary::empty, () -> SCHEMA)
                .inspect(QUESTION, "SELECT warehouse_id FROM warehouses LIMIT 1").status()).isEqualTo(NOT_APPLICABLE);
    }

    @Test
    void nonCandidateQuestionsDoNotLoadCatalogOrGlossary() {
        var checker = new ExtremumProjectionChecker(() -> { throw new AssertionError("glossary loaded"); },
                () -> { throw new AssertionError("catalog loaded"); });
        assertThat(checker.inspect("订单数是多少？", "SELECT COUNT(*) FROM orders").status()).isEqualTo(NOT_APPLICABLE);
    }

    @Test
    void sqlLengthAndNestingAreBounded() {
        assertThat(checker().inspect(QUESTION, "SELECT " + " ".repeat(20_001)).status()).isEqualTo(UNKNOWN);
        String sql = "SELECT warehouse_id FROM warehouses";
        for (int i=0;i<15;i++) sql = "SELECT * FROM (" + sql + ") a";
        assertThat(checker().inspect(QUESTION, sql).status()).isEqualTo(UNKNOWN);
    }

    @Test
    void resultCheckerUsesBoundSourceRatherThanJdbcLabel() {
        var checker = new ResultChecker(checker());
        var rows = new QueryResult(List.of("capacity"), List.of(List.of("100")), false, 1);
        assertThat(checker.check(QUESTION, "SELECT warehouse_id AS capacity FROM warehouses LIMIT 1", rows).rule())
                .isEqualTo("MISSING_EXTREMUM_ATTRIBUTE");
        assertThat(checker.check(QUESTION, "SELECT capacity AS arbitrary_name FROM warehouses LIMIT 1", rows).suspicious()).isFalse();
    }
}
