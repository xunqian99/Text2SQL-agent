package com.ecommerce.platform.product;

import com.ecommerce.platform.common.ApiResponse;
import com.ecommerce.platform.common.PageResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品管理 RESTful 接入控制器。
 * 提供商品分页查询、根据ID查看、新增、编辑、删除及品类列表接口。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * 分页查询商品列表。
     */
    @GetMapping
    public ApiResponse<PageResult<Product>> list(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        PageResult<Product> result = productService.findPage(category, keyword, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * 获取所有可用商品品类列表。
     */
    @GetMapping("/categories")
    public ApiResponse<List<String>> listCategories() {
        return ApiResponse.ok(productService.listCategories());
    }

    /**
     * 查询指定商品详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Product>> getById(@PathVariable("id") String id) {
        return productService.findById(id)
                .map(p -> ResponseEntity.ok(ApiResponse.ok(p)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, "商品不存在: " + id)));
    }

    /**
     * 新增商品。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Product>> create(@Valid @RequestBody Product product) {
        Product created = productService.create(product);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("商品新增成功", created));
    }

    /**
     * 更新商品信息。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> update(
            @PathVariable("id") String id,
            @RequestBody Product product) {
        boolean ok = productService.update(id, product);
        if (ok) {
            return ResponseEntity.ok(ApiResponse.ok("更新成功", null));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "更新失败，商品不存在: " + id));
        }
    }

    /**
     * 删除商品。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable("id") String id) {
        boolean ok = productService.delete(id);
        if (ok) {
            return ResponseEntity.ok(ApiResponse.ok("删除成功", null));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "删除失败，商品不存在: " + id));
        }
    }
}
