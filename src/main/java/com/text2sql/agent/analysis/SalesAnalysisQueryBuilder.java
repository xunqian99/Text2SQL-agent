package com.text2sql.agent.analysis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将结构化销售分析上下文转换为固定的只读 SQL 模板。
 *
 * <p>这里不让模型或前端生成 SQL。视图、Join、过滤字段、排序和 LIMIT 都由代码固定，
 * 只有经过白名单校验的上下文值可以进入查询。
 */
@Component
public class SalesAnalysisQueryBuilder {

    private static final int MAX_LITERAL_LENGTH = 200;
    private static final int MAX_RESULT_ROWS = 200;
    private static final String CATEGORY_EXPRESSION =
            "COALESCE(pct.product_category_name_english, p.product_category_name, '未分类')";

    public SalesAnalysisQuery build(AnalysisContext context, SalesAnalysisView view) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(view, "view must not be null");

        String metricExpression = metricExpression(context.metric().name());
        int year = year(context.period());
        boolean categoryJoin = view == SalesAnalysisView.CATEGORY
                || context.filters().stream().anyMatch(filter -> filter.field().equals("category"));
        boolean customerJoin = view == SalesAnalysisView.REGION
                || context.filters().stream().anyMatch(filter ->
                filter.field().equals("customer_state") || filter.field().equals("region"));

        StringBuilder sql = new StringBuilder("SELECT ");
        switch (view) {
            case TOTAL -> sql.append("ROUND(COALESCE(").append(metricExpression)
                    .append(", 0), 2) AS metric_value");
            case MONTH -> sql.append("DATE_TRUNC('month', o.order_purchase_timestamp)::date AS month, ")
                    .append("ROUND(COALESCE(").append(metricExpression)
                    .append(", 0), 2) AS metric_value");
            case CATEGORY -> sql.append(CATEGORY_EXPRESSION).append(" AS category, ")
                    .append("ROUND(COALESCE(").append(metricExpression)
                    .append(", 0), 2) AS metric_value");
            case REGION -> sql.append("c.customer_state AS region, ")
                    .append("ROUND(COALESCE(").append(metricExpression)
                    .append(", 0), 2) AS metric_value");
        }

        sql.append(" FROM orders o")
                .append(" JOIN order_items oi ON oi.order_id = o.order_id");
        if (categoryJoin) {
            sql.append(" JOIN products p ON p.product_id = oi.product_id")
                    .append(" LEFT JOIN product_category_translation pct")
                    .append(" ON pct.product_category_name = p.product_category_name");
        }
        if (customerJoin) {
            sql.append(" JOIN customers c ON c.customer_id = o.customer_id");
        }

        List<String> predicates = new ArrayList<>();
        predicates.add("o.order_status NOT IN ('canceled', 'unavailable')");
        predicates.add("o.order_purchase_timestamp >= DATE '%04d-01-01'".formatted(year));
        predicates.add("o.order_purchase_timestamp < DATE '%04d-01-01'".formatted(year + 1));
        for (AnalysisFilter filter : context.filters()) {
            predicates.add(renderFilter(filter));
        }
        sql.append(" WHERE ").append(String.join(" AND ", predicates));

        switch (view) {
            case TOTAL -> {
                // 单值结果不需要排序或 LIMIT。
            }
            case MONTH -> sql.append(" GROUP BY 1 ORDER BY month ASC");
            case CATEGORY -> sql.append(" GROUP BY 1 ORDER BY metric_value DESC NULLS LAST, category ASC LIMIT ")
                    .append(MAX_RESULT_ROWS);
            case REGION -> sql.append(" GROUP BY 1 ORDER BY metric_value DESC NULLS LAST, region ASC LIMIT ")
                    .append(MAX_RESULT_ROWS);
        }

        List<String> columns = switch (view) {
            case TOTAL -> List.of("metric_value");
            case MONTH -> List.of("month", "metric_value");
            case CATEGORY -> List.of("category", "metric_value");
            case REGION -> List.of("region", "metric_value");
        };
        return new SalesAnalysisQuery(view, sql.toString(), columns);
    }

    private static String metricExpression(String metric) {
        return switch (metric) {
            case "gmv_with_freight" -> "SUM(oi.price + oi.freight_value)";
            case "sales_without_freight" -> "SUM(oi.price)";
            default -> throw new IllegalArgumentException("Unsupported sales metric: " + metric);
        };
    }

    private static int year(AnalysisContext.Period period) {
        if (!period.type().equals("YEAR")) {
            throw new IllegalArgumentException("Only YEAR period is supported in B-1");
        }
        try {
            int parsed = Integer.parseInt(period.value());
            if (parsed < 1900 || parsed > 2100) {
                throw new IllegalArgumentException("Year is outside supported range: " + period.value());
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Year must be numeric: " + period.value(), e);
        }
    }

    private static String renderFilter(AnalysisFilter filter) {
        if (filter.operator() != AnalysisFilter.Operator.EQ) {
            throw new IllegalArgumentException("Only EQ filters are supported in B-1: " + filter.field());
        }
        String expression = switch (filter.field()) {
            case "category" -> CATEGORY_EXPRESSION;
            case "customer_state", "region" -> "c.customer_state";
            case "order_status" -> "o.order_status";
            default -> throw new IllegalArgumentException("Unsupported sales filter: " + filter.field());
        };
        return expression + " = " + quoteLiteral(filter.value());
    }

    private static String quoteLiteral(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LITERAL_LENGTH) {
            throw new IllegalArgumentException("Filter value is blank or too long");
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
