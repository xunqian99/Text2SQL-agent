BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';
SELECT jsonb_build_object('check','columns','rows',jsonb_agg(x)) FROM (
SELECT c.table_name,c.column_name,c.data_type,c.is_nullable,
 col_description(('public.'||c.table_name)::regclass,c.ordinal_position) AS comment
FROM information_schema.columns c
WHERE c.table_schema='public' AND c.table_name IN
('orders','customers','regions','members','member_levels','order_items','refunds','refund_items','products','product_category_translation','coupon_usages','coupons','campaigns','search_logs','inventory','warehouses','product_brand_map','brands','settlements','settlement_items','user_tag_map','user_tags','member_snapshot','shipping_addresses','support_tickets','ticket_categories','referrals','order_reviews')
ORDER BY c.table_name,c.ordinal_position) x;
SELECT jsonb_build_object('check','constraints','rows',jsonb_agg(x)) FROM (
SELECT con.conrelid::regclass::text AS table_name,con.conname,con.contype,pg_get_constraintdef(con.oid) AS definition
FROM pg_constraint con JOIN pg_namespace n ON n.oid=con.connamespace
WHERE n.nspname='public' AND con.conrelid::regclass::text IN
('orders','customers','regions','members','member_levels','order_items','refunds','refund_items','products','product_category_translation','coupon_usages','coupons','campaigns','search_logs','inventory','warehouses','product_brand_map','brands','settlements','settlement_items','user_tag_map','user_tags','member_snapshot','shipping_addresses','support_tickets','ticket_categories','referrals','order_reviews')
ORDER BY 1,2) x;
SELECT jsonb_build_object('check','indexes','rows',jsonb_agg(x)) FROM (
SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public'
AND tablename IN ('members','member_levels','products','product_category_translation','coupons','campaigns','inventory','warehouses','product_brand_map','brands','user_tag_map','user_tags','member_snapshot','shipping_addresses','order_reviews') ORDER BY 1,2) x;
ROLLBACK;
