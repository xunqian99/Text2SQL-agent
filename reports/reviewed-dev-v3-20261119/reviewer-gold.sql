-- T1-002 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM orders
WHERE order_purchase_timestamp >= '2018-01-01'
  AND order_purchase_timestamp < '2019-01-01') q;
ROLLBACK;

-- T1-004 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(DISTINCT customer_unique_id) FROM customers) q;
ROLLBACK;

-- T1-006 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM products) q;
ROLLBACK;

-- T1-008 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(DISTINCT customer_city) FROM customers) q;
ROLLBACK;

-- T1-011 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(DISTINCT product_category_name) FROM products) q;
ROLLBACK;

-- T1-013 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT order_id, order_item_id, freight_value
FROM order_items ORDER BY freight_value DESC LIMIT 5) q;
ROLLBACK;

-- T1-015 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM order_payments WHERE payment_type = 'credit_card') q;
ROLLBACK;

-- T1-017 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM products WHERE product_weight_g > 5000) q;
ROLLBACK;

-- T1-019 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM orders WHERE order_delivered_customer_date IS NULL) q;
ROLLBACK;

-- T1-020 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM order_reviews WHERE review_comment_title IS NOT NULL) q;
ROLLBACK;

-- T1-022 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM order_items WHERE price < 10) q;
ROLLBACK;

-- T1-024 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM members WHERE level_id = 3) q;
ROLLBACK;

-- T1-026 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT member_id, points FROM members ORDER BY points DESC LIMIT 10) q;
ROLLBACK;

-- T1-028 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT warehouse_id, warehouse_name, capacity
FROM warehouses ORDER BY capacity DESC LIMIT 1) q;
ROLLBACK;

-- T1-031 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM coupons) q;
ROLLBACK;

-- T1-033 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM support_tickets WHERE priority = 4) q;
ROLLBACK;

-- T1-035 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM inventory WHERE quantity < safety_stock) q;
ROLLBACK;

-- T1-037 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(DISTINCT keyword) FROM search_logs) q;
ROLLBACK;

-- T1-039 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM campaigns WHERE status = 3) q;
ROLLBACK;

-- T1-040 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(DISTINCT product_id) FROM product_price_history) q;
ROLLBACK;

-- T2-001 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT SUM(price) FROM order_items) q;
ROLLBACK;

-- T2-003 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(AVG(price), 2) FROM order_items) q;
ROLLBACK;

-- T2-005 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT order_status, COUNT(*) AS cnt
FROM orders GROUP BY order_status ORDER BY cnt DESC) q;
ROLLBACK;

-- T2-007 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT review_score, COUNT(*) AS cnt
FROM order_reviews GROUP BY review_score ORDER BY review_score) q;
ROLLBACK;

-- T2-009 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT product_category_name, COUNT(*) AS cnt
FROM products
WHERE product_category_name IS NOT NULL
GROUP BY product_category_name ORDER BY cnt DESC LIMIT 10) q;
ROLLBACK;

-- T2-010 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT seller_state, COUNT(*) AS cnt
FROM sellers GROUP BY seller_state ORDER BY cnt DESC) q;
ROLLBACK;

-- T2-012 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT seller_id, ROUND(SUM(freight_value), 2) AS total
FROM order_items GROUP BY seller_id ORDER BY total DESC LIMIT 10) q;
ROLLBACK;

-- T2-014 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT
  COUNT(*) FILTER (WHERE payment_type = 'credit_card') AS credit_card,
  COUNT(*) FILTER (WHERE payment_type = 'boleto')      AS boleto
FROM order_payments) q;
ROLLBACK;

-- T2-016 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT status, COUNT(*) AS cnt FROM members GROUP BY status ORDER BY status) q;
ROLLBACK;

-- T2-018 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT campaign_type, COUNT(*) AS cnt
FROM campaigns GROUP BY campaign_type ORDER BY cnt DESC) q;
ROLLBACK;

-- T2-021 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT reason_code, COUNT(*) AS cnt
FROM refunds GROUP BY reason_code ORDER BY cnt DESC) q;
ROLLBACK;

-- T2-023 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(SUM(refund_amount), 2) FROM refunds WHERE status = 4) q;
ROLLBACK;

-- T2-025 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT SUM(quantity) FROM inventory) q;
ROLLBACK;

-- T2-027 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT movement_type, COUNT(*) AS cnt
FROM inventory_movements GROUP BY movement_type ORDER BY movement_type) q;
ROLLBACK;

