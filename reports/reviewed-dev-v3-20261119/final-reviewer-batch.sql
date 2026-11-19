BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('kind','gold','id','T1-002','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM orders
WHERE order_purchase_timestamp >= '2018-01-01'
  AND order_purchase_timestamp < '2019-01-01')q));
SELECT json_build_object('kind','gold','id','T1-004','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(DISTINCT customer_unique_id) FROM customers)q));
SELECT json_build_object('kind','gold','id','T1-006','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM products)q));
SELECT json_build_object('kind','gold','id','T1-008','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(DISTINCT customer_city) FROM customers)q));
SELECT json_build_object('kind','gold','id','T1-011','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(DISTINCT product_category_name) FROM products)q));
SELECT json_build_object('kind','gold','id','T1-013','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT order_id, order_item_id, freight_value
FROM order_items ORDER BY freight_value DESC LIMIT 5)q));
SELECT json_build_object('kind','gold','id','T1-015','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM order_payments WHERE payment_type = 'credit_card')q));
SELECT json_build_object('kind','gold','id','T1-017','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM products WHERE product_weight_g > 5000)q));
SELECT json_build_object('kind','gold','id','T1-019','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM orders WHERE order_delivered_customer_date IS NULL)q));
SELECT json_build_object('kind','gold','id','T1-020','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM order_reviews WHERE review_comment_title IS NOT NULL AND BTRIM(review_comment_title)<>'')q));
SELECT json_build_object('kind','gold','id','T1-022','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM order_items WHERE price < 10)q));
SELECT json_build_object('kind','gold','id','T1-024','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM members m JOIN member_levels l ON l.level_id=m.level_id WHERE l.level_name='黄金会员')q));
SELECT json_build_object('kind','gold','id','T1-026','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT member_id,points FROM members WHERE points IS NOT NULL ORDER BY points DESC,member_id ASC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T1-028','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT warehouse_id,capacity FROM warehouses WHERE capacity IS NOT NULL ORDER BY capacity DESC,warehouse_id ASC LIMIT 1)q));
SELECT json_build_object('kind','gold','id','T1-031','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM coupons)q));
SELECT json_build_object('kind','gold','id','T1-033','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM support_tickets WHERE priority = 4)q));
SELECT json_build_object('kind','gold','id','T1-035','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM inventory WHERE quantity < safety_stock)q));
SELECT json_build_object('kind','gold','id','T1-037','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(DISTINCT keyword) FROM search_logs)q));
SELECT json_build_object('kind','gold','id','T1-039','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM campaigns WHERE status = 3)q));
SELECT json_build_object('kind','gold','id','T1-040','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(DISTINCT product_id) FROM product_price_history)q));
SELECT json_build_object('kind','gold','id','T2-001','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT SUM(price) FROM order_items)q));
SELECT json_build_object('kind','gold','id','T2-003','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(AVG(price), 2) FROM order_items)q));
SELECT json_build_object('kind','gold','id','T2-005','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT order_status, COUNT(*) AS cnt
FROM orders GROUP BY order_status ORDER BY cnt DESC)q));
SELECT json_build_object('kind','gold','id','T2-007','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT review_score, COUNT(*) AS cnt
FROM order_reviews GROUP BY review_score ORDER BY review_score)q));
SELECT json_build_object('kind','gold','id','T2-009','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT product_category_name, COUNT(*) AS cnt
FROM products
WHERE product_category_name IS NOT NULL
GROUP BY product_category_name ORDER BY cnt DESC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T2-010','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT seller_state, COUNT(*) AS cnt
FROM sellers GROUP BY seller_state ORDER BY cnt DESC)q));
SELECT json_build_object('kind','gold','id','T2-012','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT seller_id, ROUND(SUM(freight_value), 2) AS total
FROM order_items GROUP BY seller_id ORDER BY total DESC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T2-014','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT
  COUNT(*) FILTER (WHERE payment_type = 'credit_card') AS credit_card,
  COUNT(*) FILTER (WHERE payment_type = 'boleto')      AS boleto
FROM order_payments)q));
SELECT json_build_object('kind','gold','id','T2-016','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT status, COUNT(*) AS cnt FROM members GROUP BY status ORDER BY status)q));
SELECT json_build_object('kind','gold','id','T2-018','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT campaign_type, COUNT(*) AS cnt
FROM campaigns GROUP BY campaign_type ORDER BY cnt DESC)q));
SELECT json_build_object('kind','gold','id','T2-021','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT reason_code, COUNT(*) AS cnt
FROM refunds GROUP BY reason_code ORDER BY cnt DESC)q));
SELECT json_build_object('kind','gold','id','T2-023','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(SUM(refund_amount), 2) FROM refunds WHERE status = 4)q));
SELECT json_build_object('kind','gold','id','T2-025','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT SUM(quantity) FROM inventory)q));
SELECT json_build_object('kind','gold','id','T2-027','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT movement_type, COUNT(*) AS cnt
FROM inventory_movements GROUP BY movement_type ORDER BY movement_type)q));
SELECT json_build_object('kind','gold','id','T2-029','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT status, COUNT(*) AS cnt FROM shipments GROUP BY status ORDER BY status)q));
SELECT json_build_object('kind','gold','id','T2-030','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT category_id, COUNT(*) AS cnt
FROM support_tickets GROUP BY category_id ORDER BY category_id)q));
SELECT json_build_object('kind','gold','id','T2-032','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT status, COUNT(*) AS cnt FROM settlements GROUP BY status ORDER BY status)q));
SELECT json_build_object('kind','gold','id','T2-034','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(SUM(commission), 2) FROM settlements)q));
SELECT json_build_object('kind','gold','id','T2-036','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT product_category_name, COUNT(*) AS cnt
FROM products
WHERE product_category_name IS NOT NULL
GROUP BY product_category_name
HAVING COUNT(*) > 1500
ORDER BY cnt DESC)q));
SELECT json_build_object('kind','gold','id','T2-038','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT MAX(price) AS max_price, MIN(price) AS min_price FROM order_items)q));
SELECT json_build_object('kind','gold','id','T3-002','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT EXTRACT(YEAR FROM order_purchase_timestamp)::INT AS yr, COUNT(*) AS cnt
FROM orders
WHERE EXTRACT(YEAR FROM order_purchase_timestamp) IN (2017, 2018)
GROUP BY 1 ORDER BY 1)q));
SELECT json_build_object('kind','gold','id','T3-004','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM orders
WHERE order_purchase_timestamp >= '2017-11-01'
  AND order_purchase_timestamp < '2017-12-01')q));
SELECT json_build_object('kind','gold','id','T3-006','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT DATE_TRUNC('month', o.order_purchase_timestamp) AS month,
       ROUND(SUM(oi.price), 2) AS sales
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1)q));
SELECT json_build_object('kind','gold','id','T3-008','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT DATE_TRUNC('month', o.order_purchase_timestamp) AS month,
       ROUND(SUM(oi.price) / COUNT(DISTINCT o.order_id), 2) AS avg_order_value
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1)q));
SELECT json_build_object('kind','gold','id','T3-011','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH monthly AS(SELECT DATE_TRUNC('month',order_purchase_timestamp) AS m,COUNT(*) AS cnt FROM orders WHERE order_purchase_timestamp IS NOT NULL GROUP BY 1),calendar AS(SELECT generate_series(MIN(m),MAX(m),INTERVAL '1 month') AS m FROM monthly),aligned AS(SELECT c.m,m.cnt FROM calendar c LEFT JOIN monthly m USING(m)) SELECT m AS month,ROUND((cnt-LAG(cnt) OVER(ORDER BY m))::numeric/NULLIF(LAG(cnt) OVER(ORDER BY m),0),4) AS mom_rate FROM aligned ORDER BY m)q));
SELECT json_build_object('kind','gold','id','T3-013','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH anchor AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders) SELECT COUNT(*) FROM orders o CROSS JOIN anchor a WHERE o.order_purchase_timestamp>=a.mx::date-INTERVAL '89 days' AND o.order_purchase_timestamp<a.mx::date+INTERVAL '1 day')q));
SELECT json_build_object('kind','gold','id','T3-015','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT EXTRACT(HOUR FROM order_purchase_timestamp)::INT AS hr, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY cnt DESC LIMIT 1)q));
SELECT json_build_object('kind','gold','id','T3-017','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(AVG(EXTRACT(EPOCH FROM (order_delivered_customer_date - order_purchase_timestamp)) / 86400), 2)
FROM orders WHERE order_delivered_customer_date IS NOT NULL)q));
SELECT json_build_object('kind','gold','id','T3-019','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM orders
WHERE order_delivered_customer_date > order_estimated_delivery_date)q));
SELECT json_build_object('kind','gold','id','T3-020','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(
  COUNT(*) FILTER (WHERE order_delivered_customer_date > order_estimated_delivery_date)::NUMERIC
  / NULLIF(COUNT(*) FILTER (WHERE order_delivered_customer_date IS NOT NULL), 0), 4) AS late_rate
