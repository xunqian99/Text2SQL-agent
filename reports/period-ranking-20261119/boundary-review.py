"""Execute SQL contract counterexamples; this does not test model compliance."""
from pathlib import Path
import json
import subprocess

FOLDER = Path(__file__).resolve().parent
CASES = []

def case(identity, sql, expected):
    CASES.append(dict(id=identity, sql=sql.strip().rstrip(';'), expected=expected, evidenceType='synthetic'))

case('sparse-row-is-not-previous-calendar-month', """
WITH m(t,n) AS(VALUES(DATE '2016-10-01',324),(DATE '2016-12-01',1)),
bad AS(SELECT t,n-LAG(n) OVER(ORDER BY t) AS delta FROM m)
SELECT json_build_array(b.delta,c.n-COALESCE(p.n,0))
FROM m c LEFT JOIN m p ON p.t=c.t-INTERVAL '1 month'
JOIN bad b ON b.t=c.t WHERE c.t=DATE '2016-12-01'
""", [-323, 1])
case('report-first-period-can-have-history', """
WITH m(t,n) AS(VALUES(DATE '2023-12-01',10),(DATE '2024-01-01',17)),
compared AS(SELECT t,n-LAG(n) OVER(ORDER BY t) AS delta FROM m)
SELECT to_json(delta) FROM compared WHERE t=DATE '2024-01-01'
""", 7)
case('partition-by-full-nontime-group', """
WITH m(g,t,n) AS(VALUES('a',DATE '2024-01-01',10),('a',DATE '2024-02-01',12),
 ('b',DATE '2024-01-01',100),('b',DATE '2024-02-01',107)),
q AS(SELECT g,t,n-LAG(n) OVER(PARTITION BY g ORDER BY t) AS d FROM m)
SELECT json_agg(json_build_array(g,d) ORDER BY g) FROM q WHERE t=DATE '2024-02-01'
""", [['a',2],['b',7]])
case('requested-periods-are-not-inner-joined-away', """
WITH months AS(SELECT generate_series(1,12) AS m),
facts(m,v) AS(VALUES(1,10),(9,90))
SELECT json_build_array(COUNT(*),COUNT(v),MAX(v) FILTER(WHERE months.m=12))
FROM months LEFT JOIN facts USING(m)
""", [12,2,None])
case('coverage-is-separate-from-event-minmax', """
WITH requested(period,complete) AS(VALUES('a',true),('b',false)),
facts(period,n) AS(SELECT 'none',1 WHERE false)
SELECT json_agg(json_build_array(r.period,CASE WHEN r.complete THEN COALESCE(f.n,0) ELSE f.n END)
 ORDER BY r.period) FROM requested r LEFT JOIN facts f USING(period)
""", [['a',0],['b',None]])
case('calendar-empty-input-no-invented-period', """
WITH m(t,n) AS(SELECT DATE '2024-01-01',1 WHERE false),
calendar AS(SELECT generate_series(MIN(t),MAX(t),INTERVAL '1 month') AS t FROM m)
SELECT to_json(COUNT(*)) FROM calendar
""", 0)
case('identity-does-not-outrank-metric', """
WITH x(id,v) AS(VALUES('z',100),('a',90),('b',90),('c',NULL::integer)),
q AS(SELECT * FROM x ORDER BY v DESC NULLS LAST,id LIMIT 2)
SELECT json_agg(id ORDER BY v DESC,id) FROM q
""", ['z','a'])
case('input-order-does-not-decide-cutoff-tie', """
WITH x(id,v) AS(VALUES('b',90),('z',100),('a',90)),
q AS(SELECT * FROM x ORDER BY v DESC,id LIMIT 2)
SELECT json_agg(id ORDER BY v DESC,id) FROM q
""", ['z','a'])
case('composite-key-breaks-within-parent-ties', """
WITH x(order_id,payment_sequential,v) AS(VALUES('b',1,24),('a',2,24),('a',1,24)),
q AS(SELECT * FROM x ORDER BY v DESC,order_id,payment_sequential LIMIT 1)
SELECT json_build_array(order_id,payment_sequential,v) FROM q
""", ['a',1,24])
case('unknown-is-not-numeric-maximum', """
WITH x(id,v) AS(VALUES('a',NULL::integer),('b',24)),
q AS(SELECT * FROM x WHERE v IS NOT NULL ORDER BY v DESC,id LIMIT 1)
SELECT to_json(id) FROM q
""", 'b')
case('all-unknown-has-no-confirmed-maximum', """
WITH x(id,v) AS(VALUES('a',NULL::integer),('b',NULL::integer)),
q AS(SELECT * FROM x WHERE v IS NOT NULL ORDER BY v DESC,id LIMIT 1)
SELECT to_json(COUNT(*)) FROM q
""", 0)
case('ranking-keys-must-exclude-display-identity', """
WITH x(id,v) AS(VALUES('a',100),('b',90),('c',90)),
ranked AS(SELECT *,RANK() OVER(ORDER BY v DESC) AS good_rank,
 RANK() OVER(ORDER BY v DESC,id) AS broken_rank FROM x)
SELECT json_build_array(COUNT(*) FILTER(WHERE good_rank<=2),COUNT(*) FILTER(WHERE broken_rank<=2))
FROM ranked
""", [3,2])
case('insufficient-candidates-are-not-padded', """
WITH x(id,v) AS(VALUES('a',1)),q AS(SELECT * FROM x ORDER BY v DESC,id LIMIT 20)
SELECT to_json(COUNT(*)) FROM q
""", 1)
case('absent-values-do-not-become-zero-means', """
WITH x(g,v) AS(VALUES('unknown',NULL::numeric),('known',10::numeric)),
groups(g) AS(VALUES('absent'),('unknown'),('known'))
SELECT json_agg(json_build_array(g,n,mean) ORDER BY g) FROM(
 SELECT gs.g,COUNT(x.g) AS n,AVG(x.v) AS mean FROM groups gs LEFT JOIN x USING(g) GROUP BY gs.g) q
""", [['absent',0,None],['known',1,10],['unknown',1,None]])

