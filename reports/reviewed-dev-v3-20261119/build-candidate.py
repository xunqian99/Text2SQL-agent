"""Build a separate, reviewable dev reference; never changes original data or production."""
from pathlib import Path
import hashlib
import json
import re
import yaml

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
DEST = ROOT / 'data/benchmarks/olist-v3-dev-candidate'
if (DEST / 'reference-seal.json').exists():
    raise SystemExit('Refusing to overwrite a frozen reference')
review = json.loads((HERE / 'reviewer-review.json').read_text(encoding='utf-8'))
reviews = {i['id']: i for i in review['items']}

# These are explicit definitions of new benchmark requests, not assertions of user approval.
questions = {
    i['id']: i['proposedQuestion'] for i in review['items'] if i.get('proposedQuestion')
}
questions.update({
    'T1-020': '带文字标题的评价有多少条？标题去首尾空格后为空的不算。',
    'T1-026': '积分最高的10个会员是谁？只考虑已知积分，输出会员ID和积分，同分按会员ID升序严格取10人。',
    'T1-028': '已知容量最大的仓库是哪一个？输出仓库ID和容量，并列按仓库ID升序取1个。',
    'T3-011': '从最早到最晚下单月份逐自然月输出订单量环比增长率（小数四位），仅输出月份和增长率；缺月订单量未知，当前月或上个自然月未知、或基期为0时增长率为NULL。',
    'T3-013': '以库内最大下单日期为参考日，含参考日在内最近90个自然日的订单有多少笔？',
    'T3-006': '按有商品明细的下单月份，统计该月全部状态订单明细的不含运费商品金额合计两位，仅列有明细的月份，不补缺月。',
    'T4-001': '按自然人统计订单数，输出订单最多的10人的自然人ID与订单数，同数按自然人ID升序。',
    'T4-009': '按支付方式原值分组，统计每条支付记录等权的平均支付金额，未知方式保留为NULL组，金额两位。',
    'T4-010': '按有商品明细的订单状态统计商品金额合计两位，不含运费，不补无明细的状态，不额外排除取消或不可用状态。',
    'T4-012': '按订单客户档案所在州统计每条评价等权的平均分两位，按评价记录数取前10州，仅输出州和平均分，同数按州升序。',
    'T4-014': '每种支付方式的评价平均分是多少（两位）？每条评价在其订单使用的每种支付方式下各计一次，同单同方式多次支付不重复加权评价。',
    'T4-018': '按当前会员等级ID统计这些会员全部订单状态下的商品金额合计，不含运费或支付金额，输出等级ID和金额两位。',
    'T4-021': '按活动ID统计其关联优惠券的使用记录数，仅列有使用记录的活动，输出活动ID与次数。',
    'T4-025': '按仓库所在宏观大区统计全部仓库的已知容量合计，无法确定大区的仓库保留NULL组。',
    'T4-027': '按库存记录中出现的仓库ID，统计库存数量总和及每条仓库商品库存记录等权的平均库存量两位；输出仓库ID和两项数量。',
    'T4-029': '按工单关联订单的客户档案所在州统计工单数；全部工单都参与，无法确定州的保留NULL组。',
    'T4-034': '全部订单中有过任意退款记录的订单占比是多少？不排订单状态，按订单去重，输出0到1小数四位，分母为0时NULL。',
    'T4-043': '按发货状态统计每张发货单等权的平均物流轨迹记录数两位；包括0条轨迹的发货单。',
    'T4-045': '按非NULL类目统计已知商品重量平均值两位；按类目全部商品数取前10，同数按类目原值升序，仅输出类目和平均重量。',
    'T5-002': '按当前会员等级ID统计其会员订单明细商品金额，不含运费，包含全部订单状态；输出等级ID和金额两位。',
    'T5-004': '按发货仓库的区域编码统计发货单量和不同订单数，输出区域编码及两项计数，无法确定区域的发货单保留NULL组。',
    'T5-006': '物流延迟类工单按关联订单客户所在州、工单会员的当前等级ID组合统计工单数；未知属性保留NULL，按工单数取20，同数按州和等级ID升序NULL最后。',
    'T5-008': '按推荐人和被推荐人的当前会员等级ID组合，统计全部推荐记录数和奖励金额两位；未知等级保留NULL，输出两个等级ID和两项指标。',
    'T5-011': '只统计商品当前is_current=true已关联品牌的全部订单明细，按品牌ID汇总不含运费商品金额两位，输出前10品牌ID和金额；同金额按品牌ID升序，严格最多10。',
    'T5-015': '全部物流轨迹按事件代码和订单客户档案所在州分组，统计轨迹中不同发货单ID数；不要求订单或明细存在，未知州保留NULL，按发货单数取20，同数按事件代码和州升序。',
    'T5-017': '按工单category_id、priority组合统计全部工单数和消息条数，包含零消息工单及未知属性，不限制会员资格；输出两个维度和两项计数。',
    'T5-020': '按流水所属仓库映射的宏观区域及出入库类型，统计全部库存变动记录数和数量合计；不要求当前库存仍有记录，未知大区保留NULL。',
    'T6-012': '2018年按自然月统计有效下单自然人数（MAU），输出全部12个月；无事实月份覆盖未知，人数保留NULL。',
    'T6-009': '按订单客户档案所在州统计有效订单评价记录中1到2星评价占比百分数两位，每条评价等权；按比例取前10，同值按州升序，仅输出州和比例。',
    'T6-016': '按当前品牌ID统计商品总数、有效订单中动销商品数、不含运费商品销售额两位和动销率百分数两位；仅在销售额已知的品牌中取前10，同额按品牌ID升序，严格最多10。',
    'T3-028': '以会员表最大最近登录日期为参考日，含参考日在内最近30个自然日登录过的会员有多少人？',
    'T6-023': '按仓库ID列出全部仓库当前库存量、最近90个自然日出库数量和库存周转天数一位；参考日取全部库存流水最大日期且含当日，无出库记0，未有参考日期时未知；周转天数=90*当前库存/出库量，出库为0时NULL。',
    'T6-025': '2018年逐自然月输出有效订单含运费GMV两位，按自然人全部历史首次有效下单月区分新老客；输出月份、新客GMV、老客GMV全部12个月，缺金额事实为NULL。',
})

