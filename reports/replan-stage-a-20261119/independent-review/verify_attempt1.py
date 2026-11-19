"""Independent public A-package checks; no provider, database, or forbidden corpus reads."""
import collections
import hashlib
import json
from pathlib import Path
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
A = ROOT / 'reports/replan-stage-a-20261119'
READS = {}
CHECKS = []

def read(p):
    p=Path(p)
    if not p.is_absolute(): p=ROOT/p
    parts=p.relative_to(ROOT).parts
    if parts[0] != 'scripts' and any('blind' in x.lower() or 'holdout' in x.lower() or x.lower() in {'u_b','u_c'} for x in parts):
        raise ValueError('Forbidden content source')
    data=p.read_bytes()
    READS[p.relative_to(ROOT).as_posix()]=hashlib.sha256(data).hexdigest()
    return data

def js(p): return json.loads(read(p).decode('utf-8-sig'))
def check(name, passed, **evidence): CHECKS.append(dict(id=name,passed=bool(passed),**evidence))
def items(d):
    return [x for p in sorted((ROOT/d).glob('T*.yaml')) for x in yaml.safe_load(read(p).decode('utf-8-sig'))]

baseline=js(A/'source-baseline.json')
drift=[p for p,h in baseline['protectedFiles'].items() if hashlib.sha256(read(p)).hexdigest()!=h]
check('protected_source_and_old_public_bytes',not drift,checked=len(baseline['protectedFiles']),drift=drift)
audited={(x['suite'],x['id']):x for x in js(A/'contract-audit/item-recommendations.json')['items']}
changes=[]; modes=collections.Counter(); identities={}; key_checks=[]
for suite,old_dir in [('development','data/benchmarks/olist-v3-dev-runtime-candidate'),('formal','data/benchmarks/olist-v3-candidate')]:
    new_dir=f'data/benchmarks/olist-v4-{suite}-20261119'
    old=items(old_dir); new=items(new_dir); op={x['id']:x for x in old}; np={x['id']:x for x in new}
    identities[suite]=set(np)
    check(suite+'_same_ids_layers',len(new)==len(np)==100 and set(op)==set(np) and collections.Counter(x['difficulty'] for x in old)==collections.Counter(x['difficulty'] for x in new))
    pol=js(ROOT/new_dir/'scoring-policy.json')['policies']; check(suite+'_policy_coverage',set(pol)==set(np))
    seal=js(ROOT/new_dir/'reference-seal.json')
    check(suite+'_seal_files',all(hashlib.sha256(read(ROOT/new_dir/p)).hexdigest()==h for p,h in seal['files'].items()))
    check(suite+'_no_evaluation_admission',seal['state']=='REFERENCE_CANDIDATE_NOT_EVALUATION_ADMITTED' and not seal['stageAccepted'])
    for identity,item in np.items():
        before=op[identity]; spec=audited[suite,identity]; p=pol[identity];modes[p['mode']]+=1
        check(suite+':'+identity+':independent_spec',item['question']==spec['proposedQuestion'] and item['gold_sql']==spec['proposedGoldSql'] and p['mode']==spec['scoring']['mode'])
        if before['question']!=item['question'] or before['gold_sql']!=item['gold_sql']:
            changes.append((suite,identity,before['question']!=item['question'],before['gold_sql']!=item['gold_sql']))
        if p['mode'] in {'TIME_ASC','PARTIAL_ORDER'}:
            # Manually independently checked against all 25 public SELECT/ORDER contracts.
            expected=[2] if suite=='formal' and identity=='T5-001' else [1] if p['mode']=='PARTIAL_ORDER' else [0]
            key_checks.append(dict(suite=suite,id=identity,keys=p['keyPositions'],matchesReviewedSelectAndRequestedOrder=p['keyPositions']==expected))
check('disjoint_development_formal_ids',not identities['development']&identities['formal'])
errata=js(A/'v4-errata.json')['items'];check('errata_exact_changed_item_coverage',{(x['suite'],x['id']) for x in errata}=={(s,i) for s,i,_,_ in changes},changes=changes)
check('all_25_registered_order_columns',len(key_checks)==25 and all(x['matchesReviewedSelectAndRequestedOrder'] for x in key_checks),items=key_checks)
check('score_modes_139_25_36',modes['UNORDERED_MULTISET_OR_SINGLE_ROW']==139 and modes['TIME_ASC']+modes['PARTIAL_ORDER']==25 and modes['TOTAL_ORDER_STRICT_TOP_N']==36,counts=dict(modes))
targets=js(A/'original-target-preregistration.json')
check('strict_original_targets_preserved',targets['threshold']=='AT_LEAST_2_OF_3_STRICTLY' and targets['extraRequestsMax']==2 and {x['id']:x['requestLimit'] for x in targets['targets']}=={'T4-001':1,'T3-002':0,'T6-003':1})
for name in ['source-manifest.json','artifacts.sha256.json']:
    manifest=js(A/'input-inventory'/name)
    manifest=manifest['files'] if name=='source-manifest.json' else manifest
    bad=[]
    for p,v in manifest.items():
        source=ROOT/p if name=='source-manifest.json' else A/'input-inventory'/p
        wanted=v['sha256'] if isinstance(v,dict) else v
        if hashlib.sha256(read(source)).hexdigest()!=wanted: bad.append(p)
    check('input_inventory_'+name+'_hashes',not bad,checked=len(manifest),drift=bad)
