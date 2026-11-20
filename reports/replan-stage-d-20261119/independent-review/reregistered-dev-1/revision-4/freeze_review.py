"""Seal only owned revision-4 evidence; never mutate actual registration."""
from __future__ import annotations
import hashlib,json,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[5];OUT=Path(__file__).resolve().parent
PLAN='da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe'
HELPER='35b86ea00752c4e4a986480c8be266e87345c0617bd041214cbf17f53e7cb9db'
DRIVER='bda005614aa90c7f872f9451d0654a958204b2d4f5f807038d999245892d1d4e'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(name,value):
    with (OUT/name).open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)
def git_bytes(mapping,revision):
    command=['git','-c','core.longpaths=true','cat-file','--batch']
    result=subprocess.run(command,cwd=ROOT,input=''.join(revision+p+'\n' for p in mapping).encode(),stdout=subprocess.PIPE,check=True)
    offset=0
    for p,expected in mapping.items():
        end=result.stdout.index(b'\n',offset);fields=result.stdout[offset:end].split();offset=end+1
        assert len(fields)==3 and fields[1]==b'blob',(p,fields)
        size=int(fields[2]);raw=result.stdout[offset:offset+size];offset+=size+1
        assert hashlib.sha256(raw).hexdigest()==expected,p
    assert offset==len(result.stdout)
    return len(mapping)
