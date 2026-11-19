BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';

-- T4-002: both customer and seller geography are physically meaningful.
SELECT jsonb_build_object('check','T4-002-geography','result',jsonb_build_object(
 'item_rows',COUNT(*),'different_customer_seller_states',COUNT(*) FILTER (WHERE c.customer_state IS DISTINCT FROM s.seller_state),
 'goods_amount',ROUND(SUM(oi.price),2),'with_freight',ROUND(SUM(oi.price+oi.freight_value),2),
 'missing_customer',COUNT(*) FILTER(WHERE c.customer_id IS NULL),'missing_seller',COUNT(*) FILTER(WHERE s.seller_id IS NULL)))
FROM order_items oi JOIN orders o ON o.order_id=oi.order_id
LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN sellers s ON s.seller_id=oi.seller_id;
WITH buyer AS (SELECT c.customer_state AS state,ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN orders o ON o.order_id=oi.order_id JOIN customers c ON c.customer_id=o.customer_id GROUP BY 1),
seller AS (SELECT s.seller_state AS state,ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN sellers s ON s.seller_id=oi.seller_id GROUP BY 1)
SELECT jsonb_build_object('check','T4-002-state-results','result',jsonb_agg(x ORDER BY state)) FROM (SELECT COALESCE(b.state,s.state) AS state,b.sales AS customer_sales,s.sales AS seller_sales FROM buyer b FULL JOIN seller s USING(state)) x;

-- T4-004: untranslated non-NULL categories must not be silently lost.
SELECT jsonb_build_object('check','T4-004-coverage','result',jsonb_build_object(
 'all_item_rows',COUNT(*),'null_raw_category_items',COUNT(*) FILTER(WHERE p.product_category_name IS NULL),
 'untranslated_nonnull_items',COUNT(*) FILTER(WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL),
 'untranslated_nonnull_sales',ROUND(SUM(oi.price) FILTER(WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL),2),
 'untranslated_categories',jsonb_agg(DISTINCT p.product_category_name) FILTER(WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL),
 'duplicate_english_name_groups',(SELECT COUNT(*) FROM (SELECT product_category_name_english FROM product_category_translation GROUP BY 1 HAVING COUNT(*)>1) d)))
FROM order_items oi JOIN products p ON p.product_id=oi.product_id LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name;
WITH gold AS (SELECT t.product_category_name_english AS category,ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN products p ON p.product_id=oi.product_id JOIN product_category_translation t ON t.product_category_name=p.product_category_name GROUP BY 1),
preserved AS (SELECT p.product_category_name AS raw_category,t.product_category_name_english AS category,ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN products p ON p.product_id=oi.product_id LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name WHERE p.product_category_name IS NOT NULL GROUP BY 1,2)
SELECT jsonb_build_object('check','T4-004-top','result',jsonb_build_object('gold_top',(SELECT jsonb_agg(g) FROM (SELECT * FROM gold ORDER BY sales DESC LIMIT 3) g),'preserved_top',(SELECT jsonb_agg(p) FROM (SELECT * FROM preserved ORDER BY sales DESC LIMIT 3) p),'gold_first_place_ties',(SELECT COUNT(*) FROM gold WHERE sales=(SELECT MAX(sales) FROM gold))));

SELECT jsonb_build_object('check','T4-006-seller-states','result',jsonb_agg(x ORDER BY sales DESC)) FROM (SELECT s.seller_state,COUNT(*) AS item_rows,ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN sellers s ON s.seller_id=oi.seller_id GROUP BY 1) x;

SELECT jsonb_build_object('check','T4-008-antijoin','result',jsonb_build_object(
 'members',(SELECT COUNT(*) FROM members),'gold_never_tagged',(SELECT COUNT(*) FROM members m LEFT JOIN user_tag_map t ON t.member_id=m.member_id WHERE t.member_id IS NULL),
 'exists_never_tagged',(SELECT COUNT(*) FROM members m WHERE NOT EXISTS(SELECT 1 FROM user_tag_map t WHERE t.member_id=m.member_id)),
 'orphan_member_mappings',(SELECT COUNT(*) FROM user_tag_map t LEFT JOIN members m ON m.member_id=t.member_id WHERE m.member_id IS NULL)));

