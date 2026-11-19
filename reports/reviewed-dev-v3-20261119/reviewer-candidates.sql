-- 候选SQL目录：每个CASE以text2sql_ro在BEGIN READ ONLY、8s statement_timeout下独立执行。
-- 仅为审查建议，不修改原Gold；附带明确题干的候选须连同题干条件审核，执行成功不等于语义批准。
-- CASE T1-020 synthetic=false
SELECT COUNT(*) FROM order_reviews WHERE review_comment_title IS NOT NULL AND BTRIM(review_comment_title)<>'';
-- CASE T1-024 synthetic=false
SELECT COUNT(*) FROM members m JOIN member_levels l ON l.level_id=m.level_id WHERE l.level_name='黄金会员';
-- CASE T1-026 synthetic=false
SELECT member_id,points FROM members WHERE points IS NOT NULL ORDER BY points DESC,member_id ASC LIMIT 10;
-- CASE T1-028 synthetic=false
SELECT warehouse_id,capacity FROM warehouses WHERE capacity IS NOT NULL ORDER BY capacity DESC,warehouse_id ASC LIMIT 1;
-- CASE T3-011 synthetic=false
WITH monthly AS(SELECT DATE_TRUNC('month',order_purchase_timestamp) AS m,COUNT(*) AS cnt FROM orders WHERE order_purchase_timestamp IS NOT NULL GROUP BY 1),calendar AS(SELECT generate_series(MIN(m),MAX(m),INTERVAL '1 month') AS m FROM monthly),aligned AS(SELECT c.m,m.cnt FROM calendar c LEFT JOIN monthly m USING(m)) SELECT m AS month,ROUND((cnt-LAG(cnt) OVER(ORDER BY m))::numeric/NULLIF(LAG(cnt) OVER(ORDER BY m),0),4) AS mom_rate FROM aligned ORDER BY m LIMIT ALL;
-- CASE T3-013 synthetic=false
WITH anchor AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders) SELECT COUNT(*) FROM orders o CROSS JOIN anchor a WHERE o.order_purchase_timestamp>=a.mx::date-INTERVAL '89 days' AND o.order_purchase_timestamp<a.mx::date+INTERVAL '1 day';
-- CASE T4-001 synthetic=false
SELECT c.customer_unique_id,COUNT(*) AS cnt FROM orders o JOIN customers c ON c.customer_id=o.customer_id GROUP BY c.customer_unique_id ORDER BY cnt DESC,c.customer_unique_id ASC NULLS LAST LIMIT 10;
-- CASE T4-009 synthetic=false
SELECT payment_type,ROUND(AVG(payment_value),2) AS avg_value FROM order_payments GROUP BY payment_type ORDER BY avg_value DESC NULLS LAST,payment_type ASC NULLS LAST LIMIT ALL;
-- CASE T4-012 synthetic=false
SELECT c.customer_state,ROUND(AVG(r.review_score),2) AS avg_score FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id GROUP BY c.customer_state ORDER BY COUNT(*) DESC,c.customer_state ASC NULLS LAST LIMIT 10;
-- CASE T4-014 synthetic=false
WITH order_types AS(SELECT DISTINCT order_id,payment_type FROM order_payments) SELECT p.payment_type,ROUND(AVG(r.review_score),2) AS avg_score FROM order_types p JOIN order_reviews r ON r.order_id=p.order_id GROUP BY p.payment_type ORDER BY avg_score DESC NULLS LAST,p.payment_type ASC NULLS LAST LIMIT ALL;
-- CASE T4-021 synthetic=false
SELECT c.campaign_id,COUNT(*) AS cnt FROM coupon_usages cu JOIN coupons cp ON cp.coupon_id=cu.coupon_id JOIN campaigns c ON c.campaign_id=cp.campaign_id GROUP BY c.campaign_id ORDER BY cnt DESC,c.campaign_id ASC LIMIT ALL;
-- CASE T4-027 synthetic=false
SELECT i.warehouse_id,SUM(i.quantity) AS total_qty,ROUND(AVG(i.quantity),2) AS avg_qty FROM inventory i GROUP BY i.warehouse_id ORDER BY total_qty DESC NULLS LAST,i.warehouse_id ASC LIMIT ALL;
-- CASE T4-029 synthetic=false
SELECT c.customer_state,COUNT(*) AS cnt FROM support_tickets st LEFT JOIN orders o ON o.order_id=st.order_id LEFT JOIN customers c ON c.customer_id=o.customer_id GROUP BY c.customer_state ORDER BY cnt DESC,c.customer_state ASC NULLS LAST LIMIT ALL;
-- CASE T4-034 synthetic=false
SELECT ROUND(COUNT(*) FILTER(WHERE EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=o.order_id))::numeric/NULLIF(COUNT(*),0),4) AS refund_rate FROM orders o;
-- CASE T4-041 synthetic=false
WITH products_by_brand AS(SELECT b.brand_id,p.product_id FROM brands b LEFT JOIN product_brand_map bm ON bm.brand_id=b.brand_id AND bm.is_current IS TRUE LEFT JOIN products p ON p.product_id=bm.product_id) SELECT b.brand_id,COUNT(DISTINCT b.product_id) AS product_cnt,ROUND(SUM(oi.price),2) AS sales FROM products_by_brand b LEFT JOIN order_items oi ON oi.product_id=b.product_id GROUP BY b.brand_id ORDER BY sales DESC NULLS LAST,b.brand_id ASC LIMIT ALL;
-- CASE T4-043 synthetic=false
WITH per_shipment AS(SELECT s.shipment_id,s.status,COUNT(t.tracking_id) AS cnt FROM shipments s LEFT JOIN shipment_tracking t ON t.shipment_id=s.shipment_id GROUP BY s.shipment_id,s.status) SELECT status,ROUND(AVG(cnt),2) AS avg_events FROM per_shipment GROUP BY status ORDER BY status ASC NULLS LAST LIMIT ALL;
-- CASE T4-045 synthetic=false
SELECT product_category_name,ROUND(AVG(product_weight_g),2) AS avg_weight FROM products WHERE product_category_name IS NOT NULL GROUP BY product_category_name ORDER BY COUNT(*) DESC,product_category_name ASC LIMIT 10;
-- CASE T5-002 synthetic=false
SELECT m.level_id,ROUND(SUM(oi.price),2) AS sales FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN members m ON m.customer_unique_id=c.customer_unique_id JOIN order_items oi ON oi.order_id=o.order_id GROUP BY m.level_id ORDER BY sales DESC NULLS LAST,m.level_id ASC NULLS LAST LIMIT ALL;
-- CASE T5-004 synthetic=false
SELECT w.region_code,COUNT(*) AS shipments,COUNT(DISTINCT s.order_id) AS orders FROM shipments s LEFT JOIN warehouses w ON w.warehouse_id=s.warehouse_id GROUP BY w.region_code ORDER BY shipments DESC,w.region_code ASC NULLS LAST LIMIT ALL;
-- CASE T5-006 synthetic=false
SELECT c.customer_state,m.level_id,COUNT(*) AS tickets FROM support_tickets st JOIN ticket_categories tc ON tc.category_id=st.category_id LEFT JOIN orders o ON o.order_id=st.order_id LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN members m ON m.member_id=st.member_id WHERE tc.category_name='物流延迟' GROUP BY c.customer_state,m.level_id ORDER BY tickets DESC,c.customer_state ASC NULLS LAST,m.level_id ASC NULLS LAST LIMIT 20;
-- CASE T5-008 synthetic=false
SELECT mr.level_id AS referrer_level,mii.level_id AS invitee_level,COUNT(*) AS referrals,ROUND(SUM(rf.reward_amount),2) AS reward FROM referrals rf LEFT JOIN members mr ON mr.member_id=rf.referrer_member_id LEFT JOIN members mii ON mii.member_id=rf.invitee_member_id GROUP BY mr.level_id,mii.level_id ORDER BY referrals DESC,mr.level_id ASC NULLS LAST,mii.level_id ASC NULLS LAST LIMIT ALL;
-- CASE T5-011 synthetic=false
SELECT bm.brand_id,ROUND(SUM(oi.price),2) AS sales FROM order_items oi JOIN product_brand_map bm ON bm.product_id=oi.product_id AND bm.is_current IS TRUE GROUP BY bm.brand_id ORDER BY sales DESC NULLS LAST,bm.brand_id ASC LIMIT 10;
-- CASE T5-015 synthetic=false
SELECT st.event_code,c.customer_state,COUNT(DISTINCT s.shipment_id) AS shipments FROM shipment_tracking st JOIN shipments s ON s.shipment_id=st.shipment_id JOIN orders o ON o.order_id=s.order_id JOIN customers c ON c.customer_id=o.customer_id GROUP BY st.event_code,c.customer_state ORDER BY shipments DESC,st.event_code ASC NULLS LAST,c.customer_state ASC NULLS LAST LIMIT 20;
-- CASE T5-017 synthetic=false
SELECT st.category_id,st.priority,COUNT(DISTINCT st.ticket_id) AS tickets,COUNT(tm.message_id) AS messages FROM support_tickets st LEFT JOIN ticket_messages tm ON tm.ticket_id=st.ticket_id GROUP BY st.category_id,st.priority ORDER BY tickets DESC,st.category_id ASC NULLS LAST,st.priority ASC NULLS LAST LIMIT ALL;
-- CASE T5-020 synthetic=false
SELECT r.macro_region,im.movement_type,COUNT(*) AS movements,SUM(im.quantity) AS qty FROM inventory_movements im LEFT JOIN warehouses w ON w.warehouse_id=im.warehouse_id LEFT JOIN regions r ON r.region_code=w.region_code GROUP BY r.macro_region,im.movement_type ORDER BY movements DESC,r.macro_region ASC NULLS LAST,im.movement_type ASC NULLS LAST LIMIT ALL;
-- CASE T6-003 synthetic=false
WITH per_user AS(SELECT c.customer_unique_id,COUNT(DISTINCT o.order_id) AS order_cnt FROM orders o JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_unique_id) SELECT ROUND(100.0*COUNT(*) FILTER(WHERE order_cnt>=2)/NULLIF(COUNT(*),0),2) AS repeat_rate_pct FROM per_user;
-- CASE T6-005 synthetic=false
SELECT ROUND(100.0*COUNT(DISTINCT o.order_id) FILTER(WHERE m.member_id IS NOT NULL)/NULLIF(COUNT(DISTINCT o.order_id),0),2) AS penetration_pct FROM orders o LEFT JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN members m ON m.customer_unique_id=c.customer_unique_id WHERE o.order_status NOT IN('canceled','unavailable');
-- CASE T6-007 synthetic=false
SELECT ROUND(100.0*COUNT(DISTINCT o.order_id) FILTER(WHERE EXISTS(SELECT 1 FROM refunds r WHERE r.order_id=o.order_id))/NULLIF(COUNT(DISTINCT o.order_id),0),2) AS refund_rate_pct FROM orders o WHERE o.order_status NOT IN('canceled','unavailable');
-- CASE T6-010 synthetic=false
SELECT ROUND(100.0*COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL)/NULLIF(COUNT(*),0),2) AS ctr_pct FROM search_logs;
-- CASE T6-012 synthetic=false
WITH monthly AS(SELECT DATE_TRUNC('month',o.order_purchase_timestamp)::date AS month,COUNT(DISTINCT c.customer_unique_id) AS mau FROM orders o JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') AND o.order_purchase_timestamp>=DATE '2018-01-01' AND o.order_purchase_timestamp<DATE '2019-01-01' GROUP BY 1),calendar AS(SELECT generate_series(DATE '2018-01-01',DATE '2018-12-01',INTERVAL '1 month')::date AS month) SELECT c.month,m.mau FROM calendar c LEFT JOIN monthly m USING(month) ORDER BY c.month LIMIT ALL;
-- CASE T6-014 synthetic=false
WITH per_member AS(SELECT m.member_id,m.level_id,COUNT(DISTINCT o.order_id) AS order_cnt,SUM(oi.price+oi.freight_value) AS gmv FROM members m LEFT JOIN customers c ON c.customer_unique_id=m.customer_unique_id LEFT JOIN orders o ON o.customer_id=c.customer_id AND o.order_status NOT IN('canceled','unavailable') LEFT JOIN order_items oi ON oi.order_id=o.order_id GROUP BY m.member_id,m.level_id) SELECT level_id,COUNT(*) AS users,ROUND(AVG(order_cnt),2) AS orders_per_user,ROUND(AVG(gmv),2) AS gmv_per_user FROM per_member GROUP BY level_id ORDER BY gmv_per_user DESC NULLS LAST,level_id ASC NULLS LAST LIMIT ALL;
-- CASE T6-021 synthetic=false
WITH user_gmv AS(SELECT c.customer_unique_id,SUM(oi.price+oi.freight_value) AS gmv FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN order_items oi ON oi.order_id=o.order_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_unique_id),ranked AS(SELECT gmv,ROW_NUMBER() OVER(ORDER BY ROUND(gmv,2) DESC,customer_unique_id ASC) AS rn,COUNT(*) OVER() AS n FROM user_gmv) SELECT ROUND(SUM(gmv) FILTER(WHERE rn<=CEIL(n*0.2))/NULLIF(SUM(gmv),0),4) AS top20_gmv_ratio FROM ranked;
-- CASE T6-023 synthetic=false
WITH anchor AS(SELECT MAX(moved_at) AS mx FROM inventory_movements),outbound AS(SELECT im.warehouse_id,SUM(im.quantity) AS out_qty FROM inventory_movements im CROSS JOIN anchor a WHERE im.movement_type=2 AND im.moved_at>=a.mx::date-INTERVAL '89 days' AND im.moved_at<a.mx::date+INTERVAL '1 day' GROUP BY im.warehouse_id),stock AS(SELECT warehouse_id,SUM(quantity) AS stock_qty FROM inventory GROUP BY warehouse_id),combined AS(SELECT w.warehouse_id,s.stock_qty,CASE WHEN a.mx IS NULL THEN NULL ELSE COALESCE(o.out_qty,0) END AS out_qty FROM warehouses w CROSS JOIN anchor a LEFT JOIN stock s ON s.warehouse_id=w.warehouse_id LEFT JOIN outbound o ON o.warehouse_id=w.warehouse_id) SELECT warehouse_id,stock_qty,out_qty AS out_qty_90d,ROUND(90.0*stock_qty/NULLIF(out_qty,0),1) AS turnover_days FROM combined ORDER BY turnover_days DESC NULLS LAST,warehouse_id ASC LIMIT ALL;
-- CASE T6-025 synthetic=false
WITH first_order AS(SELECT c.customer_unique_id,DATE_TRUNC('month',MIN(o.order_purchase_timestamp))::date AS first_month FROM orders o JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_unique_id),monthly AS(SELECT DATE_TRUNC('month',o.order_purchase_timestamp)::date AS month,c.customer_unique_id,SUM(oi.price+oi.freight_value) AS gmv FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN order_items oi ON oi.order_id=o.order_id WHERE o.order_status NOT IN('canceled','unavailable') AND o.order_purchase_timestamp>=DATE '2018-01-01' AND o.order_purchase_timestamp<DATE '2019-01-01' GROUP BY 1,2),summed AS(SELECT m.month,ROUND(SUM(m.gmv) FILTER(WHERE f.first_month=m.month),2) AS new_customer_gmv,ROUND(SUM(m.gmv) FILTER(WHERE f.first_month<m.month),2) AS returning_gmv FROM monthly m JOIN first_order f ON f.customer_unique_id=m.customer_unique_id GROUP BY m.month),calendar AS(SELECT generate_series(DATE '2018-01-01',DATE '2018-12-01',INTERVAL '1 month')::date AS month) SELECT c.month,s.new_customer_gmv,s.returning_gmv FROM calendar c LEFT JOIN summed s USING(month) ORDER BY c.month LIMIT ALL;
