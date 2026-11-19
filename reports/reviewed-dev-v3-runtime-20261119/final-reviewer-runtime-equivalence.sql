BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('kind','environment','readOnly',current_setting('transaction_read_only'),'role',current_user,'timeout',current_setting('statement_timeout'),'postgresVersion',version());
SELECT json_build_object('kind','original','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH changed AS(
 SELECT p.product_category_name,pph.product_id,pph.new_price-pph.old_price AS delta
 FROM product_price_history pph JOIN products p ON p.product_id=pph.product_id
 WHERE p.product_category_name IS NOT NULL),
means AS(SELECT product_category_name,ROUND(AVG(delta),2) AS avg_price_change FROM changed GROUP BY 1),
orders AS(SELECT x.product_category_name,COUNT(DISTINCT oi.order_id) AS orders
 FROM(SELECT DISTINCT product_category_name,product_id FROM changed)x
 LEFT JOIN order_items oi ON oi.product_id=x.product_id GROUP BY 1)
SELECT m.product_category_name,m.avg_price_change,o.orders FROM means m JOIN orders o USING(product_category_name)
ORDER BY o.orders DESC,m.product_category_name ASC LIMIT 20)q));
SELECT json_build_object('kind','runtime','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (WITH changed AS(
 SELECT p.product_category_name,pph.product_id,pph.new_price-pph.old_price AS delta
 FROM product_price_history pph JOIN products p ON p.product_id=pph.product_id
 WHERE p.product_category_name IS NOT NULL),
means AS(SELECT product_category_name,ROUND(AVG(delta),2) AS avg_price_change FROM changed GROUP BY 1),
order_counts AS(SELECT x.product_category_name,COUNT(DISTINCT oi.order_id) AS orders
 FROM(SELECT DISTINCT product_category_name,product_id FROM changed)x
 LEFT JOIN order_items oi ON oi.product_id=x.product_id GROUP BY 1)
SELECT m.product_category_name,m.avg_price_change,o.orders FROM means m JOIN order_counts o USING(product_category_name)
ORDER BY o.orders DESC,m.product_category_name ASC LIMIT 20)q));
WITH frozen_result AS MATERIALIZED (WITH changed AS(
 SELECT p.product_category_name,pph.product_id,pph.new_price-pph.old_price AS delta
 FROM product_price_history pph JOIN products p ON p.product_id=pph.product_id
 WHERE p.product_category_name IS NOT NULL),
means AS(SELECT product_category_name,ROUND(AVG(delta),2) AS avg_price_change FROM changed GROUP BY 1),
orders AS(SELECT x.product_category_name,COUNT(DISTINCT oi.order_id) AS orders
 FROM(SELECT DISTINCT product_category_name,product_id FROM changed)x
 LEFT JOIN order_items oi ON oi.product_id=x.product_id GROUP BY 1)
SELECT m.product_category_name,m.avg_price_change,o.orders FROM means m JOIN orders o USING(product_category_name)
ORDER BY o.orders DESC,m.product_category_name ASC LIMIT 20), runtime_result AS MATERIALIZED (WITH changed AS(
 SELECT p.product_category_name,pph.product_id,pph.new_price-pph.old_price AS delta
 FROM product_price_history pph JOIN products p ON p.product_id=pph.product_id
 WHERE p.product_category_name IS NOT NULL),
means AS(SELECT product_category_name,ROUND(AVG(delta),2) AS avg_price_change FROM changed GROUP BY 1),
order_counts AS(SELECT x.product_category_name,COUNT(DISTINCT oi.order_id) AS orders
 FROM(SELECT DISTINCT product_category_name,product_id FROM changed)x
 LEFT JOIN order_items oi ON oi.product_id=x.product_id GROUP BY 1)
SELECT m.product_category_name,m.avg_price_change,o.orders FROM means m JOIN order_counts o USING(product_category_name)
ORDER BY o.orders DESC,m.product_category_name ASC LIMIT 20) SELECT json_build_object('kind','equivalence','frozenMinusRuntime',(SELECT COUNT(*) FROM(SELECT * FROM frozen_result EXCEPT ALL SELECT * FROM runtime_result)d),'runtimeMinusFrozen',(SELECT COUNT(*) FROM(SELECT * FROM runtime_result EXCEPT ALL SELECT * FROM frozen_result)d));
ROLLBACK;