SELECT jsonb_build_object('check','T4-011-review-status','result',jsonb_agg(x ORDER BY order_status)) FROM (
 SELECT o.order_status,COUNT(DISTINCT o.order_id) AS orders,COUNT(r.order_id) AS reviews,
 COUNT(DISTINCT r.order_id) AS reviewed_orders,ROUND(AVG(r.review_score),2) AS gold_avg_score
 FROM orders o LEFT JOIN order_reviews r ON r.order_id=o.order_id GROUP BY 1) x;

SELECT jsonb_build_object('check','T4-013-bad-order-counts','result',jsonb_agg(x ORDER BY customer_state)) FROM (
 SELECT c.customer_state,COUNT(*) AS gold_review_rows,COUNT(DISTINCT o.order_id) AS distinct_bad_orders,
 COUNT(*)-COUNT(DISTINCT o.order_id) AS overcount
 FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id
 WHERE r.review_score<=2 GROUP BY 1) x;
SELECT jsonb_build_object('check','T4-013-review-cardinality','result',jsonb_build_object(
 'review_rows',COUNT(*),'reviewed_orders',COUNT(DISTINCT order_id),'null_order_ids',COUNT(*) FILTER(WHERE order_id IS NULL),
 'scores_outside_1_to_5',COUNT(*) FILTER(WHERE review_score<1 OR review_score>5),
 'multiple_review_orders',(SELECT COUNT(*) FROM (SELECT order_id FROM order_reviews GROUP BY 1 HAVING COUNT(*)>1) d),
 'bad_review_rows',COUNT(*) FILTER(WHERE review_score<=2),'bad_orders',COUNT(DISTINCT order_id) FILTER(WHERE review_score<=2))) FROM order_reviews;

WITH buyer AS (SELECT r.macro_region,COUNT(*) AS orders FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN regions r ON r.region_code=c.customer_state GROUP BY 1),
seller AS (SELECT r.macro_region,COUNT(DISTINCT o.order_id) AS orders FROM orders o JOIN order_items oi ON oi.order_id=o.order_id JOIN sellers s ON s.seller_id=oi.seller_id JOIN regions r ON r.region_code=s.seller_state GROUP BY 1)
SELECT jsonb_build_object('check','T4-015-regional-subject','result',jsonb_agg(x ORDER BY macro_region)) FROM (SELECT COALESCE(b.macro_region,s.macro_region) AS macro_region,b.orders AS customer_orders,s.orders AS seller_orders FROM buyer b FULL JOIN seller s USING(macro_region)) x;
SELECT jsonb_build_object('check','T4-015-region-coverage','result',jsonb_build_object('orders',COUNT(*),'missing_customer',COUNT(*) FILTER(WHERE c.customer_id IS NULL),'unmapped_customer_state',COUNT(*) FILTER(WHERE r.region_code IS NULL),'null_macro_region',COUNT(*) FILTER(WHERE r.macro_region IS NULL))) FROM orders o LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN regions r ON r.region_code=c.customer_state;

SELECT jsonb_build_object('check','T4-017-member-level-orders','result',jsonb_agg(x ORDER BY level_id)) FROM (
 SELECT m.level_id,COUNT(*) AS gold_count,COUNT(DISTINCT o.order_id) AS distinct_orders
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN members m ON m.customer_unique_id=c.customer_unique_id GROUP BY 1) x;

SELECT jsonb_build_object('check','T4-019-tag-populations','result',jsonb_agg(x ORDER BY tag_id)) FROM (
 SELECT t.tag_id,t.tag_code,t.tag_name,COUNT(DISTINCT tm.member_id) AS mapped_members,COUNT(DISTINCT m.member_id) AS existing_members
 FROM user_tags t LEFT JOIN user_tag_map tm ON tm.tag_id=t.tag_id LEFT JOIN members m ON m.member_id=tm.member_id GROUP BY 1,2,3) x;
SELECT jsonb_build_object('check','T4-019-tag-name-duplicates','result',COALESCE(jsonb_agg(x),'[]'::jsonb)) FROM (SELECT tag_name,COUNT(*) AS tags FROM user_tags GROUP BY 1 HAVING COUNT(*)>1) x;