provenance=js(A/'input-inventory/execution-provenance.json')
jar_path=ROOT/baseline['jars']['p21Current']['path']; read(jar_path)
with zipfile.ZipFile(jar_path) as z:
    bad=[]
    for p,v in provenance['runtimeFileFingerprints'].items():
        entry=p.split('target/replan-stage-a-frozen/',1)[1]
        if hashlib.sha256(z.read(entry)).hexdigest()!=v['sha256']: bad.append(p)
    check('capture_runtime_exactly_from_frozen_p21_jar',not bad,checked=len(provenance['runtimeFileFingerprints']),drift=bad)
captures=[]
for filename,expected in [('capture-original-200.json',200),('capture-v3-dev-100.json',100),('capture-v3-formal-100.json',100)]:
    c=js(A/'input-inventory'/filename); good=True
    def utf16(s):return len(s.encode('utf-16-le'))//2
    for x in c['items']:
        good=good and sum(x['componentChars'].values())==x['promptChars']==utf16(c['systemPrompt']['text'])+utf16(x['userPrompt']['text'])
    check(filename+'_component_identity',len(c['items'])==expected and c['modelCalls']==0 and good)
    captures.extend(c['items'])
requests=[json.loads(x) for x in read(A/'input-inventory/request-segments.jsonl').decode('utf-8').splitlines()]
calls=[json.loads(x) for x in read(A/'input-inventory/model-call-ledger.jsonl').decode('utf-8').splitlines()]
runtime=js(A/'input-inventory/runtime-summary.json')
check('runtime_ledger_totals',len(requests)==700 and len(calls)==698 and sum(not x['usageKnown'] for x in calls)==44 and abs(sum(x['knownCostYuan'] for x in calls if x['knownCostYuan'] is not None)-runtime['knownCostSubtotalYuan'])<1e-10)
unknown_sent=[dict(run=x['run'],id=x['id'],callNumber=x['callNumber'],status=x['status'],source=x['source']) for x in calls if not x['usageKnown']]
correction_unknown=[x for x in unknown_sent if x['callNumber']==2]
check('transport_unknown_not_labeled_sent',not unknown_sent,unknownUsageLabeledSentCount=len(unknown_sent),unknownCorrectionCount=len(correction_unknown),examples=correction_unknown)
gold=A/'free-verification.json'
if gold.exists():
    g=js(gold);check('gold_executable_200_plus_new200',len(g['runs'])==3 and all(x['correct']==x['total'] for x in g['runs'].values()) and sum(x['total'] for x in g['runs'].values())==400 and g['providerCalls']==0,evidence=g)
    sources={'original200':'data/eval','v4development100':'data/benchmarks/olist-v4-development-20261119','v4formal100':'data/benchmarks/olist-v4-formal-20261119'}
    for name,ev in g['runs'].items():
        raw=read(ev['report']);d=json.loads(raw.decode('utf-8-sig'));reference={x['id']:x for x in items(sources[name])}
        passed=hashlib.sha256(raw).hexdigest()==ev['sha256'] and len(d['itemEvidence'])==len(reference)==ev['total'] and d['meta']['dryRun']
        for x in d['itemEvidence']:
            a=reference[x['id']]
            passed=passed and x['status']=='SUCCESS' and x['correct'] and not x['calls'] and x['goldSqlSha256']==hashlib.sha256(a['gold_sql'].encode()).hexdigest() and x['questionSha256']==hashlib.sha256(a['question'].encode()).hexdigest()
        check(name+'_actual_free_report_identity',passed,report=ev['report'])
result=dict(format='independent-a-attempt1-package-checks-v1',checks=CHECKS,passed=sum(x['passed'] for x in CHECKS),total=len(CHECKS),reads=READS,providerCalls=0,databaseCalls=0,forbiddenContentRead=False)
(OUT/'package-checks-attempt1.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':result['passed'],'total':result['total'],'failed':[x['id'] for x in CHECKS if not x['passed']],'scoringModes':dict(modes),'unknownCorrectionCount':len(correction_unknown),'freeVerificationPresent':gold.exists()}))
