-- R-T4-011-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T4-011','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT o.order_status,ROUND(AVG(r.review_score),2) AS avg_score
FROM orders o LEFT JOIN order_reviews r ON r.order_id=o.order_id
GROUP BY o.order_status ORDER BY avg_score DESC NULLS LAST)g;
WITH old_result AS (SELECT o.order_status, ROUND(AVG(r.review_score), 2) AS avg_score
FROM orders o JOIN order_reviews r ON r.order_id = o.order_id
GROUP BY 1 ORDER BY avg_score DESC), new_result AS (SELECT o.order_status,ROUND(AVG(r.review_score),2) AS avg_score
FROM orders o LEFT JOIN order_reviews r ON r.order_id=o.order_id
GROUP BY o.order_status ORDER BY avg_score DESC NULLS LAST) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T4-015-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T4-015','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT r.macro_region,COUNT(*) AS orders
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
GROUP BY r.macro_region ORDER BY orders DESC)g;
WITH old_result AS (SELECT r.macro_region, COUNT(*) AS cnt
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
GROUP BY 1 ORDER BY cnt DESC), new_result AS (SELECT r.macro_region,COUNT(*) AS orders
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
GROUP BY r.macro_region ORDER BY orders DESC) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T4-031-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T4-031','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT s.seller_state,ROUND(SUM(st.net_amount),2) AS net
FROM settlements st LEFT JOIN sellers s ON s.seller_id=st.seller_id
GROUP BY s.seller_state ORDER BY net DESC NULLS LAST)g;
WITH old_result AS (SELECT s.seller_state, ROUND(SUM(st.net_amount), 2) AS total
FROM settlements st JOIN sellers s ON s.seller_id = st.seller_id
GROUP BY 1 ORDER BY total DESC), new_result AS (SELECT s.seller_state,ROUND(SUM(st.net_amount),2) AS net
FROM settlements st LEFT JOIN sellers s ON s.seller_id=st.seller_id
GROUP BY s.seller_state ORDER BY net DESC NULLS LAST) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T5-001-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T5-001','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT r.macro_region,ml.level_name,ROUND(SUM(oi.price),2) AS sales
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
JOIN members m ON m.customer_unique_id=c.customer_unique_id
LEFT JOIN member_levels ml ON ml.level_id=m.level_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY r.macro_region,ml.level_name ORDER BY sales DESC NULLS LAST)g;
WITH old_result AS (SELECT r.macro_region, ml.level_name, ROUND(SUM(oi.price), 2) AS gmv
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN members m ON m.customer_unique_id = c.customer_unique_id
JOIN member_levels ml ON ml.level_id = m.level_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1, 2 ORDER BY gmv DESC), new_result AS (SELECT r.macro_region,ml.level_name,ROUND(SUM(oi.price),2) AS sales
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
JOIN members m ON m.customer_unique_id=c.customer_unique_id
LEFT JOIN member_levels ml ON ml.level_id=m.level_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY r.macro_region,ml.level_name ORDER BY sales DESC NULLS LAST) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T5-003-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T5-003','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT t.product_category_name_english,ROUND(SUM(ri.refund_value),2) AS refund_amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english HAVING SUM(ri.refund_value) IS NOT NULL
ORDER BY refund_amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 15)g;
WITH old_result AS (SELECT t.product_category_name_english,ROUND(SUM(ri.refund_value),2) AS refund_amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english
ORDER BY refund_amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 15), new_result AS (SELECT t.product_category_name_english,ROUND(SUM(ri.refund_value),2) AS refund_amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english HAVING SUM(ri.refund_value) IS NOT NULL
ORDER BY refund_amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 15) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T5-009-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T5-009','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT i.warehouse_id,w.region_code,pbm.brand_id,COUNT(*) AS low_stock_items
FROM inventory i LEFT JOIN warehouses w ON w.warehouse_id=i.warehouse_id
LEFT JOIN product_brand_map pbm ON pbm.product_id=i.product_id AND pbm.is_current
WHERE i.quantity<i.safety_stock
GROUP BY i.warehouse_id,w.region_code,pbm.brand_id
ORDER BY low_stock_items DESC,i.warehouse_id,w.region_code,pbm.brand_id ASC NULLS LAST LIMIT 20)g;
WITH old_result AS (SELECT w.warehouse_id,w.region_code,pbm.brand_id,COUNT(*) AS low_stock_items
FROM inventory i JOIN warehouses w ON w.warehouse_id=i.warehouse_id
LEFT JOIN product_brand_map pbm ON pbm.product_id=i.product_id AND pbm.is_current
WHERE i.quantity<i.safety_stock
GROUP BY w.warehouse_id,w.region_code,pbm.brand_id
ORDER BY low_stock_items DESC,w.warehouse_id,w.region_code,pbm.brand_id ASC NULLS LAST LIMIT 20), new_result AS (SELECT i.warehouse_id,w.region_code,pbm.brand_id,COUNT(*) AS low_stock_items
FROM inventory i LEFT JOIN warehouses w ON w.warehouse_id=i.warehouse_id
LEFT JOIN product_brand_map pbm ON pbm.product_id=i.product_id AND pbm.is_current
WHERE i.quantity<i.safety_stock
GROUP BY i.warehouse_id,w.region_code,pbm.brand_id
ORDER BY low_stock_items DESC,i.warehouse_id,w.region_code,pbm.brand_id ASC NULLS LAST LIMIT 20) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T5-010-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T5-010','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT st.seller_id,COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
 ROUND(SUM(si.commission_amount),2) AS commission
FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
LEFT JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
GROUP BY st.seller_id HAVING SUM(si.commission_amount) IS NOT NULL ORDER BY commission DESC NULLS LAST,st.seller_id LIMIT 20)g;
WITH old_result AS (SELECT st.seller_id,COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
 ROUND(SUM(si.commission_amount),2) AS commission
FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
LEFT JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
GROUP BY st.seller_id ORDER BY commission DESC NULLS LAST,st.seller_id LIMIT 20), new_result AS (SELECT st.seller_id,COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
 ROUND(SUM(si.commission_amount),2) AS commission
FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
LEFT JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
GROUP BY st.seller_id HAVING SUM(si.commission_amount) IS NOT NULL ORDER BY commission DESC NULLS LAST,st.seller_id LIMIT 20) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T5-014-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T5-014','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (WITH ranked AS (
 SELECT ms.*,ROW_NUMBER() OVER(PARTITION BY member_id ORDER BY snapshot_date DESC,snapshot_id DESC) AS rn
 FROM member_snapshot ms WHERE snapshot_date IS NOT NULL
)
SELECT ml_cur.level_name AS current_level,ml_snap.level_name AS snapshot_level,
 COUNT(DISTINCT m.member_id) AS member_cnt
FROM ranked ms
JOIN members m ON m.member_id=ms.member_id
LEFT JOIN member_levels ml_cur ON ml_cur.level_id=m.level_id
LEFT JOIN member_levels ml_snap ON ml_snap.level_id=ms.level_id
WHERE ms.rn=1 AND ml_cur.level_id IS NOT NULL AND ml_snap.level_id IS NOT NULL AND ms.level_id<>m.level_id
GROUP BY ml_cur.level_name,ml_snap.level_name ORDER BY member_cnt DESC)g;
WITH old_result AS (WITH ranked AS (
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
GROUP BY ml_cur.level_name,ml_snap.level_name ORDER BY member_cnt DESC), new_result AS (WITH ranked AS (
 SELECT ms.*,ROW_NUMBER() OVER(PARTITION BY member_id ORDER BY snapshot_date DESC,snapshot_id DESC) AS rn
 FROM member_snapshot ms WHERE snapshot_date IS NOT NULL
)
SELECT ml_cur.level_name AS current_level,ml_snap.level_name AS snapshot_level,
 COUNT(DISTINCT m.member_id) AS member_cnt
FROM ranked ms
JOIN members m ON m.member_id=ms.member_id
LEFT JOIN member_levels ml_cur ON ml_cur.level_id=m.level_id
LEFT JOIN member_levels ml_snap ON ml_snap.level_id=ms.level_id
WHERE ms.rn=1 AND ml_cur.level_id IS NOT NULL AND ml_snap.level_id IS NOT NULL AND ms.level_id<>m.level_id
GROUP BY ml_cur.level_name,ml_snap.level_name ORDER BY member_cnt DESC) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T6-006-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T6-006','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT ROUND(100.0*COUNT(*) FILTER(
 WHERE order_delivered_customer_date<=order_estimated_delivery_date
)/NULLIF(COUNT(*),0),2) AS on_time_pct
FROM orders WHERE order_delivered_customer_date IS NOT NULL)g;
WITH old_result AS (SELECT ROUND(100.0 * COUNT(*) FILTER (
         WHERE o.order_delivered_customer_date <= o.order_estimated_delivery_date
       ) / COUNT(*), 2) AS on_time_pct
FROM orders o
WHERE o.order_delivered_customer_date IS NOT NULL), new_result AS (SELECT ROUND(100.0*COUNT(*) FILTER(
 WHERE order_delivered_customer_date<=order_estimated_delivery_date
)/NULLIF(COUNT(*),0),2) AS on_time_pct
FROM orders WHERE order_delivered_customer_date IS NOT NULL) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T6-008-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T6-008','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (WITH sold AS (
 SELECT COUNT(DISTINCT oi.product_id) AS n FROM order_items oi
 JOIN orders o ON o.order_id=oi.order_id WHERE o.order_status NOT IN ('canceled','unavailable')
), allp AS(SELECT COUNT(*) AS n FROM products)
SELECT ROUND(100.0*s.n/NULLIF(a.n,0),2) AS sell_through_pct FROM sold s CROSS JOIN allp a)g;
WITH old_result AS (WITH sold AS (
  SELECT COUNT(DISTINCT oi.product_id) AS n
  FROM order_items oi
  JOIN orders o ON o.order_id = oi.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
),
allp AS (SELECT COUNT(*) AS n FROM products)
SELECT ROUND(100.0 * s.n / a.n, 2) AS sell_through_pct
FROM sold s CROSS JOIN allp a), new_result AS (WITH sold AS (
 SELECT COUNT(DISTINCT oi.product_id) AS n FROM order_items oi
 JOIN orders o ON o.order_id=oi.order_id WHERE o.order_status NOT IN ('canceled','unavailable')
), allp AS(SELECT COUNT(*) AS n FROM products)
SELECT ROUND(100.0*s.n/NULLIF(a.n,0),2) AS sell_through_pct FROM sold s CROSS JOIN allp a) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T6-011-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T6-011','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT r.macro_region,ROUND(100.0*SUM(oi.freight_value)/
 NULLIF(SUM(oi.price+oi.freight_value),0),2) AS freight_pct
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
WHERE o.order_status NOT IN ('canceled','unavailable')
GROUP BY r.macro_region ORDER BY freight_pct DESC NULLS LAST)g;
WITH old_result AS (SELECT r.macro_region,
       ROUND(100.0 * SUM(oi.freight_value) / SUM(oi.price + oi.freight_value), 2) AS freight_pct
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY freight_pct DESC), new_result AS (SELECT r.macro_region,ROUND(100.0*SUM(oi.freight_value)/
 NULLIF(SUM(oi.price+oi.freight_value),0),2) AS freight_pct
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
WHERE o.order_status NOT IN ('canceled','unavailable')
GROUP BY r.macro_region ORDER BY freight_pct DESC NULLS LAST) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T6-017-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T6-017','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT ROUND(100.0*COUNT(*) FILTER(WHERE converted_at IS NOT NULL)/NULLIF(COUNT(*),0),2) AS conversion_pct,
 ROUND(SUM(reward_amount)/NULLIF(COUNT(*) FILTER(WHERE converted_at IS NOT NULL),0),2) AS reward_per_converted
