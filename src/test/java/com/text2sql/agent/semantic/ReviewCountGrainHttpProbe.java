package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Frozen original mismatch plus explicit unit controls; never reinterpret the original score. */
public final class ReviewCountGrainHttpProbe {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("freeze/run NEW_DIRECTORY");
        Path directory=Path.of(args[1]).toAbsolutePath().normalize();
        if(args[0].equals("run")){CustomerIdentityHttpProbe.runFrozen(directory);return;}
        if(!args[0].equals("freeze"))throw new IllegalArgumentException("freeze/run");
        var items=new EvalItemLoader(new AgentProperties()).load("data/eval",List.of(),0).stream()
                .collect(Collectors.toMap(i->i.id(),Function.identity()));
        var original=items.get("T4-013");var score=items.get("T2-007");
        String joined=" FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id WHERE r.review_score<=2";
        CustomerIdentityHttpProbe.freezeReferences(directory,List.of(
                List.of("original-low-review-orders",original.question(),original.goldSql()),
                List.of("explicit-state-review-records","1分或2分的评价记录按客户州各有多少条？统计评价记录，一笔订单多条低分评价分别计数。只返回州、记录数，按记录数降序。",
                        "SELECT c.customer_state,COUNT(*)"+joined+" GROUP BY 1 ORDER BY 2 DESC"),
                List.of("explicit-state-distinct-orders","至少有一条1分或2分评价的订单按客户州各有多少笔？每笔订单只算一次，只返回州、订单数，按订单数降序。",
                        "SELECT c.customer_state,COUNT(DISTINCT o.order_id)"+joined+" GROUP BY 1 ORDER BY 2 DESC"),
                List.of("all-low-review-records","评价表里1分或2分的评价记录共多少条？一笔订单多条低分评价分别计数。",
                        "SELECT COUNT(*) FROM order_reviews WHERE review_score<=2"),
                List.of("all-low-distinct-orders","至少收到一条1分或2分评价的订单一共有多少笔？按订单去重，不统计评价条数。",
                        "SELECT COUNT(*) FROM orders o WHERE EXISTS (SELECT 1 FROM order_reviews r WHERE r.order_id=o.order_id AND r.review_score<=2)"),
                List.of("records-and-orders-same-request","1分或2分的评价记录条数及其涉及的不同订单数分别是多少？按此顺序只返回两个计数列。",
                        "SELECT COUNT(*),COUNT(DISTINCT order_id) FROM order_reviews WHERE review_score<=2"),
                List.of("review-score-groups-control",score.question(),score.goldSql()),
                List.of("orders-total-control","一共有多少笔订单？","SELECT COUNT(*) FROM orders")),
                "review-count-grain-http-v1","Exactly eight once; original T4-013 Gold retained despite record/order mismatch; six explicit review/order and score controls plus order total; not blind accuracy/P95.");
    }
}
