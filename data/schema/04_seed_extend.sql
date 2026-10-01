-- 扩展表数据生成
-- 关键约束：所有外键必须引用 Olist 真实存在的 ID，否则后续 join 查询会返回空结果，
-- 评估集会因此失效。生成后必须做一次完整性校验（见 05_verify.sql）。
-- 使用 setseed 保证可复现：同样脚本跑两次得到同样的数据。

\set ON_ERROR_STOP on
SELECT setseed(0.42);

-- ============ 用户域 ============

TRUNCATE member_levels, members, member_snapshot, user_tags, user_tag_map CASCADE;

INSERT INTO member_levels (level_id, level_code, level_name, discount_rate) VALUES
  (1, 'BRONZE', '青铜会员', 1.000),
  (2, 'SILVER', '白银会员', 0.980),
  (3, 'GOLD',   '黄金会员', 0.950),
  (4, 'PLATINUM','铂金会员', 0.920);

-- 会员以自然人（customer_unique_id）为单位，一个自然人可能有多个 customer_id
INSERT INTO members (customer_unique_id, level_id, status, points, register_date, last_login_date)
SELECT c.customer_unique_id,
       -- 等级按消费能力加权随机：多数是青铜/白银
       CASE
         WHEN random() < 0.55 THEN 1
         WHEN random() < 0.85 THEN 2
         WHEN random() < 0.97 THEN 3
         ELSE 4
       END,
       -- 状态：绝大多数正常，少量冻结/注销
       CASE
         WHEN random() < 0.94 THEN 1
         WHEN random() < 0.98 THEN 2
         ELSE 3
       END,
       (random() * 5000)::INTEGER,
       -- 注册日期落在订单时间范围内
       DATE '2016-01-01' + ((random() * 1000)::INTEGER),
       DATE '2017-01-01' + ((random() * 660)::INTEGER)
FROM (SELECT DISTINCT customer_unique_id FROM customers) c;

-- 快照表：只覆盖部分会员，制造"表存在但数据不全"的真实感
-- 快照等级刻意与当前等级存在少量漂移（约 15%），否则
-- 「当前等级 vs 快照等级」这类对比题的答案会恒为 0，失去评估意义。
INSERT INTO member_snapshot (member_id, snapshot_date, level_id, points)
SELECT m.member_id,
       DATE '2018-01-01' + ((random() * 300)::INTEGER),
       CASE WHEN random() < 0.15 THEN GREATEST(1, m.level_id - 1) ELSE m.level_id END,
       GREATEST(0, m.points - (random() * 800)::INTEGER)
FROM members m
WHERE random() < 0.4;

INSERT INTO user_tags (tag_id, tag_code, tag_name) VALUES
  (1, 'HIGH_VALUE',   '高价值用户'),
  (2, 'CHURN_RISK',   '流失风险用户'),
  (3, 'PRICE_SENSITIVE','价格敏感用户'),
  (4, 'NEW_USER',     '新客'),
  (5, 'REPEAT_BUYER', '复购用户'),
  (6, 'REVIEW_ACTIVE','活跃评价用户');

INSERT INTO user_tag_map (member_id, tag_id, tagged_at)
-- 标签按真实行为推导，而不是随机贴。
-- 原先的子查询没有引用外层 m，PostgreSQL 把它当成不相关子查询只求值一次，
-- 结果 96096 个会员全部拿到同一个标签，所有标签类评估题的答案都退化成 0。
WITH buyer AS (
  SELECT c.customer_unique_id,
         COUNT(DISTINCT o.order_id) AS order_cnt,
         COUNT(DISTINCT r.review_id) AS review_cnt,
         COUNT(DISTINCT o.order_id) FILTER (WHERE p.payment_type IN ('boleto', 'voucher')) AS cheap_pay_cnt
  FROM customers c
  JOIN orders o ON o.customer_id = c.customer_id
  LEFT JOIN order_reviews r ON r.order_id = o.order_id
  LEFT JOIN order_payments p ON p.order_id = o.order_id
  GROUP BY 1
)
SELECT m.member_id, t.tag_id, DATE '2018-01-01' + ((random() * 300)::INTEGER)
FROM members m
JOIN buyer b ON b.customer_unique_id = m.customer_unique_id
CROSS JOIN LATERAL (
  VALUES
    (1, m.points >= 4000),                                    -- 高价值：积分 4000 以上
    (2, m.last_login_date < DATE '2018-04-01'),               -- 流失风险：半年内未登录
    (3, b.cheap_pay_cnt > 0),                                 -- 价格敏感：用过 boleto/voucher
    (4, m.register_date >= DATE '2018-06-01'),                -- 新客：2018-06 之后注册
    (5, b.order_cnt >= 2),                                    -- 复购：有效订单 2 笔以上
    (6, b.review_cnt > 0)                                     -- 活跃评价：写过评价
) AS t(tag_id, hit)
WHERE t.hit;

