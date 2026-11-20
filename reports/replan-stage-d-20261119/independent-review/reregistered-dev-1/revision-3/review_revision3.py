"""Independent revision-3 review; actual batch is never mutated."""
from __future__ import annotations
import copy, hashlib, importlib, io, json, shutil, subprocess, sys, tempfile, time, unittest, zipfile
from pathlib import Path
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[5]
OUT=Path(__file__).resolve().parent
sys.dont_write_bytecode=True
sys.path.insert(0,str(ROOT/'scripts'))
import run_replan_stage_d_reregistered as n
import replan_quota_monitor as q
from datetime import datetime
PLAN='9b32def424104c383d8088cb538ec54698f9bda12bbbb45a3d6472b65db5d41c'
PACKAGE=ROOT/'reports/replan-stage-d-20261119/reregistration-candidate/root-revision-3-free-verification'

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(name,value):
    p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True)
    with p.open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)
def snapshot():return {p.relative_to(ROOT).as_posix():sha(p) for p in n.BATCH.rglob('*') if p.is_file()}
def original_bytes(mapping):
    r=subprocess.run(['git','cat-file','--batch'],cwd=ROOT,input=''.join('HEAD:'+p+'\n' for p in mapping).encode(),stdout=subprocess.PIPE,check=True)
    offset=0
    for p,s in mapping.items():
        end=r.stdout.index(b'\n',offset);fields=r.stdout[offset:end].split();offset=end+1
        assert len(fields)==3 and fields[1]==b'blob',(p,fields)
        size=int(fields[2]);raw=r.stdout[offset:offset+size];offset+=size+1
        assert hashlib.sha256(raw).hexdigest()==s,p
    return len(mapping)

def before():
    assert sha(n.BATCH/'plan.json')==PLAN and not (n.BATCH/n.AUTH_NAME).exists()
    save('actual-batch-before.json',dict(planSha256=PLAN,files=snapshot(),realHumanAuthorizationAbsent=True))

def suite():
    target=OUT/'combined';target.mkdir();(target/'temp').mkdir();tempfile.tempdir=str(target/'temp')
    names=('test_replan_stage_d','test_replan_stage_d_proxy','test_replan_durable_write','test_replan_stage_d_reregistered','test_replan_stable_proxy','test_replan_quota_monitor')
    modules=[importlib.import_module(name) for name in names]
    redirected={}
    for module in modules:
        if hasattr(module,'EVIDENCE'):
            module.EVIDENCE=(target/(module.__name__+'-evidence')).resolve()
            redirected[module.__name__]=str(module.EVIDENCE)
    sources={str(Path(module.__file__).relative_to(ROOT)).replace('\\','/'):sha(module.__file__) for module in modules}
    for name in ('run_replan_stage_d','run_replan_stage_d_reregistered','replan_quota_monitor','replan_stage_d_proxy','replan_stable_proxy','replan_durable_write'):
        p=ROOT/'scripts'/(name+'.py');sources[p.relative_to(ROOT).as_posix()]=sha(p)
    tests=unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromModule(m) for m in modules)
    stream=io.StringIO();started=time.perf_counter();result=unittest.TextTestRunner(stream=stream,verbosity=2).run(tests)
    (target/'combined-suite.log').write_bytes(stream.getvalue().encode())
    assert all(sha(ROOT/p)==s for p,s in sources.items())
    save('combined-suite.json',dict(testsRun=result.testsRun,failures=len(result.failures),errors=len(result.errors),skipped=len(result.skipped),passed=result.wasSuccessful(),elapsedSeconds=time.perf_counter()-started,redirectedEvidence=redirected,sources=sources,providerCalls=0))

def identities():
    plan=q.read(n.BATCH/'plan.json');assert sha(n.BATCH/'plan.json')==PLAN
    assert len(plan['files'])==756
    for p,s in plan['files'].items():assert sha(ROOT/p)==s,p
    nonruntime={p:s for p,s in plan['files'].items() if '/tool-runtime/' not in p};assert len(nonruntime)==108
    original_bytes(nonruntime)
    preflight=q.read(n.BATCH/'preflight.json');assert preflight['passed'] is True and preflight['planSha256']==PLAN
    assert len(preflight['evidence'])==44
    for p,s in preflight['evidence'].items():assert sha(ROOT/p)==s,p
    original_bytes(preflight['evidence'])
    legacy=n.legacy_facts();first=n.first_free_facts();rejected=n.rejected_free_facts()
    delivery=q.read(PACKAGE/'delivery-manifest.json')
    assert delivery['planSha256']==PLAN
    for p,s in delivery['files'].items():assert sha(ROOT/p)==s,p
    original_bytes(delivery['files'])
    archive=q.read(PACKAGE/'final-performance-archive.json');assert sha(ROOT/archive['archive'])==archive['archiveSha256']
    with zipfile.ZipFile(ROOT/archive['archive']) as z:
        assert set(z.namelist())==set(archive['files'])
        for p,s in archive['files'].items():assert hashlib.sha256(z.read(p)).hexdigest()==s,p
    state=q.read(n.BATCH/'state.json');ledger=q.read(n.BATCH/'send-ledger.json');quota=q.read(n.BATCH/'candidate-cost-quota.json',exact=True)
    assert state['requestsStarted']==0 and not state['rounds'] and state['terminal'] is None
    assert ledger['calls']==ledger['rejections']==ledger['breaches']==[]
    assert sha(n.BATCH/'candidate-cost-quota.json')==sha(n.OLD_BATCH/'candidate-cost-quota.json')
    q.validate_seed(q.read(n.BATCH/'candidate-quota-initial-snapshot.json',exact=True),quota)
    assert not (n.BATCH/n.AUTH_NAME).exists() and not (n.BATCH/'human-authorization-consumed.json').exists()
    save('identities.json',dict(planSha256=PLAN,boundFiles=756,nonruntimeGitBytes=108,runtimeFiles=648,preflightGitBytes=44,rootDeliveryFiles=len(delivery['files']),rootArchivedPerformanceFiles=len(archive['files']),legacy=legacy,firstFree=first,rejectedRevision2=rejected,actualPaidStateZero=True,realHumanAuthorizationAbsent=True,providerCalls=0))

