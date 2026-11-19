"""Read-only CTE witnesses: shadow source tables, execute the authored Gold, compare independent expected rows."""
import json
from datetime import datetime, timezone
from reviewer_execute import OUT, execute, load_items

CASES = [
('review-complete-key-and-null-body','B2-T1-011',"""
order_reviews(review_id,order_id,review_score,review_comment_message,review_creation_date) AS (VALUES
 ('r1','o1',3,NULL::text,TIMESTAMP '2018-05-02'),('r1','o2',3,NULL,TIMESTAMP '2018-05-02'),
 ('r2','o3',3,'',TIMESTAMP '2018-05-03'))
""",[['r1','o1','2018-05-02T00:00:00'],['r1','o2','2018-05-02T00:00:00']],
 'Same review_id on different orders remains two reviews; empty body is not SQL NULL.'),
('customer-record-versus-person','B2-T2-002',"""
customers(customer_id,customer_unique_id,customer_state) AS (VALUES
 ('c1','p1','SP'),('c2','p1','SP'),('c3','p1','RJ'),('c4','p2',NULL::text))
""",[['SP',2,1],['RJ',1,1],[None,1,1]],'Profile count is physical rows; each state separately deduplicates people.'),
('latest-before-price-filter','B2-T3-005',"""
product_price_history(history_id,product_id,old_price,new_price,changed_at) AS (VALUES
 (1,'p1',100,200,TIMESTAMP '2018-06-01'),(2,'p1',200,150,TIMESTAMP '2018-06-02'),
 (3,'p2',100,110,TIMESTAMP '2018-06-02'),(4,'p2',110,140,TIMESTAMP '2018-06-02'),
 (5,'p3',100,999,TIMESTAMP '2018-07-01'))
""",[['p2',4,'2018-06-02T00:00:00',30]],'Latest row precedes predicate; history_id breaks ties; upper boundary excluded.'),
('snapshot-latest-before-points','B2-T3-006',"""
member_snapshot(snapshot_id,member_id,snapshot_date,level_id,points) AS (VALUES
 (1,1,DATE '2018-06-01',1,3000),(2,1,DATE '2018-06-02',1,5000),
 (3,2,DATE '2018-06-30',1,3000),(4,2,DATE '2018-06-30',2,4000),
 (5,3,DATE '2018-07-01',1,3000),(6,NULL,DATE '2018-06-30',1,3000))
""",[[2,'2018-06-30',2,4000]],'Later unsuitable row cannot fall back; same-day highest snapshot_id wins; cutoff inclusive.'),
('invalid-activity-interval','B2-T3-009',"""
campaigns(campaign_id,campaign_name,start_date,end_date) AS (VALUES
 (999001,'reversed_interval',DATE '2018-06-20',DATE '2018-06-10'),
 (999002,'one_day',DATE '2018-06-30',DATE '2018-06-30'),
 (999003,'outside',DATE '2018-07-01',DATE '2018-07-01'))
""",[[999002,'one_day','2018-06-30','2018-06-30']],
 'An interval with start after end contains no day; June 30 single-day overlap is valid; July 1 excluded.'),
('latest-review-within-window','B2-T3-015',"""
order_reviews(review_id,order_id,review_score,review_creation_date) AS (VALUES
 ('r1','o1',1,TIMESTAMP '2018-05-01'),('r2','o1',5,TIMESTAMP '2018-05-02'),
 ('r3','o2',1,TIMESTAMP '2018-05-03'),('r4','o2',5,TIMESTAMP '2018-06-01'),
 ('r5','o3',5,TIMESTAMP '2018-05-04'),('r6','o3',1,TIMESTAMP '2018-05-04'))
""",[['o2','r3','2018-05-03T00:00:00'],['o3','r6','2018-05-04T00:00:00']],
 'Latest in window, not latest lifetime or latest score-1 row; review_id tie breaker preserved.'),
('calendar-adjacent-not-last-nonempty','B2-T3-016',"""
orders(order_id,order_status,order_purchase_timestamp) AS (VALUES
 ('o1','delivered',TIMESTAMP '2018-01-02'),('o2','delivered',TIMESTAMP '2018-03-02'),
 ('o3','delivered',TIMESTAMP '2018-04-02'),('o4','delivered',TIMESTAMP '2018-04-03'),
 ('o5','canceled',TIMESTAMP '2018-02-02'))
""",[['2018-04',1]],'Missing valid February prevents March delta; canceled rows do not fill a calendar gap.'),
('tracking-exists-no-fanout','B2-T4-008',"""
shipments(shipment_id,warehouse_id,shipped_at,delivered_at) AS (VALUES
 (1,1,TIMESTAMP '2018-01-01',NULL::timestamp),(2,NULL,TIMESTAMP '2018-01-01',TIMESTAMP '2018-01-02'),
 (3,1,TIMESTAMP '2018-01-01',NULL::timestamp)),
shipment_tracking(tracking_id,shipment_id,location) AS (VALUES(1,1,'x'),(2,1,'y'),(3,2,''),(4,3,NULL::text)),
warehouses(warehouse_id,region_code) AS (VALUES(1,'SP')),
regions(region_code,macro_region) AS (VALUES('SP','Southeast'))
""",[['Southeast',1,1],[None,1,0]],'Multiple events count one shipment; empty location qualifies; missing warehouse remains NULL group.'),
('default-address-exists-fact-key','B2-T4-013',"""
orders(order_id,customer_id,order_purchase_timestamp) AS (VALUES('o1','c1',TIMESTAMP '2018-05-02'),('o2','c2',TIMESTAMP '2018-05-02')),
customers(customer_id,customer_unique_id,customer_state) AS (VALUES('c1','p1','SP'),('c2','p1','RJ')),
shipping_addresses(address_id,customer_id,is_default,state) AS (VALUES(1,'c1',true,'SP'),(2,'c1',true,'SP'),(3,'c2',true,'SP'))
""",[['SP',1]],'Two default addresses do not duplicate order; shared natural person cannot transfer SP evidence to RJ order.'),
('review-category-complete-key','B2-T4-021',"""
order_reviews(review_id,order_id,review_score) AS (VALUES('r1','o1',1),('r1','o2',5)),
order_items(order_id,order_item_id,product_id) AS (VALUES('o1',1,'p1'),('o1',2,'p2'),('o1',3,'p3'),('o2',1,'p1')),
products(product_id,product_category_name) AS (VALUES('p1','a'),('p2','a'),('p3','b'))
""",[['a',2,1],['b',1,1]],'Duplicate category items deduplicate only per full review identity; same review_id in another order stays separate.'),
('inventory-no-fact-versus-unknown','B2-T5-007',"""
inventory(warehouse_id,product_id,quantity) AS (VALUES(1,'p1',10),(1,'p2',20),(1,'p3',NULL::integer)),
inventory_movements(warehouse_id,product_id,movement_type,quantity,moved_at) AS (VALUES(1,'p2',2,NULL::integer,TIMESTAMP '2018-09-01')),
warehouses(warehouse_id,region_code) AS (VALUES(1,'SP')),
regions(region_code,macro_region) AS (VALUES('SP','Southeast')),
products(product_id,product_category_name) AS (VALUES('p1','a'),('p2','b'),('p3','c')),
product_category_translation(product_category_name,product_category_name_english) AS (VALUES('a','A'),('b','B'),('c','C'))
""",[['Southeast','B',20,None],['Southeast','A',10,0]],
 'Known-stock candidates are sorted by stock; absence is known zero outflow; present all-NULL movements remain unknown; unknown-stock group is excluded from this explicit Top150.'),
('multi-fact-preaggregate','B2-T5-008',"""
orders(order_id,customer_id,order_purchase_timestamp) AS (VALUES('o1','c1',TIMESTAMP '2018-04-02'),('o2','c2',TIMESTAMP '2018-04-02'),('o3','c3',TIMESTAMP '2018-04-02')),
customers(customer_id,customer_state) AS (VALUES('c1','SP'),('c2','RJ'),('c3','MG')),
regions(region_code,macro_region) AS (VALUES('SP','A'),('RJ','B'),('MG','C')),
order_items(order_id,price) AS (VALUES('o1',10::numeric),('o1',10),('o2',NULL::numeric)),
order_payments(order_id,payment_value) AS (VALUES('o1',7::numeric),('o1',13),('o2',NULL::numeric)),
refunds(order_id,refund_amount) AS (VALUES('o1',2::numeric),('o1',3),('o2',NULL::numeric))
""",[['A',20,20,5,1],['B',None,None,None,1],['C',0,0,0,1]],
 'Equal amounts survive; 2x2x2 fact cross-product does not inflate sums; unknown facts differ from no fact.'),
('member-denominator-complete','B2-T6-003',"""
orders(order_id,customer_id,order_status,order_purchase_timestamp) AS (VALUES
 ('o1','c1','delivered',TIMESTAMP '2018-01-01'),('o2','c2','delivered',TIMESTAMP '2018-01-01'),
 ('o3','missing','delivered',TIMESTAMP '2018-01-01'),('o4','c1','canceled',TIMESTAMP '2018-01-01')),
customers(customer_id,customer_state,customer_unique_id) AS (VALUES('c1','SP','p1'),('c2','SP','p2')),
members(member_id,customer_unique_id,status) AS (VALUES(1,'p1',3))
""",[['SP',50.00],[None,0.00]],'Canceled excluded; status-3 current membership qualifies; nonmember and unmatched customer remain denominators.'),
('retention-global-first-and-person-dedupe','B2-T6-007',"""
orders(order_id,customer_id,order_status,order_purchase_timestamp) AS (VALUES
 ('o1','c1','delivered',TIMESTAMP '2017-10-01'),('o2','c1','delivered',TIMESTAMP '2017-11-01'),
 ('o3','c2','delivered',TIMESTAMP '2017-11-02'),('o4','c3','delivered',TIMESTAMP '2018-01-01'),
 ('o5','c3','delivered',TIMESTAMP '2018-01-02'),('o6','c4','delivered',TIMESTAMP '2017-11-03'),
 ('o7','c4','canceled',TIMESTAMP '2018-01-01')),
customers(customer_id,customer_unique_id) AS (VALUES('c1','p1'),('c2','p2'),('c3','p2'),('c4','p3'))
""",[[50.00]],'October-first buyer excluded; p2 returns through other customer_id twice but counts once; nonreturning p3 stays denominator.'),
('dynamic-cohort-order-completeness','B2-T6-008',"""
orders(order_id,customer_id,order_status,order_purchase_timestamp) AS (VALUES
 ('old','c1','delivered',TIMESTAMP '2018-02-01'),('o1','c1','delivered',TIMESTAMP '2018-03-01'),
 ('o2','c2','delivered',TIMESTAMP '2018-03-02'),('o3','c2','delivered',TIMESTAMP '2018-03-03'),
 ('o4','missing','delivered',TIMESTAMP '2018-03-04')),
customers(customer_id,customer_unique_id) AS (VALUES('c1','p1'),('c2','p2')),
order_items(order_id,price,freight_value) AS (VALUES('o1',100::numeric,10::numeric),('o2',NULL::numeric,1::numeric))
""",[['2018-03','NEW',2,0],['2018-03','RETURNING',1,110],['2018-03',None,1,0]],
 'Full-history first-month classification; all first-month orders NEW; absent items contribute zero, unknown present item remains NULL then standard SUM ignores it.'),
('sla-all-tickets-and-fractional-hours','B2-T6-009',"""
support_tickets(ticket_id,category_id,created_at,resolved_at) AS (VALUES
 (1,1,TIMESTAMP '2018-04-01',TIMESTAMP '2018-04-02'),
 (2,1,TIMESTAMP '2018-04-01',TIMESTAMP '2018-04-02 00:00:01'),
 (3,1,TIMESTAMP '2018-04-01',NULL::timestamp),(4,2,TIMESTAMP '2018-04-01',TIMESTAMP '2018-04-01 01:00')),
ticket_categories(category_id,category_name,sla_hours) AS (VALUES(1,'a',24),(2,'b',NULL::integer))
""",[[1,'a',33.33],[2,'b',0.00]],'Exactly 24 hours qualifies, 24h+1s fails, unresolved stays denominator; unknown SLA is not success.'),
('inventory-global-anchor-and-open-lower-bound','B2-T6-012',"""
inventory(warehouse_id,product_id,quantity) AS (VALUES(1,'p1',10),(1,'p2',20),(2,'p1',10),(3,'p1',NULL::integer)),
inventory_movements(warehouse_id,movement_type,quantity,moved_at) AS (VALUES
 (99,1,1,TIMESTAMP '2018-10-01'),(1,2,900,TIMESTAMP '2018-07-03'),
 (1,2,3,TIMESTAMP '2018-07-03 00:00:01'),(1,2,3,TIMESTAMP '2018-10-01'),
 (2,2,2,TIMESTAMP '2018-06-01'),(3,2,2,TIMESTAMP '2018-09-01'))
""",[[1,450.0],[2,None],[3,None]],'Global anchor includes non-outbound movements; exact 90-day lower boundary excluded; empty outbound and unknown stock return NULL.')
]

