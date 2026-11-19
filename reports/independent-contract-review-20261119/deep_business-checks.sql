BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';

SELECT jsonb_build_object('id','T5-001','check','membership and regional coverage','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS item_rows,SUM(oi.price) AS item_amount,
 COUNT(*) FILTER (WHERE r.region_code IS NULL) AS missing_region_items,
 COUNT(*) FILTER (WHERE ml.level_id IS NULL) AS missing_level_items,
 COUNT(*) FILTER (WHERE o.order_status IN ('canceled','unavailable')) AS canceled_or_unavailable_items
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 JOIN members m ON m.customer_unique_id=c.customer_unique_id
 JOIN order_items oi ON oi.order_id=o.order_id
 LEFT JOIN regions r ON r.region_code=c.customer_state
 LEFT JOIN member_levels ml ON ml.level_id=m.level_id
) x;

SELECT jsonb_build_object('id','T5-003','check','refund item translation coverage','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS refund_items,SUM(ri.refund_value) AS total,
 COUNT(*) FILTER (WHERE p.product_category_name IS NULL) AS null_category_items,
 SUM(ri.refund_value) FILTER (WHERE p.product_category_name IS NULL) AS null_category_amount,
 COUNT(*) FILTER (WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL) AS untranslated_items,
 SUM(ri.refund_value) FILTER (WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL) AS untranslated_amount,
 COUNT(*) FILTER (WHERE t.product_category_name_english IS NULL AND p.product_category_name IS NOT NULL) AS null_english_items
 FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
 JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
 JOIN products p ON p.product_id=oi.product_id
 LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
) x;

WITH facts AS (
 SELECT ca.campaign_id,ca.campaign_name,cu.order_id,COUNT(*) AS usages
 FROM coupon_usages cu JOIN coupons cp ON cp.coupon_id=cu.coupon_id
 JOIN campaigns ca ON ca.campaign_id=cp.campaign_id
 JOIN orders o ON o.order_id=cu.order_id
 WHERE o.order_status NOT IN ('canceled','unavailable') GROUP BY 1,2,3
), ot AS (SELECT order_id,SUM(price) AS sales FROM order_items GROUP BY 1)
SELECT jsonb_build_object('id','T5-005','check','campaign order grain','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS campaign_orders,COUNT(*) FILTER (WHERE usages>1) AS repeated_campaign_orders,
 SUM(usages) AS usage_rows,SUM(ot.sales*usages) AS gold_sales,SUM(ot.sales) AS deduplicated_campaign_order_sales,
 (SELECT COUNT(*) FROM (SELECT campaign_name FROM campaigns GROUP BY 1 HAVING COUNT(*)>1) d) AS duplicate_campaign_names
 FROM facts JOIN ot USING(order_id)
) x;

SELECT jsonb_build_object('id','T5-007','check','clicked search population','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS clicked_logs,
 COUNT(*) FILTER(WHERE p.product_id IS NULL) AS missing_products,
 COUNT(*) FILTER(WHERE m.member_id IS NULL) AS missing_members,
 COUNT(*) FILTER(WHERE ml.level_id IS NULL) AS missing_member_levels,
 COUNT(*) FILTER(WHERE p.product_category_name IS NULL) AS null_category,
 COUNT(*) FILTER(WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL) AS untranslated_category,
 COUNT(*) FILTER(WHERE t.product_category_name IS NOT NULL AND m.member_id IS NOT NULL AND ml.level_id IS NOT NULL) AS gold_logs
 FROM search_logs sl LEFT JOIN products p ON p.product_id=sl.clicked_product_id
 LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
 LEFT JOIN members m ON m.member_id=sl.member_id
 LEFT JOIN member_levels ml ON ml.level_id=m.level_id
 WHERE sl.clicked_product_id IS NOT NULL
) x;

WITH bc AS (SELECT product_id,COUNT(*) FILTER(WHERE is_current) AS current_brands FROM product_brand_map GROUP BY 1)
SELECT jsonb_build_object('id','T5-009','check','low inventory grain and names','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS low_stock_rows,COUNT(*) FILTER(WHERE COALESCE(bc.current_brands,0)=0) AS no_current_brand,
 COUNT(*) FILTER(WHERE bc.current_brands>1) AS multiple_current_brands,
 COUNT(*) FILTER(WHERE w.warehouse_id IS NULL OR r.region_code IS NULL) AS missing_warehouse_or_region,
 (SELECT COUNT(*) FROM (SELECT warehouse_name FROM warehouses GROUP BY 1 HAVING COUNT(*)>1) d) AS duplicate_warehouse_names,
 (SELECT COUNT(*) FROM (SELECT brand_name FROM brands GROUP BY 1 HAVING COUNT(*)>1) d) AS duplicate_brand_names
 FROM inventory i LEFT JOIN bc USING(product_id) LEFT JOIN warehouses w ON w.warehouse_id=i.warehouse_id
 LEFT JOIN regions r ON r.region_code=w.region_code WHERE i.quantity<i.safety_stock
) x;

