import hashlib,json,subprocess,re,yaml
from collections import Counter
from decimal import Decimal,ROUND_HALF_UP
from pathlib import Path
root=Path('reports/stage2-dimension-display-regression-20261118')
read=lambda p:json.loads(Path(p).read_text(encoding='utf-8-sig'))
questions={x['id']:x for p in Path('data/eval').glob('*.yaml') for x in yaml.safe_load(p.read_text(encoding='utf-8-sig'))}
def execute(sql):
    # Previously executed SELECTs only, under a read-only role and explicit transaction.
    sql=sql.rstrip().rstrip(';')
    command="BEGIN READ ONLY; SET LOCAL statement_timeout='5s'; SELECT COALESCE(json_agg(row_to_json(q)), '[]'::json) FROM (\n"+sql+"\n) q; COMMIT;"
    p=subprocess.run(['docker','exec','text2sql-postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','text2sql_ro','-d','olist','-c',command],capture_output=True,text=True,encoding='utf-8',timeout=12)
    if p.returncode:raise RuntimeError(p.stderr)
    return json.loads(p.stdout.strip())

def cell(x):
    if x is None:return 'NULL'
    try:return format(Decimal(str(x)).quantize(Decimal('.0001'),rounding=ROUND_HALF_UP).normalize(),'f')
    except Exception:return re.sub(r'[T ]00:00:00(?:\.0+)?$','',str(x))

def bag(rows,indexes=None):
    return Counter(tuple(cell(v) for n,v in enumerate(r.values()) if indexes is None or n in indexes) for r in rows)


lookups={
 'level':execute('SELECT level_id AS id, level_name AS label FROM member_levels'),
 'warehouse':execute('SELECT warehouse_id AS id, warehouse_name AS label FROM warehouses'),
 'campaign':execute('SELECT campaign_id AS id, campaign_name AS label FROM campaigns')}
selected={'T2-015':'level','T2-019':'campaign','T2-024':'warehouse','T2-026':'warehouse','T4-017':'level','T5-005':None,'T6-013':None}
reports={name:read(next((root/name).glob('eval-*.json'))) for name in ('r1','r2','r3')}
sources={name:{'reportSha256':hashlib.sha256(next((root/name).glob('eval-*.json')).read_bytes()).hexdigest(),'codeCommit':r['meta']['gitCommit']} for name,r in reports.items()}
items=[];query_count=3
for ident,kind in selected.items():
 q=questions[ident];gold=execute(q['gold_sql']);query_count+=1
 generated={}
 for name,r in reports.items():
  x=next(x for x in r['itemEvidence'] if x['id']==ident)
  if x['status']=='SUCCESS':
   generated.setdefault(x['finalSql'],[]).append({'round':name,'strictCorrect':x['correct']})
 variants=[]
 for sql,occurrences in generated.items():
  actual=execute(sql);query_count+=1
  row=dict(sql=sql,occurrences=occurrences,result=actual)
  if kind:
   lookup=lookups[kind]
   counts=Counter(x['label'] for x in lookup)
   unique=all(x['label'] is not None and counts[x['label']]==1 for x in lookup)
   row['lookupLabelsUniqueAndNonNull']=unique
   if unique:
    inverse={x['label']:x['id'] for x in lookup}
    mapped=[];unmapped=[]
    for result_row in actual:
     values=list(result_row.values())
     if values[0] not in inverse:unmapped.append(values[0]);continue
     mapped.append({'id':inverse[values[0]],**{str(n):v for n,v in enumerate(values[1:],1)}})
    row['unmappedLabels']=unmapped
    row['diagnosticAgreementAfterResolvingLabels']=not unmapped and bag(mapped)==bag(gold)
  variants.append(row)
 items.append(dict(id=ident,question=q['question'],goldSql=q['gold_sql'],goldResult=gold,variants=variants))
result=dict(format='p18-formal-contract-diagnosis-v1',sources=sources,originalBankSize=200,strictScoresUnchanged=True,readOnlySqlExecutions=query_count,modelCalls=0,modelCostYuan=0,lookups=lookups,items=items,limits=['Diagnostic identity resolution is not production response rewriting or rescoring.','Original Gold and strict failures are preserved.','Some questions may have additional scope/grain defects.'])
(root/'contract-review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'queries':query_count,'items':[(x['id'],[(v.get('diagnosticAgreementAfterResolvingLabels'),v['occurrences']) for v in x['variants']]) for x in items]},ensure_ascii=True))
