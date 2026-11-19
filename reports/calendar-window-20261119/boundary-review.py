"""Read-only PostgreSQL boundary evidence. No provider client or credentials."""
import hashlib
import json
from pathlib import Path
import subprocess

FOLDER = Path(__file__).resolve().parent
CASES = []


def case(identity, sql, expected=None):
    CASES.append(dict(id=identity, sql=sql.strip().rstrip(';'), expected=expected))


def timestamp_case(identity, values, predicate, expected):
    rows = ','.join(f"('{label}',TIMESTAMP '{time}')" for label, time in values)
    case(identity, f"WITH events(id,t) AS(VALUES {rows}) "
         f"SELECT COALESCE(json_agg(id ORDER BY id),'[]'::json) FROM events WHERE {predicate}", expected)


edges = [('before', '2024-02-29 23:59:59.999999'),
         ('start', '2024-03-01 00:00:00'),
         ('noon', '2024-03-01 12:00:00'),
         ('last', '2024-03-01 23:59:59.999999'),
         ('next', '2024-03-02 00:00:00')]
timestamp_case('one-day-contains-whole-reference-day', edges,
               "t >= DATE '2024-03-01' AND t < DATE '2024-03-01'+1", ['last', 'noon', 'start'])
timestamp_case('two-days-cross-leap-day', edges,
               "t >= DATE '2024-03-01'-1 AND t < DATE '2024-03-01'+1", ['before', 'last', 'noon', 'start'])
timestamp_case('between-date-end-is-only-midnight', edges,
               "t BETWEEN DATE '2024-02-29' AND DATE '2024-03-01'", ['before', 'start'])
timestamp_case('explicit-exclusive-end-remains-exclusive', edges,
               "t >= TIMESTAMP '2024-03-01 00:00' AND t < TIMESTAMP '2024-03-01 12:00'", ['start'])
timestamp_case('explicit-inclusive-clock-retains-noon', edges,
               "t >= TIMESTAMP '2024-03-01 00:00' AND t <= TIMESTAMP '2024-03-01 12:00'", ['noon', 'start'])
timestamp_case('rolling-24-hours-preserves-clock', [
    ('lower', '2024-02-29 12:00:00'), ('inside', '2024-02-29 12:00:00.000001'),
    ('anchor', '2024-03-01 12:00:00'), ('after', '2024-03-01 12:00:00.000001')],
    "t > TIMESTAMP '2024-03-01 12:00'-INTERVAL '24 hours' AND t <= TIMESTAMP '2024-03-01 12:00'",
    ['anchor', 'inside'])
case('thirty-natural-dates', """
    WITH events AS(SELECT d::date AS t FROM generate_series(
      TIMESTAMP '2024-01-01',TIMESTAMP '2024-03-02',INTERVAL '1 day') d)
    SELECT to_json(COUNT(*)) FROM events WHERE t >= DATE '2024-03-01'-29 AND t < DATE '2024-03-01'+1
    """, 30)
case('off-by-one-control-is-thirty-one', """
    WITH events AS(SELECT d::date AS t FROM generate_series(
      TIMESTAMP '2024-01-01',TIMESTAMP '2024-03-02',INTERVAL '1 day') d)
    SELECT to_json(COUNT(*)) FROM events WHERE t >= DATE '2024-03-01'-30 AND t < DATE '2024-03-01'+1
    """, 31)
timestamp_case('cross-year-two-natural-days', [
    ('before', '2023-12-30 23:59:59.999999'), ('start', '2023-12-31 00:00:00'),
    ('anchor', '2024-01-01 23:59:59.999999'), ('end', '2024-01-02 00:00:00')],
    "t >= DATE '2024-01-01'-1 AND t < DATE '2024-01-01'+1", ['anchor', 'start'])
timestamp_case('calendar-month-keeps-final-microsecond', [
    ('first', '2024-02-01 00:00:00'), ('last', '2024-02-29 23:59:59.999999'),
    ('next', '2024-03-01 00:00:00')],
    "t >= DATE '2024-02-01' AND t < DATE '2024-03-01'", ['first', 'last'])
case('null-anchor-count-and-mean', """
    WITH events(t,value) AS(VALUES(NULL::date,10),(NULL::date,20)),
    anchor AS(SELECT MAX(t) AS d FROM events)
    SELECT json_build_array(COUNT(*),AVG(value)) FROM events CROSS JOIN anchor
    WHERE t >= d-29 AND t < d+1
    """, [0, None])
case('global-anchor-before-business-filter', """
    WITH events(group_id,t) AS(VALUES('old',DATE '2024-01-01'),('new',DATE '2024-03-01')),
    anchor AS(SELECT MAX(t) AS d FROM events)
    SELECT to_json(COUNT(*)) FROM events CROSS JOIN anchor
    WHERE group_id='old' AND t >= d-1 AND t < d+1
    """, 0)
