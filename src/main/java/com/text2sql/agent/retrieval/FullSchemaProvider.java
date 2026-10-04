package com.text2sql.agent.retrieval;

import com.text2sql.agent.semantic.Metric;
import com.text2sql.agent.semantic.MetricRegistry;

import java.util.List;
import java.util.Set;

/**
 * 阶段 1 的 SchemaProvider：把整库 schema 直接塞进上下文，**不做任何筛选**。
 *
 * <p>这是有意为之的「笨实现」，它有两个作用：
 *
 * <ol>
 *   <li>给出一个诚实的 baseline。不做检索、不做 join 规划、不做语义层，
 *       模型只靠全量 schema 生成 SQL，准确率是多少就记多少。</li>
 *   <li>提供阶段 2 的对比对象。阶段 2 上线检索后，如果准确率上升且
 *       prompt token 下降，才能证明检索真的在起作用。如果一开始就上检索，
 *       就没有参照点，「提升」也就无从谈起。</li>
 * </ol>
 *
 * <p>代价是显而易见的：37 张表、201 列全塞进去，光 schema 就 4–5k token，
 * 而单表问题实际只需要其中一张表。噪声会稀释模型的注意力，这正是阶段 2 要解决的问题。
 *
 * <p>阶段 2 之后它仍然保留，而且是**默认实现**：它和检索版共用同一份
 * {@link SchemaCatalog}，因此两者的差异只剩「选哪些表」这一个变量。
 * 想跑检索版要显式打开 {@code agent.retrieval.enabled=true}——
 * 让优化必须被显式开启，baseline 才是随时可复现的。
 */
public class FullSchemaProvider implements SchemaProvider {

    private final SchemaCatalog catalog;
    private final MetricRegistry metricRegistry;
    private final com.text2sql.agent.config.AgentProperties properties;

    public FullSchemaProvider(SchemaCatalog catalog, MetricRegistry metricRegistry,
                              com.text2sql.agent.config.AgentProperties properties) {
        this.catalog = catalog;
        this.metricRegistry = metricRegistry;
        this.properties = properties;
    }

    @Override
    public SchemaContext provide(String question) {
        // 阶段 1 的形态：整库塞入，不做表筛选。
        //
        // 但阶段 4 起 question 不再被完全忽略——语义层要按问题注入指标定义。
        // 全量 schema 下所有表都可用，所以指标不存在「依赖表没召回」的问题，
        // 这是它和检索版的关键差别。
        SchemaContext full = catalog.full();
        if (!properties.getSemantic().isEnabled()) {
            return full;
        }
        List<Metric> applicable = metricRegistry.findApplicable(question,
                Set.copyOf(full.tableNames()));
        if (applicable.isEmpty()) {
            return full;
        }
        StringBuilder sb = new StringBuilder();
        for (Metric metric : applicable) {
            sb.append(metric.render()).append("\n\n");
        }
        return full.withMetrics(sb.toString().stripTrailing());
    }
}
