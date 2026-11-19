"""Focused final A re-review: fingerprints, typed contracts, unchanged evidence, transport labels."""
import collections
import hashlib
import json
from pathlib import Path
import zipfile
import yaml

ROOT=Path(__file__).resolve().parents[3]
A=ROOT/'reports/replan-stage-a-20261119'
OUT=Path(__file__).resolve().parent
READS={}; CHECKS=[]
def read(p):
    p=Path(p)
    if not p.is_absolute():p=ROOT/p
    rel=p.relative_to(ROOT).as_posix()
    if p.relative_to(ROOT).parts[0]!='scripts' and any('blind' in t.lower() or 'holdout' in t.lower() or t.lower() in {'u_b','u_c'} for t in p.relative_to(ROOT).parts):raise ValueError('Forbidden content')
    b=p.read_bytes();READS[rel]=hashlib.sha256(b).hexdigest();return b
def js(p):return json.loads(read(p).decode('utf-8-sig'))
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def check(i,p,**e):CHECKS.append(dict(id=i,passed=bool(p),**e))

manifest=js(A/'package-manifest-attempt2.json')
bad=[p for p,h in manifest['files'].items() if sha(p)!=h]
check('frozen_package_71_file_integrity',len(manifest['files'])==71 and not bad,checked=len(manifest['files']),drift=bad)
old=js(OUT/'package-checks-attempt1.json')['reads']
selected={p:h for p,h in old.items() if (p.startswith('data/benchmarks/olist-v4-') and p.endswith('.yaml')) or '/input-inventory/capture-' in p or '/gold-attempt-02/' in p}
bad=[p for p,h in selected.items() if sha(p)!=h]
check('reuse_unchanged_questions_gold_and_raw_capture',len(selected)==21 and not bad,checked=len(selected),drift=bad)
typed=js(A/'output-column-schema-attempt2.json')
audit=js(A/'contract-audit/item-recommendations.json')
spec={(x['suite']+':'+x['id']):x for x in audit['items']}
check('metadata_audited_source_identity',typed['sourceSpecificationSha256']==sha(A/'contract-audit/item-recommendations.json') and set(typed['items'])==set(spec) and len(spec)==200)
kind_counts=collections.Counter();type_errors=[]
numeric={-6,5,4,-5,6,7,8,2,3};temporal={91,92,93,2013,2014}
for key,row in typed['items'].items():
    s=spec[key]
    if row['goldSqlSha256']!=digest(s['proposedGoldSql']) or row['questionSha256']!=digest(s['proposedQuestion']):type_errors.append(key+':source')
    for i,c in enumerate(row['columns']):
        expected='NUMERIC' if c['jdbcType'] in numeric else 'TEMPORAL' if c['jdbcType'] in temporal else 'TEXT'
        if c['kind']!=expected or c['position']!=i:type_errors.append(key+':position/type')
        kind_counts[c['kind']]+=1
check('all_200_gold_metadata_hashes_positions_and_kinds',not type_errors and kind_counts=={'NUMERIC':285,'TEXT':73,'TEMPORAL':20},errors=type_errors,columns=sum(kind_counts.values()),kinds=dict(kind_counts))
check('independent_capture_versions_same_output_metadata',js(A/'output-column-schema.json')['items']==typed['items'])
policy_errors=[];source_hash=sha(A/'ReferenceContractScorer.java');metadata_hash=sha(A/'output-column-schema-attempt2.json')
for suite in ['development','formal']:
    directory=ROOT/f'data/benchmarks/olist-v4-{suite}-20261119';policy=js(directory/'scoring-policy.json')
    if policy['evaluatorSourceSha256']!=source_hash or policy['referenceColumnSchemaSha256']!=metadata_hash:policy_errors.append(suite+':artifact_identity')
    for identity,p in policy['policies'].items():
        row=typed['items'][suite+':'+identity]
        expected_schema_hash=digest(json.dumps(row,sort_keys=True))
        if p['columnKinds']!=[x['kind'] for x in row['columns']] or p['goldOutputSchemaSha256']!=expected_schema_hash:policy_errors.append(suite+':'+identity+':schema')
        expected_keys=[0] if p['mode']=='TIME_ASC' else [2] if suite=='formal' and identity=='T5-001' else [1] if p['mode']=='PARTIAL_ORDER' else []
        if p['keyPositions']!=expected_keys or p['mode']!=spec[suite+':'+identity]['scoring']['mode']:policy_errors.append(suite+':'+identity+':order_changed')
    seal=js(directory/'reference-seal.json')
    if not all(sha(directory/p)==h for p,h in seal['files'].items()):policy_errors.append(suite+':seal')
    if seal['state']!='REFERENCE_CANDIDATE_NOT_EVALUATION_ADMITTED' or policy['paidAdmission']!='DENIED_UNTIL_D_COMMON_SCORER_AND_CONTROLLER_INTEGRATION':policy_errors.append(suite+':paid_gate')
