package com.text2sql.agent.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 阶段 2 的 SchemaProvider：按问题召回相关表，而不是整库塞入。
 *
 * <p>对应 ROADMAP 阶段 2 第 1、2、4 条（schema 切块、检索、Top-K）。
 * 上游是编排层传来的中文问题，下游把**裁剪过的** {@link SchemaContext}
 * 交给生成层；生成层和编排层一行都不用改——这正是阶段 1 留
 * {@code SchemaProvider.provide(question)} 这个缝的价值。
 *
 * <p><b>这个类为什么叫 Hybrid，但当前只有词法一路</b>
 *
 * <p>「混合」指最终的形态是词法 + 向量两路融合。当前先落词法一路，
 * 因为它的**依赖为零**：不需要 embedding 服务、不需要 pgvector、
 * 不需要联网。这样能先拿到一个干净的对照数字——「纯词法检索的表召回率
 * 是多少」。有了这个基线，之后加向量路才能说清「向量路额外贡献了多少」。
 * 如果一开始就把两路一起上，就没有这个增量数字了。
 *
 * <p>这正是阶段 1「先做笨实现拿 baseline」的同一个方法论，在阶段 2 内部
 * 又用了一次。
 *
 * <p><b>为什么裁剪的是表，而不是列</b>
 *
 * <p>ROADMAP 写的是「Top-K 表与 Top-N 列」。当前只做表级裁剪，理由：
 * 表选错会让 SQL 完全跑不通（错误可见），列选错则是静默的语义错误
 * （SQL 能跑，答案不对）。先解决可见的那一类，用评估数字证明表级检索
 * 有效，再上列级。而且列级裁剪有个真实风险——列名跨表重名严重
 * （{@code order_id} 在 6 张表里都有），裁错的代价比裁表更高。
 */
public class HybridSchemaProvider implements SchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(HybridSchemaProvider.class);

    private final SchemaCatalog catalog;
    private final LexicalSchemaRetriever retriever;

    public HybridSchemaProvider(SchemaCatalog catalog, LexicalSchemaRetriever retriever) {
        this.catalog = catalog;
        this.retriever = retriever;
    }

    @Override
    public SchemaContext provide(String question) {
        SchemaContext full = catalog.full();
        RetrievalResult result = retriever.retrieve(question);

        if (result.fellBack()) {
            log.info("检索未命中任何词典词，回退全量 schema（{} 张表）", full.tables().size());
            return full;
        }

        // 按检索给出的顺序重排表，让「最相关的表」出现在 prompt 前面。
        // 长上下文里模型对开头和结尾注意力更强，把高置信度的表放前面
        // 能减少它选错表的概率。这个顺序不是装饰。
        Map<String, SchemaContext.Table> byName = new LinkedHashMap<>();
        for (SchemaContext.Table table : full.tables()) {
            byName.put(table.name().toLowerCase(Locale.ROOT), table);
        }
        List<SchemaContext.Table> selected = result.tableNames().stream()
                .map(name -> byName.get(name.toLowerCase(Locale.ROOT)))
                .filter(java.util.Objects::nonNull)
                .toList();

        java.util.Set<String> names = new java.util.LinkedHashSet<>(result.tableNames());
        List<SchemaContext.ForeignKey> keptEdges = full.foreignKeys().stream()
                .filter(fk -> names.contains(fk.fromTable()) && names.contains(fk.toTable()))
                .toList();
        String ddl = SchemaDdlRenderer.render(selected, keptEdges);

        log.info("检索完成：{} | DDL {} 字符（全量 {} 字符）",
                result.summary(), ddl.length(), full.ddlText().length());
        if (log.isDebugEnabled()) {
            result.tableNames().forEach(name ->
                    log.debug("  {} <- {}", name, result.evidence().getOrDefault(name, List.of())));
        }

        return full.subset(names, ddl);
    }
}
