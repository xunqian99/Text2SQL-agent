# 原正式100题契约独立复核包（待外部填写）

候选生产提交：`46efb8eeb01c70ea6eae65b86270ea13c14b108f`。编制日期：2026-11-19。

**状态：PENDING。复核人：未填写。独立性说明：未填写。签字日期：未填写。**

本包由实现AI整理，所有裁决留空。下面是已见原题参考审核，不是未见盲测；不得将AI自审当作独立审核。

请未参与原题/Gold编写和本轮调参的人员逐题确认：统计对象/身份、事实全集、维度显示、时间参照与边界、NULL/零事实、单位、并列与输出形状。

每题填写：保持原参考 / 接受修订并写理由 / 需要业务补充。SQL可运行不代表业务正确；诊断SQL只是候选，不能直接签收。

禁止改原v2文件和历史分数。若形成新协议，另存版本、冻结差异及门槛后再测；不能跨参考版本宣称模型提升。

| 汇总 | 值 |
|---|---:|
| 原eval题数 | 100 |
| AI标出的确定冲突 | 14 |
| 外部已签收条数 | 0 |
| 新供应商调用 | 0 |

独立未见盲测另需外部人员持有尚未向实现方公开的题目和Gold。本包不满足该要求。

## 1. T1-001

一共有多少笔订单？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM orders;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 2. T1-003

已取消的订单有多少笔？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM orders WHERE order_status = 'canceled';
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 3. T1-005

单价超过 1000 的订单明细有多少条？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM order_items WHERE price > 1000;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 4. T1-007

一共有多少个卖家？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM sellers;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 5. T1-009

圣保罗州（SP）的客户有多少个？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM customers WHERE customer_state = 'SP';
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 6. T1-010

订单状态一共有几种？

**原Gold（保持原样）**

```sql
SELECT COUNT(DISTINCT order_status) FROM orders;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 7. T1-012

价格最高的 10 条订单明细是哪些？

**原Gold（保持原样）**

```sql
SELECT order_id, order_item_id, price
FROM order_items ORDER BY price DESC LIMIT 10;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 8. T1-014

评价分数为 1 分的评价有多少条？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM order_reviews WHERE review_score = 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 9. T1-016

分期数最多的那一笔支付分了多少期？

**原Gold（保持原样）**

```sql
SELECT order_id, payment_installments
FROM order_payments ORDER BY payment_installments DESC LIMIT 1;
```

**AI审查提示（需独立核实）**

- 截断线存在并列，Gold无二级排序，允许成员集合未冻结；不计入确定冲突。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 10. T1-018

订单明细里运费超过 50 的有多少条？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM order_items WHERE freight_value > 50;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 11. T1-021

里约热内卢州（RJ）的卖家有多少个？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM sellers WHERE seller_state = 'RJ';
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 12. T1-023

没有填写类目的商品有多少个？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM products WHERE product_category_name IS NULL;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 13. T1-025

状态为冻结的会员有多少人？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM members WHERE status = 2;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 14. T1-027

一共有几个仓库？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM warehouses;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 15. T1-029

已经完成退款的退款单有多少笔？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM refunds WHERE status = 4;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 16. T1-030

因为质量问题发起的退款有多少笔？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM refunds WHERE reason_code = 'QUAL';
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 17. T1-032

满减类型的优惠券有多少张？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM coupons WHERE discount_type = 'fixed';
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 18. T1-034

客服工单一共有几个分类？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM ticket_categories;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 19. T1-036

出库类型的库存变动记录有多少条？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM inventory_movements WHERE movement_type = 2;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 20. T1-038

有多少个州属于「Sudeste」这个大区？

**原Gold（保持原样）**

```sql
SELECT COUNT(*) FROM regions WHERE macro_region = 'Sudeste';
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 21. T2-002

所有订单的运费总额是多少？

**原Gold（保持原样）**

```sql
SELECT SUM(freight_value) FROM order_items;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 22. T2-004

平均每个订单有多少条明细？

**原Gold（保持原样）**

```sql
SELECT ROUND(AVG(cnt), 2) FROM (
  SELECT order_id, COUNT(*) AS cnt FROM order_items GROUP BY order_id
) t;
```

**AI审查提示（需独立核实）**

- Gold均值仅覆盖有明细订单，775单无明细；尚未冻结全部订单是否补0的业务口径，不计入确定冲突。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 23. T2-006

每种支付方式各有多少笔支付记录？

**原Gold（保持原样）**

```sql
SELECT payment_type, COUNT(*) AS cnt
FROM order_payments GROUP BY payment_type ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 24. T2-008

客户数量最多的 5 个州是哪些？各有多少客户？

**原Gold（保持原样）**

```sql
SELECT customer_state, COUNT(*) AS cnt
FROM customers GROUP BY customer_state ORDER BY cnt DESC LIMIT 5;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 25. T2-011

每个卖家的商品销售总额是多少？取金额最高的 10 个卖家。

**原Gold（保持原样）**

```sql
SELECT seller_id, ROUND(SUM(price), 2) AS total
FROM order_items GROUP BY seller_id ORDER BY total DESC LIMIT 10;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 26. T2-013

已送达订单和已取消订单各有多少笔？

**原Gold（保持原样）**

```sql
SELECT
  COUNT(*) FILTER (WHERE order_status = 'delivered') AS delivered,
  COUNT(*) FILTER (WHERE order_status = 'canceled')  AS canceled
