package com.text2sql.agent.generation;

import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessContextCompressorTest {

    private static final SchemaContext.SourceProvenance TRACEABLE =
            new SchemaContext.SourceProvenance(
                    Set.of("orders"), Set.of("customers"), Set.of("orders"), Set.of(), false, true);

    @Test
    void sharesOnlyExactRepeatedOrdinaryConstraints() {
        String common = "共同过滤：只统计有效订单，并且只在用户没有明确覆盖时保留这一事实集合边界。"
                + " 这是一个足够长的业务约束，用来证明共享段只有在实际节省字符时才启用。".repeat(4);
        String input = "METRIC first  -- first metric\n"
                + "  表达式: SUM(a.value)\n"
                + "  " + common + "\n"
                + "  独有口径：first 的分母。\n\n"
                + "METRIC second  -- second metric\n"
                + "  表达式: SUM(b.value)\n"
                + "  " + common + "\n"
                + "  独有口径：second 的分母。";

        var result = new BusinessContextCompressor().compress(input, TRACEABLE);

        assertThat(result.compressed()).isTrue();
        assertThat(result.text()).contains("SHARED BUSINESS CONSTRAINTS")
                .contains(common)
                .contains("表达式: SUM(a.value)", "表达式: SUM(b.value)")
                .contains("独有口径：first 的分母。", "独有口径：second 的分母。");
        assertThat(result.sharedConstraints()).containsKey(common);
        assertThat(result.sharedConstraints().get(common))
                .containsExactly("first", "second");
        assertThat(result.text().length()).isLessThan(input.length());
    }

    @Test
    void doesNotCompressWhenProvenanceIsUnknownOrFallback() {
        String input = "METRIC first  -- first\n  共同过滤：只统计有效订单。\n\n"
                + "METRIC second  -- second\n  共同过滤：只统计有效订单。";
        var compressor = new BusinessContextCompressor();

        var unknown = compressor.compress(input, SchemaContext.SourceProvenance.unknown());
        var fallback = compressor.compress(input,
                new SchemaContext.SourceProvenance(Set.of(), Set.of(), Set.of(), Set.of(), true, true));

        assertThat(unknown.compressed()).isFalse();
        assertThat(unknown.reason()).isEqualTo("untraceable-provenance");
        assertThat(unknown.text()).isSameAs(input);
        assertThat(fallback.compressed()).isFalse();
        assertThat(fallback.reason()).isEqualTo("schema-fallback");
        assertThat(fallback.text()).isSameAs(input);
    }

    @Test
    void preservesPolicyBlocksAndSingleMetricTextByteForByte() {
        String policy = "DIMENSION_POLICY category -- products.product_category_name\n"
                + "  说明：保留 NULL。";
        var compressor = new BusinessContextCompressor();
        var policyResult = compressor.compress(policy, TRACEABLE);
        var singleResult = compressor.compress(
                "METRIC first  -- first\n  共同过滤：只统计有效订单。", TRACEABLE);

        assertThat(policyResult.compressed()).isFalse();
        assertThat(policyResult.reason()).isEqualTo("policy-block-present");
        assertThat(policyResult.text()).isSameAs(policy);
        assertThat(singleResult.compressed()).isFalse();
        assertThat(singleResult.reason()).isEqualTo("fewer-than-two-metrics");
        assertThat(singleResult.text()).isEqualTo("METRIC first  -- first\n  共同过滤：只统计有效订单。");
    }

    @Test
    void refusesShortOrStructuralDuplicatesWhenMarkerWouldAddBytes() {
        String input = "METRIC first  -- first\n  口径：A\n\nMETRIC second  -- second\n  口径：A";

        var result = new BusinessContextCompressor().compress(input, TRACEABLE);

        assertThat(result.compressed()).isFalse();
        assertThat(result.reason()).isEqualTo("marker-overhead-exceeds-savings");
        assertThat(result.text()).isEqualTo(input);
    }

    @Test
    void neverMovesContinuationLinesFromMultilineExpressions() {
        String expressionLine = "WHEN shared_condition = 1 AND this_is_a_very_long_expression_branch = true "
                + "THEN 'RETURNING'";
        String chineseElse = "  ELSE '其他'";
        String sharedNote = "共同说明：这条很长的业务说明可以安全共享，但表达式分支不能共享。".repeat(4);
        String input = "METRIC first  -- first\n"
                + "  表达式: CASE\n"
                + "  " + expressionLine + "\n"
                + chineseElse + "\n"
                + "END\n"
                + "  " + sharedNote + "\n\n"
                + "METRIC second  -- second\n"
                + "  表达式: CASE\n"
                + "  " + expressionLine + "\n"
                + chineseElse + "\n"
                + "END\n"
                + "  " + sharedNote;

        var result = new BusinessContextCompressor().compress(input, TRACEABLE);

        assertThat(result.compressed()).isTrue();
        assertThat(result.text()).contains(expressionLine + "\n", expressionLine + "\n")
                .contains(sharedNote);
        assertThat(result.sharedConstraints().keySet())
                .noneMatch(key -> key.contains("WHEN shared_condition"));
    }

    @Test
    void failsOpenWhenBalancedExpressionHasAmbiguousAsciiContinuation() {
        String ambiguous = "CONTINUATION_TOKEN_that_could_be_part_of_expression";
        String shared = "共同说明：只有明确属于业务说明的行才允许共享。".repeat(4);
        String input = "METRIC first  -- first\n"
                + "  表达式: EXPRESSION_TOKEN\n"
                + "  " + ambiguous + "\n"
                + "  " + shared + "\n\n"
                + "METRIC second  -- second\n"
                + "  表达式: EXPRESSION_TOKEN\n"
                + "  " + ambiguous + "\n"
                + "  " + shared;

        var result = new BusinessContextCompressor().compress(input, TRACEABLE);

        assertThat(result.compressed()).isFalse();
        assertThat(result.reason()).isEqualTo("no-cross-metric-duplicate");
        assertThat(result.text()).isEqualTo(input);
    }

    @Test
    void preservesTrailingLineBreakWhenCompressionOccurs() {
        String common = "共同过滤：保留完整事实集合。".repeat(8);
        String input = "METRIC first  -- first\n  表达式: SUM(a.value)\n  " + common
                + "\n\nMETRIC second  -- second\n  表达式: SUM(b.value)\n  " + common + "\n";

        var result = new BusinessContextCompressor().compress(input, TRACEABLE);

        assertThat(result.compressed()).isTrue();
        assertThat(result.text()).endsWith("\n");
    }

    @Test
    void doesNotNormalizeThreeSpaceNestedLinesIntoSharedConstraints() {
        String nested = "嵌套内容：缩进三格时作用域不明确，必须留在原指标内。".repeat(5);
        String input = "METRIC first  -- first\n"
                + "   " + nested + "\n\n"
                + "METRIC second  -- second\n"
                + "   " + nested;

        var result = new BusinessContextCompressor().compress(input, TRACEABLE);

        assertThat(result.compressed()).isFalse();
        assertThat(result.reason()).isEqualTo("no-cross-metric-duplicate");
        assertThat(result.text()).isEqualTo(input);
    }

    @Test
    void doesNotShareTabIndentedLines() {
        String nested = "\t嵌套内容：TAB 缩进作用域不明确。".repeat(5);
        String input = "METRIC first  -- first\n"
                + "  " + nested + "\n\n"
                + "METRIC second  -- second\n"
                + "  " + nested;

        var result = new BusinessContextCompressor().compress(input, TRACEABLE);

        assertThat(result.compressed()).isFalse();
        assertThat(result.reason()).isEqualTo("no-cross-metric-duplicate");
        assertThat(result.text()).isEqualTo(input);
    }

    @Test
    void keepsDuplicateOccurrencesAndMalformedHeadersFailOpen() {
        String repeated = "重复约束：这两个位置的相同文字可能属于不同作用域。".repeat(4);
        String repeatedInput = "METRIC first  -- first\n"
                + "  " + repeated + "\n"
                + "  " + repeated + "\n\n"
                + "METRIC second  -- second\n"
                + "  " + repeated;
        String malformed = "METRIC first\n  约束：A\n\nMETRIC first  -- duplicate\n  约束：A";

        var compressor = new BusinessContextCompressor();
        var repeatedResult = compressor.compress(repeatedInput, TRACEABLE);
        var malformedResult = compressor.compress(malformed, TRACEABLE);

        assertThat(repeatedResult.compressed()).isFalse();
        assertThat(repeatedResult.reason()).isEqualTo("no-cross-metric-duplicate");
        assertThat(repeatedResult.text()).isEqualTo(repeatedInput);
        assertThat(malformedResult.compressed()).isFalse();
        assertThat(malformedResult.reason()).isEqualTo("fewer-than-two-metrics");
        assertThat(malformedResult.text()).isEqualTo(malformed);
    }
}
