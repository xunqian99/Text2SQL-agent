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
     * 同一 SchemaCatalog 的完整公开业务 schema，供执行前校验使用。
     *
     * <p>必须保留完整列，且不能包含内部持久化表或其他 schema。
     * 生成、会话复用与检索指标仍使用 provide(question) 的原始检索子集。
     * 空问题检索不能保证完整，未实现时必须明确失败，禁止伪装成全量目录。
     */
    default SchemaContext fullSchema() {
        throw new UnsupportedOperationException("SchemaProvider must expose its shared full business catalog");
    }
}
