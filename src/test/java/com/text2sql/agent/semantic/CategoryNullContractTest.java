package com.text2sql.agent.semantic;

import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only VALUES CTE fixtures and an independent Java set oracle; never changes business data. */
@EnabledIf("databaseAvailable")
class CategoryNullContractTest {
    record Product(String id, String category) { }
    record Item(String order, String product) { }
    record Scenario(String name, List<Product> products, List<Item> items, boolean includeUnknown) {
        @Override public String toString() { return name + (includeUnknown ? "-include-unknown" : "-default"); }
    }
    static Stream<Scenario> scenarios() {
        var mixedProducts = List.of(new Product("p1", "books"), new Product("p2", "books"), new Product("p3", null),
                new Product("p4", "toys"), new Product("p5", ""), new Product("p6", "未知"), new Product("p7", "unbought"));
        var mixedItems = List.of(new Item("a", "p1"), new Item("a", "p2"), new Item("a", "p3"),
                new Item("b", "p1"), new Item("b", "p4"), new Item("c", "p3"), new Item("d", "p5"), new Item("e", "p6"), new Item("f", "missing"));
        return Stream.of(false, true).flatMap(include -> Stream.of(
                new Scenario("fanout-cross-category-null-empty-literal-unknown", mixedProducts, mixedItems, include),
                new Scenario("all-null", List.of(new Product("p", null)), List.of(new Item("a", "p"), new Item("a", "p")), include),
                new Scenario("no-items", mixedProducts, List.of(), include),
                new Scenario("no-products", List.of(), mixedItems, include)));
    }

    @ParameterizedTest @MethodSource("scenarios")
    void configuredDefaultAndExplicitPreservationMatchIndependentSetOracle(Scenario scenario) throws Exception {
        var policy = DimensionPolicyTest.registry().dimensionPolicies().getFirst();
        String sql = cte(scenario) + " SELECT products.product_category_name, COUNT(DISTINCT order_items.order_id)"
                + " FROM order_items JOIN products ON products.product_id=order_items.product_id"
                + (scenario.includeUnknown() ? "" : " WHERE " + policy.defaultFilter()) + " GROUP BY 1";
        var actual = new HashMap<String, Long>();
        try (var connection = DriverManager.getConnection(MemberPenetrationContractTest.url(), MemberPenetrationContractTest.user(), MemberPenetrationContractTest.password())) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery(sql)) {
                    while (rows.next()) actual.put(rows.getString(1), rows.getLong(2));
                }
            }
        }
        assertThat(actual).isEqualTo(oracle(scenario));
        if (scenario.name().startsWith("fanout")) {
            assertThat(actual).containsEntry("books", 2L).containsEntry("toys", 1L).containsEntry("", 1L).containsEntry("未知", 1L);
            if (scenario.includeUnknown()) assertThat(actual).containsEntry(null, 2L);
            else assertThat(actual).doesNotContainKey(null);
        }
    }

    static Map<String, Long> oracle(Scenario scenario) {
        var products = new HashMap<String, Product>(); scenario.products().forEach(p -> products.put(p.id(), p));
        var ordersByCategory = new LinkedHashMap<String, HashSet<String>>();
        for (var item : scenario.items()) {
            Product product = products.get(item.product());
            if (product == null || (product.category() == null && !scenario.includeUnknown())) continue;
            ordersByCategory.computeIfAbsent(product.category(), k -> new HashSet<>()).add(item.order());
        }
        var result = new HashMap<String, Long>(); ordersByCategory.forEach((category, orders) -> result.put(category, (long) orders.size()));
        return result;
    }
    static String cte(Scenario scenario) {
        return "WITH products(product_id,product_category_name) AS (" + rows(scenario.products().stream().map(p -> "(" + value(p.id()) + "," + value(p.category()) + ")").toList())
                + "), order_items(order_id,product_id) AS (" + rows(scenario.items().stream().map(i -> "(" + value(i.order()) + "," + value(i.product()) + ")").toList()) + ")";
    }
    private static String rows(List<String> rows) { return rows.isEmpty() ? "SELECT NULL::text,NULL::text WHERE FALSE" : "VALUES " + String.join(",", rows); }
    private static String value(String s) { return s == null ? "NULL::text" : "'" + s.replace("'", "''") + "'::text"; }
    static boolean databaseAvailable() { return MemberPenetrationContractTest.databaseAvailable(); }
}
