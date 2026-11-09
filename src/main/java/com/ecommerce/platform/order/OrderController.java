package com.ecommerce.platform.order;

import com.ecommerce.platform.common.ApiResponse;
import com.ecommerce.platform.common.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单中心 RESTful 接口控制器。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 分页查询订单列表。
     */
    @GetMapping
    public ApiResponse<PageResult<OrderSummary>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "orderId", required = false) String orderId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        return ApiResponse.ok(orderService.findPage(status, orderId, page, pageSize));
    }

    /**
     * 查询订单详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderSummary>> getById(@PathVariable("id") String id) {
        return orderService.findById(id)
                .map(o -> ResponseEntity.ok(ApiResponse.ok(o)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, "订单不存在: " + id)));
    }

    /**
     * 修改订单状态（例如流转为 shipped、delivered 或 canceled）。
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Void>> updateStatus(
            @PathVariable("id") String id,
            @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        boolean ok = orderService.updateStatus(id, newStatus);
        if (ok) {
            return ResponseEntity.ok(ApiResponse.ok("订单状态流转成功", null));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "订单不存在或更新失败: " + id));
        }
    }
}
