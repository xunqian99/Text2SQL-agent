BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT jsonb_build_object('id','T1-001','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM orders)g;
SELECT jsonb_build_object('id','T1-003','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM orders WHERE order_status = 'canceled')g;
SELECT jsonb_build_object('id','T1-005','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM order_items WHERE price > 1000)g;
SELECT jsonb_build_object('id','T1-007','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM sellers)g;
SELECT jsonb_build_object('id','T1-009','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM customers WHERE customer_state = 'SP')g;
SELECT jsonb_build_object('id','T1-010','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(DISTINCT order_status) FROM orders)g;
SELECT jsonb_build_object('id','T1-012','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT order_id, order_item_id, price
FROM order_items ORDER BY price DESC NULLS LAST,order_id,order_item_id LIMIT 10)g;
SELECT jsonb_build_object('id','T1-014','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM order_reviews WHERE review_score = 1)g;
SELECT jsonb_build_object('id','T1-016','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT order_id,payment_sequential,payment_installments
FROM order_payments WHERE payment_installments IS NOT NULL
ORDER BY payment_installments DESC,order_id,payment_sequential LIMIT 1)g;
SELECT jsonb_build_object('id','T1-018','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM order_items WHERE freight_value > 50)g;
SELECT jsonb_build_object('id','T1-021','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM sellers WHERE seller_state = 'RJ')g;
SELECT jsonb_build_object('id','T1-023','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM products WHERE product_category_name IS NULL)g;
SELECT jsonb_build_object('id','T1-025','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM members WHERE status = 2)g;
SELECT jsonb_build_object('id','T1-027','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM warehouses)g;
SELECT jsonb_build_object('id','T1-029','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM refunds WHERE status = 4)g;
SELECT jsonb_build_object('id','T1-030','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM refunds WHERE reason_code = 'QUAL')g;
SELECT jsonb_build_object('id','T1-032','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM coupons WHERE discount_type = 'fixed')g;
SELECT jsonb_build_object('id','T1-034','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM ticket_categories)g;
SELECT jsonb_build_object('id','T1-036','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM inventory_movements WHERE movement_type = 2)g;
SELECT jsonb_build_object('id','T1-038','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*) FROM regions WHERE macro_region = 'Sudeste')g;
SELECT jsonb_build_object('id','T2-002','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT SUM(freight_value) FROM order_items)g;
SELECT jsonb_build_object('id','T2-004','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ROUND(AVG(cnt),2) FROM (
 SELECT o.order_id,COUNT(oi.order_item_id) AS cnt
 FROM orders o LEFT JOIN order_items oi ON oi.order_id=o.order_id
 GROUP BY o.order_id
) t)g;
SELECT jsonb_build_object('id','T2-006','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT payment_type, COUNT(*) AS cnt
FROM order_payments GROUP BY payment_type ORDER BY cnt DESC)g;
SELECT jsonb_build_object('id','T2-008','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT customer_state, COUNT(*) AS cnt
FROM customers GROUP BY customer_state ORDER BY cnt DESC,customer_state ASC NULLS LAST LIMIT 5)g;
SELECT jsonb_build_object('id','T2-011','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT seller_id, ROUND(SUM(price), 2) AS total
FROM order_items GROUP BY seller_id ORDER BY total DESC NULLS LAST,seller_id LIMIT 10)g;
SELECT jsonb_build_object('id','T2-013','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT
  COUNT(*) FILTER (WHERE order_status = 'delivered') AS delivered,
  COUNT(*) FILTER (WHERE order_status = 'canceled')  AS canceled
FROM orders)g;
SELECT jsonb_build_object('id','T2-015','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT level_id, ROUND(AVG(points), 2) AS avg_points
FROM members GROUP BY level_id ORDER BY level_id)g;
SELECT jsonb_build_object('id','T2-017','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT macro_region, COUNT(*) AS cnt
FROM regions GROUP BY macro_region ORDER BY cnt DESC)g;
SELECT jsonb_build_object('id','T2-019','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT campaign_id, budget FROM campaigns ORDER BY budget DESC)g;
SELECT jsonb_build_object('id','T2-020','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT discount_type, COUNT(*) AS cnt
FROM coupons GROUP BY discount_type ORDER BY cnt DESC)g;
SELECT jsonb_build_object('id','T2-022','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ROUND(SUM(refund_amount), 2) FROM refunds)g;
SELECT jsonb_build_object('id','T2-024','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT warehouse_id, COUNT(*) AS cnt
FROM inventory GROUP BY warehouse_id ORDER BY warehouse_id)g;
SELECT jsonb_build_object('id','T2-026','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT warehouse_id, SUM(quantity) AS total_qty
FROM inventory GROUP BY warehouse_id ORDER BY warehouse_id)g;
SELECT jsonb_build_object('id','T2-028','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT carrier, COUNT(*) AS cnt
FROM shipments GROUP BY carrier ORDER BY cnt DESC)g;
SELECT jsonb_build_object('id','T2-031','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT priority, COUNT(*) AS cnt
FROM support_tickets GROUP BY priority ORDER BY priority)g;
SELECT jsonb_build_object('id','T2-033','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ROUND(SUM(net_amount), 2) FROM settlements)g;
SELECT jsonb_build_object('id','T2-035','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT keyword, COUNT(*) AS cnt
FROM search_logs GROUP BY keyword ORDER BY cnt DESC,keyword ASC NULLS LAST LIMIT 10)g;
SELECT jsonb_build_object('id','T2-037','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT c.customer_state,COUNT(*) AS cnt FROM orders o
JOIN customers c ON c.customer_id=o.customer_id
GROUP BY c.customer_state HAVING COUNT(*)>5000 ORDER BY cnt DESC)g;
SELECT jsonb_build_object('id','T2-039','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ROUND(COUNT(*) FILTER(WHERE review_score>=4)::numeric/NULLIF(COUNT(*),0),4) AS good_rate
FROM order_reviews)g;
SELECT jsonb_build_object('id','T2-040','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT payment_type, ROUND(SUM(payment_value), 2) AS total
FROM order_payments GROUP BY payment_type ORDER BY total DESC)g;
SELECT jsonb_build_object('id','T3-001','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('month', order_purchase_timestamp) AS month,
       COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-003','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('quarter', order_purchase_timestamp) AS quarter, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-005','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('week', order_purchase_timestamp)::DATE AS week_start, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY cnt DESC,week_start ASC NULLS LAST LIMIT 1)g;
SELECT jsonb_build_object('id','T3-007','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('month', o.order_purchase_timestamp) AS month,
       ROUND(SUM(oi.freight_value), 2) AS freight
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-009','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('month', order_purchase_timestamp) AS month,
       COUNT(*) AS cnt
FROM orders
WHERE order_purchase_timestamp >= '2018-01-01'
  AND order_purchase_timestamp < '2019-01-01'
GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-010','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH monthly AS (
 SELECT DATE_TRUNC('month',order_purchase_timestamp) AS m,COUNT(*) AS cnt
 FROM orders GROUP BY 1
), calendar AS (
 SELECT generate_series(MIN(m),MAX(m),INTERVAL '1 month') AS m FROM monthly
), filled AS (
 SELECT c.m,COALESCE(m.cnt,0) AS cnt FROM calendar c LEFT JOIN monthly m ON m.m=c.m
)
SELECT m AS month,cnt,cnt-LAG(cnt) OVER(ORDER BY m) AS mom_change FROM filled ORDER BY m)g;
SELECT jsonb_build_object('id','T3-012','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH monthly AS (
  SELECT EXTRACT(YEAR FROM order_purchase_timestamp)::INT AS yr,
         EXTRACT(MONTH FROM order_purchase_timestamp)::INT AS mo,
         COUNT(*) AS cnt
  FROM orders
  WHERE EXTRACT(YEAR FROM order_purchase_timestamp) IN (2017, 2018)
  GROUP BY 1, 2
)
SELECT cur.mo AS month,
       cur.cnt - prev.cnt AS yoy_change
FROM monthly cur
JOIN monthly prev ON prev.mo = cur.mo AND prev.yr = 2017
WHERE cur.yr = 2018
ORDER BY cur.mo)g;
SELECT jsonb_build_object('id','T3-014','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH anchor AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders)
SELECT DATE_TRUNC('week',o.order_purchase_timestamp)::date AS week_start,
 ROUND(SUM(oi.price),2) AS sales
FROM orders o JOIN order_items oi ON oi.order_id=o.order_id CROSS JOIN anchor a
WHERE o.order_purchase_timestamp>=a.mx::date-INTERVAL '89 days'
 AND o.order_purchase_timestamp<a.mx::date+INTERVAL '1 day'
GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-016','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT EXTRACT(DOW FROM order_purchase_timestamp)::INT AS dow, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY cnt DESC,dow ASC NULLS LAST LIMIT 1)g;
SELECT jsonb_build_object('id','T3-018','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT c.customer_state,
       ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)) / 86400), 2) AS avg_days
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_delivered_customer_date IS NOT NULL
GROUP BY 1 ORDER BY avg_days DESC)g;
SELECT jsonb_build_object('id','T3-021','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('month', requested_at) AS month, COUNT(*) AS cnt
FROM refunds GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-023','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('month', created_at) AS month, COUNT(*) AS cnt
FROM support_tickets GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-025','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT c.customer_state, COUNT(*) AS cnt
FROM orders o JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_purchase_timestamp >= '2018-01-01'
  AND o.order_purchase_timestamp < '2018-07-01'
GROUP BY 1 ORDER BY cnt DESC)g;
SELECT jsonb_build_object('id','T3-027','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('month', register_date) AS month, COUNT(*) AS cnt
FROM members WHERE register_date IS NOT NULL GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-029','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('month', period_start) AS month,
       ROUND(SUM(commission), 2) AS total
FROM settlements GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T3-030','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT DATE_TRUNC('quarter', order_purchase_timestamp) AS quarter,
       COUNT(*) AS cnt
FROM orders
WHERE order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T4-002','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT c.customer_state, ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY sales DESC)g;
SELECT jsonb_build_object('id','T4-004','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT t.product_category_name_english,ROUND(SUM(oi.price),2) AS sales
FROM order_items oi JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english
ORDER BY sales DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 1)g;
SELECT jsonb_build_object('id','T4-006','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT s.seller_state, ROUND(SUM(oi.price), 2) AS sales
FROM order_items oi JOIN sellers s ON s.seller_id = oi.seller_id
GROUP BY 1 ORDER BY sales DESC)g;
SELECT jsonb_build_object('id','T4-008','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*)
FROM members m
LEFT JOIN user_tag_map t ON t.member_id = m.member_id
WHERE t.member_id IS NULL)g;
SELECT jsonb_build_object('id','T4-011','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT o.order_status, ROUND(AVG(r.review_score), 2) AS avg_score
FROM orders o JOIN order_reviews r ON r.order_id = o.order_id
GROUP BY 1 ORDER BY avg_score DESC)g;
SELECT jsonb_build_object('id','T4-013','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT c.customer_state,COUNT(DISTINCT o.order_id) AS cnt FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id WHERE r.review_score IN (1,2) GROUP BY c.customer_state ORDER BY cnt DESC LIMIT 100)g;
SELECT jsonb_build_object('id','T4-015','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT r.macro_region, COUNT(*) AS cnt
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
GROUP BY 1 ORDER BY cnt DESC)g;
SELECT jsonb_build_object('id','T4-017','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT m.level_id, COUNT(*) AS cnt
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T4-019','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT t.tag_id,COUNT(DISTINCT m.member_id) AS cnt FROM user_tags t LEFT JOIN user_tag_map tm ON tm.tag_id=t.tag_id LEFT JOIN members m ON m.member_id=tm.member_id GROUP BY t.tag_id ORDER BY cnt DESC,t.tag_id LIMIT 100)g;
SELECT jsonb_build_object('id','T4-020','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(DISTINCT o.order_id) AS cnt
FROM user_tag_map tm
JOIN user_tags t ON t.tag_id = tm.tag_id
JOIN members m ON m.member_id = tm.member_id
JOIN customers c ON c.customer_unique_id = m.customer_unique_id
JOIN orders o ON o.customer_id = c.customer_id
WHERE t.tag_code = 'HIGH_VALUE')g;
SELECT jsonb_build_object('id','T4-022','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ROUND(SUM(discount_amount), 2) FROM coupon_usages)g;
SELECT jsonb_build_object('id','T4-024','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT w.warehouse_name,COUNT(s.shipment_id) AS cnt FROM warehouses w FULL JOIN shipments s ON s.warehouse_id=w.warehouse_id GROUP BY w.warehouse_name ORDER BY cnt DESC LIMIT 100)g;
SELECT jsonb_build_object('id','T4-026','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT carrier,
       ROUND(AVG(EXTRACT(EPOCH FROM (delivered_at - shipped_at)) / 86400), 2) AS avg_days
FROM shipments
WHERE delivered_at IS NOT NULL AND shipped_at IS NOT NULL
GROUP BY 1 ORDER BY avg_days)g;
SELECT jsonb_build_object('id','T4-028','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COALESCE(st.category_id,tc.category_id) AS category_id,ROUND((AVG(EXTRACT(EPOCH FROM(st.resolved_at-st.created_at))/3600.0))::numeric,2) AS avg_hours FROM ticket_categories tc FULL JOIN support_tickets st ON st.category_id=tc.category_id GROUP BY COALESCE(st.category_id,tc.category_id) ORDER BY avg_hours DESC NULLS LAST LIMIT 100)g;
SELECT jsonb_build_object('id','T4-031','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT s.seller_state, ROUND(SUM(st.net_amount), 2) AS total
FROM settlements st JOIN sellers s ON s.seller_id = st.seller_id
GROUP BY 1 ORDER BY total DESC)g;
SELECT jsonb_build_object('id','T4-033','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT reason_code, ROUND(SUM(refund_amount), 2) AS total
FROM refunds GROUP BY 1 ORDER BY total DESC)g;
SELECT jsonb_build_object('id','T4-035','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH order_totals AS (SELECT order_id,SUM(price+freight_value) AS amount FROM order_items GROUP BY order_id),per_person AS (SELECT c.customer_unique_id,ROUND(SUM(t.amount)/NULLIF(COUNT(o.order_id),0),2) AS avg_order_value FROM orders o JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN order_totals t ON t.order_id=o.order_id WHERE o.order_status NOT IN ('canceled','unavailable') GROUP BY c.customer_unique_id) SELECT customer_unique_id,avg_order_value FROM per_person WHERE avg_order_value IS NOT NULL ORDER BY avg_order_value DESC,customer_unique_id LIMIT 10)g;
SELECT jsonb_build_object('id','T4-037','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT p.product_id, p.product_photos_qty, COUNT(*) AS sold
FROM order_items oi JOIN products p ON p.product_id = oi.product_id
GROUP BY 1, 2 ORDER BY sold DESC NULLS LAST,p.product_id LIMIT 10)g;
SELECT jsonb_build_object('id','T4-039','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ROUND(COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL)::numeric/NULLIF(COUNT(*),0),4) AS ctr FROM search_logs)g;
SELECT jsonb_build_object('id','T4-040','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(*)
FROM products p
LEFT JOIN inventory i ON i.product_id = p.product_id
WHERE i.product_id IS NULL)g;
SELECT jsonb_build_object('id','T4-042','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT COUNT(DISTINCT product_id) FROM product_price_history WHERE new_price > old_price)g;
SELECT jsonb_build_object('id','T4-044','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT r.macro_region,COUNT(DISTINCT c.customer_id) AS customers,ROUND(COUNT(o.order_id)::numeric/NULLIF(COUNT(DISTINCT c.customer_unique_id),0),2) AS orders_per_customer FROM customers c LEFT JOIN regions r ON r.region_code=c.customer_state LEFT JOIN orders o ON o.customer_id=c.customer_id GROUP BY r.macro_region ORDER BY customers DESC LIMIT 100)g;
SELECT jsonb_build_object('id','T5-001','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT r.macro_region, ml.level_name, ROUND(SUM(oi.price), 2) AS gmv
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN members m ON m.customer_unique_id = c.customer_unique_id
JOIN member_levels ml ON ml.level_id = m.level_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1, 2 ORDER BY gmv DESC)g;
SELECT jsonb_build_object('id','T5-003','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT t.product_category_name_english,ROUND(SUM(ri.refund_value),2) AS refund_amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english
ORDER BY refund_amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 15)g;
SELECT jsonb_build_object('id','T5-005','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH campaign_orders AS (
 SELECT DISTINCT cp.campaign_id, cu.order_id
 FROM coupon_usages cu JOIN coupons cp ON cp.coupon_id=cu.coupon_id
 JOIN orders o ON o.order_id=cu.order_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
), item_totals AS (
 SELECT order_id,SUM(price) AS sales FROM order_items GROUP BY order_id
)
SELECT ca.campaign_id,COUNT(co.order_id) AS used_orders,ROUND(SUM(it.sales),2) AS gmv
FROM campaigns ca LEFT JOIN campaign_orders co ON co.campaign_id=ca.campaign_id
LEFT JOIN item_totals it ON it.order_id=co.order_id
GROUP BY ca.campaign_id ORDER BY gmv DESC NULLS LAST,ca.campaign_id LIMIT 10)g;
SELECT jsonb_build_object('id','T5-007','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT sl.keyword,t.product_category_name_english,COUNT(*) AS clicks
FROM search_logs sl JOIN products p ON p.product_id=sl.clicked_product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE sl.clicked_product_id IS NOT NULL AND p.product_category_name IS NOT NULL
GROUP BY sl.keyword,t.product_category_name_english ORDER BY clicks DESC,sl.keyword ASC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 20)g;
SELECT jsonb_build_object('id','T5-009','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT w.warehouse_id,w.region_code,pbm.brand_id,COUNT(*) AS low_stock_items
FROM inventory i JOIN warehouses w ON w.warehouse_id=i.warehouse_id
LEFT JOIN product_brand_map pbm ON pbm.product_id=i.product_id AND pbm.is_current
WHERE i.quantity<i.safety_stock
GROUP BY w.warehouse_id,w.region_code,pbm.brand_id
ORDER BY low_stock_items DESC,w.warehouse_id,w.region_code,pbm.brand_id ASC NULLS LAST LIMIT 20)g;
SELECT jsonb_build_object('id','T5-010','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT st.seller_id,COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
 ROUND(SUM(si.commission_amount),2) AS commission
FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
LEFT JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
GROUP BY st.seller_id ORDER BY commission DESC NULLS LAST,st.seller_id LIMIT 20)g;
SELECT jsonb_build_object('id','T5-012','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ut.tag_id,COUNT(DISTINCT m.member_id) AS members,ROUND(AVG(oi.price),2) AS avg_item_price
FROM user_tags ut LEFT JOIN user_tag_map utm ON utm.tag_id=ut.tag_id
LEFT JOIN members m ON m.member_id=utm.member_id
LEFT JOIN customers c ON c.customer_unique_id=m.customer_unique_id
LEFT JOIN orders o ON o.customer_id=c.customer_id
LEFT JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY ut.tag_id ORDER BY members DESC)g;
SELECT jsonb_build_object('id','T5-014','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH ranked AS (
 SELECT ms.*,ROW_NUMBER() OVER(PARTITION BY member_id ORDER BY snapshot_date DESC,snapshot_id DESC) AS rn
 FROM member_snapshot ms WHERE snapshot_date IS NOT NULL
)
SELECT ml_cur.level_name AS current_level,ml_snap.level_name AS snapshot_level,
 COUNT(DISTINCT m.member_id) AS member_cnt
FROM ranked ms
JOIN members m ON m.member_id=ms.member_id
LEFT JOIN member_levels ml_cur ON ml_cur.level_id=m.level_id
LEFT JOIN member_levels ml_snap ON ml_snap.level_id=ms.level_id
WHERE ms.rn=1 AND ms.level_id<>m.level_id
GROUP BY ml_cur.level_name,ml_snap.level_name ORDER BY member_cnt DESC)g;
SELECT jsonb_build_object('id','T5-016','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT t.product_category_name_english,r.reason_code,
 COUNT(DISTINCT r.refund_id) AS refunds,ROUND(SUM(ri.refund_value),2) AS amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english,r.reason_code
ORDER BY amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST,r.reason_code ASC NULLS LAST LIMIT 20)g;
SELECT jsonb_build_object('id','T5-018','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT sa.state AS addr_state, c.customer_state, COUNT(DISTINCT o.order_id) AS orders
FROM shipping_addresses sa
JOIN customers c ON c.customer_id = sa.customer_id
JOIN orders o ON o.customer_id = c.customer_id
WHERE sa.is_default
GROUP BY 1, 2 ORDER BY orders DESC,1 ASC NULLS LAST,2 ASC NULLS LAST LIMIT 20)g;
SELECT jsonb_build_object('id','T6-002','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH valid_orders AS (
 SELECT o.order_id,c.customer_unique_id FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
), item_totals AS (
 SELECT order_id,SUM(price+freight_value) AS gmv FROM order_items GROUP BY order_id
)
SELECT COUNT(*) AS valid_orders,COUNT(DISTINCT vo.customer_unique_id) AS buyers,
 ROUND(SUM(it.gmv)/NULLIF(COUNT(*),0),2) AS aov
FROM valid_orders vo LEFT JOIN item_totals it ON it.order_id=vo.order_id)g;
SELECT jsonb_build_object('id','T6-004','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH first_order AS (
  SELECT c.customer_unique_id, MIN(o.order_purchase_timestamp) AS first_ts
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1
),
cohort AS (
  SELECT customer_unique_id, DATE_TRUNC('month', first_ts)::date AS cohort_month
  FROM first_order
  WHERE first_ts >= DATE '2017-01-01' AND first_ts < DATE '2017-07-01'
),
returner AS (
  SELECT DISTINCT co.customer_unique_id, co.cohort_month
  FROM cohort co
  JOIN customers c ON c.customer_unique_id = co.customer_unique_id
  JOIN orders o ON o.customer_id = c.customer_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
    AND o.order_purchase_timestamp >= DATE '2017-07-01'
    AND o.order_purchase_timestamp < DATE '2018-01-01'
)
SELECT co.cohort_month,
       ROUND(100.0 * COUNT(re.customer_unique_id) / COUNT(*), 2) AS retention_pct
FROM cohort co
LEFT JOIN returner re ON re.customer_unique_id = co.customer_unique_id
GROUP BY 1 ORDER BY 1)g;
SELECT jsonb_build_object('id','T6-006','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ROUND(100.0 * COUNT(*) FILTER (
         WHERE o.order_delivered_customer_date <= o.order_estimated_delivery_date
       ) / COUNT(*), 2) AS on_time_pct
FROM orders o
WHERE o.order_delivered_customer_date IS NOT NULL)g;
SELECT jsonb_build_object('id','T6-008','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH sold AS (
  SELECT COUNT(DISTINCT oi.product_id) AS n
  FROM order_items oi
  JOIN orders o ON o.order_id = oi.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
),
allp AS (SELECT COUNT(*) AS n FROM products)
SELECT ROUND(100.0 * s.n / a.n, 2) AS sell_through_pct
FROM sold s CROSS JOIN allp a)g;
SELECT jsonb_build_object('id','T6-011','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT r.macro_region,
       ROUND(100.0 * SUM(oi.freight_value) / SUM(oi.price + oi.freight_value), 2) AS freight_pct
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY freight_pct DESC)g;
SELECT jsonb_build_object('id','T6-013','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH coupon_totals AS (
 SELECT order_id,SUM(discount_amount) AS discount_amount FROM coupon_usages GROUP BY order_id
), order_totals AS (
 SELECT order_id,SUM(price+freight_value) AS order_gmv FROM order_items GROUP BY order_id
)
SELECT ROUND(SUM(ot.order_gmv),2) AS gmv,ROUND(SUM(ct.discount_amount),2) AS discount,
 ROUND(100.0*SUM(ct.discount_amount)/NULLIF(SUM(ot.order_gmv),0),2) AS discount_pct
FROM coupon_totals ct JOIN orders o ON o.order_id=ct.order_id
JOIN order_totals ot ON ot.order_id=ct.order_id
WHERE o.order_status NOT IN ('canceled','unavailable'))g;
SELECT jsonb_build_object('id','T6-015','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT st.category_id,ROUND(100.0*COUNT(*) FILTER(
 WHERE st.resolved_at IS NOT NULL
 AND EXTRACT(EPOCH FROM(st.resolved_at-st.created_at))/3600.0<=tc.sla_hours
)/NULLIF(COUNT(*),0),2) AS sla_pct
FROM support_tickets st LEFT JOIN ticket_categories tc ON tc.category_id=st.category_id
GROUP BY st.category_id ORDER BY sla_pct)g;
SELECT jsonb_build_object('id','T6-017','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE converted_at IS NOT NULL) / COUNT(*), 2) AS conversion_pct,
       ROUND(SUM(reward_amount) / COUNT(*) FILTER (WHERE converted_at IS NOT NULL), 2) AS reward_per_converted
FROM referrals)g;
SELECT jsonb_build_object('id','T6-019','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (SELECT c.customer_state,
       ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)) / 86400.0), 2) AS avg_days
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_delivered_customer_date IS NOT NULL
GROUP BY 1 ORDER BY avg_days DESC NULLS LAST,c.customer_state ASC NULLS LAST LIMIT 10)g;
SELECT jsonb_build_object('id','T6-020','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH seller_rate AS (
 SELECT st.seller_id,SUM(si.gross_amount) AS gross,SUM(si.commission_amount) AS commission,
 SUM(si.commission_amount)/NULLIF(SUM(si.gross_amount),0) AS rate
 FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id GROUP BY st.seller_id
)
SELECT ROUND(SUM(commission)/NULLIF(SUM(gross),0),4) AS platform_rate,
 ROUND(MIN(rate),4) AS min_seller_rate,ROUND(AVG(rate),4) AS avg_seller_rate,
 ROUND(MAX(rate),4) AS max_seller_rate FROM seller_rate)g;
SELECT jsonb_build_object('id','T6-022','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH monthly AS (
 SELECT EXTRACT(YEAR FROM o.order_purchase_timestamp)::int AS yr,
 EXTRACT(MONTH FROM o.order_purchase_timestamp)::int AS mo,SUM(oi.price+oi.freight_value) AS gmv
 FROM orders o JOIN order_items oi ON oi.order_id=o.order_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
 AND o.order_purchase_timestamp>=DATE '2017-01-01' AND o.order_purchase_timestamp<DATE '2019-01-01'
 GROUP BY 1,2
), months AS (SELECT generate_series(1,12) AS mo)
SELECT m.mo,ROUND(m17.gmv,2) AS gmv_2017,ROUND(m18.gmv,2) AS gmv_2018,
 ROUND(100.0*(m18.gmv-m17.gmv)/NULLIF(m17.gmv,0),2) AS yoy_pct
FROM months m LEFT JOIN monthly m17 ON m17.mo=m.mo AND m17.yr=2017
LEFT JOIN monthly m18 ON m18.mo=m.mo AND m18.yr=2018 ORDER BY m.mo)g;
SELECT jsonb_build_object('id','T6-024','rows',COALESCE(jsonb_agg(to_jsonb(g)),'[]'::jsonb)) FROM (WITH reason_orders AS (SELECT DISTINCT reason_code,order_id FROM refunds)
SELECT r.reason_code,ROUND(AVG(rev.review_score),2) AS avg_score,
 ROUND(100.0*COUNT(*) FILTER(WHERE rev.review_score<=2)/NULLIF(COUNT(*),0),2) AS low_score_pct
FROM reason_orders r JOIN order_reviews rev ON rev.order_id=r.order_id
GROUP BY r.reason_code ORDER BY low_score_pct DESC)g;
ROLLBACK;
