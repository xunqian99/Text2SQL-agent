BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';
SELECT jsonb_build_object('id','T5-005','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM (WITH campaign_orders AS (
 SELECT DISTINCT cp.campaign_id, cu.order_id
 FROM coupon_usages cu JOIN coupons cp ON cp.coupon_id=cu.coupon_id
 JOIN orders o ON o.order_id=cu.order_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
), item_totals AS (
 SELECT order_id,SUM(price) AS sales FROM order_items GROUP BY order_id
)
SELECT ca.campaign_id,COUNT(co.order_id) AS used_orders,ROUND(SUM(it.sales),2) AS gmv
FROM campaigns ca LEFT JOIN campaign_orders co ON co.campaign_id=ca.campaign_id
LEFT JOIN item_totals it ON it.order_id=co.order_id
GROUP BY ca.campaign_id ORDER BY gmv DESC NULLS LAST LIMIT 10) q;

SELECT jsonb_build_object('id','T5-007','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM (SELECT sl.keyword,t.product_category_name_english,COUNT(*) AS clicks
FROM search_logs sl JOIN products p ON p.product_id=sl.clicked_product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE sl.clicked_product_id IS NOT NULL AND p.product_category_name IS NOT NULL
GROUP BY sl.keyword,t.product_category_name_english ORDER BY clicks DESC LIMIT 20) q;

SELECT jsonb_build_object('id','T5-010','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM (SELECT st.seller_id,COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
 ROUND(SUM(si.commission_amount),2) AS commission
FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id
LEFT JOIN order_items oi ON oi.order_id=si.order_id AND oi.order_item_id=si.order_item_id
LEFT JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
GROUP BY st.seller_id ORDER BY commission DESC LIMIT 20) q;

SELECT jsonb_build_object('id','T5-012','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM (SELECT ut.tag_id,COUNT(DISTINCT m.member_id) AS members,ROUND(AVG(oi.price),2) AS avg_item_price
FROM user_tags ut LEFT JOIN user_tag_map utm ON utm.tag_id=ut.tag_id
LEFT JOIN members m ON m.member_id=utm.member_id
LEFT JOIN customers c ON c.customer_unique_id=m.customer_unique_id
LEFT JOIN orders o ON o.customer_id=c.customer_id
LEFT JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY ut.tag_id ORDER BY members DESC) q;

SELECT jsonb_build_object('id','T6-002','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM (WITH valid_orders AS (
 SELECT o.order_id,c.customer_unique_id FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
), item_totals AS (
 SELECT order_id,SUM(price+freight_value) AS gmv FROM order_items GROUP BY order_id
)
SELECT COUNT(*) AS valid_orders,COUNT(DISTINCT vo.customer_unique_id) AS buyers,
 ROUND(SUM(it.gmv)/NULLIF(COUNT(*),0),2) AS aov
FROM valid_orders vo LEFT JOIN item_totals it ON it.order_id=vo.order_id) q;

SELECT jsonb_build_object('id','T6-013','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM (WITH coupon_totals AS (
 SELECT order_id,SUM(discount_amount) AS discount_amount FROM coupon_usages GROUP BY order_id
), order_totals AS (
 SELECT order_id,SUM(price+freight_value) AS order_gmv FROM order_items GROUP BY order_id
)
SELECT ROUND(SUM(ot.order_gmv),2) AS gmv,ROUND(SUM(ct.discount_amount),2) AS discount,
 ROUND(100.0*SUM(ct.discount_amount)/NULLIF(SUM(ot.order_gmv),0),2) AS discount_pct
FROM coupon_totals ct JOIN orders o ON o.order_id=ct.order_id
JOIN order_totals ot ON ot.order_id=ct.order_id
WHERE o.order_status NOT IN ('canceled','unavailable')) q;

SELECT jsonb_build_object('id','T6-015','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM (SELECT st.category_id,ROUND(100.0*COUNT(*) FILTER(
 WHERE st.resolved_at IS NOT NULL
 AND EXTRACT(EPOCH FROM(st.resolved_at-st.created_at))/3600.0<=tc.sla_hours
)/NULLIF(COUNT(*),0),2) AS sla_pct
FROM support_tickets st LEFT JOIN ticket_categories tc ON tc.category_id=st.category_id
GROUP BY st.category_id ORDER BY sla_pct) q;

SELECT jsonb_build_object('id','T6-022','result',COALESCE(jsonb_agg(q),'[]'::jsonb)) FROM (WITH monthly AS (
 SELECT EXTRACT(YEAR FROM o.order_purchase_timestamp)::int AS yr,
 EXTRACT(MONTH FROM o.order_purchase_timestamp)::int AS mo,SUM(oi.price+oi.freight_value) AS gmv
 FROM orders o JOIN order_items oi ON oi.order_id=o.order_id
 WHERE o.order_status NOT IN ('canceled','unavailable')
 AND o.order_purchase_timestamp>=DATE '2017-01-01' AND o.order_purchase_timestamp<DATE '2019-01-01'
 GROUP BY 1,2
), months AS (SELECT generate_series(1,12) AS mo)
SELECT m.mo,ROUND(m17.gmv,2) AS gmv_2017,ROUND(m18.gmv,2) AS gmv_2018,
 ROUND(100.0*(m18.gmv-m17.gmv)/NULLIF(m17.gmv,0),2) AS yoy_pct
FROM months m LEFT JOIN monthly m17 ON m17.mo=m.mo AND m17.yr=2017
LEFT JOIN monthly m18 ON m18.mo=m.mo AND m18.yr=2018 ORDER BY m.mo) q;
ROLLBACK;