-- T2-029 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT status, COUNT(*) AS cnt FROM shipments GROUP BY status ORDER BY status) q;
ROLLBACK;

-- T2-030 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT category_id, COUNT(*) AS cnt
FROM support_tickets GROUP BY category_id ORDER BY category_id) q;
ROLLBACK;

-- T2-032 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT status, COUNT(*) AS cnt FROM settlements GROUP BY status ORDER BY status) q;
ROLLBACK;

-- T2-034 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(SUM(commission), 2) FROM settlements) q;
ROLLBACK;

-- T2-036 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT product_category_name, COUNT(*) AS cnt
FROM products
WHERE product_category_name IS NOT NULL
GROUP BY product_category_name
HAVING COUNT(*) > 1500
ORDER BY cnt DESC) q;
ROLLBACK;

-- T2-038 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT MAX(price) AS max_price, MIN(price) AS min_price FROM order_items) q;
ROLLBACK;

-- T3-002 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT EXTRACT(YEAR FROM order_purchase_timestamp)::INT AS yr, COUNT(*) AS cnt
FROM orders
WHERE EXTRACT(YEAR FROM order_purchase_timestamp) IN (2017, 2018)
GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T3-004 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM orders
WHERE order_purchase_timestamp >= '2017-11-01'
  AND order_purchase_timestamp < '2017-12-01') q;
ROLLBACK;

-- T3-006 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT DATE_TRUNC('month', o.order_purchase_timestamp) AS month,
       ROUND(SUM(oi.price), 2) AS sales
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T3-008 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT DATE_TRUNC('month', o.order_purchase_timestamp) AS month,
       ROUND(SUM(oi.price) / COUNT(DISTINCT o.order_id), 2) AS avg_order_value
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T3-011 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH monthly AS (
  SELECT DATE_TRUNC('month', order_purchase_timestamp) AS m, COUNT(*) AS cnt
  FROM orders GROUP BY 1
)
SELECT m AS month,
       cnt,
       ROUND((cnt - LAG(cnt) OVER (ORDER BY m))::NUMERIC
             / NULLIF(LAG(cnt) OVER (ORDER BY m), 0), 4) AS mom_rate
FROM monthly ORDER BY m) q;
ROLLBACK;

-- T3-013 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM orders
WHERE order_purchase_timestamp > (SELECT MAX(order_purchase_timestamp) FROM orders) - INTERVAL '90 days') q;
ROLLBACK;

-- T3-015 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT EXTRACT(HOUR FROM order_purchase_timestamp)::INT AS hr, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY cnt DESC LIMIT 1) q;
ROLLBACK;

-- T3-017 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(AVG(EXTRACT(EPOCH FROM (order_delivered_customer_date - order_purchase_timestamp)) / 86400), 2)
FROM orders WHERE order_delivered_customer_date IS NOT NULL) q;
ROLLBACK;

-- T3-019 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM orders
WHERE order_delivered_customer_date > order_estimated_delivery_date) q;
ROLLBACK;

-- T3-020 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(
  COUNT(*) FILTER (WHERE order_delivered_customer_date > order_estimated_delivery_date)::NUMERIC
  / NULLIF(COUNT(*) FILTER (WHERE order_delivered_customer_date IS NOT NULL), 0), 4) AS late_rate
FROM orders) q;
ROLLBACK;

-- T3-022 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT DATE_TRUNC('month', requested_at) AS month,
       ROUND(SUM(refund_amount), 2) AS total
FROM refunds GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T3-024 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600), 2)
FROM support_tickets WHERE resolved_at IS NOT NULL) q;
ROLLBACK;

-- T3-026 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT DATE_TRUNC('month', review_creation_date) AS month,
       ROUND(AVG(review_score), 2) AS avg_score
FROM order_reviews GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T3-028 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*) FROM members
WHERE last_login_date > (SELECT MAX(last_login_date) FROM members) - 30) q;
ROLLBACK;

-- T4-001 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT c.customer_unique_id, COUNT(*) AS cnt
FROM orders o JOIN customers c ON c.customer_id = o.customer_id
GROUP BY 1 ORDER BY cnt DESC LIMIT 10) q;
ROLLBACK;

-- T4-003 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT p.product_category_name, COUNT(DISTINCT oi.order_id) AS order_cnt
FROM order_items oi JOIN products p ON p.product_id = oi.product_id
WHERE p.product_category_name IS NOT NULL
GROUP BY 1 ORDER BY order_cnt DESC) q;
ROLLBACK;

