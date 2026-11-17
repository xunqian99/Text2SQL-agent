package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DriverManager;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent record/identity-set oracle; source review identity is the full (review_id,order_id). */
@EnabledIf("databaseAvailable")
class ReviewCountGrainDatabaseTest {
    record Order(String id, String state) { }
    record Review(String id, String order, Integer score) { }
    record Scenario(String name, List<Order> orders, List<Review> reviews) {
        @Override public String toString() { return name; }
    }
    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("two-low-reviews-one-order", List.of(o("o1","SP")), List.of(r("r1","o1",1),r("r2","o1",2))),
                new Scenario("mixed-score-order-still-low-once", List.of(o("o1","SP"),o("o2","SP")),
                        List.of(r("r1","o1",1),r("r2","o1",5),r("r3","o2",3))),
                new Scenario("review-id-alone-is-not-unique", List.of(o("o1","SP"),o("o2","SP")),
                        List.of(r("same","o1",1),r("same","o2",2))),
                new Scenario("composite-keys-cannot-be-concatenated", List.of(o("c","SP"),o("bc","SP")),
                        List.of(r("ab","c",1),r("a","bc",2))),
                new Scenario("null-score-and-null-geography", List.of(o("o1",null),o("o2","RJ")),
                        List.of(r("r1","o1",1),r("r2","o2",null))),
                new Scenario("no-low-reviews", List.of(o("o1","SP")),List.of(r("r1","o1",5))),
                new Scenario("no-reviews", List.of(o("o1","SP")),List.of()),
                new Scenario("orphan-review-not-in-joined-order-coverage", List.of(),List.of(r("r1","missing",1))));
    }

    @ParameterizedTest @MethodSource("scenarios")
    void filteredReviewRecordsOrderSetsAndJoinedCompositeKeysStaySeparate(Scenario scenario) throws Exception {
        var states=new HashMap<String,String>();
        scenario.orders().forEach(o->states.put(o.id(),o.state()));
        var recordCounts=new TreeMap<String,Long>();
        var orderSets=new TreeMap<String,Set<String>>();
        long sourceLow=0;
        for(var review:scenario.reviews()) {
            if(review.score()==null || review.score()>2)continue;
            sourceLow++;
            if(!states.containsKey(review.order()))continue;
            String state=state(states.get(review.order()));
            recordCounts.merge(state,1L,Long::sum);
            orderSets.computeIfAbsent(state,k->new HashSet<>()).add(review.order());
        }
        var orderCounts=new TreeMap<String,Long>();
        orderSets.forEach((state,orders)->orderCounts.put(state,(long)orders.size()));
        String prefix=cte(scenario);
        String join=" FROM reviews r JOIN orders o ON o.order_id=r.order_id";
        try(var connection=DriverManager.getConnection(MemberPenetrationContractTest.url(),
                MemberPenetrationContractTest.user(),MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            assertThat(groups(connection,prefix+"SELECT o.state,COUNT(*)"+join+" WHERE r.score<=2 GROUP BY 1")).isEqualTo(recordCounts);
            assertThat(groups(connection,prefix+"SELECT o.state,COUNT(DISTINCT o.order_id)"+join+" WHERE r.score<=2 GROUP BY 1")).isEqualTo(orderCounts);
            assertThat(groups(connection,prefix+"SELECT o.state,COUNT(*) FROM orders o WHERE EXISTS "
                    +"(SELECT 1 FROM reviews r WHERE r.order_id=o.order_id AND r.score<=2) GROUP BY 1")).isEqualTo(orderCounts);
            assertThat(groups(connection,prefix+"SELECT o.state,COUNT(DISTINCT (r.review_id,r.order_id))"+join
                    +" CROSS JOIN (VALUES (1),(2)) detail(n) WHERE r.score<=2 GROUP BY 1")).isEqualTo(recordCounts);
            assertThat(scalar(connection,prefix+"SELECT COUNT(*) FROM reviews WHERE score<=2")).isEqualTo(sourceLow);
            assertThat(scalar(connection,prefix+"SELECT COUNT(DISTINCT o.order_id)"+join+" WHERE r.score<=2"))
                    .isEqualTo(orderCounts.values().stream().mapToLong(Long::longValue).sum());
            if(scenario.name().equals("two-low-reviews-one-order")) {
                assertThat(recordCounts.get("SP")).isEqualTo(2L);assertThat(orderCounts.get("SP")).isEqualTo(1L);
            }
            if(scenario.name().equals("review-id-alone-is-not-unique")) {
                assertThat(scalar(connection,prefix+"SELECT COUNT(DISTINCT review_id) FROM reviews")).isEqualTo(1L);
                assertThat(sourceLow).isEqualTo(2L);
            }
        }
    }
    private static Map<String,Long> groups(java.sql.Connection connection,String sql)throws Exception {
        var result=new TreeMap<String,Long>();
        try(var statement=connection.createStatement()) {statement.setQueryTimeout(5);
            try(var rows=statement.executeQuery(sql)){while(rows.next())result.put(state(rows.getString(1)),rows.getLong(2));}}
        return result;
    }
    private static long scalar(java.sql.Connection connection,String sql)throws Exception {
        try(var statement=connection.createStatement()) {statement.setQueryTimeout(5);
            try(var rows=statement.executeQuery(sql)){rows.next();return rows.getLong(1);}}
    }
    private static String cte(Scenario s) {
        var orders=s.orders().stream().map(o->"("+lit(o.id())+","+lit(o.state())+")").toList();
        var reviews=s.reviews().stream().map(r->"("+lit(r.id())+","+lit(r.order())+","+(r.score()==null?"NULL::int":r.score())+")").toList();
        return "WITH orders(order_id,state) AS ("+(orders.isEmpty()?"SELECT NULL::text,NULL::text WHERE FALSE":"VALUES "+String.join(",",orders))
                +"), reviews(review_id,order_id,score) AS ("+(reviews.isEmpty()?"SELECT NULL::text,NULL::text,NULL::int WHERE FALSE":"VALUES "+String.join(",",reviews))+") ";
    }
    private static String state(String value){return value==null?"<SQL_NULL>":value;}
    private static String lit(String value){return value==null?"NULL::text":"'"+value.replace("'","''")+"'::text";}
    private static Order o(String id,String state){return new Order(id,state);}
    private static Review r(String id,String order,Integer score){return new Review(id,order,score);}
    static boolean databaseAvailable(){return MemberPenetrationContractTest.databaseAvailable();}
}
