package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Existing references retained; record populations and explicit person geography are accounted separately. */
public final class CustomerPopulationHttpProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("freeze/run NEW_DIRECTORY");
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("run")) { CustomerIdentityHttpProbe.runFrozen(directory); return; }
        if (!args[0].equals("freeze")) throw new IllegalArgumentException("freeze/run");
        var items = new EvalItemLoader(new AgentProperties()).load("data/eval", List.of(), 0).stream()
                .collect(Collectors.toMap(i -> i.id(), Function.identity()));
        var top = items.get("T4-001"); var state = items.get("T1-009"); var ranking = items.get("T2-008");
        CustomerIdentityHttpProbe.freezeReferences(directory, List.of(
                List.of("original-customer-top10", top.question(), top.goldSql()),
                List.of("distinct-buyers", "有订单的顾客，按真实自然人去重后共多少人？",
                        "SELECT COUNT(DISTINCT c.customer_unique_id) FROM orders o JOIN customers c ON c.customer_id=o.customer_id"),
                List.of("explicit-row-key", "按 orders.customer_id 分组计数后，返回订单数量和对应的客户关联键数量，按订单数量升序。",
                        "SELECT cnt,COUNT(*) FROM (SELECT customer_id,COUNT(*) AS cnt FROM orders GROUP BY customer_id) t GROUP BY cnt ORDER BY cnt"),
                List.of("orders-total-control", "一共有多少笔订单？", "SELECT COUNT(*) FROM orders"),
                List.of("original-state-customers", state.question(), state.goldSql()),
                List.of("original-state-record-ranking", ranking.question(), ranking.goldSql()),
                List.of("explicit-state-people", "圣保罗州（SP）的客户按自然人去重后有多少人？",
                        "SELECT COUNT(DISTINCT customer_unique_id) FROM customers WHERE customer_state='SP'"),
                List.of("explicit-state-records", "圣保罗州（SP）的客户档案记录有多少条？按customer_id计数，不按自然人去重。",
                        "SELECT COUNT(*) FROM customers WHERE customer_state='SP'"),
                List.of("explicit-state-person-ranking", "客户按自然人统计，人数最多的5个州是哪些？各有多少人？同一人跨州可各计一次。",
                        "SELECT customer_state,COUNT(DISTINCT customer_unique_id) AS cnt FROM customers GROUP BY customer_state ORDER BY cnt DESC LIMIT 5"),
                List.of("global-records-and-people", "客户表的档案记录数与自然人去重人数分别是多少？按此顺序只返回两个计数列。",
                        "SELECT COUNT(*),COUNT(DISTINCT customer_unique_id) FROM customers")),
                "customer-population-http-v1", "Exactly ten preregistered requests once; retain previous four references including unresolved Top10 tie membership; separate record population from explicit person geography; not blind accuracy/P95.");
    }
}
