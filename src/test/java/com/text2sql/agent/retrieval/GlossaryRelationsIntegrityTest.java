package com.text2sql.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.text2sql.agent.retrieval.glossary.Glossary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实词典文件的完整性测试。
 *
 * <p><b>它和前面几组测试的区别</b>：前面用的都是内存里构造的假词典，
 * 验证的是「逻辑对不对」；这一组读的是 {@code src/main/resources/schema/glossary.yml}
 * 本身，验证的是「**真实数据里那几条边在不在**」。
 *
 * <p>为什么需要它：阶段 3 修 T4-015 的前提是词典里真的有
 * {@code customers.customer_state -> regions.region_code}。如果那条边
 * 被人误删了，所有逻辑测试仍然全绿，但线上 prompt 又会缺这条 join，
 * 模型重新开始猜列名——**而这类回归不会抛出任何异常**。
 *
 * <p>用 {@code ObjectMapper} 直接解析而不是走 {@code GlossaryLoader}：
 * 后者需要 {@code SchemaCatalog} 做校验，要连数据库；这里只关心
 * 词典文件自身的内容，不该被数据库可用性拖累。
 */
class GlossaryRelationsIntegrityTest {

    private static Glossary glossary;

    @BeforeAll
    static void loadRealGlossary() throws Exception {
        try (InputStream in = GlossaryRelationsIntegrityTest.class
                .getClassLoader().getResourceAsStream("schema/glossary.yml")) {
            assertThat(in).as("找不到 schema/glossary.yml，资源未被正确打包").isNotNull();
            glossary = new ObjectMapper(new YAMLFactory()).readValue(in, Glossary.class);
        }
        assertThat(glossary).isNotNull();
    }

    private static boolean hasRelation(String from, String to) {
        return glossary.relations().stream().anyMatch(r ->
                r.from().equalsIgnoreCase(from) && r.to().equalsIgnoreCase(to));
    }

    @Test
    @DisplayName("T4-015 依赖的边在真实词典里存在：customer_state -> region_code")
    void hasRegionJoinUsedByT4_015() {
        // 这条边没有数据库外键，是纯人工知识。它不在，T4-015 必错。
        assertThat(hasRelation("customers.customer_state", "regions.region_code"))
                .as("缺这条边，模型会把 region_code 猜成 region_name")
                .isTrue();
    }

    @Test
    @DisplayName("T4-017 / T5-001 依赖的边存在：members 与 customers 靠自然人标识打通")
    void hasMemberCustomerJoin() {
        // 订单级 customer_id 与人级 customer_unique_id 是两个不同的粒度，
        // 这条边是「会员等级 × 订单」这类问题的唯一正确通路。
        assertThat(hasRelation("members.customer_unique_id", "customers.customer_unique_id"))
                .as("缺这条边，模型会拿 orders.customer_id 直接连 members，行数会错")
                .isTrue();
    }

    @Test
    @DisplayName("T5-010 依赖的边存在：类目翻译表靠业务值而非主键连接")
    void hasCategoryTranslationJoin() {
        // 这条边连接的是「值」而不是主键，任何外键约束都表达不了它。
        assertThat(hasRelation("product_category_translation.product_category_name",
                "products.product_category_name")).isTrue();
    }

    @Test
    @DisplayName("词典规模没有意外缩水")
    void glossarySizeIsSane() {
        // 不是精确断言，而是防止「整段被误删」这种事故。
        // 阶段 2 收尾时是 36 条，留出余量只做下限保护。
        assertThat(glossary.relations().size()).isGreaterThanOrEqualTo(30);
        assertThat(glossary.tables().size()).isGreaterThanOrEqualTo(35);
    }

    @Test
    @DisplayName("每条关联的两端都写成 表.列 的形式")
    void everyRelationIsQualified() {
        for (Glossary.Relation relation : glossary.relations()) {
            assertThat(relation.from()).contains(".");
            assertThat(relation.to()).contains(".");
        }
    }
}
