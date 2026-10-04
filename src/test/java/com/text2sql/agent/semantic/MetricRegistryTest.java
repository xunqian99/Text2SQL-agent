package com.text2sql.agent.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标注册表测试。**用真实的 metrics.yml**，不是内存里造的假数据。
 *
 * <p>理由：这一层的价值全在「口径写对了没有」。用假数据测只能验证匹配逻辑，
 * 验证不了「有效订单的口径是不是真的排除了 canceled/unavailable」。
 * 而后者才是阶段 4 要解决的问题。
 */
class MetricRegistryTest {

    private static MetricRegistry loaded() {
        MetricRegistry registry = new MetricRegistry();
        registry.load();
        return registry;
    }

    @Test
    @DisplayName("真实指标表能加载，且包含最高频的「有效订单」口径")
    void loadsRealMetricsFile() {
        MetricRegistry registry = loaded();

        assertThat(registry.all()).isNotEmpty();
        assertThat(registry.all()).anySatisfy(m -> {
            assertThat(m.name()).isEqualTo("valid_order");
            // 这条表达式是 17/25 道 T6 题的基础，写错了整层都错。
            assertThat(m.expression()).contains("canceled").contains("unavailable");
        });
    }

    @Test
    @DisplayName("按别名命中：问「复购率」能找到 repeat_rate")
    void findsMetricByAlias() {
        MetricRegistry registry = loaded();

        List<Metric> hits = registry.findMentioned("复购率是多少？");

        assertThat(hits).anySatisfy(m -> assertThat(m.name()).isEqualTo("repeat_rate"));
    }

    @Test
    @DisplayName("命中多个指标：复合问题不丢信息")
    void findsMultipleMetrics() {
        MetricRegistry registry = loaded();

        List<Metric> hits = registry.findMentioned("GMV 和客单价分别是多少？");

        assertThat(hits).extracting(Metric::name)
                .contains("gmv_with_freight", "aov");
    }

    @Test
    @DisplayName("同一术语按上下文选择口径：普通客单价不注入含运费指标")
    void appliesContextConditions() {
        MetricRegistry registry = loaded();

        assertThat(registry.findMentioned("客单价最高的 10 个客户是谁？客单价按订单平均金额算。"))
                .noneMatch(m -> m.name().equals("aov"));
        assertThat(registry.findMentioned("有效订单的订单数、下单人数、客单价分别是多少？"))
                .anyMatch(m -> m.name().equals("aov"));
    }

    @Test
    @DisplayName("承运商配送时长不套用下单到签收指标")
    void excludesDifferentDeliveryScope() {
        MetricRegistry registry = loaded();

        assertThat(registry.findMentioned("每家承运商的平均配送时长是多少天？"))
                .noneMatch(m -> m.name().equals("delivery_days"));
        assertThat(registry.findMentioned("各州的平均配送时长（下单到签收）是多少天？"))
                .anyMatch(m -> m.name().equals("delivery_days"));
    }

    @Test
    @DisplayName("依赖表不可用时过滤掉该指标，避免注入引用不存在表的表达式")
    void filtersByAvailableTables() {
        MetricRegistry registry = loaded();

        // 复购率的表达式依赖 customers（要按 customer_unique_id 聚合）。
        // 只给 orders 时必须把它滤掉，否则模型会照抄一个引用不存在表的 SQL。
        List<Metric> onlyOrders = registry.findApplicable("复购率是多少？", Set.of("orders"));
        assertThat(onlyOrders).noneMatch(m -> m.name().equals("repeat_rate"));

        List<Metric> withCustomers = registry.findApplicable("复购率是多少？",
                Set.of("orders", "customers"));
        assertThat(withCustomers).anySatisfy(m -> assertThat(m.name()).isEqualTo("repeat_rate"));
    }

