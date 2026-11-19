-- 查询目录：每个CASE独立包裹BEGIN READ ONLY、SET LOCAL statement_timeout='8s'、ROLLBACK执行。
-- 实际SQL与结果、synthetic和timeoutMs见reviewer-check-results.json；预期除零反例是诊断证据。
-- CASE schema synthetic=false
SELECT c.table_name,c.column_name,c.data_type,c.is_nullable,col_description(('public.'||c.table_name)::regclass,c.ordinal_position) AS comment FROM information_schema.columns c WHERE c.table_schema='public' AND c.table_name NOT IN('schema_chunks','semantic_cache','llm_call_log','conversation_session','conversation_turn') ORDER BY c.table_name,c.ordinal_position;
-- CASE constraints synthetic=false
SELECT c.relname AS table_name,conname,pg_get_constraintdef(con.oid) AS definition FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname NOT IN('schema_chunks','semantic_cache','llm_call_log','conversation_session','conversation_turn') ORDER BY c.relname,conname;
-- CASE basic-values synthetic=false
SELECT (SELECT COUNT(DISTINCT customer_city) FROM customers) AS city_names,(SELECT COUNT(DISTINCT (customer_state,customer_city)) FROM customers) AS state_city_pairs,(SELECT COUNT(*) FROM order_reviews WHERE review_comment_title IS NOT NULL) AS nonnull_titles,(SELECT COUNT(*) FROM order_reviews WHERE review_comment_title IS NOT NULL AND BTRIM(review_comment_title)='') AS blank_titles,(SELECT COUNT(*) FROM members WHERE points IS NULL) AS null_points,(SELECT COUNT(*) FROM warehouses WHERE capacity IS NULL) AS null_capacity,(SELECT COUNT(*) FROM order_payments WHERE payment_type IS NULL) AS null_payment_type,(SELECT COUNT(*) FROM products WHERE product_category_name IS NOT NULL AND product_weight_g IS NULL) AS known_category_missing_weight;
-- CASE member-level-values synthetic=false
SELECT l.level_id,l.level_name,COUNT(m.member_id) AS members FROM member_levels l LEFT JOIN members m USING(level_id) GROUP BY l.level_id,l.level_name ORDER BY l.level_id;
-- CASE null-and-tie-boundaries synthetic=false
WITH x AS(SELECT 'T1-013' AS id,freight_value AS v FROM order_items UNION ALL SELECT 'T1-026',points::numeric FROM members),nth AS(SELECT id,v,ROW_NUMBER() OVER(PARTITION BY id ORDER BY v DESC NULLS LAST) AS n FROM x),cut AS(SELECT id,v FROM nth WHERE(id='T1-013' AND n=5) OR(id='T1-026' AND n=10)) SELECT cut.id,cut.v AS cutoff,COUNT(*) FILTER(WHERE x.v>cut.v) AS above,COUNT(*) FILTER(WHERE x.v=cut.v) AS tied FROM cut JOIN x ON x.id=cut.id GROUP BY cut.id,cut.v;
-- CASE topn-group-cutoffs synthetic=false
WITH groups AS(
SELECT 'T2-009' AS id,COUNT(*)::numeric AS v FROM products WHERE product_category_name IS NOT NULL GROUP BY product_category_name
UNION ALL SELECT 'T2-012',ROUND(SUM(freight_value),2) FROM order_items GROUP BY seller_id
UNION ALL SELECT 'T4-001',COUNT(*) FROM orders o JOIN customers c USING(customer_id) GROUP BY c.customer_unique_id
UNION ALL SELECT 'T4-005',COUNT(DISTINCT order_id) FROM order_items GROUP BY seller_id
UNION ALL SELECT 'T4-012',COUNT(*) FROM order_reviews r JOIN orders o USING(order_id) JOIN customers c USING(customer_id) GROUP BY c.customer_state
UNION ALL SELECT 'T4-030',ROUND(SUM(total_amount),2) FROM settlements GROUP BY seller_id
UNION ALL SELECT 'T4-045',COUNT(*) FROM products WHERE product_category_name IS NOT NULL AND product_weight_g IS NOT NULL GROUP BY product_category_name),r AS(SELECT id,v,ROW_NUMBER() OVER(PARTITION BY id ORDER BY v DESC NULLS LAST) AS n FROM groups),c AS(SELECT id,v FROM r WHERE n=10) SELECT c.id,c.v AS cutoff,COUNT(*) FILTER(WHERE g.v>c.v) AS above,COUNT(*) FILTER(WHERE g.v=c.v) AS tied FROM c JOIN groups g ON g.id=c.id GROUP BY c.id,c.v ORDER BY c.id;
-- CASE recent-windows synthetic=false
WITH a AS(SELECT MAX(order_purchase_timestamp) AS t FROM orders),b AS(SELECT MAX(last_login_date) AS d FROM members) SELECT (SELECT t FROM a) AS order_anchor,(SELECT d FROM b) AS login_anchor,(SELECT COUNT(*) FROM orders,a WHERE order_purchase_timestamp>t-INTERVAL '90 days') AS rolling90_orders,(SELECT COUNT(*) FROM orders,a WHERE order_purchase_timestamp>=t::date-INTERVAL '89 days' AND order_purchase_timestamp<t::date+INTERVAL '1 day') AS calendar90_orders,(SELECT COUNT(*) FROM members,b WHERE last_login_date>d-30) AS gold_login30,(SELECT COUNT(*) FROM members,b WHERE last_login_date>=d-29 AND last_login_date<d+1) AS calendar_login30;
-- CASE sparse-month-rate synthetic=false
WITH m AS(SELECT DATE_TRUNC('month',order_purchase_timestamp) AS m,COUNT(*) AS n FROM orders GROUP BY 1),r AS(SELECT *,LAG(m) OVER(ORDER BY m) AS prev_m,LAG(n) OVER(ORDER BY m) AS prev_n FROM m) SELECT *,ROUND((n-prev_n)::numeric/NULLIF(prev_n,0),4) AS gold_rate FROM r WHERE m>prev_m+INTERVAL '1 month';
-- CASE order-item-coverage synthetic=false
SELECT DATE_TRUNC('month',o.order_purchase_timestamp) AS month,COUNT(*) AS orders,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=o.order_id)) AS zero_item_orders FROM orders o GROUP BY 1 ORDER BY 1;
-- CASE event-time-coverage synthetic=false
SELECT 'orders' AS source,MIN(order_purchase_timestamp) AS lo,MAX(order_purchase_timestamp) AS hi,COUNT(*) FILTER(WHERE order_purchase_timestamp IS NULL) AS unknown FROM orders UNION ALL SELECT 'refunds',MIN(requested_at),MAX(requested_at),COUNT(*) FILTER(WHERE requested_at IS NULL) FROM refunds UNION ALL SELECT 'tickets',MIN(created_at),MAX(created_at),COUNT(*) FILTER(WHERE created_at IS NULL) FROM support_tickets UNION ALL SELECT 'reviews',MIN(review_creation_date),MAX(review_creation_date),COUNT(*) FILTER(WHERE review_creation_date IS NULL) FROM order_reviews UNION ALL SELECT 'movements',MIN(moved_at),MAX(moved_at),COUNT(*) FILTER(WHERE moved_at IS NULL) FROM inventory_movements;
-- CASE payment-review-weighting synthetic=false
WITH direct AS(SELECT p.payment_type,ROUND(AVG(r.review_score),2) AS v,COUNT(*) AS n FROM order_payments p JOIN order_reviews r USING(order_id) GROUP BY p.payment_type),dedup AS(SELECT p.payment_type,ROUND(AVG(r.review_score),2) AS v,COUNT(*) AS n FROM(SELECT DISTINCT order_id,payment_type FROM order_payments)p JOIN order_reviews r USING(order_id) GROUP BY p.payment_type) SELECT d.payment_type,d.v AS gold_avg,d.n AS gold_pairs,e.v AS review_equal_avg,e.n AS distinct_reviews FROM direct d JOIN dedup e USING(payment_type) ORDER BY payment_type;
-- CASE campaign-coverage synthetic=false
SELECT c.campaign_id,c.campaign_name,COUNT(cu.usage_id) AS uses FROM campaigns c LEFT JOIN coupons cp USING(campaign_id) LEFT JOIN coupon_usages cu USING(coupon_id) GROUP BY c.campaign_id,c.campaign_name ORDER BY c.campaign_id;
-- CASE names-and-current-map synthetic=false
SELECT (SELECT COUNT(*) FROM(SELECT campaign_name FROM campaigns GROUP BY 1 HAVING COUNT(*)>1)x) AS repeated_campaign_names,(SELECT COUNT(*) FROM(SELECT brand_name FROM brands GROUP BY 1 HAVING COUNT(*)>1)x) AS repeated_brand_names,(SELECT COUNT(*) FROM(SELECT warehouse_name FROM warehouses GROUP BY 1 HAVING COUNT(*)>1)x) AS repeated_warehouse_names,(SELECT COUNT(*) FROM(SELECT level_name FROM member_levels GROUP BY 1 HAVING COUNT(*)>1)x) AS repeated_level_names,(SELECT COUNT(*) FROM product_brand_map WHERE NOT COALESCE(is_current,FALSE)) AS historical_brand_maps,(SELECT COUNT(*) FROM(SELECT product_id FROM product_brand_map WHERE is_current GROUP BY 1 HAVING COUNT(*)>1)x) AS multiple_current_brands;
-- CASE ticket-coverage synthetic=false
SELECT COUNT(*) AS all_tickets,COUNT(*) FILTER(WHERE order_id IS NULL) AS no_order,COUNT(*) FILTER(WHERE member_id IS NULL) AS no_member,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM ticket_messages tm WHERE tm.ticket_id=st.ticket_id)) AS no_messages,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.order_id=st.order_id)) AS unmatched_order FROM support_tickets st;
-- CASE ticket-category-coverage synthetic=false
SELECT tc.category_id,COUNT(st.ticket_id) AS all_tickets,COUNT(st.ticket_id) FILTER(WHERE EXISTS(SELECT 1 FROM ticket_messages tm WHERE tm.ticket_id=st.ticket_id)) AS with_message FROM ticket_categories tc LEFT JOIN support_tickets st USING(category_id) GROUP BY tc.category_id ORDER BY tc.category_id;
-- CASE shipment-tracking-coverage synthetic=false
SELECT s.status,COUNT(*) AS shipments,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM shipment_tracking t WHERE t.shipment_id=s.shipment_id)) AS zero_tracking FROM shipments s GROUP BY s.status ORDER BY s.status;
-- CASE refund-category-attribution synthetic=false
WITH all_items AS(SELECT DISTINCT rf.refund_id,p.product_category_name AS c FROM refunds rf JOIN order_items oi ON oi.order_id=rf.order_id JOIN products p USING(product_id)),actual_items AS(SELECT DISTINCT ri.refund_id,p.product_category_name AS c FROM refund_items ri JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id JOIN products p USING(product_id)) SELECT (SELECT COUNT(*) FROM all_items) AS order_category_pairs,(SELECT COUNT(*) FROM actual_items) AS refunded_category_pairs,(SELECT COUNT(*) FROM all_items a WHERE NOT EXISTS(SELECT 1 FROM actual_items b WHERE b.refund_id=a.refund_id AND b.c IS NOT DISTINCT FROM a.c)) AS extra_order_categories;
-- CASE brand-product-population synthetic=false
WITH base AS(SELECT b.brand_id,b.brand_name,COUNT(DISTINCT bm.product_id) AS all_products FROM brands b JOIN product_brand_map bm USING(brand_id) GROUP BY b.brand_id,b.brand_name),sold AS(SELECT b.brand_id,COUNT(DISTINCT oi.product_id) AS sold_products FROM brands b JOIN product_brand_map bm USING(brand_id) JOIN order_items oi USING(product_id) GROUP BY b.brand_id) SELECT COUNT(*) AS brands,COUNT(*) FILTER(WHERE b.all_products<>s.sold_products) AS brands_with_unsold_products,SUM(b.all_products) AS mapped_products,SUM(s.sold_products) AS sold_products FROM base b JOIN sold s USING(brand_id);
-- CASE price-weight-population synthetic=false
WITH a AS(SELECT product_category_name,COUNT(*) AS all_n,COUNT(product_weight_g) AS known_n,ROUND(AVG(product_weight_g),2) AS avg_weight FROM products WHERE product_category_name IS NOT NULL GROUP BY 1) SELECT * FROM a WHERE all_n<>known_n ORDER BY all_n DESC;
-- CASE shipment-region-loss synthetic=false
SELECT COUNT(*) AS shipments,COUNT(*) FILTER(WHERE s.warehouse_id IS NULL) AS no_warehouse,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.order_id=s.order_id)) AS no_order,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=s.order_id)) AS no_items FROM shipments s;
-- CASE referrals-coverage synthetic=false
SELECT COUNT(*) AS referrals,COUNT(*) FILTER(WHERE invitee_member_id IS NULL) AS no_invitee,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM members m WHERE m.member_id=rf.invitee_member_id)) AS unmatched_invitee FROM referrals rf;
-- CASE campaign-time-attribution synthetic=false
SELECT COUNT(*) AS item_campaign_pairs,COUNT(*) FILTER(WHERE o.order_purchase_timestamp<c.start_date OR o.order_purchase_timestamp>=c.end_date+INTERVAL '1 day') AS outside_campaign_dates FROM campaign_products cp JOIN campaigns c USING(campaign_id) JOIN order_items oi USING(product_id) JOIN orders o USING(order_id);
-- CASE movement-inventory-loss synthetic=false
SELECT COUNT(*) AS movements,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM inventory i WHERE i.warehouse_id=im.warehouse_id AND i.product_id=im.product_id)) AS missing_current_inventory FROM inventory_movements im;
-- CASE member-level-full-population synthetic=false
WITH f AS(SELECT m.member_id,m.level_id,COUNT(DISTINCT o.order_id) AS all_valid_orders,COUNT(DISTINCT o.order_id) FILTER(WHERE EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=o.order_id)) AS item_valid_orders FROM members m LEFT JOIN customers c ON c.customer_unique_id=m.customer_unique_id LEFT JOIN orders o ON o.customer_id=c.customer_id AND o.order_status NOT IN('canceled','unavailable') GROUP BY m.member_id,m.level_id) SELECT level_id,COUNT(*) AS all_members,COUNT(*) FILTER(WHERE item_valid_orders>0) AS gold_users,COUNT(*) FILTER(WHERE all_valid_orders=0) AS no_valid_orders,SUM(all_valid_orders-item_valid_orders) AS missing_no_item_orders FROM f GROUP BY level_id ORDER BY level_id;
-- CASE turnover-window synthetic=false
WITH a AS(SELECT MAX(moved_at) AS mx FROM inventory_movements) SELECT im.warehouse_id,ROUND(SUM(im.quantity) FILTER(WHERE im.movement_type=2 AND im.moved_at>a.mx-INTERVAL '90 days'),2) AS rolling_out,SUM(im.quantity) FILTER(WHERE im.movement_type=2 AND im.moved_at>=a.mx::date-INTERVAL '89 days' AND im.moved_at<a.mx::date+INTERVAL '1 day') AS calendar_out FROM inventory_movements im CROSS JOIN a GROUP BY im.warehouse_id ORDER BY im.warehouse_id;
-- CASE null-candidate synthetic=true
WITH candidates(id,points) AS(VALUES(1,10),(2,NULL::int)) SELECT id,points FROM candidates ORDER BY points DESC LIMIT 1;
-- CASE names-merge synthetic=true
WITH entities(id,name,budget) AS(VALUES(1,'same',10),(2,'same',20)) SELECT name,SUM(budget) AS merged,COUNT(*) AS entity_count FROM entities GROUP BY name;
-- CASE zero-parent-average synthetic=true
WITH shipments(id) AS(VALUES(1),(2)),events(shipment_id,id) AS(VALUES(1,1),(1,2)) SELECT ROUND((SELECT AVG(n) FROM(SELECT shipment_id,COUNT(*) AS n FROM events GROUP BY shipment_id)x),2) AS inner_avg,ROUND((SELECT AVG(n) FROM(SELECT s.id,COUNT(e.id) AS n FROM shipments s LEFT JOIN events e ON e.shipment_id=s.id GROUP BY s.id)x),2) AS full_avg;
-- CASE payment-review-counterexample synthetic=true
WITH payments(order_id,seq,kind) AS(VALUES('a',1,'card'),('a',2,'card'),('b',1,'card')),reviews(order_id,score) AS(VALUES('a',1),('b',5)) SELECT ROUND((SELECT AVG(score) FROM payments p JOIN reviews r USING(order_id)),2) AS payment_weighted,ROUND((SELECT AVG(score) FROM(SELECT DISTINCT order_id,kind FROM payments)p JOIN reviews r USING(order_id)),2) AS review_equal;
-- CASE empty-rate-unprotected synthetic=true
WITH x(n) AS(SELECT 1 WHERE FALSE) SELECT COUNT(*)::numeric/COUNT(*) AS rate FROM x;
-- CASE empty-rate-safe synthetic=true
WITH x(n) AS(SELECT 1 WHERE FALSE) SELECT COUNT(*)::numeric/NULLIF(COUNT(*),0) AS rate FROM x;
-- CASE ntile-shape synthetic=true
WITH users(id,gmv) AS(VALUES(1,10),(2,9),(3,8),(4,7),(5,6),(6,5)),r AS(SELECT *,NTILE(5) OVER(ORDER BY gmv DESC,id) AS q FROM users) SELECT q,COUNT(*) AS users,SUM(gmv) AS gmv FROM r GROUP BY q ORDER BY q;
-- CASE extra-join-pair-differences synthetic=false
WITH base AS(SELECT DISTINCT st.event_code,c.customer_state,s.shipment_id FROM shipment_tracking st JOIN shipments s USING(shipment_id) JOIN orders o ON o.order_id=s.order_id JOIN customers c USING(customer_id)),restricted AS(SELECT DISTINCT st.event_code,c.customer_state,s.shipment_id FROM shipment_tracking st JOIN shipments s USING(shipment_id) JOIN orders o ON o.order_id=s.order_id JOIN customers c USING(customer_id) JOIN order_items oi ON oi.order_id=o.order_id) SELECT (SELECT COUNT(*) FROM base) AS base_pairs,(SELECT COUNT(*) FROM restricted) AS gold_pairs,(SELECT COUNT(*) FROM refunds r WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.order_id=r.order_id)) AS orphan_refund_orders;
-- CASE price-change-averaging synthetic=false
WITH direct AS(SELECT p.product_category_name,ROUND(AVG(h.new_price-h.old_price),2) AS amount_mean,COUNT(*) AS n FROM product_price_history h JOIN products p USING(product_id) GROUP BY 1),weighted AS(SELECT p.product_category_name,ROUND(AVG(h.new_price-h.old_price),2) AS amount_mean,COUNT(*) AS n FROM product_price_history h JOIN products p USING(product_id) JOIN order_items oi USING(product_id) GROUP BY 1) SELECT d.product_category_name,d.amount_mean AS record_equal,d.n AS change_records,w.amount_mean AS sale_weighted,w.n AS joined_rows FROM direct d JOIN weighted w ON w.product_category_name IS NOT DISTINCT FROM d.product_category_name WHERE d.amount_mean IS DISTINCT FROM w.amount_mean ORDER BY d.n DESC LIMIT 8;
-- CASE full-membership-small synthetic=true
WITH members(id) AS(VALUES(1),(2)),orders(id,member_id) AS(VALUES(1,1)),items(order_id,price) AS(VALUES(1,10)) SELECT (SELECT COUNT(*) FROM members m JOIN orders o ON o.member_id=m.id JOIN items i ON i.order_id=o.id) AS gold_member_count,(SELECT COUNT(*) FROM members) AS all_member_count;
-- CASE required-zero-message synthetic=true
WITH tickets(id,category) AS(VALUES(1,'A'),(2,'A')),messages(id,ticket_id) AS(VALUES(1,1)) SELECT (SELECT COUNT(DISTINCT t.id) FROM tickets t JOIN messages m ON m.ticket_id=t.id) AS inner_tickets,(SELECT COUNT(DISTINCT t.id) FROM tickets t LEFT JOIN messages m ON m.ticket_id=t.id) AS left_tickets;
-- CASE irrelevant-child-filter synthetic=true
WITH movement(id,warehouse_id,product_id,qty) AS(VALUES(1,1,'p',4)),inventory(warehouse_id,product_id) AS(SELECT 1,'p' WHERE FALSE) SELECT (SELECT COUNT(*) FROM movement m JOIN inventory i USING(warehouse_id,product_id)) AS gold_rows,(SELECT COUNT(*) FROM movement) AS movement_rows;
-- CASE low-score-unit-comparison synthetic=false
SELECT c.customer_state,COUNT(*) AS reviews,COUNT(DISTINCT o.order_id) AS reviewed_orders,ROUND(100.0*COUNT(*) FILTER(WHERE r.review_score<=2)/COUNT(*),2) AS review_pct,ROUND(100.0*COUNT(DISTINCT o.order_id) FILTER(WHERE r.review_score<=2)/COUNT(DISTINCT o.order_id),2) AS reviewed_order_pct FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_state ORDER BY c.customer_state;
-- CASE hour-maximum-ties synthetic=false
WITH counts AS(SELECT EXTRACT(HOUR FROM order_purchase_timestamp)::int AS h,COUNT(*) AS n FROM orders GROUP BY h) SELECT h,n FROM counts WHERE n=(SELECT MAX(n) FROM counts) ORDER BY h;
-- CASE unknown-dimension synthetic=true
WITH payments(id,kind,amount) AS(VALUES(1,'card',10),(2,NULL::text,20)) SELECT kind,AVG(amount) AS avg_amount FROM payments GROUP BY kind ORDER BY kind NULLS LAST;
-- CASE gold-level-surrogate synthetic=true
WITH levels(id,name) AS(VALUES(2,'黄金会员'),(3,'青铜会员')),members(id,level_id) AS(VALUES(1,2),(2,2),(3,3)) SELECT (SELECT COUNT(*) FROM members WHERE level_id=3) AS hardcoded_gold,(SELECT COUNT(*) FROM members m JOIN levels l ON l.id=m.level_id WHERE l.name='黄金会员') AS named_gold;
