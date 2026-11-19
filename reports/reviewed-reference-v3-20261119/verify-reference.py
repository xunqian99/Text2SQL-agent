"""Check final reference and the preserved independent review chain without model access."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[2]
FOLDER=Path(__file__).resolve().parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
seal=json.loads((ROOT/'data/benchmarks/olist-v3-candidate/reference-seal.json').read_text(encoding='utf-8'))
for item in seal['candidateManifest']+seal['contractManifest']:
    assert sha(ROOT/item['path'])==item['sha256'],item['path']
for name,h in seal['originalDataSha256'].items():assert sha(ROOT/name)==h,name
for name,h in seal['artifactSha256'].items():assert sha(FOLDER/name)==h,name
review=json.loads((FOLDER/'reviewer-acceptance.json').read_text(encoding='utf-8'))
assert review['metadata']['finalCounts']==dict(PASS=100,DEFECT=0,AMBIGUOUS=0)
assert len(review['items'])==100 and not review['metadata']['unresolvedItems']
assert seal['providerCalls']==seal['providerTokens']==seal['providerCostYuan']==0
assert seal['minimumFormalPercent']==89 and seal['relativeRegressionLimitPp']==2
assert not seal['blindTest'] and not seal['stageAccepted']
print('PASS V3 reference100, review100, originals intact, provider0; no model accuracy claim')
