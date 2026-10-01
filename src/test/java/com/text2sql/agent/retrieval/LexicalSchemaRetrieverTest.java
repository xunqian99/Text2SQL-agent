package com.text2sql.agent.retrieval;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索器测试。**不连数据库**，用内存里的最小 schema + 词典。
 *
 * <p>能脱离数据库测试，是这一层被设计成「词典 + 纯计算」的直接收益：
 * 检索是确定性的字符串/图计算，没有 I/O。这意味着
 * <ol>
 *   <li>改词典后能在秒级验证有没有破坏已有召回；</li>
 *   <li>CI 里不需要起 PostgreSQL；</li>
 *   <li>失败时能精确定位到「哪个词没匹配上」，而不是被数据库状态干扰。</li>
 * </ol>
 *
 * <p>这组测试里最重要的是 {@code longestMatchWins}——它固定了
 * 「最大匹配」这个核心行为。如果哪天有人把匹配逻辑改成对每个别名做
 * {@code contains}，那条测试会立刻失败，而这正是我们要防的回归。
 */
class LexicalSchemaRetrieverTest {

    private SchemaCatalog catalog;
    private GlossaryLoader glossaryLoader;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        catalog = mock(SchemaCatalog.class);
        glossaryLoader = mock(GlossaryLoader.class);

        SchemaContext.Table orders = table("orders",
                col("order_id"), col("customer_id"), col("order_status"),
                col("order_purchase_timestamp"));
        SchemaContext.Table customers = table("customers",
                col("customer_id"), col("customer_unique_id"), col("customer_state"));
        SchemaContext.Table orderItems = table("order_items",
                col("order_id"), col("product_id"), col("price"));
        SchemaContext.Table products = table("products",
                col("product_id"), col("product_category_name"));
        SchemaContext.Table refunds = table("refunds",
                col("refund_id"), col("order_id"), col("refund_amount"));

        SchemaContext full = new SchemaContext(
                List.of(orders, customers, orderItems, products, refunds),
                List.of(new SchemaContext.ForeignKey("order_items", "order_id", "orders", "order_id")),
                "",
                "");
        when(catalog.full()).thenReturn(full);
        when(catalog.tablesByName()).thenReturn(Map.of(
                "orders", orders,
                "customers", customers,
                "order_items", orderItems,
                "products", products,
                "refunds", refunds));