FROM referrals)g;
WITH old_result AS (SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE converted_at IS NOT NULL) / COUNT(*), 2) AS conversion_pct,
       ROUND(SUM(reward_amount) / COUNT(*) FILTER (WHERE converted_at IS NOT NULL), 2) AS reward_per_converted
FROM referrals), new_result AS (SELECT ROUND(100.0*COUNT(*) FILTER(WHERE converted_at IS NOT NULL)/NULLIF(COUNT(*),0),2) AS conversion_pct,
 ROUND(SUM(reward_amount)/NULLIF(COUNT(*) FILTER(WHERE converted_at IS NOT NULL),0),2) AS reward_per_converted
FROM referrals) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T6-019-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T6-019','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (WITH state_times AS (
 SELECT c.customer_state,ROUND(AVG(EXTRACT(EPOCH FROM(
 o.order_delivered_customer_date-o.order_purchase_timestamp))/86400.0),2) AS avg_days
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 WHERE o.order_delivered_customer_date IS NOT NULL GROUP BY c.customer_state
)
SELECT customer_state,avg_days FROM state_times WHERE avg_days IS NOT NULL
ORDER BY avg_days DESC,customer_state ASC NULLS LAST LIMIT 10)g;
WITH old_result AS (SELECT c.customer_state,
       ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)) / 86400.0), 2) AS avg_days
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_delivered_customer_date IS NOT NULL
GROUP BY 1 ORDER BY avg_days DESC NULLS LAST,c.customer_state ASC NULLS LAST LIMIT 10), new_result AS (WITH state_times AS (
 SELECT c.customer_state,ROUND(AVG(EXTRACT(EPOCH FROM(
 o.order_delivered_customer_date-o.order_purchase_timestamp))/86400.0),2) AS avg_days
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 WHERE o.order_delivered_customer_date IS NOT NULL GROUP BY c.customer_state
)
SELECT customer_state,avg_days FROM state_times WHERE avg_days IS NOT NULL
ORDER BY avg_days DESC,customer_state ASC NULLS LAST LIMIT 10) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- R-T5-001-actual-retry; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SET LOCAL max_parallel_workers_per_gather=0;
SELECT json_build_object('id','T5-001','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT r.macro_region,ml.level_name,ROUND(SUM(oi.price),2) AS sales
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
JOIN members m ON m.customer_unique_id=c.customer_unique_id
LEFT JOIN member_levels ml ON ml.level_id=m.level_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY r.macro_region,ml.level_name ORDER BY sales DESC NULLS LAST)g;
WITH old_result AS (SELECT r.macro_region, ml.level_name, ROUND(SUM(oi.price), 2) AS gmv
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN members m ON m.customer_unique_id = c.customer_unique_id
JOIN member_levels ml ON ml.level_id = m.level_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1, 2 ORDER BY gmv DESC), new_result AS (SELECT r.macro_region,ml.level_name,ROUND(SUM(oi.price),2) AS sales
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
JOIN members m ON m.customer_unique_id=c.customer_unique_id
LEFT JOIN member_levels ml ON ml.level_id=m.level_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY r.macro_region,ml.level_name ORDER BY sales DESC NULLS LAST) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- RS01; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(order_id,order_status) AS(VALUES('o','created')),order_reviews(order_id,review_score) AS(SELECT NULL::text,NULL::int WHERE false) SELECT o.order_status,ROUND(AVG(r.review_score),2) AS avg_score
FROM orders o LEFT JOIN order_reviews r ON r.order_id=o.order_id
GROUP BY o.order_status ORDER BY avg_score DESC NULLS LAST)g;
ROLLBACK;