FROM orders)q));
SELECT json_build_object('kind','gold','id','T3-022','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT DATE_TRUNC('month', requested_at) AS month,
       ROUND(SUM(refund_amount), 2) AS total
FROM refunds GROUP BY 1 ORDER BY 1)q));
SELECT json_build_object('kind','gold','id','T3-024','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600), 2)
FROM support_tickets WHERE resolved_at IS NOT NULL)q));
SELECT json_build_object('kind','gold','id','T3-026','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT DATE_TRUNC('month', review_creation_date) AS month,
       ROUND(AVG(review_score), 2) AS avg_score
FROM order_reviews GROUP BY 1 ORDER BY 1)q));
SELECT json_build_object('kind','gold','id','T3-028','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*) FROM members
WHERE last_login_date > (SELECT MAX(last_login_date) FROM members) - 30)q));
SELECT json_build_object('kind','gold','id','T4-001','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.customer_unique_id,COUNT(*) AS cnt FROM orders o JOIN customers c ON c.customer_id=o.customer_id GROUP BY c.customer_unique_id ORDER BY cnt DESC,c.customer_unique_id ASC NULLS LAST LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T4-003','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT p.product_category_name, COUNT(DISTINCT oi.order_id) AS order_cnt
FROM order_items oi JOIN products p ON p.product_id = oi.product_id
WHERE p.product_category_name IS NOT NULL
GROUP BY 1 ORDER BY order_cnt DESC)q));
SELECT json_build_object('kind','gold','id','T4-005','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT oi.seller_id, COUNT(DISTINCT oi.order_id) AS order_cnt
FROM order_items oi GROUP BY 1 ORDER BY order_cnt DESC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T4-007','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT COUNT(*)
FROM products p
LEFT JOIN product_price_history h ON h.product_id = p.product_id
WHERE h.product_id IS NULL)q));
SELECT json_build_object('kind','gold','id','T4-009','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT payment_type,ROUND(AVG(payment_value),2) AS avg_value FROM order_payments GROUP BY payment_type ORDER BY avg_value DESC NULLS LAST,payment_type ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T4-010','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT o.order_status, ROUND(SUM(oi.price), 2) AS sales
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY sales DESC)q));
SELECT json_build_object('kind','gold','id','T4-012','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.customer_state,ROUND(AVG(r.review_score),2) AS avg_score FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id GROUP BY c.customer_state ORDER BY COUNT(*) DESC,c.customer_state ASC NULLS LAST LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T4-014','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH order_types AS(SELECT DISTINCT order_id,payment_type FROM order_payments) SELECT p.payment_type,ROUND(AVG(r.review_score),2) AS avg_score FROM order_types p JOIN order_reviews r ON r.order_id=p.order_id GROUP BY p.payment_type ORDER BY avg_score DESC NULLS LAST,p.payment_type ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T4-016','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT r.macro_region,ROUND(SUM(oi.price),2) AS sales
FROM order_items oi JOIN orders o ON o.order_id=oi.order_id
LEFT JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
GROUP BY r.macro_region ORDER BY sales DESC NULLS LAST,r.macro_region ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T4-018','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT m.level_id, ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1)q));
SELECT json_build_object('kind','gold','id','T4-021','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.campaign_id,COUNT(*) AS cnt FROM coupon_usages cu JOIN coupons cp ON cp.coupon_id=cu.coupon_id JOIN campaigns c ON c.campaign_id=cp.campaign_id GROUP BY c.campaign_id ORDER BY cnt DESC,c.campaign_id ASC)q));
SELECT json_build_object('kind','gold','id','T4-023','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.discount_type, ROUND(SUM(cu.discount_amount), 2) AS total
FROM coupon_usages cu JOIN coupons c ON c.coupon_id = cu.coupon_id
GROUP BY 1 ORDER BY total DESC)q));
SELECT json_build_object('kind','gold','id','T4-025','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT r.macro_region, SUM(w.capacity) AS total_capacity
FROM warehouses w JOIN regions r ON r.region_code = w.region_code
GROUP BY 1 ORDER BY total_capacity DESC)q));
SELECT json_build_object('kind','gold','id','T4-027','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT i.warehouse_id,SUM(i.quantity) AS total_qty,ROUND(AVG(i.quantity),2) AS avg_qty FROM inventory i GROUP BY i.warehouse_id ORDER BY total_qty DESC NULLS LAST,i.warehouse_id ASC)q));
SELECT json_build_object('kind','gold','id','T4-029','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.customer_state,COUNT(*) AS cnt FROM support_tickets st LEFT JOIN orders o ON o.order_id=st.order_id LEFT JOIN customers c ON c.customer_id=o.customer_id GROUP BY c.customer_state ORDER BY cnt DESC,c.customer_state ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T4-030','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT seller_id, ROUND(SUM(total_amount), 2) AS total
FROM settlements GROUP BY 1 ORDER BY total DESC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T4-032','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT p.product_category_name,COUNT(DISTINCT ri.refund_id) AS cnt
FROM refund_items ri JOIN refunds rf ON rf.refund_id=ri.refund_id AND rf.order_id=ri.order_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
WHERE p.product_category_name IS NOT NULL
GROUP BY p.product_category_name ORDER BY cnt DESC,p.product_category_name ASC)q));
SELECT json_build_object('kind','gold','id','T4-034','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(COUNT(*) FILTER(WHERE EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=o.order_id))::numeric/NULLIF(COUNT(*),0),4) AS refund_rate FROM orders o)q));
SELECT json_build_object('kind','gold','id','T4-036','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.customer_state,
       ROUND(SUM(oi.price) / COUNT(DISTINCT c.customer_unique_id), 2) AS avg_per_customer
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY avg_per_customer DESC)q));
SELECT json_build_object('kind','gold','id','T4-038','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT m.level_id, COUNT(DISTINCT m.member_id) AS cnt
FROM members m
JOIN user_tag_map tm ON tm.member_id = m.member_id
JOIN user_tags t ON t.tag_id = tm.tag_id
WHERE t.tag_code = 'CHURN_RISK'
GROUP BY 1 ORDER BY 1)q));
SELECT json_build_object('kind','gold','id','T4-041','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH products_by_brand AS(SELECT b.brand_id,p.product_id FROM brands b LEFT JOIN product_brand_map bm ON bm.brand_id=b.brand_id AND bm.is_current IS TRUE LEFT JOIN products p ON p.product_id=bm.product_id) SELECT b.brand_id,COUNT(DISTINCT b.product_id) AS product_cnt,ROUND(SUM(oi.price),2) AS sales FROM products_by_brand b LEFT JOIN order_items oi ON oi.product_id=b.product_id GROUP BY b.brand_id ORDER BY sales DESC NULLS LAST,b.brand_id ASC)q));
SELECT json_build_object('kind','gold','id','T4-043','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH per_shipment AS(SELECT s.shipment_id,s.status,COUNT(t.tracking_id) AS cnt FROM shipments s LEFT JOIN shipment_tracking t ON t.shipment_id=s.shipment_id GROUP BY s.shipment_id,s.status) SELECT status,ROUND(AVG(cnt),2) AS avg_events FROM per_shipment GROUP BY status ORDER BY status ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T4-045','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT product_category_name,ROUND(AVG(product_weight_g),2) AS avg_weight FROM products WHERE product_category_name IS NOT NULL GROUP BY product_category_name ORDER BY COUNT(*) DESC,product_category_name ASC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T5-002','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT m.level_id,ROUND(SUM(oi.price),2) AS sales FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN members m ON m.customer_unique_id=c.customer_unique_id JOIN order_items oi ON oi.order_id=o.order_id GROUP BY m.level_id ORDER BY sales DESC NULLS LAST,m.level_id ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T5-004','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT w.region_code,COUNT(*) AS shipments,COUNT(DISTINCT s.order_id) AS orders FROM shipments s LEFT JOIN warehouses w ON w.warehouse_id=s.warehouse_id GROUP BY w.region_code ORDER BY shipments DESC,w.region_code ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T5-006','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.customer_state,m.level_id,COUNT(*) AS tickets FROM support_tickets st JOIN ticket_categories tc ON tc.category_id=st.category_id LEFT JOIN orders o ON o.order_id=st.order_id LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN members m ON m.member_id=st.member_id WHERE tc.category_name='物流延迟' GROUP BY c.customer_state,m.level_id ORDER BY tickets DESC,c.customer_state ASC NULLS LAST,m.level_id ASC NULLS LAST LIMIT 20)q));
SELECT json_build_object('kind','gold','id','T5-008','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT mr.level_id AS referrer_level,mii.level_id AS invitee_level,COUNT(*) AS referrals,ROUND(SUM(rf.reward_amount),2) AS reward FROM referrals rf LEFT JOIN members mr ON mr.member_id=rf.referrer_member_id LEFT JOIN members mii ON mii.member_id=rf.invitee_member_id GROUP BY mr.level_id,mii.level_id ORDER BY referrals DESC,mr.level_id ASC NULLS LAST,mii.level_id ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T5-011','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT bm.brand_id,ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN product_brand_map bm ON bm.product_id=oi.product_id AND bm.is_current IS TRUE GROUP BY bm.brand_id ORDER BY sales DESC NULLS LAST,bm.brand_id ASC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T5-013','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT cp.campaign_id,COUNT(DISTINCT oi.order_id) AS orders,ROUND(SUM(oi.price),2) AS sales
FROM campaign_products cp JOIN order_items oi ON oi.product_id=cp.product_id
GROUP BY cp.campaign_id ORDER BY sales DESC NULLS LAST,cp.campaign_id ASC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T5-015','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT st.event_code,c.customer_state,COUNT(DISTINCT s.shipment_id) AS shipments FROM shipment_tracking st JOIN shipments s ON s.shipment_id=st.shipment_id JOIN orders o ON o.order_id=s.order_id JOIN customers c ON c.customer_id=o.customer_id GROUP BY st.event_code,c.customer_state ORDER BY shipments DESC,st.event_code ASC NULLS LAST,c.customer_state ASC NULLS LAST LIMIT 20)q));
SELECT json_build_object('kind','gold','id','T5-017','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT st.category_id,st.priority,COUNT(DISTINCT st.ticket_id) AS tickets,COUNT(tm.message_id) AS messages FROM support_tickets st LEFT JOIN ticket_messages tm ON tm.ticket_id=st.ticket_id GROUP BY st.category_id,st.priority ORDER BY tickets DESC,st.category_id ASC NULLS LAST,st.priority ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T5-019','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH changed AS(
 SELECT p.product_category_name,pph.product_id,pph.new_price-pph.old_price AS delta
 FROM product_price_history pph JOIN products p ON p.product_id=pph.product_id
 WHERE p.product_category_name IS NOT NULL),