check('policies_bind_same_frozen_scorer_and_gold_column_schema',not policy_errors,errors=policy_errors)
targets=js(A/'original-target-preregistration.json');identity=targets['originalScorerIdentity']
check('original_scorer_three_source_hashes',sha(identity['probe'])==identity['probeSourceSha256'] and sha(identity['outputProbe'])==identity['outputProbeSha256'] and sha(identity['normalizeSource'])==identity['normalizeSourceSha256'])
check('original_strict_algorithm_and_request_preregistration',identity['method']=='normalize(QueryResult,false).equals(expectedRows)' and not identity['rowOrderMatters'] and identity['boundaryMembershipExact'] and identity['sqlAwareTiesProhibited'] and targets['threshold']=='AT_LEAST_2_OF_3_STRICTLY' and targets['extraRequestsMax']==2 and {x['id']:x['requestLimit'] for x in targets['targets']}=={'T4-001':1,'T3-002':0,'T6-003':1} and all('HTTP200 + SUCCESS + !truncated' in x['originalScorer'] for x in targets['targets']))
def jsonl(p):return [json.loads(x) for x in read(p).decode('utf-8-sig').splitlines()]
calls=jsonl(A/'input-inventory/model-call-ledger.jsonl');requests=jsonl(A/'input-inventory/request-segments.jsonl')
counts=collections.Counter(x['sendEvidence'] for x in calls)
check('all_698_started_calls_keep_654_confirmed_44_unknown',len(calls)==698 and counts=={'CONFIRMED_BY_PROVIDER_RESPONSE':654,'UNKNOWN':44} and all(x['sendEvidence']==('CONFIRMED_BY_PROVIDER_RESPONSE' if x['usageKnown'] else 'UNKNOWN') for x in calls),counts=dict(counts))
call_map={(x['run'],x['id'],x['callNumber']):x for x in calls}
trace_errors=[]
for row in requests:
    if 'firstCallSent' in row or 'correctionSent' in row:trace_errors.append(row['run']+':'+row['id']+':old_label')
    for number,prefix in [(1,'firstCall'),(2,'correction')]:
        call=call_map.get((row['run'],row['id'],number));field='firstCallStarted' if number==1 else 'correctionStarted'
        evidence_field='firstCallSendEvidence' if number==1 else 'correctionSendEvidence'
        if row[field]!=(call is not None) or row[evidence_field]!=(call['sendEvidence'] if call else 'UNKNOWN'):trace_errors.append(row['run']+':'+row['id']+':'+prefix)
