-- Olist 数据导入脚本
-- 执行方式：在容器内用 psql 运行，CSV 通过只读卷挂载在 /data/olist
-- 注意：COPY 需要绝对路径，且文件必须对 postgres 用户可读

\set ON_ERROR_STOP on

TRUNCATE order_reviews, order_payments, order_items, orders,
         products, product_category_translation, customers, sellers, geolocation;

-- 客户表：CSV 含表头，用 HEADER 跳过
COPY customers (customer_id, customer_unique_id, customer_zip_code_prefix, customer_city, customer_state)
FROM '/data/olist/olist_customers_dataset.csv' WITH (FORMAT csv, HEADER true);

COPY sellers (seller_id, seller_zip_code_prefix, seller_city, seller_state)
FROM '/data/olist/olist_sellers_dataset.csv' WITH (FORMAT csv, HEADER true);

-- 类目翻译表：CSV 无表头
COPY product_category_translation (product_category_name, product_category_name_english)
FROM '/data/olist/product_category_name_translation.csv' WITH (FORMAT csv, HEADER true);

-- 商品表：空字符串要转成 NULL，否则 INTEGER 列会导入失败
COPY products (product_id, product_category_name, product_name_lenght,
               product_description_lenght, product_photos_qty, product_weight_g,
               product_length_cm, product_height_cm, product_width_cm)
FROM '/data/olist/olist_products_dataset.csv' WITH (FORMAT csv, HEADER true, NULL '');

-- 订单表：部分订单尚未送达，时间字段为空，同样需要 NULL ''
COPY orders (order_id, customer_id, order_status, order_purchase_timestamp,
             order_approved_at, order_delivered_carrier_date,
             order_delivered_customer_date, order_estimated_delivery_date)
FROM '/data/olist/olist_orders_dataset.csv' WITH (FORMAT csv, HEADER true, NULL '');

COPY order_items (order_id, order_item_id, product_id, seller_id,
                  shipping_limit_date, price, freight_value)
FROM '/data/olist/olist_order_items_dataset.csv' WITH (FORMAT csv, HEADER true, NULL '');

COPY order_payments (order_id, payment_sequential, payment_type,
                     payment_installments, payment_value)
FROM '/data/olist/olist_order_payments_dataset.csv' WITH (FORMAT csv, HEADER true, NULL '');

COPY order_reviews (review_id, order_id, review_score, review_comment_title,
                    review_comment_message, review_creation_date, review_answer_timestamp)
FROM '/data/olist/olist_order_reviews_dataset.csv' WITH (FORMAT csv, HEADER true, NULL '');

COPY geolocation (geolocation_zip_code_prefix, geolocation_lat, geolocation_lng,
                  geolocation_city, geolocation_state)
FROM '/data/olist/olist_geolocation_dataset.csv' WITH (FORMAT csv, HEADER true);

ANALYZE;