    @Test
    @DisplayName("表名比较不区分大小写")
    void tableNameComparisonIsCaseInsensitive() {
        MetricRegistry registry = loaded();

        List<Metric> hits = registry.findApplicable("有效订单有多少？", Set.of("ORDERS"));

        assertThat(hits).anySatisfy(m -> assertThat(m.name()).isEqualTo("valid_order"));
    }

    @Test
    @DisplayName("没提到任何指标时返回空，不做无差别注入")
    void returnsEmptyWhenNothingMentioned() {
        MetricRegistry registry = loaded();

        assertThat(registry.findMentioned("2018 年有多少笔订单？")).isEmpty();
    }

    @Test
    @DisplayName("渲染出的文本包含表达式和口径说明，两者缺一不可")
    void rendersExpressionAndNotes() {
        MetricRegistry registry = loaded();
        Metric repeat = registry.all().stream()
                .filter(m -> m.name().equals("repeat_rate"))
                .findFirst()
                .orElseThrow();

        String text = repeat.render();

        assertThat(text).contains("METRIC repeat_rate");
        assertThat(text).contains("表达式:");
        // notes 里的关键提醒必须出现——只给表达式防不住「用错分母」这类错误。
        assertThat(text).contains("customer_unique_id");
    }

    @Test
    @DisplayName("复杂指标会渲染输出约束和查询结构")
    void rendersOutputRuleAndQueryPattern() {
        MetricRegistry registry = loaded();
        Metric retention = registry.all().stream()
                .filter(m -> m.name().equals("retention_rate"))
                .findFirst()
                .orElseThrow();

        String text = retention.render();

        assertThat(text).contains("输出约束（必须遵守）:")
                .contains("推荐查询结构")
                .contains("WITH first_order")
                .contains("不要展开成 cohort_month × activity_month");
    }