sql = {}
sql['T4-025'] = '''SELECT r.macro_region,SUM(w.capacity) AS total_capacity
FROM warehouses w LEFT JOIN regions r ON r.region_code=w.region_code
GROUP BY r.macro_region ORDER BY total_capacity DESC NULLS LAST,r.macro_region ASC NULLS LAST;'''
sql['T5-015'] = '''SELECT st.event_code,c.customer_state,COUNT(DISTINCT st.shipment_id) AS shipments
FROM shipment_tracking st LEFT JOIN shipments s ON s.shipment_id=st.shipment_id
LEFT JOIN orders o ON o.order_id=s.order_id LEFT JOIN customers c ON c.customer_id=o.customer_id
GROUP BY st.event_code,c.customer_state
ORDER BY shipments DESC,st.event_code ASC NULLS LAST,c.customer_state ASC NULLS LAST LIMIT 20;'''
turnover = reviews['T6-023']['proposedGoldSql']
sql['T6-023'] = turnover.replace('SUM(im.quantity) AS out_qty', 'SUM(im.quantity) AS out_qty,COUNT(*) AS records').replace(
    'ELSE COALESCE(o.out_qty,0) END', 'WHEN o.records IS NULL THEN 0 ELSE o.out_qty END')
assert 'WHEN o.records IS NULL THEN 0' in sql['T6-023']

stable = {
    'T1-013': ('ORDER BY freight_value DESC', 'ORDER BY freight_value DESC,order_id ASC,order_item_id ASC'),
    'T2-009': ('ORDER BY cnt DESC', 'ORDER BY cnt DESC,product_category_name ASC'),
    'T2-012': ('ORDER BY total DESC', 'ORDER BY total DESC,seller_id ASC'),
    'T3-015': ('ORDER BY cnt DESC', 'ORDER BY cnt DESC,hr ASC NULLS LAST'),
    'T4-005': ('ORDER BY order_cnt DESC', 'ORDER BY order_cnt DESC,oi.seller_id ASC'),
    'T4-030': ('GROUP BY 1 ORDER BY total DESC', 'GROUP BY 1 HAVING SUM(total_amount) IS NOT NULL ORDER BY total DESC,seller_id ASC'),
    'T6-009': ('ORDER BY low_pct DESC', 'ORDER BY low_pct DESC,c.customer_state ASC NULLS LAST'),
}
for ident,(before,after) in stable.items():
    sql[ident] = reviews[ident]['goldSql'].replace(before,after)
    assert sql[ident] != reviews[ident]['goldSql'], ident
questions['T4-030'] = '按卖家ID统计已知结算总额两位，取已知总额最高的最多10个，仅输出卖家ID和金额；同额按卖家ID升序。'

