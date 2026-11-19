-- S14_T5-003_unknown_refund_amount_top15; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH refunds(refund_id) AS(VALUES(1)),refund_items(refund_id,order_id,order_item_id,refund_value) AS(VALUES(1,'o',1,NULL::numeric)),order_items(order_id,order_item_id,product_id) AS(VALUES('o',1,'p')),products(product_id,product_category_name) AS(VALUES('p','cat')),product_category_translation(product_category_name,product_category_name_english) AS(VALUES('cat','category')) SELECT t.product_category_name_english,ROUND(SUM(ri.refund_value),2) AS refund_amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english
ORDER BY refund_amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 15;
ROLLBACK;

-- S15_T5-010_unknown_commission_top20; synthetic=true
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH settlements(settlement_id,seller_id) AS(VALUES(1,'s')),settlement_items(settlement_id,order_id,order_item_id,commission_amount) AS(VALUES(1,'o',1,NULL::numeric)),order_items(order_id,order_item_id,product_id) AS(VALUES('o',1,'p')),products(product_id,product_category_name) AS(VALUES('p','cat')),product_category_translation(product_category_name,product_category_name_english) AS(VALUES('cat','category')) SELECT st.seller_id,COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
 ROUND(SUM(si.commission_amount),2) AS commission
FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
LEFT JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
GROUP BY st.seller_id ORDER BY commission DESC NULLS LAST,st.seller_id LIMIT 20;
ROLLBACK;

-- A13_nullable_amount_facts; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
SELECT 'refund_value_NULL' AS issue,count(*) AS n FROM refund_items WHERE refund_value IS NULL UNION ALL SELECT 'commission_amount_NULL',count(*) FROM settlement_items WHERE commission_amount IS NULL UNION ALL SELECT 'refund_value_nullable',count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='refund_items' AND column_name='refund_value' AND is_nullable='YES' UNION ALL SELECT 'commission_amount_nullable',count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='settlement_items' AND column_name='commission_amount' AND is_nullable='YES';
ROLLBACK;

-- A14_T5-016_top20_ranking_ambiguity; synthetic=false
BEGIN READ ONLY;
SET LOCAL statement_timeout='8s';
WITH g AS(SELECT t.product_category_name_english AS cat,r.reason_code,count(DISTINCT r.refund_id) AS refunds,round(sum(ri.refund_value),2) AS amt FROM refunds r JOIN refund_items ri USING(refund_id) JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id JOIN products p USING(product_id) LEFT JOIN product_category_translation t USING(product_category_name) WHERE p.product_category_name IS NOT NULL GROUP BY t.product_category_name_english,r.reason_code),ranked AS(SELECT *,row_number() OVER(ORDER BY refunds DESC,cat ASC NULLS LAST,reason_code ASC NULLS LAST) AS count_rank,row_number() OVER(ORDER BY amt DESC NULLS LAST,cat ASC NULLS LAST,reason_code ASC NULLS LAST) AS amount_rank FROM g) SELECT jsonb_build_object('groups',count(*),'only_in_count_top20',count(*) FILTER(WHERE count_rank<=20 AND amount_rank>20),'only_in_amount_top20',count(*) FILTER(WHERE amount_rank<=20 AND count_rank>20),'top_by_count',jsonb_agg(jsonb_build_object('cat',cat,'reason',reason_code,'refunds',refunds,'amt',amt) ORDER BY count_rank) FILTER(WHERE count_rank<=20),'top_by_amount',jsonb_agg(jsonb_build_object('cat',cat,'reason',reason_code,'refunds',refunds,'amt',amt) ORDER BY amount_rank) FILTER(WHERE amount_rank<=20)) FROM ranked;
ROLLBACK;
