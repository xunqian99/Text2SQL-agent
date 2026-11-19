BEGIN READ ONLY;
SET LOCAL statement_timeout = '8s';
WITH q AS (SELECT t.product_category_name_english, ROUND(SUM(ri.refund_value), 2) AS refund_amt
FROM refunds r
JOIN refund_items ri ON ri.refund_id = r.refund_id
JOIN order_items oi ON oi.order_id = ri.order_id AND oi.order_item_id = ri.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1 ORDER BY refund_amt DESC), z AS (SELECT q.*, ROW_NUMBER() OVER(ORDER BY refund_amt DESC) AS rn FROM q), boundary AS (SELECT refund_amt AS value FROM z WHERE rn=15) SELECT jsonb_build_object('id','T5-003','check','TopK boundary','result',jsonb_build_object('limit',15,'total_groups',(SELECT COUNT(*) FROM q),'boundary_value',(SELECT value FROM boundary),'groups_equal_to_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.refund_amt=boundary.value),'groups_above_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.refund_amt>boundary.value)));
WITH q AS (SELECT ca.campaign_name, COUNT(DISTINCT cu.order_id) AS used_orders, ROUND(SUM(oi.price), 2) AS gmv
FROM coupon_usages cu
JOIN coupons cp ON cp.coupon_id = cu.coupon_id
JOIN campaigns ca ON ca.campaign_id = cp.campaign_id
JOIN orders o ON o.order_id = cu.order_id
JOIN order_items oi ON oi.order_id = cu.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY gmv DESC), z AS (SELECT q.*, ROW_NUMBER() OVER(ORDER BY gmv DESC) AS rn FROM q), boundary AS (SELECT gmv AS value FROM z WHERE rn=10) SELECT jsonb_build_object('id','T5-005','check','TopK boundary','result',jsonb_build_object('limit',10,'total_groups',(SELECT COUNT(*) FROM q),'boundary_value',(SELECT value FROM boundary),'groups_equal_to_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.gmv=boundary.value),'groups_above_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.gmv>boundary.value)));
WITH q AS (SELECT sl.keyword, t.product_category_name_english, COUNT(*) AS clicks
FROM search_logs sl
JOIN products p ON p.product_id = sl.clicked_product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
JOIN members m ON m.member_id = sl.member_id
JOIN member_levels ml ON ml.level_id = m.level_id
WHERE sl.clicked_product_id IS NOT NULL
GROUP BY 1, 2 ORDER BY clicks DESC), z AS (SELECT q.*, ROW_NUMBER() OVER(ORDER BY clicks DESC) AS rn FROM q), boundary AS (SELECT clicks AS value FROM z WHERE rn=20) SELECT jsonb_build_object('id','T5-007','check','TopK boundary','result',jsonb_build_object('limit',20,'total_groups',(SELECT COUNT(*) FROM q),'boundary_value',(SELECT value FROM boundary),'groups_equal_to_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.clicks=boundary.value),'groups_above_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.clicks>boundary.value)));
WITH q AS (SELECT w.warehouse_name, r.region_name, b.brand_name, COUNT(*) AS low_stock_items
FROM inventory i
JOIN warehouses w ON w.warehouse_id = i.warehouse_id
JOIN regions r ON r.region_code = w.region_code
JOIN product_brand_map pbm ON pbm.product_id = i.product_id AND pbm.is_current
JOIN brands b ON b.brand_id = pbm.brand_id
WHERE i.quantity < i.safety_stock
GROUP BY 1, 2, 3 ORDER BY low_stock_items DESC), z AS (SELECT q.*, ROW_NUMBER() OVER(ORDER BY low_stock_items DESC) AS rn FROM q), boundary AS (SELECT low_stock_items AS value FROM z WHERE rn=20) SELECT jsonb_build_object('id','T5-009','check','TopK boundary','result',jsonb_build_object('limit',20,'total_groups',(SELECT COUNT(*) FROM q),'boundary_value',(SELECT value FROM boundary),'groups_equal_to_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.low_stock_items=boundary.value),'groups_above_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.low_stock_items>boundary.value)));
WITH q AS (SELECT st.seller_id, COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
       ROUND(SUM(si.commission_amount), 2) AS commission
FROM settlements st
JOIN settlement_items si ON si.settlement_id = st.settlement_id
JOIN order_items oi ON oi.order_id = si.order_id AND oi.order_item_id = si.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1 ORDER BY commission DESC), z AS (SELECT q.*, ROW_NUMBER() OVER(ORDER BY commission DESC) AS rn FROM q), boundary AS (SELECT commission AS value FROM z WHERE rn=20) SELECT jsonb_build_object('id','T5-010','check','TopK boundary','result',jsonb_build_object('limit',20,'total_groups',(SELECT COUNT(*) FROM q),'boundary_value',(SELECT value FROM boundary),'groups_equal_to_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.commission=boundary.value),'groups_above_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.commission>boundary.value)));
WITH q AS (SELECT t.product_category_name_english, r.reason_code,
       COUNT(DISTINCT r.refund_id) AS refunds, ROUND(SUM(ri.refund_value), 2) AS amt
FROM refunds r
JOIN refund_items ri ON ri.refund_id = r.refund_id
JOIN order_items oi ON oi.order_id = ri.order_id AND oi.order_item_id = ri.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1, 2 ORDER BY amt DESC), z AS (SELECT q.*, ROW_NUMBER() OVER(ORDER BY amt DESC) AS rn FROM q), boundary AS (SELECT amt AS value FROM z WHERE rn=20) SELECT jsonb_build_object('id','T5-016','check','TopK boundary','result',jsonb_build_object('limit',20,'total_groups',(SELECT COUNT(*) FROM q),'boundary_value',(SELECT value FROM boundary),'groups_equal_to_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.amt=boundary.value),'groups_above_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.amt>boundary.value)));
WITH q AS (SELECT sa.state AS addr_state, c.customer_state, COUNT(DISTINCT o.order_id) AS orders
FROM shipping_addresses sa
JOIN customers c ON c.customer_id = sa.customer_id
JOIN orders o ON o.customer_id = c.customer_id
WHERE sa.is_default
GROUP BY 1, 2 ORDER BY orders DESC), z AS (SELECT q.*, ROW_NUMBER() OVER(ORDER BY orders DESC) AS rn FROM q), boundary AS (SELECT orders AS value FROM z WHERE rn=20) SELECT jsonb_build_object('id','T5-018','check','TopK boundary','result',jsonb_build_object('limit',20,'total_groups',(SELECT COUNT(*) FROM q),'boundary_value',(SELECT value FROM boundary),'groups_equal_to_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.orders=boundary.value),'groups_above_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.orders>boundary.value)));
WITH q AS (SELECT c.customer_state,
       ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)) / 86400.0), 2) AS avg_days
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_delivered_customer_date IS NOT NULL
GROUP BY 1 ORDER BY avg_days DESC), z AS (SELECT q.*, ROW_NUMBER() OVER(ORDER BY avg_days DESC) AS rn FROM q), boundary AS (SELECT avg_days AS value FROM z WHERE rn=10) SELECT jsonb_build_object('id','T6-019','check','TopK boundary','result',jsonb_build_object('limit',10,'total_groups',(SELECT COUNT(*) FROM q),'boundary_value',(SELECT value FROM boundary),'groups_equal_to_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.avg_days=boundary.value),'groups_above_boundary',(SELECT COUNT(*) FROM q,boundary WHERE q.avg_days>boundary.value)));
ROLLBACK;
