-- A01_schema_constraints; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT conrelid::regclass::text AS relation,conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE connamespace='public'::regnamespace AND contype IN ('p','u','f') ORDER BY 1,2;
ROLLBACK;

-- A02_nulls_and_denominators; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT (SELECT count(*) FROM orders WHERE order_delivered_customer_date IS NOT NULL) AS delivered,(SELECT count(*) FROM products) AS products,(SELECT count(*) FROM referrals) AS referrals,(SELECT count(*) FROM referrals WHERE converted_at IS NOT NULL) AS converted,(SELECT count(*) FROM orders WHERE order_purchase_timestamp IS NULL) AS null_order_time,(SELECT count(*) FROM refunds WHERE requested_at IS NULL) AS null_refund_time,(SELECT count(*) FROM support_tickets WHERE created_at IS NULL) AS null_ticket_time,(SELECT count(*) FROM members WHERE register_date IS NULL) AS null_registration,(SELECT count(*) FROM settlements WHERE period_start IS NULL) AS null_period_start;
ROLLBACK;

-- A03_month_coverage; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH events AS(SELECT 'orders' AS src,order_purchase_timestamp AS ts FROM orders UNION ALL SELECT 'freight',o.order_purchase_timestamp FROM orders o JOIN order_items i USING(order_id) UNION ALL SELECT 'refunds',requested_at FROM refunds UNION ALL SELECT 'tickets',created_at FROM support_tickets UNION ALL SELECT 'members',register_date FROM members UNION ALL SELECT 'settlements',period_start FROM settlements), monthly AS(SELECT src,date_trunc('month',ts) AS m,count(*) AS n FROM events WHERE ts IS NOT NULL GROUP BY 1,2), bounds AS(SELECT src,min(m) AS lo,max(m) AS hi FROM monthly GROUP BY src) SELECT b.src,b.lo,b.hi,count(*) FILTER(WHERE m.n IS NULL) AS missing_months,array_agg(c.m::date ORDER BY c.m) FILTER(WHERE m.n IS NULL) AS gaps FROM bounds b CROSS JOIN LATERAL generate_series(b.lo,b.hi,interval '1 month') c(m) LEFT JOIN monthly m ON m.src=b.src AND m.m=c.m GROUP BY b.src,b.lo,b.hi ORDER BY b.src;
ROLLBACK;

-- A04_zero_review_states_and_member_levels; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT 'order_status' AS dimension,o.order_status AS value,count(DISTINCT o.order_id) AS population,count(r.order_id) AS child_facts FROM orders o LEFT JOIN order_reviews r USING(order_id) GROUP BY o.order_status UNION ALL SELECT 'member_level',ml.level_id::text,count(DISTINCT m.member_id),count(o.order_id) FROM member_levels ml LEFT JOIN members m ON m.level_id=ml.level_id LEFT JOIN customers c ON c.customer_unique_id=m.customer_unique_id LEFT JOIN orders o ON o.customer_id=c.customer_id GROUP BY ml.level_id ORDER BY 1,2;
ROLLBACK;

-- A05_unmatched_keys; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT 'inventory_warehouse' AS relation,count(*) AS n FROM inventory i LEFT JOIN warehouses w USING(warehouse_id) WHERE w.warehouse_id IS NULL UNION ALL SELECT 'settlement_seller',count(*) FROM settlements st LEFT JOIN sellers s USING(seller_id) WHERE s.seller_id IS NULL UNION ALL SELECT 'snapshot_level',count(*) FROM member_snapshot ms LEFT JOIN member_levels ml USING(level_id) WHERE ms.level_id IS NOT NULL AND ml.level_id IS NULL UNION ALL SELECT 'refund_order',count(*) FROM refunds r LEFT JOIN orders o USING(order_id) WHERE o.order_id IS NULL UNION ALL SELECT 'raw_category_no_translation',count(*) FROM products p LEFT JOIN product_category_translation t USING(product_category_name) WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL UNION ALL SELECT 'clicked_product',count(*) FROM search_logs sl LEFT JOIN products p ON p.product_id=sl.clicked_product_id WHERE sl.clicked_product_id IS NOT NULL AND p.product_id IS NULL;
ROLLBACK;

-- A06_identity_and_fanout; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT 'customer_unique_ids_with_multiple_rows' AS issue,count(*) AS n FROM(SELECT customer_unique_id FROM customers GROUP BY 1 HAVING count(*)>1)x UNION ALL SELECT 'people_in_multiple_states',count(*) FROM(SELECT customer_unique_id FROM customers GROUP BY 1 HAVING count(DISTINCT customer_state)>1)x UNION ALL SELECT 'orders_with_multiple_reviews',count(*) FROM(SELECT order_id FROM order_reviews GROUP BY 1 HAVING count(*)>1)x UNION ALL SELECT 'orders_with_multiple_coupons',count(*) FROM(SELECT order_id FROM coupon_usages GROUP BY 1 HAVING count(*)>1)x UNION ALL SELECT 'customers_with_multiple_default_addresses',count(*) FROM(SELECT customer_id FROM shipping_addresses WHERE is_default GROUP BY 1 HAVING count(*)>1)x UNION ALL SELECT 'products_with_multiple_current_brands',count(*) FROM(SELECT product_id FROM product_brand_map WHERE is_current GROUP BY 1 HAVING count(*)>1)x UNION ALL SELECT 'member_date_multiple_snapshots',count(*) FROM(SELECT member_id,snapshot_date FROM member_snapshot GROUP BY 1,2 HAVING count(*)>1)x;
ROLLBACK;

