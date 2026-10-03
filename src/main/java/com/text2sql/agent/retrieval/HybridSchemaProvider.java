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
    private final com.text2sql.agent.retrieval.glossary.GlossaryLoader glossaryLoader;

    /**
     * 关系图缓存。
     *
     * <p><b>为什么必须缓存，而不是每次请求现算</b>
     *
     * <p>第一版写成在 {@code provide()} 里直接调 {@code JoinGraph.from(...)}，
     * 功能正确但每来一个问题就把 49 条边重新合并、重新建邻接表。单次代价
     * 只有几十微秒，看起来无所谓，但它有两个真实问题：一是评估跑 100 条
     * 就白做 100 次；二是**让检索延迟数字变脏**——那正是要用来证明
     * 「检索几乎不花时间」的证据。
     *
     * <p>关系图只由 schema 和词典决定，两者在一次运行内都不变，
     * 所以缓存一次即可。与 {@link LexicalSchemaRetriever} 里的缓存同源同寿命。
     */
    private volatile JoinGraph joinGraph;

    public HybridSchemaProvider(SchemaCatalog catalog, LexicalSchemaRetriever retriever,
                                com.text2sql.agent.retrieval.glossary.GlossaryLoader glossaryLoader) {
        this.catalog = catalog;
        this.retriever = retriever;
        this.glossaryLoader = glossaryLoader;
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

        // 阶段 3 的关键改动：join 关系不再只取真实外键，而是走统一的关系图。
        //
        // 阶段 2 这里过滤的是 full.foreignKeys()，只有 13 条外键，于是
        // customers.customer_state -> regions.region_code 这类词典关联
        // 从来没进过 prompt——模型只能猜列名，T4-015 就猜成了 region_name
        // （两列在 regions 里都存在，SQL 能跑，只是结果错，属于静默错误）。
        //
        // 现在改成 JoinGraph.edgesAmong()：外键和词典关联一起给，用 [FK]
        // 标记区分可信度，并明确写出「哪一列连哪一列」。
        List<JoinGraph.Edge> keptEdges = joinGraph(full).edgesAmong(names);

        // 阶段 3 第 2 步：把扁平的边列表规划成一棵连接树。
        //
        // 边列表回答「存在哪些关联」，连接树回答「推荐按什么顺序连」。
        // 多条路径都连通时（T5-001：orders 既能经 customers 到 members，
        // 也能经 coupon_usages 到），只有连接树能表达「优先走外键那条」。
        JoinPathPlanner.Plan plan = JoinPathPlanner.plan(keptEdges, result.tableNames());
        String ddl = SchemaDdlRenderer.renderWithJoins(selected, keptEdges, plan);

        log.info("检索完成：{} | DDL {} 字符（全量 {} 字符）",
                result.summary(), ddl.length(), full.ddlText().length());
        log.info("join 规划：{} 条边，起点 {}，连接 {} 张表",
                plan.edges().size(), plan.root(),
                plan.isEmpty() ? 0 : plan.edges().size() + 1);
        if (log.isDebugEnabled()) {
            result.tableNames().forEach(name ->
                    log.debug("  {} <- {}", name, result.evidence().getOrDefault(name, List.of())));
        }

        return full.subset(names, ddl);
    }

    /** 关系图，惰性构建一次后复用。 */
    private JoinGraph joinGraph(SchemaContext full) {
        JoinGraph local = joinGraph;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (joinGraph == null) {
                joinGraph = JoinGraph.from(full, glossaryLoader.get());
            }
            return joinGraph;
        }
    }
}