SELECT jsonb_build_object('check','T4-020-high-value-orders','result',jsonb_build_object(
 'gold_count',COUNT(DISTINCT o.order_id),'joined_rows',COUNT(*),'members',COUNT(DISTINCT m.member_id),
 'matching_tags',(SELECT jsonb_agg(jsonb_build_object('tag_id',tag_id,'tag_code',tag_code,'tag_name',tag_name)) FROM user_tags WHERE tag_code='HIGH_VALUE'),
 'exists_count',(SELECT COUNT(*) FROM orders o2 JOIN customers c2 ON c2.customer_id=o2.customer_id WHERE EXISTS(SELECT 1 FROM members m2 JOIN user_tag_map tm2 ON tm2.member_id=m2.member_id JOIN user_tags t2 ON t2.tag_id=tm2.tag_id WHERE m2.customer_unique_id=c2.customer_unique_id AND t2.tag_code='HIGH_VALUE'))))
FROM user_tag_map tm JOIN user_tags t ON t.tag_id=tm.tag_id JOIN members m ON m.member_id=tm.member_id JOIN customers c ON c.customer_unique_id=m.customer_unique_id JOIN orders o ON o.customer_id=c.customer_id WHERE t.tag_code='HIGH_VALUE';

SELECT jsonb_build_object('check','T4-022-coupon-amount','result',jsonb_build_object('usage_rows',COUNT(*),'gold_discount',ROUND(SUM(discount_amount),2),'null_amounts',COUNT(*) FILTER(WHERE discount_amount IS NULL),'multiple_usage_orders',(SELECT COUNT(*) FROM (SELECT order_id FROM coupon_usages GROUP BY 1 HAVING COUNT(*)>1) d))) FROM coupon_usages;

SELECT jsonb_build_object('check','T4-024-warehouse-coverage','result',jsonb_build_object(
 'shipment_rows',(SELECT COUNT(*) FROM shipments),'null_warehouse_shipments',(SELECT COUNT(*) FROM shipments WHERE warehouse_id IS NULL),
 'unmatched_warehouse_shipments',(SELECT COUNT(*) FROM shipments s LEFT JOIN warehouses w ON w.warehouse_id=s.warehouse_id WHERE w.warehouse_id IS NULL),
 'warehouses_without_shipments',(SELECT COUNT(*) FROM warehouses w WHERE NOT EXISTS(SELECT 1 FROM shipments s WHERE s.warehouse_id=w.warehouse_id)),
 'duplicate_name_groups',(SELECT COUNT(*) FROM (SELECT warehouse_name FROM warehouses GROUP BY 1 HAVING COUNT(*)>1) d),
 'gold',(SELECT jsonb_agg(x ORDER BY cnt DESC) FROM (SELECT w.warehouse_name,COUNT(*) AS cnt FROM shipments s JOIN warehouses w ON w.warehouse_id=s.warehouse_id GROUP BY 1) x)));

SELECT jsonb_build_object('check','T4-026-carrier-days','result',jsonb_agg(x ORDER BY carrier)) FROM (
 SELECT carrier,COUNT(*) AS all_shipments,COUNT(*) FILTER(WHERE delivered_at IS NOT NULL AND shipped_at IS NOT NULL) AS timed_shipments,
 ROUND(AVG(EXTRACT(EPOCH FROM (delivered_at-shipped_at))/86400) FILTER(WHERE delivered_at IS NOT NULL AND shipped_at IS NOT NULL),2) AS avg_days,
 COUNT(*) FILTER(WHERE delivered_at<shipped_at) AS negative_durations
 FROM shipments GROUP BY 1) x;

SELECT jsonb_build_object('check','T4-028-category-populations','result',jsonb_agg(x ORDER BY category_id)) FROM (
 SELECT tc.category_id,tc.category_name,COUNT(st.ticket_id) AS all_tickets,
 COUNT(st.ticket_id) FILTER(WHERE st.resolved_at IS NOT NULL) AS resolved_tickets,
 ROUND(AVG(EXTRACT(EPOCH FROM(st.resolved_at-st.created_at))/3600) FILTER(WHERE st.resolved_at IS NOT NULL),2) AS avg_hours
 FROM ticket_categories tc LEFT JOIN support_tickets st ON st.category_id=tc.category_id GROUP BY 1,2) x;
SELECT jsonb_build_object('check','T4-028-coverage','result',jsonb_build_object(
 'tickets',COUNT(*),'null_category',COUNT(*) FILTER(WHERE st.category_id IS NULL),
 'resolved_without_category',COUNT(*) FILTER(WHERE st.resolved_at IS NOT NULL AND tc.category_id IS NULL),
 'resolved_missing_created_at',COUNT(*) FILTER(WHERE st.resolved_at IS NOT NULL AND st.created_at IS NULL),
 'negative_durations',COUNT(*) FILTER(WHERE st.resolved_at<st.created_at),
 'duplicate_name_groups',(SELECT COUNT(*) FROM (SELECT category_name FROM ticket_categories GROUP BY 1 HAVING COUNT(*)>1) d))) FROM support_tickets st LEFT JOIN ticket_categories tc ON tc.category_id=st.category_id;