means AS(SELECT product_category_name,ROUND(AVG(delta),2) AS avg_price_change FROM changed GROUP BY 1),
orders AS(SELECT x.product_category_name,COUNT(DISTINCT oi.order_id) AS orders
 FROM(SELECT DISTINCT product_category_name,product_id FROM changed)x
 LEFT JOIN order_items oi ON oi.product_id=x.product_id GROUP BY 1)
SELECT m.product_category_name,m.avg_price_change,o.orders FROM means m JOIN orders o USING(product_category_name)
ORDER BY o.orders DESC,m.product_category_name ASC LIMIT 20)q));
SELECT json_build_object('kind','gold','id','T5-020','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT r.macro_region,im.movement_type,COUNT(*) AS movements,SUM(im.quantity) AS qty FROM inventory_movements im LEFT JOIN warehouses w ON w.warehouse_id=im.warehouse_id LEFT JOIN regions r ON r.region_code=w.region_code GROUP BY r.macro_region,im.movement_type ORDER BY movements DESC,r.macro_region ASC NULLS LAST,im.movement_type ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T6-001','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(SUM(oi.price + oi.freight_value), 2) AS gmv_with_freight,
       ROUND(SUM(oi.price), 2) AS sales_without_freight
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
  AND o.order_purchase_timestamp >= DATE '2018-01-01'
  AND o.order_purchase_timestamp < DATE '2019-01-01')q));