FROM orders;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 27. T2-015

每个会员等级的平均积分是多少？

**原Gold（保持原样）**

```sql
SELECT level_id, ROUND(AVG(points), 2) AS avg_points
FROM members GROUP BY level_id ORDER BY level_id;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 28. T2-017

每个大区各包含多少个州？

**原Gold（保持原样）**

```sql
SELECT macro_region, COUNT(*) AS cnt
FROM regions GROUP BY macro_region ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 29. T2-019

每个活动的预算总额是多少？

**原Gold（保持原样）**

```sql
SELECT campaign_id, budget FROM campaigns ORDER BY budget DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 30. T2-020

每种折扣类型的优惠券各有多少张？

**原Gold（保持原样）**

```sql
SELECT discount_type, COUNT(*) AS cnt
FROM coupons GROUP BY discount_type ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 31. T2-022

退款总金额是多少？

**原Gold（保持原样）**

```sql
SELECT ROUND(SUM(refund_amount), 2) FROM refunds;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 32. T2-024

每个仓库各有多少条库存记录？

**原Gold（保持原样）**

```sql
SELECT warehouse_id, COUNT(*) AS cnt
FROM inventory GROUP BY warehouse_id ORDER BY warehouse_id;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 33. T2-026

每个仓库的库存总数量是多少？

**原Gold（保持原样）**

```sql
SELECT warehouse_id, SUM(quantity) AS total_qty
FROM inventory GROUP BY warehouse_id ORDER BY warehouse_id;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 34. T2-028

每家承运商各承担了多少个发货单？

**原Gold（保持原样）**

```sql
SELECT carrier, COUNT(*) AS cnt
FROM shipments GROUP BY carrier ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 35. T2-031

每个优先级的工单各有多少个？

**原Gold（保持原样）**

```sql
SELECT priority, COUNT(*) AS cnt
FROM support_tickets GROUP BY priority ORDER BY priority;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 36. T2-033

平台支付给卖家的结算净额合计是多少？

**原Gold（保持原样）**

```sql
SELECT ROUND(SUM(net_amount), 2) FROM settlements;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 37. T2-035

搜索次数最多的 10 个关键词是哪些？

**原Gold（保持原样）**

```sql
SELECT keyword, COUNT(*) AS cnt
FROM search_logs GROUP BY keyword ORDER BY cnt DESC LIMIT 10;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 38. T2-037

订单数超过 5000 的州有哪些？

**原Gold（保持原样）**

```sql
SELECT customer_state, COUNT(*) AS cnt
FROM customers GROUP BY customer_state
HAVING COUNT(*) > 5000 ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

- 题目问订单，Gold数客户档案。现库客户与订单一对一且无孤儿，当前结果相同；不是可泛化的等价改写。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 39. T2-039

好评（4 分及以上）占所有评价的比例是多少？

**原Gold（保持原样）**

```sql
SELECT ROUND(
  COUNT(*) FILTER (WHERE review_score >= 4)::NUMERIC / COUNT(*), 4
) AS good_rate
FROM order_reviews;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 40. T2-040

每个支付方式收上来的钱合计是多少？

**原Gold（保持原样）**

```sql
SELECT payment_type, ROUND(SUM(payment_value), 2) AS total
FROM order_payments GROUP BY payment_type ORDER BY total DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 41. T3-001

每个月的订单数量是多少？按月份排序。

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('month', order_purchase_timestamp) AS month,
       COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 42. T3-003

每个季度有多少笔订单？

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('quarter', order_purchase_timestamp) AS quarter, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 43. T3-005

订单量最多的那一周是哪一周？有多少笔？

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('week', order_purchase_timestamp)::DATE AS week_start, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY cnt DESC LIMIT 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 44. T3-007

每个月的运费总额是多少？

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('month', o.order_purchase_timestamp) AS month,
       ROUND(SUM(oi.freight_value), 2) AS freight
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 45. T3-009

2018 年每个月的订单量是多少？

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('month', order_purchase_timestamp) AS month,
       COUNT(*) AS cnt
FROM orders
WHERE order_purchase_timestamp >= '2018-01-01'
  AND order_purchase_timestamp < '2019-01-01'
GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 46. T3-010

每个月的订单量，以及相比上个月的变化量是多少？

**原Gold（保持原样）**

```sql
WITH monthly AS (
  SELECT DATE_TRUNC('month', order_purchase_timestamp) AS m, COUNT(*) AS cnt
  FROM orders GROUP BY 1
)
SELECT m AS month,
       cnt,
       cnt - LAG(cnt) OVER (ORDER BY m) AS mom_change
FROM monthly ORDER BY m;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 47. T3-012

2018 年每个月的订单量相比 2017 年同期增长了多少？

**原Gold（保持原样）**

```sql
WITH monthly AS (
  SELECT EXTRACT(YEAR FROM order_purchase_timestamp)::INT AS yr,
         EXTRACT(MONTH FROM order_purchase_timestamp)::INT AS mo,
         COUNT(*) AS cnt
  FROM orders
  WHERE EXTRACT(YEAR FROM order_purchase_timestamp) IN (2017, 2018)
  GROUP BY 1, 2
)
SELECT cur.mo AS month,
       cur.cnt - prev.cnt AS yoy_change
