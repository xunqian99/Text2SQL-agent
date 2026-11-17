package com.text2sql.agent.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only counterexamples for independent filtered counts and grouped/time controls. */
@EnabledIf("databaseAvailable")
class CountComparisonDatabaseTest {
    @ParameterizedTest @CsvSource({"delivered,2,1", "shipped,0,1"})
    void namedCountsAreIndependentAndMissingSubsetRemainsZero(String label, int first, int second) throws Exception {
        String sql = "WITH facts(status) AS (VALUES ('delivered'),('delivered'),('canceled'),(NULL::text)) "
                + "SELECT COUNT(*) FILTER(WHERE status='" + label + "'),COUNT(*) FILTER(WHERE status='canceled') FROM facts";
        assertThat(rows(sql)).containsExactly(List.of(String.valueOf(first), String.valueOf(second)));
    }

    @Test void emptyInputReturnsOneRowWithTwoZerosRatherThanNoGroups() throws Exception {
        String source = "WITH facts(status) AS (SELECT NULL::text WHERE FALSE) ";
        assertThat(rows(source + "SELECT COUNT(*) FILTER(WHERE status='delivered'),COUNT(*) FILTER(WHERE status='canceled') FROM facts"))
                .containsExactly(List.of("0", "0"));
        assertThat(rows(source + "SELECT status,COUNT(*) FROM facts GROUP BY status")).isEmpty();
    }

    @Test void overlappingSubsetsAreNotForcedIntoExclusiveBuckets() throws Exception {
        assertThat(rows("WITH facts(status) AS (VALUES ('delivered'),('canceled'),('shipped')) "
                + "SELECT COUNT(*) FILTER(WHERE status='delivered'),COUNT(*) FILTER(WHERE status<>'canceled') FROM facts"))
                .containsExactly(List.of("1", "2"));
    }

    @Test void inputOrderControlsColumnsWithoutChangingWhichRowsAreCounted() throws Exception {
        assertThat(rows("WITH facts(status) AS (VALUES ('delivered'),('delivered'),('canceled')) "
                + "SELECT COUNT(*) FILTER(WHERE status='canceled'),COUNT(*) FILTER(WHERE status='delivered') FROM facts"))
                .containsExactly(List.of("1", "2"));
    }

    @Test void explicitGroupingKeepsOneRowPerGroupAndBothMetricsPerRow() throws Exception {
        assertThat(rows("WITH facts(state,status) AS (VALUES ('SP','delivered'),('SP','canceled'),('RJ','delivered')) "
                + "SELECT state,COUNT(*) FILTER(WHERE status='delivered'),COUNT(*) FILTER(WHERE status='canceled') "
                + "FROM facts GROUP BY state ORDER BY state"))
                .containsExactly(List.of("RJ", "1", "0"), List.of("SP", "1", "1"));
    }

    @Test void temporalComparisonAndExplicitWideVersionContainSameCountsWithDifferentShapes() throws Exception {
        String source = "WITH facts(yr) AS (VALUES (2017),(2018),(2018)) ";
        assertThat(rows(source + "SELECT yr,COUNT(*) FROM facts GROUP BY yr ORDER BY yr"))
                .containsExactly(List.of("2017", "1"), List.of("2018", "2"));
        assertThat(rows(source + "SELECT COUNT(*) FILTER(WHERE yr=2017),COUNT(*) FILTER(WHERE yr=2018) FROM facts"))
                .containsExactly(List.of("1", "2"));
    }

    static List<List<String>> rows(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery(sql)) {
                    var rows = new ArrayList<List<String>>();
                    while (result.next()) {
                        var row = new ArrayList<String>();
                        for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getString(i));
                        rows.add(row);
                    }
                    return rows;
                }
            }
        }
    }

    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