-- RS02; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(order_id,customer_id) AS(VALUES('o1','c1'),('o2','c2')),customers(customer_id,customer_state) AS(VALUES('c1','SP'),('c2',NULL::text)),regions(region_code,macro_region) AS(VALUES('SP','Sudeste')) SELECT r.macro_region,COUNT(*) AS orders
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
GROUP BY r.macro_region ORDER BY orders DESC)g;
ROLLBACK;

-- RS03; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH settlements(seller_id,net_amount) AS(VALUES('orphan',100::numeric)),sellers(seller_id,seller_state) AS(SELECT NULL::text,NULL::text WHERE false) SELECT s.seller_state,ROUND(SUM(st.net_amount),2) AS net
FROM settlements st LEFT JOIN sellers s ON s.seller_id=st.seller_id
GROUP BY s.seller_state ORDER BY net DESC NULLS LAST)g;
ROLLBACK;

-- RS04; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(order_id,customer_id) AS(VALUES('o1','c1'),('o2','c2'),('o3','c3')),customers(customer_id,customer_state,customer_unique_id) AS(VALUES('c1','SP','u1'),('c2','SP','u2'),('c3','XX','u3')),regions(region_code,macro_region) AS(VALUES('SP','Sudeste')),members(customer_unique_id,level_id) AS(VALUES('u1',1),('u2',2),('u3',NULL::int)),member_levels(level_id,level_name) AS(VALUES(1,'same-name'),(2,'same-name')),order_items(order_id,price) AS(VALUES('o1',100::numeric),('o2',200::numeric),('o3',25::numeric)) SELECT r.macro_region,ml.level_name,ROUND(SUM(oi.price),2) AS sales
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
JOIN members m ON m.customer_unique_id=c.customer_unique_id
LEFT JOIN member_levels ml ON ml.level_id=m.level_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY r.macro_region,ml.level_name ORDER BY sales DESC NULLS LAST)g;
ROLLBACK;