SELECT json_build_object('kind','gold','id','T6-003','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH per_user AS(SELECT c.customer_unique_id,COUNT(DISTINCT o.order_id) AS order_cnt FROM orders o JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_unique_id) SELECT ROUND(100.0*COUNT(*) FILTER(WHERE order_cnt>=2)/NULLIF(COUNT(*),0),2) AS repeat_rate_pct FROM per_user)q));
SELECT json_build_object('kind','gold','id','T6-005','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(100.0*COUNT(DISTINCT o.order_id) FILTER(WHERE m.member_id IS NOT NULL)/NULLIF(COUNT(DISTINCT o.order_id),0),2) AS penetration_pct FROM orders o LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN members m ON m.customer_unique_id=c.customer_unique_id WHERE o.order_status NOT IN('canceled','unavailable'))q));
SELECT json_build_object('kind','gold','id','T6-007','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(100.0*COUNT(DISTINCT o.order_id) FILTER(WHERE EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=o.order_id))/NULLIF(COUNT(DISTINCT o.order_id),0),2) AS refund_rate_pct FROM orders o WHERE o.order_status NOT IN('canceled','unavailable'))q));
SELECT json_build_object('kind','gold','id','T6-009','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.customer_state,
       ROUND(100.0 * COUNT(*) FILTER (WHERE r.review_score <= 2) / COUNT(*), 2) AS low_pct
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY low_pct DESC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T6-010','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT ROUND(100.0*COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL)/NULLIF(COUNT(*),0),2) AS ctr_pct FROM search_logs)q));
SELECT json_build_object('kind','gold','id','T6-012','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH monthly AS(SELECT DATE_TRUNC('month',o.order_purchase_timestamp)::date AS month,COUNT(DISTINCT c.customer_unique_id) AS mau FROM orders o JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') AND o.order_purchase_timestamp>=DATE '2018-01-01' AND o.order_purchase_timestamp<DATE '2019-01-01' GROUP BY 1),calendar AS(SELECT generate_series(DATE '2018-01-01',DATE '2018-12-01',INTERVAL '1 month')::date AS month) SELECT c.month,m.mau FROM calendar c LEFT JOIN monthly m USING(month) ORDER BY c.month)q));
SELECT json_build_object('kind','gold','id','T6-014','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH per_member AS(SELECT m.member_id,m.level_id,COUNT(DISTINCT o.order_id) AS order_cnt,SUM(oi.price+oi.freight_value) AS gmv FROM members m LEFT JOIN customers c ON c.customer_unique_id=m.customer_unique_id LEFT JOIN orders o ON o.customer_id=c.customer_id AND o.order_status NOT IN('canceled','unavailable') LEFT JOIN order_items oi ON oi.order_id=o.order_id GROUP BY m.member_id,m.level_id) SELECT level_id,COUNT(*) AS users,ROUND(AVG(order_cnt),2) AS orders_per_user,ROUND(AVG(gmv),2) AS gmv_per_user FROM per_member GROUP BY level_id ORDER BY gmv_per_user DESC NULLS LAST,level_id ASC NULLS LAST)q));
SELECT json_build_object('kind','gold','id','T6-016','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH bp AS(SELECT b.brand_id,p.product_id FROM brands b
 LEFT JOIN product_brand_map bm ON bm.brand_id=b.brand_id AND bm.is_current IS TRUE
 LEFT JOIN products p ON p.product_id=bm.product_id),
totals AS(SELECT brand_id,COUNT(DISTINCT product_id) AS product_cnt FROM bp GROUP BY 1),
sales AS(SELECT bp.brand_id,COUNT(DISTINCT oi.product_id) AS sold_products,SUM(oi.price) AS sales
 FROM bp JOIN order_items oi ON oi.product_id=bp.product_id JOIN orders o ON o.order_id=oi.order_id
 WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY 1)
SELECT t.brand_id,t.product_cnt,COALESCE(s.sold_products,0) AS sold_products,ROUND(s.sales,2) AS sales,
 ROUND(100.0*COALESCE(s.sold_products,0)/NULLIF(t.product_cnt,0),2) AS sell_through_pct
FROM totals t JOIN sales s USING(brand_id) WHERE s.sales IS NOT NULL
ORDER BY sales DESC,t.brand_id ASC LIMIT 10)q));
SELECT json_build_object('kind','gold','id','T6-018','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH user_orders AS(SELECT c.customer_unique_id,
 BOOL_OR(m.member_id IS NOT NULL) AS is_member,COUNT(DISTINCT o.order_id) AS order_cnt
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 LEFT JOIN members m ON m.customer_unique_id=c.customer_unique_id
 WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY 1),