-- A07_campaign_population; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT campaign_type,count(*) AS campaigns,count(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM coupons cp WHERE cp.campaign_id=ca.campaign_id)) AS without_coupon FROM campaigns ca GROUP BY campaign_type ORDER BY 1;
ROLLBACK;

-- A08_slowest_unknown_candidates; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT c.customer_state,count(*) AS delivered, count(o.order_purchase_timestamp) AS measurable,round(avg(extract(epoch FROM(o.order_delivered_customer_date-o.order_purchase_timestamp))/86400.0),2) AS avg_days FROM orders o JOIN customers c USING(customer_id) WHERE order_delivered_customer_date IS NOT NULL GROUP BY c.customer_state ORDER BY avg_days DESC NULLS LAST,c.customer_state NULLS LAST;
ROLLBACK;

-- A09_zero_gmv_region; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT r.macro_region,sum(i.price+i.freight_value) AS gmv FROM orders o JOIN customers c USING(customer_id) JOIN regions r ON r.region_code=c.customer_state JOIN order_items i USING(order_id) WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY 1 ORDER BY 1;
ROLLBACK;

-- S01_T6-006_empty_delivered; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH orders AS(SELECT * FROM public.orders WHERE false) SELECT ROUND(100.0 * COUNT(*) FILTER (
         WHERE o.order_delivered_customer_date <= o.order_estimated_delivery_date
       ) / COUNT(*), 2) AS on_time_pct
FROM orders o
WHERE o.order_delivered_customer_date IS NOT NULL;
ROLLBACK;

-- S02_T6-008_empty_products; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH sold AS(SELECT 0::bigint AS n),allp AS(SELECT 0::bigint AS n) SELECT round(100.0*s.n/a.n,2) AS sell_through_pct FROM sold s CROSS JOIN allp a;
ROLLBACK;

-- S03_T6-011_zero_region_gmv; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH oi(price,freight_value) AS(VALUES(0::numeric,0::numeric)) SELECT round(100.0*sum(freight_value)/sum(price+freight_value),2) AS freight_pct FROM oi;
ROLLBACK;

-- S04_T6-017_empty_referrals; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH referrals AS(SELECT * FROM public.referrals WHERE false) SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE converted_at IS NOT NULL) / COUNT(*), 2) AS conversion_pct,
       ROUND(SUM(reward_amount) / COUNT(*) FILTER (WHERE converted_at IS NOT NULL), 2) AS reward_per_converted
FROM referrals;
ROLLBACK;

-- S05_T6-017_no_conversion; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH referrals(converted_at,reward_amount) AS(VALUES(NULL::timestamp,0::numeric)) SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE converted_at IS NOT NULL) / COUNT(*), 2) AS conversion_pct,
       ROUND(SUM(reward_amount) / COUNT(*) FILTER (WHERE converted_at IS NOT NULL), 2) AS reward_per_converted
FROM referrals;
ROLLBACK;

-- S06_T5-009_orphan_warehouse; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH inventory(warehouse_id,product_id,quantity,safety_stock) AS(VALUES(999999,'synthetic-p',1,2)),warehouses(warehouse_id,region_code) AS(SELECT NULL::int,NULL::text WHERE false),product_brand_map(product_id,brand_id,is_current) AS(SELECT NULL::text,NULL::int,NULL::boolean WHERE false) SELECT w.warehouse_id,w.region_code,pbm.brand_id,COUNT(*) AS low_stock_items
FROM inventory i JOIN warehouses w ON w.warehouse_id=i.warehouse_id
LEFT JOIN product_brand_map pbm ON pbm.product_id=i.product_id AND pbm.is_current
WHERE i.quantity<i.safety_stock
GROUP BY w.warehouse_id,w.region_code,pbm.brand_id
ORDER BY low_stock_items DESC,w.warehouse_id,w.region_code,pbm.brand_id ASC NULLS LAST LIMIT 20;
ROLLBACK;

-- S08_T6-019_unknown_average_enters_top10; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH orders(customer_id,order_delivered_customer_date,order_purchase_timestamp) AS(VALUES('c',TIMESTAMP '2018-01-03',NULL::timestamp)),customers(customer_id,customer_state) AS(VALUES('c','SP')) SELECT c.customer_state,
       ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)) / 86400.0), 2) AS avg_days
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_delivered_customer_date IS NOT NULL
GROUP BY 1 ORDER BY avg_days DESC NULLS LAST,c.customer_state ASC NULLS LAST LIMIT 10;
ROLLBACK;