-- RS05; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH refunds(refund_id) AS(VALUES(1)),refund_items(refund_id,order_id,order_item_id,refund_value) AS(VALUES(1,'o',1,NULL::numeric)),order_items(order_id,order_item_id,product_id) AS(VALUES('o',1,'p')),products(product_id,product_category_name) AS(VALUES('p','cat')),product_category_translation(product_category_name,product_category_name_english) AS(VALUES('cat','category')) SELECT t.product_category_name_english,ROUND(SUM(ri.refund_value),2) AS refund_amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english HAVING SUM(ri.refund_value) IS NOT NULL
ORDER BY refund_amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 15)g;
ROLLBACK;

-- RS06; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH inventory(warehouse_id,product_id,quantity,safety_stock) AS(VALUES(999,'p1',1,2),(1,'p2',1,2)),warehouses(warehouse_id,region_code) AS(VALUES(1,'SP')),product_brand_map(product_id,brand_id,is_current) AS(VALUES('p1',1,true),('p1',2,true)) SELECT i.warehouse_id,w.region_code,pbm.brand_id,COUNT(*) AS low_stock_items
FROM inventory i LEFT JOIN warehouses w ON w.warehouse_id=i.warehouse_id
LEFT JOIN product_brand_map pbm ON pbm.product_id=i.product_id AND pbm.is_current
WHERE i.quantity<i.safety_stock
GROUP BY i.warehouse_id,w.region_code,pbm.brand_id
ORDER BY low_stock_items DESC,i.warehouse_id,w.region_code,pbm.brand_id ASC NULLS LAST LIMIT 20)g;
ROLLBACK;

-- RS07; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH settlements(settlement_id,seller_id) AS(VALUES(1,'s')),settlement_items(settlement_id,order_id,order_item_id,commission_amount) AS(VALUES(1,'o',1,NULL::numeric)),order_items(order_id,order_item_id,product_id) AS(VALUES('o',1,'p')),products(product_id,product_category_name) AS(VALUES('p','cat')),product_category_translation(product_category_name,product_category_name_english) AS(VALUES('cat','category')) SELECT st.seller_id,COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
 ROUND(SUM(si.commission_amount),2) AS commission
FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
LEFT JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
GROUP BY st.seller_id HAVING SUM(si.commission_amount) IS NOT NULL ORDER BY commission DESC NULLS LAST,st.seller_id LIMIT 20)g;
ROLLBACK;

-- RS08; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH member_snapshot(snapshot_id,member_id,snapshot_date,level_id) AS(VALUES(1,1,DATE '2018-01-01',2),(2,1,DATE '2018-02-01',999),(3,2,DATE '2018-02-01',2),(4,2,DATE '2018-02-01',1),(5,3,NULL::date,1),(6,3,DATE '2018-01-01',2)),members(member_id,level_id) AS(VALUES(1,1),(2,1),(3,1)),member_levels(level_id,level_name) AS(VALUES(1,'current'),(2,'other')), ranked AS (
 SELECT ms.*,ROW_NUMBER() OVER(PARTITION BY member_id ORDER BY snapshot_date DESC,snapshot_id DESC) AS rn
 FROM member_snapshot ms WHERE snapshot_date IS NOT NULL
)
SELECT ml_cur.level_name AS current_level,ml_snap.level_name AS snapshot_level,
 COUNT(DISTINCT m.member_id) AS member_cnt
FROM ranked ms
JOIN members m ON m.member_id=ms.member_id
LEFT JOIN member_levels ml_cur ON ml_cur.level_id=m.level_id
LEFT JOIN member_levels ml_snap ON ml_snap.level_id=ms.level_id
WHERE ms.rn=1 AND ml_cur.level_id IS NOT NULL AND ml_snap.level_id IS NOT NULL AND ms.level_id<>m.level_id
GROUP BY ml_cur.level_name,ml_snap.level_name ORDER BY member_cnt DESC)g;
ROLLBACK;

-- RS09; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(order_delivered_customer_date,order_estimated_delivery_date) AS(SELECT NULL::timestamp,NULL::timestamp WHERE false) SELECT ROUND(100.0*COUNT(*) FILTER(
 WHERE order_delivered_customer_date<=order_estimated_delivery_date
)/NULLIF(COUNT(*),0),2) AS on_time_pct
FROM orders WHERE order_delivered_customer_date IS NOT NULL)g;
ROLLBACK;

