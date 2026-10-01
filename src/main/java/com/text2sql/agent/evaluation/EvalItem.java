package com.text2sql.agent.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一条评估项，对应 YAML 里的一个元素。
 *
 * <p>用 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 而不是严格模式：
 * 评估集是人工维护的 YAML，之后一定会加字段（比如阶段 2 要加「期望召回的表」）。
 * 严格模式会让「加了个新字段」变成「整个评估跑不起来」，这种耦合没有任何收益。
 *
 * <p>字段名用 {@code @JsonProperty} 显式映射，而不是靠 Jackson 的命名策略。
 * YAML 里是 {@code gold_sql}（下划线），Java 里是 {@code goldSql}（驼峰）。
 * 依赖全局命名策略意味着这个类的正确性取决于一个远离它的配置，
 * 改一次配置就可能静默失效。显式注解把依赖放在看得见的地方。
 *
 * @param id         唯一编号，如 T1-001。报告里按它定位失败案例
 * @param difficulty 难度分层，用于分组统计
 * @param tags       能力标签（计数 / 时间 / 多表 / 口径），用于归因分析
 * @param question   中文自然语言问题，就是喂给链路的那句话
 * @param goldSql    标准 SQL，作为正确答案
 * @param notes      口径说明。**这一栏是给人看的，程序不解析**
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalItem(
        String id,
        String difficulty,
        List<String> tags,
        String question,
        @JsonProperty("gold_sql") String goldSql,
        String notes) {

    public EvalItem {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
