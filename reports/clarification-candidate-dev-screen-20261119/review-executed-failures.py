"""Read-only diagnosis of the frozen candidate. Never changes or rescores a benchmark."""
import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, 'scripts')
from eval_lib import load_eval_items, normalize_result

folder = Path('reports/clarification-candidate-dev-screen-20261119')
report_path = folder / 'eval-20261119-085723.json'
read = lambda p: json.loads(Path(p).read_text(encoding='utf-8-sig'))
report = read(report_path)
summary = read(folder / 'summary.json')
assert summary['passedEvidenceAudit'] and not summary['candidateForConfirmation']
assert hashlib.sha256(report_path.read_bytes()).hexdigest() == summary['reportSha256']
original = {item['id']: item for item in load_eval_items()}
executions = 0


def clean(sql):
    return sql.strip().rstrip(';')


def query(sql):
    global executions
    executions += 1
    wrapped = ("BEGIN READ ONLY; SET LOCAL statement_timeout='8s';\n"
               "SELECT COALESCE(json_agg(row_to_json(q)), '[]'::json) FROM (\n"
               + clean(sql) + "\n) q;\nCOMMIT;\n")
    result = subprocess.run(
        ['docker', 'exec', '-i', 'text2sql-postgres', 'psql', '-X', '-qAt',
         '-v', 'ON_ERROR_STOP=1', '-U', 'text2sql_ro', '-d', 'olist'],
        input=wrapped, text=True, encoding='utf-8', capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout, parse_float=Decimal)


def normalized(rows):
    return normalize_result([[None if v is None else str(v) for v in row.values()]
                             for row in rows], ordered=False)


def without_limit(sql):
    return re.sub(r'\s+LIMIT\s+\d+\s*$', '', clean(sql), flags=re.I)


