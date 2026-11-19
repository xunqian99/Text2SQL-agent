"""Author a separate reviewed-reference candidate; never edit v2 or model output."""
from pathlib import Path
import hashlib
import json
import sys
import yaml

ROOT = Path(__file__).resolve().parents[2]
FOLDER = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'scripts'))
from eval_lib import load_eval_items

read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
reviews = {}
for name in ('basic_time', 'joins', 'deep_business'):
    for item in read(ROOT / f'reports/independent-contract-review-20261119/{name}-review.json')['items']:
        reviews[item['id']] = item
original = [i for i in load_eval_items() if i['id'] in reviews]
assert len(original) == 100

# These decisions resolve the wording in a new version. They do not redefine past requests.
questions = {
 'T1-016': '仅考虑分期数已知的支付，找出分期数最多的一笔；并列时按订单编号、支付顺序号升序取第一笔，输出订单编号、支付顺序号和分期期数。',
 'T2-015': '按现有会员记录里实际出现的会员等级编号分组，每个等级的平均已知积分是多少？',
 'T2-024': '有库存记录的每个仓库编号，各有多少条仓库×商品库存记录？',
 'T2-026': '有库存记录的每个仓库编号，其库存数量quantity合计是多少？',
 'T2-033': '全部卖家结算单的应结净额合计是多少？不按打款状态或打款日期筛选。',
 'T2-037': '按订单关联客户档案所在州统计订单数，超过5000笔的州有哪些？输出州代码和订单数。',
 'T3-009': '2018年实际有订单的各个月份，订单量是多少？按月份排序。',
 'T3-010': '从首笔到末笔订单所在月的连续月份，每月订单量及比上一个自然月增加的笔数是多少？区间内无订单月份计0，首月无基期的增加值为NULL。',
 'T3-012': '2018年有订单且2017年同月也有订单的各个月份，订单量相比2017年同月增加多少笔？只输出月份编号和增加笔数。',
 'T3-014': '以全部订单最大下单日期为参考日，包含参考日的最后90个自然日内，每周的商品销售额（不含运费）分别是多少？按周起始日期排序。',
 'T4-002': '每个客户所在州的商品销售额（不含运费）是多少？',
 'T4-004': '按英文类目名称汇总商品销售额（不含运费），哪个类目最高？原始类目为空不参与分类；有原始类目但缺翻译的金额保留在NULL名称组。',
 'T4-006': '每个卖家所在州的商品销售额合计是多少？不含运费。',
 'T4-013': '至少有一条1分或2分评价的不同订单里，按订单关联客户档案所在州各有多少笔？',
 'T4-015': '按订单关联客户档案所在州所属的大区，订单数量分别是多少？',
 'T4-019': '每个用户标签编号各覆盖多少个会员？包括零会员标签。',
 'T4-024': '按仓库名称汇总负责的发货单数量；包括零发货仓库，未分配仓库的发货单单列NULL名称组。',
 'T4-028': '每个工单分类编号的平均实际处理时长是多少小时？包括没有可计时时长的分类，其平均值为NULL。',
 'T4-035': '客单价最高的10个自然人客户是谁？客单价按有效订单商品金额加运费总和除以全部有效订单数，没有商品明细的有效订单仍计入分母；不展示无法计算金额的客户。',
 'T4-042': '发生过价格调整的商品里，有多少个商品在历史上至少有一次新价高于旧价？',
 'T4-044': '每个客户所在大区有多少条客户档案记录？按大区内去重自然人计算，平均每人下多少单？',
 'T5-001': '按订单关联客户档案所在州所属宏观区域，各会员等级（按等级名称汇总）的商品销售额分别是多少？按销售额从高到低排序。',
 'T5-005': '按活动编号统计每个优惠券活动带来的有效订单数和商品销售额，按商品销售额降序取前10；没有有效用券订单的活动也保留。',
 'T5-007': '有商品点击的搜索记录中，按关键词和英文类目名称汇总，点击次数最多的20组是什么？原始类目为空不参与分类；有原始类目但缺翻译的点击保留在NULL名称组。不要求点击者有会员等级。',
 'T5-009': '库存低于安全库存时，按仓库编号、仓库州代码及当前品牌编号统计仓库×商品库存行数，缺当前品牌归入NULL品牌组；有多个当前品牌的商品分别计入各品牌组。按行数降序取最多20组，并列时按这三个分组字段升序、NULL排最后。',
 'T5-012': '每个用户标签编号覆盖多少会员、这些会员订单的平均商品单价是多少？会员数包括无订单会员，平均值只用已知商品明细价格。',
 'T5-014': '将每个会员最近一次日期的快照等级与当前等级比较，快照同日多条时取快照编号最大一条，日期未知不参与最新判断。按当前等级名称和快照等级名称组合汇总不一致会员数；任一等级未知或无快照不判为已确认不一致。',
 'T6-011': '按订单关联客户档案所在州所属宏观区域，运费占含运费GMV的百分比是多少？仅统计有效订单。',
 'T6-015': '按工单分类编号，各类工单的SLA达成百分比是多少？解决时间不超过类目SLA小时数计达成，未解决工单仍在分母。',
 'T6-020': '平台整体佣金率和各卖家佣金率分布（最低、等权平均、最高）分别是多少？输出四位小数比率；分母为0的卖家率未知，不补0参与平均。',
 'T6-022': '按1至12月展示2017年和2018年每月GMV，以及2018相对2017同月的同比增长百分比；某年该月没有金额数据时为NULL，同比不可计算时为NULL，不填0。',
 'T6-024': '按退款原因，统计有该原因退款订单对应评价记录的平均评分和低分百分比；同一原因同一订单多笔退款不得重复加权评价，每条评价按完整记录身份保留。',
}
questions.update({
 'T3-001':'仅对实际有订单的月份，订单数量分别是多少？按月份排序。',
 'T3-007':'仅对实际有关联订单明细的下单月份，订单明细运费总额分别是多少？',
 'T3-021':'仅对实际有退款申请的月份，退款单数量分别是多少？',
 'T3-023':'仅对实际新建客服工单的月份，新增工单数量分别是多少？',
 'T3-029':'仅对实际有结算单的账期起始月份，佣金收入分别是多少？',
 'T4-011':'对实际订单中出现的每种订单状态，平均评价分是多少？没有可用评价的状态平均分为NULL。',
 'T4-017':'仅对现有订单关联会员中实际出现的会员等级编号，订单数量分别是多少？',
 'T5-005':'所有活动通过优惠券带来的有效订单数和商品销售额是多少？按活动编号输出、按商品销售额降序取最多10个；没有有效用券订单的活动也保留。',
 'T5-016':'按英文类目和退款原因统计退款笔数与退款金额，按已知退款金额最高列出最多20组；原始类目为空不参与分类，缺英文翻译保留NULL名称组。',
})

