BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';
SELECT json_build_object('check','schema_columns','rows',json_agg(x)) FROM (
 SELECT c.table_name,c.column_name,c.data_type,c.is_nullable,c.column_default,
        col_description(pc.oid,c.ordinal_position) AS comment
 FROM information_schema.columns c
 JOIN pg_class pc ON pc.relname=c.table_name
 JOIN pg_namespace pn ON pn.oid=pc.relnamespace AND pn.nspname=c.table_schema
 WHERE c.table_schema='public' AND c.table_name IN ('orders','order_reviews','customers','order_items','products','sellers','product_category_translation','members','member_levels','user_tag_map','user_tags','regions','coupon_usages','shipments','warehouses','support_tickets','ticket_categories','settlements','refunds','search_logs','inventory','product_price_history')
 ORDER BY c.table_name,c.ordinal_position
) x;
SELECT json_build_object('check','schema_constraints','rows',json_agg(x)) FROM (
 SELECT rel.relname AS table_name,con.conname,con.contype,pg_get_constraintdef(con.oid) AS definition
 FROM pg_constraint con JOIN pg_class rel ON rel.oid=con.conrelid
 JOIN pg_namespace n ON n.oid=rel.relnamespace
 WHERE n.nspname='public' AND rel.relname IN ('orders','order_reviews','customers','order_items','products','sellers','product_category_translation','members','member_levels','user_tag_map','user_tags','regions','coupon_usages','shipments','warehouses','support_tickets','ticket_categories','settlements','refunds','search_logs','inventory','product_price_history')
 ORDER BY rel.relname,con.contype,con.conname
) x;
SELECT jsonb_build_object('check','schema_unique_indexes','rows',COALESCE(jsonb_agg(x),'[]'::jsonb)) FROM (
 SELECT rel.relname AS table_name,idx.relname AS index_name,i.indisprimary,i.indisvalid,pg_get_indexdef(i.indexrelid) AS definition
 FROM pg_index i JOIN pg_class rel ON rel.oid=i.indrelid JOIN pg_class idx ON idx.oid=i.indexrelid JOIN pg_namespace n ON n.oid=rel.relnamespace
 WHERE n.nspname='public' AND i.indisunique AND rel.relname IN ('orders','order_reviews','customers','order_items','products','sellers','product_category_translation','members','member_levels','user_tag_map','user_tags','regions','coupon_usages','shipments','warehouses','support_tickets','ticket_categories','settlements','refunds','search_logs','inventory','product_price_history')
 ORDER BY rel.relname,idx.relname
) x;
COMMIT;