SELECT jsonb_build_object('check','T4-031-settlement-coverage','result',jsonb_build_object('settlement_rows',COUNT(*),'unmatched_seller',COUNT(*) FILTER(WHERE s.seller_id IS NULL),'net_total',ROUND(SUM(st.net_amount),2),'matched_net_total',ROUND(SUM(st.net_amount) FILTER(WHERE s.seller_id IS NOT NULL),2),'null_net_amounts',COUNT(*) FILTER(WHERE st.net_amount IS NULL))) FROM settlements st LEFT JOIN sellers s ON s.seller_id=st.seller_id;
SELECT jsonb_build_object('check','T4-031-state-totals','result',jsonb_agg(x ORDER BY total DESC)) FROM (SELECT s.seller_state,ROUND(SUM(st.net_amount),2) AS total FROM settlements st JOIN sellers s ON s.seller_id=st.seller_id GROUP BY 1) x;

SELECT jsonb_build_object('check','T4-033-refund-reasons','result',jsonb_agg(x ORDER BY total DESC)) FROM (SELECT reason_code,COUNT(*) AS refund_records,ROUND(SUM(refund_amount),2) AS total,COUNT(*) FILTER(WHERE refund_amount IS NULL) AS null_amounts FROM refunds GROUP BY 1) x;

SELECT jsonb_build_object('check','T4-035-order-population','result',jsonb_build_object(
 'valid_orders',COUNT(*),'valid_orders_without_items',COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=o.order_id)),
 'people_with_valid_orders_without_items',COUNT(DISTINCT c.customer_unique_id) FILTER(WHERE NOT EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=o.order_id))))
FROM orders o JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable');
WITH totals AS (SELECT order_id,SUM(price+freight_value) AS amount FROM order_items GROUP BY 1),
gold AS (SELECT c.customer_unique_id,ROUND(SUM(t.amount)/COUNT(o.order_id),2) AS avg_order_value FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN totals t ON t.order_id=o.order_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY 1),
all_valid AS (SELECT c.customer_unique_id,ROUND(SUM(t.amount)/COUNT(o.order_id),2) AS avg_order_value FROM orders o JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN totals t ON t.order_id=o.order_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY 1),
g10 AS (SELECT * FROM gold ORDER BY avg_order_value DESC LIMIT 10),
a10 AS (SELECT * FROM all_valid WHERE avg_order_value IS NOT NULL ORDER BY avg_order_value DESC LIMIT 10)
SELECT jsonb_build_object('check','T4-035-top10-comparison','result',jsonb_build_object(
 'gold_top10',(SELECT jsonb_agg(g ORDER BY avg_order_value DESC) FROM g10 g),
 'all_valid_top10',(SELECT jsonb_agg(a ORDER BY avg_order_value DESC) FROM a10 a),
 'changed_nonnull_people',(SELECT COUNT(*) FROM gold g JOIN all_valid a USING(customer_unique_id) WHERE g.avg_order_value IS DISTINCT FROM a.avg_order_value),
 'all_valid_null_amount_people',(SELECT COUNT(*) FROM all_valid WHERE avg_order_value IS NULL),
 'cutoff_tied_people',(SELECT COUNT(*) FROM gold WHERE avg_order_value=(SELECT MIN(avg_order_value) FROM g10))));

WITH sold AS (SELECT p.product_id,p.product_photos_qty,COUNT(*) AS sold FROM order_items oi JOIN products p ON p.product_id=oi.product_id GROUP BY 1,2),
top10 AS (SELECT * FROM sold ORDER BY sold DESC LIMIT 10)
SELECT jsonb_build_object('check','T4-037-sales-top10','result',jsonb_build_object('top10',(SELECT jsonb_agg(t ORDER BY sold DESC) FROM top10 t),'cutoff_tied_products',(SELECT COUNT(*) FROM sold WHERE sold=(SELECT MIN(sold) FROM top10)),'all_products',(SELECT COUNT(*) FROM products),'sold_products',(SELECT COUNT(*) FROM sold)));