-- T4-005 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT oi.seller_id, COUNT(DISTINCT oi.order_id) AS order_cnt
FROM order_items oi GROUP BY 1 ORDER BY order_cnt DESC LIMIT 10) q;
ROLLBACK;

-- T4-007 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT COUNT(*)
FROM products p
LEFT JOIN product_price_history h ON h.product_id = p.product_id
WHERE h.product_id IS NULL) q;
ROLLBACK;

-- T4-009 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT payment_type, ROUND(AVG(payment_value), 2) AS avg_value
FROM order_payments WHERE payment_type IS NOT NULL
GROUP BY 1 ORDER BY avg_value DESC) q;
ROLLBACK;

-- T4-010 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT o.order_status, ROUND(SUM(oi.price), 2) AS sales
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY sales DESC) q;
ROLLBACK;

-- T4-012 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT c.customer_state, ROUND(AVG(r.review_score), 2) AS avg_score, COUNT(*) AS cnt
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
GROUP BY 1 ORDER BY cnt DESC LIMIT 10) q;
ROLLBACK;

-- T4-014 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT p.payment_type, ROUND(AVG(r.review_score), 2) AS avg_score
FROM order_payments p
JOIN order_reviews r ON r.order_id = p.order_id
GROUP BY 1 ORDER BY avg_score DESC) q;
ROLLBACK;

-- T4-016 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT r.macro_region, ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY sales DESC) q;
ROLLBACK;

-- T4-018 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT m.level_id, ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T4-021 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT c.campaign_name, COUNT(*) AS cnt
FROM coupon_usages cu
JOIN coupons cp ON cp.coupon_id = cu.coupon_id
JOIN campaigns c ON c.campaign_id = cp.campaign_id
GROUP BY 1 ORDER BY cnt DESC) q;
ROLLBACK;

-- T4-023 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT c.discount_type, ROUND(SUM(cu.discount_amount), 2) AS total
FROM coupon_usages cu JOIN coupons c ON c.coupon_id = cu.coupon_id
GROUP BY 1 ORDER BY total DESC) q;
ROLLBACK;

-- T4-025 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT r.macro_region, SUM(w.capacity) AS total_capacity
FROM warehouses w JOIN regions r ON r.region_code = w.region_code
GROUP BY 1 ORDER BY total_capacity DESC) q;
ROLLBACK;

-- T4-027 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT w.warehouse_name, SUM(i.quantity) AS total_qty, ROUND(AVG(i.quantity), 2) AS avg_qty
FROM inventory i JOIN warehouses w ON w.warehouse_id = i.warehouse_id
GROUP BY 1 ORDER BY total_qty DESC) q;
ROLLBACK;

-- T4-029 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT c.customer_state, COUNT(*) AS cnt
FROM support_tickets st
JOIN orders o ON o.order_id = st.order_id
JOIN customers c ON c.customer_id = o.customer_id
GROUP BY 1 ORDER BY cnt DESC) q;
ROLLBACK;

-- T4-030 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT seller_id, ROUND(SUM(total_amount), 2) AS total
FROM settlements GROUP BY 1 ORDER BY total DESC LIMIT 10) q;
ROLLBACK;

-- T4-032 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT p.product_category_name, COUNT(DISTINCT rf.refund_id) AS cnt
FROM refunds rf
JOIN order_items oi ON oi.order_id = rf.order_id
JOIN products p ON p.product_id = oi.product_id
WHERE p.product_category_name IS NOT NULL
GROUP BY 1 ORDER BY cnt DESC) q;
ROLLBACK;

-- T4-034 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(
  (SELECT COUNT(DISTINCT order_id) FROM refunds)::NUMERIC
  / (SELECT COUNT(*) FROM orders), 4) AS refund_rate) q;
ROLLBACK;

-- T4-036 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT c.customer_state,
       ROUND(SUM(oi.price) / COUNT(DISTINCT c.customer_unique_id), 2) AS avg_per_customer
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY avg_per_customer DESC) q;
ROLLBACK;

-- T4-038 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT m.level_id, COUNT(DISTINCT m.member_id) AS cnt
FROM members m
JOIN user_tag_map tm ON tm.member_id = m.member_id
JOIN user_tags t ON t.tag_id = tm.tag_id
WHERE t.tag_code = 'CHURN_RISK'
GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T4-041 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT b.brand_name, COUNT(DISTINCT oi.product_id) AS product_cnt,
       ROUND(SUM(oi.price), 2) AS sales
