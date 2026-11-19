from pathlib import Path
from collections import Counter
import hashlib,json,subprocess,time,yaml

BASE=Path(r'C:\Users\xunqian\Desktop\agent')
OUT=BASE/'reports/reviewed-dev-v3-runtime-20261119'
SOURCE=BASE/'data/benchmarks/olist-v3-dev-candidate'
RUNTIME=BASE/'data/benchmarks/olist-v3-dev-runtime-candidate'
PREVIOUS=BASE/'reports/reviewed-dev-v3-20261119/final-reviewer-final-manifest.json'
previous=json.loads(PREVIOUS.read_text(encoding='utf-8'))

def sha(data): return hashlib.sha256(data).hexdigest()
def objsha(obj): return sha(json.dumps(obj,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode())
def load_cases(folder):
    cases=[]
    for f in sorted(folder.glob('*.yaml')):
        for c in yaml.safe_load(f.read_text(encoding='utf-8')):
            cases.append(c)
    assert len(cases)==100 and len({c['id'] for c in cases})==100
    return cases

source=load_cases(SOURCE)
runtime=load_cases(RUNTIME)
src={c['id']:c for c in source}
run={c['id']:c for c in runtime}
assert [c['id'] for c in source]==[c['id'] for c in runtime], 'Question order changed'
source_manifest={str(f.relative_to(BASE)).replace('\\','/'):sha(f.read_bytes()) for f in sorted(SOURCE.glob('*.yaml'))}
runtime_manifest={str(f.relative_to(BASE)).replace('\\','/'):sha(f.read_bytes()) for f in sorted(RUNTIME.glob('*.yaml'))}
for f,s in source_manifest.items():
    assert previous[f]==s, 'Frozen source changed since independent acceptance: '+f
assert len(source_manifest)==len(runtime_manifest)==6
changed=[]
for cid in src:
    if src[cid]!=run[cid]: changed.append(cid)
assert changed==['T5-019'],changed
old=src['T5-019']['gold_sql']
new=run['T5-019']['gold_sql']
assert old.count('orders AS(')==1 and old.count('JOIN orders o USING(')==1
expected=old.replace('orders AS(','order_counts AS(').replace('JOIN orders o USING(','JOIN order_counts o USING(')
assert new==expected,'Changed SQL differs from exact CTE rename'
without_sql=lambda c:{k:v for k,v in c.items() if k!='gold_sql'}
assert without_sql(src['T5-019'])==without_sql(run['T5-019'])
unchanged_fields=['id','difficulty','tags','question','notes']
for c in source:
    assert without_sql(c)==without_sql(run[c['id']]),c['id']
contract_manifest={k:sha((BASE/k).read_bytes()) for k in previous if k!='gold_sql_sha256_by_id' and not k.startswith('data/benchmarks/')}
assert all(previous[k]==v for k,v in contract_manifest.items()), 'Production contract or source DDL changed'

def clean(sql):return sql.strip().rstrip(';')
sql="BEGIN READ ONLY;\nSET LOCAL statement_timeout='8s';\n"
sql+="SELECT json_build_object('kind','environment','readOnly',current_setting('transaction_read_only'),'role',current_user,'timeout',current_setting('statement_timeout'),'postgresVersion',version());\n"
sql+="SELECT json_build_object('kind','original','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM ("+clean(old)+")q));\n"
sql+="SELECT json_build_object('kind','runtime','rows',(SELECT COALESCE(json_agg(row_to_json(q)),'[]'::json) FROM ("+clean(new)+")q));\n"
sql+="WITH frozen_result AS MATERIALIZED ("+clean(old)+"), runtime_result AS MATERIALIZED ("+clean(new)+") SELECT json_build_object('kind','equivalence','frozenMinusRuntime',(SELECT COUNT(*) FROM(SELECT * FROM frozen_result EXCEPT ALL SELECT * FROM runtime_result)d),'runtimeMinusFrozen',(SELECT COUNT(*) FROM(SELECT * FROM runtime_result EXCEPT ALL SELECT * FROM frozen_result)d));\n"
sql+='ROLLBACK;\n'
(OUT/'final-reviewer-runtime-equivalence.sql').write_text(sql,encoding='utf-8')
t=time.monotonic()
result=subprocess.run(['docker','exec','-i','text2sql-postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','text2sql_ro','-d','olist'],input=sql.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
elapsed=time.monotonic()-t
(OUT/'final-reviewer-runtime-output.jsonl').write_bytes(result.stdout)
(OUT/'final-reviewer-runtime-stderr.txt').write_bytes(result.stderr)
if result.returncode:
    print(result.stderr.decode('utf-8',errors='replace'))
    raise SystemExit(result.returncode)
records={x['kind']:x for x in map(json.loads,result.stdout.decode().splitlines())}
assert records['environment']['readOnly']=='on'
assert records['equivalence']['frozenMinusRuntime']==records['equivalence']['runtimeMinusFrozen']==0
assert records['original']['rows']==records['runtime']['rows'], 'Ordered output differs'
assert len(records['runtime']['rows'])==20

item_records=[]
for cid in src:
    item_records.append({'id':cid,'status':'PASS','comparison':'EXACT_CTE_RENAME_WITH_EQUAL_RESULTS' if cid=='T5-019' else 'ALL_YAML_ELEMENTS_UNCHANGED',
      'sourceGoldSQLSHA256':sha(src[cid]['gold_sql'].encode()),'runtimeGoldSQLSHA256':sha(run[cid]['gold_sql'].encode()),
      'completeSourceElementSHA256':objsha(src[cid]),'completeRuntimeElementSHA256':objsha(run[cid]),
      'questionAndOtherFieldsUnchanged':True})
metadata={
 'reviewer':'independent_runtime_compatibility_reviewer_20261119','date':'2026-11-19',
 'status':'ACCEPTED','scope':'Only T5-019 CTE orders to order_counts rename; no business semantics change',
 'finalCounts':{'total':100,'PASS':100,'DEFECT':0,'AMBIGUOUS':0,'exactUnchangedElements':99,'compatibleCTERename':1},
 'frozenSourceCandidateManifest':source_manifest,'frozenSourceManifestMatchesPriorIndependentAcceptance':True,
 'finalCandidateManifest':runtime_manifest,'finalCandidateManifestSHA256':objsha(runtime_manifest),
 'productionContractAndDDLManifest':contract_manifest,'productionContractAndDDLManifestSHA256':objsha(contract_manifest),
 'changedIds':['T5-019'],'changedFields':['gold_sql'],'all100QuestionsAndOtherFieldsUnchanged':True,
 'changeSpecification':{'declaration':{'from':'orders AS(','to':'order_counts AS('},'reference':{'from':'JOIN orders o USING(','to':'JOIN order_counts o USING('},'outputAliasOrdersUnchanged':True},
 'equivalence':{**records['equivalence'],'rows':20,'orderedOutputEqual':True,'sourceOutputSHA256':objsha(records['original']['rows']),'runtimeOutputSHA256':objsha(records['runtime']['rows'])},
 'execution':{'seconds':round(elapsed,3),'readOnly':True,'databaseRole':'text2sql_ro','statementTimeout':'8s','sqlSHA256':sha(sql.encode()),'sqlFile':'final-reviewer-runtime-equivalence.sql','outputFile':'final-reviewer-runtime-output.jsonl','full100GoldReexecuted':False},
 'independence':{'modelOrProviderCalls':0,'keysRead':False,'blindOrUnseenContentRead':False,'otherAuditConclusionsRead':False,'candidateOrProductionFilesEdited':False,'originalIndependentReportModified':False,'gitCommits':0},
 'limitations':['This review independently verifies exact transformation and SQL result equivalence, not the separate Java validator preflight.','The frozen failure record and original independent semantic review remain historical records; no model improvement claim follows from this rename.']
}
report={'metadata':metadata,'items':item_records,'unresolvedIssues':[]}
(OUT/'final-reviewer-runtime-acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
md=['# 开发参考运行兼容性独立接受','',
 '最终接受：100 PASS。99题全部YAML元素完全相同；T5-019仅Gold的CTE声明及对应引用从orders改为order_counts。100题题面、标签、难度、备注、输出列orders均未改。','',
 '独立只读执行原SQL与运行兼容SQL各返回20行，顺序与数值相同；双向EXCEPT ALL分别0行，耗时'+str(round(elapsed,3))+'秒。未重复全100执行，模型/供应商调用0次。','',
 '原冻结六文件仍与上次独立接受SHA相符，生产契约和源DDL SHA未变。原报告与原失败记录未改。本次只验精确重命名及数据库等价，不冒充另一个Java校验器预检。','',
 '## 新六YAML SHA-256','','| 文件 | SHA-256 |','|---|---|']
md+=['| '+k+' | '+v+' |' for k,v in runtime_manifest.items()]
md+=['','候选manifest SHA-256：`'+metadata['finalCandidateManifestSHA256']+'`。',
 '生产契约与DDL manifest SHA-256：`'+metadata['productionContractAndDDLManifestSHA256']+'`。','',
 '完整100项差异核对、逐题内容SHA、来源清单及执行证据见 `final-reviewer-runtime-acceptance.json`。']
(OUT/'final-reviewer-runtime-report.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
print(json.dumps({'counts':metadata['finalCounts'],'execution':metadata['execution'],'equivalence':metadata['equivalence'],'finalCandidateManifest':runtime_manifest,'finalCandidateManifestSHA256':metadata['finalCandidateManifestSHA256']},ensure_ascii=False,indent=2))
