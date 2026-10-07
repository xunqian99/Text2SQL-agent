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

class ValueRetrieverTest {

    private GlossaryLoader glossaryLoader;
    private ValueRetriever valueRetriever;

    @BeforeEach
    void setUp() {
        glossaryLoader = mock(GlossaryLoader.class);

        Glossary.ColumnEntry orderStatus = new Glossary.ColumnEntry(
                List.of("订单状态", "状态"),
                List.of(
                        new Glossary.EnumValue("canceled", "已取消"),
                        new Glossary.EnumValue("delivered", "已交付"),
                        new Glossary.EnumValue("shipped", "已发货")
                )
        );

        Glossary.TableEntry ordersEntry = new Glossary.TableEntry(
                List.of("订单", "orders"),
                "订单核心表",
                Map.of("order_status", orderStatus)
        );

        Glossary glossary = new Glossary(List.of(), Map.of("orders", ordersEntry));
        when(glossaryLoader.get()).thenReturn(glossary);

        valueRetriever = new ValueRetriever(glossaryLoader);
    }

    @Test
    @DisplayName("命中词典枚举值时，能精确对齐到表字段与物理枚举值")
    void matchesGlossaryEnumValue() {
        String question = "统计2017年已取消的订单总数";
        List<ValueRetriever.ValueMatch> matches = valueRetriever.findMatches(question, Set.of("orders"));

        assertThat(matches).hasSize(1);
        ValueRetriever.ValueMatch match = matches.get(0);
        assertThat(match.matchedWord()).isEqualTo("已取消");
        assertThat(match.table()).isEqualTo("orders");
        assertThat(match.column()).isEqualTo("order_status");
        assertThat(match.value()).isEqualTo("canceled");
        assertThat(match.condition()).isEqualTo("order_status = 'canceled'");
    }

    @Test
    @DisplayName("命中巴西州名别名时，自动对齐到目标表的 2 位 ISO 缩写代码")
    void matchesStateCode() {
        String question = "查询圣保罗州的客户平均消费";
        List<ValueRetriever.ValueMatch> matches = valueRetriever.findMatches(question, Set.of("customers"));

        assertThat(matches).isNotEmpty();
        ValueRetriever.ValueMatch match = matches.stream()
                .filter(m -> "customer_state".equals(m.column()))
                .findFirst()
                .orElse(null);
        assertThat(match).isNotNull();
        assertThat(match.value()).isEqualTo("SP");
        assertThat(match.condition()).isEqualTo("customer_state = 'SP'");
    }

    @Test
    @DisplayName("表级隔离：目标表集合中不存在该表时，不注入无关实体的提示")
    void filtersOutNonTargetTables() {
        String question = "查询圣保罗州的已取消订单";
        // 目标表只给了 orders，没有 customers
        List<ValueRetriever.ValueMatch> matches = valueRetriever.findMatches(question, Set.of("orders"));

        // orders 应该有 order_status = 'canceled'，但不应包含 customers.customer_state
        assertThat(matches).anyMatch(m -> "orders".equals(m.table()) && "canceled".equals(m.value()));
        assertThat(matches).noneMatch(m -> "customers".equals(m.table()));
    }

    @Test
    @DisplayName("渲染对齐提示字符串：格式清晰规范")
    void rendersValueHints() {
        String question = "查询圣保罗州已取消的订单";
        List<ValueRetriever.ValueMatch> matches = valueRetriever.findMatches(question, Set.of("orders", "customers"));
        String hints = valueRetriever.renderValueHints(matches);

        assertThat(hints).isNotNull();
        assertThat(hints).contains("order_status = 'canceled'");
        assertThat(hints).contains("customer_state = 'SP'");
    }

    @Test
    @DisplayName("无实体命中时返回空，renderValueHints 返回 null")
    void returnsNullWhenNoMatches() {
        String question = "查询商品总销售额";
        List<ValueRetriever.ValueMatch> matches = valueRetriever.findMatches(question, Set.of("order_items"));

        assertThat(matches).isEmpty();
        assertThat(valueRetriever.renderValueHints(matches)).isNull();
    }
}
