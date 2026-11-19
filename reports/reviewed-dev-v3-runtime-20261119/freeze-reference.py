"""Seal a one-identifier compatibility revision; original reference remains frozen."""
from pathlib import Path
import datetime as dt
import hashlib
import json
import zipfile

ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
r=json.loads((HERE/'final-reviewer-runtime-acceptance.json').read_text(encoding='utf-8'))
m=r['metadata']
assert m['status']=='ACCEPTED' and m['finalCounts']['PASS']==100
assert m['changedIds']==['T5-019'] and m['finalCounts']['exactUnchangedElements']==99
assert m['equivalence']['frozenMinusRuntime']==m['equivalence']['runtimeMinusFrozen']==0
for manifest in ('finalCandidateManifest','frozenSourceCandidateManifest','productionContractAndDDLManifest'):
    for p,h in m[manifest].items():assert sha(ROOT/p)==h,p
with zipfile.ZipFile(HERE/'raw-review-bytes.zip','x',compression=zipfile.ZIP_DEFLATED) as z:
    for p in sorted(HERE.iterdir()):
        if p.is_file() and p.suffix in ('.json','.md','.py','.sql','.jsonl'):z.write(p,p.name)
for p in HERE.iterdir():
    if p.is_file() and p.suffix in ('.json','.md','.py','.sql','.jsonl'):
        p.write_bytes(p.read_bytes().replace(b'\r\n',b'\n'))
old=ROOT/'data/benchmarks/olist-v3-dev-candidate/reference-seal.json'
seal=json.loads(old.read_text(encoding='utf-8'))
seal.update(suiteId='olist-v3-development-runtime-compatible-20261119',
    frozenAt=dt.datetime.now(dt.timezone.utc).isoformat(),
    previousReferenceSealSha256=sha(old),compatibilityOnly=True,sqlIdentifierChanges=1,
    unchangedElements=99,logicalSqlChanges=seal['goldSqlChanges'],
    candidateManifest=[dict(path=p,sha256=h,bytes=(ROOT/p).stat().st_size) for p,h in m['finalCandidateManifest'].items()],
    artifactSha256={p.name:sha(p) for p in sorted(HERE.iterdir()) if p.is_file()},
    priorArtifactDirectory='reports/reviewed-dev-v3-20261119',
    initialPreflightCorrect=dict(B=99,C=99),initialPreflightProviderCalls=0)
out=ROOT/'data/benchmarks/olist-v3-dev-runtime-candidate/reference-seal.json'
with out.open('x',encoding='utf-8',newline='\n') as f:json.dump(seal,f,ensure_ascii=False,indent=2);f.write('\n')
print('FROZEN runtime-compatible dev100,99 unchanged,CTE rename equivalent;provider0')
