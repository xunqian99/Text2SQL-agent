package com.ecommerce.platform.order;

import com.ecommerce.platform.common.PageResult;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public PageResult<OrderSummary> findPage(String status, String orderId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.clamp(pageSize, 1, 100);
        return orderRepository.findPage(status, orderId, safePage, safeSize);
    }

    public Optional<OrderSummary> findById(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        return orderRepository.findById(orderId.trim());
    }

    public boolean updateStatus(String orderId, String newStatus) {
        if (orderId == null || orderId.isBlank() || newStatus == null || newStatus.isBlank()) {
            return false;
        }
        return orderRepository.updateStatus(orderId.trim(), newStatus.trim()) > 0;
    }
}