SELECT jsonb_build_object('check','T4-039-search-ratio','result',jsonb_build_object('search_records',COUNT(*),'clicked_search_records',COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL),'gold_decimal_ratio',ROUND(COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL)::numeric/COUNT(*),4),'percent_if_registered_ctr',ROUND(100.0*COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL)/NULLIF(COUNT(*),0),2),'empty_population_count',(SELECT COUNT(*) FROM search_logs WHERE FALSE))) FROM search_logs;

SELECT jsonb_build_object('check','T4-040-inventory-antijoin','result',jsonb_build_object('products',(SELECT COUNT(*) FROM products),'gold_never_stocked',(SELECT COUNT(*) FROM products p LEFT JOIN inventory i ON i.product_id=p.product_id WHERE i.product_id IS NULL),'exists_never_stocked',(SELECT COUNT(*) FROM products p WHERE NOT EXISTS(SELECT 1 FROM inventory i WHERE i.product_id=p.product_id)),'multi_warehouse_products',(SELECT COUNT(*) FROM (SELECT product_id FROM inventory GROUP BY 1 HAVING COUNT(*)>1) d),'zero_or_negative_quantity_rows',(SELECT COUNT(*) FROM inventory WHERE quantity<=0)));

WITH per_product AS (SELECT product_id,COUNT(*) AS histories,BOOL_OR(new_price>old_price) AS ever_up,BOOL_OR(new_price<old_price) AS ever_down FROM product_price_history GROUP BY 1),
latest_time AS (SELECT product_id,MAX(changed_at) AS max_time FROM product_price_history GROUP BY 1),
latest AS (SELECT h.* FROM product_price_history h JOIN latest_time t ON t.product_id=h.product_id AND t.max_time=h.changed_at)
SELECT jsonb_build_object('check','T4-042-rise-interpretations','result',jsonb_build_object('history_rows',(SELECT COUNT(*) FROM product_price_history),'adjusted_products',(SELECT COUNT(*) FROM per_product),'gold_ever_up',(SELECT COUNT(*) FROM per_product WHERE ever_up),'ever_up_and_ever_down',(SELECT COUNT(*) FROM per_product WHERE ever_up AND ever_down),'latest_up_products',(SELECT COUNT(DISTINCT product_id) FROM latest WHERE new_price>old_price),'multiple_history_products',(SELECT COUNT(*) FROM per_product WHERE histories>1),'latest_timestamp_tie_products',(SELECT COUNT(*) FROM (SELECT product_id FROM latest GROUP BY 1 HAVING COUNT(*)>1) d),'null_price_or_time_histories',(SELECT COUNT(*) FROM product_price_history WHERE old_price IS NULL OR new_price IS NULL OR changed_at IS NULL),'orphan_product_histories',(SELECT COUNT(*) FROM product_price_history h LEFT JOIN products p ON p.product_id=h.product_id WHERE p.product_id IS NULL)));

SELECT jsonb_build_object('check','T4-044-region-populations','result',jsonb_agg(x ORDER BY macro_region)) FROM (
 SELECT r.macro_region,COUNT(DISTINCT c.customer_id) AS customer_records,COUNT(DISTINCT c.customer_unique_id) AS gold_people,
 COUNT(o.order_id) AS orders,ROUND(COUNT(o.order_id)::numeric/COUNT(DISTINCT c.customer_unique_id),2) AS gold_orders_per_person,
 ROUND(COUNT(o.order_id)::numeric/COUNT(DISTINCT c.customer_id),2) AS orders_per_customer_record
 FROM customers c JOIN regions r ON r.region_code=c.customer_state LEFT JOIN orders o ON o.customer_id=c.customer_id GROUP BY 1) x;
SELECT jsonb_build_object('check','T4-044-coverage','result',jsonb_build_object('customer_records',(SELECT COUNT(*) FROM customers),'distinct_people',(SELECT COUNT(DISTINCT customer_unique_id) FROM customers),'customers_without_orders',(SELECT COUNT(*) FROM customers c WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.customer_id=c.customer_id)),'unmapped_region_customers',(SELECT COUNT(*) FROM customers c LEFT JOIN regions r ON r.region_code=c.customer_state WHERE r.region_code IS NULL),'people_with_multiple_macro_regions',(SELECT COUNT(*) FROM (SELECT c.customer_unique_id FROM customers c JOIN regions r ON r.region_code=c.customer_state GROUP BY 1 HAVING COUNT(DISTINCT r.macro_region)>1) d)));
COMMIT;
