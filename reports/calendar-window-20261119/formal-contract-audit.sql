BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('name','T4-019-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT t.tag_name, COUNT(DISTINCT m.member_id) AS cnt
FROM user_tag_map m JOIN user_tags t ON t.tag_id = m.tag_id
GROUP BY 1 ORDER BY cnt DESC
) q;
SELECT json_build_object('name','T4-019-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT t.tag_id, COUNT(DISTINCT m.member_id) AS cnt
FROM user_tag_map m JOIN user_tags t ON t.tag_id = m.tag_id
GROUP BY 1 ORDER BY cnt DESC
) q;
SELECT json_build_object('name','T4-028-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT tc.category_name,
       ROUND(AVG(EXTRACT(EPOCH FROM (st.resolved_at - st.created_at)) / 3600), 2) AS avg_hours
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
WHERE st.resolved_at IS NOT NULL
GROUP BY 1 ORDER BY avg_hours DESC
) q;
SELECT json_build_object('name','T4-028-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT tc.category_id,
       ROUND(AVG(EXTRACT(EPOCH FROM (st.resolved_at - st.created_at)) / 3600), 2) AS avg_hours
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
WHERE st.resolved_at IS NOT NULL
GROUP BY 1 ORDER BY avg_hours DESC
) q;
SELECT json_build_object('name','T5-005-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT ca.campaign_name, COUNT(DISTINCT cu.order_id) AS used_orders, ROUND(SUM(oi.price), 2) AS gmv
FROM coupon_usages cu
JOIN coupons cp ON cp.coupon_id = cu.coupon_id
JOIN campaigns ca ON ca.campaign_id = cp.campaign_id
JOIN orders o ON o.order_id = cu.order_id
JOIN order_items oi ON oi.order_id = cu.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY gmv DESC LIMIT 10
) q;
SELECT json_build_object('name','T5-005-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT ca.campaign_id, COUNT(DISTINCT cu.order_id) AS used_orders, ROUND(SUM(oi.price), 2) AS gmv
FROM coupon_usages cu
JOIN coupons cp ON cp.coupon_id = cu.coupon_id
JOIN campaigns ca ON ca.campaign_id = cp.campaign_id
JOIN orders o ON o.order_id = cu.order_id
JOIN order_items oi ON oi.order_id = cu.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY gmv DESC LIMIT 10
) q;
SELECT json_build_object('name','T5-009-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT w.warehouse_name, r.region_name, b.brand_name, COUNT(*) AS low_stock_items
FROM inventory i
JOIN warehouses w ON w.warehouse_id = i.warehouse_id
JOIN regions r ON r.region_code = w.region_code
JOIN product_brand_map pbm ON pbm.product_id = i.product_id AND pbm.is_current
JOIN brands b ON b.brand_id = pbm.brand_id
WHERE i.quantity < i.safety_stock
GROUP BY 1, 2, 3 ORDER BY low_stock_items DESC LIMIT 20
) q;
SELECT json_build_object('name','T5-009-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT w.warehouse_id, r.region_code, b.brand_id, COUNT(*) AS low_stock_items
FROM inventory i
JOIN warehouses w ON w.warehouse_id = i.warehouse_id
JOIN regions r ON r.region_code = w.region_code
JOIN product_brand_map pbm ON pbm.product_id = i.product_id AND pbm.is_current
JOIN brands b ON b.brand_id = pbm.brand_id
WHERE i.quantity < i.safety_stock
GROUP BY 1, 2, 3 ORDER BY low_stock_items DESC LIMIT 20
) q;
SELECT json_build_object('name','T5-012-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT ut.tag_name, COUNT(DISTINCT m.member_id) AS members, ROUND(AVG(oi.price), 2) AS avg_item_price
FROM user_tag_map utm
JOIN user_tags ut ON ut.tag_id = utm.tag_id
JOIN members m ON m.member_id = utm.member_id
LEFT JOIN customers c ON c.customer_unique_id = m.customer_unique_id
LEFT JOIN orders o ON o.customer_id = c.customer_id
LEFT JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY members DESC
) q;
SELECT json_build_object('name','T5-012-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT ut.tag_id, COUNT(DISTINCT m.member_id) AS members, ROUND(AVG(oi.price), 2) AS avg_item_price
FROM user_tag_map utm
JOIN user_tags ut ON ut.tag_id = utm.tag_id
JOIN members m ON m.member_id = utm.member_id
LEFT JOIN customers c ON c.customer_unique_id = m.customer_unique_id
LEFT JOIN orders o ON o.customer_id = c.customer_id
LEFT JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY members DESC
) q;
SELECT json_build_object('name','T6-015-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT tc.category_name,
       ROUND(100.0 * COUNT(*) FILTER (
         WHERE st.resolved_at IS NOT NULL
           AND EXTRACT(EPOCH FROM (st.resolved_at - st.created_at)) / 3600.0 <= tc.sla_hours
       ) / COUNT(*), 2) AS sla_pct
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
GROUP BY 1 ORDER BY sla_pct
) q;
SELECT json_build_object('name','T6-015-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT tc.category_id,
       ROUND(100.0 * COUNT(*) FILTER (
         WHERE st.resolved_at IS NOT NULL
           AND EXTRACT(EPOCH FROM (st.resolved_at - st.created_at)) / 3600.0 <= tc.sla_hours
       ) / COUNT(*), 2) AS sla_pct
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
GROUP BY 1 ORDER BY sla_pct
) q;
SELECT json_build_object('name','T4-013-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT c.customer_state, COUNT(*) AS cnt
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
WHERE r.review_score <= 2
GROUP BY 1 ORDER BY cnt DESC
) q;
SELECT json_build_object('name','T4-013-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT c.customer_state, COUNT(DISTINCT o.order_id) AS cnt
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
WHERE r.review_score <= 2
GROUP BY 1 ORDER BY cnt DESC
) q;
SELECT json_build_object('name','T6-013-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT ROUND(SUM(oi.price + oi.freight_value), 2) AS gmv,
       ROUND(SUM(cu.discount_amount), 2) AS discount,
       ROUND(100.0 * SUM(cu.discount_amount) / SUM(oi.price + oi.freight_value), 2) AS discount_pct
FROM coupon_usages cu
JOIN orders o ON o.order_id = cu.order_id
JOIN order_items oi ON oi.order_id = cu.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
) q;
SELECT json_build_object('name','T6-013-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH ct AS(SELECT order_id,SUM(discount_amount) AS discount FROM coupon_usages GROUP BY 1),
    ot AS(SELECT order_id,SUM(price+freight_value) AS gmv FROM order_items GROUP BY 1)
    SELECT ROUND(SUM(ot.gmv),2) AS gmv,ROUND(SUM(ct.discount),2) AS discount,
           ROUND(100.0*SUM(ct.discount)/NULLIF(SUM(ot.gmv),0),2) AS discount_pct
    FROM ct JOIN orders o USING(order_id) JOIN ot USING(order_id)
    WHERE o.order_status NOT IN ('canceled','unavailable')
) q;
SELECT json_build_object('name','T3-014-gold','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH anchor AS (SELECT MAX(order_purchase_timestamp) AS mx FROM orders)
SELECT DATE_TRUNC('week', o.order_purchase_timestamp)::DATE AS week_start,
       ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
CROSS JOIN anchor a
WHERE o.order_purchase_timestamp > a.mx - INTERVAL '90 days'
  AND o.order_purchase_timestamp <= a.mx
GROUP BY 1 ORDER BY 1
) q;
SELECT json_build_object('name','T3-014-contract','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH anchor AS (SELECT MAX(order_purchase_timestamp) AS mx FROM orders)
SELECT DATE_TRUNC('week', o.order_purchase_timestamp)::DATE AS week_start,
       ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
CROSS JOIN anchor a
WHERE o.order_purchase_timestamp >= a.mx::date - 89
  AND o.order_purchase_timestamp < a.mx::date + 1
GROUP BY 1 ORDER BY 1
) q;
SELECT json_build_object('name','review-grain','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT COUNT(*) AS review_rows,COUNT(DISTINCT order_id) AS distinct_orders FROM order_reviews WHERE review_score<=2
) q;
SELECT json_build_object('name','coupon-campaign-multiplicity','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT COUNT(*) AS duplicate_campaign_order_pairs FROM(SELECT cp.campaign_id,cu.order_id FROM coupon_usages cu JOIN coupons cp USING(coupon_id) GROUP BY 1,2 HAVING COUNT(*)>1)t
) q;
SELECT json_build_object('name','snapshot-multiplicity','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT COUNT(*) AS rows,COUNT(DISTINCT member_id) AS members,COUNT(*) FILTER(WHERE snapshot_date IS NULL) AS null_dates FROM member_snapshot
) q;
SELECT json_build_object('name','search-click-coverage','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT COUNT(*) AS clicked,COUNT(*) FILTER(WHERE m.member_id IS NULL) AS missing_member,COUNT(*) FILTER(WHERE ml.level_id IS NULL) AS missing_level FROM search_logs sl LEFT JOIN members m USING(member_id) LEFT JOIN member_levels ml ON ml.level_id=m.level_id WHERE sl.clicked_product_id IS NOT NULL
) q;
SELECT json_build_object('name','customer-order-coincidence','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT (SELECT COUNT(*) FROM customers) AS customers,(SELECT COUNT(*) FROM orders) AS orders,(SELECT COUNT(*) FROM customers c WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.customer_id=c.customer_id)) AS without_orders,(SELECT COUNT(*) FROM(SELECT customer_id FROM orders GROUP BY 1 HAVING COUNT(*)>1)t) AS multiple_orders
) q;
SELECT json_build_object('name','registration-null','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT COUNT(*) FILTER(WHERE register_date IS NULL) AS null_registration FROM members
) q;
SELECT json_build_object('name','order-item-population','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT COUNT(*) AS all_orders,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=o.order_id)) AS without_items FROM orders o
) q;
SELECT json_build_object('name','display-name-uniqueness','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
SELECT 'user_tags' AS entity,COUNT(*) AS rows,COUNT(DISTINCT tag_name) AS names FROM user_tags UNION ALL SELECT 'ticket_categories',COUNT(*),COUNT(DISTINCT category_name) FROM ticket_categories UNION ALL SELECT 'warehouses',COUNT(*),COUNT(DISTINCT warehouse_name) FROM warehouses UNION ALL SELECT 'campaigns',COUNT(*),COUNT(DISTINCT campaign_name) FROM campaigns UNION ALL SELECT 'member_levels',COUNT(*),COUNT(DISTINCT level_name) FROM member_levels
) q;
SELECT json_build_object('name','T1-012-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT order_id, order_item_id, price
FROM order_items), cutoff AS(
      SELECT price AS value FROM candidates ORDER BY price DESC OFFSET 9 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE price>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE price=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T1-016-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT order_id, payment_installments
FROM order_payments), cutoff AS(
      SELECT payment_installments AS value FROM candidates ORDER BY payment_installments DESC OFFSET 0 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE payment_installments>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE payment_installments=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T2-008-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT customer_state, COUNT(*) AS cnt
FROM customers GROUP BY customer_state), cutoff AS(
      SELECT cnt AS value FROM candidates ORDER BY cnt DESC OFFSET 4 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE cnt>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE cnt=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T2-011-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT seller_id, ROUND(SUM(price), 2) AS total
FROM order_items GROUP BY seller_id), cutoff AS(
      SELECT total AS value FROM candidates ORDER BY total DESC OFFSET 9 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE total>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE total=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T2-035-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT keyword, COUNT(*) AS cnt
FROM search_logs GROUP BY keyword), cutoff AS(
      SELECT cnt AS value FROM candidates ORDER BY cnt DESC OFFSET 9 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE cnt>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE cnt=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T3-005-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT DATE_TRUNC('week', order_purchase_timestamp)::DATE AS week_start, COUNT(*) AS cnt
FROM orders GROUP BY 1), cutoff AS(
      SELECT cnt AS value FROM candidates ORDER BY cnt DESC OFFSET 0 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE cnt>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE cnt=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T3-016-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT EXTRACT(DOW FROM order_purchase_timestamp)::INT AS dow, COUNT(*) AS cnt
FROM orders GROUP BY 1), cutoff AS(
      SELECT cnt AS value FROM candidates ORDER BY cnt DESC OFFSET 0 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE cnt>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE cnt=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T4-004-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT t.product_category_name_english, ROUND(SUM(oi.price), 2) AS sales
FROM order_items oi
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1), cutoff AS(
      SELECT sales AS value FROM candidates ORDER BY sales DESC OFFSET 0 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE sales>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE sales=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T4-035-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT c.customer_unique_id,
       ROUND(SUM(oi.price + oi.freight_value) / COUNT(DISTINCT o.order_id), 2) AS avg_order_value
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1), cutoff AS(
      SELECT avg_order_value AS value FROM candidates ORDER BY avg_order_value DESC OFFSET 9 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE avg_order_value>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE avg_order_value=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T4-037-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT p.product_id, p.product_photos_qty, COUNT(*) AS sold
FROM order_items oi JOIN products p ON p.product_id = oi.product_id
GROUP BY 1, 2), cutoff AS(
      SELECT sold AS value FROM candidates ORDER BY sold DESC OFFSET 9 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE sold>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE sold=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T5-003-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT t.product_category_name_english, ROUND(SUM(ri.refund_value), 2) AS refund_amt
FROM refunds r
JOIN refund_items ri ON ri.refund_id = r.refund_id
JOIN order_items oi ON oi.order_id = ri.order_id AND oi.order_item_id = ri.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1), cutoff AS(
      SELECT refund_amt AS value FROM candidates ORDER BY refund_amt DESC OFFSET 14 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE refund_amt>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE refund_amt=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T5-005-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT ca.campaign_name, COUNT(DISTINCT cu.order_id) AS used_orders, ROUND(SUM(oi.price), 2) AS gmv
FROM coupon_usages cu
JOIN coupons cp ON cp.coupon_id = cu.coupon_id
JOIN campaigns ca ON ca.campaign_id = cp.campaign_id
JOIN orders o ON o.order_id = cu.order_id
JOIN order_items oi ON oi.order_id = cu.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1), cutoff AS(
      SELECT gmv AS value FROM candidates ORDER BY gmv DESC OFFSET 9 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE gmv>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE gmv=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T5-007-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT sl.keyword, t.product_category_name_english, COUNT(*) AS clicks
FROM search_logs sl
JOIN products p ON p.product_id = sl.clicked_product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
JOIN members m ON m.member_id = sl.member_id
JOIN member_levels ml ON ml.level_id = m.level_id
WHERE sl.clicked_product_id IS NOT NULL
GROUP BY 1, 2), cutoff AS(
      SELECT clicks AS value FROM candidates ORDER BY clicks DESC OFFSET 19 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE clicks>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE clicks=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T5-009-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT w.warehouse_name, r.region_name, b.brand_name, COUNT(*) AS low_stock_items
FROM inventory i
JOIN warehouses w ON w.warehouse_id = i.warehouse_id
JOIN regions r ON r.region_code = w.region_code
JOIN product_brand_map pbm ON pbm.product_id = i.product_id AND pbm.is_current
JOIN brands b ON b.brand_id = pbm.brand_id
WHERE i.quantity < i.safety_stock
GROUP BY 1, 2, 3), cutoff AS(
      SELECT low_stock_items AS value FROM candidates ORDER BY low_stock_items DESC OFFSET 19 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE low_stock_items>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE low_stock_items=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T5-010-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT st.seller_id, COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
       ROUND(SUM(si.commission_amount), 2) AS commission
FROM settlements st
JOIN settlement_items si ON si.settlement_id = st.settlement_id
JOIN order_items oi ON oi.order_id = si.order_id AND oi.order_item_id = si.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1), cutoff AS(
      SELECT commission AS value FROM candidates ORDER BY commission DESC OFFSET 19 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE commission>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE commission=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T5-016-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT t.product_category_name_english, r.reason_code,
       COUNT(DISTINCT r.refund_id) AS refunds, ROUND(SUM(ri.refund_value), 2) AS amt
FROM refunds r
JOIN refund_items ri ON ri.refund_id = r.refund_id
JOIN order_items oi ON oi.order_id = ri.order_id AND oi.order_item_id = ri.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1, 2), cutoff AS(
      SELECT amt AS value FROM candidates ORDER BY amt DESC OFFSET 19 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE amt>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE amt=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T5-018-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT sa.state AS addr_state, c.customer_state, COUNT(DISTINCT o.order_id) AS orders
FROM shipping_addresses sa
JOIN customers c ON c.customer_id = sa.customer_id
JOIN orders o ON o.customer_id = c.customer_id
WHERE sa.is_default
GROUP BY 1, 2), cutoff AS(
      SELECT orders AS value FROM candidates ORDER BY orders DESC OFFSET 19 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE orders>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE orders=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
SELECT json_build_object('name','T6-019-cutoff','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (
WITH candidates AS(SELECT c.customer_state,
       ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)) / 86400.0), 2) AS avg_days
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_delivered_customer_date IS NOT NULL
GROUP BY 1), cutoff AS(
      SELECT avg_days AS value FROM candidates ORDER BY avg_days DESC OFFSET 9 LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE avg_days>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE avg_days=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates
) q;
COMMIT;
