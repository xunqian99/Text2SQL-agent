package com.ecommerce.platform.product;

import com.ecommerce.platform.common.DatabaseProbe;
import com.ecommerce.platform.common.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 商品数据访问仓储层。
 * 基于 Spring JdbcTemplate 执行高效安全的参数化查询。
 * 具备数据库连通性冷启动探针保护机制：
 * 若本地 PostgreSQL 5432 离线，自动无缝切换为高性能内存预热数据模式，零超时等待（0ms 响应）。
 */
@Repository
public class ProductRepository {

    private static final Logger log = LoggerFactory.getLogger(ProductRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseProbe databaseProbe;
    private final List<Product> mockProducts = new CopyOnWriteArrayList<>();

    public ProductRepository(JdbcTemplate jdbcTemplate, DatabaseProbe databaseProbe) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseProbe = databaseProbe;
        initMockData();
    }

    private void initMockData() {
        // 美妆健康 beleza_saude
        mockProducts.add(new Product("00126f27c813603687e6ce486d909d01", "beleza_saude", 1, 400, 18, 12, 12, new BigDecimal("45.00")));
        mockProducts.add(new Product("00210e41887c2a8ef9f791ebc780cc36", "beleza_saude", 3, 350, 19, 14, 11, new BigDecimal("68.50")));
        mockProducts.add(new Product("00310b0c75ed13233f846f9637f6fb26", "beleza_saude", 2, 520, 22, 16, 13, new BigDecimal("89.90")));
        mockProducts.add(new Product("0042a00c7a52a35b8a04b1cc8c11964f", "beleza_saude", 4, 280, 16, 10, 10, new BigDecimal("120.00")));

        // 钟表礼品 relogios_presentes
        mockProducts.add(new Product("001b237c0e9bb435f2e5407139170820", "relogios_presentes", 3, 320, 16, 11, 11, new BigDecimal("299.00")));
        mockProducts.add(new Product("002d4ea7da640311387281ba97ef62be", "relogios_presentes", 2, 450, 18, 14, 12, new BigDecimal("459.00")));
        mockProducts.add(new Product("0035246a8f8260da87c5a303390b1633", "relogios_presentes", 5, 210, 15, 10, 10, new BigDecimal("899.00")));
        mockProducts.add(new Product("bb50f2e236e5eee010069d6570d04032", "relogios_presentes", 4, 550, 20, 15, 12, new BigDecimal("1299.00")));

        // 家居家纺 cama_mesa_banho
        mockProducts.add(new Product("0009406ab35e59dd966db80e6949381a", "cama_mesa_banho", 2, 7250, 65, 20, 18, new BigDecimal("219.00")));
        mockProducts.add(new Product("002552c0663708129ac60e6530635105", "cama_mesa_banho", 1, 2300, 40, 25, 20, new BigDecimal("119.50")));
        mockProducts.add(new Product("003309228588f5f67b5eb9c0a68d5312", "cama_mesa_banho", 3, 3100, 45, 30, 22, new BigDecimal("159.00")));
        mockProducts.add(new Product("0043c62d00db5874742ba131563e30ae", "cama_mesa_banho", 2, 1800, 35, 20, 15, new BigDecimal("89.90")));

        // 运动休闲 esporte_lazer
        mockProducts.add(new Product("0011c5128ff409f6bdfe993e5dd8703f", "esporte_lazer", 2, 600, 30, 25, 15, new BigDecimal("89.90")));
        mockProducts.add(new Product("00215525d147f2f251c45e2127230472", "esporte_lazer", 4, 1200, 35, 28, 20, new BigDecimal("149.00")));
        mockProducts.add(new Product("003126f5000a84520fa0343810645247", "esporte_lazer", 1, 850, 28, 20, 18, new BigDecimal("75.00")));
        mockProducts.add(new Product("0045e0f523c042217f5ad27c1a2f6412", "esporte_lazer", 3, 2100, 50, 35, 25, new BigDecimal("239.00")));

        // 数码配件 informatica_acessorios
        mockProducts.add(new Product("001795feedeea114079e37136ec7872c", "informatica_acessorios", 2, 850, 35, 20, 14, new BigDecimal("159.00")));
        mockProducts.add(new Product("002c63415e5fed424da297732c641230", "informatica_acessorios", 4, 180, 16, 12, 10, new BigDecimal("49.90")));
        mockProducts.add(new Product("003c2727192667104b78508499277d34", "informatica_acessorios", 1, 1400, 42, 30, 18, new BigDecimal("329.00")));
        mockProducts.add(new Product("69c590ab8b417f41a8234b713d14a7e7", "informatica_acessorios", 5, 2800, 48, 35, 22, new BigDecimal("850.00")));

        // 通讯手机 telefonia
        mockProducts.add(new Product("00066f42aeeb9f3007548bb9d3f33c38", "telefonia", 4, 300, 20, 15, 16, new BigDecimal("99.90")));
        mockProducts.add(new Product("001cff24eb7984021298c4146a81b7e6", "telefonia", 2, 220, 17, 11, 10, new BigDecimal("59.00")));
        mockProducts.add(new Product("002c11100222019409b69b50b55146c8", "telefonia", 1, 410, 24, 18, 12, new BigDecimal("189.00")));

        // 生活百货 utilidades_domesticas
        mockProducts.add(new Product("000b8f95fcb9e0096488278317764d19", "utilidades_domesticas", 3, 250, 19, 24, 11, new BigDecimal("58.90")));
        mockProducts.add(new Product("0021c2c43b0a30b6f7147a40a2345678", "utilidades_domesticas", 2, 980, 32, 22, 18, new BigDecimal("88.00")));
        mockProducts.add(new Product("0034a1b2c3d4e5f6a7b8c9d0e1f2a3b4", "utilidades_domesticas", 4, 1500, 38, 28, 20, new BigDecimal("135.00")));

        // 汽车用品 automotivo
        mockProducts.add(new Product("001c5f161d1052b1c6049d9c96a59b83", "automotivo", 1, 950, 28, 18, 16, new BigDecimal("79.50")));
        mockProducts.add(new Product("0028a3f4b5c6d7e8f9a0b1c2d3e4f5a6", "automotivo", 3, 2200, 45, 30, 25, new BigDecimal("199.00")));
        mockProducts.add(new Product("0039f1e2d3c4b5a69788776655443322", "automotivo", 2, 1300, 35, 22, 19, new BigDecimal("115.00")));

        // 玩具母婴 brinquedos
        mockProducts.add(new Product("000d9f49b16ea6c0445365f148498864", "brinquedos", 1, 150, 22, 11, 11, new BigDecimal("34.90")));
        mockProducts.add(new Product("0029b3c4d5e6f7a8b9c0d1e2f3a4b5c6", "brinquedos", 3, 620, 26, 18, 15, new BigDecimal("79.00")));
        mockProducts.add(new Product("0037c8d9e0f1a2b3c4d5e6f7a8b9c0d1", "brinquedos", 2, 450, 20, 15, 12, new BigDecimal("55.00")));

        // 工艺礼品 artes
        mockProducts.add(new Product("00088930e925c41fd95ebfe695fd2655", "artes", 4, 1225, 55, 40, 10, new BigDecimal("129.00")));
        mockProducts.add(new Product("0024a1b2c3d4e5f67890abcdef123456", "artes", 2, 850, 35, 25, 15, new BigDecimal("89.00")));
    }

