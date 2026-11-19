BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';
SELECT ROUND(COUNT(*) FILTER (WHERE clicked_product_id IS NOT NULL)::NUMERIC / COUNT(*),4) AS ctr FROM search_logs WHERE FALSE;
COMMIT;