groups(is_member,user_type) AS(VALUES(TRUE,'member'),(FALSE,'non_member'))
SELECT g.user_type,ROUND(100.0*COUNT(u.customer_unique_id) FILTER(WHERE u.order_cnt>=2)
 /NULLIF(COUNT(u.customer_unique_id),0),2) AS repeat_rate_pct
FROM groups g LEFT JOIN user_orders u ON u.is_member=g.is_member
GROUP BY g.user_type ORDER BY g.user_type)q));
SELECT json_build_object('kind','gold','id','T6-021','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH user_gmv AS(SELECT c.customer_unique_id,SUM(oi.price+oi.freight_value) AS gmv FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN order_items oi ON oi.order_id=o.order_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_unique_id),ranked AS(SELECT gmv,ROW_NUMBER() OVER(ORDER BY ROUND(gmv,2) DESC,customer_unique_id ASC) AS rn,COUNT(*) OVER() AS n FROM user_gmv) SELECT ROUND(SUM(gmv) FILTER(WHERE rn<=CEIL(n*0.2))/NULLIF(SUM(gmv),0),4) AS top20_gmv_ratio FROM ranked)q));
SELECT json_build_object('kind','gold','id','T6-023','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH anchor AS(SELECT MAX(moved_at) AS mx FROM inventory_movements),outbound AS(SELECT im.warehouse_id,SUM(im.quantity) AS out_qty FROM inventory_movements im CROSS JOIN anchor a WHERE im.movement_type=2 AND im.moved_at>=a.mx::date-INTERVAL '89 days' AND im.moved_at<a.mx::date+INTERVAL '1 day' GROUP BY im.warehouse_id),stock AS(SELECT warehouse_id,SUM(quantity) AS stock_qty FROM inventory GROUP BY warehouse_id),combined AS(SELECT w.warehouse_id,s.stock_qty,CASE WHEN a.mx IS NULL THEN NULL ELSE COALESCE(o.out_qty,0) END AS out_qty FROM warehouses w CROSS JOIN anchor a LEFT JOIN stock s ON s.warehouse_id=w.warehouse_id LEFT JOIN outbound o ON o.warehouse_id=w.warehouse_id) SELECT warehouse_id,stock_qty,out_qty AS out_qty_90d,ROUND(90.0*stock_qty/NULLIF(out_qty,0),1) AS turnover_days FROM combined ORDER BY turnover_days DESC NULLS LAST,warehouse_id ASC)q));
SELECT json_build_object('kind','gold','id','T6-025','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH first_order AS(SELECT c.customer_unique_id,DATE_TRUNC('month',MIN(o.order_purchase_timestamp))::date AS first_month FROM orders o JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_unique_id),monthly AS(SELECT DATE_TRUNC('month',o.order_purchase_timestamp)::date AS month,c.customer_unique_id,SUM(oi.price+oi.freight_value) AS gmv FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN order_items oi ON oi.order_id=o.order_id WHERE o.order_status NOT IN('canceled','unavailable') AND o.order_purchase_timestamp>=DATE '2018-01-01' AND o.order_purchase_timestamp<DATE '2019-01-01' GROUP BY 1,2),summed AS(SELECT m.month,ROUND(SUM(m.gmv) FILTER(WHERE f.first_month=m.month),2) AS new_customer_gmv,ROUND(SUM(m.gmv) FILTER(WHERE f.first_month<m.month),2) AS returning_gmv FROM monthly m JOIN first_order f ON f.customer_unique_id=m.customer_unique_id GROUP BY m.month),calendar AS(SELECT generate_series(DATE '2018-01-01',DATE '2018-12-01',INTERVAL '1 month')::date AS month) SELECT c.month,s.new_customer_gmv,s.returning_gmv FROM calendar c LEFT JOIN summed s USING(month) ORDER BY c.month)q));
SELECT json_build_object('kind','risk','id','R00-runtime','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT current_date, current_user, version(), current_setting('transaction_read_only') AS read_only,
 current_setting('TimeZone') AS timezone, current_setting('statement_timeout') AS timeout)q));
SELECT json_build_object('kind','risk','id','R01-actual-ddl','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.relname AS table_name,
 (SELECT json_agg(json_build_object('name',a.attname,'type',format_type(a.atttypid,a.atttypmod),'not_null',a.attnotnull) ORDER BY a.attnum)
 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped) AS columns,
 (SELECT json_agg(json_build_object('name',x.conname,'definition',pg_get_constraintdef(x.oid)) ORDER BY x.conname)
 FROM pg_constraint x WHERE x.conrelid=c.oid) AS constraints
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relkind='r'
 AND c.relname IN ('customers','sellers','products','orders','order_items','order_payments','order_reviews','members','member_levels','user_tags','user_tag_map','regions','campaigns','coupons','coupon_usages','campaign_products','referrals','warehouses','shipments','shipment_tracking','inventory','inventory_movements','support_tickets','ticket_categories','ticket_messages','refunds','refund_items','settlements','settlement_items','brands','product_brand_map','product_price_history','search_logs')
 ORDER BY c.relname)q));