def copy_verify():
    target=OUT/'full-verify-copy';target.mkdir()
    for p in n.BATCH.iterdir():
        if p.is_file():shutil.copyfile(p,target/p.name)
    plan=q.read(target/'plan.json');before_sha={p.name:sha(p) for p in target.iterdir() if p.is_file()}
    # No key, vendor or remote credential access; database snapshot is readonly local.
    with patch.object(n.d,'BATCH',target),patch.object(n,'_old_real_key',side_effect=AssertionError('real key forbidden')) as keys:
        timings=[]
        for i in range(3):
            started=time.perf_counter();n.verify(plan,database=i==0);timings.append(round((time.perf_counter()-started)*1000,3))
        before_entry={p.name:sha(p) for p in target.iterdir() if p.is_file()}
        with patch.object(n.d,'run_next',side_effect=AssertionError('paid route forbidden')) as inherited:
            reasons=[]
            for action in (n.run_next,n.guarded_real_key):
                try:action()
                except n.Refused as error:reasons.append(str(error))
                else:raise AssertionError('missing auth accepted')
        assert before_entry=={p.name:sha(p) for p in target.iterdir() if p.is_file()}
        assert keys.call_count==inherited.call_count==0
    save('full-verify-copy.json',dict(planSha256=PLAN,completeVerifyMs=timings,databaseIdentityRechecked=True,independentObservationAppendedInCopyOnly=True,initialTopLevelFiles=before_sha,missingHumanAuthBothBarriers=reasons,entryAndKeyBarrierByteDelta=0,realKeyReaderCalls=0,paidRouteCalls=0,providerCalls=0))

def after():
    before=q.read(OUT/'actual-batch-before.json')['files'];current=snapshot();assert before==current
    assert not (n.BATCH/n.AUTH_NAME).exists() and not (n.BATCH/'human-authorization-consumed.json').exists()
    save('actual-batch-after.json',dict(planSha256=PLAN,allActualBatchBytesAndFileSetUnchanged=True,files=current,realHumanAuthorizationAbsent=True,providerCalls=0))

