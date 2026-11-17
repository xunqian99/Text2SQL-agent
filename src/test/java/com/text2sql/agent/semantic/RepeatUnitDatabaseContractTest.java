package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.DriverManager;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Numeric contract, not a claim that prompts force model compliance. All SQL uses read-only VALUES. */
@EnabledIf("databaseAvailable")
class RepeatUnitDatabaseContractTest {
    record Sample(String name, List<Integer> ordersPerPerson) {
        @Override public String toString() { return name; }
    }
    static Stream<Sample> samples() {
        return Stream.of(new Sample("empty-denominator-undefined", List.of()), new Sample("no-repeat", List.of(1, 1)),
                new Sample("one-third-rounding", List.of(1, 1, 2)), new Sample("all-repeat", List.of(2, 3)));
    }
    @ParameterizedTest @MethodSource("samples")
    void registeredPercentAndExplicitDecimalHaveDifferentScaleAndSameUnderlyingFraction(Sample sample) throws Exception {
        String cte = sample.ordersPerPerson().isEmpty() ? "WITH per_user(order_cnt) AS (SELECT NULL::int WHERE FALSE) "
                : "WITH per_user(order_cnt) AS (VALUES " + String.join(",", sample.ordersPerPerson().stream().map(v -> "(" + v + ")").toList()) + ") ";
        long repeated = sample.ordersPerPerson().stream().filter(v -> v >= 2).count();
        int people = sample.ordersPerPerson().size();
        BigDecimal percent = people == 0 ? null : BigDecimal.valueOf(repeated).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(people), 2, RoundingMode.HALF_UP);
        BigDecimal ratio = people == 0 ? null : BigDecimal.valueOf(repeated).divide(BigDecimal.valueOf(people), 4, RoundingMode.HALF_UP);
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(), MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                String sql = cte + "SELECT " + MetricUnitContractTest.metric("repeat_rate").expression()
                        + ", ROUND(COUNT(*) FILTER (WHERE order_cnt>=2)::numeric / NULLIF(COUNT(*),0),4) FROM per_user";
                try (var rows = statement.executeQuery(sql)) {
                    assertThat(rows.next()).isTrue();
                    if (people == 0) { assertThat(rows.getBigDecimal(1)).isNull(); assertThat(rows.getBigDecimal(2)).isNull(); }
                    else { assertThat(rows.getBigDecimal(1)).isEqualByComparingTo(percent); assertThat(rows.getBigDecimal(2)).isEqualByComparingTo(ratio); }
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