SELECT jsonb_build_object('id','T5-010','check','settlement coverage before English lookup','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS settlement_item_rows,SUM(si.commission_amount) AS all_commission,
 COUNT(*) FILTER(WHERE oi.order_id IS NULL OR p.product_id IS NULL) AS missing_item_product,
 COUNT(*) FILTER(WHERE p.product_category_name IS NULL) AS null_category_items,
 SUM(si.commission_amount) FILTER(WHERE p.product_category_name IS NULL) AS null_category_commission,
 COUNT(*) FILTER(WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL) AS untranslated_items,
 SUM(si.commission_amount) FILTER(WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL) AS untranslated_commission,
 SUM(si.commission_amount) FILTER(WHERE t.product_category_name IS NOT NULL) AS gold_commission
 FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
 LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
 LEFT JOIN products p ON p.product_id=oi.product_id
 LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
) x;

WITH tagged AS (SELECT utm.tag_id,utm.member_id FROM user_tag_map utm JOIN members m USING(member_id)),
ordered AS (SELECT DISTINCT m.member_id FROM members m JOIN customers c USING(customer_unique_id) JOIN orders o USING(customer_id) JOIN order_items oi USING(order_id))
SELECT jsonb_build_object('id','T5-012','check','tag membership denominator','result',jsonb_agg(x)) FROM (
 SELECT t.tag_id,ut.tag_name,COUNT(*) AS tagged_members,COUNT(*) FILTER(WHERE od.member_id IS NULL) AS no_item_members,
 (SELECT COUNT(*) FROM user_tags u2 WHERE u2.tag_name=ut.tag_name) AS same_name_tags
 FROM tagged t JOIN user_tags ut USING(tag_id) LEFT JOIN ordered od USING(member_id)
 GROUP BY 1,2 ORDER BY 1
) x;

WITH latest_dates AS (SELECT member_id,MAX(snapshot_date) AS snapshot_date FROM member_snapshot GROUP BY 1),
latest AS (SELECT ms.* FROM member_snapshot ms JOIN latest_dates ld USING(member_id,snapshot_date)),
gold_groups AS (SELECT m.level_id AS current_level,ms.level_id AS snapshot_level,COUNT(DISTINCT m.member_id) AS n FROM member_snapshot ms JOIN members m USING(member_id) WHERE ms.level_id<>m.level_id GROUP BY 1,2),
latest_groups AS (SELECT m.level_id AS current_level,ms.level_id AS snapshot_level,COUNT(DISTINCT m.member_id) AS n FROM latest ms JOIN members m USING(member_id) WHERE ms.level_id<>m.level_id GROUP BY 1,2)
SELECT jsonb_build_object('id','T5-014','check','latest snapshot comparison','result',jsonb_build_object(
 'snapshot_rows',(SELECT COUNT(*) FROM member_snapshot),
 'members_with_multiple_snapshots',(SELECT COUNT(*) FROM (SELECT member_id FROM member_snapshot GROUP BY 1 HAVING COUNT(*)>1) d),
 'members_tied_on_latest_date',(SELECT COUNT(*) FROM (SELECT member_id FROM latest GROUP BY 1 HAVING COUNT(*)>1) d),
 'null_snapshot_date_rows',(SELECT COUNT(*) FROM member_snapshot WHERE snapshot_date IS NULL),
 'gold_groups',(SELECT jsonb_agg(g) FROM gold_groups g),
 'latest_groups',(SELECT jsonb_agg(g) FROM latest_groups g)));

SELECT jsonb_build_object('id','T5-016','check','refund reason category attribution','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS detail_rows,COUNT(DISTINCT r.refund_id) AS refunds,
 COUNT(*) FILTER(WHERE ri.order_id<>r.order_id) AS refund_header_detail_order_mismatches,
 SUM(ri.refund_value) FILTER(WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL) AS untranslated_amount,
 COUNT(*) FILTER(WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL) AS untranslated_items,
 COUNT(*) FILTER(WHERE p.product_category_name IS NULL) AS null_category_items
 FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
 JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
 JOIN products p ON p.product_id=oi.product_id
 LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
) x;