case('login-anchor-is-not-order-anchor', """
    WITH orders(t) AS(VALUES(DATE '2018-10-17')), members(t) AS(VALUES(DATE '2018-10-23')),
    anchor AS(SELECT MAX(t) AS d FROM members)
    SELECT to_json(COUNT(*)) FROM members CROSS JOIN anchor WHERE t >= d AND t < d+1
    """, 1)
case('max-ignores-null-without-changing-window', """
    WITH events(t) AS(VALUES(NULL::date),(DATE '2024-03-01'),(DATE '2024-02-01')),
    anchor AS(SELECT MAX(t) AS d FROM events)
    SELECT to_json(COUNT(*)) FROM events CROSS JOIN anchor WHERE t >= d-29 AND t < d+1
    """, 2)
case('real-member-window', """
    WITH a AS(SELECT MAX(last_login_date) AS d FROM members)
    SELECT json_build_object('anchor',d,'start',d-29,'endExclusive',d+1,
      'natural30',(SELECT COUNT(*) FROM members WHERE last_login_date>=d-29 AND last_login_date<d+1),
      'gold',(SELECT COUNT(*) FROM members WHERE last_login_date>d-30),
      'wrongInclusive31',(SELECT COUNT(*) FROM members WHERE last_login_date>=d-30),
      'extraBoundaryMembers',(SELECT COUNT(*) FROM members WHERE last_login_date=d-30)) FROM a
    """)
case('real-order-window-reference-difference', """
    WITH a AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders)
    SELECT json_build_object('anchor',mx,'calendarStart',mx::date-89,'calendarEndExclusive',mx::date+1,
      'natural90',(SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp>=mx::date-89 AND order_purchase_timestamp<mx::date+1),
      'oldGoldRolling90',(SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp>mx-INTERVAL '90 days'),
      'removedPartialDate',(SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp>mx-INTERVAL '90 days' AND order_purchase_timestamp<mx::date-89)) FROM a
    """)
case('real-week-sales-reference-difference', """
    WITH a AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders),
    calendar AS(SELECT DATE_TRUNC('week',o.order_purchase_timestamp)::date AS week_start,SUM(oi.price) AS sales
      FROM orders o JOIN order_items oi USING(order_id) CROSS JOIN a
      WHERE o.order_purchase_timestamp>=mx::date-89 AND o.order_purchase_timestamp<mx::date+1 GROUP BY 1),
    old_gold AS(SELECT DATE_TRUNC('week',o.order_purchase_timestamp)::date AS week_start,SUM(oi.price) AS sales
      FROM orders o JOIN order_items oi USING(order_id) CROSS JOIN a
      WHERE o.order_purchase_timestamp>mx-INTERVAL '90 days' AND o.order_purchase_timestamp<=mx GROUP BY 1)
    SELECT COALESCE(json_agg(json_build_object('week',g.week_start,'calendar',ROUND(c.sales,2),
      'oldGold',ROUND(g.sales,2),'difference',ROUND(g.sales-c.sales,2)) ORDER BY g.week_start),'[]'::json)
      FROM old_gold g FULL JOIN calendar c USING(week_start) WHERE c.sales IS DISTINCT FROM g.sales
    """)

sql = "BEGIN READ ONLY;\nSET LOCAL statement_timeout='8s';\n" + '\n'.join(
    f"SELECT json_build_object('id','{c['id']}','result',probe.value) FROM ({c['sql']}) AS probe(value);"
    for c in CASES) + '\nCOMMIT;\n'
run = subprocess.run(['docker','exec','-i','text2sql-postgres','psql','-X','-qAt',
    '-v','ON_ERROR_STOP=1','-U','text2sql_ro','-d','olist'], input=sql, text=True,
    encoding='utf-8',capture_output=True,timeout=60)
if run.returncode: raise RuntimeError(run.stderr)
rows = [json.loads(line) for line in run.stdout.splitlines() if line.strip()]
assert len(rows) == len(CASES)
for c, row in zip(CASES, rows):
    assert c['id'] == row['id']
    if c['expected'] is not None: assert c['expected'] == row['result'], (c,row)
    c['actual'] = row['result']
member=next(c['actual'] for c in CASES if c['id']=='real-member-window')
assert member['natural30']==member['gold']==4321
assert member['wrongInclusive31']==4458 and member['extraBoundaryMembers']==137
report=dict(format='calendar-window-database-v1',readOnly=True,boundaryCases=14,
    passed=14,realDatabaseDiagnostics=3,externalProviderCalls=0,providerTokens=0,providerCostYuan=0,
    sqlSha256=hashlib.sha256(sql.encode()).hexdigest(),cases=CASES)
for name,content in [('database-review.sql',sql),('database-review.json',json.dumps(report,ensure_ascii=False,indent=2)+'\n')]:
    with (FOLDER/name).open('x',encoding='utf-8',newline='\n') as f:f.write(content)
print(json.dumps({k:v for k,v in report.items() if k!='cases'}))