FROM monthly cur
JOIN monthly prev ON prev.mo = cur.mo AND prev.yr = 2017
WHERE cur.yr = 2018
ORDER BY cur.mo;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 48. T3-014

数据里最后 90 天，每周的商品销售额分别是多少？

**原Gold（保持原样）**

```sql
WITH anchor AS (SELECT MAX(order_purchase_timestamp) AS mx FROM orders)
SELECT DATE_TRUNC('week', o.order_purchase_timestamp)::DATE AS week_start,
       ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
CROSS JOIN anchor a
WHERE o.order_purchase_timestamp > a.mx - INTERVAL '90 days'
  AND o.order_purchase_timestamp <= a.mx
GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

- 现行最后N天默认自然日，Gold采用最大时刻回溯连续90天，首周金额相差10838.88。

**诊断候选SQL（未签收；不进入原评分）**

```sql
WITH anchor AS (SELECT MAX(order_purchase_timestamp) AS mx FROM orders)
SELECT DATE_TRUNC('week', o.order_purchase_timestamp)::DATE AS week_start,
       ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
CROSS JOIN anchor a
WHERE o.order_purchase_timestamp >= a.mx::date - 89
  AND o.order_purchase_timestamp < a.mx::date + 1
GROUP BY 1 ORDER BY 1
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 49. T3-016

一周中星期几的订单量最多？

**原Gold（保持原样）**

```sql
SELECT EXTRACT(DOW FROM order_purchase_timestamp)::INT AS dow, COUNT(*) AS cnt
FROM orders GROUP BY 1 ORDER BY cnt DESC LIMIT 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 50. T3-018

每个州的平均配送时长是多少天？

**原Gold（保持原样）**

```sql
SELECT c.customer_state,
       ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)) / 86400), 2) AS avg_days
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_delivered_customer_date IS NOT NULL
GROUP BY 1 ORDER BY avg_days DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 51. T3-021

每个月的退款单数量是多少？

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('month', requested_at) AS month, COUNT(*) AS cnt
FROM refunds GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 52. T3-023

每个月新增多少张客服工单？

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('month', created_at) AS month, COUNT(*) AS cnt
FROM support_tickets GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 53. T3-025

2018 年上半年（1 到 6 月）每个州的订单量是多少？

**原Gold（保持原样）**

```sql
SELECT c.customer_state, COUNT(*) AS cnt
FROM orders o JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_purchase_timestamp >= '2018-01-01'
  AND o.order_purchase_timestamp < '2018-07-01'
GROUP BY 1 ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 54. T3-027

每个月的会员注册数量是多少？

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('month', register_date) AS month, COUNT(*) AS cnt
FROM members WHERE register_date IS NOT NULL GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

- Gold过滤NULL注册日期；现库NULL为0，不构成当前结果冲突。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 55. T3-029

每个月的佣金收入是多少？

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('month', period_start) AS month,
       ROUND(SUM(commission), 2) AS total
FROM settlements GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 56. T3-030

每个季度的有效订单量分别是多少？按季度排序。

**原Gold（保持原样）**

```sql
SELECT DATE_TRUNC('quarter', order_purchase_timestamp) AS quarter,
       COUNT(*) AS cnt
FROM orders
WHERE order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 57. T4-002

每个州的商品销售额是多少？

**原Gold（保持原样）**

```sql
SELECT c.customer_state, ROUND(SUM(oi.price), 2) AS sales
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY sales DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 58. T4-004

用英文类目名统计，哪个类目的销售额最高？

**原Gold（保持原样）**

```sql
SELECT t.product_category_name_english, ROUND(SUM(oi.price), 2) AS sales
FROM order_items oi
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1 ORDER BY sales DESC LIMIT 1;
```

**AI审查提示（需独立核实）**

- 生产要求先澄清；旧严格单轮评分将澄清计错。

**当前生产需要补充的信息**

- 「销售额」有两种常见口径：含运费的 GMV，还是不含运费的商品金额？请确认一种。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 59. T4-006

每个卖家所在的州和该州卖家的总销售额是多少？

**原Gold（保持原样）**

```sql
SELECT s.seller_state, ROUND(SUM(oi.price), 2) AS sales
FROM order_items oi JOIN sellers s ON s.seller_id = oi.seller_id
GROUP BY 1 ORDER BY sales DESC;
```

**AI审查提示（需独立核实）**

- 生产要求先澄清；旧严格单轮评分将澄清计错。

**当前生产需要补充的信息**

- 「销售额」有两种常见口径：含运费的 GMV，还是不含运费的商品金额？请确认一种。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 60. T4-008

有多少个会员从来没有被打过任何标签？

**原Gold（保持原样）**

```sql
SELECT COUNT(*)
FROM members m
LEFT JOIN user_tag_map t ON t.member_id = m.member_id
WHERE t.member_id IS NULL;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 61. T4-011

每个订单状态的平均评价分是多少？

**原Gold（保持原样）**

```sql
SELECT o.order_status, ROUND(AVG(r.review_score), 2) AS avg_score
FROM orders o JOIN order_reviews r ON r.order_id = o.order_id
GROUP BY 1 ORDER BY avg_score DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 62. T4-013

差评（1 分或 2 分）的订单里，各州分别有多少笔？

**原Gold（保持原样）**

```sql
SELECT c.customer_state, COUNT(*) AS cnt
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
WHERE r.review_score <= 2
GROUP BY 1 ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

