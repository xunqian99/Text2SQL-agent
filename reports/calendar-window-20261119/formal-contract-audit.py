"""Review all original eval100 contracts; keep Gold, scoring and history intact.

Counterfactual SQL is diagnostic evidence, never a production question-ID branch.
No model SDK is imported. All database statements run as text2sql_ro READ ONLY.
"""
from collections import Counter
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
FOLDER = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT/'scripts'))
from eval_lib import load_eval_items, normalize_result

read = lambda p: json.loads(Path(p).read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
questions = {q['id']:q for q in load_eval_items()}
capture = read(FOLDER/'prompt-eval.json')
ids = [i['id'] for i in capture['items']]
assert len(ids)==len(set(ids))==100
clarification = {i['id']:i for i in read(FOLDER/'clarifications.json')['items']}
clarify_ids = {i for i in ids if clarification[i]['needsClarification']}
assert clarify_ids == {'T4-004','T4-006','T4-015','T5-001','T6-011'}

plans = []
def plan(name, sql):
    assert name not in {p['name'] for p in plans}
    plans.append(dict(name=name,sql=sql.strip().rstrip(';')))
    return name

display = {
    'T4-019':[('t.tag_name','t.tag_id')],
    'T4-028':[('tc.category_name','tc.category_id')],
    'T5-005':[('ca.campaign_name','ca.campaign_id')],
    'T5-009':[('w.warehouse_name','w.warehouse_id'),('r.region_name','r.region_code'),('b.brand_name','b.brand_id')],
    'T5-012':[('ut.tag_name','ut.tag_id')],
    'T6-015':[('tc.category_name','tc.category_id')],
}
alternatives = {}
for identity, changes in display.items():
    sql = questions[identity]['gold_sql']
    for before,after in changes:
        assert sql.count(before)==1, (identity,before)
        sql = sql.replace(before,after)
    alternatives[identity] = sql
alternatives['T4-013'] = questions['T4-013']['gold_sql'].replace('COUNT(*)','COUNT(DISTINCT o.order_id)')
alternatives['T6-013'] = """
    WITH ct AS(SELECT order_id,SUM(discount_amount) AS discount FROM coupon_usages GROUP BY 1),
    ot AS(SELECT order_id,SUM(price+freight_value) AS gmv FROM order_items GROUP BY 1)
    SELECT ROUND(SUM(ot.gmv),2) AS gmv,ROUND(SUM(ct.discount),2) AS discount,
           ROUND(100.0*SUM(ct.discount)/NULLIF(SUM(ot.gmv),0),2) AS discount_pct
    FROM ct JOIN orders o USING(order_id) JOIN ot USING(order_id)
    WHERE o.order_status NOT IN ('canceled','unavailable')
    """
alternatives['T3-014'] = questions['T3-014']['gold_sql'].replace(
    "o.order_purchase_timestamp > a.mx - INTERVAL '90 days'",'o.order_purchase_timestamp >= a.mx::date - 89').replace(
    'o.order_purchase_timestamp <= a.mx','o.order_purchase_timestamp < a.mx::date + 1')
for identity, sql in alternatives.items():
    assert sql != questions[identity]['gold_sql']
    plan(identity+'-gold',questions[identity]['gold_sql'])
    plan(identity+'-contract',sql)

stats = {
    'review-grain':"SELECT COUNT(*) AS review_rows,COUNT(DISTINCT order_id) AS distinct_orders FROM order_reviews WHERE review_score<=2",
    'coupon-campaign-multiplicity':"SELECT COUNT(*) AS duplicate_campaign_order_pairs FROM(SELECT cp.campaign_id,cu.order_id FROM coupon_usages cu JOIN coupons cp USING(coupon_id) GROUP BY 1,2 HAVING COUNT(*)>1)t",
    'snapshot-multiplicity':"SELECT COUNT(*) AS rows,COUNT(DISTINCT member_id) AS members,COUNT(*) FILTER(WHERE snapshot_date IS NULL) AS null_dates FROM member_snapshot",
    'search-click-coverage':"SELECT COUNT(*) AS clicked,COUNT(*) FILTER(WHERE m.member_id IS NULL) AS missing_member,COUNT(*) FILTER(WHERE ml.level_id IS NULL) AS missing_level FROM search_logs sl LEFT JOIN members m USING(member_id) LEFT JOIN member_levels ml ON ml.level_id=m.level_id WHERE sl.clicked_product_id IS NOT NULL",
    'customer-order-coincidence':"SELECT (SELECT COUNT(*) FROM customers) AS customers,(SELECT COUNT(*) FROM orders) AS orders,(SELECT COUNT(*) FROM customers c WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.customer_id=c.customer_id)) AS without_orders,(SELECT COUNT(*) FROM(SELECT customer_id FROM orders GROUP BY 1 HAVING COUNT(*)>1)t) AS multiple_orders",
    'registration-null':"SELECT COUNT(*) FILTER(WHERE register_date IS NULL) AS null_registration FROM members",
    'order-item-population':"SELECT COUNT(*) AS all_orders,COUNT(*) FILTER(WHERE NOT EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=o.order_id)) AS without_items FROM orders o",
    'display-name-uniqueness':"SELECT 'user_tags' AS entity,COUNT(*) AS rows,COUNT(DISTINCT tag_name) AS names FROM user_tags UNION ALL SELECT 'ticket_categories',COUNT(*),COUNT(DISTINCT category_name) FROM ticket_categories UNION ALL SELECT 'warehouses',COUNT(*),COUNT(DISTINCT warehouse_name) FROM warehouses UNION ALL SELECT 'campaigns',COUNT(*),COUNT(DISTINCT campaign_name) FROM campaigns UNION ALL SELECT 'member_levels',COUNT(*),COUNT(DISTINCT level_name) FROM member_levels",
}
for name,sql in stats.items():plan(name,sql)
topk = {
    'T1-012':('price',10),'T1-016':('payment_installments',1),
    'T2-008':('cnt',5),'T2-011':('total',10),'T2-035':('cnt',10),
    'T3-005':('cnt',1),'T3-016':('cnt',1),'T4-004':('sales',1),
    'T4-035':('avg_order_value',10),'T4-037':('sold',10),
    'T5-003':('refund_amt',15),'T5-005':('gmv',10),'T5-007':('clicks',20),
    'T5-009':('low_stock_items',20),'T5-010':('commission',20),
    'T5-016':('amt',20),'T5-018':('orders',20),'T6-019':('avg_days',10),
}
for identity,(metric,k) in topk.items():
    sql=re.sub(r'\s+ORDER BY\s+[^;]+\s+LIMIT\s+\d+\s*;?\s*$','',questions[identity]['gold_sql'],flags=re.I)
    assert sql != questions[identity]['gold_sql']
    plan(identity+'-cutoff',f"""WITH candidates AS({sql}), cutoff AS(
      SELECT {metric} AS value FROM candidates ORDER BY {metric} DESC OFFSET {k-1} LIMIT 1)
      SELECT (SELECT value FROM cutoff) AS cutoff,COUNT(*) AS candidates,
      COUNT(*) FILTER(WHERE {metric}>(SELECT value FROM cutoff)) AS strictly_above,
      COUNT(*) FILTER(WHERE {metric}=(SELECT value FROM cutoff)) AS tied_at_cutoff FROM candidates""")

wrapped = "BEGIN READ ONLY;\nSET LOCAL statement_timeout='8s';\n" + '\n'.join(
    f"SELECT json_build_object('name','{p['name']}','rows',COALESCE(json_agg(row_to_json(q)),'[]'::json)) FROM (\n{p['sql']}\n) q;"
    for p in plans) + '\nCOMMIT;\n'
result=subprocess.run(['docker','exec','-i','text2sql-postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1',
    '-U','text2sql_ro','-d','olist'],input=wrapped,text=True,encoding='utf-8',capture_output=True,timeout=90)
if result.returncode:raise RuntimeError(result.stderr)
queries={x['name']:x['rows'] for x in (json.loads(line,parse_float=Decimal) for line in result.stdout.splitlines() if line.strip())}
assert len(queries)==len(plans)

def normalized(rows):
    return normalize_result([[None if v is None else str(v) for v in row.values()] for row in rows],ordered=False)

comparisons={}
for identity in alternatives:
    gold,contract=queries[identity+'-gold'],queries[identity+'-contract']
    assert gold and contract and normalized(gold)!=normalized(contract),identity
    comparisons[identity]=dict(gold=gold,contract=contract,
        sameRowMultiset=False,diagnosticOnly=True,rescored=False)

notes={
    'T2-004':('population_ambiguity','Gold均值仅覆盖有明细订单，775单无明细；尚未冻结全部订单是否补0的业务口径，不计入确定冲突。'),
    'T2-037':('current_data_coincidence','题目问订单，Gold数客户档案。现库客户与订单一对一且无孤儿，当前结果相同；不是可泛化的等价改写。'),
    'T3-027':('current_data_coincidence','Gold过滤NULL注册日期；现库NULL为0，不构成当前结果冲突。'),
    'T4-037':('population_ambiguity','Gold只从有销量商品取Top10；未给零销量时的保留与TopK并列规则，不计确定冲突。'),
    'T5-005':('current_data_coincidence','优惠记录直连明细存在多对多隐患，但现库同活动同订单无重复用券；此隐患不计当前金额错误。名称投影另有确定冲突。'),
    'T5-007':('current_data_coincidence','Gold额外关联会员/等级；现库8651条点击均有匹配，未造成当前过滤损失，不判当前Gold错误。'),
    'T5-014':('current_data_coincidence','Gold未挑最近快照；现库38471行=38471会员且日期无NULL，当前每人只有一份快照。缺少多快照反例，不能宣称实现可泛化。'),
    'T6-006':('contract_alignment','按送达时间非空确定已发生送达事件，与当前履约指标一致；不以状态字段强行替代。'),
    'T6-022':('contract_alignment','题目显式要求两年GMV及同比，Gold原始两期金额是所需输出，不是多余列。'),
    'T6-024':('contract_alignment','退款事实集合中的评价分母有已登记指标例外，不额外加全站有效订单过滤。'),
}
cutoffs={}
for identity,(_,k) in topk.items():
    row=queries[identity+'-cutoff'][0]
    row['limit']=k
    row['ambiguousMembership']=row['cutoff'] is not None and row['strictly_above']+row['tied_at_cutoff']>k
    cutoffs[identity]=row

items=[]
for identity in ids:
    q=questions[identity];issues=[];confirmed=[]
    if identity in clarify_ids:
        issues.append(dict(kind='single_turn_clarification_conflict',detail='生产要求先澄清；旧严格单轮评分将澄清计错。'))
        confirmed.append('single_turn_clarification_conflict')
    if identity in display:
        issues.append(dict(kind='default_display_conflict',detail='Gold输出名称；当前规则8默认输出现有标识。仅替换展示列后结果已核实不同。'))
        confirmed.append('default_display_conflict')
    if identity=='T4-013':
        issues.append(dict(kind='reference_grain_defect',detail='问题单位为不同订单；Gold数低分评价记录。多评价订单重复计数，按州结果实际不同。'))
        confirmed.append('reference_grain_defect')
    if identity=='T6-013':
        issues.append(dict(kind='reference_grain_defect',detail='Gold把订单优惠重复到商品明细；按订单分别聚合优惠和商品金额后实际不同。'))
        confirmed.append('reference_grain_defect')
    if identity=='T3-014':
        issues.append(dict(kind='default_time_conflict',detail='现行最后N天默认自然日，Gold采用最大时刻回溯连续90天，首周金额相差10838.88。'))
        confirmed.append('default_time_conflict')
    if identity in notes:
        kind,detail=notes[identity];issues.append(dict(kind=kind,detail=detail))
    if identity in cutoffs and cutoffs[identity]['ambiguousMembership']:
        issues.append(dict(kind='top_k_tie_ambiguity',detail='截断线存在并列，Gold无二级排序，允许成员集合未冻结；不计入确定冲突。'))
    items.append(dict(id=identity,question=q['question'],questionSha256=hashlib.sha256(q['question'].encode()).hexdigest(),
        goldSqlSha256=hashlib.sha256(q['gold_sql'].encode()).hexdigest(),issues=issues,
        confirmedConflictKinds=confirmed,reviewScope='Original question/Gold and current documented contracts; no independent blind review.'))

confirmed={i['id'] for i in items if i['confirmedConflictKinds']}
assert len(confirmed)==14
assert set(display).isdisjoint(clarify_ids)
assert queries['review-grain']==[dict(review_rows=14575,distinct_orders=14533)]
assert queries['coupon-campaign-multiplicity']==[dict(duplicate_campaign_order_pairs=0)]
assert queries['snapshot-multiplicity']==[dict(rows=38471,members=38471,null_dates=0)]
assert queries['search-click-coverage']==[dict(clicked=8651,missing_member=0,missing_level=0)]
stats_report=dict(format='formal-contract-audit-v1',productionCommit=subprocess.check_output(
    ['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),itemsReviewed=100,
    readOnlyQueries=len(plans),originalGoldChanged=False,scorerChanged=False,rescored=False,
    independentReview=False,externalProviderCalls=0,providerTokens=0,providerCostYuan=0,
    confirmedDistinctConflicts=sorted(confirmed),confirmedConflictCount=14,
    conflictCounts=dict(Counter(k for i in items for k in i['confirmedConflictKinds'])),
    conditionalContractCompliantScoreCeilingPercent=86,formalGatePercent=89,
    ceilingScope='Conditional logical ceiling for fully contract-compliant outputs on this frozen database and single-turn exact scorer; not measured model accuracy.',
    decision='Do not spend on a candidate that cannot jointly meet current contracts and the unchanged original89% gate. Resolve evaluation-contract governance first; preserve every historical failure.',
    latestMeasuredDevCorrect=68,latestMeasuredFormalPercent=78,stageAccepted=False,stage3Deferred=True,
    items=items,comparisons=comparisons,cutoffs=cutoffs,
    diagnostics={name:queries[name] for name in stats},
    provenanceSha256={str(p.relative_to(ROOT)):sha(p) for p in [FOLDER/'clarifications.json',FOLDER/'prompt-eval.json',
        ROOT/'src/main/resources/schema/ambiguities.yml',ROOT/'src/main/resources/schema/metrics.yml',
        ROOT/'src/main/java/com/text2sql/agent/generation/PromptTemplate.java']})

def dumps(obj):return json.dumps(obj,ensure_ascii=False,indent=2,default=str)+'\n'
for name,content in [('formal-contract-audit.sql',wrapped),('formal-contract-audit.json',dumps(stats_report)),
                     ('formal-contract-query-results.json',dumps(dict(queries=plans,results=queries)))]:
    with (FOLDER/name).open('x',encoding='utf-8',newline='\n') as f:f.write(content)
print(dumps({k:v for k,v in stats_report.items() if k not in ['items','comparisons','cutoffs','diagnostics','provenanceSha256']}))
