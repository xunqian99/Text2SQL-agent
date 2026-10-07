package com.text2sql.agent.validation;

import com.text2sql.agent.execution.QueryResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 启发式 SQL 结果集校验器（阶段 B）。
 *
 * <p><b>核心目标：为自纠错机制“制造信号”</b>
 *
 * <p>当前系统 91% 的失败属于「静默错误」：SQL 完全合法、通过 AST 校验、
 * 成功在数据库执行，但由于连表条件或过滤条件写偏，产出了荒谬或错误的结果。
 * 在生产环境没有标准答案（gold）可供比对的情况下，自纠错触发率为 0%。
 *
 * <p>本校验器实现一组不需要 gold 答案的轻量业务常识与统计规则，
 * 自动识别“大概率有问题”的异常结果，将静默错误转化为有信号的可自纠错反馈。
 */
@Component
public class ResultChecker {

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

        // 规则 3：比率/百分比越界异常（Ratio Out of Bounds）
        // 问题问“率/比/占比”，若返回数值 > 100 或 < 0（除极个别特殊增长率外），通常为分子分母颠倒
        if (normQ.contains("率") || normQ.contains("比") || normQ.contains("占比") || normQ.contains("比例")) {
            for (List<String> row : result.rows()) {
                for (String val : row) {
                    Double num = parseDouble(val);
                    if (num != null) {
                        // 如果是百分比（如 > 100 或 < 0）
                        // 注意：增长率允许负数或 >100%，因此排除“增长率/环比/同比”
                        boolean isGrowth = normQ.contains("增长") || normQ.contains("环比") || normQ.contains("同比");
                        if (!isGrowth) {
                            if (num < 0.0 || num > 100.0) {
                                return CheckResult.suspicious(
                                        "RATIO_OUT_OF_BOUNDS",
                                        "占比/比率计算结果（" + num + "）超出了合理的百分比范围 [0, 100]。请检查计算公式中的分子与分母是否颠倒。"
                                );
                            }
                        }
                    }
                }
            }
        }

        // 规则 4：负数异常（Non-Negative Column Negative Value）
        // 金额、GMV、订单量、客户数等指标绝不应该出现负数
        if (normQ.contains("金额") || normQ.contains("GMV") || normQ.contains("费用") || normQ.contains("价格")
                || normQ.contains("数量") || normQ.contains("笔数") || normQ.contains("人数")) {
            for (List<String> row : result.rows()) {
                for (String val : row) {
                    Double num = parseDouble(val);
                    if (num != null && num < 0.0) {
                        return CheckResult.suspicious(
                                "NEGATIVE_METRIC_VALUE",
                                "统计金额、数量或价格时出现了负数（" + num + "）。请检查计算表达式是否写反或数据筛选有误。"
                        );
                    }
                }
            }
        }

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

        return CheckResult.NORMAL;
    }

    private static Double parseDouble(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return Double.parseDouble(str.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