def performance():
    """Time real gate callbacks at synthetic maximum route storage; zero model delay."""
    target=OUT/'tail-callback-performance';target.mkdir()
    source=PACKAGE/'final-performance-1'/'902'
    for p in source.rglob('*'):
        if p.is_file():
            dest=target/p.relative_to(source);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
    plan_sha=sha(target/'plan.json');steps=[]
    def timed(name,fn):
        began=time.perf_counter();value=fn();steps.append(dict(step=name,elapsedMs=round((time.perf_counter()-began)*1000,3)));return value
    with patch.object(n.d,'BATCH',target),patch.object(n,'_old_real_key',side_effect=AssertionError('real key forbidden')):
        monitor=n.quota_monitor()
        timed('coldMonitorReconstructionAndObserve',lambda:monitor.observe('INDEPENDENT_SCALE_OPEN'))
        gate=timed('sendGateOpenOutsideQuestionBudget',lambda:n.SendGate(target/'send-ledger.json',plan_sha,'F3',clock=lambda:datetime(2026,11,21,12,tzinfo=n.d.BEIJING)))
        start=time.monotonic();timed('QUESTION_BEGIN',lambda:gate.begin(dict(id='INDEPENDENT-TAIL',question='本机合成尾部预算测量'),start+15))
        permits=[];callback_start=time.perf_counter()
        failure=None
        for attempt in (1,2):
            text='=== 用户问题 ===\n本机合成尾部预算测量\n'
            if attempt==2:text+='\n\n=== 上一次 SQL 与评估反馈 ===\nsynthetic correction'
            payload=dict(model='deepseek-flash',temperature=0,max_tokens=8192,messages=[dict(role='system',content='independent synthetic only'),dict(role='user',content=text)])
            raw=q.packed(payload);value=q.read(monitor.runtime,exact=True)
            value['holds']['independent-tail-'+str(attempt)]=dict(requestId='independent-tail-request',callNumber=attempt,promptSha256=q.prompt_sha(payload),status='PENDING')
            # Emulate Java's atomic PENDING persistence, not a model call.
            monitor.runtime.write_bytes(q.packed(q.quota_semantic(value))+b'\n')
            try:permit=timed('BEFORE_'+str(attempt),lambda:gate.before(payload,raw))
            except Exception as error:failure=type(error).__name__+': '+str(error);break
            permits.append(permit)
            usage=dict(prompt_tokens=1,completion_tokens=1,total_tokens=2,prompt_cache_hit_tokens=0,prompt_cache_miss_tokens=1)
            body=q.packed(dict(usage=usage))
            result=dict(responseBodyRaw=body,responseBodySha256=q.digest(body),responseBytes=len(body),responseComplete=True,upstreamAttempted=True,responseJson=dict(usage=usage))
            timed('AFTER_'+str(attempt),lambda:gate.after(permit,result))
            value=q.read(monitor.runtime,exact=True);value['holds'].pop('independent-tail-'+str(attempt))
            value['knownCalls']+=1;value['knownCostYuan']=q.money(q.decimal(value['knownCostYuan'])+q.Decimal('.000005'))
            monitor.runtime.write_bytes(q.packed(q.quota_semantic(value))+b'\n')
        callbacks_ms=(time.perf_counter()-callback_start)*1000
        if failure is None:
            response=dict(status='SUCCESS',budget=dict(requestId='independent-tail-request',modelCalls=2,termination='COMPLETE'))
            meta=target/'http-answers'/'INDEPENDENT-TAIL.json';meta.parent.mkdir();raw=q.packed(response)
            meta.with_suffix('.body.bin').write_bytes(raw)
            meta.write_bytes(q.packed(dict(responseComplete=True,responseBodySha256=q.digest(raw),responseBytes=len(raw))))
            evidence=dict(httpEvidence=meta.relative_to(target).as_posix(),httpEvidenceSha256=sha(meta),responseBodySha256=q.digest(raw),questionId=meta.stem)
            timed('HTTP_RETURN',lambda:monitor.observe('HTTP_RETURN',response=response,evidence=evidence))
        timed('QUESTION_END',gate.end)
        elapsed=(time.monotonic()-start)*1000
    save('tail-callback-performance.json',dict(planSha256=PLAN,fixturePlanSha256=plan_sha,sourceQuestions=902,sourceCalls=1804,
        sourceStorageShape='Worst case full-route two calls per question, not an average or real model run',
        steps=steps,callbackBeforeAfterOnlyMs=round(callbacks_ms,3),questionBeginThroughEndMs=round(elapsed,3),
        gatePermitsReturned=len(permits),failure=failure,remainingOf15000Ms=round(15000-elapsed,3),
        artificialModelOrTransportDelayMs=0,sqlExecutionDelayMs=0,providerCalls=0,keyReads=0,
        notActualP95=True,actualBatchChanged=False))

def reconstruct_real():
    results=[]
    for case in ('ready-only','known-one'):
        source=PACKAGE/'real-monitor-integration-1'/case;target=OUT/'real-jar-evidence-reconstruction'/case
        shutil.copytree(source,target)
        before={p.relative_to(source).as_posix():sha(p) for p in source.rglob('*') if p.is_file()}
        with patch.object(n.d,'BATCH',target),patch.object(n,'_old_real_key',side_effect=AssertionError('real key forbidden')):
            m=n.quota_monitor();started=time.perf_counter();quota=m.observe('INDEPENDENT_REAL_JAR_SOURCE_RECONSTRUCTION');elapsed=(time.perf_counter()-started)*1000
            expected_calls=67 if case=='ready-only' else 68
            assert quota['knownCalls']==expected_calls and len(quota['holds'])==3
            assert all(v['status']=='UNKNOWN' for v in quota['holds'].values())
            assert quota['knownCostYuan']==('0.16312188' if case=='ready-only' else '0.16317188')
        # This process is a new independent observer; the original raw fixture stays read-only.
        assert before=={p.relative_to(source).as_posix():sha(p) for p in source.rglob('*') if p.is_file()}
        results.append(dict(case=case,sourceRawFilesVerified=len(before),sourceFileSetAndBytesUnchanged=True,independentFreshMonitorPassed=True,knownCalls=quota['knownCalls'],knownCostYuan=quota['knownCostYuan'],unknownHolds=3,elapsedMs=round(elapsed,3)))
    save('real-jar-evidence-reconstruction.json',dict(results=results,actualJavaRestartedByReview=False,rootActualJarRawEvidenceGitVerified=True,providerCalls=0,keyReads=0))

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    globals()[sys.argv[1]]()
