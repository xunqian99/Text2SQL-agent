package com.ecommerce.platform.product;

import com.ecommerce.platform.common.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductServiceTest {

    private ProductRepository productRepository;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productRepository = Mockito.mock(ProductRepository.class);
        productService = new ProductService(productRepository);
    }

    @Test
    @DisplayName("分页查询：能正确限制分页边界并转发仓储层")
    void testFindPage() {
        List<Product> mockList = List.of(
                new Product("p1", "telefonia", 2, 500, 20, 20, 20, new BigDecimal("99.90"))
        );
        PageResult<Product> expected = PageResult.of(mockList, 1, 1, 10);
        when(productRepository.findPage(eq("telefonia"), eq("phone"), eq(1), eq(10)))
                .thenReturn(expected);

        PageResult<Product> result = productService.findPage("telefonia", "phone", 1, 10);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.list()).hasSize(1);
        assertThat(result.list().getFirst().productId()).isEqualTo("p1");
    }

    @Test
    @DisplayName("新增商品：未传 ID 时自动生成 32 位 UUID")
    void testCreateAutoId() {
        Product input = new Product(null, "esporte_lazer", 1, 300, 15, 15, 15, new BigDecimal("49.00"));

        Product created = productService.create(input);

        assertThat(created.productId()).isNotNull().hasSize(32);
        assertThat(created.productCategoryName()).isEqualTo("esporte_lazer");
        verify(productRepository).create(any(Product.class));
    }

    @Test
    @DisplayName("详情查询与删除：正常响应")
    void testFindByIdAndDelete() {
        when(productRepository.findById("p100")).thenReturn(Optional.of(
                new Product("p100", "informatica_acessorios", 3, 800, 25, 25, 25, new BigDecimal("199.00"))
        ));
        when(productRepository.delete("p100")).thenReturn(1);

        Optional<Product> found = productService.findById("p100");
        assertThat(found).isPresent();
        assertThat(found.get().productId()).isEqualTo("p100");

        boolean deleted = productService.delete("p100");
        assertThat(deleted).isTrue();
    }
}
