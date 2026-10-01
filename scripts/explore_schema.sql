-- 数据探索脚本：用于编写评估集时确认真实数据分布
-- 输出用 ### 标记分隔，便于脚本解析
\pset pager off

\echo '### ORDER_STATUS'
SELECT order_status, COUNT(*) AS cnt FROM orders GROUP BY 1 ORDER BY 2 DESC;

\echo '### ORDER_TIME_RANGE'
SELECT MIN(order_purchase_timestamp) AS min_ts, MAX(order_purchase_timestamp) AS max_ts, COUNT(*) FROM orders;

\echo '### PAYMENT_TYPE'
SELECT payment_type, COUNT(*) AS cnt FROM order_payments GROUP BY 1 ORDER BY 2 DESC;

\echo '### REVIEW_SCORE'
SELECT review_score, COUNT(*) AS cnt FROM order_reviews GROUP BY 1 ORDER BY 1;

\echo '### CUSTOMER_CARDINALITY'
SELECT COUNT(*) AS customer_rows, COUNT(DISTINCT customer_unique_id) AS unique_persons FROM customers;

\echo '### CUSTOMER_STATE_TOP'
SELECT customer_state, COUNT(*) AS cnt FROM customers GROUP BY 1 ORDER BY 2 DESC LIMIT 8;

\echo '### CATEGORY_TOP'
SELECT p.product_category_name, t.product_category_name_english, COUNT(*) AS cnt
FROM products p LEFT JOIN product_category_translation t USING (product_category_name)
GROUP BY 1,2 ORDER BY 3 DESC LIMIT 12;

\echo '### DELIVERY_NULLS'
SELECT COUNT(*) AS total,
       COUNT(*) FILTER (WHERE order_delivered_customer_date IS NULL) AS no_delivered,
       COUNT(*) FILTER (WHERE order_approved_at IS NULL) AS no_approved
FROM orders;

\echo '### MONTHLY_ORDERS_FIRST'
SELECT TO_CHAR(DATE_TRUNC('month', order_purchase_timestamp), 'YYYY-MM') AS m, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY 1 LIMIT 10;

\echo '### MONTHLY_ORDERS_LAST'
SELECT TO_CHAR(DATE_TRUNC('month', order_purchase_timestamp), 'YYYY-MM') AS m, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY 1 DESC LIMIT 8;

\echo '### PRICE_RANGE'
SELECT MIN(price) AS min_price, MAX(price) AS max_price, ROUND(AVG(price),2) AS avg_price,
       MIN(freight_value) AS min_freight, MAX(freight_value) AS max_freight FROM order_items;

\echo '### ITEMS_PER_ORDER'
SELECT ROUND(AVG(cnt),3) AS avg_items, MAX(cnt) AS max_items
FROM (SELECT order_id, COUNT(*) AS cnt FROM order_items GROUP BY 1) x;

\echo '### TABLE_COUNTS'
SELECT 'customers' AS t, COUNT(*) AS n FROM customers
UNION ALL SELECT 'sellers', COUNT(*) FROM sellers
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_items', COUNT(*) FROM order_items
UNION ALL SELECT 'order_payments', COUNT(*) FROM order_payments
UNION ALL SELECT 'order_reviews', COUNT(*) FROM order_reviews
UNION ALL SELECT 'geolocation', COUNT(*) FROM geolocation
UNION ALL SELECT 'product_category_translation', COUNT(*) FROM product_category_translation;
