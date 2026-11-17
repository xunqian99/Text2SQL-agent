package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItemLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fixed count/shape cases and controls; references are committed before any model call. */
public final class CountComparisonHttpProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("freeze/run NEW_DIRECTORY");
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("run")) { CustomerIdentityHttpProbe.runFrozen(directory); return; }
        if (!args[0].equals("freeze")) throw new IllegalArgumentException("freeze/run");
        var items = new EvalItemLoader(new AgentProperties()).load("data/eval", List.of(), 0).stream()
                .collect(Collectors.toMap(i -> i.id(), Function.identity()));
        var status = items.get("T2-013"); var payment = items.get("T2-014"); var years = items.get("T3-002");
        var distribution = items.get("T2-005"); var money = items.get("T6-001"); var months = items.get("T3-001");
        CustomerIdentityHttpProbe.freezeReferences(directory, List.of(
                List.of("original-status-counts", status.question(), status.goldSql()),
                List.of("reversed-named-counts", "分别统计已取消订单数和已送达订单数，按此顺序返回两个计数。",
                        "SELECT COUNT(*) FILTER(WHERE order_status='canceled'),COUNT(*) FILTER(WHERE order_status='delivered') FROM orders"),
                List.of("original-payment-counts", payment.question(), payment.goldSql()),
                List.of("original-year-rows", years.question(), years.goldSql()),
                List.of("explicit-year-columns", years.question() + "请用横表输出，2017年和2018年各占一列，只输出两列订单数。",
                        "SELECT COUNT(*) FILTER(WHERE order_purchase_timestamp>='2017-01-01' AND order_purchase_timestamp<'2018-01-01'),"
                                + "COUNT(*) FILTER(WHERE order_purchase_timestamp>='2018-01-01' AND order_purchase_timestamp<'2019-01-01') FROM orders"),
                List.of("explicit-status-rows", status.question() + "请用纵表，只返回状态、订单数两列，每个状态一行，按状态升序。",
                        "SELECT order_status,COUNT(*) FROM orders WHERE order_status IN ('delivered','canceled') GROUP BY order_status ORDER BY order_status"),
                List.of("all-status-groups-control", distribution.question(), distribution.goldSql()),
                List.of("money-two-metrics-control", money.question(), money.goldSql()),
                List.of("explicit-year-start-timestamps", years.question() + "请用纵表，第一列明确返回年初时间戳，不要整数年份；第二列为订单数，按时间升序。",
                        "SELECT DATE_TRUNC('year',order_purchase_timestamp),COUNT(*) FROM orders WHERE order_purchase_timestamp>='2017-01-01' "
                                + "AND order_purchase_timestamp<'2019-01-01' GROUP BY 1 ORDER BY 1"),
                List.of("monthly-label-control", months.question(), months.goldSql())),
                "named-count-comparison-http-v3", "Exactly ten unchanged v2 references once; metadata-backed per-request named count contract with explicit/time/group controls; not blind accuracy/P95.");
    }
}
