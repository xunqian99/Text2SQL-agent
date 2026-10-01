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
}
