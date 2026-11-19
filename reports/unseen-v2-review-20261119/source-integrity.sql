BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('probe','execution_identity','user',current_user,'transactionReadOnly',current_setting('transaction_read_only'),'statementTimeout',current_setting('statement_timeout'));
SELECT json_build_object('probe','business_keys','rows',json_agg(q)) FROM (
 SELECT c.relname AS table_name,con.conname,con.contype,pg_get_constraintdef(con.oid) AS definition
 FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname IN ('brands','campaign_products','campaigns','coupon_usages','coupons','customers','geolocation','inventory','inventory_movements','member_levels','member_snapshot','members','order_items','order_payments','order_reviews','orders','product_brand_map','product_category_translation','product_price_history','products','referrals','refund_items','refunds','regions','search_logs','sellers','settlement_items','settlements','shipment_tracking','shipments','shipping_addresses','support_tickets','ticket_categories','ticket_messages','user_tag_map','user_tags','warehouses')
 ORDER BY c.relname,con.conname
) q;
SELECT json_build_object('probe','reversed_activity_interval','count',COUNT(*)) FROM campaigns WHERE start_date>end_date;
SELECT json_build_object('probe','refund_header_line_order_mismatch','count',COUNT(*)) FROM refund_items ri JOIN refunds r ON r.refund_id=ri.refund_id WHERE ri.order_id<>r.order_id;
SELECT json_build_object('probe','customer_person_multiple_profiles','count',COUNT(*)) FROM (SELECT customer_unique_id FROM customers GROUP BY customer_unique_id HAVING COUNT(*)>1) q;
SELECT json_build_object('probe','customer_person_multiple_states','count',COUNT(*)) FROM (SELECT customer_unique_id FROM customers GROUP BY customer_unique_id HAVING COUNT(DISTINCT customer_state)>1) q;
SELECT json_build_object('probe','review_id_multiple_order_id','count',COUNT(*)) FROM (SELECT review_id FROM order_reviews GROUP BY review_id HAVING COUNT(DISTINCT order_id)>1) q;
SELECT json_build_object('probe','multiple_payments_per_order','count',COUNT(*)) FROM (SELECT order_id FROM order_payments GROUP BY order_id HAVING COUNT(*)>1) q;
SELECT json_build_object('probe','multiple_refunds_per_order','count',COUNT(*)) FROM (SELECT order_id FROM refunds GROUP BY order_id HAVING COUNT(*)>1) q;
SELECT json_build_object('probe','multiple_items_per_order','count',COUNT(*)) FROM (SELECT order_id FROM order_items GROUP BY order_id HAVING COUNT(*)>1) q;
SELECT json_build_object('probe','multiple_current_brands','count',COUNT(*)) FROM (SELECT product_id FROM product_brand_map WHERE is_current IS TRUE GROUP BY product_id HAVING COUNT(*)>1) q;
SELECT json_build_object('probe','tracking_fanout','count',COUNT(*)) FROM (SELECT shipment_id FROM shipment_tracking GROUP BY shipment_id HAVING COUNT(*)>1) q;
SELECT json_build_object('probe','missing_translation_categories','count',COUNT(*)) FROM products p LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name WHERE p.product_category_name IS NOT NULL AND t.product_category_name IS NULL;
SELECT json_build_object('probe','negative_ticket_durations','count',COUNT(*)) FROM support_tickets WHERE resolved_at<created_at;
SELECT json_build_object('probe','maximum_dimensions','campaigns',(SELECT COUNT(*) FROM campaigns),'brands',(SELECT COUNT(*) FROM brands),'warehouses',(SELECT COUNT(*) FROM warehouses),'memberLevels',(SELECT COUNT(*) FROM member_levels),'tags',(SELECT COUNT(*) FROM user_tags));
ROLLBACK;
