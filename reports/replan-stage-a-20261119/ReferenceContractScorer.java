import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.ResultNormalizer;
import java.nio.file.*;
import java.util.*;

/** External common V4 scorer. No generator, Spring context, SQL execution or model client. */
public final class ReferenceContractScorer {
    private static final ObjectMapper JSON = new ObjectMapper();
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record Policy(String mode, List<Integer> keyPositions, Integer requestedLimit, List<String> columnKinds) { }

    public static boolean equivalent(QueryResult gold, QueryResult actual, Policy policy) {
        if (gold == null || actual == null || policy == null || policy.mode() == null)
            throw new IllegalArgumentException("Missing reference, result, or explicit policy");
        if (!Set.of("UNORDERED_MULTISET_OR_SINGLE_ROW", "TOTAL_ORDER_STRICT_TOP_N", "TIME_ASC", "PARTIAL_ORDER").contains(policy.mode()))
            throw new IllegalArgumentException("Unknown score policy");
        int width = gold.columns().size();
        if (policy.columnKinds() == null || policy.columnKinds().size() != width
                || policy.columnKinds().stream().anyMatch(k -> !Set.of("TEXT","NUMERIC","TEMPORAL").contains(k)))
            throw new IllegalArgumentException("Missing/invalid frozen reference column kinds");
        if ((policy.mode().equals("TIME_ASC") || policy.mode().equals("PARTIAL_ORDER"))
                && (policy.keyPositions() == null || policy.keyPositions().isEmpty()
                || policy.keyPositions().stream().anyMatch(i -> i == null || i < 0 || i >= width)))
            throw new IllegalArgumentException("Missing/invalid registered ordering column");
        if (policy.mode().equals("TOTAL_ORDER_STRICT_TOP_N") && policy.requestedLimit() == null)
            throw new IllegalArgumentException("Missing registered TopN limit");
        if (gold.truncated() || actual.truncated() || gold.columns().size() != actual.columns().size()) return false;
        if (gold.rows().stream().anyMatch(r -> r.size() != width) || actual.rows().stream().anyMatch(r -> r.size() != width))
            return false;
        if (policy.requestedLimit() != null && (policy.requestedLimit() < 1 || gold.rows().size() > policy.requestedLimit()))
            throw new IllegalArgumentException("Reference violates registered TopN contract");
        if (policy.requestedLimit() != null && actual.rows().size() > policy.requestedLimit()) return false;
        // Full multiset equality is mandatory: an equal score never authorizes a forged identity.
        if (!normalize(gold, policy.columnKinds(), false).equals(normalize(actual, policy.columnKinds(), false))) return false;
        if (policy.mode().equals("UNORDERED_MULTISET_OR_SINGLE_ROW")) return true;
        var expected = normalize(gold, policy.columnKinds(), true);
        var observed = normalize(actual, policy.columnKinds(), true);
        if (policy.mode().equals("TOTAL_ORDER_STRICT_TOP_N")) return expected.equals(observed);
        // Full values already match. Only permutations inside equal requested key blocks are allowed.
        for (int r = 0; r < expected.size(); r++)
            for (int key : policy.keyPositions())
                if (!expected.get(r).get(key).equals(observed.get(r).get(key))) return false;
        return true;
    }

    private static List<List<String>> normalize(QueryResult result, List<String> kinds, boolean ordered) {
        var rows = new ArrayList<List<String>>();
        for(var raw:result.rows()) {
            var row=new ArrayList<String>();
            for(int i=0;i<raw.size();i++) {
                String cell=raw.get(i), kind=kinds.get(i);
                // Type tags separate SQL null from every possible non-null text value.
                row.add(cell==null ? "NULL:" : kind.equals("TEXT") ? "TEXT:"+cell
                    : kind+":"+ResultNormalizer.normalizeValue(cell));
            }
            rows.add(row);
        }
        if(!ordered) rows.sort((a,b)-> {
            for(int i=0;i<a.size();i++) { int c=a.get(i).compareTo(b.get(i)); if(c!=0)return c; }
            return 0;
        });
        return rows;
    }

