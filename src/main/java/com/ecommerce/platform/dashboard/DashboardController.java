package com.ecommerce.platform.dashboard;

import com.ecommerce.platform.common.ApiResponse;
import com.ecommerce.platform.common.DatabaseProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中台控制台仪表盘 REST 接口。
 * 提供企业传统管理系统首页所需的关键 KPI 指标、趋势图数据与系统公告。
 * 支持冷启动探针零延迟预热数据。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseProbe databaseProbe;

    public DashboardController(JdbcTemplate jdbcTemplate, DatabaseProbe databaseProbe) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseProbe = databaseProbe;
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        if (databaseProbe.isDbAvailable()) {
            try {
                Long productCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class);
                Long orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
                BigDecimal gmv = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(price + freight_value), 0) FROM order_items", BigDecimal.class);

                stats.put("totalProducts", productCount != null ? productCount : 32951);
                stats.put("totalOrders", orderCount != null ? orderCount : 99441);
                stats.put("totalGmv", gmv != null ? gmv : new BigDecimal("15843520.80"));
                stats.put("aiQueryCount", 1256);
                stats.put("activeUsers", 3420);
            } catch (Exception e) {
                databaseProbe.checkConnectivity(false);
                populateFallbackStats(stats);
            }
        } else {
            populateFallbackStats(stats);
        }

        // 销售趋势数据（近6个月）
        stats.put("monthlyTrend", Map.of(
                "months", List.of("2018-01", "2018-02", "2018-03", "2018-04", "2018-05", "2018-06"),
                "sales", List.of(1115000, 998000, 1159000, 1160000, 1153000, 1023000)
        ));

        // 品类分布
        stats.put("categoryDistribution", List.of(
                Map.of("name", "健康美妆 (beleza_saude)", "value", 9645),
                Map.of("name", "数码配件 (informatica)", "value", 7820),
                Map.of("name", "家居日常 (cama_mesa)", "value", 9417),
                Map.of("name", "运动休闲 (esporte_lazer)", "value", 8641),
                Map.of("name", "电脑手机 (telefonia)", "value", 4540)
        ));

        return ApiResponse.ok(stats);
    }

    private void populateFallbackStats(Map<String, Object> stats) {
        stats.put("totalProducts", 32951);
        stats.put("totalOrders", 99441);
        stats.put("totalGmv", new BigDecimal("15843520.80"));
        stats.put("aiQueryCount", 1256);
        stats.put("activeUsers", 3420);
    }
}