- 问题单位为不同订单；Gold数低分评价记录。多评价订单重复计数，按州结果实际不同。

**诊断候选SQL（未签收；不进入原评分）**

```sql
SELECT c.customer_state, COUNT(DISTINCT o.order_id) AS cnt
FROM order_reviews r
JOIN orders o ON o.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
WHERE r.review_score <= 2
GROUP BY 1 ORDER BY cnt DESC
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 63. T4-015

每个大区的订单数量是多少？

**原Gold（保持原样）**

```sql
SELECT r.macro_region, COUNT(*) AS cnt
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
GROUP BY 1 ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

- 生产要求先澄清；旧严格单轮评分将澄清计错。

**当前生产需要补充的信息**

- 交易指标按哪一方的地域归属统计大区：客户收货地、卖家所在地，还是仓库所在地？请明确来源。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 64. T4-017

每个会员等级对应的订单数量是多少？

**原Gold（保持原样）**

```sql
SELECT m.level_id, COUNT(*) AS cnt
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN members m ON m.customer_unique_id = c.customer_unique_id
GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 65. T4-019

每种用户标签下各有多少会员？

**原Gold（保持原样）**

```sql
SELECT t.tag_name, COUNT(DISTINCT m.member_id) AS cnt
FROM user_tag_map m JOIN user_tags t ON t.tag_id = m.tag_id
GROUP BY 1 ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

- Gold输出名称；当前规则8默认输出现有标识。仅替换展示列后结果已核实不同。

**诊断候选SQL（未签收；不进入原评分）**

```sql
SELECT t.tag_id, COUNT(DISTINCT m.member_id) AS cnt
FROM user_tag_map m JOIN user_tags t ON t.tag_id = m.tag_id
GROUP BY 1 ORDER BY cnt DESC
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 66. T4-020

打了"高价值用户"标签的会员一共下了多少单？

**原Gold（保持原样）**

```sql
SELECT COUNT(DISTINCT o.order_id) AS cnt
FROM user_tag_map tm
JOIN user_tags t ON t.tag_id = tm.tag_id
JOIN members m ON m.member_id = tm.member_id
JOIN customers c ON c.customer_unique_id = m.customer_unique_id
JOIN orders o ON o.customer_id = c.customer_id
WHERE t.tag_code = 'HIGH_VALUE';
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 67. T4-022

通过优惠券一共优惠了多少钱？

**原Gold（保持原样）**

```sql
SELECT ROUND(SUM(discount_amount), 2) FROM coupon_usages;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 68. T4-024

每个仓库（按仓库名称）负责了多少个发货单？

**原Gold（保持原样）**

```sql
SELECT w.warehouse_name, COUNT(*) AS cnt
FROM shipments s JOIN warehouses w ON w.warehouse_id = s.warehouse_id
GROUP BY 1 ORDER BY cnt DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 69. T4-026

每家承运商的平均配送时长是多少天？

**原Gold（保持原样）**

```sql
SELECT carrier,
       ROUND(AVG(EXTRACT(EPOCH FROM (delivered_at - shipped_at)) / 86400), 2) AS avg_days
FROM shipments
WHERE delivered_at IS NOT NULL AND shipped_at IS NOT NULL
GROUP BY 1 ORDER BY avg_days;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 70. T4-028

每个工单分类的平均处理时长是多少小时？

**原Gold（保持原样）**

```sql
SELECT tc.category_name,
       ROUND(AVG(EXTRACT(EPOCH FROM (st.resolved_at - st.created_at)) / 3600), 2) AS avg_hours
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
WHERE st.resolved_at IS NOT NULL
GROUP BY 1 ORDER BY avg_hours DESC;
```

**AI审查提示（需独立核实）**

- Gold输出名称；当前规则8默认输出现有标识。仅替换展示列后结果已核实不同。

**诊断候选SQL（未签收；不进入原评分）**

```sql
SELECT tc.category_id,
       ROUND(AVG(EXTRACT(EPOCH FROM (st.resolved_at - st.created_at)) / 3600), 2) AS avg_hours
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
WHERE st.resolved_at IS NOT NULL
GROUP BY 1 ORDER BY avg_hours DESC
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 71. T4-031

每个州的卖家结算净额合计是多少？

**原Gold（保持原样）**

```sql
SELECT s.seller_state, ROUND(SUM(st.net_amount), 2) AS total
FROM settlements st JOIN sellers s ON s.seller_id = st.seller_id
GROUP BY 1 ORDER BY total DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 72. T4-033

每个退款原因的退款金额合计是多少？

**原Gold（保持原样）**

```sql
SELECT reason_code, ROUND(SUM(refund_amount), 2) AS total
FROM refunds GROUP BY 1 ORDER BY total DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 73. T4-035

客单价最高的 10 个客户是谁？客单价按订单平均金额算。

**原Gold（保持原样）**