SELECT json_build_object('kind','risk','id','R02-topn-boundaries','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH scores(case_id,identity,score,k) AS(
 SELECT 'T1-013',order_id||':'||order_item_id,freight_value,5 FROM order_items
 UNION ALL SELECT 'T2-009',product_category_name,COUNT(*)::numeric,10 FROM products WHERE product_category_name IS NOT NULL GROUP BY product_category_name
 UNION ALL SELECT 'T2-012',seller_id,ROUND(SUM(freight_value),2),10 FROM order_items GROUP BY seller_id
 UNION ALL SELECT 'T3-015',EXTRACT(HOUR FROM order_purchase_timestamp)::int::text,COUNT(*)::numeric,1 FROM orders GROUP BY 2
 UNION ALL SELECT 'T4-005',seller_id,COUNT(DISTINCT order_id)::numeric,10 FROM order_items GROUP BY seller_id
 UNION ALL SELECT 'T4-030',seller_id,ROUND(SUM(total_amount),2),10 FROM settlements GROUP BY seller_id
 UNION ALL SELECT 'T6-009',c.customer_state,ROUND(100.0*COUNT(*) FILTER(WHERE r.review_score<=2)/COUNT(*),2),10 FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_state
 ),ranked AS(SELECT *,ROW_NUMBER() OVER(PARTITION BY case_id ORDER BY score DESC NULLS LAST,identity ASC NULLS LAST) AS rn FROM scores),cuts AS(SELECT case_id,MAX(k) AS k,MAX(score) FILTER(WHERE rn=k) AS boundary FROM ranked GROUP BY case_id)
 SELECT c.case_id,c.k,c.boundary,COUNT(*) FILTER(WHERE s.score>c.boundary) AS above_boundary,COUNT(*) FILTER(WHERE s.score=c.boundary) AS at_boundary,COUNT(*) FILTER(WHERE s.score IS NULL) AS unknown_scores,
 (SELECT json_agg(x) FROM(SELECT r.score,COUNT(*) AS tied_candidates FROM ranked r WHERE r.case_id=c.case_id AND r.score>=c.boundary GROUP BY r.score HAVING COUNT(*)>1 ORDER BY r.score DESC)x) AS ties_relevant_to_topn
 FROM cuts c JOIN scores s USING(case_id) GROUP BY c.case_id,c.k,c.boundary ORDER BY c.case_id)q));