-- ============ 区域与地址 ============

TRUNCATE regions, shipping_addresses CASCADE;

-- 巴西州份到大区的映射
INSERT INTO regions (region_code, region_name, macro_region) VALUES
  ('SP','São Paulo','Sudeste'),
  ('RJ','Rio de Janeiro','Sudeste'),
  ('MG','Minas Gerais','Sudeste'),
  ('ES','Espírito Santo','Sudeste'),
  ('RS','Rio Grande do Sul','Sul'),
  ('PR','Paraná','Sul'),
  ('SC','Santa Catarina','Sul'),
  ('BA','Bahia','Nordeste'),
  ('PE','Pernambuco','Nordeste'),
  ('CE','Ceará','Nordeste'),
  ('DF','Distrito Federal','Centro-Oeste'),
  ('GO','Goiás','Centro-Oeste'),
  ('MT','Mato Grosso','Centro-Oeste'),
  ('MS','Mato Grosso do Sul','Centro-Oeste'),
  ('AM','Amazonas','Norte'),
  ('PA','Pará','Norte'),
  ('RO','Rondônia','Norte'),
  ('AC','Acre','Norte'),
  ('AP','Amapá','Norte'),
  ('RR','Roraima','Norte'),
  ('TO','Tocantins','Norte'),
  ('MA','Maranhão','Nordeste'),
  ('PI','Piauí','Nordeste'),
  ('RN','Rio Grande do Norte','Nordeste'),
  ('PB','Paraíba','Nordeste'),
  ('AL','Alagoas','Nordeste'),
  ('SE','Sergipe','Nordeste');

INSERT INTO shipping_addresses (customer_id, zip_code_prefix, city, state, is_default)
SELECT customer_id, customer_zip_code_prefix, customer_city, customer_state, true
FROM customers;

-- ============ 商品域补充 ============

TRUNCATE brands, product_price_history CASCADE;

TRUNCATE product_brand_map CASCADE;

INSERT INTO brands (brand_id, brand_name, country)
SELECT g, 'Brand_' || g, (ARRAY['BR','CN','US','DE','IT'])[1 + (g % 5)]
FROM generate_series(1, 60) g;

-- 价格变动历史：只覆盖部分商品，模拟真实的价格调整记录
INSERT INTO product_price_history (product_id, old_price, new_price, changed_at)
SELECT p.product_id,
       ROUND((random() * 300 + 20)::NUMERIC, 2),
       ROUND((random() * 300 + 20)::NUMERIC, 2),
       TIMESTAMP '2017-01-01' + (random() * 600) * INTERVAL '1 day'
FROM products p
WHERE random() < 0.3;

-- 商品品牌关联：约 70% 的商品有品牌，每件商品归属一个品牌
INSERT INTO product_brand_map (product_id, brand_id, is_current, mapped_at)
SELECT p.product_id,
       1 + (ABS(HASHTEXT(p.product_id)) % 60),
       true,
       DATE '2017-01-01' + (random() * 600)::INTEGER
FROM products p
WHERE random() < 0.7;

-- ============ 营销域 ============

TRUNCATE campaigns, coupons, coupon_usages, campaign_products, referrals CASCADE;

INSERT INTO campaigns (campaign_id, campaign_name, campaign_type, start_date, end_date, budget, status)
SELECT g,
       '活动_' || g,
       (ARRAY['DISCOUNT','COUPON','FLASH_SALE','MEMBER_DAY'])[1 + (g % 4)],
       DATE '2017-01-01' + (g * 15),
       DATE '2017-01-01' + (g * 15) + 30,
       ROUND((random() * 100000 + 5000)::NUMERIC, 2),
       CASE WHEN g <= 40 THEN 3 WHEN g <= 48 THEN 2 ELSE 1 END
FROM generate_series(1, 52) g;

INSERT INTO coupons (coupon_id, coupon_code, campaign_id, discount_type, discount_value, min_order_amount, valid_from, valid_to)
SELECT g,
       'CPN' || LPAD(g::TEXT, 6, '0'),
       1 + (g % 52),
       CASE WHEN g % 2 = 0 THEN 'fixed' ELSE 'percent' END,
       CASE WHEN g % 2 = 0 THEN ROUND((random() * 50 + 5)::NUMERIC, 2)
            ELSE ROUND((random() * 0.3 + 0.05)::NUMERIC, 3) END,
       ROUND((random() * 200)::NUMERIC, 2),
       DATE '2017-01-01' + (random() * 500)::INTEGER,
       DATE '2017-06-01' + (random() * 500)::INTEGER
FROM generate_series(1, 300) g;

