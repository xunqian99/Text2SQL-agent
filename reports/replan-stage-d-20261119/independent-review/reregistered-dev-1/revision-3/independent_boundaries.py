"""Additional independent lifecycle checks, only owned synthetic batch copies."""
from __future__ import annotations
import copy,json,shutil,subprocess,sys
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[5];OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'scripts'))
import run_replan_stage_d_reregistered as n
import replan_quota_monitor as q
RESULTS=[]

def plain(p,v):p.write_bytes(q.packed(v)+b'\n')
def fixture(name):
    p=OUT/'independent-boundaries'/name;p.mkdir(parents=True)
    for source in n.BATCH.iterdir():
        if source.is_file():shutil.copyfile(source,p/source.name)
    return p
def start(m):
    state=q.read(m.state);state['rounds']={'DEV_F':dict(status='RUNNING',side='F')}
    m.persist(m.state,state);m.observe('APPLICATION_ENTER',update=lambda k,v:k.update(runtimeStarted=True,activeSide='F'))
def child(p):
    code="""import json,sys
from pathlib import Path
sys.dont_write_bytecode=True
sys.path.insert(0,sys.argv[1])
import run_replan_stage_d_reregistered as n
import replan_quota_monitor as q
n.d.BATCH=Path(sys.argv[2])
try:n.check_candidate_quota()
except q.QuotaRefused as e:print(json.dumps(dict(refused=True,reason=str(e))))
else:print(json.dumps(dict(refused=False)))
"""
    result=subprocess.run([sys.executable,'-B','-c',code,str(ROOT/'scripts'),str(p)],cwd=ROOT,
        env={'SYSTEMROOT':r'C:\Windows','TEMP':str(p),'TMP':str(p)},capture_output=True,check=True)
    (p/'child.stdout.json').write_bytes(result.stdout)
    return json.loads(result.stdout)
def rollback_cases():
    for name,kind in (('cross-process-known-rollback','known'),('cross-process-new-unknown-removal','unknown')):
        p=fixture(name)
        with patch.object(n.d,'BATCH',p):
            m=n.quota_monitor();start(m);seed=q.read(m.runtime,exact=True);value=copy.deepcopy(seed)
            if kind=='known':value['knownCalls']+=100;value['knownCostYuan']='.66312188'
            else:value['holds']['independent-new-unknown']=dict(requestId='own-new',callNumber=1,promptSha256='a'*64,status='UNKNOWN')
            plain(m.runtime,q.quota_semantic(value));m.observe('INDEPENDENT_LIABILITY')
            plain(m.runtime,q.quota_semantic(seed));cold=child(p);assert cold['refused']
            try:q.QuotaMonitor(p,n.sha(p/'plan.json'),n.raw_write).observe('RESTORE_AFTER_REFUSAL')
            except q.QuotaRefused as e:sealed=str(e)
            else:raise AssertionError('persistent stop disappeared')
            assert 'Persisted quota' in sealed
            RESULTS.append(dict(case=name,passed=True,child=cold,persistedStopAfterRuntimeRestore=sealed))

def pending_pair(valid):
    p=fixture('two-pending-exact-settlement' if valid else 'two-pending-under-counted-settlement')
    with patch.object(n.d,'BATCH',p):
        m=n.quota_monitor();start(m)
        gate=n.SendGate(p/'send-ledger.json',n.sha(p/'plan.json'),'DEV_F',clock=lambda:datetime(2026,11,21,12,tzinfo=n.d.BEIJING),monotonic=lambda:10.)
        gate.begin(dict(id='OWN-PAIR',question='独立双预留'),25.)
        for attempt in (1,2):
            user='=== 用户问题 ===\n独立双预留\n'
            if attempt==2:user+='\n\n=== 上一次 SQL 与评估反馈 ===\nsynthetic'
            payload=dict(model='deepseek-flash',temperature=0,max_tokens=8192,messages=[dict(role='system',content='independent'),dict(role='user',content=user)])
            value=q.read(m.runtime,exact=True);value['holds']['pair-'+str(attempt)]=dict(requestId='pair-request',callNumber=attempt,promptSha256=q.prompt_sha(payload),status='PENDING')
            plain(m.runtime,q.quota_semantic(value));permit=gate.before(payload,q.packed(payload))
            body=q.packed(dict(usage=dict(prompt_tokens=1,completion_tokens=1,total_tokens=2,prompt_cache_hit_tokens=0,prompt_cache_miss_tokens=1)))
            gate.after(permit,dict(responseBodyRaw=body,responseBodySha256=q.digest(body),responseBytes=len(body),responseComplete=True,upstreamAttempted=True,responseJson=q.parse(body)))
        value=q.read(m.runtime,exact=True)
        for identity in ('pair-1','pair-2'):value['holds'].pop(identity)
        value['knownCalls']+=2 if valid else 1
        value['knownCostYuan']=q.money(q.decimal(value['knownCostYuan'])+q.Decimal('.000010' if valid else '.000005'))
        plain(m.runtime,q.quota_semantic(value))
        try:m.observe('INDEPENDENT_TWO_SETTLED')
        except q.QuotaRefused as e:
            assert not valid;reason=str(e)
        else:
            assert valid;reason=None
            latest=m._load()[1]['knowledge'];assert all(latest['retired'][i]['outcome']=='KNOWN' for i in ('pair-1','pair-2'))
            assert len(q.read(m.runtime)['holds'])==3
        RESULTS.append(dict(case=p.name,passed=True,validPairAccepted=valid,refusal=reason,oldUnknownsStillPresent=3))

def every_anchor_failure():
    for failed in ('state.json','send-ledger.json','candidate-quota-checkpoint.json'):
        p=fixture('partial-commit-'+failed)
        with patch.object(n.d,'BATCH',p):
            m=n.quota_monitor();original=m.writer
            def fail(path,value,**kwargs):
                if path.name==failed:raise PermissionError('independent injected failure')
                return original(path,value,**kwargs)
            m.writer=fail
            try:m.observe('INDEPENDENT_PARTIAL_WRITE')
            except PermissionError:pass
            else:raise AssertionError('injected partial write not reached')
            try:q.QuotaMonitor(p,n.sha(p/'plan.json'),n.raw_write).observe('RESTART')
            except q.QuotaRefused as e:reason=str(e)
            else:raise AssertionError('partial commit resumed')
            RESULTS.append(dict(case=p.name,passed=True,restartRefused=reason))

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    with patch.object(n,'_old_real_key',side_effect=AssertionError('real key forbidden')):
        rollback_cases();pending_pair(True);pending_pair(False);every_anchor_failure()
    with (OUT/'independent-boundaries.json').open('x',encoding='utf-8') as f:json.dump(dict(results=RESULTS,passed=len(RESULTS),providerCalls=0,realAuthorizationCreated=False),f,ensure_ascii=False,indent=2)