        Glossary glossary = new Glossary(
                List.of(
                        new Glossary.Relation("refunds.order_id", "orders.order_id"),
                        new Glossary.Relation("order_items.order_id", "orders.order_id")),
                Map.of(
                        "orders", new Glossary.TableEntry(List.of("订单"), "订单主表", Map.of(
                                "order_status", new Glossary.ColumnEntry(List.of("订单状态"), List.of(
                                        new Glossary.EnumValue("canceled", "已取消"))),
                                "order_purchase_timestamp", new Glossary.ColumnEntry(List.of("下单时间"), List.of()))),
                        "customers", new Glossary.TableEntry(List.of("客户"), "客户表", Map.of(
                                "customer_state", new Glossary.ColumnEntry(List.of("州", "州份"), List.of()))),
                        "order_items", new Glossary.TableEntry(List.of("订单明细"), "订单明细表", Map.of(
                                "price", new Glossary.ColumnEntry(List.of("销售额", "金额"), List.of()))),
                        "products", new Glossary.TableEntry(List.of("商品"), "商品表", Map.of()),
                        "refunds", new Glossary.TableEntry(List.of("退款"), "退款表", Map.of())));
        when(glossaryLoader.get()).thenReturn(glossary);
    }

    private LexicalSchemaRetriever retriever() {
        return new LexicalSchemaRetriever(glossaryLoader, catalog, properties);
    }

    @Test
    @DisplayName("最大匹配：「订单明细」不能被拆成「订单」，否则会误召回 orders")
    void longestMatchWins() {
        RetrievalResult result = retriever().retrieve("订单明细里有多少商品？");

        // 关系扩展会把 orders 带进来（order_items -> orders 是真实关联），
        // 但 order_items 必须排在最前面——它是直接命中，orders 是扩展进来的。
        assertThat(result.tableNames()).contains("order_items");
        assertThat(result.tableNames().getFirst()).isEqualTo("order_items");
        assertThat(result.expandedTables()).contains("orders");
    }

    @Test
    @DisplayName("表别名命中：直接命中排第一")
    void tableAliasHit() {
        RetrievalResult result = retriever().retrieve("一共有多少订单？");

        assertThat(result.tableNames()).contains("orders");
        assertThat(result.tableNames().getFirst()).isEqualTo("orders");
    }

    @Test
    @DisplayName("列别名命中：「销售额」映射到 order_items，而不是靠字面猜")
    void columnAliasHit() {
        RetrievalResult result = retriever().retrieve("销售额最高的商品是哪些？");

        assertThat(result.tableNames()).contains("order_items");
        assertThat(result.evidence().get("order_items"))
                .anyMatch(e -> e.contains("销售额"));
    }

    @Test
    @DisplayName("枚举值命中：「已取消」同时定位到 orders 表，权重高于普通表名")
    void enumLabelHit() {
        RetrievalResult result = retriever().retrieve("已取消的订单有多少？");

        assertThat(result.tableNames()).contains("orders");
        assertThat(result.evidence().get("orders"))
                .anyMatch(e -> e.contains("已取消"));
    }

    @Test
    @DisplayName("关系扩展：命中退款后，沿约定关联带出 orders（本库没有这条外键）")
    void relationExpansionBringsRelatedTable() {
        RetrievalResult result = retriever().retrieve("退款金额是多少？");

        assertThat(result.tableNames()).contains("refunds");
        assertThat(result.expandedTables()).contains("orders");
    }

    @Test
    @DisplayName("完全没命中时回退全量：宁可多给表，也不能筛掉必要的表")
    void fallsBackToFullSchemaWhenNothingMatches() {
        RetrievalResult result = retriever().retrieve("xyzzy 完全无关的问题");

        assertThat(result.fellBack()).isTrue();
        assertThat(result.tableNames()).containsExactlyInAnyOrder(
                "orders", "customers", "order_items", "products", "refunds");
    }

    @Test
    @DisplayName("Top-K 截断：召回表数不超过配置上限")
    void respectsTopKLimit() {
        properties.getRetrieval().setTopK(2);
        RetrievalResult result = retriever().retrieve("订单明细里有多少商品？");

        assertThat(result.tableNames()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("关掉关系扩展：只剩直接命中的表，用于做消融实验")
    void relationHopsCanBeDisabled() {
        properties.getRetrieval().setRelationHops(0);
        RetrievalResult result = retriever().retrieve("退款金额是多少？");

        assertThat(result.tableNames()).contains("refunds");
        assertThat(result.expandedTables()).isEmpty();
    }

    @Test
    @DisplayName("证据留痕：每张被选中的表都能说清为什么被选中")
    void everySelectedTableHasEvidence() {
        RetrievalResult result = retriever().retrieve("已取消订单的销售额是多少？");

        assertThat(result.tableNames()).isNotEmpty();
        for (String table : result.tableNames()) {
            assertThat(result.evidence()).containsKey(table);
        }
    }

    @Test
    @DisplayName("单字别名打折：命中「州」的权重低于双字词，但仍能召回")
    void singleCharAliasStillRecalled() {
        RetrievalResult result = retriever().retrieve("每个州有多少订单？");

        assertThat(result.tableNames()).contains("customers");
        assertThat(result.tableNames()).contains("orders");
    }

    @Test
    @DisplayName("连通性修复：两个不连通分量之间自动补最短桥接表")
    void repairsDisconnectedSelection() {
        // 造一条链 t1 - t2 - t3 - t4，问句只直接命中 t1 和 t4。
        // 把扩展种子限制成 1，t4 那一侧就扩不出去，子图必然断开。
        // 这正是真实场景的缩影：问「大区的商品销售额」不会说「客户」，
        // 而 customers 是 regions 和 orders 之间唯一的通路。
        SchemaContext.Table t1 = table("t1", col("id"));
        SchemaContext.Table t2 = table("t2", col("id"));
        SchemaContext.Table t3 = table("t3", col("id"));
        SchemaContext.Table t4 = table("t4", col("id"));

        SchemaCatalog chainCatalog = mock(SchemaCatalog.class);
        SchemaContext chain = new SchemaContext(List.of(t1, t2, t3, t4), List.of(), "", "");
        when(chainCatalog.full()).thenReturn(chain);
        when(chainCatalog.tablesByName()).thenReturn(Map.of(
                "t1", t1, "t2", t2, "t3", t3, "t4", t4));

        GlossaryLoader chainGlossaryLoader = mock(GlossaryLoader.class);
        when(chainGlossaryLoader.get()).thenReturn(new Glossary(
                List.of(
                        new Glossary.Relation("t1.id", "t2.id"),
                        new Glossary.Relation("t2.id", "t3.id"),
                        new Glossary.Relation("t3.id", "t4.id")),
                Map.of(
                        "t1", new Glossary.TableEntry(List.of("甲"), null, Map.of()),
                        "t2", new Glossary.TableEntry(List.of("乙"), null, Map.of()),
                        "t3", new Glossary.TableEntry(List.of("丙"), null, Map.of()),
                        "t4", new Glossary.TableEntry(List.of("丁"), null, Map.of()))));

        properties.getRetrieval().setExpansionSeedLimit(1);
        LexicalSchemaRetriever chainRetriever =
                new LexicalSchemaRetriever(chainGlossaryLoader, chainCatalog, properties);

        RetrievalResult result = chainRetriever.retrieve("甲和丁");

        // t3 既没被问句命中，也不是 t1 的一跳邻居，只能是图算法补出来的。
        assertThat(result.tableNames()).contains("t1", "t4", "t3");
        assertThat(result.expandedTables()).contains("t3");
        assertThat(result.evidence().get("t3"))
                .anyMatch(e -> e.contains("连通性修复"));
    }

    @Test
    @DisplayName("连通性修复可关闭：关掉后不补桥接表，用于做消融实验")
    void bridgeRepairCanBeDisabled() {
        properties.getRetrieval().setExpansionSeedLimit(1);
        properties.getRetrieval().setBridgeRepairEnabled(false);

        SchemaCatalog chainCatalog = mock(SchemaCatalog.class);
        SchemaContext.Table t1 = table("t1", col("id"));
        SchemaContext.Table t2 = table("t2", col("id"));
        SchemaContext.Table t3 = table("t3", col("id"));
        SchemaContext.Table t4 = table("t4", col("id"));
        when(chainCatalog.full()).thenReturn(
                new SchemaContext(List.of(t1, t2, t3, t4), List.of(), "", ""));
        when(chainCatalog.tablesByName()).thenReturn(Map.of(
                "t1", t1, "t2", t2, "t3", t3, "t4", t4));

        GlossaryLoader chainGlossaryLoader = mock(GlossaryLoader.class);
        when(chainGlossaryLoader.get()).thenReturn(new Glossary(
                List.of(
                        new Glossary.Relation("t1.id", "t2.id"),
                        new Glossary.Relation("t2.id", "t3.id"),
                        new Glossary.Relation("t3.id", "t4.id")),
                Map.of(
                        "t1", new Glossary.TableEntry(List.of("甲"), null, Map.of()),
                        "t2", new Glossary.TableEntry(List.of("乙"), null, Map.of()),
                        "t3", new Glossary.TableEntry(List.of("丙"), null, Map.of()),
                        "t4", new Glossary.TableEntry(List.of("丁"), null, Map.of()))));

        LexicalSchemaRetriever chainRetriever =
                new LexicalSchemaRetriever(chainGlossaryLoader, chainCatalog, properties);

        RetrievalResult result = chainRetriever.retrieve("甲和丁");

        assertThat(result.tableNames()).contains("t1", "t4");
        assertThat(result.tableNames()).doesNotContain("t3");
    }

    private static SchemaContext.Table table(String name, SchemaContext.Column... columns) {
        return new SchemaContext.Table(name, null, List.of(columns));
    }

    private static SchemaContext.Column col(String name) {
        return new SchemaContext.Column(name, "text", true, null);
    }
}
