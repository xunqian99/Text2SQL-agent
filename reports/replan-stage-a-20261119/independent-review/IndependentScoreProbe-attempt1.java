import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.execution.QueryResult;
import com.text2sql.agent.execution.ResultNormalizer;
import java.nio.file.*;
import java.util.*;

/** Independent A-review probes. No SQL, Spring context, model client, or network. */
public final class IndependentScoreProbe {
    private static final List<Map<String,Object>> CHECKS = new ArrayList<>();
    private static QueryResult result(List<List<String>> rows) {
        var columns = new ArrayList<String>();
        for(int i=0;i<rows.getFirst().size();i++) columns.add("c"+i);
        return new QueryResult(columns, rows, false, 0);
    }
    private static List<String> row(String... cells) { return Arrays.asList(cells); }
    private static void check(String name, QueryResult gold, QueryResult actual,
            ReferenceContractScorer.Policy policy, boolean expected) {
        Boolean actualResult=null; String error=null;
        try { actualResult=ReferenceContractScorer.equivalent(gold, actual, policy); }
        catch(Exception e) { error=e.getClass().getSimpleName()+": "+e.getMessage(); }
        var r=new LinkedHashMap<String,Object>();
        r.put("id",name);r.put("expectedCorrect",expected);r.put("observedCorrect",actualResult);
        r.put("exception",error);r.put("passed",Objects.equals(actualResult,expected));
        r.put("goldRows",gold.rows());r.put("actualRows",actual.rows());CHECKS.add(r);
    }
    public static void main(String[] args) throws Exception {
        var un=new ReferenceContractScorer.Policy("UNORDERED_MULTISET_OR_SINGLE_ROW",List.of(),null);
        var top=new ReferenceContractScorer.Policy("TOTAL_ORDER_STRICT_TOP_N",List.of(),2);
        var partial=new ReferenceContractScorer.Policy("PARTIAL_ORDER",List.of(1),null);
        var partial2=new ReferenceContractScorer.Policy("PARTIAL_ORDER",List.of(2),null);
        var time=new ReferenceContractScorer.Policy("TIME_ASC",List.of(0),null);
        check("text_identity_leading_zero",result(List.of(row("001","10"))),result(List.of(row("1","10"))),top,false);
        check("sql_null_vs_literal_NULL",result(List.of(row(null,"10"))),result(List.of(row("NULL","10"))),top,false);
        check("text_group_key_whitespace",result(List.of(row(" x ","10"))),result(List.of(row("x","10"))),un,false);
        check("text_group_key_date_string",result(List.of(row("2018-01-01","10"))),result(List.of(row("2018-01-01 00:00:00","10"))),un,false);
        var gold=result(List.of(row("A","10"),row("B","10"),row("C","5")));
        check("partial_allows_same_key_block",gold,result(List.of(row("B","10"),row("A","10"),row("C","5"))),partial,true);
        check("partial_rejects_wrong_key_block",gold,result(List.of(row("B","10"),row("C","5"),row("A","10"))),partial,false);
        check("partial_rejects_identity_mutation",gold,result(List.of(row("X","10"),row("B","10"),row("C","5"))),partial,false);
        check("formal_T5_001_column2_tie",result(List.of(row("R1","L1","10"),row("R2","L2","10"),row("R3","L3","5"))),result(List.of(row("R2","L2","10"),row("R1","L1","10"),row("R3","L3","5"))),partial2,true);
        check("formal_T5_001_column2_reverse",result(List.of(row("R1","L1","10"),row("R2","L2","5"))),result(List.of(row("R2","L2","5"),row("R1","L1","10"))),partial2,false);
        check("time_rejects_reverse",result(List.of(row("2018-01-01","1"),row("2018-02-01","2"))),result(List.of(row("2018-02-01","2"),row("2018-01-01","1"))),time,false);
        check("topn_hidden_metric_still_strict",result(List.of(row("A","3.5"),row("B","4.5"))),result(List.of(row("B","4.5"),row("A","3.5"))),top,false);
        check("topn_boundary_identity_substitution",result(List.of(row("A","10"),row("B","5"))),result(List.of(row("A","10"),row("C","5"))),top,false);
        check("unordered_keeps_duplicate_multiplicity",result(List.of(row("A","1"),row("A","1"))),result(List.of(row("A","1"))),un,false);
        check("numeric_tolerance_preserved",result(List.of(row("A","10.00001"))),result(List.of(row("A","10"))),un,true);
        check("date_midnight_compatibility_preserved",result(List.of(row("2018-01-01","1"))),result(List.of(row("2018-01-01 00:00:00","1"))),time,true);
        check("topn_overlong_actual_is_wrong_answer",result(List.of(row("A","10"),row("B","5"))),result(List.of(row("A","10"),row("B","5"),row("C","1"))),top,false);
        var out=new LinkedHashMap<String,Object>();out.put("format","independent-a-attempt1-scorer-probes-v1");
        out.put("checks",CHECKS);out.put("pass",CHECKS.stream().filter(x->Boolean.TRUE.equals(x.get("passed"))).count());
        out.put("total",CHECKS.size());out.put("providerCalls",0);out.put("databaseCalls",0);
        var legacyGold=new QueryResult(List.of("customer_unique_id","cnt"),List.of(row("A","10"),row("B","5")),false,0);
        var legacyActual=new QueryResult(List.of("customer_unique_id","cnt"),List.of(row("A","10"),row("C","5")),false,0);
        String legacySql="SELECT customer_unique_id,COUNT(*) AS cnt FROM t GROUP BY 1 ORDER BY cnt DESC LIMIT 2";
        out.put("legacyOriginalComparatorDiagnostic",Map.of(
            "strictNormalizedListEquality",ResultNormalizer.equivalent(ResultNormalizer.normalize(legacyGold,true),ResultNormalizer.normalize(legacyActual,true)),
            "sqlAwareP21Comparator",ResultNormalizer.equivalent(legacyGold,legacySql,legacyActual,legacySql),
            "meaning","The p21 class name alone does not pin the original strict algorithm; its overloads disagree about tied boundary membership."));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),out);
        System.out.println("INDEPENDENT_SCORER_PROBES "+out.get("pass")+"/"+out.get("total"));
    }
}
