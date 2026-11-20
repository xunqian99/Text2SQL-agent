"""Seal owned independent review evidence; never alter production registration."""
from __future__ import annotations
import hashlib,json,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[5];OUT=Path(__file__).resolve().parent
PLAN='9b32def424104c383d8088cb538ec54698f9bda12bbbb45a3d6472b65db5d41c'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(name,value):
    with (OUT/name).open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)

if __name__=='__main__':
    assert subprocess.run(['git','diff','--cached','--quiet'],cwd=ROOT).returncode==0
    before=json.loads((OUT/'actual-batch-before.json').read_bytes());after=json.loads((OUT/'actual-batch-after.json').read_bytes())
    assert before['files']==after['files'] and after['allActualBatchBytesAndFileSetUnchanged']
    for relative,expected in after['files'].items():assert sha(ROOT/relative)==expected,relative
    batch=ROOT/'reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-3'
    assert not (batch/'human-paid-authorization.json').exists()
    performance=OUT/'tail-callback-performance'
    raw={p.relative_to(OUT).as_posix():sha(p) for p in sorted(performance.rglob('*')) if p.is_file()}
    archive=OUT/'tail-callback-performance-evidence.zip'
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for relative in raw:z.write(OUT/relative,relative)
    with zipfile.ZipFile(archive) as z:
        assert set(z.namelist())==set(raw)
        for relative,expected in raw.items():assert hashlib.sha256(z.read(relative)).hexdigest()==expected
    save('tail-callback-performance-archive.json',dict(archive=archive.relative_to(ROOT).as_posix(),archiveSha256=sha(archive),fileCount=len(raw),files=raw,originalFilesRetained=True,archiveRoundTripAllMatched=True,scope='Only own worst-case synthetic performance fixture; no actual paid output',providerCalls=0))
    files={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(OUT.rglob('*')) if p.is_file() and not p.is_relative_to(performance)}
    save('source-evidence-manifest.json',dict(format='replan-d-reregistered-independent-review-v3',planSha256=PLAN,files=files,fileCount=len(files),rawPerformanceArchivedFiles=len(raw),rawPerformanceArchive='tail-callback-performance-evidence.zip',providerCalls=0))
    save('decision.json',dict(format='replan-d-independent-decision-v1',registration='batch-1/reregistered-dev-1/revision-3',accepted=False,decision='REJECT_REREGISTERED_D_ENGINEERING_ADMISSION',planSha256=PLAN,
        implementationCommit='b178a4b2fe454cf5b5df90e4f62de665fce1fe6c',rootFreePackageCommit='08927fe86f95856700a5beff5b60de3eaeec5a5b',
        evidenceManifestSha256=sha(OUT/'source-evidence-manifest.json'),
        blockingFindings=[dict(id='DR3-SYNCHRONOUS-QUOTA-OBSERVATION-BUDGET',priority=1,source='scripts/run_replan_stage_d_reregistered.py:553',
            evidence='tail-callback-performance.json',reason='Worst-case full-route storage causes real synchronous two-call gate callbacks to consume about 10.75 seconds with zero model/network/SQL delay; current 15-second constraints lack adequate operating margin',
            twoBeforeAfterIntervalMs=10757.916,fourIndividualCallbacksSumMs=10749.717,beginThroughEndMs=14069.733,
            includesGateBeginBeforeJavaHttp=True,httpReturnAndEndAfterOriginalReadCompletion=True,
            modelP95Measured=False,worstCaseNotAverage=True,controlledOsColdCache=False)],
        resolvedPriorFindings=['DR2-CANDIDATE-QUOTA-ROLLBACK','DR2-FIRST-START-EXACT-SEED-FALSE-REJECT'],
        verified=dict(boundFiles=756,nonruntimeGitBytes=108,runtimeFiles=648,preflightGitBytes=44,rootDeliveryGitFiles=597,rootPerformanceArchiveFiles=6041,
            suppliedTestsRun=166,suppliedTestsPassed=166,suppliedFailures=0,suppliedErrors=0,suppliedSkipped=0,independentAdditionalBoundaryCases=7,
            originalActualJarFixtureFreshReconstructions=2,databaseIdentityRecheckedInOwnCopy=True,realAuthorizationMissingBothBarriers=True,
            actualBatchFullFileSetAndBytesUnchanged=True),
        sourceHelperSha256='20871566cf63cee846e5e7813f23d267fe22e3340f458258cfd7767286d2db1a',sourceDriverSha256='7fb43d4450b5db061ac544694ed5a7b0207399dd18b2d0595d51920b2a6da277',
        safetyFixesDoNotEstablishRuntimeNetBenefit=True,wholeDirectoryCoordinatedRollbackResistance=False,partialCommitRecovery=False,
        priorDecisionsUnchanged=True,realHumanAuthorizationAbsent=True,paidAuthorized=False,engineeringAcceptanceWouldNotAuthorizePayment=True,
        oldBatchInterruptedNoReplay=True,modelAccuracyAccepted=False,unseenAdmitted=False,stage2Accepted=False,stage3Resumed=False,providerCalls=0,providerTokens=0,providerCostYuan='0'))
    paths=[ROOT/relative for relative in files]+[OUT/'source-evidence-manifest.json',OUT/'decision.json']
    pathspec=OUT/'.git-pathspec'
    pathspec.write_bytes(b''.join(p.relative_to(ROOT).as_posix().encode()+b'\0' for p in paths))
    try:subprocess.run(['git','-c','core.longpaths=true','-c','core.autocrlf=false','add','--force','--pathspec-from-file='+str(pathspec),'--pathspec-file-nul'],cwd=ROOT,check=True)
    finally:pathspec.unlink()
    print(json.dumps(dict(stagedFiles=len(paths),archivedPerformanceRawFiles=len(raw),manifestSha256=sha(OUT/'source-evidence-manifest.json'),decisionSha256=sha(OUT/'decision.json'))))
