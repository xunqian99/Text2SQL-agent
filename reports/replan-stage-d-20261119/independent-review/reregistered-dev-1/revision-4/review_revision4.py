"""Independent revision-4 review; actual registration is read-only."""
from __future__ import annotations
import hashlib,importlib,io,json,shutil,subprocess,sys,tempfile,time,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[5];OUT=Path(__file__).resolve().parent
sys.dont_write_bytecode=True;sys.path.insert(0,str(ROOT/'scripts'))
import run_replan_stage_d_reregistered as n
import replan_quota_monitor as q
PACKAGE=n.CANDIDATE/'root-revision-4-free-verification'
HELPER='35b86ea00752c4e4a986480c8be266e87345c0617bd041214cbf17f53e7cb9db'
DRIVER='bda005614aa90c7f872f9451d0654a958204b2d4f5f807038d999245892d1d4e'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(name,value):
    p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True)
    with p.open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)
def actual_snapshot():return {p.relative_to(ROOT).as_posix():sha(p) for p in n.BATCH.rglob('*') if p.is_file()}
def git_bytes(mapping):
    result=subprocess.run(['git','cat-file','--batch'],cwd=ROOT,input=''.join('HEAD:'+p+'\n' for p in mapping).encode(),stdout=subprocess.PIPE,check=True);offset=0
    for p,s in mapping.items():
        end=result.stdout.index(b'\n',offset);fields=result.stdout[offset:end].split();offset=end+1
        assert len(fields)==3 and fields[1]==b'blob',(p,fields)
        size=int(fields[2]);raw=result.stdout[offset:offset+size];offset+=size+1
        assert hashlib.sha256(raw).hexdigest()==s,p
    return len(mapping)
def before():
    assert sha(Path(q.__file__))==HELPER and sha(Path(n.__file__))==DRIVER
    assert not (n.BATCH/n.AUTH_NAME).exists()
    assert sha(n.BATCH/'plan.json')=='da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe'
    save('actual-batch-before.json',dict(planSha256=sha(n.BATCH/'plan.json'),files=actual_snapshot(),realHumanAuthorizationAbsent=True,helperSha256=HELPER,driverSha256=DRIVER))
def suite():
    target=OUT/'combined';target.mkdir();(target/'temp').mkdir();tempfile.tempdir=str(target/'temp')
    names=('test_replan_stage_d','test_replan_stage_d_proxy','test_replan_durable_write','test_replan_stage_d_reregistered','test_replan_stable_proxy','test_replan_quota_monitor')
    modules=[importlib.import_module(name) for name in names];redirected={}
    for module in modules:
        if hasattr(module,'EVIDENCE'):
            module.EVIDENCE=(target/(module.__name__+'-evidence')).resolve();redirected[module.__name__]=str(module.EVIDENCE)
    sources={Path(m.__file__).relative_to(ROOT).as_posix():sha(m.__file__) for m in modules}
    sources.update({Path(m.__file__).relative_to(ROOT).as_posix():sha(m.__file__) for m in (q,n)})
    tests=unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromModule(m) for m in modules)
    output=io.StringIO();started=time.perf_counter();result=unittest.TextTestRunner(stream=output,verbosity=2).run(tests)
    (target/'combined-suite.log').write_bytes(output.getvalue().encode())
    assert all(sha(ROOT/p)==s for p,s in sources.items())
    save('combined-suite.json',dict(testsRun=result.testsRun,failures=len(result.failures),errors=len(result.errors),skipped=len(result.skipped),passed=result.wasSuccessful(),elapsedSeconds=time.perf_counter()-started,redirectedEvidence=redirected,sources=sources,providerCalls=0))
def copy_verify():
    target=OUT/'full-verify-copy';target.mkdir()
    for source in n.BATCH.iterdir():
        if source.is_file():shutil.copyfile(source,target/source.name)
    plan=q.read(target/'plan.json');times=[]
    with patch.object(n.d,'BATCH',target),patch.object(n,'_old_real_key',side_effect=AssertionError('real key forbidden')) as keys:
        for i in range(3):
            started=time.perf_counter();n.verify(plan,database=i==0);times.append(round((time.perf_counter()-started)*1000,3))
        before_entry={p.name:sha(p) for p in target.iterdir() if p.is_file()}
        reasons=[]
        with patch.object(n.d,'run_next',side_effect=AssertionError('paid route forbidden')) as route:
            for action in (n.run_next,n.guarded_real_key):
                try:action()
                except n.Refused as error:reasons.append(str(error))
                else:raise AssertionError('missing authorization admitted')
        assert before_entry=={p.name:sha(p) for p in target.iterdir() if p.is_file()}
        assert keys.call_count==route.call_count==0
    save('full-verify-copy.json',dict(planSha256=sha(n.BATCH/'plan.json'),completeVerifyMs=times,databaseIdentityRechecked=True,ownCopyOnly=True,missingAuthBothBarrierRefusals=reasons,missingAuthDirectoryByteDelta=0,keyReaderCalls=0,paidRouteCalls=0,providerCalls=0))
