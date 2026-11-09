package com.ecommerce.platform.order;

import com.ecommerce.platform.common.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private OrderRepository orderRepository;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = Mockito.mock(OrderRepository.class);
        orderService = new OrderService(orderRepository);
    }

    @Test
    @DisplayName("订单分页查询：转发仓储层")
    void testFindPage() {
        List<OrderSummary> mockList = List.of(
                new OrderSummary("ord-1", "cust-1", "delivered",
                        LocalDateTime.now(), LocalDateTime.now(), 2, new BigDecimal("150.00"))
        );
        when(orderRepository.findPage(eq("delivered"), eq("ord"), eq(1), eq(10)))
                .thenReturn(PageResult.of(mockList, 1, 1, 10));

        PageResult<OrderSummary> result = orderService.findPage("delivered", "ord", 1, 10);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.list().getFirst().orderStatus()).isEqualTo("delivered");
    }

    @Test
    @DisplayName("更新订单状态：非空正常更新")
    void testUpdateStatus() {
        when(orderRepository.updateStatus("ord-1", "shipped")).thenReturn(1);

        boolean ok = orderService.updateStatus("ord-1", "shipped");
        assertThat(ok).isTrue();

        boolean blankFail = orderService.updateStatus("", "shipped");
        assertThat(blankFail).isFalse();
    }
}
