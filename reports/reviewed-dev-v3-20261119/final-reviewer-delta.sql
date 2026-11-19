BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('kind','changed-gold','id','T1-013','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT order_id, order_item_id, freight_value
FROM order_items ORDER BY freight_value DESC,order_id ASC,order_item_id ASC LIMIT 5)q));
SELECT json_build_object('kind','changed-gold','id','T2-009','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT product_category_name, COUNT(*) AS cnt
FROM products
WHERE product_category_name IS NOT NULL
GROUP BY product_category_name ORDER BY cnt DESC,product_category_name ASC LIMIT 10)q));
SELECT json_build_object('kind','changed-gold','id','T2-012','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT seller_id, ROUND(SUM(freight_value), 2) AS total
FROM order_items GROUP BY seller_id ORDER BY total DESC,seller_id ASC LIMIT 10)q));
SELECT json_build_object('kind','changed-gold','id','T3-015','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT EXTRACT(HOUR FROM order_purchase_timestamp)::INT AS hr, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY cnt DESC,hr ASC NULLS LAST LIMIT 1)q));
SELECT json_build_object('kind','changed-gold','id','T4-005','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT oi.seller_id, COUNT(DISTINCT oi.order_id) AS order_cnt
FROM order_items oi GROUP BY 1 ORDER BY order_cnt DESC,oi.seller_id ASC LIMIT 10)q));
SELECT json_build_object('kind','changed-gold','id','T4-025','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT r.macro_region,SUM(w.capacity) AS total_capacity
FROM warehouses w LEFT JOIN regions r ON r.region_code=w.region_code
GROUP BY r.macro_region ORDER BY total_capacity DESC NULLS LAST,r.macro_region ASC NULLS LAST)q));
SELECT json_build_object('kind','changed-gold','id','T4-030','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT seller_id, ROUND(SUM(total_amount), 2) AS total
FROM settlements GROUP BY 1 HAVING SUM(total_amount) IS NOT NULL ORDER BY total DESC,seller_id ASC LIMIT 10)q));
SELECT json_build_object('kind','changed-gold','id','T5-015','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT st.event_code,c.customer_state,COUNT(DISTINCT st.shipment_id) AS shipments
FROM shipment_tracking st LEFT JOIN shipments s ON s.shipment_id=st.shipment_id
LEFT JOIN orders o ON o.order_id=s.order_id LEFT JOIN customers c ON c.customer_id=o.customer_id
GROUP BY st.event_code,c.customer_state
ORDER BY shipments DESC,st.event_code ASC NULLS LAST,c.customer_state ASC NULLS LAST LIMIT 20)q));
SELECT json_build_object('kind','changed-gold','id','T6-009','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (SELECT c.customer_state,
       ROUND(100.0 * COUNT(*) FILTER (WHERE r.review_score <= 2) / COUNT(*), 2) AS low_pct
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY low_pct DESC,c.customer_state ASC NULLS LAST LIMIT 10)q));
SELECT json_build_object('kind','changed-gold','id','T6-023','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH anchor AS(SELECT MAX(moved_at) AS mx FROM inventory_movements),outbound AS(SELECT im.warehouse_id,SUM(im.quantity) AS out_qty,COUNT(*) AS records FROM inventory_movements im CROSS JOIN anchor a WHERE im.movement_type=2 AND im.moved_at>=a.mx::date-INTERVAL '89 days' AND im.moved_at<a.mx::date+INTERVAL '1 day' GROUP BY im.warehouse_id),stock AS(SELECT warehouse_id,SUM(quantity) AS stock_qty FROM inventory GROUP BY warehouse_id),combined AS(SELECT w.warehouse_id,s.stock_qty,CASE WHEN a.mx IS NULL THEN NULL WHEN o.records IS NULL THEN 0 ELSE o.out_qty END AS out_qty FROM warehouses w CROSS JOIN anchor a LEFT JOIN stock s ON s.warehouse_id=w.warehouse_id LEFT JOIN outbound o ON o.warehouse_id=w.warehouse_id) SELECT warehouse_id,stock_qty,out_qty AS out_qty_90d,ROUND(90.0*stock_qty/NULLIF(out_qty,0),1) AS turnover_days FROM combined ORDER BY turnover_days DESC NULLS LAST,warehouse_id ASC)q));
SELECT json_build_object('kind','fixture','id','F01-complete-item-identity','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH order_items(order_id,order_item_id,freight_value) AS(VALUES('b',1,10.00::numeric),('a',2,10.00),('a',1,10.00),('a',3,10.00),('a',4,10.00),('a',5,10.00)) SELECT order_id, order_item_id, freight_value
FROM order_items ORDER BY freight_value DESC,order_id ASC,order_item_id ASC LIMIT 5)q));
SELECT json_build_object('kind','fixture','id','F02-unknown-warehouse-region','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH warehouses(warehouse_id,region_code,capacity) AS(VALUES(1,NULL::text,100)),regions(region_code,macro_region) AS(VALUES('SP','Southeast')) SELECT r.macro_region,SUM(w.capacity) AS total_capacity
FROM warehouses w LEFT JOIN regions r ON r.region_code=w.region_code
GROUP BY r.macro_region ORDER BY total_capacity DESC NULLS LAST,r.macro_region ASC NULLS LAST)q));
SELECT json_build_object('kind','fixture','id','F03-missing-shipment-order','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH shipment_tracking(tracking_id,shipment_id,event_code) AS(VALUES(1,1,'DELIVERED'),(2,2,'DELIVERED'),(3,1,'DELIVERED')),shipments(shipment_id,order_id) AS(VALUES(1,'missing')),orders(order_id,customer_id) AS(SELECT NULL::text,NULL::text WHERE FALSE),customers(customer_id,customer_state) AS(SELECT NULL::text,NULL::text WHERE FALSE) SELECT st.event_code,c.customer_state,COUNT(DISTINCT st.shipment_id) AS shipments
FROM shipment_tracking st LEFT JOIN shipments s ON s.shipment_id=st.shipment_id
LEFT JOIN orders o ON o.order_id=s.order_id LEFT JOIN customers c ON c.customer_id=o.customer_id
GROUP BY st.event_code,c.customer_state
ORDER BY shipments DESC,st.event_code ASC NULLS LAST,c.customer_state ASC NULLS LAST LIMIT 20)q));
SELECT json_build_object('kind','fixture','id','F04-no-outbound-vs-unknown','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH warehouses(warehouse_id) AS(VALUES(1),(2),(3)),inventory(warehouse_id,quantity) AS(VALUES(1,100),(2,50),(3,20)),inventory_movements(warehouse_id,quantity,movement_type,moved_at) AS(VALUES(1,NULL::int,2,TIMESTAMP '2018-05-01 12:00:00'),(2,5,1,TIMESTAMP '2018-05-01 12:00:00'),(3,10,2,TIMESTAMP '2018-05-01 12:00:00')),anchor AS(SELECT MAX(moved_at) AS mx FROM inventory_movements),outbound AS(SELECT im.warehouse_id,SUM(im.quantity) AS out_qty,COUNT(*) AS records FROM inventory_movements im CROSS JOIN anchor a WHERE im.movement_type=2 AND im.moved_at>=a.mx::date-INTERVAL '89 days' AND im.moved_at<a.mx::date+INTERVAL '1 day' GROUP BY im.warehouse_id),stock AS(SELECT warehouse_id,SUM(quantity) AS stock_qty FROM inventory GROUP BY warehouse_id),combined AS(SELECT w.warehouse_id,s.stock_qty,CASE WHEN a.mx IS NULL THEN NULL WHEN o.records IS NULL THEN 0 ELSE o.out_qty END AS out_qty FROM warehouses w CROSS JOIN anchor a LEFT JOIN stock s ON s.warehouse_id=w.warehouse_id LEFT JOIN outbound o ON o.warehouse_id=w.warehouse_id) SELECT warehouse_id,stock_qty,out_qty AS out_qty_90d,ROUND(90.0*stock_qty/NULLIF(out_qty,0),1) AS turnover_days FROM combined ORDER BY turnover_days DESC NULLS LAST,warehouse_id ASC)q));
SELECT json_build_object('kind','fixture','id','F05-missing-reference-date','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH warehouses(warehouse_id) AS(VALUES(1),(2)),inventory(warehouse_id,quantity) AS(VALUES(1,100),(2,50)),inventory_movements(warehouse_id,quantity,movement_type,moved_at) AS(VALUES(1,5,2,NULL::timestamp)),anchor AS(SELECT MAX(moved_at) AS mx FROM inventory_movements),outbound AS(SELECT im.warehouse_id,SUM(im.quantity) AS out_qty,COUNT(*) AS records FROM inventory_movements im CROSS JOIN anchor a WHERE im.movement_type=2 AND im.moved_at>=a.mx::date-INTERVAL '89 days' AND im.moved_at<a.mx::date+INTERVAL '1 day' GROUP BY im.warehouse_id),stock AS(SELECT warehouse_id,SUM(quantity) AS stock_qty FROM inventory GROUP BY warehouse_id),combined AS(SELECT w.warehouse_id,s.stock_qty,CASE WHEN a.mx IS NULL THEN NULL WHEN o.records IS NULL THEN 0 ELSE o.out_qty END AS out_qty FROM warehouses w CROSS JOIN anchor a LEFT JOIN stock s ON s.warehouse_id=w.warehouse_id LEFT JOIN outbound o ON o.warehouse_id=w.warehouse_id) SELECT warehouse_id,stock_qty,out_qty AS out_qty_90d,ROUND(90.0*stock_qty/NULLIF(out_qty,0),1) AS turnover_days FROM combined ORDER BY turnover_days DESC NULLS LAST,warehouse_id ASC)q));
SELECT json_build_object('kind','fixture','id','F06-unknown-settlement-candidate','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH settlements(seller_id,total_amount) AS(VALUES('unknown',NULL::numeric),('known',100.00::numeric)) SELECT seller_id, ROUND(SUM(total_amount), 2) AS total
FROM settlements GROUP BY 1 HAVING SUM(total_amount) IS NOT NULL ORDER BY total DESC,seller_id ASC LIMIT 10)q));
SELECT json_build_object('kind','fixture','id','F07-rounded-score-identity','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH customers(customer_id,customer_state) AS(VALUES('a','A'),('b','B')),orders(order_id,customer_id,order_status) AS(VALUES('a','a','delivered'),('b','b','delivered')),order_reviews(order_id,review_score) AS(SELECT 'a',CASE WHEN n<=5000 THEN 1 ELSE 5 END FROM generate_series(1,50000)n UNION ALL SELECT 'b',CASE WHEN n<=5001 THEN 1 ELSE 5 END FROM generate_series(1,50000)n) SELECT c.customer_state,
       ROUND(100.0 * COUNT(*) FILTER (WHERE r.review_score <= 2) / COUNT(*), 2) AS low_pct
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY low_pct DESC,c.customer_state ASC NULLS LAST LIMIT 10)q));
ROLLBACK;