sql = {i['id']: reviews[i['id']]['proposedGoldSql'] for i in original
       if reviews[i['id']].get('proposedGoldSql')}
sql.update({
 'T1-016': '''SELECT order_id,payment_sequential,payment_installments
FROM order_payments WHERE payment_installments IS NOT NULL
ORDER BY payment_installments DESC,order_id,payment_sequential LIMIT 1;''',
 'T4-004': '''SELECT t.product_category_name_english,ROUND(SUM(oi.price),2) AS sales
FROM order_items oi JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english
ORDER BY sales DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 1;''',
 'T5-003': '''SELECT t.product_category_name_english,ROUND(SUM(ri.refund_value),2) AS refund_amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english
ORDER BY refund_amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST LIMIT 15;''',
 'T5-009': '''SELECT w.warehouse_id,w.region_code,pbm.brand_id,COUNT(*) AS low_stock_items
FROM inventory i JOIN warehouses w ON w.warehouse_id=i.warehouse_id
LEFT JOIN product_brand_map pbm ON pbm.product_id=i.product_id AND pbm.is_current
WHERE i.quantity<i.safety_stock
GROUP BY w.warehouse_id,w.region_code,pbm.brand_id
ORDER BY low_stock_items DESC,w.warehouse_id,w.region_code,pbm.brand_id ASC NULLS LAST LIMIT 20;''',
 'T5-014': '''WITH ranked AS (
 SELECT ms.*,ROW_NUMBER() OVER(PARTITION BY member_id ORDER BY snapshot_date DESC,snapshot_id DESC) AS rn
 FROM member_snapshot ms WHERE snapshot_date IS NOT NULL
)
SELECT ml_cur.level_name AS current_level,ml_snap.level_name AS snapshot_level,
 COUNT(DISTINCT m.member_id) AS member_cnt
FROM ranked ms
JOIN members m ON m.member_id=ms.member_id
LEFT JOIN member_levels ml_cur ON ml_cur.level_id=m.level_id
LEFT JOIN member_levels ml_snap ON ml_snap.level_id=ms.level_id
WHERE ms.rn=1 AND ms.level_id<>m.level_id
GROUP BY ml_cur.level_name,ml_snap.level_name ORDER BY member_cnt DESC;''',
 'T5-016': '''SELECT t.product_category_name_english,r.reason_code,
 COUNT(DISTINCT r.refund_id) AS refunds,ROUND(SUM(ri.refund_value),2) AS amt
FROM refunds r JOIN refund_items ri ON ri.refund_id=r.refund_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
LEFT JOIN product_category_translation t ON t.product_category_name=p.product_category_name
WHERE p.product_category_name IS NOT NULL
GROUP BY t.product_category_name_english,r.reason_code
ORDER BY amt DESC NULLS LAST,t.product_category_name_english ASC NULLS LAST,r.reason_code ASC NULLS LAST LIMIT 20;''',
 'T6-020': '''WITH seller_rate AS (
 SELECT st.seller_id,SUM(si.gross_amount) AS gross,SUM(si.commission_amount) AS commission,
 SUM(si.commission_amount)/NULLIF(SUM(si.gross_amount),0) AS rate
 FROM settlements st JOIN settlement_items si ON si.settlement_id=st.settlement_id GROUP BY st.seller_id
)
SELECT ROUND(SUM(commission)/NULLIF(SUM(gross),0),4) AS platform_rate,
 ROUND(MIN(rate),4) AS min_seller_rate,ROUND(AVG(rate),4) AS avg_seller_rate,
 ROUND(MAX(rate),4) AS max_seller_rate FROM seller_rate;''',
 'T6-024': '''WITH reason_orders AS (SELECT DISTINCT reason_code,order_id FROM refunds)
SELECT r.reason_code,ROUND(AVG(rev.review_score),2) AS avg_score,
 ROUND(100.0*COUNT(*) FILTER(WHERE rev.review_score<=2)/NULLIF(COUNT(*),0),2) AS low_score_pct
FROM reason_orders r JOIN order_reviews rev ON rev.order_id=r.order_id
GROUP BY r.reason_code ORDER BY low_score_pct DESC;''',
})
sql.update({
 'T4-011':'''SELECT o.order_status,ROUND(AVG(r.review_score),2) AS avg_score
FROM orders o LEFT JOIN order_reviews r ON r.order_id=o.order_id
GROUP BY o.order_status ORDER BY avg_score DESC NULLS LAST;''',
 'T4-015':'''SELECT r.macro_region,COUNT(*) AS orders
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
GROUP BY r.macro_region ORDER BY orders DESC;''',
 'T4-031':'''SELECT s.seller_state,ROUND(SUM(st.net_amount),2) AS net
FROM settlements st LEFT JOIN sellers s ON s.seller_id=st.seller_id
GROUP BY s.seller_state ORDER BY net DESC NULLS LAST;''',
 'T5-001':'''SELECT r.macro_region,ml.level_name,ROUND(SUM(oi.price),2) AS sales
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
JOIN members m ON m.customer_unique_id=c.customer_unique_id
LEFT JOIN member_levels ml ON ml.level_id=m.level_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY r.macro_region,ml.level_name ORDER BY sales DESC NULLS LAST;''',
 'T6-006':'''SELECT ROUND(100.0*COUNT(*) FILTER(
 WHERE order_delivered_customer_date<=order_estimated_delivery_date
)/NULLIF(COUNT(*),0),2) AS on_time_pct
FROM orders WHERE order_delivered_customer_date IS NOT NULL;''',
 'T6-008':'''WITH sold AS (
 SELECT COUNT(DISTINCT oi.product_id) AS n FROM order_items oi
 JOIN orders o ON o.order_id=oi.order_id WHERE o.order_status NOT IN ('canceled','unavailable')
), allp AS(SELECT COUNT(*) AS n FROM products)
SELECT ROUND(100.0*s.n/NULLIF(a.n,0),2) AS sell_through_pct FROM sold s CROSS JOIN allp a;''',
 'T6-011':'''SELECT r.macro_region,ROUND(100.0*SUM(oi.freight_value)/
 NULLIF(SUM(oi.price+oi.freight_value),0),2) AS freight_pct
FROM orders o JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
JOIN order_items oi ON oi.order_id=o.order_id
WHERE o.order_status NOT IN ('canceled','unavailable')
GROUP BY r.macro_region ORDER BY freight_pct DESC NULLS LAST;''',
 'T6-017':'''SELECT ROUND(100.0*COUNT(*) FILTER(WHERE converted_at IS NOT NULL)/NULLIF(COUNT(*),0),2) AS conversion_pct,
 ROUND(SUM(reward_amount)/NULLIF(COUNT(*) FILTER(WHERE converted_at IS NOT NULL),0),2) AS reward_per_converted
FROM referrals;''',
 'T6-019':'''WITH state_times AS (
 SELECT c.customer_state,ROUND(AVG(EXTRACT(EPOCH FROM(
 o.order_delivered_customer_date-o.order_purchase_timestamp))/86400.0),2) AS avg_days
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 WHERE o.order_delivered_customer_date IS NOT NULL GROUP BY c.customer_state
)
SELECT customer_state,avg_days FROM state_times WHERE avg_days IS NOT NULL
ORDER BY avg_days DESC,customer_state ASC NULLS LAST LIMIT 10;''',
})
sql['T5-009']=sql['T5-009'].replace('w.warehouse_id','i.warehouse_id').replace(
 'inventory i JOIN warehouses w ON i.warehouse_id=i.warehouse_id',
 'inventory i LEFT JOIN warehouses w ON w.warehouse_id=i.warehouse_id')
