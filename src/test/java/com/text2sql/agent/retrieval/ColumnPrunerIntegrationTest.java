package com.text2sql.agent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列剪枝在真实 schema 上的行为测试。
 *
 * <p>要钉住的核心点：**主键不能靠列名猜**。
 * {@code support_tickets} 的主键叫 {@code ticket_id}——按「表名 + _id」的
 * 启发式永远猜不到，于是它会被当成普通列裁掉，那张表就彻底失去 join 能力
 * （连不到 orders、members）。数据库的 {@code information_schema} 明明知道
 * 真实主键是什么，没有理由让程序去猜。
 *
 * <p>没有数据库时自动跳过。
 */
class ColumnPrunerIntegrationTest {

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

    private static List<SchemaContext.Table> realTables() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD));
        return new DatabaseSchemaReader(jdbc).readTables();
    }

    private static List<String> columnNames(SchemaContext.Table table) {
        return table.columns().stream().map(SchemaContext.Column::name).toList();
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：主键用元数据判定，support_tickets.ticket_id 不能被裁掉")
    void keepsPrimaryKeyWithNonObviousName() {
        List<SchemaContext.Table> tables = realTables();
        SchemaContext.Table supportTickets = tables.stream()
                .filter(t -> t.name().equals("support_tickets"))
                .findFirst()
                .orElseThrow();

        // 先确认元数据确实带上了主键标记
        assertThat(supportTickets.columns())
                .filteredOn(SchemaContext.Column::primaryKey)
                .extracting(SchemaContext.Column::name)
                .contains("ticket_id");

        var pruner = new ColumnPruner(null);
        List<SchemaContext.Table> pruned = pruner.prune(
                List.of(supportTickets),
                "客服工单总数是多少？",
                java.util.Set.of("support_tickets"),
                List.of(),
                List.of(),
                List.of(),
                6);

        SchemaContext.Table after = pruned.get(0);
        assertThat(columnNames(after))
                .as("主键被裁掉意味着这张表再也 join 不出去")
                .contains("ticket_id");
    }

    @Test
    @EnabledIf("databaseAvailable")
    @DisplayName("真实库：外键列要在对端表被选中时保留（桥接表也要能连）")
    void keepsForeignKeyTowardsSelectedTables() {
        List<SchemaContext.Table> tables = realTables();
        SchemaContext.Table orderItems = tables.stream()
                .filter(t -> t.name().equals("order_items"))
                .findFirst()
                .orElseThrow();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD));
        var foreignKeys = new DatabaseSchemaReader(jdbc).readForeignKeys();

        var pruner = new ColumnPruner(null);
        // 目标表只给 orders，但 order_items 与 products 也在本次选中的表里，
        // 它的外键是连 orders 与 products 的唯一通道，必须保留。
        List<SchemaContext.Table> selected = tables.stream()
                .filter(t -> List.of("order_items", "orders", "products").contains(t.name()))
                .toList();
        List<SchemaContext.Table> pruned = pruner.prune(
                selected, "每个商品卖出了多少件？", java.util.Set.of("orders"), foreignKeys, List.of(), List.of(), 6);

        SchemaContext.Table after = pruned.stream()
                .filter(t -> t.name().equals("order_items"))
                .findFirst()
                .orElseThrow();
        assertThat(columnNames(after)).contains("order_id", "product_id");
    }
}