CASES.append(('inventory-stable-top150-tie-boundary','B2-T5-007',"""
inventory(warehouse_id,product_id,quantity) AS (SELECT 1,'p'||n,10 FROM generate_series(1,151) n),
inventory_movements(warehouse_id,product_id,movement_type,quantity,moved_at) AS (SELECT 1,'p1',2,1,TIMESTAMP '2017-01-01' WHERE FALSE),
warehouses(warehouse_id,region_code) AS (VALUES(1,'SP')),
regions(region_code,macro_region) AS (VALUES('SP','A')),
products(product_id,product_category_name) AS (SELECT 'p'||n,CASE WHEN n=151 THEN NULL ELSE 'c'||LPAD(n::text,3,'0') END FROM generate_series(1,151) n),
product_category_translation(product_category_name,product_category_name_english) AS (SELECT 'c'||LPAD(n::text,3,'0'),'C'||LPAD(n::text,3,'0') FROM generate_series(1,150) n)
""",[['A','C'+str(n).zfill(3),10,0] for n in range(1,151)],
 '151 groups have equal known stock: full dimension tie breakers select C001-C150; NULL dimension sorts last and is deterministically excluded by explicit LIMIT150.'))

def run():
    items = {i['id']:i for i in load_items()}
    results = []
    for label,item_id,fixture,expected,rationale in CASES:
        gold = items[item_id]['gold_sql'].rstrip().rstrip(';')
        body = fixture.strip()+', '+gold[5:] if gold.upper().startswith('WITH ') else fixture.strip()+' '+gold
        sql = 'WITH '+body
        outcome = execute("SELECT COALESCE(json_agg(q),'[]'::json) FROM ("+sql+') q;')
        actual = [list(x.values()) for x in json.loads(outcome['stdout'])] if outcome['ok'] else None
        results.append({'witness':label,'itemId':item_id,'status':'PASS' if actual==expected else 'DEFECT',
                        'expected':expected,'actual':actual,'rationale':rationale,'fixtureSql':fixture.strip(),
                        'execution':outcome,'sourceSha256':items[item_id]['sourceSha256']})
    report = {'format':'unseen-v2-independent-boundary-witnesses-v1','at':datetime.now(timezone.utc).isoformat(),
              'count':len(results),'passed':sum(x['status']=='PASS' for x in results),
              'defects':sum(x['status']=='DEFECT' for x in results),'results':results}
    (OUT/'boundary-witness-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k!='results'}))
    print(json.dumps([{'witness':x['witness'],'itemId':x['itemId'],'expected':x['expected'],'actual':x['actual'],'error':x['execution']['stderr']} for x in results if x['status']!='PASS'],ensure_ascii=False))

if __name__=='__main__':
    run()