categories = {
    'T1-026': 'top_k_ties', 'T4-001': 'top_k_ties',
    'T1-028': 'projection', 'T2-036': 'projection', 'T3-011': 'projection',
    'T4-012': 'projection', 'T4-045': 'projection_and_null_population',
    'T4-021': 'display_and_population', 'T4-027': 'display', 'T5-004': 'display',
    'T4-025': 'population', 'T4-043': 'population', 'T5-015': 'extra_reference_join',
    'T3-028': 'time_boundary',
}
checks = [
    ('member_login_boundary', """SELECT MAX(last_login_date) AS anchor,
       COUNT(*) FILTER (WHERE last_login_date=(SELECT MAX(last_login_date) FROM members)-30) AS at_lower,
       COUNT(*) FILTER (WHERE last_login_date>(SELECT MAX(last_login_date) FROM members)-30) AS exclusive_count,
       COUNT(*) FILTER (WHERE last_login_date>=(SELECT MAX(last_login_date) FROM members)-30) AS inclusive_count
       FROM members"""),
    ('member_top10_cutoff', """WITH cutoff AS (SELECT points FROM members ORDER BY points DESC OFFSET 9 LIMIT 1)
       SELECT (SELECT points FROM cutoff) AS cutoff,
       COUNT(*) FILTER (WHERE points>(SELECT points FROM cutoff)) AS strictly_above,
       COUNT(*) FILTER (WHERE points=(SELECT points FROM cutoff)) AS tied_at_cutoff FROM members"""),
    ('customer_top10_cutoff', """WITH counts AS (SELECT c.customer_unique_id,COUNT(*) AS n
       FROM orders o JOIN customers c ON c.customer_id=o.customer_id GROUP BY 1),
       cutoff AS (SELECT n FROM counts ORDER BY n DESC OFFSET 9 LIMIT 1)
       SELECT (SELECT n FROM cutoff) AS cutoff,
       COUNT(*) FILTER(WHERE n>(SELECT n FROM cutoff)) AS strictly_above,
       COUNT(*) FILTER(WHERE n=(SELECT n FROM cutoff)) AS tied_at_cutoff FROM counts"""),
    ('regions_without_warehouses', """SELECT r.macro_region, COUNT(*) AS regions
       FROM regions r WHERE NOT EXISTS(SELECT 1 FROM warehouses w WHERE w.region_code=r.region_code)
       GROUP BY r.macro_region ORDER BY 1"""),
    ('shipments_without_tracking', """SELECT s.status,COUNT(*) AS shipments FROM shipments s
       WHERE NOT EXISTS(SELECT 1 FROM shipment_tracking t WHERE t.shipment_id=s.shipment_id)
       GROUP BY s.status ORDER BY 1"""),
    ('campaigns_without_usages', """SELECT c.campaign_id,c.campaign_name FROM campaigns c
       WHERE NOT EXISTS(SELECT 1 FROM coupons p JOIN coupon_usages u ON u.coupon_id=p.coupon_id
       WHERE p.campaign_id=c.campaign_id) ORDER BY 1"""),
    ('unknown_weights', """SELECT product_category_name,COUNT(*) AS all_products,
       COUNT(product_weight_g) AS known_weights FROM products WHERE product_category_name IS NOT NULL
       GROUP BY 1 HAVING COUNT(*)<>COUNT(product_weight_g) ORDER BY all_products DESC"""),
    ('tracking_excluded_by_reference_item_join', """SELECT COUNT(*) AS tracking_records,
       COUNT(DISTINCT s.shipment_id) AS shipments FROM shipment_tracking st
       JOIN shipments s ON s.shipment_id=st.shipment_id JOIN orders o ON o.order_id=s.order_id
       WHERE NOT EXISTS(SELECT 1 FROM order_items oi WHERE oi.order_id=o.order_id)"""),
    ('shipment_missing_order_or_customer', """SELECT COUNT(*) AS shipments,
       COUNT(*) FILTER(WHERE o.order_id IS NULL) AS missing_orders,
       COUNT(*) FILTER(WHERE o.order_id IS NOT NULL AND c.customer_id IS NULL) AS missing_customers
       FROM shipments s LEFT JOIN orders o ON o.order_id=s.order_id
       LEFT JOIN customers c ON c.customer_id=o.customer_id"""),
    ('duplicate_display_names', """SELECT 'warehouses' AS source,COUNT(*)-COUNT(DISTINCT warehouse_name) AS duplicates
       FROM warehouses UNION ALL SELECT 'campaigns',COUNT(*)-COUNT(DISTINCT campaign_name) FROM campaigns
       UNION ALL SELECT 'regions',COUNT(*)-COUNT(DISTINCT region_name) FROM regions"""),
]
diagnostics = [dict(name=name, sql=sql, rows=query(sql)) for name, sql in checks]
failures = []
for item in report['itemEvidence']:
    if item['correct'] or item['status'] != 'SUCCESS':
        continue
    identity = item['id']
    source = original[identity]
    actual_sql, gold_sql = clean(item['finalSql']), clean(source['gold_sql'])
    actual, gold = query(actual_sql), query(gold_sql)
    variants = {}
    comparison_sql = gold_sql
    if identity == 'T1-028':
        comparison_sql = 'SELECT warehouse_id,warehouse_name FROM (' + gold_sql + ') q'
    elif identity == 'T2-036':
        comparison_sql = 'SELECT product_category_name FROM (' + gold_sql + ') q'
    elif identity == 'T3-011':
        comparison_sql = 'SELECT month,mom_rate FROM (' + gold_sql + ') q'
    elif identity == 'T4-012':
        comparison_sql = 'SELECT customer_state,avg_score FROM (' + gold_sql + ') q'
    elif identity == 'T3-028':
        variants['exclude_lower_boundary'] = actual_sql.replace('last_login_date >=', 'last_login_date >')
    elif identity == 'T4-021':
        variants['resolve_id_to_name'] = ('SELECT c.campaign_name,a.usage_count FROM (' + actual_sql
                                        + ') a JOIN campaigns c ON c.campaign_id=a.campaign_id')
        variants['resolve_and_exclude_zero_usage'] = variants['resolve_id_to_name'] + ' WHERE a.usage_count>0'
    elif identity == 'T4-025':
        variants['only_regions_with_warehouses'] = actual_sql.replace('LEFT JOIN warehouses', 'JOIN warehouses')
    elif identity == 'T4-027':
        variants['resolve_id_to_name'] = ('SELECT w.warehouse_name,a.total_quantity,a.avg_quantity FROM ('
                                        + actual_sql + ') a JOIN warehouses w ON w.warehouse_id=a.warehouse_id')
    elif identity == 'T4-043':
        variants['only_shipments_with_tracking'] = actual_sql.replace('LEFT JOIN (', 'JOIN (')
    elif identity == 'T4-045':
        comparison_sql = 'SELECT product_category_name,avg_weight FROM (' + gold_sql + ') q'
        variants['exclude_null_weights_from_ranking_population'] = actual_sql.replace(
            'WHERE p.product_category_name IS NOT NULL',
            'WHERE p.product_category_name IS NOT NULL AND p.product_weight_g IS NOT NULL')
    elif identity == 'T5-004':
        variants['resolve_region_code_to_name'] = ('SELECT r.region_name,a.shipment_count,a.covered_order_count FROM ('
            + actual_sql + ') a JOIN regions r ON r.region_code=a.region_code')
    elif identity == 'T5-015':
        variants['add_reference_item_filter'] = actual_sql.replace(
            'GROUP BY st.event_code', 'JOIN order_items oi ON oi.order_id=o.order_id\nGROUP BY st.event_code')
        variants['actual_without_top20'] = without_limit(actual_sql)
        variants['gold_without_top20'] = without_limit(gold_sql)
    comparison = query(comparison_sql) if comparison_sql != gold_sql else gold
    variant_results = []
    for name, sql in variants.items():
        rows = query(sql)
        variant_results.append(dict(name=name, sql=sql, rows=rows,
                                    sameMultisetAsDiagnosticTarget=normalized(rows)==normalized(comparison)))
    failures.append(dict(id=identity, question=source['question'], category=categories[identity],
        frozenStrictCorrect=False, actualSql=actual_sql, goldSql=gold_sql, actualRows=actual, goldRows=gold,
        sameMultisetIgnoringOrder=normalized(actual)==normalized(gold),
        diagnosticTargetSql=comparison_sql,
        sameMultisetAfterReferenceProjection=normalized(actual)==normalized(comparison), variants=variant_results))