```sql
SELECT c.customer_unique_id,
       ROUND(SUM(oi.price + oi.freight_value) / COUNT(DISTINCT o.order_id), 2) AS avg_order_value
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1
ORDER BY avg_order_value DESC LIMIT 10;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 74. T4-037

每个商品的图片数量和它的销量分别是多少？取销量前 10。

**原Gold（保持原样）**

```sql
SELECT p.product_id, p.product_photos_qty, COUNT(*) AS sold
FROM order_items oi JOIN products p ON p.product_id = oi.product_id
GROUP BY 1, 2 ORDER BY sold DESC LIMIT 10;
```

**AI审查提示（需独立核实）**

- Gold只从有销量商品取Top10；未给零销量时的保留与TopK并列规则，不计确定冲突。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 75. T4-039

搜索后有点击的搜索记录占多少比例？

**原Gold（保持原样）**

```sql
SELECT ROUND(
  COUNT(*) FILTER (WHERE clicked_product_id IS NOT NULL)::NUMERIC / COUNT(*), 4) AS ctr
FROM search_logs;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 76. T4-040

有多少个商品从来没有进过任何仓库的库存表？

**原Gold（保持原样）**

```sql
SELECT COUNT(*)
FROM products p
LEFT JOIN inventory i ON i.product_id = p.product_id
WHERE i.product_id IS NULL;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 77. T4-042

发生过价格调整的商品里，有多少个商品的价格是上调的？

**原Gold（保持原样）**

```sql
SELECT COUNT(DISTINCT product_id) FROM product_price_history WHERE new_price > old_price;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 78. T4-044

每个大区有多少个客户？平均每人下多少单？

**原Gold（保持原样）**

```sql
SELECT r.macro_region,
       COUNT(DISTINCT c.customer_unique_id) AS customers,
       ROUND(COUNT(o.order_id)::NUMERIC / COUNT(DISTINCT c.customer_unique_id), 2) AS orders_per_customer
FROM customers c
JOIN regions r ON r.region_code = c.customer_state
LEFT JOIN orders o ON o.customer_id = c.customer_id
GROUP BY 1 ORDER BY customers DESC;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 79. T5-001

每个宏观区域下，各会员等级（按等级名称）的商品销售额分别是多少？按销售额从高到低排序。

**原Gold（保持原样）**

```sql
SELECT r.macro_region, ml.level_name, ROUND(SUM(oi.price), 2) AS gmv
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN members m ON m.customer_unique_id = c.customer_unique_id
JOIN member_levels ml ON ml.level_id = m.level_id
JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1, 2 ORDER BY gmv DESC;
```

**AI审查提示（需独立核实）**

- 生产要求先澄清；旧严格单轮评分将澄清计错。

**当前生产需要补充的信息**

- 交易指标按哪一方的地域归属统计大区：客户收货地、卖家所在地，还是仓库所在地？请明确来源。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 80. T5-003

用英文类目名统计退款金额，哪些类目退款最多？取前 15。

**原Gold（保持原样）**

```sql
SELECT t.product_category_name_english, ROUND(SUM(ri.refund_value), 2) AS refund_amt
FROM refunds r
JOIN refund_items ri ON ri.refund_id = r.refund_id
JOIN order_items oi ON oi.order_id = ri.order_id AND oi.order_item_id = ri.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1 ORDER BY refund_amt DESC LIMIT 15;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 81. T5-005

每个优惠券活动带来的有效订单数和商品销售额是多少？取前 10。

**原Gold（保持原样）**

```sql
SELECT ca.campaign_name, COUNT(DISTINCT cu.order_id) AS used_orders, ROUND(SUM(oi.price), 2) AS gmv
FROM coupon_usages cu
JOIN coupons cp ON cp.coupon_id = cu.coupon_id
JOIN campaigns ca ON ca.campaign_id = cp.campaign_id
JOIN orders o ON o.order_id = cu.order_id
JOIN order_items oi ON oi.order_id = cu.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY gmv DESC LIMIT 10;
```

**AI审查提示（需独立核实）**

- Gold输出名称；当前规则8默认输出现有标识。仅替换展示列后结果已核实不同。
- 优惠记录直连明细存在多对多隐患，但现库同活动同订单无重复用券；此隐患不计当前金额错误。名称投影另有确定冲突。

**诊断候选SQL（未签收；不进入原评分）**

