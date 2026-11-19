package com.text2sql.agent.validation;

import com.text2sql.agent.execution.QueryResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 启发式 SQL 结果集校验器（阶段 B）。
 *
 * <p>执行成功不代表业务语义正确。本组件用不依赖 Gold 的轻量规则提供可疑信号，
 * 而非正确性证明。列名是弱证据：增长、净额与未知列不能继承整道问题的金额约束。
 */
@Component
public class ResultChecker {
    public static final String POLICY_VERSION = "output-value-and-entity-extremum-v2";

    private final ExtremumProjectionChecker extremumProjectionChecker;

    /** Existing isolated fixtures can use only the original value heuristics. */
    public ResultChecker() { this.extremumProjectionChecker = null; }

    @Autowired
    public ResultChecker(ExtremumProjectionChecker extremumProjectionChecker) {
        this.extremumProjectionChecker = java.util.Objects.requireNonNull(extremumProjectionChecker);
    }

    /** After a proven omission, an unresolvable correction cannot silently count as a repair. */
    public CheckResult checkCorrection(String question, String sql, QueryResult result, String initialRule) {
        CheckResult check = check(question, sql, result);
        if (check.suspicious()) return check;
        if (ExtremumProjectionChecker.RULE.equals(initialRule) && extremumProjectionChecker != null
                && extremumProjectionChecker.inspect(question, sql).status() != ExtremumProjectionChecker.Status.PRESENT) {
            return CheckResult.suspicious("UNVERIFIED_EXTREMUM_CORRECTION",
                    "首次已证实缺少实体寻优属性，但本次无法确认该属性原值已进入最外层输出；保留首次结果。");
        }
        return check;
    }

    /**
     * 对查询结果与执行的 SQL 进行多维度启发式检查。
     *
     * @param question 用户原始问题
     * @param sql 执行成功的 SQL
     * @param result 数据库返回的查询结果集
     * @return 校验结果
     */
    public CheckResult check(String question, String sql, QueryResult result) {
        if (result == null) {
            return CheckResult.suspicious("NULL_RESULT", "数据库返回空结果对象");
        }

        String normSql = sql == null ? "" : sql.toUpperCase(Locale.ROOT);
        String normQ = question == null ? "" : question.strip();

        // 规则 1：空结果集检查（带过滤条件的统计查询如果返回 0 行，通常是过滤条件拼写过死或大小写不匹配）
        if (result.rowCount() == 0) {
            // 如果问题不是明确在问“不存在”或“哪些没有”，带 WHERE 的聚合/查询返回 0 行极度可疑
            if (normSql.contains(" WHERE ") && !normQ.contains("没有") && !normQ.contains("未") && !normQ.contains("零")) {
                return CheckResult.suspicious(
                        "EMPTY_RESULT",
                        "查询返回了 0 行数据。请检查 WHERE 条件中的字符串常量大小写、状态代码枚举值或 JOIN 条件是否过于严苛。"
                );
            }
        }

        // 规则 2：非分组聚合单行全 NULL 异常
        // 如 SELECT SUM(price) ... 如果返回一行且值为 NULL，说明没有匹配到数据或字段类型转换异常
        if (result.rowCount() == 1 && !result.columns().isEmpty()) {
            List<String> firstRow = result.rows().get(0);
            boolean isPureAgg = (normSql.contains("SUM(") || normSql.contains("AVG(") || normSql.contains("COUNT("))
                    && !normSql.contains("GROUP BY");
            if (isPureAgg && firstRow != null) {
                boolean allNullOrEmpty = firstRow.stream().allMatch(v -> v == null || v.isBlank() || "null".equalsIgnoreCase(v));
                if (allNullOrEmpty) {
                    return CheckResult.suspicious(
                            "ALL_NULL_AGGREGATE",
                            "单行聚合计算结果全为 NULL。说明关联条件未匹配到任何数据，或者聚合的字段全为 NULL。"
                    );
                }
            }
        }

        // Rules 3/4 share column identities and evidence, not question-wide exemptions.
        // In a mixed GMV + YoY + refund-rate result, each column gets its own policy.
        List<ResultColumnPolicy> policies = ResultColumnPolicy.classify(result.columns());
        CheckResult ratioCheck = checkNumericColumns(result, policies, ResultColumnPolicy.Kind.BOUNDED_RATIO);
        if (ratioCheck.suspicious()) return ratioCheck;
        CheckResult negativeCheck = checkNumericColumns(result, policies, ResultColumnPolicy.Kind.NON_NEGATIVE);
        if (negativeCheck.suspicious()) return negativeCheck;

        // 规则 5：疑似笛卡尔积（Cartesian Product 行数爆炸）
        // 单表维度的统计（如“每个州/每个类目”），数据库通常只有几十个州或类目，如果无 GROUP BY 或者无正确关联出现几万行，极度可疑
        if (normSql.contains(" JOIN ") && !normSql.contains(" ON ") && !normSql.contains(" USING ")) {
            return CheckResult.suspicious(
                    "MISSING_JOIN_CONDITION",
                    "SQL 中存在 JOIN 但缺少 ON 或 USING 关联条件，疑似产生笛卡尔积。"
            );
        }

        // 规则 6：时间周期维度丢失（Missing Time Dimension）
        // 问题明确要求按周期（如每个月、每周、每年）统计，但 SQL 未使用 DATE_TRUNC 或 EXTRACT 进行时间分组
        boolean asksTimeGrouping = normQ.contains("每个月") || normQ.contains("每月") || normQ.contains("按月")
                || normQ.contains("每周") || normQ.contains("按周") || normQ.contains("每年") || normQ.contains("按年");
        if (asksTimeGrouping && !normSql.contains("DATE_TRUNC") && !normSql.contains("EXTRACT") && !normSql.contains("GROUP BY")) {
            return CheckResult.suspicious(
                    "MISSING_TIME_DIMENSION",
                    "问题明确要求按时间周期（如每个月/每周/每年）统计，但 SQL 缺少时间截断（DATE_TRUNC）或按时间维度的 GROUP BY 分组。"
            );
        }

        // 规则 7：金额指标仅计数未累加求和（Count Instead of Sum For Money）
        // 问题明确询问“收上来的钱/退款总额/退款金额/优惠金额/销售金额/佣金收入/净额合计”，但 SQL 只用了 COUNT 未用 SUM
        boolean asksMoney = normQ.contains("收上来的钱") || normQ.contains("退款总额") || normQ.contains("退款金额")
                || normQ.contains("优惠金额") || normQ.contains("销售金额") || normQ.contains("佣金收入")
                || normQ.contains("净额合计") || normQ.contains("优惠了多少钱");
        if (asksMoney && normSql.contains("COUNT(") && !normSql.contains("SUM(")) {
            return CheckResult.suspicious(
                    "COUNT_INSTEAD_OF_SUM",
                    "问题询问的是金额/总额（钱数），但 SQL 仅使用了 COUNT 进行行数计数，未对金额字段使用 SUM 进行求和累加。"
            );
        }

        // 规则 8：退款金额误聚合数量列（Sum Qty Instead of Amount）
        if ((normQ.contains("金额") || normQ.contains("钱")) && normSql.contains("REFUND_QTY")) {
            return CheckResult.suspicious(
                    "SUM_QTY_INSTEAD_OF_VALUE",
                    "问题询问的是退款金额，但 SQL 聚合了数量列 refund_qty 而非金额列（refund_value 或 refunds.refund_amount）。"
            );
        }

        return extremumProjectionChecker == null ? CheckResult.NORMAL
                : extremumProjectionChecker.check(question, sql);
    }

