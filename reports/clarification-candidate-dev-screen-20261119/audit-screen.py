import hashlib,json,sys,subprocess
from pathlib import Path
from decimal import Decimal
from datetime import datetime,timedelta,timezone
sys.path.insert(0,'scripts')
from summarize_request_budget import audit_run
from summarize_holdout import dataset_hash,ids_hash
folder=Path('reports/clarification-candidate-dev-screen-20261119')
read=lambda p:json.loads(Path(p).read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
m=read(folder/'manifest.json'); errors=[]
archive_mode='--archive' in sys.argv
def require(ok,message):
    if not ok: errors.append(message)
require(m['state']=='COMPLETE' and m['exitCode']==0,'process not complete')
if archive_mode:
    require(subprocess.run(['git','merge-base','--is-ancestor',m['codeCommit'],'HEAD'],capture_output=True).returncode==0,'measurement commit is not an ancestor')
else:
    require(subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()==m['codeCommit'],'HEAD changed')
for key in ('protectedSha256','runtimeSha256'):
    for p,h in m[key].items():
        if archive_mode and p=='.gitattributes':
            # The archive adds a byte-preservation rule after measurement; audit the original policy.
            frozen=subprocess.check_output(['git','show',m['codeCommit']+':'+p])
            require(hashlib.sha256(frozen).hexdigest()==h,'original attributes differ')
        else:
            require(Path(p).is_file() and sha(p)==h,'frozen bytes changed: '+p)
reports=list(folder.glob('eval-*.json')); require(len(reports)==1,'report count differs')
report=read(reports[0]); meta=report['meta']; items=report['itemEvidence']
require(sha(reports[0])==m['reportSha256'],'final report SHA differs')
for name,h in m['captureSha256'].items(): require(sha(folder/name)==h,'captured input changed: '+name)
require(sha(folder/m['goldReport'])==m['goldSha256'],'recovery Gold changed')
require(read(folder/m['goldReport'])['overall']['correct']==200,'recovery Gold failed')
before_folder=Path('reports/request-scope-dev-screen-20261118')
before=read(next(before_folder.glob('eval-*.json')))
old={i['id']:i for i in before['itemEvidence']}
capture=read(folder/'prompt-capture-dev.json'); captured={i['id']:i for i in capture['items']}
require(meta['gitCommit']==m['codeCommit'],'runtime source commit differs')
require(meta['datasetSha256']==capture['datasetSha256']==dataset_hash('data/eval'),'dataset hash differs')
require(meta['sampleIdsSha256']==capture['sampleIdsSha256']==ids_hash(old),'sample hash differs')
require(len(items)==100 and {i['id'] for i in items}==set(old),'question set differs')
for k in ('model','baseUrl','dryRun','itemCount','limit','layers','split','includeDataProfile','includeForeignKeys','limitMode','maxRows','schemaTableCount','retrievalEnabled','retrievalTopK','joinHintsEnabled','productionSelfCorrectionEnabled','productionSelfCorrectionMaxAttempts','selfCorrectionEnabled','selfCorrectionMaxAttempts'):
    require(meta[k]==before['meta'][k],'metadata differs: '+k)
cfg=meta['effectiveConfig']; old_cfg=before['meta']['effectiveConfig']
require(set(cfg)==set(old_cfg),'configuration key set differs')
for k in old_cfg:
    if k!='metricDefinitionsSha256': require(cfg.get(k)==old_cfg[k],'configuration drift: '+k)
metric_sha=sha('src/main/resources/schema/metrics.yml')
require(cfg['metricDefinitionsSha256']==metric_sha==sha('target/classes/schema/metrics.yml'),'candidate metric resource differs')
require(meta['promptVersion']==m['expectedPromptVersion']=='p19-request-scope-v19','candidate prompt version differs')
require(metric_sha==m['protectedSha256']['src/main/resources/schema/metrics.yml'],'metric differs from preregistration')
matched=0
for i in items:
    for k in ('questionSha256','goldSqlSha256','goldResultSha256'):
        require(i[k]==old[i['id']][k],i['id']+': original input/Gold changed')
    if i['calls']:
        ok=i['calls'][0]['promptSha256']==captured[i['id']]['promptSha256']
        require(ok,i['id']+': actual first prompt differs'); matched+=ok
ledger=[json.loads(line) for p in sorted((folder/'llm-calls').glob('*.jsonl')) for line in p.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
group=audit_run(report,ledger,True,require)
for c in ledger:
    require(c['configuredPromptVersion']==meta['promptVersion'],'ledger prompt differs')
    require(c['configuredReasoningEffort']=='provider-default','ledger reasoning differs')
    stamp=datetime.fromisoformat(c['timestamp']).astimezone(timezone(timedelta(hours=8)))
    require(stamp>=datetime.fromisoformat(m['notBeforeBeijing']),'before registered start')
    for t in (stamp,stamp+timedelta(seconds=120)):
        require(not(t.weekday()<5 and (9<=t.hour<12 or 14<=t.hour<18)),'peak/reserve violation')
known=[c for c in ledger if c['usageKnown']]
known_keys={(c['requestId'],c['callNumber']) for c in known}
expected_keys={(i['budget']['requestId'],c['number']) for i in items for c in i['calls']}
group['unresolvedUsageCalls']=len(expected_keys-known_keys)
group['fullKnownConfiguredCostYuan']=float(sum(Decimal(str(c['costYuan'])) for c in known))
group['fullKnownTokens']={k:sum(c[k] for c in known) for k in ('promptTokens','completionTokens','totalTokens','cacheHitTokens','cacheMissTokens')}
gates=dict(correctAtLeast71=group['correct']>=71,deepAtLeast4=report['byLayer']['multi_join_deep']['correct']>=4,businessAtLeast6=report['byLayer']['business_semantics']['correct']>=6,timeoutsAtMost9=group['statusCounts'].get('REQUEST_TIMEOUT',0)<=9,budgetAndCleanup=not group['boundedReturnViolations'] and group['cancellationRejected']==0,evidence=not errors)
changes=[dict(id=i['id'],beforeCorrect=old[i['id']]['correct'],afterCorrect=i['correct'],beforeStatus=old[i['id']]['status'],afterStatus=i['status'],beforeSql=old[i['id']]['finalSql'],afterSql=i['finalSql'],firstPromptUnchanged=bool(i['calls'] and old[i['id']]['calls'] and i['calls'][0]['promptSha256']==old[i['id']]['calls'][0]['promptSha256'])) for i in items if i['correct']!=old[i['id']]['correct']]
targets=[{k:i[k] for k in ('id','status','correct','finalSql')} for i in items if i['id'] in ('T3-020','T4-021','T4-027','T5-006','T5-015','T6-025')]
summary=dict(format='clarification-candidate-dev-screen-v1',codeCommit=m['codeCommit'],report=reports[0].name,reportSha256=sha(reports[0]),passedEvidenceAudit=not errors,errors=errors,actualFirstPromptsMatched=matched,group=group,screenGates=gates,candidateForConfirmation=all(gates.values()),stageAccepted=False,stage3Deferred=True,latestFormalAccuracyPercent=78.0,formalMinimumPercent=89,registeredResourceChange=dict(before=old_cfg['metricDefinitionsSha256'],candidate=metric_sha),targets=targets,gained=[c['id'] for c in changes if c['afterCorrect']],lost=[c['id'] for c in changes if not c['afterCorrect']],lostWithUnchangedFirstPrompt=[c['id'] for c in changes if not c['afterCorrect'] and c['firstPromptUnchanged']],limits=['Historical comparison does not establish causal attribution.','Unknown usage is not zero cost; development scores do not replace formal evaluation.'])
for name,value in (('summary',summary),('paired-changes',changes)):
    (folder/(name+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in summary.items() if k not in ('group',)},ensure_ascii=True,indent=2))
print(json.dumps({k:group[k] for k in ('correct','sqlValid','calls','wallAvgMs','wallP95Ms','statusCounts','unresolvedUsageCalls','fullKnownConfiguredCostYuan','fullKnownTokens')},ensure_ascii=True,indent=2))
raise SystemExit(0 if not errors else 1)