case('minimum-direction-is-ascending', '''
WITH x(id,v) AS(VALUES('a',5),('b',1)),
q AS(SELECT * FROM x WHERE v IS NOT NULL ORDER BY v ASC,id LIMIT 1)
SELECT to_json(id) FROM q
''', 'b')
case('display-precision-is-the-default-tie-precision', '''
WITH x(id,v) AS(VALUES('a',10.003::numeric),('b',10.004::numeric)),
q AS(SELECT id,ROUND(v,2) AS shown FROM x ORDER BY ROUND(v,2) DESC,id LIMIT 1)
SELECT json_build_array(id,shown) FROM q
''', ['a',10.00])
case('rank-and-dense-rank-have-different-contracts', '''
WITH x(id,v) AS(VALUES('a',100),('b',100),('c',90)),
q AS(SELECT RANK() OVER(ORDER BY v DESC) AS r,DENSE_RANK() OVER(ORDER BY v DESC) AS d FROM x)
SELECT json_build_array(COUNT(*) FILTER(WHERE r<=2),COUNT(*) FILTER(WHERE d<=2)) FROM q
''', [2,3])

sql = "BEGIN READ ONLY;\nSET LOCAL statement_timeout='8s';\n" + '\n'.join(
    f"SELECT json_build_object('id','{c['id']}','result',q.value) FROM ({c['sql']}) q(value);"
    for c in CASES) + '\nCOMMIT;\n'
run = subprocess.run(['docker','exec','-i','text2sql-postgres','psql','-X','-qAt','-v',
    'ON_ERROR_STOP=1','-U','text2sql_ro','-d','olist'],input=sql,text=True,encoding='utf-8',
    capture_output=True,timeout=45)
if run.returncode: raise RuntimeError(run.stderr)
rows = [json.loads(line) for line in run.stdout.splitlines() if line.strip()]
assert len(rows)==len(CASES)
for c,row in zip(CASES,rows):
    assert c['id']==row['id'] and c['expected']==row['result'],(c,row)
    c['actual']=row['result']
report=dict(format='period-ranking-boundaries-v1',cases=CASES,passed=len(CASES),total=len(CASES),
    providerCalls=0,providerTokens=0,providerCostYuan=0,
    limits=['Synthetic read-only SQL validates the declared contract examples, not LLM adherence or automatic AST enforcement.'])
for name,content in [('boundaries.sql',sql),('boundaries.json',json.dumps(report,ensure_ascii=False,indent=2)+'\n')]:
    with (FOLDER/name).open('x',encoding='utf-8',newline='\n') as f: f.write(content)
print(f"PASS {len(CASES)}/{len(CASES)} SQL contract boundaries; provider 0")