```sql
SELECT ca.campaign_id, COUNT(DISTINCT cu.order_id) AS used_orders, ROUND(SUM(oi.price), 2) AS gmv
FROM coupon_usages cu
JOIN coupons cp ON cp.coupon_id = cu.coupon_id
JOIN campaigns ca ON ca.campaign_id = cp.campaign_id
JOIN orders o ON o.order_id = cu.order_id
JOIN order_items oi ON oi.order_id = cu.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY gmv DESC LIMIT 10
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 82. T5-007

有商品点击的搜索记录中，哪些关键词最常点到哪个英文类目的商品？取前 20。

**原Gold（保持原样）**

```sql
SELECT sl.keyword, t.product_category_name_english, COUNT(*) AS clicks
FROM search_logs sl
JOIN products p ON p.product_id = sl.clicked_product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
JOIN members m ON m.member_id = sl.member_id
JOIN member_levels ml ON ml.level_id = m.level_id
WHERE sl.clicked_product_id IS NOT NULL
GROUP BY 1, 2 ORDER BY clicks DESC LIMIT 20;
```

**AI审查提示（需独立核实）**

- Gold额外关联会员/等级；现库8651条点击均有匹配，未造成当前过滤损失，不判当前Gold错误。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 83. T5-009

库存低于安全库存的商品，按仓库、区域、品牌统计数量，列出前 20。

**原Gold（保持原样）**

```sql
SELECT w.warehouse_name, r.region_name, b.brand_name, COUNT(*) AS low_stock_items
FROM inventory i
JOIN warehouses w ON w.warehouse_id = i.warehouse_id
JOIN regions r ON r.region_code = w.region_code
JOIN product_brand_map pbm ON pbm.product_id = i.product_id AND pbm.is_current
JOIN brands b ON b.brand_id = pbm.brand_id
WHERE i.quantity < i.safety_stock
GROUP BY 1, 2, 3 ORDER BY low_stock_items DESC LIMIT 20;
```

**AI审查提示（需独立核实）**

- Gold输出名称；当前规则8默认输出现有标识。仅替换展示列后结果已核实不同。
- 截断线存在并列，Gold无二级排序，允许成员集合未冻结；不计入确定冲突。

**诊断候选SQL（未签收；不进入原评分）**

```sql
SELECT w.warehouse_id, r.region_code, b.brand_id, COUNT(*) AS low_stock_items
FROM inventory i
JOIN warehouses w ON w.warehouse_id = i.warehouse_id
JOIN regions r ON r.region_code = w.region_code
JOIN product_brand_map pbm ON pbm.product_id = i.product_id AND pbm.is_current
JOIN brands b ON b.brand_id = pbm.brand_id
WHERE i.quantity < i.safety_stock
GROUP BY 1, 2, 3 ORDER BY low_stock_items DESC LIMIT 20
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 84. T5-010

每个卖家结算明细覆盖了多少个英文商品类目、佣金合计多少？取佣金前 20。

**原Gold（保持原样）**

```sql
SELECT st.seller_id, COUNT(DISTINCT t.product_category_name_english) AS category_cnt,
       ROUND(SUM(si.commission_amount), 2) AS commission
FROM settlements st
JOIN settlement_items si ON si.settlement_id = st.settlement_id
JOIN order_items oi ON oi.order_id = si.order_id AND oi.order_item_id = si.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1 ORDER BY commission DESC LIMIT 20;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 85. T5-012

每类用户标签覆盖多少会员、这些会员订单的平均商品单价是多少？

**原Gold（保持原样）**

```sql
SELECT ut.tag_name, COUNT(DISTINCT m.member_id) AS members, ROUND(AVG(oi.price), 2) AS avg_item_price
FROM user_tag_map utm
JOIN user_tags ut ON ut.tag_id = utm.tag_id
JOIN members m ON m.member_id = utm.member_id
LEFT JOIN customers c ON c.customer_unique_id = m.customer_unique_id
LEFT JOIN orders o ON o.customer_id = c.customer_id
LEFT JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY members DESC;
```

**AI审查提示（需独立核实）**

- Gold输出名称；当前规则8默认输出现有标识。仅替换展示列后结果已核实不同。

**诊断候选SQL（未签收；不进入原评分）**

```sql
SELECT ut.tag_id, COUNT(DISTINCT m.member_id) AS members, ROUND(AVG(oi.price), 2) AS avg_item_price
FROM user_tag_map utm
JOIN user_tags ut ON ut.tag_id = utm.tag_id
JOIN members m ON m.member_id = utm.member_id
LEFT JOIN customers c ON c.customer_unique_id = m.customer_unique_id
LEFT JOIN orders o ON o.customer_id = c.customer_id
LEFT JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY 1 ORDER BY members DESC
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 86. T5-014

当前等级与最近一次会员快照等级不一致的会员有多少？按等级名称组合统计。

**原Gold（保持原样）**

```sql
SELECT ml_cur.level_name AS current_level, ml_snap.level_name AS snapshot_level,
       COUNT(DISTINCT ms.member_id) AS member_cnt
FROM member_snapshot ms
JOIN members m ON m.member_id = ms.member_id
JOIN member_levels ml_cur ON ml_cur.level_id = m.level_id
JOIN member_levels ml_snap ON ml_snap.level_id = ms.level_id
WHERE ms.level_id <> m.level_id
GROUP BY 1, 2 ORDER BY member_cnt DESC;
```

**AI审查提示（需独立核实）**

- Gold未挑最近快照；现库38471行=38471会员且日期无NULL，当前每人只有一份快照。缺少多快照反例，不能宣称实现可泛化。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 87. T5-016

按英文类目和退款原因统计退款笔数与退款金额，列出前 20。

**原Gold（保持原样）**

```sql
SELECT t.product_category_name_english, r.reason_code,
       COUNT(DISTINCT r.refund_id) AS refunds, ROUND(SUM(ri.refund_value), 2) AS amt
FROM refunds r
JOIN refund_items ri ON ri.refund_id = r.refund_id
JOIN order_items oi ON oi.order_id = ri.order_id AND oi.order_item_id = ri.order_item_id
JOIN products p ON p.product_id = oi.product_id
JOIN product_category_translation t ON t.product_category_name = p.product_category_name
GROUP BY 1, 2 ORDER BY amt DESC LIMIT 20;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 88. T5-018

默认收货地址所在州和客户登记州各组合的订单量对比，列出前 20。

**原Gold（保持原样）**

```sql
SELECT sa.state AS addr_state, c.customer_state, COUNT(DISTINCT o.order_id) AS orders
FROM shipping_addresses sa
JOIN customers c ON c.customer_id = sa.customer_id
JOIN orders o ON o.customer_id = c.customer_id
WHERE sa.is_default
GROUP BY 1, 2 ORDER BY orders DESC LIMIT 20;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 89. T6-002

