package com.text2sql.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 查询执行计划分析器（Query Plan Analyzer）。
 *
 * <p><b>解决的核心痛点：大模型生成的 SQL 存在隐式全表笛卡尔积或超高算力消耗慢查询</b>
 *
 * <p>大模型在编写多表查询时，可能因为漏写关联条件或未能利用索引，生成出诸如
 * {@code SELECT * FROM orders, customers} 之类的 SQL。此类语句 AST 语法完全合法，
 * 但在数据库执行时会导致千万级全表笛卡尔积，瞬间消耗数 GB 内存并阻塞连接池。
 *
 * <p>本组件在 SQL 真正提交物理执行之前，通过 PostgreSQL 原生 {@code EXPLAIN (FORMAT JSON)} 命令
 * 获取执行计划树，进行微秒级预执行安全代价评估（Cost Guard）：
 * <ol>
 *   <li>解析总预估代价（Total Cost），拦截超过安全阈值的慢查询；</li>
 *   <li>递归遍历执行计划树，检测无过滤条件的 Nested Loop（全表笛卡尔积）；</li>
 *   <li>产出结构化代价风险诊断，作为环境反馈回灌给 Agent 触发反思与重规划。</li>
 * </ol>
 */
@Component
public class QueryPlanAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanAnalyzer.class);

    private final JdbcTemplate jdbcTemplate;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    public QueryPlanAnalyzer(JdbcTemplate jdbcTemplate, AgentProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 执行计划分析结论。
     *
     * @param safe                 是否安全（可放行执行）
     * @param totalCost            预估总代价
     * @param planRows             预估输出行数
     * @param hasCartesianProduct  是否包含无约束笛卡尔积
     * @param riskDescription      风险详细描述（若有）
     * @param rawPlanJson          原生执行计划 JSON 文本
     */
    public record PlanAnalysis(
            boolean safe,
            double totalCost,
            long planRows,
            boolean hasCartesianProduct,
            String riskDescription,
            String rawPlanJson) {

        public static PlanAnalysis safe(double cost, long rows, String raw) {
            return new PlanAnalysis(true, cost, rows, false, null, raw);
        }

        public static PlanAnalysis risk(double cost, long rows, boolean cartesian, String desc, String raw) {
            return new PlanAnalysis(false, cost, rows, cartesian, desc, raw);
        }

        public static PlanAnalysis error(String message) {
            return new PlanAnalysis(false, 0.0, 0L, false, message, null);
        }
    }

    /**
     * 对 SQL 进行预执行代价与执行计划分析。
     */
    public PlanAnalysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return PlanAnalysis.error("SQL 为空，无法执行计划分析");
        }

        String explainSql = "EXPLAIN (FORMAT JSON) " + sql;
        int timeout = properties.getDb().getQueryTimeoutSeconds();

        try {
            List<String> results = jdbcTemplate.query(
                    connection -> {
                        connection.setReadOnly(true);
                        PreparedStatement ps = connection.prepareStatement(explainSql);
                        ps.setQueryTimeout(timeout);
                        return ps;
                    },
                    (rs, rowNum) -> rs.getString(1)
            );

            if (results.isEmpty() || results.get(0) == null) {
                return PlanAnalysis.error("数据库未返回执行计划");
            }

            String jsonText = results.get(0);
            JsonNode rootArray = objectMapper.readTree(jsonText);
            if (!rootArray.isArray() || rootArray.isEmpty()) {
                return PlanAnalysis.error("执行计划返回格式异常（非 JSON 数组）");
            }

            JsonNode planNode = rootArray.get(0).path("Plan");
            double totalCost = planNode.path("Total Cost").asDouble(0.0);
            long planRows = planNode.path("Plan Rows").asLong(0L);

            boolean cartesian = detectCartesianProduct(planNode);

            double maxCost = properties.getGuard().getMaxAllowedCost();
            boolean costExceeded = totalCost > maxCost;

            if (cartesian && properties.getGuard().isRejectCartesianProduct()) {
                String desc = String.format("执行计划检测到无约束全表笛卡尔积（Nested Loop Join，预估输出 %d 行，Total Cost: %.1f）。请检查多表之间是否缺少 JOIN 连接条件（ON / WHERE）。",
                        planRows, totalCost);
                return PlanAnalysis.risk(totalCost, planRows, true, desc, jsonText);
            }

            if (costExceeded) {
                String desc = String.format("执行计划预估总代价超标（Total Cost: %.1f > 安全阈值: %.1f，预估输出 %d 行）。疑似缺少有效过滤条件或索引缺失，存在拖慢数仓风险。",
                        totalCost, maxCost, planRows);
                return PlanAnalysis.risk(totalCost, planRows, false, desc, jsonText);
            }

            return PlanAnalysis.safe(totalCost, planRows, jsonText);

        } catch (Exception e) {
            log.warn("EXPLAIN 执行计划分析异常：{}", e.getMessage());
            // 如果执行计划都无法生成，通常是语法方言或表列问题，交由执行器或报错拦截
            return PlanAnalysis.error("执行计划生成失败：" + e.getMessage());
        }
    }

    /**
     * 递归检测执行计划树中是否存在全表笛卡尔积。
     *
     * <p>特征：节点为 {@code Nested Loop}，Join Type 为 {@code Inner} 或空，且不存在 {@code Join Filter} 或 {@code Hash Cond}。
     */
    private boolean detectCartesianProduct(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return false;
        }

        String nodeType = node.path("Node Type").asText("");
        if ("Nested Loop".equalsIgnoreCase(nodeType)) {
            boolean hasJoinFilter = node.has("Join Filter");
            boolean hasHashCond = node.has("Hash Cond");
            long rows = node.path("Plan Rows").asLong(0L);

            // 无连接过滤条件且预估行数较大时，视为笛卡尔积
            if (!hasJoinFilter && !hasHashCond && rows > 1000) {
                return true;
            }
        }

        JsonNode plans = node.path("Plans");
        if (plans.isArray()) {
            for (JsonNode child : plans) {
                if (detectCartesianProduct(child)) {
                    return true;
                }
            }
        }

        return false;
    }
}
