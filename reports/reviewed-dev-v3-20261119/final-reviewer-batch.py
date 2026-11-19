from pathlib import Path
import hashlib
import json
import subprocess
import time
import yaml

BASE = Path(r'C:\Users\xunqian\Desktop\agent')
OUT = BASE / 'reports/reviewed-dev-v3-20261119'
PREFIX = OUT / 'final-reviewer'
files = sorted((BASE / 'data/benchmarks/olist-v3-dev-candidate').glob('*.yaml'))
cases = []
for f in files:
    for case in yaml.safe_load(f.read_text(encoding='utf-8')):
        case['source_file'] = str(f.relative_to(BASE)).replace('\\', '/')
        cases.append(case)
assert len(cases) == 100 and len({x['id'] for x in cases}) == 100
contract_files = [BASE / p for p in [
    'src/main/java/com/text2sql/agent/generation/PromptTemplate.java',
    'src/main/resources/schema/metrics.yml',
    'src/main/resources/schema/glossary.yml',
    'src/main/resources/schema/ambiguities.yml',
    'data/schema/01_olist_schema.sql',
    'data/schema/03_extend_schema.sql',
]]
manifest = {str(p.relative_to(BASE)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in files + contract_files}
manifest['gold_sql_sha256_by_id'] = {c['id']: hashlib.sha256(c['gold_sql'].encode()).hexdigest() for c in cases}
Path(str(PREFIX) + '-input-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
Path(str(PREFIX) + '-input-cases.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding='utf-8')

risks = {
 'R00-runtime': """SELECT current_date, current_user, version(), current_setting('transaction_read_only') AS read_only,
 current_setting('TimeZone') AS timezone, current_setting('statement_timeout') AS timeout""",
 'R01-actual-ddl': """SELECT c.relname AS table_name,
 (SELECT json_agg(json_build_object('name',a.attname,'type',format_type(a.atttypid,a.atttypmod),'not_null',a.attnotnull) ORDER BY a.attnum)
 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped) AS columns,
 (SELECT json_agg(json_build_object('name',x.conname,'definition',pg_get_constraintdef(x.oid)) ORDER BY x.conname)
 FROM pg_constraint x WHERE x.conrelid=c.oid) AS constraints
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relkind='r'
 AND c.relname IN ('customers','sellers','products','orders','order_items','order_payments','order_reviews','members','member_levels','user_tags','user_tag_map','regions','campaigns','coupons','coupon_usages','campaign_products','referrals','warehouses','shipments','shipment_tracking','inventory','inventory_movements','support_tickets','ticket_categories','ticket_messages','refunds','refund_items','settlements','settlement_items','brands','product_brand_map','product_price_history','search_logs')
 ORDER BY c.relname""",
 'R02-topn-boundaries': """WITH scores(case_id,identity,score,k) AS(
 SELECT 'T1-013',order_id||':'||order_item_id,freight_value,5 FROM order_items
 UNION ALL SELECT 'T2-009',product_category_name,COUNT(*)::numeric,10 FROM products WHERE product_category_name IS NOT NULL GROUP BY product_category_name
 UNION ALL SELECT 'T2-012',seller_id,ROUND(SUM(freight_value),2),10 FROM order_items GROUP BY seller_id
 UNION ALL SELECT 'T3-015',EXTRACT(HOUR FROM order_purchase_timestamp)::int::text,COUNT(*)::numeric,1 FROM orders GROUP BY 2
 UNION ALL SELECT 'T4-005',seller_id,COUNT(DISTINCT order_id)::numeric,10 FROM order_items GROUP BY seller_id
 UNION ALL SELECT 'T4-030',seller_id,ROUND(SUM(total_amount),2),10 FROM settlements GROUP BY seller_id
 UNION ALL SELECT 'T6-009',c.customer_state,ROUND(100.0*COUNT(*) FILTER(WHERE r.review_score<=2)/COUNT(*),2),10 FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_state
 ),ranked AS(SELECT *,ROW_NUMBER() OVER(PARTITION BY case_id ORDER BY score DESC NULLS LAST,identity ASC NULLS LAST) AS rn FROM scores),cuts AS(SELECT case_id,MAX(k) AS k,MAX(score) FILTER(WHERE rn=k) AS boundary FROM ranked GROUP BY case_id)
 SELECT c.case_id,c.k,c.boundary,COUNT(*) FILTER(WHERE s.score>c.boundary) AS above_boundary,COUNT(*) FILTER(WHERE s.score=c.boundary) AS at_boundary,COUNT(*) FILTER(WHERE s.score IS NULL) AS unknown_scores,
 (SELECT json_agg(x) FROM(SELECT r.score,COUNT(*) AS tied_candidates FROM ranked r WHERE r.case_id=c.case_id AND r.score>=c.boundary GROUP BY r.score HAVING COUNT(*)>1 ORDER BY r.score DESC)x) AS ties_relevant_to_topn
 FROM cuts c JOIN scores s USING(case_id) GROUP BY c.case_id,c.k,c.boundary ORDER BY c.case_id""",
 'R03-join-coverage': """SELECT 'warehouse_missing_region' AS risk,COUNT(*) AS rows,SUM(w.capacity)::numeric AS amount FROM warehouses w LEFT JOIN regions r ON r.region_code=w.region_code WHERE r.region_code IS NULL
 UNION ALL SELECT 'shipments_missing_order',COUNT(*),NULL FROM shipments s LEFT JOIN orders o ON o.order_id=s.order_id WHERE o.order_id IS NULL
 UNION ALL SELECT 'tracked_shipments_missing_order',COUNT(DISTINCT s.shipment_id),NULL FROM shipment_tracking t JOIN shipments s ON s.shipment_id=t.shipment_id LEFT JOIN orders o ON o.order_id=s.order_id WHERE o.order_id IS NULL
 UNION ALL SELECT 'tracking_missing_shipment',COUNT(*),NULL FROM shipment_tracking t LEFT JOIN shipments s ON s.shipment_id=t.shipment_id WHERE s.shipment_id IS NULL
 UNION ALL SELECT 'unbranded_order_items',COUNT(*),SUM(oi.price) FROM order_items oi WHERE NOT EXISTS(SELECT 1 FROM product_brand_map bm WHERE bm.product_id=oi.product_id AND bm.is_current IS TRUE)
 UNION ALL SELECT 'multiple_current_brands',COUNT(*),NULL FROM(SELECT product_id FROM product_brand_map WHERE is_current IS TRUE GROUP BY product_id HAVING COUNT(*)>1)x
 UNION ALL SELECT 'brandmap_missing_product',COUNT(*),NULL FROM product_brand_map bm LEFT JOIN products p ON p.product_id=bm.product_id WHERE p.product_id IS NULL
 UNION ALL SELECT 'brands_without_known_valid_sales',COUNT(*),NULL FROM brands b WHERE NOT EXISTS(SELECT 1 FROM product_brand_map bm JOIN order_items oi ON oi.product_id=bm.product_id JOIN orders o ON o.order_id=oi.order_id WHERE bm.brand_id=b.brand_id AND bm.is_current IS TRUE AND o.order_status NOT IN('canceled','unavailable'))""",
 'R04-relative-login': """SELECT MIN(last_login_date) AS min_login,MAX(last_login_date) AS reference_day,
 COUNT(*) FILTER(WHERE last_login_date>(SELECT MAX(last_login_date) FROM members)-30) AS max_anchored,
 COUNT(*) FILTER(WHERE last_login_date>=current_date-29 AND last_login_date<current_date+1) AS current_anchored,
 COUNT(*) FILTER(WHERE last_login_date IS NULL) AS unknown_login FROM members""",
 'R05-calendar-gaps': """WITH months AS(SELECT DATE_TRUNC('month',order_purchase_timestamp) AS m,COUNT(*) AS n FROM orders WHERE order_purchase_timestamp IS NOT NULL GROUP BY 1),cal AS(SELECT generate_series(MIN(m),MAX(m),INTERVAL '1 month') AS m FROM months) SELECT c.m,mo.n FROM cal c LEFT JOIN months mo USING(m) WHERE mo.n IS NULL ORDER BY c.m""",
 'R06-fact-grains': """SELECT 'orders' AS fact,COUNT(*) AS rows,COUNT(DISTINCT order_id) AS distinct_ids FROM orders
 UNION ALL SELECT 'reviews',COUNT(*),COUNT(DISTINCT order_id) FROM order_reviews
 UNION ALL SELECT 'payments',COUNT(*),COUNT(DISTINCT order_id) FROM order_payments
 UNION ALL SELECT 'members_no_valid_order',COUNT(*),NULL FROM members m WHERE NOT EXISTS(SELECT 1 FROM customers c JOIN orders o ON o.customer_id=c.customer_id WHERE c.customer_unique_id=m.customer_unique_id AND o.order_status NOT IN('canceled','unavailable'))
 UNION ALL SELECT 'shipments_no_tracking',COUNT(*),NULL FROM shipments s WHERE NOT EXISTS(SELECT 1 FROM shipment_tracking t WHERE t.shipment_id=s.shipment_id)
 UNION ALL SELECT 'coupons_multiple_use_records_per_order',COUNT(*),NULL FROM(SELECT order_id FROM coupon_usages GROUP BY order_id HAVING COUNT(*)>1)x
 UNION ALL SELECT 'null_movement_quantity',COUNT(*),NULL FROM inventory_movements WHERE quantity IS NULL
 UNION ALL SELECT 'null_settlement_amount',COUNT(*),NULL FROM settlements WHERE total_amount IS NULL
 UNION ALL SELECT 'null_order_timestamp',COUNT(*),NULL FROM orders WHERE order_purchase_timestamp IS NULL
 UNION ALL SELECT 'null_customer_identity',COUNT(*),NULL FROM customers WHERE customer_unique_id IS NULL""",
 'R07-review-unit-difference': """WITH per_state AS(SELECT c.customer_state,COUNT(*) AS reviews,COUNT(DISTINCT o.order_id) AS reviewed_orders,
 COUNT(*) FILTER(WHERE r.review_score<=2) AS low_reviews,COUNT(DISTINCT o.order_id) FILTER(WHERE r.review_score<=2) AS low_orders
 FROM order_reviews r JOIN orders o ON o.order_id=r.order_id JOIN customers c ON c.customer_id=o.customer_id WHERE o.order_status NOT IN('canceled','unavailable') GROUP BY c.customer_state)
 SELECT *,ROUND(100.0*low_reviews/reviews,2) AS review_rate,ROUND(100.0*low_orders/reviewed_orders,2) AS reviewed_order_rate FROM per_state WHERE ROUND(100.0*low_reviews/reviews,2)<>ROUND(100.0*low_orders/reviewed_orders,2) ORDER BY customer_state""",
 'R08-consumption-components': """WITH payment_totals AS(SELECT order_id,SUM(payment_value) AS paid FROM order_payments GROUP BY order_id),item_totals AS(SELECT order_id,SUM(price) AS merchandise,SUM(price+freight_value) AS gmv FROM order_items GROUP BY order_id) SELECT m.level_id,ROUND(SUM(i.merchandise),2) AS merchandise_all_status,ROUND(SUM(i.gmv),2) AS with_freight_all_status,ROUND(SUM(p.paid),2) AS paid_all_status FROM orders o JOIN customers c ON c.customer_id=o.customer_id JOIN members m ON m.customer_unique_id=c.customer_unique_id LEFT JOIN item_totals i ON i.order_id=o.order_id LEFT JOIN payment_totals p ON p.order_id=o.order_id GROUP BY m.level_id ORDER BY m.level_id""",
 'R09-window90': """WITH a AS(SELECT MAX(order_purchase_timestamp) AS mx FROM orders) SELECT a.mx,COUNT(*) FILTER(WHERE o.order_purchase_timestamp>=a.mx::date-INTERVAL '89 days' AND o.order_purchase_timestamp<a.mx::date+INTERVAL '1 day') AS natural_90,COUNT(*) FILTER(WHERE o.order_purchase_timestamp>a.mx-INTERVAL '90 days' AND o.order_purchase_timestamp<=a.mx) AS rolling_90 FROM orders o CROSS JOIN a GROUP BY a.mx""",
 'R10-fixture-topn-tie': """WITH order_items(order_id,order_item_id,freight_value) AS(VALUES('b',1,10.00::numeric),('a',2,10.00),('a',1,10.00)),chosen AS(SELECT order_id,order_item_id,freight_value FROM order_items ORDER BY freight_value DESC LIMIT 2) SELECT * FROM chosen""",
 'R11-fixture-warehouse-loss': """WITH warehouses(warehouse_id,region_code,capacity) AS(VALUES(1,NULL::text,100)),regions(region_code,macro_region) AS(VALUES('SP','Southeast')),candidate AS(SELECT r.macro_region,SUM(w.capacity) AS total_capacity FROM warehouses w JOIN regions r ON r.region_code=w.region_code GROUP BY r.macro_region) SELECT 'input_warehouses' AS measure,(SELECT COUNT(*) FROM warehouses) AS n UNION ALL SELECT 'gold_output_groups',COUNT(*) FROM candidate""",
 'R12-fixture-tracked-shipment-loss': """WITH shipment_tracking(tracking_id,shipment_id,event_code) AS(VALUES(1,1,'DELIVERED')),shipments(shipment_id,order_id) AS(VALUES(1,'missing')),orders(order_id,customer_id) AS(SELECT NULL::text,NULL::text WHERE FALSE),customers(customer_id,customer_state) AS(SELECT NULL::text,NULL::text WHERE FALSE),candidate AS(SELECT st.event_code,c.customer_state,COUNT(DISTINCT s.shipment_id) AS shipments FROM shipment_tracking st JOIN shipments s ON s.shipment_id=st.shipment_id JOIN orders o ON o.order_id=s.order_id JOIN customers c ON c.customer_id=o.customer_id GROUP BY st.event_code,c.customer_state) SELECT 'input_tracked_shipments' AS measure,COUNT(*) AS n FROM shipments UNION ALL SELECT 'gold_output_groups',COUNT(*) FROM candidate""",
 'R13-fixture-unknown-outbound': """WITH inventory_movements(warehouse_id,quantity,movement_type,moved_at) AS(VALUES(1,NULL::int,2,TIMESTAMP '2018-01-30 10:00:00')),outbound AS(SELECT warehouse_id,COUNT(*) AS records,SUM(quantity) AS out_qty FROM inventory_movements WHERE movement_type=2 GROUP BY warehouse_id) SELECT warehouse_id,records,out_qty AS known_quantity_sum,COALESCE(out_qty,0) AS candidate_quantity FROM outbound""",
 'R14-fixture-unknown-settlement-ranking': """WITH settlements(seller_id,total_amount) AS(VALUES('unknown',NULL::numeric),('known',100.00::numeric)) SELECT seller_id,ROUND(SUM(total_amount),2) AS total FROM settlements GROUP BY seller_id ORDER BY total DESC LIMIT 1""",
}

def wrap(case_id, sql, kind):
    inner = sql.strip().rstrip(';')
    label = case_id.replace("'", "''")
    return f"SELECT json_build_object('kind','{kind}','id','{label}','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM ({inner})q));\n"

statements = ["BEGIN READ ONLY;\nSET LOCAL statement_timeout='8s';\n"]
statements += [wrap(c['id'],c['gold_sql'],'gold') for c in cases]
statements += [wrap(k,v,'risk') for k,v in risks.items()]
statements += ['ROLLBACK;\n']
sql = ''.join(statements)
Path(str(PREFIX) + '-batch.sql').write_text(sql,encoding='utf-8')
started=time.monotonic()
result=subprocess.run(['docker','exec','-i','text2sql-postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','text2sql_ro','-d','olist'],input=sql.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
elapsed=time.monotonic()-started
Path(str(PREFIX) + '-batch-output.jsonl').write_bytes(result.stdout)
Path(str(PREFIX) + '-batch-stderr.txt').write_bytes(result.stderr)
if result.returncode:
    print(result.stderr.decode('utf-8',errors='replace'))
    raise SystemExit(result.returncode)
records=[json.loads(line) for line in result.stdout.decode('utf-8').splitlines() if line.strip()]
assert len(records)==100+len(risks),len(records)
summary={'gold_executed':sum(r['kind']=='gold' for r in records),'risk_queries':len(risks),'batch_seconds':round(elapsed,3),'readonly':True,'provider_calls':0,'sql_sha256':hashlib.sha256(sql.encode()).hexdigest()}
Path(str(PREFIX) + '-execution-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
print(json.dumps(summary))
for r in records:
    if r['kind']=='gold':
        print(r['id'],len(r['rows']))
    elif r['id']!='R01-actual-ddl':
        print(json.dumps(r,ensure_ascii=False))