有效订单（排除已取消和不可用）的订单数、下单人数、客单价分别是多少？

**原Gold（保持原样）**

```sql
SELECT COUNT(DISTINCT o.order_id) AS valid_orders,
       COUNT(DISTINCT c.customer_unique_id) AS buyers,
       ROUND(SUM(oi.price + oi.freight_value) / COUNT(DISTINCT o.order_id), 2) AS aov
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable');
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 90. T6-004

首单在 2017 年上半年的用户，在 2017 年下半年是否再次下单？按月留存率是多少？

**原Gold（保持原样）**

```sql
WITH first_order AS (
  SELECT c.customer_unique_id, MIN(o.order_purchase_timestamp) AS first_ts
  FROM orders o
  JOIN customers c ON c.customer_id = o.customer_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1
),
cohort AS (
  SELECT customer_unique_id, DATE_TRUNC('month', first_ts)::date AS cohort_month
  FROM first_order
  WHERE first_ts >= DATE '2017-01-01' AND first_ts < DATE '2017-07-01'
),
returner AS (
  SELECT DISTINCT co.customer_unique_id, co.cohort_month
  FROM cohort co
  JOIN customers c ON c.customer_unique_id = co.customer_unique_id
  JOIN orders o ON o.customer_id = c.customer_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
    AND o.order_purchase_timestamp >= DATE '2017-07-01'
    AND o.order_purchase_timestamp < DATE '2018-01-01'
)
SELECT co.cohort_month,
       ROUND(100.0 * COUNT(re.customer_unique_id) / COUNT(*), 2) AS retention_pct
FROM cohort co
LEFT JOIN returner re ON re.customer_unique_id = co.customer_unique_id
GROUP BY 1 ORDER BY 1;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 91. T6-006

已送达订单的准时率是多少？（实际送达日期不晚于预计送达日期）

**原Gold（保持原样）**

```sql
SELECT ROUND(100.0 * COUNT(*) FILTER (
         WHERE o.order_delivered_customer_date <= o.order_estimated_delivery_date
       ) / COUNT(*), 2) AS on_time_pct
FROM orders o
WHERE o.order_delivered_customer_date IS NOT NULL;
```

**AI审查提示（需独立核实）**

- 按送达时间非空确定已发生送达事件，与当前履约指标一致；不以状态字段强行替代。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 92. T6-008

商品动销率是多少？（有销量的商品数 / 全部商品数）

**原Gold（保持原样）**

```sql
WITH sold AS (
  SELECT COUNT(DISTINCT oi.product_id) AS n
  FROM order_items oi
  JOIN orders o ON o.order_id = oi.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
),
allp AS (SELECT COUNT(*) AS n FROM products)
SELECT ROUND(100.0 * s.n / a.n, 2) AS sell_through_pct
FROM sold s CROSS JOIN allp a;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 93. T6-011

运费占 GMV 的比例是多少？按宏观区域拆解。

**原Gold（保持原样）**

```sql
SELECT r.macro_region,
       ROUND(100.0 * SUM(oi.freight_value) / SUM(oi.price + oi.freight_value), 2) AS freight_pct
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
JOIN regions r ON r.region_code = c.customer_state
JOIN order_items oi ON oi.order_id = o.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable')
GROUP BY 1 ORDER BY freight_pct DESC;
```

**AI审查提示（需独立核实）**

- 生产要求先澄清；旧严格单轮评分将澄清计错。

**当前生产需要补充的信息**

- 交易指标按哪一方的地域归属统计大区：客户收货地、卖家所在地，还是仓库所在地？请明确来源。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 94. T6-013

优惠券带来的 GMV 和优惠金额分别是多少？用券订单的折扣率是多少？

**原Gold（保持原样）**

```sql
SELECT ROUND(SUM(oi.price + oi.freight_value), 2) AS gmv,
       ROUND(SUM(cu.discount_amount), 2) AS discount,
       ROUND(100.0 * SUM(cu.discount_amount) / SUM(oi.price + oi.freight_value), 2) AS discount_pct
FROM coupon_usages cu
JOIN orders o ON o.order_id = cu.order_id
JOIN order_items oi ON oi.order_id = cu.order_id
WHERE o.order_status NOT IN ('canceled', 'unavailable');
```

**AI审查提示（需独立核实）**

- Gold把订单优惠重复到商品明细；按订单分别聚合优惠和商品金额后实际不同。

**诊断候选SQL（未签收；不进入原评分）**

```sql
WITH ct AS(SELECT order_id,SUM(discount_amount) AS discount FROM coupon_usages GROUP BY 1),
    ot AS(SELECT order_id,SUM(price+freight_value) AS gmv FROM order_items GROUP BY 1)
    SELECT ROUND(SUM(ot.gmv),2) AS gmv,ROUND(SUM(ct.discount),2) AS discount,
           ROUND(100.0*SUM(ct.discount)/NULLIF(SUM(ot.gmv),0),2) AS discount_pct
    FROM ct JOIN orders o USING(order_id) JOIN ot USING(order_id)
    WHERE o.order_status NOT IN ('canceled','unavailable')
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 95. T6-015

各类工单的 SLA 达成率是多少？（解决时间不超过类目 SLA 小时数）

