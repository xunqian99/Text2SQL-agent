-- Olist 巴西电商数据集建表脚本
-- 设计原则：保留真实数据集的原貌，包括字段名拼写错误（product_name_lenght）。
-- 这些"不完美"正是 Text2SQL 检索模块要解决的问题，人为修干净反而失去意义。

DROP TABLE IF EXISTS order_reviews CASCADE;
DROP TABLE IF EXISTS order_payments CASCADE;
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS product_category_translation CASCADE;
DROP TABLE IF EXISTS customers CASCADE;
DROP TABLE IF EXISTS sellers CASCADE;
DROP TABLE IF EXISTS geolocation CASCADE;

-- 客户表：customer_id 是订单级标识，customer_unique_id 才是自然人
-- 这个区别是真实业务里的经典陷阱，后续评估集要专门覆盖
CREATE TABLE customers (
    customer_id                 VARCHAR(32) PRIMARY KEY,
    customer_unique_id          VARCHAR(32) NOT NULL,
    customer_zip_code_prefix    INTEGER,
    customer_city               VARCHAR(64),
    customer_state              VARCHAR(8)
);

-- 卖家表
CREATE TABLE sellers (
    seller_id                   VARCHAR(32) PRIMARY KEY,
    seller_zip_code_prefix      INTEGER,
    seller_city                 VARCHAR(64),
    seller_state               VARCHAR(8)
);

-- 商品类目翻译表：葡萄牙语 -> 英语
CREATE TABLE product_category_translation (
    product_category_name           VARCHAR(128) PRIMARY KEY,
    product_category_name_english   VARCHAR(128)
);

-- 商品表：注意 product_name_lenght 是原始数据集里的拼写错误，故意保留
CREATE TABLE products (
    product_id                  VARCHAR(32) PRIMARY KEY,
    product_category_name       VARCHAR(128),
    product_name_lenght         INTEGER,
    product_description_lenght  INTEGER,
    product_photos_qty          INTEGER,
    product_weight_g            INTEGER,
    product_length_cm           INTEGER,
    product_height_cm           INTEGER,
    product_width_cm            INTEGER
);

-- 订单表：order_status 是枚举值，只有了解每个值的含义才能写对 SQL
CREATE TABLE orders (
    order_id                        VARCHAR(32) PRIMARY KEY,
    customer_id                     VARCHAR(32) NOT NULL REFERENCES customers(customer_id),
    order_status                    VARCHAR(16) NOT NULL,
    order_purchase_timestamp        TIMESTAMP,
    order_approved_at               TIMESTAMP,
    order_delivered_carrier_date    TIMESTAMP,
    order_delivered_customer_date   TIMESTAMP,
    order_estimated_delivery_date   TIMESTAMP
);

-- 订单明细：一个订单多行，做金额统计时必须注意粒度
CREATE TABLE order_items (
    order_id            VARCHAR(32) NOT NULL REFERENCES orders(order_id),
    order_item_id       INTEGER NOT NULL,
    product_id          VARCHAR(32) NOT NULL REFERENCES products(product_id),
    seller_id           VARCHAR(32) NOT NULL REFERENCES sellers(seller_id),
    shipping_limit_date TIMESTAMP,
    price               NUMERIC(12,2) NOT NULL,
    freight_value       NUMERIC(12,2) NOT NULL,
    PRIMARY KEY (order_id, order_item_id)
);

-- 支付表：一个订单可能有多笔支付（分期、组合支付）
CREATE TABLE order_payments (
    order_id                VARCHAR(32) NOT NULL REFERENCES orders(order_id),
    payment_sequential      INTEGER NOT NULL,
    payment_type            VARCHAR(24),
    payment_installments    INTEGER,
    payment_value           NUMERIC(12,2),
    PRIMARY KEY (order_id, payment_sequential)
);

-- 评价表
CREATE TABLE order_reviews (
    review_id               VARCHAR(32) NOT NULL,
    order_id                VARCHAR(32) NOT NULL REFERENCES orders(order_id),
    review_score            INTEGER,
    review_comment_title    VARCHAR(256),
    review_comment_message  TEXT,
    review_creation_date    TIMESTAMP,
    review_answer_timestamp TIMESTAMP,
    PRIMARY KEY (review_id, order_id)
);

-- 地理表：一个邮编前缀对应多个坐标点，join 时会放大行数
CREATE TABLE geolocation (
    geolocation_zip_code_prefix INTEGER,
    geolocation_lat             NUMERIC(12,8),
    geolocation_lng             NUMERIC(12,8),
    geolocation_city            VARCHAR(64),
    geolocation_state           VARCHAR(8)
);

CREATE INDEX idx_orders_customer     ON orders(customer_id);
CREATE INDEX idx_orders_status       ON orders(order_status);
CREATE INDEX idx_orders_purchase_ts  ON orders(order_purchase_timestamp);
CREATE INDEX idx_order_items_product ON order_items(product_id);
CREATE INDEX idx_order_items_seller  ON order_items(seller_id);
CREATE INDEX idx_payments_order      ON order_payments(order_id);
CREATE INDEX idx_reviews_order       ON order_reviews(order_id);
CREATE INDEX idx_products_category   ON products(product_category_name);
CREATE INDEX idx_geo_zip             ON geolocation(geolocation_zip_code_prefix);