    private static CheckResult checkNumericColumns(QueryResult result, List<ResultColumnPolicy> policies,
                                                   ResultColumnPolicy.Kind kind) {
        for (int rowIndex = 0; rowIndex < result.rows().size(); rowIndex++) {
            List<String> row = result.rows().get(rowIndex);
            if (row == null) continue;
            for (ResultColumnPolicy policy : policies) {
                if (policy.kind() != kind || policy.index() >= row.size()) continue;
                BigDecimal num = parseNumber(row.get(policy.index()));
                if (num == null) continue;
                boolean ratioViolation = kind == ResultColumnPolicy.Kind.BOUNDED_RATIO
                        && (num.signum() < 0 || num.compareTo(BigDecimal.valueOf(100)) > 0);
                boolean negativeViolation = kind == ResultColumnPolicy.Kind.NON_NEGATIVE && num.signum() < 0;
                if (!ratioViolation && !negativeViolation) continue;
                String evidence = "输出列「" + policy.label() + "」（第 " + (policy.index() + 1)
                        + " 列，依据 " + policy.source() + "/" + policy.rule() + "），第 " + (rowIndex + 1) + " 行";
                if (ratioViolation) {
                    return CheckResult.suspicious("RATIO_OUT_OF_BOUNDS", evidence + "的值（" + num
                            + "）超出当前比例检查范围 [0, 100]。请核对比例单位及分子分母；不要改动其他列的业务口径。");
                }
                if (negativeViolation) {
                    return CheckResult.suspicious("NEGATIVE_METRIC_VALUE", evidence + "的值（" + num
                            + "）为负数，按列名被识别为非负金额或数量。请核对该列的表达式和列名；不要改动增长率列或时间范围。");
                }
            }
        }
        return CheckResult.NORMAL;
    }

    private static BigDecimal parseNumber(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return new BigDecimal(str.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