sql.update({
    'T4-016': '''SELECT r.macro_region,ROUND(SUM(oi.price),2) AS sales
FROM order_items oi JOIN orders o ON o.order_id=oi.order_id
LEFT JOIN customers c ON c.customer_id=o.customer_id
LEFT JOIN regions r ON r.region_code=c.customer_state
GROUP BY r.macro_region ORDER BY sales DESC NULLS LAST,r.macro_region ASC NULLS LAST;''',
    'T4-032': '''SELECT p.product_category_name,COUNT(DISTINCT ri.refund_id) AS cnt
FROM refund_items ri JOIN refunds rf ON rf.refund_id=ri.refund_id AND rf.order_id=ri.order_id
JOIN order_items oi ON oi.order_id=ri.order_id AND oi.order_item_id=ri.order_item_id
JOIN products p ON p.product_id=oi.product_id
WHERE p.product_category_name IS NOT NULL
GROUP BY p.product_category_name ORDER BY cnt DESC,p.product_category_name ASC;''',
    'T5-013': '''SELECT cp.campaign_id,COUNT(DISTINCT oi.order_id) AS orders,ROUND(SUM(oi.price),2) AS sales
FROM campaign_products cp JOIN order_items oi ON oi.product_id=cp.product_id
GROUP BY cp.campaign_id ORDER BY sales DESC NULLS LAST,cp.campaign_id ASC LIMIT 10;''',
    'T5-019': '''WITH changed AS(
 SELECT p.product_category_name,pph.product_id,pph.new_price-pph.old_price AS delta
 FROM product_price_history pph JOIN products p ON p.product_id=pph.product_id
 WHERE p.product_category_name IS NOT NULL),
means AS(SELECT product_category_name,ROUND(AVG(delta),2) AS avg_price_change FROM changed GROUP BY 1),
orders AS(SELECT x.product_category_name,COUNT(DISTINCT oi.order_id) AS orders
 FROM(SELECT DISTINCT product_category_name,product_id FROM changed)x
 LEFT JOIN order_items oi ON oi.product_id=x.product_id GROUP BY 1)
SELECT m.product_category_name,m.avg_price_change,o.orders FROM means m JOIN orders o USING(product_category_name)
ORDER BY o.orders DESC,m.product_category_name ASC LIMIT 20;''',
    'T6-016': '''WITH bp AS(SELECT b.brand_id,p.product_id FROM brands b
 LEFT JOIN product_brand_map bm ON bm.brand_id=b.brand_id AND bm.is_current IS TRUE
 LEFT JOIN products p ON p.product_id=bm.product_id),
totals AS(SELECT brand_id,COUNT(DISTINCT product_id) AS product_cnt FROM bp GROUP BY 1),
sales AS(SELECT bp.brand_id,COUNT(DISTINCT oi.product_id) AS sold_products,SUM(oi.price) AS sales
 FROM bp JOIN order_items oi ON oi.product_id=bp.product_id JOIN orders o ON o.order_id=oi.order_id
 WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY 1)
SELECT t.brand_id,t.product_cnt,COALESCE(s.sold_products,0) AS sold_products,ROUND(s.sales,2) AS sales,
 ROUND(100.0*COALESCE(s.sold_products,0)/NULLIF(t.product_cnt,0),2) AS sell_through_pct
FROM totals t JOIN sales s USING(brand_id) WHERE s.sales IS NOT NULL
ORDER BY sales DESC,t.brand_id ASC LIMIT 10;''',
    'T6-018': '''WITH user_orders AS(SELECT c.customer_unique_id,
 BOOL_OR(m.member_id IS NOT NULL) AS is_member,COUNT(DISTINCT o.order_id) AS order_cnt
 FROM orders o JOIN customers c ON c.customer_id=o.customer_id
 LEFT JOIN members m ON m.customer_unique_id=c.customer_unique_id
 WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY 1),
groups(is_member,user_type) AS(VALUES(TRUE,'member'),(FALSE,'non_member'))
SELECT g.user_type,ROUND(100.0*COUNT(u.customer_unique_id) FILTER(WHERE u.order_cnt>=2)
 /NULLIF(COUNT(u.customer_unique_id),0),2) AS repeat_rate_pct
FROM groups g LEFT JOIN user_orders u ON u.is_member=g.is_member
GROUP BY g.user_type ORDER BY g.user_type;''',
})
# These four explicit variants keep the original mathematical query.
for ident in ('T1-008','T2-030','T3-008','T4-036'):
    sql[ident] = reviews[ident]['goldSql']

DEST.mkdir(parents=True, exist_ok=True)
lineage = []
originals = {}
for path in sorted((ROOT/'data/eval').glob('T*.yaml')):
    originals[str(path.relative_to(ROOT)).replace('\\','/')] = hashlib.sha256(path.read_bytes()).hexdigest()
    items = []
    for original in yaml.safe_load(path.read_text(encoding='utf-8')):
        ident = original['id']
        if ident not in reviews:
            continue
        i, r = dict(original), reviews[ident]
        i['question'] = questions.get(ident, i['question'])
        i['gold_sql'] = sql.get(ident, r.get('proposedGoldSql', i['gold_sql']))
        i['gold_sql'] = re.sub(r'\s+LIMIT ALL\b', '', i['gold_sql'], flags=re.I).rstrip().rstrip(';')+';\n'
        i['notes'] = 'V3开发参考，题面已明确本题定义。' if ident in questions else i.get('notes','')
        items.append(i)
        lineage.append({'id': ident, 'classification': r['decision'],
            'questionChanged': i['question'] != original['question'],
            'goldSqlChanged': re.sub(r'\s+',' ',i['gold_sql']).strip() != re.sub(r'\s+',' ',original['gold_sql']).strip(),
            'definitionAuthority': 'benchmark-author explicit request; not user business approval'})
    if items:
        (DEST/path.name).write_text(yaml.safe_dump(items,allow_unicode=True,sort_keys=False,width=110),encoding='utf-8',newline='\n')
assert len(lineage)==100 and len({i['id'] for i in lineage})==100
(HERE/'candidate-lineage.json').write_text(json.dumps({'items':lineage,'originalDataSha256':originals},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'items':100,'questionChanges':sum(i['questionChanged'] for i in lineage),
 'sqlChanges':sum(i['goldSqlChanged'] for i in lineage),'providerCalls':0}))
