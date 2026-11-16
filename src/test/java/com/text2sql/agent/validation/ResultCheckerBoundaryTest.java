package com.text2sql.agent.validation;

import com.text2sql.agent.execution.QueryResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ResultCheckerBoundaryTest {
    @TestFactory
    Stream<DynamicTest> columnBoundaryContracts() {
        return ResultCheckerAcceptanceProbe.cases().stream().map(sample -> DynamicTest.dynamicTest(sample.id(), () -> {
            var check = new ResultChecker().check(sample.question(), sample.sql(), sample.result());
            assertThat(check.rule()).isEqualTo(sample.expectedRule());
            assertThat(check.suspicious()).isEqualTo(sample.expectedRule() != null);
            if (check.suspicious()) assertThat(check.reason()).contains("输出列", "OUTPUT_LABEL/", "行");
        }));
    }

    @Test
    void recordsColumnIdentityAndEvidenceWithoutFlatteningSignedAndUnknownColumns() {
        var policies = ResultColumnPolicy.classify(List.of("gmv2018", "yoy_pct", "mystery", "net_amount", "refundRate"));
        assertThat(policies).extracting(ResultColumnPolicy::kind).containsExactly(
                ResultColumnPolicy.Kind.NON_NEGATIVE, ResultColumnPolicy.Kind.GROWTH,
                ResultColumnPolicy.Kind.UNKNOWN, ResultColumnPolicy.Kind.SIGNED, ResultColumnPolicy.Kind.BOUNDED_RATIO);
        assertThat(policies.get(2).source()).isEqualTo(ResultColumnPolicy.Source.NONE);
        assertThat(policies.get(0).source()).isEqualTo(ResultColumnPolicy.Source.OUTPUT_LABEL);
        assertThat(policies).extracting(ResultColumnPolicy::index).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void duplicateLabelsRemainSeparatePositionsAndNetHasPriorityOverAmount() {
        var policies = ResultColumnPolicy.classify(List.of("amount", "net_amount", "amount"));
        assertThat(policies).extracting(ResultColumnPolicy::kind).containsExactly(
                ResultColumnPolicy.Kind.NON_NEGATIVE, ResultColumnPolicy.Kind.SIGNED, ResultColumnPolicy.Kind.NON_NEGATIVE);
        var result = new QueryResult(List.of("amount", "net_amount", "amount"),
                List.of(List.of("100", "-10", "-5")), false, 0);
        assertThat(new ResultChecker().check("金额与净额", "SELECT 1", result).reason())
                .contains("第 3 列", "NON_NEGATIVE_LABEL");
    }

    @Test
    void nullLabelsAndNumericNonFiniteValuesDoNotCreateInventedMetricEvidence() {
        assertThat(ResultColumnPolicy.classify(Arrays.asList(null, "sum", "v", "separate_value")))
                .allMatch(p -> p.kind() == ResultColumnPolicy.Kind.UNKNOWN && p.source() == ResultColumnPolicy.Source.NONE);
        var result = new QueryResult(List.of("amount", "pct"), List.of(List.of("NaN", "Infinity")), false, 0);
        assertThat(new ResultChecker().check("金额占比", "SELECT 1", result).suspicious()).isFalse();
    }

    @Test
    void decimalBoundsRemainExactAndEvidencePointsAtTheViolatingRow() {
        var result = new QueryResult(List.of("pct"), List.of(List.of("-0.0"), List.of("100.000000000000000001")), false, 0);
        var check = new ResultChecker().check("退款率", "SELECT 1", result);
        assertThat(check.rule()).isEqualTo("RATIO_OUT_OF_BOUNDS");
        assertThat(check.reason()).contains("第 2 行", "100.000000000000000001");
    }
}
