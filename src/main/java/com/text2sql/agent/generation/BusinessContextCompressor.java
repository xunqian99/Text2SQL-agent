package com.text2sql.agent.generation;

import com.text2sql.agent.retrieval.SchemaContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compresses only exactly repeated natural-language constraints in metric blocks.
 *
 * <p>The candidate is deliberately conservative. Expressions, filters, units,
 * query patterns, dimension/entity policies and any line whose origin cannot be
 * audited stay byte-for-byte unchanged. A malformed or untraceable input returns
 * the original text (fail-open) so this optimizer cannot silently change metric
 * semantics.
 */
public final class BusinessContextCompressor {

    public record Result(String text, boolean compressed, String reason,
                         Map<String, List<String>> sharedConstraints) {
        public Result {
            sharedConstraints = copy(sharedConstraints);
        }

        private static Map<String, List<String>> copy(Map<String, List<String>> values) {
            if (values == null || values.isEmpty()) return Map.of();
            var result = new LinkedHashMap<String, List<String>>();
            values.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return java.util.Collections.unmodifiableMap(result);
        }
    }

    private static final Set<String> STRUCTURAL_PREFIXES = Set.of(
            "表达式:", "过滤条件", "默认输出单位:", "百分数按", "小数比率按",
            "此单位仅约束", "输出约束", "推荐查询结构", "表:");
    private static final Set<String> SQL_PREFIXES = Set.of(
            "select ", "from ", "join ", "left join ", "right join ", "inner join ",
            "where ", "group by ", "order by ", "having ", "with ", "case ",
            "when ", "then ", "else ", "end", "union ", "intersect ", "except ");

    /**
     * Compress a metric section while retaining every metric block.
     *
     * @param metricsText rendered metric/policy text
     * @param provenance provenance of the schema that made the metric text applicable
     */
    public Result compress(String metricsText, SchemaContext.SourceProvenance provenance) {
        if (metricsText == null || metricsText.isEmpty()) {
            return unchanged(metricsText, "empty");
        }
        if (provenance == null || !provenance.traceable()) {
            return unchanged(metricsText, "untraceable-provenance");
        }
        if (provenance.fallback()) {
            return unchanged(metricsText, "schema-fallback");
        }
        // Dimension/entity policy prose has a separate applicability model. Keeping the
        // entire text avoids accidentally treating a policy line as a metric-global rule.
        if (containsPolicyBlock(metricsText)) {
            return unchanged(metricsText, "policy-block-present");
        }

        List<String> lines = splitPreservingLines(metricsText);
        List<Block> blocks = parseBlocks(lines);
        if (blocks.size() < 2) {
            return unchanged(metricsText, "fewer-than-two-metrics");
        }

        Map<String, Candidate> candidates = collectCandidates(blocks);
        Map<String, List<String>> shared = new LinkedHashMap<>();
        for (Candidate candidate : candidates.values()) {
            if (candidate.blocks().size() >= 2) {
                shared.put(candidate.key(), candidate.blocks().stream().map(Block::name).toList());
            }
        }
        if (shared.isEmpty()) {
            return unchanged(metricsText, "no-cross-metric-duplicate");
        }

        String candidateText = render(blocks, shared.keySet(), shared);
        // Compression must earn its own marker and applicability names. Otherwise the
        // candidate could add bytes while claiming a gain.
        if (candidateText.length() >= metricsText.length()) {
            return unchanged(metricsText, "marker-overhead-exceeds-savings");
        }
        return new Result(candidateText, true, "shared-cross-metric-constraints", shared);
    }

    private static Result unchanged(String text, String reason) {
        return new Result(text, false, reason, Map.of());
    }

    private static boolean containsPolicyBlock(String text) {
        return Arrays.stream(text.split("\\R", -1))
                .map(String::stripLeading)
                .anyMatch(line -> line.startsWith("DIMENSION_POLICY ") || line.startsWith("ENTITY_POLICY "));
    }