-- RS10; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH products(product_id) AS(SELECT NULL::text WHERE false),orders(order_id,order_status) AS(SELECT NULL::text,NULL::text WHERE false),order_items(order_id,product_id) AS(SELECT NULL::text,NULL::text WHERE false), sold AS (
 SELECT COUNT(DISTINCT oi.product_id) AS n FROM order_items oi
 JOIN orders o ON o.order_id=oi.order_id WHERE o.order_status NOT IN ('canceled','unavailable')
), allp AS(SELECT COUNT(*) AS n FROM products)
SELECT ROUND(100.0*s.n/NULLIF(a.n,0),2) AS sell_through_pct FROM sold s CROSS JOIN allp a)g;
ROLLBACK;

-- RS11; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH products(product_id) AS(VALUES('p1'),('p2'),('p3')),orders(order_id,order_status) AS(VALUES('o1','delivered'),('o2','canceled')),order_items(order_id,product_id) AS(VALUES('o1','p1'),('o1','p1'),('o2','p2')), sold AS (
 SELECT COUNT(DISTINCT oi.product_id) AS n FROM order_items oi
 JOIN orders o ON o.order_id=oi.order_id WHERE o.order_status NOT IN ('canceled','unavailable')
), allp AS(SELECT COUNT(*) AS n FROM products)
SELECT ROUND(100.0*s.n/NULLIF(a.n,0),2) AS sell_through_pct FROM sold s CROSS JOIN allp a)g;
ROLLBACK;

-- RS12; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(order_id,customer_id,order_status) AS(VALUES('o1','c1','delivered'),('o2','c2','delivered')),customers(customer_id,customer_state) AS(VALUES('c1','SP'),('c2','XX')),regions(region_code,macro_region) AS(VALUES('SP','Sudeste')),order_items(order_id,price,freight_value) AS(VALUES('o1',0::numeric,0::numeric),('o2',90::numeric,10::numeric)) SELECT r.macro_region,ROUND(100.0*SUM(oi.freight_value)/
 NULLIF(SUM(oi.price+oi.freight_value),0),2) AS freight_pct
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
WHERE o.order_status NOT IN ('canceled','unavailable')
GROUP BY r.macro_region ORDER BY freight_pct DESC NULLS LAST)g;
ROLLBACK;

-- RS13; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH referrals(converted_at,reward_amount) AS(SELECT NULL::timestamp,NULL::numeric WHERE false) SELECT ROUND(100.0*COUNT(*) FILTER(WHERE converted_at IS NOT NULL)/NULLIF(COUNT(*),0),2) AS conversion_pct,
 ROUND(SUM(reward_amount)/NULLIF(COUNT(*) FILTER(WHERE converted_at IS NOT NULL),0),2) AS reward_per_converted
FROM referrals)g;
ROLLBACK;

-- RS14; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH referrals(converted_at,reward_amount) AS(VALUES(NULL::timestamp,0::numeric)) SELECT ROUND(100.0*COUNT(*) FILTER(WHERE converted_at IS NOT NULL)/NULLIF(COUNT(*),0),2) AS conversion_pct,
 ROUND(SUM(reward_amount)/NULLIF(COUNT(*) FILTER(WHERE converted_at IS NOT NULL),0),2) AS reward_per_converted
FROM referrals)g;
ROLLBACK;

-- RS15; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(customer_id,order_delivered_customer_date,order_purchase_timestamp) AS(VALUES('c',TIMESTAMP '2018-01-03',NULL::timestamp)),customers(customer_id,customer_state) AS(VALUES('c','SP')), state_times AS (
 SELECT c.customer_state,ROUND(AVG(EXTRACT(EPOCH FROM(
 o.order_delivered_customer_date-o.order_purchase_timestamp))/86400.0),2) AS avg_days
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 WHERE o.order_delivered_customer_date IS NOT NULL GROUP BY c.customer_state
)
SELECT customer_state,avg_days FROM state_times WHERE avg_days IS NOT NULL
ORDER BY avg_days DESC,customer_state ASC NULLS LAST LIMIT 10)g;
ROLLBACK;