SELECT json_build_object('kind','risk','id','R03-join-coverage','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT 'warehouse_missing_region' AS risk,COUNT(*) AS rows,SUM(w.capacity)::numeric AS amount FROM warehouses w LEFT JOIN regions r ON r.region_code=w.region_code WHERE r.region_code IS NULL
 UNION ALL SELECT 'shipments_missing_order',COUNT(*),NULL FROM shipments s LEFT JOIN orders o ON o.order_id=s.order_id WHERE o.order_id IS NULL
 UNION ALL SELECT 'tracked_shipments_missing_order',COUNT(DISTINCT s.shipment_id),NULL FROM shipment_tracking t JOIN shipments s ON s.shipment_id=t.shipment_id LEFT JOIN orders o ON o.order_id=s.order_id WHERE o.order_id IS NULL
 UNION ALL SELECT 'tracking_missing_shipment',COUNT(*),NULL FROM shipment_tracking t LEFT JOIN shipments s ON s.shipment_id=t.shipment_id WHERE s.shipment_id IS NULL
 UNION ALL SELECT 'unbranded_order_items',COUNT(*),SUM(oi.price) FROM order_items oi WHERE NOT EXISTS(SELECT 1 FROM product_brand_map bm WHERE bm.product_id=oi.product_id AND bm.is_current IS TRUE)
 UNION ALL SELECT 'multiple_current_brands',COUNT(*),NULL FROM(SELECT product_id FROM product_brand_map WHERE is_current IS TRUE GROUP BY product_id HAVING COUNT(*)>1)x
 UNION ALL SELECT 'brandmap_missing_product',COUNT(*),NULL FROM product_brand_map bm LEFT JOIN products p ON p.product_id=bm.product_id WHERE p.product_id IS NULL
 UNION ALL SELECT 'brands_without_known_valid_sales',COUNT(*),NULL FROM brands b WHERE NOT EXISTS(SELECT 1 FROM product_brand_map bm JOIN order_items oi ON oi.product_id=bm.product_id JOIN orders o ON o.order_id=oi.order_id WHERE bm.brand_id=b.brand_id AND bm.is_current IS TRUE AND o.order_status NOT IN('canceled','unavailable')))q));
SELECT json_build_object('kind','risk','id','R04-relative-login','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT MIN(last_login_date) AS min_login,MAX(last_login_date) AS reference_day,
 COUNT(*) FILTER(WHERE last_login_date>(SELECT MAX(last_login_date) FROM members)-30) AS max_anchored,
 COUNT(*) FILTER(WHERE last_login_date>=current_date-29 AND last_login_date<current_date+1) AS current_anchored,
 COUNT(*) FILTER(WHERE last_login_date IS NULL) AS unknown_login FROM members)q));
SELECT json_build_object('kind','risk','id','R05-calendar-gaps','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH months AS(SELECT DATE_TRUNC('month',order_purchase_timestamp) AS m,COUNT(*) AS n FROM orders WHERE order_purchase_timestamp IS NOT NULL GROUP BY 1),cal AS(SELECT generate_series(MIN(m),MAX(m),INTERVAL '1 month') AS m FROM months) SELECT c.m,mo.n FROM cal c LEFT JOIN months mo USING(m) WHERE mo.n IS NULL ORDER BY c.m)q));
SELECT json_build_object('kind','risk','id','R06-fact-grains','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT 'orders' AS fact,COUNT(*) AS rows,COUNT(DISTINCT order_id) AS distinct_ids FROM orders
 UNION ALL SELECT 'reviews',COUNT(*),COUNT(DISTINCT order_id) FROM order_reviews
 UNION ALL SELECT 'payments',COUNT(*),COUNT(DISTINCT order_id) FROM order_payments
 UNION ALL SELECT 'members_no_valid_order',COUNT(*),NULL FROM members m WHERE NOT EXISTS(SELECT 1 FROM customers c JOIN orders o ON o.customer_id=c.customer_id WHERE c.customer_unique_id=m.customer_unique_id AND o.order_status NOT IN('canceled','unavailable'))
 UNION ALL SELECT 'shipments_no_tracking',COUNT(*),NULL FROM shipments s WHERE NOT EXISTS(SELECT 1 FROM shipment_tracking t WHERE t.shipment_id=s.shipment_id)
 UNION ALL SELECT 'coupons_multiple_use_records_per_order',COUNT(*),NULL FROM(SELECT order_id FROM coupon_usages GROUP BY order_id HAVING COUNT(*)>1)x
 UNION ALL SELECT 'null_movement_quantity',COUNT(*),NULL FROM inventory_movements WHERE quantity IS NULL
 UNION ALL SELECT 'null_settlement_amount',COUNT(*),NULL FROM settlements WHERE total_amount IS NULL
 UNION ALL SELECT 'null_order_timestamp',COUNT(*),NULL FROM orders WHERE order_purchase_timestamp IS NULL
 UNION ALL SELECT 'null_customer_identity',COUNT(*),NULL FROM customers WHERE customer_unique_id IS NULL)q));