    @Test
    @DisplayName("每条指标都有 aliases、expression 和 tables，定义完整")
    void everyMetricIsComplete() {
        MetricRegistry registry = loaded();

        for (Metric metric : registry.all()) {
            assertThat(metric.aliases()).as("%s 缺 aliases", metric.name()).isNotEmpty();
            assertThat(metric.expression()).as("%s 缺 expression", metric.name()).isNotBlank();
            assertThat(metric.tables()).as("%s 缺 tables", metric.name()).isNotEmpty();
            assertThat(metric.notes()).as("%s 缺 notes", metric.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("指标名唯一")
    void metricNamesAreUnique() {
        MetricRegistry registry = loaded();

        assertThat(registry.all()).extracting(Metric::name).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("需要有效订单过滤的指标都带上了 filter（实测 T6 层 17/25 条依赖它）")
    void businessMetricsCarryValidOrderFilter() {
        MetricRegistry registry = loaded();

        // 这几条是实测踩过坑的：第一版漏了 filter，T6-008 动销率算出 100.00
        // 而 gold 是 99.33——因为分子没排除取消/不可用订单的商品。
        for (String name : List.of("gmv_with_freight", "aov", "repeat_rate",
                "sell_through_rate", "member_penetration", "refund_rate", "mau")) {
            Metric metric = registry.all().stream()
                    .filter(m -> m.name().equals(name)).findFirst().orElseThrow();
            assertThat(metric.filter())
                    .as("%s 缺有效订单过滤，会算出偏高的结果", name)
                    .contains("canceled").contains("unavailable");
        }
    }

    @Test
    @DisplayName("不需要过滤的指标不带 filter（避免误加改变口径）")
    void metricsWithoutFilterStayClean() {
        MetricRegistry registry = loaded();

        // 这两条的 gold 里没有 order_status 过滤：
        // T6-006 准时率的分母已经是「已送达订单」，再叠一层反而错。
        // T6-017 推荐转化率算的是全部推荐记录，与订单状态无关。
        for (String name : List.of("on_time_rate", "conversion_rate")) {
            Metric metric = registry.all().stream()
                    .filter(m -> m.name().equals(name)).findFirst().orElseThrow();
            assertThat(metric.filter())
                    .as("%s 不该有有效订单过滤", name)
                    .isNullOrEmpty();
        }
    }

    @Test
    @DisplayName("渲染文本里 filter 与例外说明成对出现")
    void rendersFilterWithExceptionNote() {
        MetricRegistry registry = loaded();
        Metric aov = registry.all().stream()
                .filter(m -> m.name().equals("aov")).findFirst().orElseThrow();

        String text = aov.render();

        assertThat(text).contains("过滤条件（默认附加）:");
        // 例外说明必须跟着出现，否则模型在「仅退款订单」这类题上会重复过滤。
        assertThat(text).contains("例外");
    }

    @Test
    @DisplayName("filter 里引用的表必须在 tables 里声明，否则注入的表达式引用不到该表")
    void filterTablesAreDeclared() {
        MetricRegistry registry = loaded();

        // 【实测踩到的坑】sell_through_rate 的 filter 写了
        // o.order_status NOT IN (...)，但 tables 只声明了
        // [order_items, products]——没声明 orders。
        //
        // 后果：MetricRegistry.findApplicable 按 tables 过滤，认为这个指标
        // 「只要 order_items 和 products 就够了」，于是把它注入到没有 orders
        // 的上下文里；模型照抄 filter，写出引用 orders 的 SQL，
        // 被校验层以 UNKNOWN_TABLE 拦下（T6-008 实测）。
        //
        // 依赖声明是「这个指标能用」的判据，漏了就等于判据错了。
        for (Metric metric : registry.all()) {
            if (metric.filter() == null || metric.filter().isBlank()) {
                continue;
            }
            for (String referenced : List.of("orders", "customers", "order_items",
                    "products", "members", "refunds", "settlements", "support_tickets",
                    "referrals", "search_logs", "order_reviews", "inventory",
                    "inventory_movements", "warehouses", "coupon_usages",
                    "settlement_items", "ticket_categories", "regions")) {
                // 只在 filter 里以「表别名.列」形式出现时才算引用
                boolean used = metric.filter().matches(
                        "(?s).*\\b" + referenced + "\\b.*");
                if (used && metric.filter().contains(".")) {
                    // 只对真正以该表名做前缀的引用做检查
                    boolean qualified = metric.filter().matches(
                            "(?s).*\\b" + referenced + "\\.[a-z_]+.*");
                    if (qualified) {
                        assertThat(metric.tables())
                                .as("%s 的 filter 引用了 %s，但 tables 未声明",
                                        metric.name(), referenced)
                                .contains(referenced);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("普通描述词不得作为指标别名——否则会注入到非业务语义题上")
    void plainDescriptiveWordsAreNotAliases() {
        MetricRegistry registry = loaded();

        // 【实测踩到的坑】sales_without_freight 原来有别名 [销售额, 商品销售额]，
        // 结果命中了 T3-014 / T4-002 / T4-004 这三道普通聚合题，并把它们弄坏：
        // 那三道题的 gold 没有「排除取消/不可用订单」的过滤，而指标定义带了
        // filter，模型照抄后多加了 WHERE，答案就错了（DeepSeek 实测 62% -> 61%）。
        //
        // 判据：**别名只放业务黑话，不放普通描述词。**
        // 黑话（GMV / 复购率 / 动销率 / 会员渗透率）才需要口径定义；
        // 普通问法（销售额 / 金额 / 数量）让模型按字面写就行。
        List<String> plainWords = List.of(
                "销售额", "商品销售额", "商品金额", "金额", "数量", "订单数");

        for (Metric metric : registry.all()) {
            for (String alias : metric.aliases()) {
                assertThat(plainWords)
                        .as("指标 %s 的别名「%s」是普通描述词，会误注入到普通题上",
                                metric.name(), alias)
                        .doesNotContain(alias);
            }
        }
    }
}