assert 'LEFT JOIN warehouses w ON w.warehouse_id=i.warehouse_id' in sql['T5-009']
sql['T5-014']=sql['T5-014'].replace('WHERE ms.rn=1 AND ms.level_id<>m.level_id',
 'WHERE ms.rn=1 AND ml_cur.level_id IS NOT NULL AND ml_snap.level_id IS NOT NULL AND ms.level_id<>m.level_id')
sql['T5-003']=sql['T5-003'].replace('GROUP BY t.product_category_name_english\n',
 'GROUP BY t.product_category_name_english HAVING SUM(ri.refund_value) IS NOT NULL\n')
sql['T5-010']=sql['T5-010'].replace('GROUP BY st.seller_id ORDER BY',
 'GROUP BY st.seller_id HAVING SUM(si.commission_amount) IS NOT NULL ORDER BY')
sql['T5-016']=sql['T5-016'].replace('GROUP BY t.product_category_name_english,r.reason_code\n',
 'GROUP BY t.product_category_name_english,r.reason_code HAVING SUM(ri.refund_value) IS NOT NULL\n')
# Explicitly distinguish maximum-value candidate membership from general unknown rows.
questions['T5-003']='用英文类目名称汇总退款金额，按已知退款金额最高列出最多15组；原始类目为空不参与分类，缺翻译保留NULL名称组。'
questions['T5-010']='每个有已知佣金金额的卖家，其结算明细覆盖多少个英文商品类目、佣金合计多少？取佣金最高的最多20个。'

