package com.text2sql.agent.generation;

import com.text2sql.agent.observability.LlmCallRecord;

/**
 * 生成层的输出：SQL + 这次调用的成本。
 *
 * <p>把成本记录和 SQL 绑在同一个对象里返回，而不是让编排层再去问「刚才花了多少」。
 * 理由是：如果成本记录是旁路写入的，一旦某条分支忘记记录，统计就会静默缺失，
 * 而这种缺失在最终报告里完全看不出来。绑在返回值上，类型系统会强制传递它。
 *
 * @param sql       模型产出的 SQL，已剥掉 markdown 代码块围栏
 * @param rawOutput 模型原始输出，失败归因时用；不进 API 响应
 * @param call      本次调用的 token / 耗时 / 成本
 */
public record GeneratedSql(String sql, String rawOutput, LlmCallRecord call) {
}
