-- AC01-T5-016-actual; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT json_build_object('id','T5-016','rows',coalesce(json_agg(row_to_json(g)),'[]'::json)) FROM (SELECT t.product_category_name_english,r.reason_code,
 COUNT(DISTINCT r.refund_id) AS refunds,ROUND(SUM(ri.refund_value),2) AS amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english,r.reason_code HAVING SUM(ri.refund_value) IS NOT NULL
ORDER BY amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST,r.reason_code ASC NULLS LAST LIMIT 20)g;
WITH old_result AS (SELECT t.product_category_name_english,r.reason_code,
 COUNT(DISTINCT r.refund_id) AS refunds,ROUND(SUM(ri.refund_value),2) AS amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english,r.reason_code
ORDER BY amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST,r.reason_code ASC NULLS LAST LIMIT 20), new_result AS (SELECT t.product_category_name_english,r.reason_code,
 COUNT(DISTINCT r.refund_id) AS refunds,ROUND(SUM(ri.refund_value),2) AS amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english,r.reason_code HAVING SUM(ri.refund_value) IS NOT NULL
ORDER BY amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST,r.reason_code ASC NULLS LAST LIMIT 20) SELECT json_build_object('oldMinusNew',(SELECT count(*) FROM(SELECT * FROM old_result EXCEPT ALL SELECT * FROM new_result)d),'newMinusOld',(SELECT count(*) FROM(SELECT * FROM new_result EXCEPT ALL SELECT * FROM old_result)d));
ROLLBACK;

-- AC02-T5-016-unknown-amount; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH refunds(refund_id,reason_code) AS(VALUES(1,'QUAL')),refund_items(refund_id,order_id,order_item_id,refund_value) AS(VALUES(1,'o',1,NULL::numeric)),order_items(order_id,order_item_id,product_id) AS(VALUES('o',1,'p')),products(product_id,product_category_name) AS(VALUES('p','cat')),product_category_translation(product_category_name,product_category_name_english) AS(VALUES('cat','category')) SELECT t.product_category_name_english,r.reason_code,
 COUNT(DISTINCT r.refund_id) AS refunds,ROUND(SUM(ri.refund_value),2) AS amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english,r.reason_code HAVING SUM(ri.refund_value) IS NOT NULL
ORDER BY amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST,r.reason_code ASC NULLS LAST LIMIT 20)g;
ROLLBACK;

-- AC03-T5-016-known-missing-name; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT COALESCE(json_agg(row_to_json(g)),'[]'::json) FROM (WITH refunds(refund_id,reason_code) AS(VALUES(1,'QUAL'),(2,'QUAL'),(3,'QUAL')),refund_items(refund_id,order_id,order_item_id,refund_value) AS(VALUES(1,'o1',1,NULL::numeric),(2,'o2',1,100::numeric),(3,'o3',1,50::numeric),(3,'o3',2,25::numeric)),order_items(order_id,order_item_id,product_id) AS(VALUES('o1',1,'p1'),('o2',1,'p2'),('o3',1,'p3'),('o3',2,'p3')),products(product_id,product_category_name) AS(VALUES('p1','unknown-amount'),('p2','missing-translation'),('p3','known-cat')),product_category_translation(product_category_name,product_category_name_english) AS(VALUES('unknown-amount','unknown-amount'),('known-cat','known-category')) SELECT t.product_category_name_english,r.reason_code,
 COUNT(DISTINCT r.refund_id) AS refunds,ROUND(SUM(ri.refund_value),2) AS amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english,r.reason_code HAVING SUM(ri.refund_value) IS NOT NULL
ORDER BY amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST,r.reason_code ASC NULLS LAST LIMIT 20)g;
ROLLBACK;