-- RS16; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH facts(id,days) AS(VALUES('AA',10.003::numeric),('ZZ',10.004::numeric),('S1',20::numeric),('S2',21::numeric),('S3',22::numeric),('S4',23::numeric),('S5',24::numeric),('S6',25::numeric),('S7',26::numeric),('S8',27::numeric),('S9',28::numeric)),orders(customer_id,order_delivered_customer_date,order_purchase_timestamp) AS(SELECT id,TIMESTAMP '2018-01-01'+days*INTERVAL '1 day',TIMESTAMP '2018-01-01' FROM facts),customers(customer_id,customer_state) AS(SELECT id,id FROM facts), state_times AS (
 SELECT c.customer_state,ROUND(AVG(EXTRACT(EPOCH FROM(
 o.order_delivered_customer_date-o.order_purchase_timestamp))/86400.0),2) AS avg_days
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 WHERE o.order_delivered_customer_date IS NOT NULL GROUP BY c.customer_state
)
SELECT customer_state,avg_days FROM state_times WHERE avg_days IS NOT NULL
ORDER BY avg_days DESC,customer_state ASC NULLS LAST LIMIT 10)g;
ROLLBACK;

-- RS17; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH refunds(refund_id,reason_code) AS(VALUES(1,'QUAL')),refund_items(refund_id,order_id,order_item_id,refund_value) AS(VALUES(1,'o',1,NULL::numeric)),order_items(order_id,order_item_id,product_id) AS(VALUES('o',1,'p')),products(product_id,product_category_name) AS(VALUES('p','cat')),product_category_translation(product_category_name,product_category_name_english) AS(VALUES('cat','category')) SELECT t.product_category_name_english,r.reason_code,
 COUNT(DISTINCT r.refund_id) AS refunds,ROUND(SUM(ri.refund_value),2) AS amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english,r.reason_code
ORDER BY amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST,r.reason_code ASC NULLS LAST LIMIT 20)g;
ROLLBACK;

-- RS18; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(order_purchase_timestamp) AS(VALUES(TIMESTAMP '2018-01-01'),(TIMESTAMP '2018-03-01')) SELECT DATE_TRUNC('month', order_purchase_timestamp) AS month,
       COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY 1)g;
ROLLBACK;

-- RS19; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(order_id,order_purchase_timestamp) AS(VALUES('o1',TIMESTAMP '2018-01-01'),('o2',TIMESTAMP '2018-02-01'),('o3',TIMESTAMP '2018-03-01')),order_items(order_id,freight_value) AS(VALUES('o1',10::numeric),('o3',30::numeric)) SELECT DATE_TRUNC('month', o.order_purchase_timestamp) AS month,
       ROUND(SUM(oi.freight_value), 2) AS freight
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1)g;
ROLLBACK;

-- RS20; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH refunds(requested_at) AS(VALUES(TIMESTAMP '2018-01-01'),(TIMESTAMP '2018-03-01')) SELECT DATE_TRUNC('month', requested_at) AS month, COUNT(*) AS cnt
FROM refunds GROUP BY 1 ORDER BY 1)g;
ROLLBACK;

-- RS21; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH support_tickets(created_at) AS(VALUES(TIMESTAMP '2018-01-01'),(TIMESTAMP '2018-03-01')) SELECT DATE_TRUNC('month', created_at) AS month, COUNT(*) AS cnt
FROM support_tickets GROUP BY 1 ORDER BY 1)g;
ROLLBACK;

-- RS22; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH settlements(period_start,commission) AS(VALUES(DATE '2018-01-01',10::numeric),(DATE '2018-03-01',30::numeric)) SELECT DATE_TRUNC('month', period_start) AS month,
       ROUND(SUM(commission), 2) AS total
FROM settlements GROUP BY 1 ORDER BY 1)g;
ROLLBACK;

-- RS23; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH orders(customer_id) AS(SELECT NULL::text WHERE false),customers(customer_id,customer_unique_id) AS(SELECT NULL::text,NULL::text WHERE false),members(customer_unique_id,level_id) AS(VALUES('u1',1)) SELECT m.level_id, COUNT(*) AS cnt
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
GROUP BY 1 ORDER BY 1)g;
ROLLBACK;

-- RS24; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH campaigns(campaign_id,campaign_type) AS(VALUES(1,'COUPON'),(2,'DISCOUNT'),(3,'MEMBER_DAY')),coupons(coupon_id,campaign_id) AS(VALUES(1,1),(2,2)),coupon_usages(coupon_id,order_id) AS(VALUES(1,'o1'),(1,'o1'),(2,'o2')),orders(order_id,order_status) AS(VALUES('o1','delivered'),('o2','delivered')),order_items(order_id,price) AS(VALUES('o1',50::numeric),('o1',50::numeric),('o2',200::numeric)), campaign_orders AS (
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
ROLLBACK;
