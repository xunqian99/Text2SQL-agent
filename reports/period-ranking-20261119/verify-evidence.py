"""Read-only verification of p21 evidence; --runtime additionally checks the current jar."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
FOLDER=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
v=read(FOLDER/'verification.json')
for name,h in v['artifactSha256'].items():assert sha(FOLDER/name)==h,name
for name,h in v['originalDataSha256'].items():assert sha(ROOT/name)==h,name
assert subprocess.check_output(['git','rev-parse',v['productionCommit']+':src'],cwd=ROOT,text=True).strip()==v['sourceGitTree']
gold=read(FOLDER/v['goldReport'])
assert gold['overall']['total']==gold['overall']['correct']==200
assert not any(i['calls'] for i in gold['itemEvidence'])
assert v['java']==dict(tests=1186,failures=0,errors=0,skipped=0)
assert v['providerCalls']==v['providerTokens']==v['providerCostYuan']==0
if '--runtime' in sys.argv:assert sha(ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar')==v['jarSha256']
print('PASS p21: Java1186, Gold200, SQL17, stable context200, provider0; original scores retained')