assert len(failures) == 14 and {x['id'] for x in failures} == set(categories)
assert not any(x['sameMultisetIgnoringOrder'] for x in failures)
assert hashlib.sha256(report_path.read_bytes()).hexdigest() == summary['reportSha256']
result = dict(format='clarification-readonly-review-v1', reviewedAt=datetime.now(timezone.utc).isoformat(),
    measuredCommit=summary['codeCommit'], reportSha256=summary['reportSha256'],
    executedFailureCount=len(failures), readonlyStatements=executions,
    categoryCounts=dict(Counter(x['category'] for x in failures)), diagnostics=diagnostics, items=failures,
    providerCalls=0, providerTokens=0, providerCostYuan=0, originalScore=68, rescored=False,
    limits=['Diagnostic variants are hand-written explanations, never model successes.',
            'Multiset comparisons ignore row order only to isolate differences; the frozen scorer is unchanged.',
            'Current-database agreement does not prove equivalence on all data or resolve ambiguous intent.'])
(folder/'readonly-review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k not in ('diagnostics','items')},ensure_ascii=True,indent=2))
for x in failures:
    print(x['id'], x['category'], 'projection_equal='+str(x['sameMultisetAfterReferenceProjection']),
          [(v['name'],v['sameMultisetAsDiagnosticTarget']) for v in x['variants']])
