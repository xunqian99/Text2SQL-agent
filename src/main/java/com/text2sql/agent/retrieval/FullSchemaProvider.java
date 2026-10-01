package com.text2sql.agent.retrieval;

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

    public FullSchemaProvider(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public SchemaContext provide(String question) {
        // 参数 question 在阶段 1 被忽略——整库塞入，没有筛选，自然也不需要问题。
        return catalog.full();
    }
}
