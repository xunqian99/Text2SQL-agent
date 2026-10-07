package com.text2sql.agent.tool;

/**
 * 智能体可调用的侦察工具类型枚举。
 */
public enum ToolType {
    INSPECT_TABLE,   // 查看某张表的完整结构和样例行
    INSPECT_COLUMN,  // 查看某列的真实枚举值与数据分布
    SAMPLE_QUERY,    // 执行小规模验证查询（强制限制 5 行）
    CHECK_JOIN,      // 验证两表连表条件是否可匹配产出数据
    EXPLAIN_QUERY,   // 预执行探测查询执行计划与代价（排查笛卡尔积或慢查询）
    FINAL_SQL        // 提交最终确定的 SQL
}
