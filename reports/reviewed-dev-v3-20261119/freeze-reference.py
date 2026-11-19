"""Freeze final independently reviewed development reference, without model calls."""
from pathlib import Path
import datetime as dt
import hashlib
import json
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
accepted=read(HERE/'final-reviewer-acceptance.json')
meta=accepted['metadata']
assert all(meta['finalCounts'][k]==v for k,v in dict(PASS=100,DEFECT=0,AMBIGUOUS=0).items())
assert len(accepted['items'])==100 and len({i['id'] for i in accepted['items']})==100
assert not meta.get('unresolvedItems')
to_manifest=lambda m:[dict(path=p,sha256=h,bytes=(ROOT/p).stat().st_size) for p,h in m.items()]
candidate=to_manifest(meta['finalCandidateManifest'])
contracts=to_manifest(meta['productionContractAndDDLManifest'])
for i in candidate+contracts:
    assert sha(ROOT/i['path'])==i['sha256'],i['path']
lineage=read(HERE/'candidate-lineage.json')
assert len(lineage['items'])==100
for p,h in lineage['originalDataSha256'].items():assert sha(ROOT/p)==h,p
frozen=read(ROOT/'reports/stage2-candidate-freeze-20261119/freeze.json')
for p,h in frozen['sourceSha256'].items():assert sha(ROOT/p)==h,p
assert sha(ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar')==frozen['jarSha256']
with zipfile.ZipFile(HERE/'raw-author-and-review-artifacts.zip','x',compression=zipfile.ZIP_DEFLATED) as z:
    for p in sorted(HERE.iterdir()):
        if p.is_file() and p.suffix in ('.json','.md','.py','.sql'):
            z.write(p,p.name)
for p in HERE.iterdir():
    if p.is_file() and p.suffix in ('.json','.md','.py','.sql'):
        p.write_bytes(p.read_bytes().replace(b'\r\n',b'\n'))
seal=dict(format='reviewed-reference-v3-seal-v1',state='REFERENCE_FROZEN_NOT_EVALUATION_ADMITTED',
    suiteId='olist-v3-development-revised-20261119',frozenAt=dt.datetime.now(dt.timezone.utc).isoformat(),
    sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
    productionSourceCommit=frozen['productionSourceCommit'],itemCount=100,
    questionChanges=sum(i['questionChanged'] for i in lineage['items']),
    goldSqlChanges=sum(i['goldSqlChanged'] for i in lineage['items']),
    independentReview=True,blindTest=False,stageAccepted=False,
    providerCalls=0,providerTokens=0,providerCostYuan=0,
    candidateManifest=candidate,contractManifest=contracts,
    originalDataSha256=lineage['originalDataSha256'],artifactSha256={},
    developmentGates=dict(correctAtLeast=71,deepCorrectAtLeast=4,businessCorrectAtLeast=6,timeoutsAtMost=9),
    limits=['Seen development reference, not unseen evaluation.',
            'Author chose explicit benchmark requests; no claim of user-approved business definitions.',
            'Final acceptance followed reviewer feedback and revisions; shared filesystem is not OS isolation.',
            'V2/V3 score differences cannot establish model improvements.'])
for p in sorted(HERE.iterdir()):
    if p.is_file() and p.suffix in ('.json','.md','.py','.sql','.zip'):
        seal['artifactSha256'][p.name]=sha(p)
out=ROOT/'data/benchmarks/olist-v3-dev-candidate/reference-seal.json'
with out.open('x',encoding='utf-8',newline='\n') as f:
    json.dump(seal,f,ensure_ascii=False,indent=2);f.write('\n')
print('FROZEN dev100, independent final acceptance100, original/production hashes intact; provider0')