-- 用券记录：引用真实订单 ID，保证与订单域可 join
INSERT INTO coupon_usages (coupon_id, order_id, member_id, used_at, discount_amount)
SELECT 1 + (random() * 299)::INTEGER,
       o.order_id,
       m.member_id,
       o.order_purchase_timestamp,
       ROUND((random() * 60 + 5)::NUMERIC, 2)
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
WHERE random() < 0.12;

INSERT INTO campaign_products (campaign_id, product_id)
SELECT 1 + (random() * 51)::INTEGER, product_id
FROM products
WHERE random() < 0.08;

INSERT INTO referrals (referrer_member_id, invitee_member_id, invite_code, created_at, converted_at, reward_amount)
-- 注意：join 条件里不能出现 random()。
-- 写成 m2.member_id = m1.member_id + random() 会让 Postgres 无法用索引，
-- 退化成 96096 x 96096 的嵌套循环（实测跑 100 秒以上仍无结果）。
-- 改用确定性偏移 + 取模抽样，让优化器走 hash join。
SELECT a.member_id,
       b.member_id,
       'REF' || LPAD(a.member_id::TEXT, 8, '0'),
       TIMESTAMP '2017-06-01' + (random() * 400) * INTERVAL '1 day',
       CASE WHEN random() < 0.35
            THEN TIMESTAMP '2017-06-01' + (random() * 400) * INTERVAL '1 day'
            ELSE NULL END,
       CASE WHEN random() < 0.35 THEN ROUND((random() * 30 + 5)::NUMERIC, 2) ELSE NULL END
FROM members a
JOIN members b ON b.member_id = a.member_id + 1 + (a.member_id % 500)
WHERE a.member_id % 20 = 0;

-- ============ 履约域 ============

TRUNCATE warehouses, shipments, shipment_tracking, inventory, inventory_movements CASCADE;

INSERT INTO warehouses (warehouse_id, warehouse_name, region_code, capacity) VALUES
  (1,'圣保罗中心仓','SP',50000),
  (2,'里约分仓','RJ',20000),
  (3,'米纳斯仓','MG',15000),
  (4,'南部仓','RS',18000),
  (5,'东北仓','BA',12000);

-- 发货单：为已发货的订单生成，引用真实 order_id
INSERT INTO shipments (order_id, warehouse_id, carrier, shipped_at, delivered_at, status)
SELECT o.order_id,
       1 + (random() * 4)::INTEGER,
       (ARRAY['Correios','Jadlog','Loggi','Total Express'])[1 + (random() * 3)::INTEGER],
       o.order_delivered_carrier_date,
       o.order_delivered_customer_date,
       CASE
         WHEN o.order_delivered_customer_date IS NOT NULL THEN 3
         WHEN o.order_delivered_carrier_date IS NOT NULL THEN 2
         ELSE 1
       END
FROM orders o
WHERE o.order_status IN ('delivered','shipped','invoiced','processing','approved');

INSERT INTO shipment_tracking (shipment_id, event_time, event_code, location)
SELECT s.shipment_id,
       s.shipped_at + (g * INTERVAL '8 hours'),
       (ARRAY['PICKED','IN_TRANSIT','ARRIVED','OUT_FOR_DELIVERY','DELIVERED'])[1 + (g % 5)],
       (ARRAY['SP','RJ','MG','RS','BA'])[1 + (random() * 4)::INTEGER]
FROM shipments s
CROSS JOIN generate_series(1, 3) g
WHERE s.shipped_at IS NOT NULL;

INSERT INTO inventory (warehouse_id, product_id, quantity, safety_stock, updated_at)
SELECT w.warehouse_id, p.product_id,
       (random() * 500)::INTEGER,
       (random() * 50)::INTEGER,
       TIMESTAMP '2018-08-01' + (random() * 60) * INTERVAL '1 day'
FROM warehouses w
CROSS JOIN products p
WHERE random() < 0.15;

INSERT INTO inventory_movements (warehouse_id, product_id, movement_type, quantity, moved_at)
SELECT i.warehouse_id, i.product_id,
       1 + (random() * 3)::INTEGER,
       (random() * 100)::INTEGER,
       TIMESTAMP '2018-01-01' + (random() * 300) * INTERVAL '1 day'
FROM inventory i
CROSS JOIN generate_series(1, 2) g;

-- ============ 客服域 ============

TRUNCATE ticket_categories, support_tickets, ticket_messages CASCADE;

INSERT INTO ticket_categories (category_id, category_name, sla_hours) VALUES
  (1,'物流延迟',24),
  (2,'商品质量问题',48),
  (3,'退款咨询',12),
  (4,'发票问题',72),
  (5,'账号问题',24),
  (6,'其他',48);

