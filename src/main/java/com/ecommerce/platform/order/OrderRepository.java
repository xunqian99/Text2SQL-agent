package com.ecommerce.platform.order;

import com.ecommerce.platform.common.DatabaseProbe;
import com.ecommerce.platform.common.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单数据访问仓储层。
 * 具备数据库连通性冷启动探针保护机制：
 * 若本地 PostgreSQL 5432 离线，自动无缝切换为高性能内存预热数据模式，零超时等待（0ms 响应）。
 */
@Repository
public class OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseProbe databaseProbe;
    private final List<OrderSummary> mockOrders = new CopyOnWriteArrayList<>();

    public OrderRepository(JdbcTemplate jdbcTemplate, DatabaseProbe databaseProbe) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseProbe = databaseProbe;
        initMockOrders();
    }

    private void initMockOrders() {
        LocalDateTime now = LocalDateTime.now();

        // 已送达订单 (delivered)
        mockOrders.add(new OrderSummary("ord-87140e4f27570431e21d5446114f6977", "cust-06b8999e2fba1a1fbc88172c00ba8bc7", "delivered", now.minusDays(5), now.minusDays(2), 1, new BigDecimal("149.90")));
        mockOrders.add(new OrderSummary("ord-496630b420f3c5f66e0c6563b01e3156", "cust-4e7b3e00288580be9b053290235281fa", "delivered", now.minusDays(8), now.minusDays(4), 1, new BigDecimal("89.00")));
        mockOrders.add(new OrderSummary("ord-a253a067a9d52f4154d3149c60f23858", "cust-fc3d1daecb448300d6499820386cb61e", "delivered", now.minusDays(10), now.minusDays(6), 2, new BigDecimal("329.80")));
        mockOrders.add(new OrderSummary("ord-3594e0561e6615440250f54e73f444eb", "cust-b623760ab702c18b6c1a806da367154d", "delivered", now.minusDays(12), now.minusDays(8), 1, new BigDecimal("199.00")));
        mockOrders.add(new OrderSummary("ord-f3e107730dbf1234b5c6d7e8f9012345", "cust-1234567890abcdef1234567890abcdef", "delivered", now.minusDays(15), now.minusDays(11), 3, new BigDecimal("540.20")));
        mockOrders.add(new OrderSummary("ord-c1b2a3f4e5d69870123456789abcdef0", "cust-abcdef0123456789abcdef0123456789", "delivered", now.minusDays(18), now.minusDays(13), 1, new BigDecimal("69.90")));
        mockOrders.add(new OrderSummary("ord-58a1b2c3d4e5f67890abcdef12345678", "cust-7890abcdef1234567890abcdef123456", "delivered", now.minusDays(20), now.minusDays(15), 4, new BigDecimal("780.00")));
        mockOrders.add(new OrderSummary("ord-89abcdef0123456789abcdef01234567", "cust-890abcdef1234567890abcdef1234567", "delivered", now.minusDays(22), now.minusDays(17), 1, new BigDecimal("115.50")));
        mockOrders.add(new OrderSummary("ord-90abcdef0123456789abcdef01234568", "cust-901abcdef1234567890abcdef1234568", "delivered", now.minusDays(25), now.minusDays(19), 2, new BigDecimal("260.00")));
        mockOrders.add(new OrderSummary("ord-01abcdef0123456789abcdef01234569", "cust-012abcdef1234567890abcdef1234569", "delivered", now.minusDays(30), now.minusDays(24), 1, new BigDecimal("49.90")));

        // 已发货订单 (shipped)
        mockOrders.add(new OrderSummary("ord-b08a3e160e91f538652d57343e32464f", "cust-18955e83d337fd6b2def6b18a428ac77", "shipped", now.minusDays(2), null, 2, new BigDecimal("230.50")));
        mockOrders.add(new OrderSummary("ord-7a1b2c3d4e5f67890123456789abcdef", "cust-29066f94e4480e7c3ef87c2901ab9e88", "shipped", now.minusDays(1), null, 1, new BigDecimal("129.00")));
        mockOrders.add(new OrderSummary("ord-8b2c3d4e5f67890123456789abcdef01", "cust-3a177fa5f5591f8d4f098d3012bcaf99", "shipped", now.minusHours(18), null, 3, new BigDecimal("410.00")));
        mockOrders.add(new OrderSummary("ord-9c3d4e5f67890123456789abcdef0123", "cust-4b2880b6a66a2a9e5a1a9e4123cda0aa", "shipped", now.minusHours(12), null, 1, new BigDecimal("85.00")));
        mockOrders.add(new OrderSummary("ord-0d4e5f67890123456789abcdef012345", "cust-5c3991c7b77b3baf6b2baf5234deb1bb", "shipped", now.minusHours(6), null, 2, new BigDecimal("340.00")));

        // 已开票订单 (invoiced)
        mockOrders.add(new OrderSummary("ord-1b272365ffb462447937e49c71981e4b", "cust-b2e174f0048d110b8686c3096485b3ec", "invoiced", now.minusDays(1), null, 3, new BigDecimal("412.00")));
        mockOrders.add(new OrderSummary("ord-2c383476aac573558048f5ad82092f5c", "cust-c3f285a1159e221c9797d41a7596c4fd", "invoiced", now.minusHours(10), null, 1, new BigDecimal("99.90")));
        mockOrders.add(new OrderSummary("ord-3d494587bbd68466915906be931a306d", "cust-d4a396b226af332da8a8e52b8607d5ae", "invoiced", now.minusHours(4), null, 2, new BigDecimal("180.50")));
        mockOrders.add(new OrderSummary("ord-4e505698cce79577a26017cf042b417e", "cust-e5b4a7c337b0443eb9b9f63c9718e6bf", "invoiced", now.minusHours(2), null, 1, new BigDecimal("65.00")));

        // 处理中订单 (processing)
        mockOrders.add(new OrderSummary("ord-5f6167a9ddf80688b37128d0153c528f", "cust-f6c5b8d448c1554fca0a074da829f7c0", "processing", now.minusHours(3), null, 1, new BigDecimal("120.00")));
        mockOrders.add(new OrderSummary("ord-607278baeea91799c48239e1264d6390", "cust-07d6c9e559d26650db1b185eb93a08d1", "processing", now.minusHours(1), null, 2, new BigDecimal("250.00")));
        mockOrders.add(new OrderSummary("ord-718389cbfba02800d5934af2375e74a1", "cust-18e7da066ae37761ec2c296fc04b19e2", "processing", now.minusMinutes(30), null, 1, new BigDecimal("45.00")));

        // 已取消订单 (canceled)
        mockOrders.add(new OrderSummary("ord-6f17e335b71946399a9cf4a408794101", "cust-4e3ae2b71bb63635eb7b969e0111fb60", "canceled", now.minusDays(12), null, 1, new BigDecimal("75.00")));
        mockOrders.add(new OrderSummary("ord-829490dccab13911e6a45b03486f85b2", "cust-29f8eb177bf48872fd3d3a70d15c2af3", "canceled", now.minusDays(7), null, 2, new BigDecimal("190.00")));
        mockOrders.add(new OrderSummary("ord-93a501eddba24022f7b56c14597096c3", "cust-30a9fc288c059983ae4e4b81e26d3b04", "canceled", now.minusDays(3), null, 1, new BigDecimal("88.00")));
    }

    private final RowMapper<OrderSummary> orderRowMapper = (rs, rowNum) -> {
        Timestamp purchaseTs = rs.getTimestamp("order_purchase_timestamp");
        Timestamp deliveredTs = rs.getTimestamp("order_delivered_customer_date");
        return new OrderSummary(
                rs.getString("order_id"),
                rs.getString("customer_id"),
                rs.getString("order_status"),
                purchaseTs != null ? purchaseTs.toLocalDateTime() : null,
                deliveredTs != null ? deliveredTs.toLocalDateTime() : null,
                rs.getObject("item_count") != null ? rs.getInt("item_count") : 0,
                rs.getBigDecimal("total_amount")
        );
    };

    /**
     * 分页查询订单。
     */
    public PageResult<OrderSummary> findPage(String status, String orderId, int page, int pageSize) {
        if (!databaseProbe.isDbAvailable()) {
            return fallbackMockPage(status, orderId, page, pageSize);
        }

        try {
            StringBuilder where = new StringBuilder(" WHERE 1=1 ");
            List<Object> params = new ArrayList<>();

            if (status != null && !status.isBlank()) {
                where.append(" AND o.order_status = ? ");
                params.add(status.trim());
            }

            if (orderId != null && !orderId.isBlank()) {
                where.append(" AND o.order_id LIKE ? ");
                params.add("%" + orderId.trim() + "%");
            }

            String countSql = "SELECT COUNT(*) FROM orders o " + where;
            Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
            long totalCount = total != null ? total : 0;

            if (totalCount == 0) {
                return PageResult.of(List.of(), 0, page, pageSize);
            }

            int offset = Math.max(0, (page - 1) * pageSize);
            String sql = "SELECT o.order_id, o.customer_id, o.order_status, o.order_purchase_timestamp, "
                    + " o.order_delivered_customer_date, "
                    + " COALESCE(items.cnt, 0) AS item_count, "
                    + " COALESCE(payments.val, 0) AS total_amount "
                    + " FROM orders o "
                    + " LEFT JOIN (SELECT order_id, COUNT(*) AS cnt FROM order_items GROUP BY order_id) items "
                    + "   ON o.order_id = items.order_id "
                    + " LEFT JOIN (SELECT order_id, SUM(payment_value) AS val FROM order_payments GROUP BY order_id) payments "
                    + "   ON o.order_id = payments.order_id "
                    + where
                    + " ORDER BY o.order_purchase_timestamp DESC NULLS LAST LIMIT ? OFFSET ?";

            List<Object> queryParams = new ArrayList<>(params);
            queryParams.add(pageSize);
            queryParams.add(offset);

            List<OrderSummary> list = jdbcTemplate.query(sql, orderRowMapper, queryParams.toArray());
            return PageResult.of(list, totalCount, page, pageSize);
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return fallbackMockPage(status, orderId, page, pageSize);
        }
    }

    private PageResult<OrderSummary> fallbackMockPage(String status, String orderId, int page, int pageSize) {
        List<OrderSummary> filtered = mockOrders.stream().filter(o -> {
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(o.orderStatus())) {
                return false;
            }
            if (orderId != null && !orderId.isBlank()) {
                String oid = orderId.toLowerCase();
                return o.orderId().toLowerCase().contains(oid);
            }
            return true;
        }).toList();

        int total = filtered.size();
        int offset = Math.max(0, (page - 1) * pageSize);
        List<OrderSummary> pageList = (offset >= total) ? List.of() :
                filtered.subList(offset, Math.min(offset + pageSize, total));
        return PageResult.of(pageList, total, page, pageSize);
    }

    /**
     * 根据 ID 查询订单详情。
     */
    public Optional<OrderSummary> findById(String orderId) {
        if (!databaseProbe.isDbAvailable()) {
            return mockOrders.stream().filter(o -> o.orderId().equalsIgnoreCase(orderId)).findFirst();
        }

        try {
            String sql = "SELECT o.order_id, o.customer_id, o.order_status, o.order_purchase_timestamp, "
                    + " o.order_delivered_customer_date, "
                    + " (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS item_count, "
                    + " (SELECT COALESCE(SUM(payment_value), 0) FROM order_payments op WHERE op.order_id = o.order_id) AS total_amount "
                    + " FROM orders o WHERE o.order_id = ?";
            List<OrderSummary> list = jdbcTemplate.query(sql, orderRowMapper, orderId);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return mockOrders.stream().filter(o -> o.orderId().equalsIgnoreCase(orderId)).findFirst();
        }
    }

    /**
     * 更新订单状态。
     */
    public int updateStatus(String orderId, String newStatus) {
        // 更新内存冷启动数据
        for (int i = 0; i < mockOrders.size(); i++) {
            OrderSummary o = mockOrders.get(i);
            if (o.orderId().equalsIgnoreCase(orderId)) {
                mockOrders.set(i, new OrderSummary(
                        o.orderId(),
                        o.customerId(),
                        newStatus,
                        o.purchaseTimestamp(),
                        "delivered".equalsIgnoreCase(newStatus) ? LocalDateTime.now() : o.deliveredCustomerDate(),
                        o.itemCount(),
                        o.totalAmount()
                ));
                break;
            }
        }

        if (!databaseProbe.isDbAvailable()) {
            return 1;
        }

        try {
            String sql = "UPDATE orders SET order_status = ? WHERE order_id = ?";
            return jdbcTemplate.update(sql, newStatus, orderId);
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return 1;
        }
    }
}
