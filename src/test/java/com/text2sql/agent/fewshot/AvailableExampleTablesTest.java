package com.text2sql.agent.fewshot;

import com.text2sql.agent.evaluation.TableRecall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvailableExampleTablesTest {
    private static Example example(String id, String question, String sql) {
        return new Example(id, "fixture", question, sql, TableRecall.expectedTables(sql));
    }

    private static ExampleSelector selector(Example... examples) {
        var loader = mock(ExampleLoader.class);
        when(loader.getExamples()).thenReturn(List.of(examples));
        return new ExampleSelector(loader);
    }

    @Test void missingDependencyCannotOutrankCompatibleExampleEvenWithMoreOverlap() {
        var unsafe = example("A", "unsafe", "SELECT * FROM orders o JOIN customers c ON true JOIN members m ON true");
        var safe = example("B", "safe", "SELECT COUNT(*) FROM orders");
        var selector = selector(unsafe, safe);
        assertThat(selector.select("question", Set.of("orders", "customers"), 1)).containsExactly(unsafe);
        assertThat(selector.select("question", Set.of("orders", "customers"), 1, true)).containsExactly(safe);
    }

    @Test void shortageDoesNotBackfillFromIncompatibleExamples() {
        var safe = example("A", "safe", "SELECT COUNT(*) FROM orders");
        var unrelated = example("B", "unrelated", "SELECT COUNT(*) FROM products");
        assertThat(selector(safe, unrelated).select("question", Set.of("orders"), 2, true)).containsExactly(safe);
    }

    @Test void noCompatibleExamplesMeansNoExamples() {
        var ex = example("A", "other", "SELECT * FROM products");
        assertThat(selector(ex).select("question", Set.of("orders"), 2, true)).isEmpty();
    }

    @Test void unknownAndInvalidDependenciesAreExcludedConservatively() {
        var invalid = example("A", "broken", "THIS IS NOT SQL");
        var tableless = example("B", "constant", "SELECT 1");
        var unknown = new Example("C", "fixture", "unknown", "SELECT 1", null);
        var blank = new Example("D", "fixture", "blank", "SELECT 1", Set.of(""));
        assertThat(selector(invalid, tableless, unknown, blank).select("question", Set.of("orders"), 5, true)).isEmpty();
    }

    @Test void cteAliasIsNotATableButDependenciesInsideTheCteRemainRequired() {
        var ex = example("A", "CTE", "WITH x AS (SELECT * FROM orders) SELECT * FROM x");
        assertThat(ex.tables()).containsExactly("orders");
        assertThat(selector(ex).select("question", Set.of("orders"), 1, true)).containsExactly(ex);
    }

    @Test void nestedSubqueryDependenciesCannotEscapeTheAvailabilityCheck() {
        var ex = example("A", "nested", "SELECT * FROM orders WHERE customer_id IN (SELECT customer_id FROM customers)");
        assertThat(selector(ex).select("question", Set.of("orders"), 1, true)).isEmpty();
    }

    @Test void dependencyMatchingIgnoresIdentifierCase() {
        var ex = example("A", "uppercase", "SELECT * FROM ORDERS");
        assertThat(selector(ex).select("question", Set.of("OrDeRs"), 1, true)).containsExactly(ex);
    }

    @Test void selfQuestionStillCannotBeInjectedInEitherPolicy() {
        var ex = example("A", "Count orders", "SELECT COUNT(*) FROM orders");
        for (boolean strict : List.of(false, true))
            assertThat(selector(ex).select(" count ORDERS ", Set.of("orders"), 1, strict)).isEmpty();
    }

    @Test void eligibleCandidatesRetainDeterministicRankingWhenLoaderOrderChanges() {
        var a = example("A", "first", "SELECT COUNT(*) FROM orders");
        var b = example("B", "second", "SELECT * FROM orders");
        assertThat(selector(b, a).select("question", Set.of("orders"), 2, true)).containsExactly(a, b);
        assertThat(selector(a, b).select("question", Set.of("orders"), 2, true)).containsExactly(a, b);
    }

    @Test void nullOrEmptyContextCannotAdmitTableDependentExamples() {
        var ex = example("A", "first", "SELECT COUNT(*) FROM orders");
        assertThat(selector(ex).select("question", null, 2, true)).isEmpty();
        assertThat(selector(ex).select("question", Set.of(), 2, true)).isEmpty();
    }

    @Test void disabledLimitCannotInjectAnything() {
        var ex = example("A", "first", "SELECT COUNT(*) FROM orders");
        assertThat(selector(ex).select("question", Set.of("orders"), 0, true)).isEmpty();
        assertThat(selector(ex).select("question", Set.of("orders"), -1, true)).isEmpty();
    }

    @Test void explicitLegacyFlagPreservesRealLibrarySelection() {
        var loader = new ExampleLoader();
        loader.init();
        var selector = new ExampleSelector(loader);
        var tables = Set.of("orders", "customers", "order_items");
        assertThat(selector.select("fixture", tables, 2, false)).isEqualTo(selector.select("fixture", tables, 2));
        assertThat(new com.text2sql.agent.config.AgentProperties().getFewshot().isRequireAvailableTables()).isFalse();
    }
}