# A secondary key cannot change the primary metric order; use full group/record identities.
tie_replacements = {
 'T1-012': ('ORDER BY price DESC', 'ORDER BY price DESC NULLS LAST,order_id,order_item_id'),
 'T2-008': ('ORDER BY cnt DESC', 'ORDER BY cnt DESC,customer_state ASC NULLS LAST'),
 'T2-011': ('ORDER BY total DESC', 'ORDER BY total DESC NULLS LAST,seller_id'),
 'T2-035': ('ORDER BY cnt DESC', 'ORDER BY cnt DESC,keyword ASC NULLS LAST'),
 'T3-005': ('ORDER BY cnt DESC', 'ORDER BY cnt DESC,week_start ASC NULLS LAST'),
 'T3-016': ('ORDER BY cnt DESC', 'ORDER BY cnt DESC,dow ASC NULLS LAST'),
 'T4-037': ('ORDER BY sold DESC', 'ORDER BY sold DESC NULLS LAST,p.product_id'),
 'T5-005': ('ORDER BY gmv DESC NULLS LAST', 'ORDER BY gmv DESC NULLS LAST,ca.campaign_id'),
 'T5-007': ('ORDER BY clicks DESC', 'ORDER BY clicks DESC,sl.keyword ASC NULLS LAST,t.product_category_name_english ASC NULLS LAST'),
 'T5-010': ('ORDER BY commission DESC', 'ORDER BY commission DESC NULLS LAST,st.seller_id'),
 'T5-018': ('ORDER BY orders DESC', 'ORDER BY orders DESC,1 ASC NULLS LAST,2 ASC NULLS LAST'),
}

