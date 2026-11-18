package com.text2sql.agent.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.DriverManager;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Database operator semantics only; an LLM still needs to obey the requested output contract. */
@EnabledIf("databaseAvailable")
class TemporalDimensionDatabaseTest {
    @ParameterizedTest
    @CsvSource({"2023-12-31T23:59:59.999999,2023-10-01T00:00,0,7",
            "2024-01-01T00:00,2024-01-01T00:00,1,1",
            "2024-03-31T23:59:59.999999,2024-01-01T00:00,0,7",
            "2024-04-01T00:00,2024-04-01T00:00,1,1"})
    void quarterAndWeekdayDefaultsKeepYearAndBoundaryMeaning(String input, String quarter, int dow, int iso) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement("""
                WITH input(ts) AS (VALUES (CAST(? AS timestamp)))
                SELECT DATE_TRUNC('quarter',ts),EXTRACT(DOW FROM ts)::int,EXTRACT(ISODOW FROM ts)::int
                FROM input
                """)) {
            statement.setQueryTimeout(5);
            statement.setObject(1, LocalDateTime.parse(input));
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, LocalDateTime.class)).isEqualTo(LocalDateTime.parse(quarter));
                assertThat(rows.getInt(2)).isEqualTo(dow);
                assertThat(rows.getInt(3)).isEqualTo(iso);
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void quarterTimestampDoesNotMergeTheSameQuarterNumberAcrossYears() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery("""
                    WITH input(ts) AS (VALUES (TIMESTAMP '2023-02-01'),(TIMESTAMP '2024-02-01'))
                    SELECT DATE_TRUNC('quarter',ts),COUNT(*) FROM input GROUP BY 1 ORDER BY 1
                    """)) {
                for (int year : new int[]{2023, 2024}) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1, LocalDateTime.class)).isEqualTo(LocalDateTime.of(year, 1, 1, 0, 0));
                    assertThat(rows.getLong(2)).isEqualTo(1);
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private static java.sql.Connection connection() throws Exception {
        var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password());
        connection.setReadOnly(true);
        return connection;
    }

    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