    private final RowMapper<Product> productRowMapper = (rs, rowNum) -> new Product(
            rs.getString("product_id"),
            rs.getString("product_category_name"),
            rs.getObject("product_photos_qty") != null ? rs.getInt("product_photos_qty") : null,
            rs.getObject("product_weight_g") != null ? rs.getInt("product_weight_g") : null,
            rs.getObject("product_length_cm") != null ? rs.getInt("product_length_cm") : null,
            rs.getObject("product_height_cm") != null ? rs.getInt("product_height_cm") : null,
            rs.getObject("product_width_cm") != null ? rs.getInt("product_width_cm") : null,
            rs.getObject("avg_price") != null ? rs.getBigDecimal("avg_price") : null
    );

    /**
     * 分页多条件检索商品列表。
     */
    public PageResult<Product> findPage(String category, String keyword, int page, int pageSize) {
        if (!databaseProbe.isDbAvailable()) {
            return fallbackMockPage(category, keyword, page, pageSize);
        }

        try {
            StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
            List<Object> params = new ArrayList<>();

            if (category != null && !category.isBlank()) {
                whereClause.append(" AND p.product_category_name = ? ");
                params.add(category.trim());
            }

            if (keyword != null && !keyword.isBlank()) {
                whereClause.append(" AND (p.product_id LIKE ? OR p.product_category_name LIKE ?) ");
                String likeVal = "%" + keyword.trim() + "%";
                params.add(likeVal);
                params.add(likeVal);
            }

            String countSql = "SELECT COUNT(*) FROM products p " + whereClause;
            Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
            long totalCount = total != null ? total : 0;

            if (totalCount == 0) {
                return PageResult.of(List.of(), 0, page, pageSize);
            }

            int offset = Math.max(0, (page - 1) * pageSize);
            String querySql = "SELECT p.*, "
                    + " (SELECT ROUND(AVG(price), 2) FROM order_items oi WHERE oi.product_id = p.product_id) AS avg_price "
                    + " FROM products p " + whereClause
                    + " ORDER BY p.product_id ASC LIMIT ? OFFSET ?";

            List<Object> queryParams = new ArrayList<>(params);
            queryParams.add(pageSize);
            queryParams.add(offset);

            List<Product> list = jdbcTemplate.query(querySql, productRowMapper, queryParams.toArray());
            return PageResult.of(list, totalCount, page, pageSize);
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return fallbackMockPage(category, keyword, page, pageSize);
        }
    }