**原Gold（保持原样）**

```sql
SELECT tc.category_name,
       ROUND(100.0 * COUNT(*) FILTER (
         WHERE st.resolved_at IS NOT NULL
           AND EXTRACT(EPOCH FROM (st.resolved_at - st.created_at)) / 3600.0 <= tc.sla_hours
       ) / COUNT(*), 2) AS sla_pct
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
GROUP BY 1 ORDER BY sla_pct;
```

**AI审查提示（需独立核实）**

- Gold输出名称；当前规则8默认输出现有标识。仅替换展示列后结果已核实不同。

**诊断候选SQL（未签收；不进入原评分）**

```sql
SELECT tc.category_id,
       ROUND(100.0 * COUNT(*) FILTER (
         WHERE st.resolved_at IS NOT NULL
           AND EXTRACT(EPOCH FROM (st.resolved_at - st.created_at)) / 3600.0 <= tc.sla_hours
       ) / COUNT(*), 2) AS sla_pct
FROM support_tickets st
JOIN ticket_categories tc ON tc.category_id = st.category_id
GROUP BY 1 ORDER BY sla_pct
```

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 96. T6-017

推荐活动的转化率和人均奖励是多少？

**原Gold（保持原样）**

```sql
SELECT ROUND(100.0 * COUNT(*) FILTER (WHERE converted_at IS NOT NULL) / COUNT(*), 2) AS conversion_pct,
       ROUND(SUM(reward_amount) / COUNT(*) FILTER (WHERE converted_at IS NOT NULL), 2) AS reward_per_converted
FROM referrals;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 97. T6-019

各州的平均配送时长（下单到签收）是多少天？列出配送最慢的 10 个州。

**原Gold（保持原样）**

```sql
SELECT c.customer_state,
       ROUND(AVG(EXTRACT(EPOCH FROM (o.order_delivered_customer_date - o.order_purchase_timestamp)) / 86400.0), 2) AS avg_days
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
WHERE o.order_delivered_customer_date IS NOT NULL
GROUP BY 1 ORDER BY avg_days DESC LIMIT 10;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 98. T6-020

平台整体佣金率和各卖家佣金率分布（最低、平均、最高）分别是多少？

**原Gold（保持原样）**

```sql
WITH seller_rate AS (
  SELECT st.seller_id,
         SUM(si.gross_amount) AS gross,
         SUM(si.commission_amount) AS commission,
         CASE WHEN SUM(si.gross_amount) > 0
              THEN SUM(si.commission_amount) / SUM(si.gross_amount) ELSE 0 END AS rate
  FROM settlements st
  JOIN settlement_items si ON si.settlement_id = st.settlement_id
  GROUP BY 1
)
SELECT ROUND(SUM(commission) / SUM(gross), 4) AS platform_rate,
       ROUND(MIN(rate), 4) AS min_seller_rate,
       ROUND(AVG(rate), 4) AS avg_seller_rate,
       ROUND(MAX(rate), 4) AS max_seller_rate
FROM seller_rate;
```

**AI审查提示（需独立核实）**

未标出已证实冲突；仍须审阅，不等于自动认可。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 99. T6-022

2017 年和 2018 年各月 GMV 分别是多少？并计算 2018 年相对 2017 年同月的同比增长率。

**原Gold（保持原样）**

```sql
WITH monthly AS (
  SELECT EXTRACT(YEAR FROM o.order_purchase_timestamp)::int AS yr,
         EXTRACT(MONTH FROM o.order_purchase_timestamp)::int AS mo,
         SUM(oi.price + oi.freight_value) AS gmv
  FROM orders o
  JOIN order_items oi ON oi.order_id = o.order_id
  WHERE o.order_status NOT IN ('canceled', 'unavailable')
  GROUP BY 1, 2
)
SELECT m18.mo,
       ROUND(m17.gmv, 2) AS gmv_2017,
       ROUND(m18.gmv, 2) AS gmv_2018,
       ROUND(100.0 * (m18.gmv - m17.gmv) / m17.gmv, 2) AS yoy_pct
FROM monthly m18
JOIN monthly m17 ON m17.mo = m18.mo AND m17.yr = 2017
WHERE m18.yr = 2018
ORDER BY m18.mo;
```

**AI审查提示（需独立核实）**

- 题目显式要求两年GMV及同比，Gold原始两期金额是所需输出，不是多余列。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。

## 100. T6-024

退款原因与客户评分的关系：各退款原因对应的平均评分和低分率是多少？

**原Gold（保持原样）**

```sql
SELECT r.reason_code,
       ROUND(AVG(rev.review_score), 2) AS avg_score,
       ROUND(100.0 * COUNT(*) FILTER (WHERE rev.review_score <= 2) / COUNT(*), 2) AS low_score_pct
FROM refunds r
JOIN orders o ON o.order_id = r.order_id
JOIN order_reviews rev ON rev.order_id = r.order_id
JOIN customers c ON c.customer_id = o.customer_id
GROUP BY 1 ORDER BY low_score_pct DESC;
```

**AI审查提示（需独立核实）**

- 退款事实集合中的评价分母有已登记指标例外，不额外加全站有效订单过滤。

**外部裁决：** 未填写。
**理由/证据/明确业务口径：** 未填写。
**复核人/日期：** 未填写。
