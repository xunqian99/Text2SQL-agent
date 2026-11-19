"""Freeze independently reviewed reference identity, not paid evaluation admission."""
from pathlib import Path
import datetime as dt
import hashlib
import json
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[2]
FOLDER=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
r=read(FOLDER/'reviewer-acceptance.json')
m=r['metadata']
assert m['finalCounts']==dict(PASS=100,DEFECT=0,AMBIGUOUS=0) and not m['unresolvedItems']
assert len(r['items'])==100 and len({i['id'] for i in r['items']})==100
for item in m['finalCandidateManifest']+m['productionContractAndDDLManifest']:
    assert sha(ROOT/item['path'])==item['sha256'],item['path']
lineage=read(FOLDER/'candidate-lineage.json')
assert lineage['originalCount']==lineage['candidateCount']==100
assert lineage['removedIds']==lineage['addedIds']==[]
original=read(ROOT/'reports/calendar-window-20261119/verification.json')['originalDataSha256']
for p,h in original.items():assert sha(ROOT/p)==h,p
with zipfile.ZipFile(FOLDER/'raw-author-and-review-artifacts.zip','x',compression=zipfile.ZIP_DEFLATED) as z:
    for p in sorted(FOLDER.iterdir()):
        if p.is_file() and not p.name.startswith('baseline-') and p.suffix in ('.json','.md','.py','.sql'):
            z.write(p,p.name)
    for p in sorted((ROOT/'data/benchmarks').glob('olist-v3-*draft*/*')):
        if p.is_file():z.write(p,str(p.relative_to(ROOT)).replace('\\','/'))
for p in FOLDER.iterdir():
    if p.is_file() and not p.name.startswith('baseline-') and p.suffix in ('.json','.md','.py','.sql'):
        p.write_bytes(p.read_bytes().replace(b'\r\n',b'\n'))
seal=dict(format='reviewed-reference-v3-seal-v1',state='REFERENCE_FROZEN_NOT_EVALUATION_ADMITTED',
    suiteId='olist-v3-formal-revised-20261119',frozenAt=dt.datetime.now(dt.timezone.utc).isoformat(),
    sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
    productionSourceCommit='ea5a8a4ea9d2125a69181d16642bb816c23f1da5',
    itemCount=100,questionChanges=42,goldSqlChanges=45,independentReview=True,blindTest=False,
    providerCalls=0,providerTokens=0,providerCostYuan=0,minimumFormalPercent=89,
    relativeRegressionLimitPp=2,stageAccepted=False,
    candidateManifest=m['finalCandidateManifest'],contractManifest=m['productionContractAndDDLManifest'],
    originalDataSha256=original,artifactSha256={},
    limits=['Reference identity only; dev review and paired evaluation protocol must be frozen before paid runs.',
            'Reviewed seen questions are not unseen blind questions.','Never compare v3-v2 score difference as model improvement.'])
for p in sorted(FOLDER.iterdir()):
    if p.is_file() and not p.name.startswith('baseline-') and p.suffix in ('.json','.md','.py','.sql','.zip'):
        seal['artifactSha256'][p.name]=sha(p)
out=ROOT/'data/benchmarks/olist-v3-candidate/reference-seal.json'
with out.open('x',encoding='utf-8',newline='\n') as f:json.dump(seal,f,ensure_ascii=False,indent=2);f.write('\n')
print('FROZEN reference100, independent review100; provider0; evaluation not yet admitted')