INSERT INTO support_tickets (order_id, member_id, category_id, priority, status, created_at, resolved_at)
SELECT o.order_id,
       m.member_id,
       1 + (random() * 5)::INTEGER,
       1 + (random() * 3)::INTEGER,
       CASE WHEN random() < 0.8 THEN 3 ELSE 2 END,
       o.order_purchase_timestamp + (random() * 30) * INTERVAL '1 day',
       CASE WHEN random() < 0.8
            THEN o.order_purchase_timestamp + (random() * 35 + 1) * INTERVAL '1 day'
            ELSE NULL END
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
WHERE random() < 0.08;

INSERT INTO ticket_messages (ticket_id, sender_type, content, created_at)
SELECT t.ticket_id,
       CASE WHEN g % 2 = 1 THEN 'customer' ELSE 'agent' END,
       '消息内容_' || g,
       t.created_at + (g * INTERVAL '2 hours')
FROM support_tickets t
CROSS JOIN generate_series(1, 2) g;

-- ============ 财务域 ============

TRUNCATE refunds, refund_items, settlements, settlement_items CASCADE;

INSERT INTO refunds (order_id, refund_amount, reason_code, status, requested_at, completed_at)
SELECT o.order_id,
       ROUND((random() * 200 + 10)::NUMERIC, 2),
       (ARRAY['CUST','QUAL','LATE','OTHER'])[1 + (random() * 3)::INTEGER],
       CASE WHEN random() < 0.7 THEN 4 WHEN random() < 0.85 THEN 2 ELSE 1 END,
       o.order_purchase_timestamp + (random() * 40) * INTERVAL '1 day',
       CASE WHEN random() < 0.7
            THEN o.order_purchase_timestamp + (random() * 45 + 2) * INTERVAL '1 day'
            ELSE NULL END
FROM orders o
WHERE random() < 0.06;

INSERT INTO refund_items (refund_id, order_id, order_item_id, refund_qty, refund_value)
SELECT r.refund_id, r.order_id, oi.order_item_id, 1, oi.price
FROM refunds r
JOIN order_items oi ON oi.order_id = r.order_id
WHERE random() < 0.8;

-- 结算单：按卖家 + 月度周期生成，引用真实 seller_id 和订单明细
INSERT INTO settlements (seller_id, period_start, period_end, total_amount, commission, net_amount, status, paid_at)
SELECT s.seller_id,
       d.period_start,
       d.period_start + 30,
       0, 0, 0,
       CASE WHEN random() < 0.8 THEN 3 ELSE 2 END,
       CASE WHEN random() < 0.8 THEN d.period_start + 45 ELSE NULL END
FROM (SELECT DISTINCT seller_id FROM sellers) s
CROSS JOIN LATERAL (
  SELECT DATE '2017-01-01' + (g * 30) AS period_start
  FROM generate_series(0, 20) g
) d
WHERE random() < 0.35;

-- 结算明细：关联到该卖家在该周期的真实订单明细
INSERT INTO settlement_items (settlement_id, order_id, order_item_id, gross_amount, commission_rate, commission_amount)
SELECT st.settlement_id, oi.order_id, oi.order_item_id,
       oi.price + oi.freight_value,
       0.10,
       ROUND((oi.price + oi.freight_value) * 0.10, 2)
FROM settlements st
JOIN order_items oi ON oi.seller_id = st.seller_id
JOIN orders o ON o.order_id = oi.order_id
WHERE o.order_purchase_timestamp >= st.period_start
  AND o.order_purchase_timestamp < st.period_start + 30;

-- 回填结算汇总金额
UPDATE settlements st
SET total_amount = x.gross, commission = x.comm, net_amount = x.gross - x.comm
FROM (
  SELECT settlement_id, SUM(gross_amount) AS gross, SUM(commission_amount) AS comm
  FROM settlement_items GROUP BY 1
) x
WHERE x.settlement_id = st.settlement_id;

DELETE FROM settlements WHERE total_amount = 0;

-- ============ 行为域 ============

TRUNCATE search_logs CASCADE;

INSERT INTO search_logs (member_id, keyword, result_count, clicked_product_id, searched_at)
-- 注意：不能对每个会员都 ORDER BY random() 取一个商品。
-- 那会对 32951 行的 products 表反复全排序，实测超过 1 分钟。
-- 改成预先生成一批随机商品 ID，再做数组随机取值。
SELECT m.member_id,
       (ARRAY['手机壳','耳机','沙发','香水','玩具','手表','书籍','运动鞋','背包','台灯'])[1 + (random() * 9)::INTEGER],
       (random() * 200)::INTEGER,
       CASE WHEN random() < 0.6 THEN sample.ids[1 + (random() * (array_length(sample.ids, 1) - 1))::INTEGER] ELSE NULL END,
       TIMESTAMP '2018-01-01' + (random() * 300) * INTERVAL '1 day'
FROM members m
CROSS JOIN (SELECT array_agg(product_id) AS ids FROM products) sample
WHERE random() < 0.15;

ANALYZE;
