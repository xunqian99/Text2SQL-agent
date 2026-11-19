package com.text2sql.agent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连真实 PostgreSQL 的 schema 读取测试。
 *
 * <p>要钉住的是「内部表不能进入业务 schema」这件事。它看起来是个小过滤，
 * 但影响面很广：
 * <ul>
 *   <li>接口返回的 {@code schemaTableCount} 会从 37 漂到 39，简历上的
 *       「37 张业务表」当场对不上；</li>
 *   <li>prompt 里会多出 {@code semantic_cache}、{@code llm_call_log}
 *       这类无关表，既费 token 又给模型「去查缓存表」的机会；</li>
 *   <li>评估报告的 {@code schemaTableCount} 会随「持久化开关」变化——
 *       一个写路径的开关反向影响了读路径，属于最难查的那类耦合。</li>
 * </ul>
 *
 * <p>没有数据库时自动跳过，不影响 CI。
 */
class DatabaseSchemaReaderIntegrationTest {

    private static final String URL = env("TEXT2SQL_TEST_DB_URL", "jdbc:postgresql://localhost:5432/olist");
    private static final String USER = env("TEXT2SQL_TEST_DB_USER", "text2sql_ro");
    private static final String PASSWORD = env("TEXT2SQL_TEST_DB_PASSWORD", "text2sql_ro");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static boolean databaseAvailable() {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static DatabaseSchemaReader reader() {
        return new DatabaseSchemaReader(new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD)));
    }

    @Test
    @EnabledIf("databaseAvailable")
    void historicalAnchorContractReachesBothProvidersWithoutMetrics() {
        var props = new com.text2sql.agent.config.AgentProperties();
        props.getSemantic().setEnabled(false);
        props.getFewshot().setEnabled(false);
        var catalog = new SchemaCatalog(reader(), props);
        var glossary = new com.text2sql.agent.retrieval.glossary.GlossaryLoader(catalog);
        var fullProvider = new FullSchemaProvider(catalog, null, props);
        var hybridProvider = new HybridSchemaProvider(catalog,
                new LexicalSchemaRetriever(glossary, catalog, props), glossary, props, null, null,
                new ValueRetriever(glossary), new ColumnPruner(glossary));
        for (SchemaProvider provider : List.of(fullProvider, hybridProvider)) {
            var context = provider.provide("最近30天登录的会员数");
            assertThat(context.hasMetrics()).isFalse();
            assertThat(new com.text2sql.agent.generation.PromptTemplate(props)
                    .userPrompt(context, "最近30天登录的会员数"))
                    .contains("上列范围仅属于orders.order_purchase_timestamp",
                            "会员登录用members.last_login_date", "先取全表参照，再筛业务子集",
                            "用户明确限定参照范围", "不伪造参照、不退回今天")
                    .endsWith("最近30天登录的会员数\n");
        }
    }

    @Test
    @EnabledIf("databaseAvailable")
    void compositeKeysReachBothProvidersAndFinalModelInput() {
        var props = new com.text2sql.agent.config.AgentProperties();
        props.getSemantic().setEnabled(false);
        props.getFewshot().setEnabled(false);
        props.getRetrieval().setColumnPruningEnabled(true);
        props.getRetrieval().setColumnPruningMinColumns(1);
        var catalog = new SchemaCatalog(reader(), props);
        var full = catalog.full();
        var reviews = full.tables().stream().filter(t -> t.name().equals("order_reviews")).findFirst().orElseThrow();
        assertThat(reviews.columns()).filteredOn(SchemaContext.Column::primaryKey)
                .extracting(SchemaContext.Column::name).containsExactly("review_id", "order_id");
        var glossary = new com.text2sql.agent.retrieval.glossary.GlossaryLoader(catalog);
        var pruner = new ColumnPruner(glossary);
        var retained = pruner.prune(List.of(reviews), "平均评分", Set.of("order_reviews"),
                List.of(), List.of(), List.of(), 1);
        assertThat(retained.getFirst().columns()).filteredOn(SchemaContext.Column::primaryKey)
                .extracting(SchemaContext.Column::name).containsExactly("review_id", "order_id");
        var fullProvider = new FullSchemaProvider(catalog, null, props);
        var hybridProvider = new HybridSchemaProvider(catalog, new LexicalSchemaRetriever(glossary, catalog, props),
                glossary, props, null, null, new ValueRetriever(glossary), pruner);
        for (SchemaProvider provider : List.of(fullProvider, hybridProvider)) {
            var context = provider.provide("各退款原因对应的平均评分是多少？");
            assertThat(new com.text2sql.agent.generation.PromptTemplate(props).userPrompt(context, "平均评分"))
                    .contains("PRIMARY KEY (review_id, order_id)  -- 组合唯一，单列不保证唯一");
        }
    }

    @Test
    @EnabledIf("databaseAvailable")
    void aReviewIdAloneReallyLosesDistinctOrderReviews() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD));
        var counts = jdbc.queryForMap("SELECT COUNT(*) AS records, COUNT(DISTINCT review_id) AS ids, "
                + "COUNT(DISTINCT (review_id, order_id)) AS tuples FROM order_reviews");
        assertThat(((Number) counts.get("tuples")).longValue()).isEqualTo(((Number) counts.get("records")).longValue());
        assertThat(((Number) counts.get("ids")).longValue()).isLessThan(((Number) counts.get("tuples")).longValue());
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：内部表不能出现在业务 schema 里")
    void excludesInternalTablesFromBusinessSchema() {
        List<String> tableNames = reader().readTables().stream()
                .map(SchemaContext.Table::name)
                .toList();

        assertThat(tableNames)
                .as("缓存/日志/会话表属于本系统自身，不是业务对象")
                .doesNotContain("semantic_cache", "llm_call_log",
                        "conversation_session", "conversation_turn", "schema_chunks");

        assertThat(tableNames)
                .as("真实业务表必须仍在，别把过滤写成了误杀")
                .contains("orders", "customers", "order_items", "sellers", "products");
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：业务表数量稳定为 37，不随持久化开关漂移")
    void businessTableCountIsStable() {
        assertThat(reader().readTables()).as("简历与报告里的 37 张业务表口径").hasSize(37);
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：外键关系里也不能出现内部表")
    void excludesInternalTablesFromForeignKeys() {
        assertThat(reader().readForeignKeys())
                .noneMatch(fk -> fk.fromTable().startsWith("conversation_")
                        || fk.toTable().startsWith("conversation_")
                        || fk.fromTable().equals("semantic_cache")
                        || fk.toTable().equals("semantic_cache"));
    }
}
