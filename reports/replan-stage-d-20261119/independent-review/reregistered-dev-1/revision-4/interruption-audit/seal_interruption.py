"""Seal an interruption diagnostic, never a production round-safety acceptance."""
from __future__ import annotations
import hashlib,json,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent
ROOT_COMMIT='c11ab39a1c865095f84502adb43eda197f3dccd4'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_bytes())
def save(name,value):
    with (OUT/name).open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)
def git_bytes(mapping,prefix):
    result=subprocess.run(['git','-c','core.longpaths=true','cat-file','--batch'],cwd=ROOT,input=''.join(prefix+p+'\n' for p in mapping).encode(),stdout=subprocess.PIPE,check=True)
    offset=0
    for path,expected in mapping.items():
        end=result.stdout.index(b'\n',offset);fields=result.stdout[offset:end].split();offset=end+1
        assert len(fields)==3 and fields[1]==b'blob',(path,fields)
        size=int(fields[2]);raw=result.stdout[offset:offset+size];offset+=size+1
        assert hashlib.sha256(raw).hexdigest()==expected,path
    assert offset==len(result.stdout)
    return len(mapping)
if __name__=='__main__':
    source=read(OUT/'source-originals.json')
    for path,expected in source['files'].items():assert sha(ROOT/path)==expected,path
    batch=ROOT/'reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4'
    execution=ROOT/'reports/replan-stage-d-20261119/reregistration-candidate/authorized-execution-20261120'
    manifest=read(execution/'interrupted-batch-manifest.json')
    assert {p.relative_to(ROOT).as_posix():sha(p) for p in batch.rglob('*') if p.is_file()}==manifest['files']
    meta=read(execution/'interrupted-batch-archive.json');archive=ROOT/meta['archive']
    assert sha(archive)==meta['archiveSha256']=='d54ba819787a9f0dcee54cc26620f22c2021fe7640b0358ab68174ecc135547d'
    assert archive.stat().st_size==meta['archiveBytes']==36172814
    with zipfile.ZipFile(archive) as z:
        assert set(z.namelist())==set(manifest['files'])
        for name,expected in manifest['files'].items():assert hashlib.sha256(z.read(name)).hexdigest()==expected,name
    changed=subprocess.check_output(['git','-c','core.longpaths=true','diff-tree','--no-commit-id','--name-only','-r','-z',ROOT_COMMIT],cwd=ROOT)
    paths=[p.decode() for p in changed.split(b'\0') if p];assert len(paths)==401
    root_git={p:sha(ROOT/p) for p in paths};assert git_bytes(root_git,ROOT_COMMIT+':')==401
    ledger=read(batch/'send-ledger.json')
    for call in ledger['calls']:
        if call['usageKnown']:
            response=call['result']['responseJson'];message=response['choices'][0]['message']
            assert not message.get('tool_calls') and not message.get('function_call')
            assert not any(response['usage'].get('server_tool_use',{}).values())
    diagnostic=read(OUT/'diagnostic.json');accounting=read(OUT/'accounting-and-interruption.json')
    assert len(diagnostic['items'])==76 and [i['ordinal'] for i in diagnostic['items']]==list(range(1,77))
    assert sum(i['dangerousAllowed'] for i in diagnostic['items'])==diagnostic['observedDangerousAllowances']==0
    assert diagnostic['completeDev100'] is False and diagnostic['productionRoundSafetyReport'] is False
    assert accounting['current']['actualCostLowerBoundYuan']=='0.13064558' and accounting['cumulative']['actualCostLowerBoundYuan']=='0.29376746'
    assert not (OUT.parent/'rounds/DEV_F.json').exists()
    save('git-byte-and-archive-verification.json',dict(rootExecutionCommit=ROOT_COMMIT,rootCommittedFiles=401,allRootGitRawBytesMatch=True,
        archive=meta['archive'],archiveSha256=meta['archiveSha256'],archiveBytes=36172814,archiveRoundTripFiles=768,archiveExactFileSetAndAllOriginalBytesMatched=True,
        originalSourcesRechecked=len(source['files']),actualBatchExactFileSetAndBytesUnchanged=True,originalProviderToolCallsAbsentInAll72KnownResponses=True,
        actualBatchWritten=False,productionMonitorInvoked=False,paidReplay=False,providerCallsAdded=0))
    excluded={OUT/'source-evidence-manifest.json',OUT/'conclusion.json'}
    files={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(OUT.iterdir()) if p.is_file() and p not in excluded}
    save('source-evidence-manifest.json',dict(format='replan-d-independent-interruption-audit-manifest-v1',planSha256=accounting['planSha256'],
        sourceInterruptionManifestSha256=source['sourceManifestSha256'],rootExecutionCommit=ROOT_COMMIT,files=files,fileCount=len(files),newProviderCalls=0))
    save('conclusion.json',dict(format='replan-d-independent-interruption-conclusion-v1',planSha256=accounting['planSha256'],evidenceManifestSha256=sha(OUT/'source-evidence-manifest.json'),
        terminal='INTERRUPTED_NO_REPLAY',planned=100,answered=76,unasked=24,completeDev100=False,productionRoundSafetyReportCreated=False,acceptedForPromotion=False,
        observedDangerousSqlAllowances=0,scope='Captured 76 local HTTP output paths and all 72 delivered original generated SQL; not complete DEV100 or unseen acceptance',unknownProviderContents=4,
        newKnownUsageCalls=72,newUnknownUsageCalls=4,newCostLowerBoundYuan='0.13064558',cumulativeCostLowerBoundYuan='0.29376746',unknownActualCostYuan=None,unknownActualTokens=None,
        candidateConfiguredCommitmentYuan='0.99376746',configuredCommitmentIsNotPaidBill=True,twoQuotaLayersNeverSummedAsBill=True,
        all76SendsOffPeak=True,all768ActualOriginalsVerified=True,actualBatchUnchanged=True,old3CandidateUnknownIdentitiesPreserved=True,
        independence=diagnostic['independence'],accuracyAccepted=False,p95Accepted=False,stage2Accepted=False,stage3Resumed=False,unseenAdmitted=False,replayAllowed=False,
        keyReads=0,providerCallsAddedByAudit=0,providerTokensAddedByAudit=0,providerCostAddedByAuditYuan='0'))
    all_files=dict(files)
    for name in ('source-evidence-manifest.json','conclusion.json'):
        p=OUT/name;all_files[p.relative_to(ROOT).as_posix()]=sha(p)
    assert subprocess.run(['git','diff','--cached','--quiet'],cwd=ROOT).returncode==0
    subprocess.run(['git','-c','core.longpaths=true','-c','core.autocrlf=false','add','--',*all_files],cwd=ROOT,check=True)
    staged=subprocess.check_output(['git','-c','core.longpaths=true','diff','--cached','--name-only','-z'],cwd=ROOT)
    assert {p.decode() for p in staged.split(b'\0') if p}==set(all_files)
    assert git_bytes(all_files,':')==len(all_files)
    print(json.dumps(dict(stagedOwnFiles=len(all_files),allStagedRawBytesMatched=True,rootArchive768RoundTrip=True,actualBatchUnchanged=True,
        manifestSha256=sha(OUT/'source-evidence-manifest.json'),conclusionSha256=sha(OUT/'conclusion.json'))))
