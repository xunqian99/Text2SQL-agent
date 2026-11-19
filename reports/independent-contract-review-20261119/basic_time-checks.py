import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INPUT = json.loads((ROOT / 'basic_time-input.json').read_text(encoding='utf-8-sig'))
CHECKS = []

def check(key, sql):
    CHECKS.append({'key': key, 'sql': sql.strip().rstrip(';')})

for item in INPUT['items']:
    check(item['id'] + ':gold', item['gold_sql'])

check('schema', """
SELECT c.table_name, c.column_name, c.data_type, c.is_nullable,
       col_description(('public.' || c.table_name)::regclass,c.ordinal_position) AS comment
FROM information_schema.columns c
WHERE c.table_schema='public' AND c.table_name IN
('orders','order_items','customers','sellers','order_reviews','order_payments','products',
 'members','member_levels','warehouses','refunds','coupons','ticket_categories',
 'inventory_movements','regions','campaigns','inventory','shipments','support_tickets',
 'settlements','search_logs') ORDER BY c.table_name,c.ordinal_position
""")
check('constraints', """
SELECT c.relname AS table_name, con.conname, pg_get_constraintdef(con.oid) AS definition
FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid
JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN
('orders','order_items','customers','sellers','order_reviews','order_payments','products',
 'members','member_levels','warehouses','refunds','coupons','ticket_categories',
 'inventory_movements','regions','campaigns','inventory','shipments','support_tickets',
 'settlements','search_logs') ORDER BY c.relname,con.conname
""")
check('basic-identity-nulls', """
SELECT
 (SELECT COUNT(*) FROM customers) AS customer_rows,
 (SELECT COUNT(DISTINCT customer_unique_id) FROM customers) AS customer_people,
 (SELECT COUNT(*) FROM customers WHERE customer_state='SP') AS sp_rows,
 (SELECT COUNT(DISTINCT customer_unique_id) FROM customers WHERE customer_state='SP') AS sp_people,
 (SELECT COUNT(*) FROM products WHERE product_category_name IS NULL) AS null_categories,
 (SELECT COUNT(*) FROM products WHERE product_category_name='') AS empty_categories,
 (SELECT COUNT(*) FROM order_payments WHERE payment_installments IS NULL) AS null_installments,
 (SELECT COUNT(*) FROM order_reviews WHERE review_score IS NULL) AS null_review_scores,
 (SELECT COUNT(*) FROM search_logs WHERE keyword IS NULL) AS null_keywords
""")
check('T1-012:cutoff', """
WITH ranked AS (SELECT price, RANK() OVER(ORDER BY price DESC) AS r FROM order_items),
cutoff AS (SELECT MIN(price) AS p FROM (SELECT price FROM order_items ORDER BY price DESC LIMIT 10) t)
SELECT p AS tenth_price, (SELECT COUNT(*) FROM order_items WHERE price>p) AS above,
(SELECT COUNT(*) FROM order_items WHERE price=p) AS tied_at_cutoff FROM cutoff
""")
check('T1-016:ties', """
SELECT payment_installments, COUNT(*) AS payments, COUNT(DISTINCT order_id) AS orders
FROM order_payments WHERE payment_installments=(SELECT MAX(payment_installments) FROM order_payments)
GROUP BY payment_installments
""")
check('T1-016:null-counterexample', """
WITH payments(order_id,payment_sequential,payment_installments) AS
 (VALUES ('a',1,24),('b',1,NULL::int))
SELECT order_id,payment_installments FROM payments ORDER BY payment_installments DESC LIMIT 1
""")
check('T2-004:denominator', """
WITH per_order AS (SELECT o.order_id,COUNT(oi.order_item_id) AS n FROM orders o
LEFT JOIN order_items oi ON oi.order_id=o.order_id GROUP BY o.order_id)
SELECT COUNT(*) AS all_orders,COUNT(*) FILTER(WHERE n=0) AS zero_item_orders,
 SUM(n) AS items,ROUND(AVG(n),2) AS all_order_avg,
 ROUND(AVG(n) FILTER(WHERE n>0),2) AS fact_only_avg FROM per_order
""")
check('T2-008:cutoff', """
WITH counts AS (SELECT customer_state,COUNT(*) AS n FROM customers GROUP BY 1),
cutoff AS (SELECT MIN(n) AS n FROM (SELECT n FROM counts ORDER BY n DESC LIMIT 5) q)
SELECT n AS fifth_count,(SELECT COUNT(*) FROM counts c WHERE c.n>cutoff.n) AS above,
(SELECT COUNT(*) FROM counts c WHERE c.n=cutoff.n) AS tied FROM cutoff
""")
check('T2-011:cutoff', """
WITH counts AS (SELECT seller_id,ROUND(SUM(price),2) AS n FROM order_items GROUP BY 1),
cutoff AS (SELECT MIN(n) AS n FROM (SELECT n FROM counts ORDER BY n DESC LIMIT 10) q)
SELECT n AS tenth_amount,(SELECT COUNT(*) FROM counts c WHERE c.n>cutoff.n) AS above,
(SELECT COUNT(*) FROM counts c WHERE c.n=cutoff.n) AS tied FROM cutoff
""")
check('T2-015:coverage', """
SELECT l.level_id,COUNT(m.member_id) AS members,COUNT(m.points) AS known_points
FROM member_levels l LEFT JOIN members m ON m.level_id=l.level_id GROUP BY 1 ORDER BY 1
""")
check('T2-024-026:warehouse-coverage', """
SELECT w.warehouse_id,COUNT(i.product_id) AS inventory_rows,SUM(i.quantity) AS quantity,
COUNT(i.quantity) AS known_quantities FROM warehouses w LEFT JOIN inventory i
ON i.warehouse_id=w.warehouse_id GROUP BY 1 ORDER BY 1
""")
check('T2-024-026:zero-counterexample', """
WITH warehouses(warehouse_id) AS (VALUES(1),(2)),
 inventory(warehouse_id,product_id,quantity) AS (VALUES(1,'p',3))
SELECT w.warehouse_id,COUNT(i.product_id) AS inventory_rows,COALESCE(SUM(i.quantity),0) AS quantity
FROM warehouses w LEFT JOIN inventory i ON i.warehouse_id=w.warehouse_id GROUP BY 1 ORDER BY 1
""")
check('T2-022:status', """
SELECT status,COUNT(*) AS n,ROUND(SUM(refund_amount),2) AS amount,
COUNT(*) FILTER(WHERE completed_at IS NOT NULL) AS completed_time FROM refunds GROUP BY 1 ORDER BY 1
""")
check('T2-033:status', """
SELECT status,COUNT(*) AS n,ROUND(SUM(net_amount),2) AS amount,
COUNT(*) FILTER(WHERE paid_at IS NOT NULL) AS paid_time FROM settlements GROUP BY 1 ORDER BY 1
""")
check('T2-035:cutoff', """
WITH counts AS (SELECT keyword,COUNT(*) AS n FROM search_logs GROUP BY 1),
cutoff AS (SELECT MIN(n) AS n FROM (SELECT n FROM counts ORDER BY n DESC LIMIT 10) q)
SELECT n AS tenth_count,(SELECT COUNT(*) FROM counts c WHERE c.n>cutoff.n) AS above,
(SELECT COUNT(*) FROM counts c WHERE c.n=cutoff.n) AS tied FROM cutoff
""")
check('T2-037:state-comparison', """
WITH customer_counts AS (SELECT customer_state,COUNT(*) AS n FROM customers GROUP BY 1),
order_counts AS (SELECT c.customer_state,COUNT(*) AS n FROM orders o
 JOIN customers c ON c.customer_id=o.customer_id GROUP BY 1)
SELECT c.customer_state,c.n AS customer_rows,o.n AS order_rows
FROM customer_counts c FULL JOIN order_counts o USING(customer_state) ORDER BY customer_state
""")
check('T2-037:multiplicity', """
SELECT (SELECT COUNT(*) FROM customers c WHERE NOT EXISTS(SELECT 1 FROM orders o WHERE o.customer_id=c.customer_id)) AS no_order_customers,
 (SELECT COUNT(*) FROM (SELECT customer_id FROM orders GROUP BY 1 HAVING COUNT(*)<>1) q) AS multi_order_keys
""")
check('T2-037:counterexample', """
WITH customers(customer_id,customer_state) AS (VALUES('c','SP')),
orders(order_id,customer_id) AS (SELECT 'o'||i,'c' FROM generate_series(1,5001) AS i)
SELECT c.customer_state,COUNT(DISTINCT c.customer_id) AS customer_count,COUNT(o.order_id) AS order_count
FROM customers c JOIN orders o ON o.customer_id=c.customer_id GROUP BY 1 HAVING COUNT(o.order_id)>5000
""")
check('T2-039:empty-counterexample', """
WITH reviews(review_score) AS (SELECT 4 WHERE FALSE)
SELECT ROUND(COUNT(*) FILTER(WHERE review_score>=4)::numeric / COUNT(*),4) AS good_rate FROM reviews
""")
check('T3:time-coverage', """
SELECT 'orders' AS source,MIN(order_purchase_timestamp) AS min_ts,MAX(order_purchase_timestamp) AS max_ts,COUNT(*) FILTER(WHERE order_purchase_timestamp IS NULL) AS nulls FROM orders
UNION ALL SELECT 'refunds',MIN(requested_at),MAX(requested_at),COUNT(*) FILTER(WHERE requested_at IS NULL) FROM refunds
UNION ALL SELECT 'tickets',MIN(created_at),MAX(created_at),COUNT(*) FILTER(WHERE created_at IS NULL) FROM support_tickets
UNION ALL SELECT 'members',MIN(register_date),MAX(register_date),COUNT(*) FILTER(WHERE register_date IS NULL) FROM members
UNION ALL SELECT 'settlements',MIN(period_start),MAX(period_start),COUNT(*) FILTER(WHERE period_start IS NULL) FROM settlements
""")
check('T3-005:ties', """
WITH c AS (SELECT DATE_TRUNC('week',order_purchase_timestamp) AS w,COUNT(*) AS n FROM orders GROUP BY 1)
SELECT w,n FROM c WHERE n=(SELECT MAX(n) FROM c) ORDER BY w
""")
check('T3-007:zero-months', """
SELECT DATE_TRUNC('month',o.order_purchase_timestamp) AS m,COUNT(DISTINCT o.order_id) AS orders,
COUNT(oi.order_id) AS item_rows FROM orders o LEFT JOIN order_items oi ON oi.order_id=o.order_id
GROUP BY 1 HAVING COUNT(oi.order_id)=0 ORDER BY 1
""")
check('T3-010:gaps', """
WITH monthly AS (SELECT DATE_TRUNC('month',order_purchase_timestamp) AS m,COUNT(*) AS n FROM orders GROUP BY 1),
compared AS (SELECT m,n,LAG(m) OVER(ORDER BY m) AS previous_m,LAG(n) OVER(ORDER BY m) AS previous_n FROM monthly)
SELECT *,n-previous_n AS gold_delta,n AS calendar_zero_delta FROM compared WHERE m>previous_m+INTERVAL '1 month'
""")
check('T3-009-012:year-months', """
SELECT EXTRACT(YEAR FROM order_purchase_timestamp)::int AS y,
EXTRACT(MONTH FROM order_purchase_timestamp)::int AS m,COUNT(*) AS n
FROM orders WHERE order_purchase_timestamp >= '2017-01-01' AND order_purchase_timestamp < '2019-01-01'
GROUP BY 1,2 ORDER BY 1,2
""")
check('T3-014:window-difference', """
WITH anchor AS(SELECT MAX(order_purchase_timestamp) AS a FROM orders),flags AS(
SELECT o.order_id,oi.price,o.order_purchase_timestamp,a.a,
(o.order_purchase_timestamp>a.a-INTERVAL '90 days' AND o.order_purchase_timestamp<=a.a) AS rolling,
(o.order_purchase_timestamp>=a.a::date-INTERVAL '89 days' AND o.order_purchase_timestamp<a.a::date+INTERVAL '1 day') AS calendar
FROM orders o JOIN order_items oi ON oi.order_id=o.order_id CROSS JOIN anchor a)
SELECT MAX(a) AS anchor,MIN(a-INTERVAL '90 days') AS rolling_lower,
MIN(a::date-INTERVAL '89 days') AS calendar_lower,
COUNT(*) FILTER(WHERE rolling<>calendar) AS changed_items,
COUNT(DISTINCT order_id) FILTER(WHERE rolling<>calendar) AS changed_orders,
ROUND(SUM(price) FILTER(WHERE rolling),2) AS rolling_sales,
ROUND(SUM(price) FILTER(WHERE calendar),2) AS calendar_sales FROM flags
""")
check('T3-016:ties', """
WITH c AS(SELECT EXTRACT(DOW FROM order_purchase_timestamp)::int AS d,COUNT(*) AS n FROM orders GROUP BY 1)
SELECT d,n FROM c WHERE n=(SELECT MAX(n) FROM c)
""")
check('T3-018:quality', """
SELECT COUNT(*) FILTER(WHERE order_delivered_customer_date IS NOT NULL) AS delivered_time,
COUNT(*) FILTER(WHERE order_delivered_customer_date IS NOT NULL AND order_purchase_timestamp IS NULL) AS missing_start,
COUNT(*) FILTER(WHERE order_delivered_customer_date<order_purchase_timestamp) AS negative,
pg_typeof(EXTRACT(EPOCH FROM INTERVAL '1 day'))::text AS extract_type,
ROUND(AVG(EXTRACT(EPOCH FROM(order_delivered_customer_date-order_purchase_timestamp))/86400),2) AS avg_days FROM orders
""")
check('session-and-paid-bases', """
SELECT version() AS server_version,current_setting('TimeZone') AS session_timezone,
 (SELECT ROUND(SUM(net_amount),2) FROM settlements WHERE status=3) AS status3_paid_net,
 (SELECT ROUND(SUM(net_amount),2) FROM settlements WHERE paid_at IS NOT NULL) AS timestamp_paid_net,
 (SELECT COUNT(*) FROM settlements WHERE (status=3) IS DISTINCT FROM (paid_at IS NOT NULL)) AS status_time_disagreements,
 pg_typeof(DATE_TRUNC('month',DATE '2018-01-10'))::text AS date_month_type,
 pg_typeof(DATE_TRUNC('month',TIMESTAMP '2018-01-10'))::text AS timestamp_month_type
""")
check('T2-039:empty-safe-control', """
WITH reviews(review_score) AS (SELECT 4 WHERE FALSE)
SELECT ROUND(COUNT(*) FILTER(WHERE review_score>=4)::numeric / NULLIF(COUNT(*),0),4) AS good_rate FROM reviews
""")
check('T2-004:proposed-gold', """
SELECT ROUND(AVG(cnt),2) FROM (
 SELECT o.order_id,COUNT(oi.order_item_id) AS cnt
 FROM orders o LEFT JOIN order_items oi ON oi.order_id=o.order_id
 GROUP BY o.order_id
) t
""")
check('T3-010:proposed-gold', """
WITH monthly AS (
 SELECT DATE_TRUNC('month',order_purchase_timestamp) AS m,COUNT(*) AS cnt
 FROM orders GROUP BY 1
), calendar AS (
 SELECT generate_series(MIN(m),MAX(m),INTERVAL '1 month') AS m FROM monthly
), filled AS (
 SELECT c.m,COALESCE(m.cnt,0) AS cnt FROM calendar c LEFT JOIN monthly m ON m.m=c.m
)
SELECT m AS month,cnt,cnt-LAG(cnt) OVER(ORDER BY m) AS mom_change FROM filled ORDER BY m
""")
check('T3-014:proposed-gold', """
WITH anchor AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders)
SELECT DATE_TRUNC('week',o.order_purchase_timestamp)::date AS week_start,
 ROUND(SUM(oi.price),2) AS sales
FROM orders o JOIN order_items oi ON oi.order_id=o.order_id CROSS JOIN anchor a
WHERE o.order_purchase_timestamp>=a.mx::date-INTERVAL '89 days'
 AND o.order_purchase_timestamp<a.mx::date+INTERVAL '1 day'
GROUP BY 1 ORDER BY 1
""")
check('T2-037:proposed-gold', """
SELECT c.customer_state,COUNT(*) AS cnt FROM orders o
JOIN customers c ON c.customer_id=o.customer_id
GROUP BY c.customer_state HAVING COUNT(*)>5000 ORDER BY cnt DESC
""")
check('T2-039:proposed-gold', """
SELECT ROUND(COUNT(*) FILTER(WHERE review_score>=4)::numeric/NULLIF(COUNT(*),0),4) AS good_rate
FROM order_reviews
""")

def execute(sql):
    wrapped = "BEGIN READ ONLY;\nSET LOCAL statement_timeout='8s';\n" + sql + ";\nROLLBACK;\n"
    p = subprocess.run(['docker','exec','-i','text2sql-postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','text2sql_ro','-d','olist'],input=wrapped,text=True,encoding='utf-8',capture_output=True)
    if p.returncode:
        return {'exitCode':p.returncode,'error':p.stderr.strip()}
    return {'exitCode':0,'rows':json.loads(p.stdout.strip())}

selected = set(sys.argv[1:])
existing_path=ROOT/'basic_time-query-results.json'
RESULTS=json.loads(existing_path.read_text(encoding='utf-8')) if selected and existing_path.exists() else []
for c in CHECKS:
    if selected and c['key'] not in selected:
        continue
    payload = "SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::jsonb) FROM ("+c['sql']+") q"
    RESULTS=[r for r in RESULTS if r['key'] != c['key']]
    RESULTS.append({**c, 'result':execute(payload)})
    print(c['key'],json.dumps(RESULTS[-1]['result'],ensure_ascii=False)[:250])
(ROOT/'basic_time-query-results.json').write_text(json.dumps(RESULTS,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assert len(INPUT['items']) == 56
