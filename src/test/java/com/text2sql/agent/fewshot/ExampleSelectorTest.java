package com.text2sql.agent.fewshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExampleSelectorTest {

    private ExampleLoader exampleLoader;
    private ExampleSelector selector;

    @BeforeEach
    void setUp() {
        exampleLoader = new ExampleLoader();
        exampleLoader.init();
        selector = new ExampleSelector(exampleLoader);
    }

    @Test
    void shouldLoadExamplesFromYaml() {
        List<Example> examples = exampleLoader.getExamples();
        assertThat(examples).isNotEmpty();
        assertThat(examples.size()).isGreaterThanOrEqualTo(30);

        // 验证每条示例都解析出了真实表名
        for (Example ex : examples) {
            assertThat(ex.id()).isNotBlank();
            assertThat(ex.question()).isNotBlank();
            assertThat(ex.sql()).isNotBlank();
            assertThat(ex.tables()).isNotEmpty();
        }
    }

    @Test
    void shouldSelectRelevantExamplesBasedOnTableOverlap() {
        // 提问关于 orders 和 customers 的多表查询
        var selected = selector.select(
                "统计各省份客户的订单总金额",
                Set.of("orders", "customers", "order_items"),
                3
        );

        assertThat(selected).hasSize(3);
        // 选出的示例应该与这些表有重叠
        for (Example ex : selected) {
            boolean hasOverlap = ex.tables().stream()
                    .anyMatch(t -> Set.of("orders", "customers", "order_items").contains(t));
            assertThat(hasOverlap).isTrue();
        }
    }

    @Test
    void shouldExcludeSelfQuestionToPreventDevLeak() {
        // 使用示例库中已有的问题提问，确保自身不会被作为示例选入
        Example first = exampleLoader.getExamples().get(0);
        var selected = selector.select(
                first.question(),
                first.tables(),
                3
        );

        assertThat(selected).noneMatch(ex -> ex.id().equals(first.id()));
    }
}