out = ROOT / 'data/benchmarks/olist-v3-candidate'
assert not out.exists(), 'Never overwrite a candidate; review revisions must be explicit'
out.mkdir(parents=True)
diffs = []
by_file = {}
for old in original:
    identity = old['id']
    item = {k: old[k] for k in ('id','difficulty','tags','question','gold_sql','notes') if k in old}
    item['question'] = questions.get(identity, old['question'])
    item['gold_sql'] = sql.get(identity, old['gold_sql']).strip()
    if identity in tie_replacements:
        a,b = tie_replacements[identity]
        assert a in item['gold_sql'], (identity,a,item['gold_sql'])
        item['gold_sql'] = item['gold_sql'].replace(a,b)
    if identity in ('T5-014',):
        item['notes'] = 'member_snapshot主键是snapshot_id，不保证同会员同日期唯一；新版显式规定同日按snapshot_id降序选择。名称明确按名称组合汇总。'
    by_file.setdefault(old['_file'], []).append(item)
    diffs.append(dict(id=identity,originalFile=old['_file'],independentDecision=reviews[identity]['decision'],
        questionChanged=item['question']!=old['question'],goldChanged=item['gold_sql'].strip()!=old['gold_sql'].strip(),
        oldQuestion=old['question'],newQuestion=item['question'],oldGoldSql=old['gold_sql'],newGoldSql=item['gold_sql'],
        reason=reviews[identity]['reason'] if reviews[identity]['decision']!='KEEP' else
              ('稳定并列裁决；保留原主指标排序，不改题目范围。' if identity in tie_replacements else '保持原参考。')))
for name, items in by_file.items():
    (out/name).write_text(yaml.safe_dump(items, allow_unicode=True, sort_keys=False, width=120),encoding='utf-8',newline='\n')
lineage=dict(format='reviewed-reference-candidate-v3',purpose='Reviewed revision of seen formal100; not blind',
    author='root',independentReviewComplete=False,originalCount=100,candidateCount=100,
    removedIds=[],addedIds=[],providerCalls=0,thresholdPercent=89,relativeRegressionLimitPp=2,
    questionsChanged=sum(x['questionChanged'] for x in diffs),goldSqlChanged=sum(x['goldChanged'] for x in diffs),
    sourceFilesSha256={str(p.relative_to(ROOT)).replace('\\','/'):sha(p) for p in sorted((ROOT/'data/eval').glob('T*.yaml'))},
    candidateFilesSha256={p.name:sha(p) for p in sorted(out.glob('T*.yaml'))},items=diffs,
    limits=['These questions were already used for tuning; review is not blind evaluation.',
            'Cross-reference score changes are not model improvement.',
            'Original labels are retained for lineage; SQL table count may change after reference repair.',
            'This is not a frozen or paid-authorized evaluation protocol.'])
(FOLDER/'candidate-lineage.json').write_text(json.dumps(lineage,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({k:lineage[k] for k in ('originalCount','candidateCount','questionsChanged','goldSqlChanged','independentReviewComplete')}))