check('all_700_request_trace_and_transport_labels_separate',len(requests)==700 and not trace_errors,errors=trace_errors)
slow=[x for x in requests if x['isSlow']];corr=[x for x in slow if x['correctionStarted']]
cc=collections.Counter(x['correctionSendEvidence'] for x in corr)
check('seven_slow_correction_starts_four_confirmed_three_unknown',len(slow)==118 and len(corr)==7 and cc=={'CONFIRMED_BY_PROVIDER_RESPONSE':4,'UNKNOWN':3},counts=dict(cc))
runtime=js(A/'input-inventory/runtime-summary.json')
check('unchanged_unknown_usage_and_cost_lower_bound',runtime['recordedStartedCallCount']==698 and runtime['unknownUsageCalls']==44 and abs(runtime['knownCostSubtotalYuan']-1.60784042)<1e-10 and all(x['knownCostYuan'] is None and x['latencyMs'] is None for x in calls if not x['usageKnown']))
hist=[h for x in jsonl(A/'input-inventory/request-input-inventory.jsonl') for h in x['historicalFirstCallComparison']]
hc=collections.Counter(x['equality'] for x in hist)
check('390_recorded_hashes_and_ten_absent_traces',hc=={'HASH_EQUAL':390,'NO_FIRST_CALL_TRACE':10} and all(x['sendEvidence'] in {'CONFIRMED_BY_PROVIDER_RESPONSE','UNKNOWN'} for x in hist),counts=dict(hc))
with zipfile.ZipFile(A/'attempt1-source.zip') as z:
    old_calls=[json.loads(x) for x in z.read('reports/replan-stage-a-20261119/input-inventory/model-call-ledger.jsonl').decode('utf-8').splitlines()]
    check('call_ledger_raw_evidence_not_rewritten',old_calls==[{k:v for k,v in x.items() if k not in {'sendEvidence','callStarted'}} for x in calls] and all(x['callStarted'] for x in calls))
    old_requests=[json.loads(x) for x in z.read('reports/replan-stage-a-20261119/input-inventory/request-segments.jsonl').decode('utf-8').splitlines()]
    for x in old_requests:x['firstCallStarted']=x.pop('firstCallSent');x['correctionStarted']=x.pop('correctionSent')
    extra={'firstCallTraceState','firstCallSendEvidence','correctionTraceState','correctionSendEvidence'}
    check('request_evidence_only_label_repaired',old_requests==[{k:v for k,v in x.items() if k not in extra} for x in requests])
for filename in ['source-manifest.json','artifacts.sha256.json']:
    m=js(A/'input-inventory'/filename);m=m['files'] if filename=='source-manifest.json' else m
    bad=[]
    for p,h in m.items():
        path=ROOT/p if filename=='source-manifest.json' else A/'input-inventory'/p
        if sha(path)!=(h['sha256'] if isinstance(h,dict) else h):bad.append(p)
    check('inventory_'+filename+'_hashes',not bad,checked=len(m),drift=bad)
free=js(A/'free-verification.json')
check('reuse_audit_time_not_execution_latency',free['runs']['original200']['reusedCompletedReportWithoutReplay'] and not free['runs']['original200']['operationIncludesJvmAndGoldExecution'] and free['runs']['original200']['operationWallSeconds']==.004 and all(sha(x['report'])==x['sha256'] for x in free['runs'].values()) and not free['officialV4RuntimeAdmitted'])
integrity=js(A/'integrity-after.json');preserved=js(A/'preexisting-changes-preserved.json')
check('main_task_integrity_and_prior_changes_records',integrity['passed'] and integrity['checkedFiles']==348 and not integrity['drift'] and integrity['databaseChecked'] and integrity['databaseUnchanged'] and preserved['passed'])
staged=js(A/'staged-byte-verification.json')
check('recorded_staging_byte_preservation',staged['checked']==80 and staged['allExactBytes'] and len(staged['checks'])==80 and all(x['exactBytes'] and x['worktreeSha256']==x['indexSha256'] for x in staged['checks']))
check('package_still_matches_at_end',all(sha(p)==h for p,h in manifest['files'].items()))
result=dict(format='independent-a-attempt2-focused-checks-v1',checks=CHECKS,passed=sum(x['passed'] for x in CHECKS),total=len(CHECKS),reads=READS,reviewedPackageManifestSha256=sha(A/'package-manifest-attempt2.json'),providerCalls=0,databaseCalls=0,forbiddenContentRead=False)
(OUT/'package-checks-attempt2.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':result['passed'],'total':result['total'],'failed':[x['id'] for x in CHECKS if not x['passed']],'columnKinds':dict(kind_counts),'sendEvidence':dict(counts)}))
