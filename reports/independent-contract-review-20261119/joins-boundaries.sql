BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';
SELECT jsonb_build_object('check','execution-context','result',jsonb_build_object('user',current_user,'read_only',current_setting('transaction_read_only'),'statement_timeout',current_setting('statement_timeout'),'server_version',current_setting('server_version')));

WITH totals AS (SELECT order_id,SUM(price+freight_value) AS amount FROM order_items GROUP BY 1),
per_person AS (
 SELECT c.customer_unique_id,COUNT(*) AS all_valid_orders,COUNT(t.order_id) AS orders_with_items,SUM(t.amount) AS amount
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id LEFT JOIN totals t ON t.order_id=o.order_id
 WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY 1
)
SELECT jsonb_build_object('check','T4-035-changed-person','result',jsonb_agg(x)) FROM (
 SELECT customer_unique_id,all_valid_orders,orders_with_items,amount,
 ROUND(amount/orders_with_items,2) AS gold_aov,ROUND(amount/all_valid_orders,2) AS all_valid_aov
 FROM per_person WHERE amount IS NOT NULL AND all_valid_orders<>orders_with_items
) x;

-- Constructed read-only counterexample, not a measurement of the real dataset.
WITH w(warehouse_id,warehouse_name) AS (VALUES (1,'has shipment'),(2,'no shipment')),
s(shipment_id,warehouse_id) AS (VALUES (101,1)),
g AS (SELECT w.warehouse_name,COUNT(*) AS shipments FROM s JOIN w USING(warehouse_id) GROUP BY 1),
a AS (SELECT w.warehouse_name,COUNT(s.shipment_id) AS shipments FROM w LEFT JOIN s USING(warehouse_id) GROUP BY 1)
SELECT jsonb_build_object('check','T4-024-zero-warehouse-counterexample','result',jsonb_build_object('constructed',true,'gold',(SELECT jsonb_agg(g) FROM g),'all_warehouses',(SELECT jsonb_agg(a) FROM a)));

-- Constructed history with an upward and a later downward adjustment.
WITH h(product_id,old_price,new_price,changed_at) AS (
 VALUES ('example',100::numeric,120::numeric,TIMESTAMP '2020-01-01'),('example',120::numeric,90::numeric,TIMESTAMP '2020-02-01')
), latest AS (SELECT * FROM h ORDER BY changed_at DESC LIMIT 1)
SELECT jsonb_build_object('check','T4-042-history-counterexample','result',jsonb_build_object('constructed',true,'ever_up',(SELECT COUNT(DISTINCT product_id) FROM h WHERE new_price>old_price),'latest_up',(SELECT COUNT(*) FROM latest WHERE new_price>old_price)));

-- Empty-search denominator and the contract's NULL result, without an intentional error.
SELECT jsonb_build_object('check','T4-039-empty-population','result',jsonb_build_object('constructed_empty_filter',true,'numerator',COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL),'denominator',COUNT(*),'protected_result',ROUND(COUNT(*) FILTER(WHERE clicked_product_id IS NOT NULL)::numeric/NULLIF(COUNT(*),0),4))) FROM search_logs WHERE FALSE;
COMMIT;
