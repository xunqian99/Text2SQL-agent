package com.text2sql.agent.retrieval;

/**
 * 检索层对外的唯一接口：给一个问题，返回要用到的 schema 上下文。
 *
 * <p>注意方法签名里带着 {@code question}，但阶段 1 的实现根本不看它。
 * 这是刻意留下的扩展缝：阶段 2 要按问题召回相关表，届时只换实现类，
 * 编排层与生成层一行都不用改。接口先定好，实现可以先笨。
 */
public interface SchemaProvider {

    SchemaContext provide(String question);

    /**
     * 全量 schema，供 dry-run 的 trusted gold SQL 校验使用。
     *
     * <p>dry-run 的目的是验证评估器/校验器/执行器自身没坏，输入是 gold SQL，
     * 不是模型生成结果。此时不应该用检索子集去卡 gold 里那些「不产出列、
     * 只起过滤作用」的 join；检索召回率由 retrieval 指标单独报告。
     */
    default SchemaContext fullSchema() {
        return provide("");
    }
}
