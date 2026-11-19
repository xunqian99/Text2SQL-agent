"""Read-only diagnosis of the 17 executed p19 failures; never rescores the benchmark."""
import collections
import datetime
import decimal
import hashlib
import json
from pathlib import Path
import re
import subprocess

source = Path('reports/request-scope-dev-screen-20261118/failure-details.json')
output = Path('reports/metric-alias-scope-20261119')
output.mkdir(exist_ok=True)
failures = json.loads(source.read_text(encoding='utf-8'))
executions = 0

def query(sql):
    global executions
    executions += 1
    # Separate lines keep leading SQL comments from swallowing the wrapping expression.
    wrapped = "BEGIN READ ONLY; SET LOCAL statement_timeout='8s';\nSELECT COALESCE(json_agg(row_to_json(q)), '[]'::json) FROM (\n" + sql.strip().rstrip(';') + "\n) q;\nCOMMIT;"
    result = subprocess.run(['docker', 'exec', '-i', 'text2sql-postgres', 'psql', '-X', '-qAt',
                             '-v', 'ON_ERROR_STOP=1', '-U', 'text2sql_ro', '-d', 'olist'],
                            input=wrapped, text=True, encoding='utf-8', capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout, parse_float=decimal.Decimal)

def scalar(value):
    if value is None: return ('null', '')
    if isinstance(value, (int, float, decimal.Decimal)):
        return ('number', str(decimal.Decimal(str(value)).quantize(decimal.Decimal('.0001')).normalize()))
    return ('text', str(value).strip())

def values(rows):
    return [tuple(scalar(v) for v in r.values()) for r in rows]

def unbounded(sql):
    return re.sub(r'\s+LIMIT\s+\d+;?\s*$', '', sql, flags=re.I).strip().rstrip(';')