if __name__=='__main__':
    assert subprocess.run(['git','diff','--cached','--quiet'],cwd=ROOT).returncode==0
    assert sha(ROOT/'scripts/replan_quota_monitor.py')==HELPER
    assert sha(ROOT/'scripts/run_replan_stage_d_reregistered.py')==DRIVER
    before=json.loads((OUT/'actual-batch-before.json').read_bytes());after=json.loads((OUT/'actual-batch-after.json').read_bytes())
    assert before['files']==after['files'] and after['allActualBatchBytesAndFileSetUnchanged']
    batch=ROOT/'reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4'
    current={p.relative_to(ROOT).as_posix():sha(p) for p in batch.rglob('*') if p.is_file()}
    assert current==after['files'] and len(current)==380
    assert not (batch/'human-paid-authorization.json').exists() and not (batch/'human-authorization-consumed.json').exists()
    assert sha(batch/'plan.json')==PLAN
    performance=OUT/'final-source-performance'
    raw={p.relative_to(OUT).as_posix():sha(p) for p in sorted(performance.rglob('*')) if p.is_file()}
    archive=OUT/'final-source-performance-evidence.zip'
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for relative in raw:z.write(OUT/relative,relative)
    with zipfile.ZipFile(archive) as z:
        assert set(z.namelist())==set(raw)
        for relative,expected in raw.items():assert hashlib.sha256(z.read(relative)).hexdigest()==expected
    save('final-source-performance-archive.json',dict(archive=archive.relative_to(ROOT).as_posix(),archiveSha256=sha(archive),fileCount=len(raw),files=raw,originalFilesRetained=True,archiveRoundTripAllMatched=True,scope='Only owned final-source synthetic performance fixtures; no actual paid output',providerCalls=0))
    excluded={OUT/'source-evidence-manifest.json',OUT/'decision.json'}
    files={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(OUT.rglob('*')) if p.is_file() and not p.is_relative_to(performance) and p not in excluded}
    save('source-evidence-manifest.json',dict(format='replan-d-reregistered-independent-review-v4',planSha256=PLAN,sourceHelperSha256=HELPER,sourceDriverSha256=DRIVER,files=files,fileCount=len(files),rawPerformanceArchivedFiles=len(raw),rawPerformanceArchive='final-source-performance-evidence.zip',nestedSyntheticDecisionsIncluded=True,providerCalls=0))
    save('decision.json',dict(format='replan-d-independent-decision-v1',registration='batch-1/reregistered-dev-1/revision-4',accepted=True,decision='ACCEPT_REREGISTERED_D_ENGINEERING_ADMISSION_ONLY',planSha256=PLAN,
        implementationCommit='495ebc3af03951e90df8a45786789421d861c9c3',rootFreePackageCommit='df381efc897fa7f455fbb8e882307c93f6f5eaa8',
        rootDeliveryManifestSha256='84a21edeeb92adef4ade1b2dc13f0ff7c48c868e3a8ff0b2b4dc63f848e56b29',
        evidenceManifestSha256=sha(OUT/'source-evidence-manifest.json'),sourceHelperSha256=HELPER,sourceDriverSha256=DRIVER,
        blockingFindings=[],resolvedPriorFindings=['DR2-CANDIDATE-QUOTA-ROLLBACK','DR2-FIRST-START-EXACT-SEED-FALSE-REJECT','DR3-SYNCHRONOUS-QUOTA-OBSERVATION-BUDGET'],
        residualFindings=[dict(id='DR4-INTERNAL-CALLBACK-1000MS-TARGET-MISSED',blockingEngineeringAdmission=False,evidence='final-source-performance.json',scale=902,measuredMs=1014.717,targetMs=1000,targetPassed=False,overrunMs=14.717,overrunPercent=1.4717,
            reason='Strict internal target remains false; material 10.75-second former blocking overhead is removed, while actual 15-second/P95/round and business gates remain unchanged')],
        performance=dict(oneSamplePerScale=True,osCacheControlled=False,actualP95Measured=False,syntheticTwoCallsPerQuestion=True,notActualAveragePayload=True,oldPerformance7NotRelabeled=True,
            scale102=dict(fourCallbacksMs=378.091,beginThroughHttpAndEndMs=574.452,fourCallbacksAtMost1000Ms=True,beginThroughHttpAndEndAtMost1500Ms=True),
            scale902=dict(fourCallbacksMs=1014.717,beginThroughHttpAndEndMs=1362.642,fourCallbacksAtMost1000Ms=False,beginThroughHttpAndEndAtMost1500Ms=True),
            originalRequestBudgetSeconds=15,originalRoundBudgetSeconds=1620,originalBudgetsAndGatesUnchanged=True),
        verified=dict(boundFiles=1140,nonruntimeGitBytes=168,runtimeFiles=972,preflightGitBytes=44,rootDeliveryGitFiles=74,rootArchivesRoundTripFiles=[770,805,39],
            suppliedTestsRun=190,suppliedTestsPassed=190,suppliedFailures=0,suppliedErrors=0,suppliedSkipped=0,independentAdditionalBoundaryCases=7,independentAdditionalPassed=7,
            originalActualJarFixtureFreshReconstructions=2,actualJarRestartedByReview=False,databaseIdentityRecheckedInOwnCopy=True,realAuthorizationMissingBothBarriers=True,keyReaderCalls=0,paidRouteCalls=0,
            actualBatchFiles=380,actualBatchFullFileSetAndBytesUnchanged=True),
        acceptedBoundaries=dict(historicalSameNameRawTamperDetectedAtNextFull=True,historicalRawRehashedEveryCallback=False,fullBeforeEveryQuestionAndRoundEndAndNewOwner=True,
            journalFullBytesRehashedEveryTransaction=True,newEventBytesCheckedSynchronously=True,fastLiabilitiesFromAnchoredLedger=True,inMemoryOwnerCapabilityOnly=True,
            firstSnapshotFailurePoisonAndPermanentSidecar=True,validSnapshotALiabilitiesRetainedWhenBInvalid=True,fullyUnwritableDiskSidecarPersistenceGuaranteed=False,
            wholeDirectoryCoordinatedRollbackResistance=False,partialCommitRecovery=False,automaticPaidReplay=False),
        priorDecisionsUnchanged=True,realHumanAuthorizationAbsent=True,paidAuthorized=False,engineeringAcceptanceIsNotPaidAuthorization=True,
        oldBatchInterruptedNoReplay=True,modelAccuracyAccepted=False,unseenAdmitted=False,stage2Accepted=False,stage3Resumed=False,providerCalls=0,providerTokens=0,providerCostYuan='0'))
    all_files=dict(files)
    for name in ('source-evidence-manifest.json','decision.json'):
        p=OUT/name;all_files[p.relative_to(ROOT).as_posix()]=sha(p)
    pathspec=OUT/'.git-pathspec'
    pathspec.write_bytes(b''.join(p.encode()+b'\0' for p in all_files))
    try:subprocess.run(['git','-c','core.longpaths=true','-c','core.autocrlf=false','add','--force','--pathspec-from-file='+str(pathspec),'--pathspec-file-nul'],cwd=ROOT,check=True)
    finally:pathspec.unlink()
    staged=subprocess.run(['git','-c','core.longpaths=true','diff','--cached','--name-only','-z'],cwd=ROOT,stdout=subprocess.PIPE,check=True).stdout
    assert {x.decode() for x in staged.split(b'\0') if x}==set(all_files)
    assert git_bytes(all_files,':')==len(all_files)
    print(json.dumps(dict(stagedFiles=len(all_files),stagedRawBytesAllMatched=True,archivedPerformanceRawFiles=len(raw),manifestSha256=sha(OUT/'source-evidence-manifest.json'),decisionSha256=sha(OUT/'decision.json'),actualBatchUnchanged=True)))
