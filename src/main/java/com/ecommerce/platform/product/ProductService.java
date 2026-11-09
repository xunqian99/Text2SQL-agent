package com.ecommerce.platform.product;

import com.ecommerce.platform.common.PageResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 商品业务逻辑服务层。
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public PageResult<Product> findPage(String category, String keyword, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.clamp(pageSize, 1, 100);
        return productRepository.findPage(category, keyword, safePage, safeSize);
    }

    public Optional<Product> findById(String productId) {
        if (productId == null || productId.isBlank()) {
            return Optional.empty();
        }
        return productRepository.findById(productId.trim());
    }

    public Product create(Product product) {
        String id = (product.productId() != null && !product.productId().isBlank())
                ? product.productId().trim()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 32);

        Product toSave = new Product(
                id,
                product.productCategoryName(),
                product.productPhotosQty() != null ? product.productPhotosQty() : 1,
                product.productWeightG() != null ? product.productWeightG() : 500,
                product.productLengthCm() != null ? product.productLengthCm() : 20,
                product.productHeightCm() != null ? product.productHeightCm() : 20,
                product.productWidthCm() != null ? product.productWidthCm() : 20,
                product.price()
        );

        productRepository.create(toSave);
        return toSave;
    }

    public boolean update(String productId, Product product) {
        if (productId == null || productId.isBlank()) {
            return false;
        }
        Product toUpdate = new Product(
                productId.trim(),
                product.productCategoryName(),
                product.productPhotosQty(),
                product.productWeightG(),
                product.productLengthCm(),
                product.productHeightCm(),
                product.productWidthCm(),
                product.price()
        );
        return productRepository.update(toUpdate) > 0;
    }

    public boolean delete(String productId) {
        if (productId == null || productId.isBlank()) {
            return false;
        }
        return productRepository.delete(productId.trim()) > 0;
    }

    public List<String> listCategories() {
        return productRepository.listCategories();
    }
}