-- S09_T4-011_zero_review_status; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH orders(order_id,order_status) AS(VALUES('synthetic-o','created')),order_reviews(order_id,review_score) AS(SELECT NULL::text,NULL::int WHERE false) SELECT o.order_status, ROUND(AVG(r.review_score), 2) AS avg_score
FROM orders o JOIN order_reviews r ON r.order_id = o.order_id
GROUP BY 1 ORDER BY avg_score DESC;
ROLLBACK;

-- S10_T4-017_zero_order_level; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH orders(customer_id) AS(SELECT NULL::text WHERE false),customers(customer_id,customer_unique_id) AS(SELECT NULL::text,NULL::text WHERE false),members(customer_unique_id,level_id) AS(VALUES('synthetic-person',1)) SELECT m.level_id, COUNT(*) AS cnt
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
GROUP BY 1 ORDER BY 1;
ROLLBACK;

-- S07_T5-014_unknown_snapshot_level; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH member_snapshot(snapshot_id,member_id,snapshot_date,level_id) AS(VALUES(1,1,DATE '2018-01-01',999)),members(member_id,level_id) AS(VALUES(1,1)),member_levels(level_id,level_name) AS(VALUES(1,'known-level')), ranked AS (
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
GROUP BY ml_cur.level_name,ml_snap.level_name ORDER BY member_cnt DESC;
ROLLBACK;

-- S11_T4-015_unmapped_region; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH orders(order_id,customer_id) AS(VALUES('o','c')),customers(customer_id,customer_state) AS(VALUES('c',NULL::text)),regions(region_code,macro_region) AS(VALUES('SP','Sudeste')) SELECT r.macro_region, COUNT(*) AS cnt
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
GROUP BY 1 ORDER BY cnt DESC;
ROLLBACK;

-- S12_T5-001_unknown_level_name; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH orders(order_id,customer_id) AS(VALUES('o','c')),customers(customer_id,customer_state,customer_unique_id) AS(VALUES('c','SP','u')),regions(region_code,macro_region) AS(VALUES('SP','Sudeste')),members(customer_unique_id,level_id) AS(VALUES('u',NULL::int)),member_levels(level_id,level_name) AS(VALUES(1,'known-level')),order_items(order_id,price) AS(VALUES('o',100::numeric)) SELECT r.macro_region, ml.level_name, ROUND(SUM(oi.price), 2) AS gmv
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN members m ON m.customer_unique_id = c.customer_unique_id
JOIN member_levels ml ON ml.level_id = m.level_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1, 2 ORDER BY gmv DESC;
ROLLBACK;

-- S13_T4-031_unmatched_seller; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH settlements(seller_id,net_amount) AS(VALUES('unknown-s',100::numeric)),sellers(seller_id,seller_state) AS(SELECT NULL::text,NULL::text WHERE false) SELECT s.seller_state, ROUND(SUM(st.net_amount), 2) AS total
FROM settlements st JOIN sellers s ON s.seller_id = st.seller_id
GROUP BY 1 ORDER BY total DESC;
ROLLBACK;

-- A10_campaign_top10_types; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH co AS(SELECT DISTINCT cp.campaign_id,cu.order_id FROM coupon_usages cu JOIN coupons cp USING(coupon_id) JOIN orders o USING(order_id) WHERE o.order_status NOT IN('canceled','unavailable')),it AS(SELECT order_id,sum(price) AS sales FROM order_items GROUP BY 1) SELECT ca.campaign_id,ca.campaign_type,round(sum(it.sales),2) AS sales FROM campaigns ca LEFT JOIN co USING(campaign_id) LEFT JOIN it USING(order_id) GROUP BY ca.campaign_id ORDER BY sales DESC NULLS LAST,ca.campaign_id LIMIT 10;
ROLLBACK;

-- A11_region_and_seller_coverage; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT count(*) AS orders_unmapped_region FROM orders o JOIN customers c USING(customer_id) LEFT JOIN regions r ON r.region_code=c.customer_state WHERE r.region_code IS NULL; SELECT count(*) AS member_orders_null_level FROM orders o JOIN customers c USING(customer_id) JOIN members m USING(customer_unique_id) WHERE m.level_id IS NULL;
ROLLBACK;

-- A12_real_schema_nullable; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT table_name,column_name,is_nullable,data_type FROM information_schema.columns WHERE table_schema='public' AND ((table_name='members' AND column_name IN('customer_unique_id','level_id')) OR (table_name='customers' AND column_name IN('customer_unique_id','customer_state')) OR (table_name='orders' AND column_name IN('customer_id','order_purchase_timestamp')) OR (table_name='member_snapshot' AND column_name IN('level_id','snapshot_date')) OR (table_name='order_items' AND column_name IN('price','freight_value'))) ORDER BY 1,2;
ROLLBACK;