    /** Exact original HTTP-probe rule: caller must also require HTTP200 and SUCCESS. Never SQL-aware ties. */
    public static boolean originalTargetEquivalent(QueryResult gold, QueryResult actual) {
        return gold!=null && actual!=null && !actual.truncated()
            && ResultNormalizer.normalize(gold,false).equals(ResultNormalizer.normalize(actual,false));
    }

    private static QueryResult result(List<List<String>> rows) {
        int width = rows.isEmpty() ? 2 : rows.getFirst().size();
        var columns = new ArrayList<String>();
        for (int i = 0; i < width; i++) columns.add("c" + i);
        return new QueryResult(columns, rows, false, 0);
    }
    private static List<String> row(String... values) { return Arrays.asList(values); }
    private static List<List<String>> rows(String... values) { return List.of(row(values)); }
    private static void check(List<Map<String,Object>> checks, String id, boolean condition) {
        checks.add(Map.of("id", id, "passed", condition));
        if (!condition) throw new IllegalStateException("Scoring counterexample failed: " + id);
    }

    private static void selfcheck(Path output) throws Exception {
        var checks = new ArrayList<Map<String,Object>>();
        var kinds=List.of("TEXT","NUMERIC");
        var unordered = new Policy("UNORDERED_MULTISET_OR_SINGLE_ROW", List.of(), null,kinds);
        var partial = new Policy("PARTIAL_ORDER", List.of(1), null,kinds);
        var time = new Policy("TIME_ASC", List.of(0), null,List.of("TEMPORAL","NUMERIC"));
        var top = new Policy("TOTAL_ORDER_STRICT_TOP_N", List.of(), 2,kinds);
        var sorted = result(List.of(row("A","10"),row("B","5")));
        var reversed = result(List.of(row("B","5"),row("A","10")));
        check(checks,"unordered_accepts_permutation",equivalent(sorted,reversed,unordered));
        check(checks,"ordered_without_limit_rejects_reverse",!equivalent(sorted,reversed,partial));
        check(checks,"ordered_without_limit_accepts_exact",equivalent(sorted,sorted,partial));
        var ties = result(List.of(row("A","10"),row("B","10"),row("C","5")));
        check(checks,"equal_key_block_permutation",equivalent(ties,result(List.of(row("B","10"),row("A","10"),row("C","5"))),partial));
        check(checks,"moving_row_across_tie_block",!equivalent(ties,result(List.of(row("B","10"),row("C","5"),row("A","10"))),partial));
        check(checks,"strict_topn_stable_id",!equivalent(sorted,reversed,top));
        check(checks,"strict_topn_forged_tail_id",!equivalent(sorted,result(List.of(row("A","10"),row("FORGED","5"))),top));
        check(checks,"strict_topn_hidden_key_uses_gold_order",!equivalent(sorted,reversed,top));
        check(checks,"no_row_dedup",!equivalent(result(List.of(row("A","1"),row("A","1"))),result(rows("A","1")),unordered));
        check(checks,"no_pivot_repair",!equivalent(sorted,result(rows("10","5")),unordered));
        check(checks,"no_extra_column",!equivalent(sorted,result(List.of(row("A","10","X"),row("B","5","Y"))),unordered));
        check(checks,"no_unit_scaling",!equivalent(result(rows("rate","3.12")),result(rows("rate","0.0312")),unordered));
        check(checks,"original_numeric_tolerance",equivalent(result(rows("amount","10.00000")),result(rows("amount","10")),unordered));
        check(checks,"year_is_not_january_date",!equivalent(result(rows("2018","1")),result(rows("2018-01-01","1")),time));
        var dates = result(List.of(row("2018-01-01","1"),row("2018-02-01","2")));
        check(checks,"chronology_rejects_reverse",!equivalent(dates,result(List.of(row("2018-02-01","2"),row("2018-01-01","1"))),time));
        check(checks,"midnight_date_equivalence",equivalent(result(rows("2018-01-01","1")),result(rows("2018-01-01 00:00:00","1")),time));
        check(checks,"null_is_not_zero",!equivalent(result(rows("A",null)),result(rows("A","0")),unordered));
        check(checks,"null_is_not_empty",!equivalent(result(rows("A",null)),result(rows("A","")),unordered));
        check(checks,"truncation_rejected",!equivalent(sorted,new QueryResult(sorted.columns(),sorted.rows(),true,0),unordered));
        var many = new ArrayList<List<String>>();
        for (int i=0;i<101;i++) many.add(row("id"+i,"1"));
        check(checks,"all_101_groups_required",!equivalent(result(many),result(many.subList(0,100)),unordered));
        check(checks,"zero_rows_still_checks_width",!equivalent(new QueryResult(List.of("A"),List.of(),false,0),new QueryResult(List.of("A","B"),List.of(),false,0),new Policy("UNORDERED_MULTISET_OR_SINGLE_ROW",List.of(),null,List.of("TEXT"))));
        boolean rejected=false;
        try { equivalent(sorted,sorted,new Policy("PARTIAL_ORDER",List.of(20),null,kinds)); } catch(IllegalArgumentException e) { rejected=true; }
        check(checks,"invalid_keys_fail_closed",rejected);
        rejected=false;
        try { equivalent(sorted,sorted,new Policy("LEGACY_GUESS_FROM_SQL",List.of(),null,kinds)); } catch(IllegalArgumentException e) { rejected=true; }
        check(checks,"unknown_policy_fail_closed",rejected);
        check(checks,"text_leading_zero_identity",!equivalent(result(rows("001","10")),result(rows("1","10")),top));
        check(checks,"literal_NULL_is_not_SQL_null",!equivalent(result(rows(null,"10")),result(rows("NULL","10")),top));
        check(checks,"text_whitespace_is_identity",!equivalent(result(rows(" x ","10")),result(rows("x","10")),unordered));
        check(checks,"date_shaped_text_remains_text",!equivalent(result(rows("2018-01-01","10")),result(rows("2018-01-01 00:00:00","10")),unordered));
        check(checks,"topn_extra_row_is_incorrect_not_exception",!equivalent(sorted,result(List.of(row("A","10"),row("B","5"),row("C","1"))),top));
        check(checks,"original_target_rejects_boundary_substitution",!originalTargetEquivalent(sorted,result(List.of(row("A","10"),row("C","5")))));
        check(checks,"original_target_ignores_row_permutation",originalTargetEquivalent(sorted,reversed));
        rejected=false;
        try { equivalent(sorted,sorted,new Policy("PARTIAL_ORDER",List.of(1),null,null)); } catch(IllegalArgumentException e) { rejected=true; }
        check(checks,"missing_types_fail_closed",rejected);
        try (var out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
            JSON.writerWithDefaultPrettyPrinter().writeValue(out,Map.of("format","public-reference-scorer-counterexamples-v1",
                "checks",checks,"passed",checks.size(),"providerCalls",0,"providerCostYuan",0,
                "runtimeIntegration","NOT_ADMITTED_UNTIL_D_COMMON_CONTROLLER"));
        }
        System.out.println("SCORER_BOUNDARIES passed="+checks.size()+" providerCalls=0");
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("selfcheck output.json OR score fixture.json");
        if(args[0].equals("selfcheck")) selfcheck(Path.of(args[1]));
        else if(args[0].equals("score")) {
            JsonNode input=JSON.readTree(Path.of(args[1]).toFile());
            var gold=JSON.treeToValue(input.get("gold"),QueryResult.class);
            var actual=JSON.treeToValue(input.get("actual"),QueryResult.class);
            var policy=JSON.treeToValue(input.get("policy"),Policy.class);
            System.out.println(JSON.writeValueAsString(Map.of("correct",equivalent(gold,actual,policy))));
        } else throw new IllegalArgumentException("Unknown operation");
    }
}
