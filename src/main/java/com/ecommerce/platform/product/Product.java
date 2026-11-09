package com.ecommerce.platform.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 商品实体对象。
 * 映射真实 Olist 数仓中的 products 表，并可携带价格信息。
 */
public record Product(
        @NotBlank(message = "商品ID不能为空")
        @Size(max = 32, message = "商品ID最长32位")
        String productId,

        String productCategoryName,

        Integer productPhotosQty,

        Integer productWeightG,

        Integer productLengthCm,

        Integer productHeightCm,

        Integer productWidthCm,

        BigDecimal price
) {
    public Product(String productId, String productCategoryName, Integer productPhotosQty,
                   Integer productWeightG, Integer productLengthCm, Integer productHeightCm,
                   Integer productWidthCm) {
        this(productId, productCategoryName, productPhotosQty, productWeightG, productLengthCm, productHeightCm, productWidthCm, null);
    }
}