    private static List<String> splitPreservingLines(String text) {
        // split with -1 preserves a trailing empty line; render() itself has no trailing
        // newline, but preserving it here makes the fail-open path byte stable.
        return new ArrayList<>(Arrays.asList(text.split("\\R", -1)));
    }

    private static List<Block> parseBlocks(List<String> lines) {
        var blocks = new ArrayList<Block>();
        var names = new LinkedHashSet<String>();
        Block current = null;
        boolean expressionContinuation = false;
        boolean expressionPendingBoundary = false;
        boolean expressionUncertain = false;
        int expressionStart = -1;
        SqlBalance expressionBalance = SqlBalance.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("METRIC ")) {
                if (current != null) blocks.add(current);
                String body = line.substring("METRIC ".length());
                int marker = body.indexOf("  -- ");
                if (marker < 0) return List.of();
                String name = body.substring(0, marker).strip();
                if (name.isEmpty() || !names.add(name)) return List.of();
                current = new Block(name, line, new ArrayList<>());
                expressionContinuation = false;
                expressionUncertain = false;
                expressionStart = -1;
                expressionBalance = SqlBalance.ZERO;
                expressionPendingBoundary = false;
            } else if (current != null) {
                int index = current.lines().size();
                current.lines().add(line);
                if (line.startsWith("  表达式:")) {
                    // The expression header is structural and must never be
                    // considered a duplicate constraint.
                    current.protectedLineIndexes().add(index);
                    expressionStart = index;
                    String expression = line.substring(line.indexOf(':') + 1).strip();
                    expressionBalance = balance(expression);
                    expressionUncertain = !expressionBalance.valid()
                            || expressionBalance.parentheses() < 0 || expressionBalance.cases() < 0;
                    expressionContinuation = expressionUncertain || !expressionBalance.complete(expression);
                    // A syntactically balanced first line does not prove that the
                    // expression is physically single-line.  SQL fragments such as
                    // `SELECT x` may legally continue with `FROM ...`, and malformed
                    // fixture text can also put an arbitrary continuation after a
                    // balanced token.  Keep one boundary decision pending so an
                    // unknown ASCII continuation cannot be mistaken for a shareable
                    // business note.  Natural-language metric notes and explicit
                    // structural headers close the expression safely.
                    expressionPendingBoundary = !expressionUncertain && !expressionContinuation;
                } else if (expressionContinuation) {
                    // Metric.render() preserves newlines inside a SQL
                    // expression. Parse only enough SQL structure to know
                    // where that expression ends; if the boundary is
                    // uncertain, protect the remainder of the block instead
                    // of risking a line being moved into the shared section.
                    current.protectedLineIndexes().add(index);
                    if (expressionUncertain) {
                        continue;
                    }
                    String value = line.strip();
                    if (value.isEmpty() || STRUCTURAL_PREFIXES.stream().anyMatch(value::startsWith)) {
                        expressionUncertain = true;
                        protectFrom(current, expressionStart);
                        continue;
                    }
                    SqlBalance next = expressionBalance.add(balance(value));
                    if (!next.valid()) {
                        expressionUncertain = true;
                        protectFrom(current, expressionStart);
                        continue;
                    }
                    expressionBalance = next;
                    expressionContinuation = !expressionBalance.complete(value);
                    expressionPendingBoundary = !expressionUncertain
                            && !expressionContinuation && looksLikeSqlContinuation(value);
                } else if (expressionPendingBoundary) {
                    String value = line.strip();
                    if (isExpressionBoundary(line, value)) {
                        expressionPendingBoundary = false;
                    } else if (looksLikeSqlContinuation(value)) {
                        current.protectedLineIndexes().add(index);
                        SqlBalance next = expressionBalance.add(balance(value));
                        if (!next.valid()) {
                            expressionUncertain = true;
                            protectFrom(current, expressionStart);
                            expressionPendingBoundary = false;
                            expressionContinuation = true;
                        } else {
                            expressionBalance = next;
                            expressionContinuation = !expressionBalance.complete(value);
                            expressionPendingBoundary = !expressionContinuation;
                                    // Keep watching clause-like lines after a
                                    // balanced SQL continuation (e.g. SELECT/FROM/WHERE).
                        }
                    } else {
                        // Ambiguous non-natural-language continuation: fail open for
                        // this metric block instead of moving a possible expression
                        // fragment into the shared section.
                        expressionUncertain = true;
                        protectFrom(current, expressionStart);
                        expressionPendingBoundary = false;
                        expressionContinuation = true;
                    }
                }
            } else if (!line.isBlank()) {
                // Any preamble makes block ownership ambiguous; fail-open.
                return List.of();
            }
        }
        if (current != null) blocks.add(current);
        return blocks;
    }

    private static Map<String, Candidate> collectCandidates(List<Block> blocks) {
        var candidates = new LinkedHashMap<String, Candidate>();
        for (Block block : blocks) {
            var counts = new LinkedHashMap<String, Integer>();
            for (int index = 0; index < block.lines().size(); index++) {
                String line = block.lines().get(index);
                if (block.protectedLineIndexes().contains(index) || !isShareable(line)) continue;
                String key = line.strip();
                counts.merge(key, 1, Integer::sum);
            }
            for (var entry : counts.entrySet()) {
                // A repeated line within one metric may have two different
                // scopes. Keep both occurrences unless the block proves the
                // line is unique; moving one shared copy would lose
                // multiplicity and scope.
                if (entry.getValue() != 1) continue;
                String key = entry.getKey();
                block.candidateKeys().add(key);
                candidates.computeIfAbsent(key, ignored -> new Candidate(key, new ArrayList<>()))
                        .blocks().add(block);
            }
        }
        return candidates;
    }

    private static void protectFrom(Block block, int start) {
        for (int index = Math.max(0, start); index < block.lines().size(); index++) {
            block.protectedLineIndexes().add(index);
        }
    }

    private static boolean isShareable(String line) {
        // Metric.render() uses exactly two spaces for notes. Four-space lines are
        // nested unit/exception/query-pattern text and remain in their block.
        // Metric.render() reserves exactly two leading spaces for ordinary
        // notes.  Three or more spaces are nested/ambiguous content and must
        // never be normalized into the shared section.
        if (line == null || line.length() < 3 || !line.startsWith("  ")
                || Character.isWhitespace(line.charAt(2))) {
            return false;
        }
        String value = line.strip();
        if (value.isEmpty() || value.startsWith("METRIC ")) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        if (STRUCTURAL_PREFIXES.stream().anyMatch(value::startsWith)) return false;
        if (SQL_PREFIXES.stream().anyMatch(lower::startsWith)) return false;
        return !value.contains("SELECT ") && !value.contains(" FROM ") && !value.contains(" JOIN ");
    }

    private static boolean isExpressionBoundary(String rawLine, String value) {
        if (rawLine == null || rawLine.isBlank() || value.isEmpty()) return true;
        if (rawLine.startsWith("    ")) return true;
        if (STRUCTURAL_PREFIXES.stream().anyMatch(value::startsWith)) return true;
        // The registry's prose notes are Chinese.  Treating a CJK line as a
        // natural-language boundary keeps ordinary notes compressible while an
        // ASCII/SQL-looking continuation remains protected or fail-open.
        int first = value.codePointAt(0);
        return Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN
                || value.startsWith("【") || value.startsWith("——");
    }

    private static boolean looksLikeSqlContinuation(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("when ") || lower.startsWith("then ") || lower.startsWith("else ")
                || lower.equals("end") || lower.startsWith("end ")
                || lower.startsWith("from ") || lower.startsWith("join ")
                || lower.startsWith("left join ") || lower.startsWith("right join ")
                || lower.startsWith("inner join ") || lower.startsWith("where ")
                || lower.startsWith("group by ") || lower.startsWith("order by ")
                || lower.startsWith("having ") || lower.startsWith("union ")
                || lower.startsWith("intersect ") || lower.startsWith("except ")
                || lower.startsWith("and ") || lower.startsWith("or ")) {
            return true;
        }
        char first = value.charAt(0);
        return first == '(' || first == ')' || first == ',' || first == '+' || first == '-'
                || first == '*' || first == '/' || first == '=' || first == '\'' || first == '"';
    }

    private static SqlBalance balance(String text) {
        int parentheses = 0;
        int cases = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (singleQuoted) {
                if (ch == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else if (ch == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++;
                } else if (ch == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (ch == '\'') {
                singleQuoted = true;
            } else if (ch == '"') {
                doubleQuoted = true;
            } else if (ch == '(') {
                parentheses++;
            } else if (ch == ')') {
                parentheses--;
            } else if (Character.isLetter(ch) || ch == '_') {
                int end = i + 1;
                while (end < text.length()
                        && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                    end++;
                }
                String word = text.substring(i, end).toLowerCase(Locale.ROOT);
                if (word.equals("case")) cases++;
                else if (word.equals("end")) cases--;
                i = end - 1;
            }
        }
        if (singleQuoted || doubleQuoted) return SqlBalance.INVALID;
        return new SqlBalance(parentheses, cases, true);
    }

    private record SqlBalance(int parentheses, int cases, boolean valid) {
        private static final SqlBalance ZERO = new SqlBalance(0, 0, true);
        private static final SqlBalance INVALID = new SqlBalance(0, 0, false);

        SqlBalance add(SqlBalance other) {
            if (!valid || !other.valid) return INVALID;
            int nextParentheses = parentheses + other.parentheses;
            int nextCases = cases + other.cases;
            return nextParentheses < 0 || nextCases < 0
                    ? INVALID : new SqlBalance(nextParentheses, nextCases, true);
        }

        boolean complete(String text) {
            if (!valid || parentheses != 0 || cases != 0) return false;
            String value = text.strip().toLowerCase(Locale.ROOT);
            return !(value.endsWith(",") || value.endsWith("+") || value.endsWith("-")
                    || value.endsWith("*") || value.endsWith("/") || value.endsWith("=")
                    || value.endsWith("and") || value.endsWith("or"));
        }
    }

    private static String render(List<Block> blocks, Set<String> sharedKeys,
                                 Map<String, List<String>> shared) {
        var output = new StringBuilder();
        output.append("SHARED BUSINESS CONSTRAINTS (exact repeated rules only):\n");
        shared.forEach((constraint, metrics) -> output.append("  ")
                .append(constraint)
                .append(" [applies to: ")
                .append(String.join(", ", metrics))
                .append("]\n"));
        output.append('\n');
        for (Block block : blocks) {
            output.append(block.headerLine()).append('\n');
            for (int index = 0; index < block.originalLines().size(); index++) {
                String line = block.originalLines().get(index);
                if (block.protectedLineIndexes().contains(index)
                        || !block.candidateKeys().contains(line.strip())
                        || !sharedKeys.contains(line.strip()) || !isShareable(line)) {
                    output.append(line).append('\n');
                }
            }
        }
        // Remove only the newline added after the final logical line.  Using
        // stripTrailing() would silently discard an input's trailing line break
        // or spaces whenever compression is enabled, making an unrelated byte
        // change look like an optimization.
        if (output.length() > 0 && output.charAt(output.length() - 1) == '\n') {
            output.setLength(output.length() - 1);
        }
        return output.toString();
    }

    private record Candidate(String key, List<Block> blocks) { }

    private static final class Block {
        private final String name;
        private final String headerLine;
        private final List<String> lines;
        private final Set<Integer> protectedLineIndexes = new LinkedHashSet<>();
        private final Set<String> candidateKeys = new LinkedHashSet<>();

        private Block(String name, String headerLine, List<String> lines) {
            this.name = name;
            this.headerLine = headerLine;
            this.lines = lines;
        }

        String name() { return name; }
        String headerLine() { return headerLine; }
        List<String> lines() { return lines; }
        List<String> originalLines() { return lines; }
        Set<Integer> protectedLineIndexes() { return protectedLineIndexes; }
        Set<String> candidateKeys() { return candidateKeys; }
    }
}
