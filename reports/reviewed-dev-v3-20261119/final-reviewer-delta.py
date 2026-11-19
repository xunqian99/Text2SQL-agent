from pathlib import Path
import hashlib,json,subprocess,time,yaml

BASE=Path(r'C:\Users\xunqian\Desktop\agent')
OUT=BASE/'reports/reviewed-dev-v3-20261119'
old={x['id']:x for x in json.loads((OUT/'final-reviewer-input-cases.json').read_text(encoding='utf-8'))}
current=[]
files=sorted((BASE/'data/benchmarks/olist-v3-dev-candidate').glob('*.yaml'))
for f in files:
    for c in yaml.safe_load(f.read_text(encoding='utf-8')):
        c['source_file']=str(f.relative_to(BASE)).replace('\\','/')
        current.append(c)
by_id={x['id']:x for x in current}
changed=[x for x in current if x['gold_sql']!=old[x['id']]['gold_sql']]
question_changes=[x['id'] for x in current if x['question']!=old[x['id']]['question']]
assert len(current)==100

def wrap(case_id,sql,kind):
    return "SELECT json_build_object('kind','%s','id','%s','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM (%s)q));\n"%(kind,case_id,sql.strip().rstrip(';'))

def fixture(case_id,ctes):
    sql=by_id[case_id]['gold_sql'].strip().rstrip(';')
    if sql[:5].upper()=='WITH ':
        sql=sql[5:]
        return 'WITH '+ctes+','+sql
    return 'WITH '+ctes+' '+sql

fixtures={
 'F01-complete-item-identity':fixture('T1-013',"order_items(order_id,order_item_id,freight_value) AS(VALUES('b',1,10.00::numeric),('a',2,10.00),('a',1,10.00),('a',3,10.00),('a',4,10.00),('a',5,10.00))"),
 'F02-unknown-warehouse-region':fixture('T4-025',"warehouses(warehouse_id,region_code,capacity) AS(VALUES(1,NULL::text,100)),regions(region_code,macro_region) AS(VALUES('SP','Southeast'))"),
 'F03-missing-shipment-order':fixture('T5-015',"shipment_tracking(tracking_id,shipment_id,event_code) AS(VALUES(1,1,'DELIVERED'),(2,2,'DELIVERED'),(3,1,'DELIVERED')),shipments(shipment_id,order_id) AS(VALUES(1,'missing')),orders(order_id,customer_id) AS(SELECT NULL::text,NULL::text WHERE FALSE),customers(customer_id,customer_state) AS(SELECT NULL::text,NULL::text WHERE FALSE)"),
 'F04-no-outbound-vs-unknown':fixture('T6-023',"warehouses(warehouse_id) AS(VALUES(1),(2),(3)),inventory(warehouse_id,quantity) AS(VALUES(1,100),(2,50),(3,20)),inventory_movements(warehouse_id,quantity,movement_type,moved_at) AS(VALUES(1,NULL::int,2,TIMESTAMP '2018-05-01 12:00:00'),(2,5,1,TIMESTAMP '2018-05-01 12:00:00'),(3,10,2,TIMESTAMP '2018-05-01 12:00:00'))"),
 'F05-missing-reference-date':fixture('T6-023',"warehouses(warehouse_id) AS(VALUES(1),(2)),inventory(warehouse_id,quantity) AS(VALUES(1,100),(2,50)),inventory_movements(warehouse_id,quantity,movement_type,moved_at) AS(VALUES(1,5,2,NULL::timestamp))"),
 'F06-unknown-settlement-candidate':fixture('T4-030',"settlements(seller_id,total_amount) AS(VALUES('unknown',NULL::numeric),('known',100.00::numeric))"),
 'F07-rounded-score-identity':fixture('T6-009',"customers(customer_id,customer_state) AS(VALUES('a','A'),('b','B')),orders(order_id,customer_id,order_status) AS(VALUES('a','a','delivered'),('b','b','delivered')),order_reviews(order_id,review_score) AS(SELECT 'a',CASE WHEN n<=5000 THEN 1 ELSE 5 END FROM generate_series(1,50000)n UNION ALL SELECT 'b',CASE WHEN n<=5001 THEN 1 ELSE 5 END FROM generate_series(1,50000)n)"),
}
sql="BEGIN READ ONLY;\nSET LOCAL statement_timeout='8s';\n"+''.join(wrap(x['id'],x['gold_sql'],'changed-gold') for x in changed)+''.join(wrap(k,v,'fixture') for k,v in fixtures.items())+'ROLLBACK;\n'
(OUT/'final-reviewer-delta.sql').write_text(sql,encoding='utf-8')
t=time.monotonic()
r=subprocess.run(['docker','exec','-i','text2sql-postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','text2sql_ro','-d','olist'],input=sql.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
elapsed=time.monotonic()-t
(OUT/'final-reviewer-delta-output.jsonl').write_bytes(r.stdout)
(OUT/'final-reviewer-delta-stderr.txt').write_bytes(r.stderr)
if r.returncode:
    print(r.stderr.decode('utf-8',errors='replace'))
    raise SystemExit(r.returncode)
records=[json.loads(s) for s in r.stdout.decode().splitlines() if s.strip()]
f={x['id']:x['rows'] for x in records if x['kind']=='fixture'}
assert [(x['order_id'],x['order_item_id']) for x in f['F01-complete-item-identity']]==[('a',1),('a',2),('a',3),('a',4),('a',5)]
assert f['F02-unknown-warehouse-region']==[{'macro_region':None,'total_capacity':100}]
assert f['F03-missing-shipment-order']==[{'event_code':'DELIVERED','customer_state':None,'shipments':2}]
turn={x['warehouse_id']:x for x in f['F04-no-outbound-vs-unknown']}
assert turn[1]['out_qty_90d'] is None and turn[1]['turnover_days'] is None
assert turn[2]['out_qty_90d']==0 and turn[2]['turnover_days'] is None
assert turn[3]['out_qty_90d']==10 and turn[3]['turnover_days']==180
assert all(x['out_qty_90d'] is None and x['turnover_days'] is None for x in f['F05-missing-reference-date'])
assert f['F06-unknown-settlement-candidate']==[{'seller_id':'known','total':100}]
assert [x['customer_state'] for x in f['F07-rounded-score-identity']]==['A','B']
assert [x['low_pct'] for x in f['F07-rounded-score-identity']]==[10,10]
summary={'changed_sql_ids':[x['id'] for x in changed],'changed_questions_since_batch':question_changes,'changed_sql_count':len(changed),'fixtures_passed':len(fixtures),'seconds':round(elapsed,3),'provider_calls':0}
(OUT/'final-reviewer-delta-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'final-reviewer-final-cases.json').write_text(json.dumps(current,ensure_ascii=False,indent=2),encoding='utf-8')
manifest={str(f.relative_to(BASE)).replace('\\','/'):hashlib.sha256(f.read_bytes()).hexdigest() for f in files}
initial_manifest=json.loads((OUT/'final-reviewer-input-manifest.json').read_text(encoding='utf-8'))
for rel in initial_manifest:
    if rel=='gold_sql_sha256_by_id' or rel.startswith('data/benchmarks/'):
        continue
    manifest[rel]=hashlib.sha256((BASE/rel).read_bytes()).hexdigest()
    assert manifest[rel]==initial_manifest[rel],f'Production contract changed: {rel}'
manifest['gold_sql_sha256_by_id']={x['id']:hashlib.sha256(x['gold_sql'].encode()).hexdigest() for x in current}
(OUT/'final-reviewer-final-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False))
for x in records:
    if x['kind']=='fixture': print(json.dumps(x,ensure_ascii=False))
