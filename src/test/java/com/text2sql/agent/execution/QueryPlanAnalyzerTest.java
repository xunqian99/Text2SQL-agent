package com.text2sql.agent.execution;

import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryPlanAnalyzerTest {

    private JdbcTemplate jdbcTemplate;
    private AgentProperties properties;
    private QueryPlanAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        properties = new AgentProperties();
        analyzer = new QueryPlanAnalyzer(jdbcTemplate, properties);
    }

    @Test
    @DisplayName("安全查询：返回正常总代价与行数，判定为 safe")
    void analyzesSafeQuery() {
        String planJson = """
                [
                  {
                    "Plan": {
                      "Node Type": "Seq Scan",
                      "Relation Name": "orders",
                      "Startup Cost": 0.00,
                      "Total Cost": 35.50,
                      "Plan Rows": 100,
                      "Plan Width": 32
                    }
                  }
                ]
                """;
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(planJson));

        var analysis = analyzer.analyze("SELECT * FROM orders WHERE order_status = 'delivered'");

        assertThat(analysis.safe()).isTrue();
        assertThat(analysis.totalCost()).isEqualTo(35.50);
        assertThat(analysis.planRows()).isEqualTo(100L);
        assertThat(analysis.hasCartesianProduct()).isFalse();
        assertThat(analysis.riskDescription()).isNull();
    }

    @Test
    @DisplayName("笛卡尔积检测：无关联条件的 Nested Loop（预估行数大）被识别为高危")
    void detectsCartesianProduct() {
        String planJson = """
                [
                  {
                    "Plan": {
                      "Node Type": "Nested Loop",
                      "Startup Cost": 0.00,
                      "Total Cost": 250000.00,
                      "Plan Rows": 9999999,
                      "Plan Width": 64,
                      "Plans": [
                        {
                          "Node Type": "Seq Scan",
                          "Relation Name": "orders"
                        },
                        {
                          "Node Type": "Seq Scan",
                          "Relation Name": "customers"
                        }
                      ]
                    }
                  }
                ]
                """;
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(planJson));

        var analysis = analyzer.analyze("SELECT * FROM orders, customers");

        assertThat(analysis.safe()).isFalse();
        assertThat(analysis.hasCartesianProduct()).isTrue();
        assertThat(analysis.riskDescription()).contains("无约束全表笛卡尔积");
        assertThat(analysis.riskDescription()).contains("Nested Loop Join");
    }

    @Test
    @DisplayName("带 Join Filter 的 Nested Loop 视为正常连接，不误判为笛卡尔积")
    void ignoresNestedLoopWithFilter() {
        String planJson = """
                [
                  {
                    "Plan": {
                      "Node Type": "Nested Loop",
                      "Join Filter": "(orders.customer_id = customers.customer_id)",
                      "Startup Cost": 0.00,
                      "Total Cost": 120.00,
                      "Plan Rows": 50,
                      "Plan Width": 64
                    }
                  }
                ]
                """;
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(planJson));

        var analysis = analyzer.analyze("SELECT * FROM orders JOIN customers ON orders.customer_id = customers.customer_id");

        assertThat(analysis.safe()).isTrue();
        assertThat(analysis.hasCartesianProduct()).isFalse();
    }

    @Test
    @DisplayName("代价超标拦截：Total Cost 超过安全上限 maxAllowedCost 时判定为高危")
    void detectsCostExceeded() {
        properties.getGuard().setMaxAllowedCost(50000.0);

        String planJson = """
                [
                  {
                    "Plan": {
                      "Node Type": "Hash Join",
                      "Hash Cond": "(orders.customer_id = customers.customer_id)",
                      "Startup Cost": 500.00,
                      "Total Cost": 88888.00,
                      "Plan Rows": 100000,
                      "Plan Width": 64
                    }
                  }
                ]
                """;
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(List.of(planJson));

        var analysis = analyzer.analyze("SELECT * FROM orders JOIN customers ON orders.customer_id = customers.customer_id");

        assertThat(analysis.safe()).isFalse();
        assertThat(analysis.hasCartesianProduct()).isFalse();
        assertThat(analysis.riskDescription()).contains("执行计划预估总代价超标");
        assertThat(analysis.riskDescription()).contains("88888.0");
    }

    @Test
    @DisplayName("空 SQL 或异常输入防御")
    void handlesEmptySql() {
        var analysis = analyzer.analyze("");
        assertThat(analysis.safe()).isFalse();
        assertThat(analysis.riskDescription()).contains("SQL 为空");
    }

    @Test
    @DisplayName("数据库异常时降级返回错误分析")
    void handlesDbException() {
        when(jdbcTemplate.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        var analysis = analyzer.analyze("SELECT * FROM orders");
        assertThat(analysis.safe()).isFalse();
        assertThat(analysis.riskDescription()).contains("执行计划生成失败");
    }
}
