package com.ecommerce.platform.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单概要信息对象。
 */
public record OrderSummary(
        String orderId,
        String customerId,
        String orderStatus,
        LocalDateTime purchaseTimestamp,
        LocalDateTime deliveredCustomerDate,
        Integer itemCount,
        BigDecimal totalAmount
) {
}