def identities():
    before=q.read(OUT/'actual-batch-before.json');plan=q.read(n.BATCH/'plan.json')
    assert sha(n.BATCH/'plan.json')==before['planSha256']
    for p,s in plan['files'].items():assert sha(ROOT/p)==s,p
    nonruntime={p:s for p,s in plan['files'].items() if '/tool-runtime/' not in p};git_bytes(nonruntime)
    pre=q.read(n.BATCH/'preflight.json');assert pre['passed'] is True and pre['planSha256']==before['planSha256']
    for p,s in pre['evidence'].items():assert sha(ROOT/p)==s,p
    git_bytes(pre['evidence'])
    legacy=n.legacy_facts();first=n.first_free_facts();rev2=n.rejected_free_facts();rev3=n.rejected_revision3_facts()
    delivery=q.read(PACKAGE/'delivery-manifest.json');assert delivery['planSha256']==before['planSha256']
    for p,s in delivery['files'].items():assert sha(ROOT/p)==s,p
    git_bytes(delivery['files'])
    archives=[]
    for p in PACKAGE.glob('*archive*.json'):
        meta=q.read(p)
        if 'archive' not in meta or 'files' not in meta:continue
        archive=ROOT/meta['archive'];assert sha(archive)==meta['archiveSha256']
        with zipfile.ZipFile(archive) as z:
            assert set(z.namelist())==set(meta['files'])
            for name,s in meta['files'].items():assert hashlib.sha256(z.read(name)).hexdigest()==s,name
        archives.append(dict(manifest=p.relative_to(ROOT).as_posix(),files=len(meta['files']),archiveSha256=sha(archive)))
    state=q.read(n.BATCH/'state.json');ledger=q.read(n.BATCH/'send-ledger.json')
    assert state['requestsStarted']==0 and state['rounds']=={} and state['terminal'] is None
    assert ledger['calls']==ledger['breaches']==ledger['rejections']==[]
    assert sha(n.BATCH/'candidate-cost-quota.json')==sha(n.OLD_BATCH/'candidate-cost-quota.json')
    assert not (n.BATCH/n.AUTH_NAME).exists() and not (n.BATCH/'human-authorization-consumed.json').exists()
    save('identities.json',dict(planSha256=before['planSha256'],boundFiles=len(plan['files']),nonruntimeGitBytes=len(nonruntime),runtimeFiles=len(plan['files'])-len(nonruntime),preflightGitBytes=len(pre['evidence']),rootDeliveryFiles=len(delivery['files']),archives=archives,legacy=legacy,firstFree=first,rejectedRevision2=rev2,rejectedRevision3=rev3,realPaidStateZero=True,realHumanAuthorizationAbsent=True,providerCalls=0))
def after():
    before=q.read(OUT/'actual-batch-before.json');current=actual_snapshot();assert before['files']==current
    assert not (n.BATCH/n.AUTH_NAME).exists() and not (n.BATCH/'human-authorization-consumed.json').exists()
    save('actual-batch-after.json',dict(planSha256=before['planSha256'],files=current,allActualBatchBytesAndFileSetUnchanged=True,realHumanAuthorizationAbsent=True,providerCalls=0))
def reconstruct_real():
    results=[]
    for case in ('ready-only','known-one'):
        source=PACKAGE/'real-monitor-integration-1'/case;target=OUT/'real-jar-evidence-reconstruction'/case
        shutil.copytree(source,target)
        protected={p.relative_to(source).as_posix():sha(p) for p in source.rglob('*') if p.is_file()}
        with patch.object(n.d,'BATCH',target),patch.object(n,'_old_real_key',side_effect=AssertionError('real key forbidden')):
            began=time.perf_counter();quota=n.quota_monitor().observe('INDEPENDENT_FINAL_SOURCE_FULL_RECONSTRUCTION');elapsed=(time.perf_counter()-began)*1000
            assert quota['knownCalls']==(67 if case=='ready-only' else 68)
            assert quota['knownCostYuan']==('0.16312188' if case=='ready-only' else '0.16317188')
            assert len(quota['holds'])==3 and all(v['status']=='UNKNOWN' for v in quota['holds'].values())
        assert protected=={p.relative_to(source).as_posix():sha(p) for p in source.rglob('*') if p.is_file()}
        results.append(dict(case=case,sourceRawFileCount=len(protected),sourceRawFileSetAndBytesUnchanged=True,
            newIndependentMonitorReconstructionPassed=True,knownCalls=quota['knownCalls'],knownCostYuan=quota['knownCostYuan'],unknownHolds=3,elapsedMs=round(elapsed,3)))
    save('real-jar-evidence-reconstruction.json',dict(results=results,helperSha256=HELPER,rootFinalSourceActualJarEvidenceIndependentlyHashChecked=True,
        actualJavaRestartedByReview=False,providerCalls=0,keyReads=0,actualBatchTouched=False))
if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8');globals()[sys.argv[1]]()