FROM order_items oi
JOIN product_brand_map bm ON bm.product_id = oi.product_id
JOIN brands b ON b.brand_id = bm.brand_id
GROUP BY 1 ORDER BY sales DESC LIMIT 10) q;
ROLLBACK;

-- T4-043 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT s.status, ROUND(AVG(t.cnt), 2) AS avg_events
FROM shipments s
JOIN (SELECT shipment_id, COUNT(*) AS cnt FROM shipment_tracking GROUP BY 1) t
  ON t.shipment_id = s.shipment_id
GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T4-045 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT p.product_category_name,
       COUNT(*) AS product_cnt,
       ROUND(AVG(p.product_weight_g), 2) AS avg_weight
FROM products p
WHERE p.product_category_name IS NOT NULL AND p.product_weight_g IS NOT NULL
GROUP BY 1 ORDER BY product_cnt DESC LIMIT 10) q;
ROLLBACK;

-- T5-002 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ml.level_name, ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
JOIN member_levels ml ON ml.level_id = m.level_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY sales DESC) q;
ROLLBACK;

-- T5-004 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT r.region_name, COUNT(DISTINCT s.shipment_id) AS shipments, COUNT(DISTINCT o.order_id) AS orders
FROM shipments s
JOIN warehouses w ON w.warehouse_id = s.warehouse_id
JOIN regions r ON r.region_code = w.region_code
JOIN orders o ON o.order_id = s.order_id
JOIN customers c ON c.customer_id = o.customer_id
GROUP BY 1 ORDER BY shipments DESC) q;
ROLLBACK;

-- T5-006 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT c.customer_state, ml.level_name, COUNT(DISTINCT st.ticket_id) AS tickets
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
JOIN orders o ON o.order_id = st.order_id
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.member_id = st.member_id
JOIN member_levels ml ON ml.level_id = m.level_id
WHERE tc.category_name = '物流延迟'
GROUP BY 1, 2 ORDER BY tickets DESC LIMIT 20) q;
ROLLBACK;

-- T5-008 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT mlr.level_name AS referrer_level, mli.level_name AS invitee_level,
       COUNT(*) AS referrals, ROUND(SUM(rf.reward_amount), 2) AS reward
FROM referrals rf
JOIN members mr ON mr.member_id = rf.referrer_member_id
JOIN member_levels mlr ON mlr.level_id = mr.level_id
JOIN members mi ON mi.member_id = rf.invitee_member_id
JOIN member_levels mli ON mli.level_id = mi.level_id
GROUP BY 1, 2 ORDER BY referrals DESC) q;
ROLLBACK;

-- T5-011 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT b.brand_name, ROUND(SUM(oi.price), 2) AS sales
FROM order_items oi
JOIN product_brand_map pbm ON pbm.product_id = oi.product_id AND pbm.is_current
JOIN brands b ON b.brand_id = pbm.brand_id
JOIN orders o ON o.order_id = oi.order_id
JOIN customers c ON c.customer_id = o.customer_id
GROUP BY 1 ORDER BY sales DESC LIMIT 10) q;
ROLLBACK;

-- T5-013 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ca.campaign_name, COUNT(DISTINCT oi.order_id) AS orders, ROUND(SUM(oi.price), 2) AS gmv
FROM campaign_products cp
JOIN campaigns ca ON ca.campaign_id = cp.campaign_id
JOIN order_items oi ON oi.product_id = cp.product_id
JOIN orders o ON o.order_id = oi.order_id
JOIN customers c ON c.customer_id = o.customer_id
GROUP BY 1 ORDER BY gmv DESC LIMIT 10) q;
ROLLBACK;

-- T5-015 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT st.event_code, c.customer_state, COUNT(DISTINCT s.shipment_id) AS shipments
FROM shipment_tracking st
JOIN shipments s ON s.shipment_id = st.shipment_id
JOIN orders o ON o.order_id = s.order_id
JOIN customers c ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1, 2 ORDER BY shipments DESC LIMIT 20) q;
ROLLBACK;

-- T5-017 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT tc.category_name, st.priority, COUNT(DISTINCT st.ticket_id) AS tickets,
       COUNT(tm.message_id) AS messages
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
JOIN ticket_messages tm ON tm.ticket_id = st.ticket_id
JOIN members m ON m.member_id = st.member_id
JOIN member_levels ml ON ml.level_id = m.level_id
GROUP BY 1, 2 ORDER BY tickets DESC) q;
ROLLBACK;

-- T5-019 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT p.product_category_name,
       ROUND(AVG(pph.new_price - pph.old_price), 2) AS avg_price_change,
       COUNT(DISTINCT oi.order_id) AS orders
