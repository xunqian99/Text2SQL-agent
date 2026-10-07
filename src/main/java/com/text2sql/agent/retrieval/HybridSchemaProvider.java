package com.text2sql.agent.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final com.text2sql.agent.config.AgentProperties properties;
    private final com.text2sql.agent.semantic.MetricRegistry metricRegistry;
    private final com.text2sql.agent.fewshot.ExampleSelector fewshotSelector;
    private final ValueRetriever valueRetriever;
    private final ColumnPruner columnPruner;

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
                                com.text2sql.agent.retrieval.glossary.GlossaryLoader glossaryLoader,
                                com.text2sql.agent.config.AgentProperties properties,
                                com.text2sql.agent.semantic.MetricRegistry metricRegistry,
                                com.text2sql.agent.fewshot.ExampleSelector fewshotSelector,
                                ValueRetriever valueRetriever,
                                ColumnPruner columnPruner) {
        this.catalog = catalog;
        this.retriever = retriever;
        this.glossaryLoader = glossaryLoader;
        this.properties = properties;
        this.metricRegistry = metricRegistry;
        this.fewshotSelector = fewshotSelector;
        this.valueRetriever = valueRetriever;
        this.columnPruner = columnPruner;
    }

    public HybridSchemaProvider(SchemaCatalog catalog, LexicalSchemaRetriever retriever,
                                com.text2sql.agent.retrieval.glossary.GlossaryLoader glossaryLoader,
                                com.text2sql.agent.config.AgentProperties properties,
                                com.text2sql.agent.semantic.MetricRegistry metricRegistry,
                                com.text2sql.agent.fewshot.ExampleSelector fewshotSelector,
                                ValueRetriever valueRetriever) {
        this(catalog, retriever, glossaryLoader, properties, metricRegistry, fewshotSelector, valueRetriever, null);
    }

    public HybridSchemaProvider(SchemaCatalog catalog, LexicalSchemaRetriever retriever,
                                com.text2sql.agent.retrieval.glossary.GlossaryLoader glossaryLoader,
                                com.text2sql.agent.config.AgentProperties properties,
                                com.text2sql.agent.semantic.MetricRegistry metricRegistry,
                                com.text2sql.agent.fewshot.ExampleSelector fewshotSelector) {
        this(catalog, retriever, glossaryLoader, properties, metricRegistry, fewshotSelector, null, null);
    }

    public HybridSchemaProvider(SchemaCatalog catalog, LexicalSchemaRetriever retriever,
                                com.text2sql.agent.retrieval.glossary.GlossaryLoader glossaryLoader,
                                com.text2sql.agent.config.AgentProperties properties,
                                com.text2sql.agent.semantic.MetricRegistry metricRegistry) {
        this(catalog, retriever, glossaryLoader, properties, metricRegistry, null, null, null);
    }

    @Override
    public SchemaContext provide(String question) {
        SchemaContext full = catalog.full();
        RetrievalResult result = retriever.retrieve(question);

        if (result.fellBack()) {
            log.info("检索未命中任何词典词，回退全量 schema（{} 张表）", full.tables().size());
            SchemaContext res = full;
            if (valueRetriever != null) {
                var matches = valueRetriever.findMatches(question, Set.copyOf(full.tableNames()));
                res = res.withValueHints(valueRetriever.renderValueHints(matches));
            }
            return res;
        }

        // 按检索给出的顺序重排表，让「最相关的表」出现在 prompt 前面。
        // 长上下文里模型对开头和结尾注意力更强，把高置信度的表放前面
        // 能减少它选错表的概率。这个顺序不是装饰。
        //
        // 命中的指标是一个特殊的检索结果：它不仅提供口径，还声明了自己依赖的表。
        // 例如「动销率」需要 order_items、orders、products；如果词法检索只召回
        // order_items 和 products，指标会因为依赖不完整而被过滤，模型就会退回
        // 自己猜 SQL。把命中的指标依赖表补进上下文，才能让「指标可用性过滤」
        // 真正发挥作用。补入的表仍然受到全库表白名单限制。
        java.util.Set<String> names = new java.util.LinkedHashSet<>(result.tableNames());
        if (properties.getSemantic().isEnabled()) {
            metricRegistry.findMentioned(question).stream()
                    .flatMap(metric -> metric.tables().stream())
                    .filter(name -> full.tableNames().contains(name))
                    .forEach(names::add);
        }
        Map<String, SchemaContext.Table> byName = new LinkedHashMap<>();
        for (SchemaContext.Table table : full.tables()) {
            byName.put(table.name().toLowerCase(Locale.ROOT), table);
        }
        List<SchemaContext.Table> selected = names.stream()
                .map(name -> byName.get(name.toLowerCase(Locale.ROOT)))
                .filter(java.util.Objects::nonNull)
                .toList();

        List<ValueRetriever.ValueMatch> valueMatches = List.of();
        if (valueRetriever != null) {
            valueMatches = valueRetriever.findMatches(question, names);
        }

        List<com.text2sql.agent.semantic.Metric> applicableMetrics = List.of();
        if (properties.getSemantic().isEnabled() && metricRegistry != null) {
            applicableMetrics = metricRegistry.findApplicable(question, names);
        }

        List<SchemaContext.Table> tablesForContext = selected;
        if (properties.getRetrieval().isColumnPruningEnabled() && columnPruner != null) {
            tablesForContext = columnPruner.prune(selected, question, names,
                    full.foreignKeys(), valueMatches, applicableMetrics,
                    properties.getRetrieval().getColumnPruningMinColumns());
        }

        // 提取本次问题的核心实体表（直接命中词典/别名/枚举/指标的表，用于定向规划连表拓扑）
        Set<String> coreTargets = new LinkedHashSet<>();
        if (result.evidence() != null) {
            for (Map.Entry<String, List<String>> entry : result.evidence().entrySet()) {
                if (result.expandedTables() == null || !result.expandedTables().contains(entry.getKey())) {
                    coreTargets.add(entry.getKey().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (valueMatches != null) {
            for (ValueRetriever.ValueMatch vm : valueMatches) {
                coreTargets.add(vm.table().toLowerCase(Locale.ROOT));
            }
        }
        if (applicableMetrics != null) {
            for (com.text2sql.agent.semantic.Metric m : applicableMetrics) {
                if (m.tables() != null) {
                    m.tables().forEach(t -> coreTargets.add(t.toLowerCase(Locale.ROOT)));
                }
            }
        }

        String ddl = renderDdl(full, tablesForContext, names, result, coreTargets);

        log.info("检索完成：{} | DDL {} 字符（全量 {} 字符）",
                result.summary(), ddl.length(), full.ddlText().length());
        if (log.isDebugEnabled()) {
            result.tableNames().forEach(name ->
                    log.debug("  {} <- {}", name, result.evidence().getOrDefault(name, List.of())));
        }

        SchemaContext sub = full.subset(tablesForContext, names, ddl).withMetrics(metricsFor(question, names));
        if (properties.getFewshot().isEnabled() && fewshotSelector != null) {
            var examples = fewshotSelector.select(question, names, properties.getFewshot().getMaxExamples());
            sub = sub.withExamples(examples);
        }
        if (!valueMatches.isEmpty()) {
            String hints = valueRetriever.renderValueHints(valueMatches);
            sub = sub.withValueHints(hints);
            log.info("注入实体与枚举值取值对齐提示：{} 条", valueMatches.size());
        }
        return sub;
    }

    /**
     * 组装本次要注入的业务指标定义。
     *
     * <p><b>为什么按「选中的表」过滤而不是按问题关键词</b>
     *
     * <p>只按关键词命中的话，用户问「复购率」会注入复购率定义，而它的表达式
     * 依赖 {@code customers} 表——如果这次检索只召回了 {@code orders}，
     * 模型照抄表达式就会写出引用不存在表的 SQL，被校验层拦下。
     * 结果从「口径错」变成「跑不通」，更难诊断。
     *
     * <p>所以过滤条件必须是「指标依赖的表都在本次上下文里」。
     * 这也是口径注册表与检索层必须协同的证据：**指标的可用性取决于检索结果**。
     *
     * <p>关闭开关时返回空串，用于做消融实验——对比「有口径注入/无口径注入」
     * 两组的准确率差异。
     */
    private String metricsFor(String question, java.util.Set<String> selectedTables) {
        if (!properties.getSemantic().isEnabled()) {
            return "";
        }
        java.util.List<com.text2sql.agent.semantic.Metric> applicable =
                metricRegistry.findApplicable(question, selectedTables);
        if (applicable.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (com.text2sql.agent.semantic.Metric metric : applicable) {
            sb.append(metric.render()).append("\n\n");
        }
        log.info("注入业务指标：{}", applicable.stream()
                .map(com.text2sql.agent.semantic.Metric::name).toList());
        return sb.toString().stripTrailing();
    }

    /**
     * 渲染交给模型的 DDL 文本，按配置决定要不要附 join 拓扑提示。
     *
     * <p>基于 {@link JoinPathPlanner#planForTargets} 执行定向生成树规划：
     * 若核心实体表不足 2 张，则完全不输出 Join 提示（杜绝单表题过度 Join）；
     * 若涉及 2 张及以上核心实体，仅输出连通这几张表的最短主干拓扑，防止引入无关冗余边。
     */
    private String renderDdl(SchemaContext full, List<SchemaContext.Table> selected,
                             java.util.Set<String> names, RetrievalResult result,
                             Set<String> coreTargets) {
        var config = properties.getRetrieval();
        if (!config.isJoinHintsEnabled()) {
            // 阶段 2 形态：只有真实外键。
            List<SchemaContext.ForeignKey> fks = full.foreignKeys().stream()
                    .filter(fk -> names.contains(fk.fromTable()) && names.contains(fk.toTable()))
                    .toList();
            return SchemaDdlRenderer.render(selected, fks);
        }

        JoinGraph graph = joinGraph(full);
        JoinPathPlanner.Plan plan = JoinPathPlanner.planForTargets(graph, coreTargets, result.tableNames());

        if (plan.isEmpty()) {
            List<SchemaContext.ForeignKey> fks = full.foreignKeys().stream()
                    .filter(fk -> names.contains(fk.fromTable()) && names.contains(fk.toTable()))
                    .toList();
            return SchemaDdlRenderer.render(selected, fks);
        }

        log.info("多表 Join 拓扑约束提示已生效：{} 条主干边，起点 {}，覆盖目标表 {}",
                plan.edges().size(), plan.root(), coreTargets);

        List<JoinGraph.Edge> keptEdges = plan.edges();
        return SchemaDdlRenderer.renderWithJoins(selected, keptEdges, plan,
                config.isJoinListEnabled(), config.isJoinPlanEnabled());
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