SELECT jsonb_build_object('id','T5-018','check','default address association','result',to_jsonb(x)) FROM (
 SELECT (SELECT COUNT(*) FROM shipping_addresses WHERE is_default) AS default_addresses,
 (SELECT COUNT(*) FROM (SELECT customer_id FROM shipping_addresses WHERE is_default GROUP BY 1 HAVING COUNT(*)>1) d) AS customers_with_multiple_defaults,
 (SELECT COUNT(*) FROM (SELECT customer_id FROM shipping_addresses WHERE is_default GROUP BY 1 HAVING COUNT(DISTINCT state)>1) d) AS customers_with_conflicting_default_states,
 (SELECT COUNT(*) FROM orders o WHERE NOT EXISTS(SELECT 1 FROM shipping_addresses sa WHERE sa.customer_id=o.customer_id AND sa.is_default)) AS orders_without_default,
 (SELECT COUNT(*) FROM shipping_addresses sa LEFT JOIN customers c USING(customer_id) WHERE sa.is_default AND c.customer_id IS NULL) AS orphan_defaults
) x;

WITH vo AS (SELECT o.* FROM orders o WHERE o.order_status NOT IN ('canceled','unavailable'))
SELECT jsonb_build_object('id','T6-002','check','order population independent of items','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS all_valid_orders,COUNT(DISTINCT c.customer_unique_id) AS all_valid_buyers,
 COUNT(*) FILTER(WHERE EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=vo.order_id)) AS gold_valid_orders,
 COUNT(DISTINCT c.customer_unique_id) FILTER(WHERE EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=vo.order_id)) AS gold_valid_buyers,
 ROUND((SELECT SUM(oi.price+oi.freight_value) FROM order_items oi JOIN vo v2 USING(order_id))/COUNT(*),2) AS all_order_aov,
 ROUND((SELECT SUM(oi.price+oi.freight_value) FROM order_items oi JOIN vo v2 USING(order_id))/COUNT(*) FILTER(WHERE EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=vo.order_id)),2) AS gold_aov
 FROM vo JOIN customers c USING(customer_id)
) x;

WITH fo AS (SELECT c.customer_unique_id,MIN(o.order_purchase_timestamp) AS ts FROM orders o JOIN customers c USING(customer_id) WHERE o.order_status NOT IN ('canceled','unavailable') GROUP BY 1),
co AS (SELECT *,DATE_TRUNC('month',ts)::date AS mo FROM fo WHERE ts>=DATE '2017-01-01' AND ts<DATE '2017-07-01')
SELECT jsonb_build_object('id','T6-004','check','monthly first-order cohorts and H2 returns','result',jsonb_agg(x)) FROM (
 SELECT co.mo,COUNT(*) AS cohort_users,COUNT(*) FILTER(WHERE EXISTS(SELECT 1 FROM customers c JOIN orders o USING(customer_id) WHERE c.customer_unique_id=co.customer_unique_id AND o.order_status NOT IN ('canceled','unavailable') AND o.order_purchase_timestamp>=DATE '2017-07-01' AND o.order_purchase_timestamp<DATE '2018-01-01')) AS returning_users
 FROM co GROUP BY 1 ORDER BY 1
) x;

SELECT jsonb_build_object('id','T6-006','check','on-time denominator and NULL handling','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS delivered_timestamp_rows,COUNT(*) FILTER(WHERE order_status<>'delivered') AS non_delivered_status_rows,
 COUNT(*) FILTER(WHERE order_estimated_delivery_date IS NULL) AS missing_estimate,
 COUNT(*) FILTER(WHERE order_delivered_customer_date<=order_estimated_delivery_date) AS on_time,
 ROUND(100.0*COUNT(*) FILTER(WHERE order_delivered_customer_date<=order_estimated_delivery_date)/NULLIF(COUNT(*),0),2) AS on_time_pct
 FROM orders WHERE order_delivered_customer_date IS NOT NULL
) x;