FROM product_price_history pph
JOIN products p ON p.product_id = pph.product_id
JOIN order_items oi ON oi.product_id = pph.product_id
JOIN orders o ON o.order_id = oi.order_id
JOIN customers c ON c.customer_id = o.customer_id
GROUP BY 1 ORDER BY orders DESC LIMIT 20) q;
ROLLBACK;

-- T5-020 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT r.macro_region, im.movement_type, COUNT(*) AS movements, SUM(im.quantity) AS qty
FROM inventory_movements im
JOIN inventory i ON i.warehouse_id = im.warehouse_id AND i.product_id = im.product_id
JOIN warehouses w ON w.warehouse_id = im.warehouse_id
JOIN regions r ON r.region_code = w.region_code
JOIN products p ON p.product_id = im.product_id
GROUP BY 1, 2 ORDER BY movements DESC) q;
ROLLBACK;

-- T6-001 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(SUM(oi.price + oi.freight_value), 2) AS gmv_with_freight,
       ROUND(SUM(oi.price), 2) AS sales_without_freight
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
  AND o.order_purchase_timestamp >= DATE '2018-01-01'
  AND o.order_purchase_timestamp < DATE '2019-01-01') q;
ROLLBACK;

-- T6-003 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH per_user AS (
  SELECT c.customer_unique_id, COUNT(DISTINCT o.order_id) AS order_cnt
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1
)
SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE order_cnt >= 2) / COUNT(*), 2) AS repeat_rate_pct
FROM per_user) q;
ROLLBACK;

-- T6-005 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH member_orders AS (
  SELECT COUNT(DISTINCT o.order_id) AS n
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  JOIN members m ON m.customer_unique_id = c.customer_unique_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
),
all_orders AS (
  SELECT COUNT(DISTINCT o.order_id) AS n
  FROM orders o
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
)
SELECT ROUND(100.0 * mo.n / ao.n, 2) AS penetration_pct
FROM member_orders mo CROSS JOIN all_orders ao) q;
ROLLBACK;

-- T6-007 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH r AS (
  SELECT COUNT(DISTINCT r.order_id) AS n FROM refunds r
  JOIN orders o ON o.order_id = r.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
),
a AS (
  SELECT COUNT(DISTINCT o.order_id) AS n FROM orders o
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
)
SELECT ROUND(100.0 * r.n / a.n, 2) AS refund_rate_pct
FROM r CROSS JOIN a) q;
ROLLBACK;

-- T6-009 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT c.customer_state,
       ROUND(100.0 * COUNT(*) FILTER (WHERE r.review_score <= 2) / COUNT(*), 2) AS low_pct
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY low_pct DESC LIMIT 10) q;
ROLLBACK;

-- T6-010 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE clicked_product_id IS NOT NULL) / COUNT(*), 2) AS ctr_pct
FROM search_logs) q;
ROLLBACK;

-- T6-012 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (SELECT DATE_TRUNC('month', o.order_purchase_timestamp)::date AS month,
       COUNT(DISTINCT c.customer_unique_id) AS mau
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
  AND o.order_purchase_timestamp >= DATE '2018-01-01'
  AND o.order_purchase_timestamp < DATE '2019-01-01'
GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T6-014 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH user_level AS (
  SELECT m.member_id, ml.level_name
  FROM members m JOIN member_levels ml ON ml.level_id = m.level_id
),
user_orders AS (
  SELECT ul.level_name, c.customer_unique_id, COUNT(DISTINCT o.order_id) AS order_cnt,
         SUM(oi.price + oi.freight_value) AS gmv
  FROM user_level ul
  JOIN customers c ON c.customer_unique_id = (
    SELECT m2.customer_unique_id FROM members m2 WHERE m2.member_id = ul.member_id
  )
  JOIN orders o ON o.customer_id = c.customer_id
  JOIN order_items oi ON oi.order_id = o.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1, 2
)
SELECT level_name, COUNT(*) AS users,
       ROUND(AVG(order_cnt), 2) AS orders_per_user,
       ROUND(AVG(gmv), 2) AS gmv_per_user
FROM user_orders GROUP BY 1 ORDER BY gmv_per_user DESC) q;
ROLLBACK;

