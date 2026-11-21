package com.text2sql.agent.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesAnalysisQueryBuilderTest {

    private final SalesAnalysisQueryBuilder builder = new SalesAnalysisQueryBuilder();

    @Test
    @DisplayName("含运费总额使用 GMV 口径并限定有效订单和年份")
    void buildsGmvTotalQuery() {
        SalesAnalysisQuery query = builder.build(context("gmv_with_freight"), SalesAnalysisView.TOTAL);

        assertThat(query.sql()).contains("SUM(oi.price + oi.freight_value)")
                .contains("o.order_status NOT IN ('canceled', 'unavailable')")
                .contains("DATE '2018-01-01'")
                .contains("DATE '2019-01-01'");
        assertThat(query.columns()).containsExactly("metric_value");
    }

    @Test
    @DisplayName("不含运费销售额只聚合商品价格")
    void buildsSalesWithoutFreightQuery() {
        SalesAnalysisQuery query = builder.build(context("sales_without_freight"), SalesAnalysisView.TOTAL);

        assertThat(query.sql()).contains("SUM(oi.price)")
                .doesNotContain("SUM(oi.price + oi.freight_value)");
    }

    @Test
    @DisplayName("月份下钻按月分组并按时间升序")
    void buildsMonthlyDrilldownQuery() {
        SalesAnalysisQuery query = builder.build(context("gmv_with_freight"), SalesAnalysisView.MONTH);

        assertThat(query.sql()).contains("DATE_TRUNC('month', o.order_purchase_timestamp)::date AS month")
                .contains("GROUP BY 1")
                .contains("ORDER BY month ASC");
        assertThat(query.columns()).containsExactly("month", "metric_value");
    }

    @Test
    @DisplayName("品类下钻连接商品分类翻译并按销售额降序")
    void buildsCategoryDrilldownQuery() {
        SalesAnalysisQuery query = builder.build(context("gmv_with_freight"), SalesAnalysisView.CATEGORY);

        assertThat(query.sql()).contains("JOIN products p ON p.product_id = oi.product_id")
                .contains("product_category_translation pct")
                .contains("AS category")
                .contains("ORDER BY metric_value DESC");
        assertThat(query.columns()).containsExactly("category", "metric_value");
    }

    @Test
    @DisplayName("地区下钻使用客户收货州并保留并列排序")
    void buildsRegionDrilldownQuery() {
        SalesAnalysisQuery query = builder.build(context("gmv_with_freight"), SalesAnalysisView.REGION);

        assertThat(query.sql()).contains("JOIN customers c ON c.customer_id = o.customer_id")
                .contains("c.customer_state AS region")
                .contains("ORDER BY metric_value DESC NULLS LAST, region ASC")
                .contains("LIMIT 200");
        assertThat(query.columns()).containsExactly("region", "metric_value");
    }

    @Test
    @DisplayName("结构化过滤条件会进入查询且不会丢失原上下文")
    void rendersSupportedFilters() {
        AnalysisContext filtered = context("gmv_with_freight")
                .withFilter(AnalysisFilter.eq("category", "beleza_saude", AnalysisContext.Source.USER_DIRECT))
                .withFilter(AnalysisFilter.eq("customer_state", "SP", AnalysisContext.Source.USER_DIRECT));

        SalesAnalysisQuery query = builder.build(filtered, SalesAnalysisView.TOTAL);

        assertThat(query.sql()).contains("COALESCE(pct.product_category_name_english, p.product_category_name, '未分类') = 'beleza_saude'")
                .contains("c.customer_state = 'SP'")
                .contains("JOIN products p ON p.product_id = oi.product_id")
                .contains("JOIN customers c ON c.customer_id = o.customer_id");
    }

    @Test
    @DisplayName("未知指标、周期和过滤字段拒绝生成 SQL")
    void rejectsUnsupportedContext() {
        assertThatThrownBy(() -> builder.build(context("unknown_metric"), SalesAnalysisView.TOTAL))
                .isInstanceOf(IllegalArgumentException.class);

        AnalysisContext unsupportedPeriod = new AnalysisContext(
                "session-001", "analysis-001",
                new AnalysisContext.MetricRef("gmv_with_freight", AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("MONTH", "2018-01"), List.of(), List.of(), null,
                "sha256:metric-v1");
        assertThatThrownBy(() -> builder.build(unsupportedPeriod, SalesAnalysisView.TOTAL))
                .isInstanceOf(IllegalArgumentException.class);

        AnalysisContext unsupportedFilter = context("gmv_with_freight")
                .withFilter(AnalysisFilter.eq("unknown_column", "x", AnalysisContext.Source.USER_DIRECT));
        assertThatThrownBy(() -> builder.build(unsupportedFilter, SalesAnalysisView.TOTAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AnalysisContext context(String metric) {
        return new AnalysisContext(
                "session-001", "analysis-001",
                new AnalysisContext.MetricRef(metric, AnalysisContext.Source.METRIC_DEFINITION),
                new AnalysisContext.Period("YEAR", "2018"), List.of(), List.of(), null,
                "sha256:metric-v1");
    }
}
