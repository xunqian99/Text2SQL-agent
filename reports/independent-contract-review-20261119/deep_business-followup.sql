BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';

WITH all_commission AS (SELECT st.seller_id,SUM(si.commission_amount) AS commission FROM settlements st JOIN settlement_items si USING(settlement_id) GROUP BY 1),
gold AS (SELECT st.seller_id,SUM(si.commission_amount) AS commission FROM settlements st JOIN settlement_items si USING(settlement_id) JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id JOIN products p USING(product_id) JOIN product_category_translation t USING(product_category_name) GROUP BY 1),
gold_top AS (SELECT * FROM gold ORDER BY commission DESC LIMIT 20),
all_top AS (SELECT * FROM all_commission ORDER BY commission DESC LIMIT 20)
SELECT jsonb_build_object('id','T5-010','check','commission error reaches selected Top20','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) FILTER(WHERE a.commission<>g.commission) AS changed_commissions_in_gold_top20,
 ROUND(SUM(a.commission-g.commission),2) AS omitted_commission_in_gold_top20,
 (SELECT COUNT(*) FROM all_top WHERE seller_id NOT IN(SELECT seller_id FROM gold_top)) AS corrected_new_top20_sellers
 FROM gold_top g JOIN all_commission a USING(seller_id)
) x;

WITH q AS (SELECT w.warehouse_id,r.region_code,pbm.brand_id,COUNT(*) AS n FROM inventory i JOIN warehouses w USING(warehouse_id) JOIN regions r USING(region_code) LEFT JOIN product_brand_map pbm ON pbm.product_id=i.product_id AND pbm.is_current WHERE i.quantity<i.safety_stock GROUP BY 1,2,3)
SELECT jsonb_build_object('id','T5-009','check','unknown brand population and Top20','result',jsonb_build_object(
 'unknown_brand_groups',(SELECT jsonb_agg(x) FROM (SELECT * FROM q WHERE brand_id IS NULL ORDER BY warehouse_id) x),
 'unknown_brand_groups_in_top20',(SELECT COUNT(*) FROM(SELECT * FROM q ORDER BY n DESC LIMIT 20) z WHERE brand_id IS NULL)));

WITH negatives AS (SELECT st.*,tc.sla_hours FROM support_tickets st JOIN ticket_categories tc USING(category_id) WHERE st.resolved_at<st.created_at)
SELECT jsonb_build_object('id','T6-015','check','negative handling times in current metric','result',to_jsonb(x)) FROM (
 SELECT COUNT(*) AS negative_duration_tickets,COUNT(*) FILTER(WHERE EXTRACT(EPOCH FROM(resolved_at-created_at))/3600.0<=sla_hours) AS counted_as_sla_success
 FROM negatives
) x;

SELECT jsonb_build_object('id','T6-017','check','reward numerator is not conversion-only','result',to_jsonb(x)) FROM (
 SELECT SUM(reward_amount) AS total_rewards,SUM(reward_amount) FILTER(WHERE converted_at IS NOT NULL) AS converted_rewards,
 COUNT(*) FILTER(WHERE converted_at IS NULL AND reward_amount>0) AS unconverted_rewarded_records,
 ROUND(SUM(reward_amount) FILTER(WHERE converted_at IS NOT NULL)/NULLIF(COUNT(*) FILTER(WHERE converted_at IS NOT NULL),0),2) AS conversion_only_reward_average
 FROM referrals
) x;

WITH m(member_id,level_id) AS (VALUES(1,3)), ms(member_id,snapshot_date,level_id) AS (VALUES (1,DATE '2020-01-01',2),(1,DATE '2020-02-01',3)), latest AS (SELECT DISTINCT ON(member_id) * FROM ms ORDER BY member_id,snapshot_date DESC)
SELECT jsonb_build_object('id','T5-014','check','synthetic read-only counterexample, not production records','result',jsonb_build_object(
 'gold_mismatched_members',(SELECT COUNT(DISTINCT member_id) FROM ms JOIN m USING(member_id) WHERE ms.level_id<>m.level_id),
 'latest_mismatched_members',(SELECT COUNT(DISTINCT member_id) FROM latest JOIN m USING(member_id) WHERE latest.level_id<>m.level_id)));

WITH r(order_id,reason_code) AS (VALUES(1,'QUAL'),(1,'QUAL'),(2,'QUAL')), v(review_id,order_id,review_score) AS (VALUES(1,1,1),(2,2,5))
SELECT jsonb_build_object('id','T6-024','check','synthetic read-only counterexample, not production records','result',jsonb_build_object(
 'gold_avg',(SELECT ROUND(AVG(review_score),2) FROM r JOIN v USING(order_id)),
 'review_grain_avg',(SELECT ROUND(AVG(review_score),2) FROM v WHERE EXISTS(SELECT 1 FROM r WHERE r.order_id=v.order_id)),
 'gold_low_pct',(SELECT ROUND(100.0*COUNT(*) FILTER(WHERE review_score<=2)/COUNT(*),2) FROM r JOIN v USING(order_id)),
 'review_grain_low_pct',(SELECT ROUND(100.0*COUNT(*) FILTER(WHERE review_score<=2)/COUNT(*),2) FROM v WHERE EXISTS(SELECT 1 FROM r WHERE r.order_id=v.order_id))));

WITH s(seller_id,gross,commission) AS (VALUES(1,0::numeric,0::numeric),(2,100::numeric,10::numeric))
SELECT jsonb_build_object('id','T6-020','check','synthetic zero denominator, not production records','result',jsonb_build_object(
 'gold_avg_rate',(SELECT ROUND(AVG(CASE WHEN gross>0 THEN commission/gross ELSE 0 END),4) FROM s),
 'contract_avg_rate',(SELECT ROUND(AVG(commission/NULLIF(gross,0)),4) FROM s)));

ROLLBACK;