-- T6-016 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH brand_products AS (
  SELECT b.brand_id, b.brand_name, pbm.product_id
  FROM brands b
  JOIN product_brand_map pbm ON pbm.brand_id = b.brand_id AND pbm.is_current
),
sales AS (
  SELECT bp.brand_id, COUNT(DISTINCT oi.product_id) AS sold_products,
         SUM(oi.price) AS sales
  FROM brand_products bp
  JOIN order_items oi ON oi.product_id = bp.product_id
  JOIN orders o ON o.order_id = oi.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1
),
total AS (
  SELECT brand_id, COUNT(*) AS product_cnt FROM brand_products GROUP BY 1
)
SELECT bp.brand_name, t.product_cnt, COALESCE(s.sold_products, 0) AS sold_products,
       ROUND(COALESCE(s.sales, 0), 2) AS sales,
       ROUND(100.0 * COALESCE(s.sold_products, 0) / t.product_cnt, 2) AS sell_through_pct
FROM brands bp
JOIN total t ON t.brand_id = bp.brand_id
LEFT JOIN sales s ON s.brand_id = bp.brand_id
ORDER BY sales DESC LIMIT 10) q;
ROLLBACK;

-- T6-018 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH user_orders AS (
  SELECT c.customer_unique_id,
         BOOL_OR(m.member_id IS NOT NULL) AS is_member,
         COUNT(DISTINCT o.order_id) AS order_cnt
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  LEFT JOIN members m ON m.customer_unique_id = c.customer_unique_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1
)
SELECT CASE WHEN is_member THEN 'member' ELSE 'non_member' END AS user_type,
       ROUND(100.0 * COUNT(*) FILTER (WHERE order_cnt >= 2) / COUNT(*), 2) AS repeat_rate_pct
FROM user_orders GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T6-021 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH user_gmv AS (
  SELECT c.customer_unique_id, SUM(oi.price + oi.freight_value) AS gmv
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  JOIN order_items oi ON oi.order_id = o.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1
),
ranked AS (
  SELECT gmv, NTILE(5) OVER (ORDER BY gmv DESC) AS quintile
  FROM user_gmv
)
SELECT quintile, ROUND(100.0 * SUM(gmv) / (SELECT SUM(gmv) FROM user_gmv), 2) AS gmv_pct
FROM ranked GROUP BY 1 ORDER BY 1) q;
ROLLBACK;

-- T6-023 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH anchor AS (
  SELECT MAX(moved_at) AS max_ts FROM inventory_movements
),
outbound AS (
  SELECT im.warehouse_id, SUM(im.quantity) AS out_qty
  FROM inventory_movements im, anchor a
  WHERE im.movement_type = 2
    AND im.moved_at > a.max_ts - INTERVAL '90 days'
    AND im.moved_at <= a.max_ts
  GROUP BY 1
),
stock AS (
  SELECT warehouse_id, SUM(quantity) AS stock_qty FROM inventory GROUP BY 1
)
SELECT w.warehouse_name, s.stock_qty, COALESCE(o.out_qty, 0) AS out_qty_90d,
       CASE WHEN COALESCE(o.out_qty, 0) > 0
            THEN ROUND(90.0 * s.stock_qty / o.out_qty, 1) END AS turnover_days
FROM warehouses w
JOIN stock s ON s.warehouse_id = w.warehouse_id
LEFT JOIN outbound o ON o.warehouse_id = w.warehouse_id
ORDER BY turnover_days DESC NULLS LAST) q;
ROLLBACK;

-- T6-025 original Gold; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(jsonb_agg(to_jsonb(q)),'[]'::jsonb) FROM (WITH first_order AS (
  SELECT c.customer_unique_id, MIN(o.order_purchase_timestamp) AS first_ts
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1
),
monthly AS (
  SELECT DATE_TRUNC('month', o.order_purchase_timestamp)::date AS month,
         c.customer_unique_id,
         SUM(oi.price + oi.freight_value) AS gmv
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  JOIN order_items oi ON oi.order_id = o.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
    AND o.order_purchase_timestamp >= DATE '2018-01-01'
    AND o.order_purchase_timestamp < DATE '2019-01-01'
  GROUP BY 1, 2
)
SELECT m.month,
       ROUND(SUM(m.gmv) FILTER (WHERE DATE_TRUNC('month', f.first_ts)::date = m.month), 2) AS new_customer_gmv,
       ROUND(SUM(m.gmv) FILTER (WHERE DATE_TRUNC('month', f.first_ts)::date <> m.month), 2) AS returning_gmv
FROM monthly m
JOIN first_order f ON f.customer_unique_id = m.customer_unique_id
GROUP BY 1 ORDER BY 1) q;
ROLLBACK;
