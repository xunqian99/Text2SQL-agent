"""Verify frozen dev lineage and final independent acceptance; no model or database access."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
seal=read(ROOT/'data/benchmarks/olist-v3-dev-candidate/reference-seal.json')
for i in seal['candidateManifest']+seal['contractManifest']:
    assert sha(ROOT/i['path'])==i['sha256'],i['path']
for p,h in seal['originalDataSha256'].items():assert sha(ROOT/p)==h,p
for p,h in seal['artifactSha256'].items():assert sha(HERE/p)==h,p
r=read(HERE/'final-reviewer-acceptance.json')
assert all(r['metadata']['finalCounts'][k]==v for k,v in dict(PASS=100,DEFECT=0,AMBIGUOUS=0).items())
assert len(r['items'])==100 and not r['metadata'].get('unresolvedItems')
assert seal['providerCalls']==seal['providerTokens']==seal['providerCostYuan']==0
assert not seal['blindTest'] and not seal['stageAccepted']
print('PASS dev reference100, final review100, original files intact; no model accuracy claim')
