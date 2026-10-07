package com.text2sql.agent.retrieval;

import com.text2sql.agent.retrieval.glossary.Glossary;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ColumnPrunerTest {

    private GlossaryLoader glossaryLoader;
    private ColumnPruner columnPruner;

    @BeforeEach
    void setUp() {
        glossaryLoader = mock(GlossaryLoader.class);

        Glossary.ColumnEntry priceEntry = new Glossary.ColumnEntry(
                List.of("价格", "金额", "单价"),
                List.of()
        );
        Glossary.TableEntry itemsEntry = new Glossary.TableEntry(
                List.of("订单明细"),
                "订单明细表",
                Map.of("price", priceEntry)
        );

        Glossary glossary = new Glossary(List.of(), Map.of("order_items", itemsEntry));
        when(glossaryLoader.get()).thenReturn(glossary);

        columnPruner = new ColumnPruner(glossaryLoader);
    }

    @Test
    @DisplayName("主外键关联列绝对保全，不被剪枝裁掉")
    void preservesJoinKeys() {
        SchemaContext.Table orders = new SchemaContext.Table("orders", "", List.of(
                new SchemaContext.Column("order_id", "text", false, ""),
                new SchemaContext.Column("customer_id", "text", false, ""),
                new SchemaContext.Column("order_status", "text", false, ""),
                new SchemaContext.Column("order_purchase_timestamp", "timestamp", false, ""),
                new SchemaContext.Column("order_approved_at", "timestamp", true, ""),
                new SchemaContext.Column("order_delivered_carrier_date", "timestamp", true, ""),
                new SchemaContext.Column("order_delivered_customer_date", "timestamp", true, ""),
                new SchemaContext.Column("order_estimated_delivery_date", "timestamp", true, "")
        ));

        List<SchemaContext.ForeignKey> fks = List.of(
                new SchemaContext.ForeignKey("orders", "customer_id", "customers", "customer_id")
        );

        List<SchemaContext.Table> pruned = columnPruner.prune(
                List.of(orders),
                "统计各状态订单总数",
                Set.of("orders", "customers"),
                fks,
                List.of(),
                List.of(),
                3
        );

        assertThat(pruned).hasSize(1);
        List<String> colNames = pruned.get(0).columns().stream().map(SchemaContext.Column::name).toList();
        // 主键 order_id 与 外键 customer_id 必须都在
        assertThat(colNames).contains("order_id", "customer_id");
        // 无关的技术时间戳应该被剪掉
        assertThat(colNames).doesNotContain("order_approved_at", "order_delivered_carrier_date", "order_estimated_delivery_date");
    }

    @Test
    @DisplayName("提问命中的语义列与实体对齐涉及列准确保留")
    void preservesMentionedAndEntityColumns() {
        SchemaContext.Table orderItems = new SchemaContext.Table("order_items", "", List.of(
                new SchemaContext.Column("order_id", "text", false, ""),
                new SchemaContext.Column("order_item_id", "int", false, ""),
                new SchemaContext.Column("product_id", "text", false, ""),
                new SchemaContext.Column("seller_id", "text", false, ""),
                new SchemaContext.Column("shipping_limit_date", "timestamp", false, ""),
                new SchemaContext.Column("price", "numeric", false, "商品售价"),
                new SchemaContext.Column("freight_value", "numeric", false, "运费")
        ));

        // 提问“计算商品价格”，命中词典中 price 的别名“价格”
        List<SchemaContext.Table> pruned = columnPruner.prune(
                List.of(orderItems),
                "计算各订单的商品价格",
                Set.of("order_items"),
                List.of(),
                List.of(),
                List.of(),
                3
        );

        List<String> colNames = pruned.get(0).columns().stream().map(SchemaContext.Column::name).toList();
        assertThat(colNames).contains("order_id", "price");
        assertThat(colNames).doesNotContain("shipping_limit_date");
    }

    @Test
    @DisplayName("安全兜底机制：当命中列数不足 minColumns 时，安全补充列以保持表结构语义完整")
    void supplementsColumnsWhenBelowMinColumns() {
        SchemaContext.Table products = new SchemaContext.Table("products", "", List.of(
                new SchemaContext.Column("product_id", "text", false, ""),
                new SchemaContext.Column("product_category_name", "text", true, "品类名称"),
                new SchemaContext.Column("product_name_lenght", "int", true, ""),
                new SchemaContext.Column("product_description_lenght", "int", true, ""),
                new SchemaContext.Column("product_photos_qty", "int", true, ""),
                new SchemaContext.Column("product_weight_g", "numeric", true, "")
        ));

        List<SchemaContext.Table> pruned = columnPruner.prune(
                List.of(products),
                "查询商品信息",
                Set.of("products"),
                List.of(),
                List.of(),
                List.of(),
                3 // 设下限为 3 列
        );

        assertThat(pruned.get(0).columns()).hasSizeGreaterThanOrEqualTo(3);
        // 主键 product_id 与 带注释的 product_category_name 应优先入选
        List<String> colNames = pruned.get(0).columns().stream().map(SchemaContext.Column::name).toList();
        assertThat(colNames).contains("product_id", "product_category_name");
    }

    @Test
    @DisplayName("本身列数少于 minColumns 的小表全量保留，不做裁剪")
    void keepsSmallTablesIntact() {
        SchemaContext.Table regions = new SchemaContext.Table("regions", "", List.of(
                new SchemaContext.Column("region_code", "text", false, ""),
                new SchemaContext.Column("region_name", "text", false, "")
        ));

        List<SchemaContext.Table> pruned = columnPruner.prune(
                List.of(regions),
                "按大区统计",
                Set.of("regions"),
                List.of(),
                List.of(),
                List.of(),
                4
        );

        assertThat(pruned.get(0).columns()).hasSize(2);
    }

    @Test
    @DisplayName("词典中的约定关联列（非物理外键）同样作为关联键保全")
    void preservesGlossarySemanticRelations() {
        Glossary glossaryWithRel = new Glossary(
                List.of(new Glossary.Relation("customers.customer_state", "regions.region_code")),
                Map.of()
        );
        when(glossaryLoader.get()).thenReturn(glossaryWithRel);

        SchemaContext.Table customers = new SchemaContext.Table("customers", "", List.of(
                new SchemaContext.Column("customer_id", "text", false, ""),
                new SchemaContext.Column("customer_unique_id", "text", false, ""),
                new SchemaContext.Column("customer_zip_code_prefix", "int", false, ""),
                new SchemaContext.Column("customer_city", "text", false, ""),
                new SchemaContext.Column("customer_state", "text", false, "")
        ));

        // 提问中完全没有“州”或“state”，但关联了 regions 表
        List<SchemaContext.Table> pruned = columnPruner.prune(
                List.of(customers),
                "按大区统计客户数量",
                Set.of("customers", "regions"),
                List.of(), // 无真实物理外键
                List.of(),
                List.of(),
                2
        );

        List<String> colNames = pruned.get(0).columns().stream().map(SchemaContext.Column::name).toList();
        // customer_state 必须保全！
        assertThat(colNames).contains("customer_state");
    }
}