checks = [
 ('dictionary_duplicates', "SELECT 'warehouses' AS source,COUNT(*)-COUNT(DISTINCT warehouse_name) AS repeated_names FROM warehouses UNION ALL SELECT 'campaigns',COUNT(*)-COUNT(DISTINCT campaign_name) FROM campaigns UNION ALL SELECT 'member_levels',COUNT(*)-COUNT(DISTINCT level_name) FROM member_levels UNION ALL SELECT 'ticket_categories',COUNT(*)-COUNT(DISTINCT category_name) FROM ticket_categories UNION ALL SELECT 'brands',COUNT(*)-COUNT(DISTINCT brand_name) FROM brands"),
 ('order_item_scope', "SELECT o.order_status,COUNT(DISTINCT o.order_id) AS orders,ROUND(SUM(oi.price),2) AS item_amount FROM orders o JOIN order_items oi ON oi.order_id=o.order_id GROUP BY 1 ORDER BY 1"),
 ('zero_tracking_shipments', "SELECT s.status,COUNT(*) AS no_tracking FROM shipments s WHERE NOT EXISTS(SELECT 1 FROM shipment_tracking t WHERE t.shipment_id=s.shipment_id) GROUP BY 1 ORDER BY 1"),
 ('weight_nulls', "SELECT product_category_name,COUNT(*) AS all_products,COUNT(product_weight_g) AS known_weights FROM products WHERE product_category_name IS NOT NULL GROUP BY 1 HAVING COUNT(*)<>COUNT(product_weight_g) ORDER BY all_products DESC LIMIT 15"),
 ('window_boundaries', "SELECT COUNT(*) FILTER(WHERE moved_at=a.max_ts-INTERVAL '90 days') AS at_lower,COUNT(*) FILTER(WHERE moved_at=a.max_ts) AS at_upper,COUNT(*) FILTER(WHERE moved_at>a.max_ts) AS after_max FROM inventory_movements CROSS JOIN(SELECT MAX(moved_at) AS max_ts FROM inventory_movements) a WHERE movement_type=2"),
 ('orphan_brand_facts', "SELECT COUNT(*) AS item_records,COUNT(*) FILTER(WHERE o.order_id IS NULL) AS missing_orders,COUNT(*) FILTER(WHERE c.customer_id IS NULL) AS missing_customers FROM order_items oi LEFT JOIN orders o ON o.order_id=oi.order_id LEFT JOIN customers c ON c.customer_id=o.customer_id"),
]
diagnostics = [dict(name=name, sql=sql, result=query(sql)) for name, sql in checks]
assert all(r['repeated_names']==0 for r in diagnostics[0]['result']), 'Dictionary-name mapping is not unique; stop identity probes'
cases = []
for item in failures:
    if item['status'] != 'SUCCESS': continue
    identity = item['id']; a = item['finalSql'].strip().rstrip(';'); g = item['goldSql'].strip().rstrip(';')
    gold, actual = query(g), query(a)
    variants = {}
    target = g
    if identity == 'T1-028':
        target = 'SELECT warehouse_id,capacity FROM ('+g+') a'
    elif identity == 'T2-030':
        variants['resolve_unique_names_to_ids'] = 'SELECT tc.category_id,a.ticket_count FROM ('+a+') a LEFT JOIN ticket_categories tc ON tc.category_name=a.category_name'
    elif identity == 'T2-036':
        target = 'SELECT product_category_name FROM ('+g+') a'
    elif identity == 'T3-008':
        variants['remove_injected_default_status_filter'] = a.replace("WHERE o.order_status NOT IN ('canceled', 'unavailable')", '')
    elif identity == 'T3-011':
        target = 'SELECT month,mom_rate FROM ('+g+') a'
    elif identity == 'T4-010':
        variants['only_fact_records_with_items'] = a.replace('LEFT JOIN order_items','JOIN order_items')
    elif identity == 'T4-012':
        target = 'SELECT customer_state,avg_score FROM ('+g+') a'
    elif identity == 'T4-016':
        variants['customer_state_source'] = a.replace('JOIN sellers s ON s.seller_id = oi.seller_id', 'JOIN orders o ON o.order_id=oi.order_id JOIN customers c ON c.customer_id=o.customer_id').replace('r.region_code = s.seller_state', 'r.region_code = c.customer_state')
    elif identity == 'T4-018':
        variants['resolve_unique_names_to_ids'] = 'SELECT ml.level_id,a.total_consumption FROM ('+a+') a JOIN member_levels ml ON ml.level_name=a.level_name'
    elif identity == 'T4-021':
        variants['resolve_unique_ids_to_names'] = 'SELECT c.campaign_name,a.coupon_usage_count FROM ('+a+') a JOIN campaigns c ON c.campaign_id=a.campaign_id'
        variants['resolve_and_remove_zero_usage_campaigns'] = variants['resolve_unique_ids_to_names']+' WHERE a.coupon_usage_count>0'
    elif identity == 'T4-027':
        variants['resolve_unique_ids_to_names'] = 'SELECT w.warehouse_name,a.total_quantity,a.avg_quantity FROM ('+a+') a JOIN warehouses w ON w.warehouse_id=a.warehouse_id'
    elif identity == 'T4-043':
        variants['only_shipments_with_tracking'] = a.replace('LEFT JOIN shipment_tracking', 'JOIN shipment_tracking')
    elif identity == 'T4-045':
        target = 'SELECT product_category_name,avg_weight FROM ('+g+') a'
        variants['exclude_unknown_weights_for_ranking_population'] = a.replace('WHERE product_category_name IS NOT NULL','WHERE product_category_name IS NOT NULL AND product_weight_g IS NOT NULL')
    elif identity == 'T5-011':
        variants['resolve_unique_ids_to_names'] = 'SELECT b.brand_name,a.sales FROM ('+a+') a JOIN brands b ON b.brand_id=a.brand_id'
    elif identity == 'T5-015':
        variants['gold_extra_item_join'] = a.replace('GROUP BY st.event_code', 'JOIN order_items oi ON oi.order_id=o.order_id\nGROUP BY st.event_code')
        variants['actual_unbounded'] = unbounded(a)
        variants['gold_unbounded'] = unbounded(g)
    elif identity == 'T5-019':
        variants['absolute_change_preserve_product_weighting'] = a.replace('(h.new_price - h.old_price) / NULLIF(h.old_price, 0)', 'h.new_price - h.old_price').replace('ROUND(cc.avg_change, 4)', 'ROUND(cc.avg_change, 2)')
        variants['absolute_change_per_history_record_separate_order_count'] = '''WITH changes AS (
          SELECT p.product_category_name AS category,ROUND(AVG(h.new_price-h.old_price),2) AS change
          FROM product_price_history h JOIN products p ON p.product_id=h.product_id
          WHERE p.product_category_name IS NOT NULL GROUP BY 1
        ), counts AS (
          SELECT p.product_category_name AS category,COUNT(DISTINCT oi.order_id) AS order_cnt
          FROM order_items oi JOIN products p ON p.product_id=oi.product_id
          WHERE p.product_category_name IS NOT NULL AND EXISTS(SELECT 1 FROM product_price_history h WHERE h.product_id=p.product_id)
          GROUP BY 1
        ) SELECT c.category,c.change,COALESCE(n.order_cnt,0) AS order_cnt FROM changes c LEFT JOIN counts n ON n.category=c.category ORDER BY order_cnt DESC LIMIT 20'''
    elif identity == 'T6-023':
        variants['resolve_unique_ids_to_names'] = 'SELECT w.warehouse_name,a.current_stock,a.out_qty,a.turnover_days FROM ('+a+') a JOIN warehouses w ON w.warehouse_id=a.warehouse_id'
        variants['resolve_and_align_window'] = variants['resolve_unique_ids_to_names'].replace('moved_at >=','moved_at >').replace("moved_at < (SELECT max_at FROM anchor) + INTERVAL '1 second'",'moved_at <= (SELECT max_at FROM anchor)')
    comparison = query(target) if target!=g else gold
    probes=[]
    for name,sql in variants.items():
        data=query(sql)
        probes.append(dict(name=name,sql=sql,result=data,rows=len(data),
                           sameUnorderedValuesAsComparison=collections.Counter(values(data))==collections.Counter(values(comparison)),
                           samePositionalSequenceAsComparison=values(data)==values(comparison)))
    cases.append(dict(id=identity,question=item['question'],goldSql=g,actualSql=a,gold=gold,actual=actual,
                      comparisonSql=target,comparison=comparison,
                      actualSameUnorderedValuesAsComparison=collections.Counter(values(actual))==collections.Counter(values(comparison)),
                      probes=probes))
report=dict(format='request-scope-readonly-contract-review-v1',createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            reviewedCommit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
            sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),sqlExecutions=executions,
            modelCalls=0,modelCostYuan=0,scoresUnchanged=True,originalDataMutated=False,diagnostics=diagnostics,cases=cases,
            limits=['Diagnostic projection and dictionary substitution are not production behavior or formal rescoring.',
                    'Comparison normalizes numeric values to 4 decimals and ignores names; unordered equality ignores sorting.',
                    'Unique dictionary names are verified for this snapshot only; no promise for other data.',
                    'No model compliance, new accuracy or latency improvement is measured.'])
(output/'readonly-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2,default=str)+'\n',encoding='utf-8')
for c in cases: print(c['id'],'actualEqProjectedGold=',c['actualSameUnorderedValuesAsComparison'],[(p['name'],p['sameUnorderedValuesAsComparison']) for p in c['probes']])
print('READONLY',executions,'modelCalls=0')