SELECT jsonb_build_object('id','T6-008','check','independent complete product denominator','result',to_jsonb(x)) FROM (
 SELECT (SELECT COUNT(*) FROM products) AS all_products,
 COUNT(DISTINCT oi.product_id) AS sold_products,
 ROUND(100.0*COUNT(DISTINCT oi.product_id)/NULLIF((SELECT COUNT(*) FROM products),0),2) AS sell_through_pct
 FROM order_items oi JOIN orders o USING(order_id) WHERE o.order_status NOT IN ('canceled','unavailable')
) x;

SELECT jsonb_build_object('id','T6-011','check','region subject changes attribution','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS valid_item_rows,COUNT(*) FILTER(WHERE cr.macro_region IS DISTINCT FROM sr.macro_region) AS different_customer_seller_macro_items,
 SUM(oi.price+oi.freight_value) FILTER(WHERE cr.macro_region IS DISTINCT FROM sr.macro_region) AS differently_attributed_gmv,
 COUNT(*) FILTER(WHERE cr.region_code IS NULL) AS missing_customer_region,
 COUNT(*) FILTER(WHERE sr.region_code IS NULL) AS missing_seller_region
 FROM order_items oi JOIN orders o USING(order_id) JOIN customers c USING(customer_id) JOIN sellers s USING(seller_id)
 LEFT JOIN regions cr ON cr.region_code=c.customer_state LEFT JOIN regions sr ON sr.region_code=s.seller_state
 WHERE o.order_status NOT IN ('canceled','unavailable')
) x;

WITH cu AS (SELECT order_id,COUNT(*) AS usages,SUM(discount_amount) AS discount FROM coupon_usages GROUP BY 1),
oi AS (SELECT order_id,COUNT(*) AS items,SUM(price+freight_value) AS gmv FROM order_items GROUP BY 1),
f AS (SELECT cu.*,oi.items,oi.gmv FROM cu JOIN oi USING(order_id) JOIN orders o USING(order_id) WHERE o.order_status NOT IN ('canceled','unavailable'))
SELECT jsonb_build_object('id','T6-013','check','coupon and item preaggregation','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS valid_coupon_orders,SUM(usages) AS usage_rows,COUNT(*) FILTER(WHERE usages>1) AS orders_with_multiple_usages,
 COUNT(*) FILTER(WHERE items>1) AS orders_with_multiple_items,
 ROUND(SUM(gmv*usages),2) AS gold_gmv,ROUND(SUM(gmv),2) AS correct_gmv,
 ROUND(SUM(discount*items),2) AS gold_discount,ROUND(SUM(discount),2) AS correct_discount,
 ROUND(100.0*SUM(discount*items)/NULLIF(SUM(gmv*usages),0),2) AS gold_discount_pct,
 ROUND(100.0*SUM(discount)/NULLIF(SUM(gmv),0),2) AS correct_discount_pct
 FROM f
) x;

SELECT jsonb_build_object('id','T6-015','check','ticket identity category coverage and SLA','result',jsonb_build_object(
 'null_category_tickets',(SELECT COUNT(*) FROM support_tickets WHERE category_id IS NULL),
 'duplicate_category_names',(SELECT COUNT(*) FROM (SELECT category_name FROM ticket_categories GROUP BY 1 HAVING COUNT(*)>1) d),
 'negative_duration_tickets',(SELECT COUNT(*) FROM support_tickets WHERE resolved_at<created_at),
 'by_category',(SELECT jsonb_agg(x) FROM (
 SELECT tc.category_id,tc.category_name,COUNT(*) AS tickets,COUNT(*) FILTER(WHERE st.resolved_at IS NULL) AS unresolved,
 ROUND(100.0*COUNT(*) FILTER(WHERE st.resolved_at IS NOT NULL AND EXTRACT(EPOCH FROM(st.resolved_at-st.created_at))/3600.0<=tc.sla_hours)/NULLIF(COUNT(*),0),2) AS sla_pct
 FROM support_tickets st JOIN ticket_categories tc USING(category_id) GROUP BY 1,2 ORDER BY 1) x)));

SELECT jsonb_build_object('id','T6-017','check','registered referral grain','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS referral_records,COUNT(*) FILTER(WHERE converted_at IS NOT NULL) AS converted_referrals,
 COUNT(DISTINCT invitee_member_id) FILTER(WHERE converted_at IS NOT NULL) AS distinct_converted_invitees,
 SUM(reward_amount) FILTER(WHERE converted_at IS NULL) AS unconverted_rewards,
 ROUND(100.0*COUNT(*) FILTER(WHERE converted_at IS NOT NULL)/NULLIF(COUNT(*),0),2) AS conversion_pct,
 ROUND(SUM(reward_amount)/NULLIF(COUNT(*) FILTER(WHERE converted_at IS NOT NULL),0),2) AS reward_per_converted
 FROM referrals
) x;

