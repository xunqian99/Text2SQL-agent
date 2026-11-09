package com.ecommerce.platform;

import com.ecommerce.platform.common.ApiResponse;
import com.ecommerce.platform.common.DatabaseProbe;
import com.ecommerce.platform.common.PageResult;
import com.ecommerce.platform.dashboard.DashboardController;
import com.ecommerce.platform.metric.MetricDictionaryController;
import com.ecommerce.platform.order.OrderController;
import com.ecommerce.platform.order.OrderRepository;
import com.ecommerce.platform.order.OrderService;
import com.ecommerce.platform.order.OrderSummary;
import com.ecommerce.platform.product.Product;
import com.ecommerce.platform.product.ProductController;
import com.ecommerce.platform.product.ProductRepository;
import com.ecommerce.platform.product.ProductService;
import com.ecommerce.platform.system.SystemController;
import com.text2sql.agent.semantic.Metric;
import com.text2sql.agent.semantic.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class PlatformControllersTest {

    private DatabaseProbe databaseProbe;
    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        dataSource = Mockito.mock(DataSource.class);
        jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        databaseProbe = new DatabaseProbe(dataSource);
    }

    @Test
    @DisplayName("ProductRepository与Controller：离线冷启动模式下全套增删改查正常")
    void testProductCrudUnderOfflineProbe() {
        // databaseProbe 默认 dbAvailable = false
        ProductRepository repo = new ProductRepository(jdbcTemplate, databaseProbe);
        ProductService service = new ProductService(repo);
        ProductController controller = new ProductController(service);

        // 1. 列表分页查询
        ApiResponse<PageResult<Product>> listResp = controller.list(null, null, 1, 10);
        assertThat(listResp.code()).isEqualTo(200);
        assertThat(listResp.data().list()).isNotEmpty();
        assertThat(listResp.data().total()).isGreaterThan(0);

        // 2. 品类列表
        ApiResponse<List<String>> catResp = controller.listCategories();
        assertThat(catResp.code()).isEqualTo(200);
        assertThat(catResp.data()).contains("beleza_saude", "telefonia");

        // 3. 新增商品
        Product newProd = new Product("test_prod_1001", "telefonia", 2, 300, 20, 15, 10, new BigDecimal("99.90"));
        ResponseEntity<ApiResponse<Product>> createResp = controller.create(newProd);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResp.getBody().data().productId()).isEqualTo("test_prod_1001");

        // 4. 根据ID详情查询
        ResponseEntity<ApiResponse<Product>> getResp = controller.getById("test_prod_1001");
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().data().productCategoryName()).isEqualTo("telefonia");

        // 5. 更新商品
        Product updateProd = new Product("test_prod_1001", "esporte_lazer", 3, 400, 25, 20, 15, new BigDecimal("129.90"));
        ResponseEntity<ApiResponse<Void>> updateResp = controller.update("test_prod_1001", updateProd);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 6. 再次查询验证修改
        Product afterUpdate = controller.getById("test_prod_1001").getBody().data();
        assertThat(afterUpdate.productCategoryName()).isEqualTo("esporte_lazer");

        // 7. 删除商品
        ResponseEntity<ApiResponse<Void>> delResp = controller.delete("test_prod_1001");
        assertThat(delResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 8. 删除后查询返回 404
        ResponseEntity<ApiResponse<Product>> afterDel = controller.getById("test_prod_1001");
        assertThat(afterDel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("OrderController：订单列表分页与状态流转")
    void testOrderController() {
        OrderRepository repo = new OrderRepository(jdbcTemplate, databaseProbe);
        OrderService service = new OrderService(repo);
        OrderController controller = new OrderController(service);

        // 1. 列表
        ApiResponse<PageResult<OrderSummary>> listResp = controller.list("delivered", null, 1, 5);
        assertThat(listResp.code()).isEqualTo(200);
        assertThat(listResp.data().list()).isNotEmpty();
        String orderId = listResp.data().list().getFirst().orderId();

        // 2. 详情
        ResponseEntity<ApiResponse<OrderSummary>> detailResp = controller.getById(orderId);
        assertThat(detailResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailResp.getBody().data().orderId()).isEqualTo(orderId);

        // 3. 状态流转
        ResponseEntity<ApiResponse<Void>> flowResp = controller.updateStatus(orderId, Map.of("status", "canceled"));
        assertThat(flowResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. 再次获取验证状态已变更
        OrderSummary updated = controller.getById(orderId).getBody().data();
        assertThat(updated.orderStatus()).isEqualTo("canceled");
    }

    @Test
    @DisplayName("DashboardController：仪表盘指标与统计分布正常返回")
    void testDashboardStats() {
        DashboardController controller = new DashboardController(jdbcTemplate, databaseProbe);
        ApiResponse<Map<String, Object>> resp = controller.getStats();

        assertThat(resp.code()).isEqualTo(200);
        Map<String, Object> data = resp.data();
        assertThat(data).containsKeys("totalProducts", "totalOrders", "totalGmv", "monthlyTrend", "categoryDistribution");
    }

    @Test
    @DisplayName("MetricDictionaryController：能正确读取业务口径指标字典")
    void testMetricDictionary() {
        MetricRegistry registry = Mockito.mock(MetricRegistry.class);
        when(registry.all()).thenReturn(List.of(
                new Metric("gmv", List.of("成交总额"), "全站销售总额", "SUM(price + freight_value)", "order_status = 'delivered'", List.of("orders", "order_items"), "口径说明", "ROUND", null)
        ));

        MetricDictionaryController controller = new MetricDictionaryController(registry);
        ApiResponse<List<Metric>> resp = controller.listMetrics();

        assertThat(resp.code()).isEqualTo(200);
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().getFirst().name()).isEqualTo("gmv");
    }

    @Test
    @DisplayName("SystemController：系统用户与操作日志模拟接口正常")
    void testSystemController() {
        SystemController controller = new SystemController();

        ApiResponse<List<Map<String, Object>>> users = controller.listUsers();
        assertThat(users.code()).isEqualTo(200);
        assertThat(users.data()).isNotEmpty();

        ApiResponse<List<Map<String, Object>>> logs = controller.listLogs();
        assertThat(logs.code()).isEqualTo(200);
        assertThat(logs.data()).isNotEmpty();
    }

    @Test
    @DisplayName("DatabaseProbe：在线与异常探测自动切换")
    void testDatabaseProbeToggle() throws SQLException {
        Connection mockConn = Mockito.mock(Connection.class);
        when(mockConn.isValid(anyInt())).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(mockConn);

        boolean ready = databaseProbe.checkConnectivity(true);
        assertThat(ready).isTrue();
        assertThat(databaseProbe.isDbAvailable()).isTrue();

        // 模拟断开
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        boolean offline = databaseProbe.checkConnectivity(true);
        assertThat(offline).isFalse();
        assertThat(databaseProbe.isDbAvailable()).isFalse();
    }
}