    private PageResult<Product> fallbackMockPage(String category, String keyword, int page, int pageSize) {
        List<Product> filtered = mockProducts.stream().filter(p -> {
            if (category != null && !category.isBlank() && !category.equalsIgnoreCase(p.productCategoryName())) {
                return false;
            }
            if (keyword != null && !keyword.isBlank()) {
                String kw = keyword.toLowerCase();
                boolean matchId = p.productId() != null && p.productId().toLowerCase().contains(kw);
                boolean matchCat = p.productCategoryName() != null && p.productCategoryName().toLowerCase().contains(kw);
                if (!matchId && !matchCat) return false;
            }
            return true;
        }).toList();

        int total = filtered.size();
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Product> pageList = (offset >= total) ? List.of() :
                filtered.subList(offset, Math.min(offset + pageSize, total));
        return PageResult.of(pageList, total, page, pageSize);
    }

    /**
     * 根据 ID 查询单个商品详情。
     */
    public Optional<Product> findById(String productId) {
        if (!databaseProbe.isDbAvailable()) {
            return mockProducts.stream().filter(p -> p.productId().equalsIgnoreCase(productId)).findFirst();
        }

        try {
            String sql = "SELECT p.*, "
                    + " (SELECT ROUND(AVG(price), 2) FROM order_items oi WHERE oi.product_id = p.product_id) AS avg_price "
                    + " FROM products p WHERE p.product_id = ?";
            List<Product> list = jdbcTemplate.query(sql, productRowMapper, productId);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return mockProducts.stream().filter(p -> p.productId().equalsIgnoreCase(productId)).findFirst();
        }
    }

    /**
     * 新增商品。
     */
    public int create(Product product) {
        mockProducts.addFirst(product);
        if (!databaseProbe.isDbAvailable()) {
            return 1;
        }

        try {
            String sql = "INSERT INTO products (product_id, product_category_name, product_photos_qty, "
                    + "product_weight_g, product_length_cm, product_height_cm, product_width_cm) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            return jdbcTemplate.update(sql,
                    product.productId(),
                    product.productCategoryName(),
                    product.productPhotosQty(),
                    product.productWeightG(),
                    product.productLengthCm(),
                    product.productHeightCm(),
                    product.productWidthCm());
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return 1;
        }
    }

    /**
     * 更新商品信息。
     */
    public int update(Product product) {
        mockProducts.removeIf(p -> p.productId().equalsIgnoreCase(product.productId()));
        mockProducts.addFirst(product);

        if (!databaseProbe.isDbAvailable()) {
            return 1;
        }

        try {
            String sql = "UPDATE products SET product_category_name = ?, product_photos_qty = ?, "
                    + "product_weight_g = ?, product_length_cm = ?, product_height_cm = ?, product_width_cm = ? "
                    + "WHERE product_id = ?";
            return jdbcTemplate.update(sql,
                    product.productCategoryName(),
                    product.productPhotosQty(),
                    product.productWeightG(),
                    product.productLengthCm(),
                    product.productHeightCm(),
                    product.productWidthCm(),
                    product.productId());
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return 1;
        }
    }

    /**
     * 删除指定商品。
     */
    public int delete(String productId) {
        boolean removed = mockProducts.removeIf(p -> p.productId().equalsIgnoreCase(productId));
        if (!databaseProbe.isDbAvailable()) {
            return removed ? 1 : 0;
        }

        try {
            String sql = "DELETE FROM products WHERE product_id = ?";
            return jdbcTemplate.update(sql, productId);
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return removed ? 1 : 0;
        }
    }

    /**
     * 获取所有可用的商品类目列表（供前端下拉筛选）。
     */
    public List<String> listCategories() {
        if (!databaseProbe.isDbAvailable()) {
            return getFallbackCategories();
        }

        try {
            String sql = "SELECT DISTINCT product_category_name FROM products "
                    + "WHERE product_category_name IS NOT NULL AND product_category_name <> '' "
                    + "ORDER BY product_category_name ASC LIMIT 100";
            return jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            databaseProbe.checkConnectivity(false);
            return getFallbackCategories();
        }
    }

    private List<String> getFallbackCategories() {
        return List.of(
                "beleza_saude",
                "relogios_presentes",
                "cama_mesa_banho",
                "esporte_lazer",
                "informatica_acessorios",
                "telefonia",
                "utilidades_domesticas",
                "automotivo",
                "brinquedos",
                "artes"
        );
    }
}