SELECT jsonb_build_object('id','T6-019','check','delivery duration bounds and state identity','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS timestamp_delivered_orders,
 COUNT(*) FILTER(WHERE order_purchase_timestamp IS NULL) AS missing_purchase_time,
 COUNT(*) FILTER(WHERE order_delivered_customer_date<order_purchase_timestamp) AS negative_durations,
 COUNT(*) FILTER(WHERE c.customer_state IS NULL) AS null_customer_states,
 MAX(EXTRACT(EPOCH FROM(order_delivered_customer_date-order_purchase_timestamp))/86400.0) AS max_days
 FROM orders o JOIN customers c USING(customer_id) WHERE order_delivered_customer_date IS NOT NULL
) x;

WITH seller_rate AS (SELECT st.seller_id,SUM(si.gross_amount) AS gross,SUM(si.commission_amount) AS commission FROM settlements st JOIN settlement_items si USING(settlement_id) GROUP BY 1)
SELECT jsonb_build_object('id','T6-020','check','seller denominators and unweighted distribution','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS sellers,COUNT(*) FILTER(WHERE gross<=0 OR gross IS NULL) AS nonpositive_or_null_gross_sellers,
 ROUND(SUM(commission)/NULLIF(SUM(gross),0),4) AS platform_rate,
 ROUND(MIN(commission/NULLIF(gross,0)),4) AS min_seller_rate,
 ROUND(AVG(commission/NULLIF(gross,0)),4) AS avg_seller_rate,
 ROUND(MAX(commission/NULLIF(gross,0)),4) AS max_seller_rate
 FROM seller_rate
) x;

WITH monthly AS (SELECT EXTRACT(YEAR FROM o.order_purchase_timestamp)::int AS yr,EXTRACT(MONTH FROM o.order_purchase_timestamp)::int AS mo,SUM(oi.price+oi.freight_value) AS gmv FROM orders o JOIN order_items oi USING(order_id) WHERE o.order_status NOT IN ('canceled','unavailable') GROUP BY 1,2)
SELECT jsonb_build_object('id','T6-022','check','month coverage both years','result',jsonb_agg(x)) FROM (
 SELECT mo,ROUND(SUM(gmv) FILTER(WHERE yr=2017),2) AS gmv_2017,ROUND(SUM(gmv) FILTER(WHERE yr=2018),2) AS gmv_2018,
 BOOL_OR(yr=2017) AND BOOL_OR(yr=2018) AS emitted_by_gold
 FROM monthly WHERE yr IN (2017,2018) GROUP BY 1 ORDER BY 1
) x;

WITH rr AS (SELECT reason_code,order_id,COUNT(*) AS refunds FROM refunds GROUP BY 1,2),
weighted AS (SELECT rr.reason_code,rr.refunds,rev.review_id,rev.order_id,rev.review_score FROM rr JOIN order_reviews rev USING(order_id))
SELECT jsonb_build_object('id','T6-024','check','review records versus refund-review multiplication','result',jsonb_build_object(
 'repeated_order_reason_pairs',(SELECT COUNT(*) FROM rr WHERE refunds>1),
 'orders_with_multiple_refunds',(SELECT COUNT(*) FROM (SELECT order_id FROM refunds GROUP BY 1 HAVING COUNT(*)>1) d),
 'reviews_with_null_score',(SELECT COUNT(*) FROM weighted WHERE review_score IS NULL),
 'groups',(SELECT jsonb_agg(x) FROM (
 SELECT reason_code,COUNT(*) AS unique_reason_review_records,SUM(refunds) AS gold_join_rows,
 ROUND(AVG(review_score),2) AS review_grain_avg,
 ROUND(SUM(review_score*refunds)::numeric/NULLIF(SUM(refunds) FILTER(WHERE review_score IS NOT NULL),0),2) AS gold_avg,
 ROUND(100.0*COUNT(*) FILTER(WHERE review_score<=2)/NULLIF(COUNT(*),0),2) AS review_grain_low_pct,
 ROUND(100.0*SUM(refunds) FILTER(WHERE review_score<=2)/NULLIF(SUM(refunds),0),2) AS gold_low_pct
 FROM weighted GROUP BY 1 ORDER BY 1) x)));

ROLLBACK;