SELECT json_build_object('kind','risk','id','R07-review-unit-difference','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH per_state AS(SELECT c.customer_state,COUNT(*) AS reviews,COUNT(DISTINCT o.order_id) AS reviewed_orders,
 COUNT(*) FILTER(WHERE r.review_score<=2) AS low_reviews,COUNT(DISTINCT o.order_id) FILTER(WHERE r.review_score<=2) AS low_orders
 FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_state)
 SELECT *,ROUND(100.0*low_reviews/reviews,2) AS review_rate,ROUND(100.0*low_orders/reviewed_orders,2) AS reviewed_order_rate FROM per_state WHERE ROUND(100.0*low_reviews/reviews,2)<>ROUND(100.0*low_orders/reviewed_orders,2) ORDER BY customer_state)q));
SELECT json_build_object('kind','risk','id','R08-consumption-components','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH payment_totals AS(SELECT order_id,SUM(payment_value) AS paid FROM order_payments GROUP BY order_id),item_totals AS(SELECT order_id,SUM(price) AS merchandise,SUM(price+freight_value) AS gmv FROM order_items GROUP BY order_id) SELECT m.level_id,ROUND(SUM(i.merchandise),2) AS merchandise_all_status,ROUND(SUM(i.gmv),2) AS with_freight_all_status,ROUND(SUM(p.paid),2) AS paid_all_status FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN members m ON m.customer_unique_id=c.customer_unique_id LEFT JOIN item_totals i ON i.order_id=o.order_id LEFT JOIN payment_totals p ON p.order_id=o.order_id GROUP BY m.level_id ORDER BY m.level_id)q));
SELECT json_build_object('kind','risk','id','R09-window90','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH a AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders) SELECT a.mx,COUNT(*) FILTER(WHERE o.order_purchase_timestamp>=a.mx::date-INTERVAL '89 days' AND o.order_purchase_timestamp<a.mx::date+INTERVAL '1 day') AS natural_90,COUNT(*) FILTER(WHERE o.order_purchase_timestamp>a.mx-INTERVAL '90 days' AND o.order_purchase_timestamp<=a.mx) AS rolling_90 FROM orders o CROSS JOIN a GROUP BY a.mx)q));
SELECT json_build_object('kind','risk','id','R10-fixture-topn-tie','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH order_items(order_id,order_item_id,freight_value) AS(VALUES('b',1,10.00::numeric),('a',2,10.00),('a',1,10.00)),chosen AS(SELECT order_id,order_item_id,freight_value FROM order_items ORDER BY freight_value DESC LIMIT 2) SELECT * FROM chosen)q));
SELECT json_build_object('kind','risk','id','R11-fixture-warehouse-loss','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH warehouses(warehouse_id,region_code,capacity) AS(VALUES(1,NULL::text,100)),regions(region_code,macro_region) AS(VALUES('SP','Southeast')),candidate AS(SELECT r.macro_region,SUM(w.capacity) AS total_capacity FROM warehouses w JOIN regions r ON r.region_code=w.region_code GROUP BY r.macro_region) SELECT 'input_warehouses' AS measure,(SELECT COUNT(*) FROM warehouses) AS n UNION ALL SELECT 'gold_output_groups',COUNT(*) FROM candidate)q));
SELECT json_build_object('kind','risk','id','R12-fixture-tracked-shipment-loss','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH shipment_tracking(tracking_id,shipment_id,event_code) AS(VALUES(1,1,'DELIVERED')),shipments(shipment_id,order_id) AS(VALUES(1,'missing')),orders(order_id,customer_id) AS(SELECT NULL::text,NULL::text WHERE FALSE),customers(customer_id,customer_state) AS(SELECT NULL::text,NULL::text WHERE FALSE),candidate AS(SELECT st.event_code,c.customer_state,COUNT(DISTINCT s.shipment_id) AS shipments FROM shipment_tracking st JOIN shipments s ON s.shipment_id=st.shipment_id JOIN orders o ON o.order_id=s.order_id JOIN customers c ON c.customer_id=o.customer_id GROUP BY st.event_code,c.customer_state) SELECT 'input_tracked_shipments' AS measure,COUNT(*) AS n FROM shipments UNION ALL SELECT 'gold_output_groups',COUNT(*) FROM candidate)q));
SELECT json_build_object('kind','risk','id','R13-fixture-unknown-outbound','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH inventory_movements(warehouse_id,quantity,movement_type,moved_at) AS(VALUES(1,NULL::int,2,TIMESTAMP '2018-01-30 10:00:00')),outbound AS(SELECT warehouse_id,COUNT(*) AS records,SUM(quantity) AS out_qty FROM inventory_movements WHERE movement_type=2 GROUP BY warehouse_id) SELECT warehouse_id,records,out_qty AS known_quantity_sum,COALESCE(out_qty,0) AS candidate_quantity FROM outbound)q));
SELECT json_build_object('kind','risk','id','R14-fixture-unknown-settlement-ranking','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH settlements(seller_id,total_amount) AS(VALUES('unknown',NULL::numeric),('known',100.00::numeric)) SELECT seller_id,ROUND(SUM(total_amount),2) AS total FROM settlements GROUP BY seller_id ORDER BY total DESC LIMIT 1)q));
ROLLBACK;
