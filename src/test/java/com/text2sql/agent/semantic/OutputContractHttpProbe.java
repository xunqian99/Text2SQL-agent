package com.text2sql.agent.semantic;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.evaluation.EvalItem;
import com.text2sql.agent.evaluation.EvalItemLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reuses the frozen-runtime/off-peak HTTP runner; references are committed before the one paid run. */
public final class OutputContractHttpProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("freeze/run NEW_DIRECTORY");
        Path dir = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("run")) { CustomerIdentityHttpProbe.runFrozen(dir); return; }
        if (!args[0].equals("freeze")) throw new IllegalArgumentException("freeze/run");
        Map<String, EvalItem> items = new EvalItemLoader(new AgentProperties()).load("data/eval", List.of(), 0)
                .stream().collect(Collectors.toMap(EvalItem::id, Function.identity()));
        var time = items.get("T3-002"); var repeat = items.get("T6-003"); var money = items.get("T6-001");
        String decimalSql = repeat.goldSql().replace("100.0 *", "1.0 *").replace(", 2)", ", 4)")
                .replace(" AS repeat_rate_pct", " AS repeat_rate_ratio");
        if (decimalSql.equals(repeat.goldSql())) throw new IllegalStateException("Explicit decimal reference did not change");
        CustomerIdentityHttpProbe.freezeReferences(dir, List.of(
                List.of("original-year-rows", time.question(), time.goldSql()),
                List.of("explicit-year-columns", time.question() + "请用横表输出，2017年和2018年各占一列，只输出两列订单数。",
                        "SELECT COUNT(*) FILTER(WHERE order_purchase_timestamp>='2017-01-01' AND order_purchase_timestamp<'2018-01-01'),"
                                + "COUNT(*) FILTER(WHERE order_purchase_timestamp>='2018-01-01' AND order_purchase_timestamp<'2019-01-01') FROM orders"),
                List.of("original-repeat-percent", repeat.question(), repeat.goldSql()),
                List.of("explicit-repeat-decimal", repeat.question() + "请以0到1的小数比率输出，保留4位小数，不要输出百分数。", decimalSql),
                List.of("money-two-metrics-control", money.question(), money.goldSql()),
                List.of("commission-decimal-control", "平台整体佣金率是多少？",
                        "SELECT ROUND(SUM(commission_amount)/NULLIF(SUM(gross_amount),0),4) FROM settlement_items")),
                "output-unit-shape-http-v1", "Exactly six targeted requests once; two old failures, explicit unit/shape overrides and two controls; not blind accuracy/P95.");
    }
}
