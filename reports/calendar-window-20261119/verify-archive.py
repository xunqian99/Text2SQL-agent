"""Check committed evidence without database access or provider calls."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
FOLDER=Path(__file__).resolve().parent
read=lambda p:json.loads(Path(p).read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
v=read(FOLDER/'verification.json')
git=lambda *args:subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()
commit=v['productionCommit']
assert git('rev-parse',commit+':src')==v['sourceGitTree']
for p,h in v['originalDataSha256'].items():assert sha(ROOT/p)==h,p
for p,h in v['artifactSha256'].items():assert sha(FOLDER/p)==h,p
assert sha(ROOT/v['reviewPacket'])==v['reviewPacketSha256']
with zipfile.ZipFile(FOLDER/'engineering-logs.zip') as archive:
    totals=Counter()
    for name in archive.namelist():
        if name.startswith('surefire/') and name.endswith('.xml'):
            suite=ET.fromstring(archive.read(name))
            for k in ('tests','failures','errors','skipped'):totals[k]+=int(suite.get(k,0))
    assert dict(totals)==v['java']==dict(tests=1185,failures=0,errors=0,skipped=0)
    for name in ('maven.exit','package.exit','gold.exit'):assert archive.read(name).decode('utf-8-sig').strip()=='0'
gold=read(FOLDER/v['goldReport'])
assert gold['meta']['gitCommit']==commit and gold['meta']['promptVersion']==v['promptVersion']
assert gold['overall']['total']==gold['overall']['correct']==200
assert not any(i['calls'] for i in gold['itemEvidence'])
boundary=read(FOLDER/'database-review.json')
assert boundary['passed']==boundary['boundaryCases']==14 and len(boundary['cases'])==17
assert boundary['readOnly'] and boundary['externalProviderCalls']==0
assert boundary['sqlSha256']==sha(FOLDER/'database-review.sql')
assert all(c['actual']==c['expected'] for c in boundary['cases'] if c['expected'] is not None)
audit=read(FOLDER/'formal-contract-audit.json')
assert audit['productionCommit']==commit and len(audit['items'])==audit['itemsReviewed']==100
ids={i['id'] for i in audit['items'] if i['confirmedConflictKinds']}
assert len(ids)==14 and sorted(ids)==audit['confirmedDistinctConflicts']
assert audit['conflictCounts']==dict(single_turn_clarification_conflict=5,default_display_conflict=6,
    reference_grain_defect=2,default_time_conflict=1)
assert 100-len(ids)==audit['conditionalContractCompliantScoreCeilingPercent']==86<audit['formalGatePercent']==89
assert len(audit['comparisons'])==9 and all(not c['sameRowMultiset'] and not c['rescored'] for c in audit['comparisons'].values())
query_review=read(FOLDER/'formal-contract-query-results.json')
assert len(query_review['queries'])==len(query_review['results'])==audit['readOnlyQueries']==44
for p,h in audit['provenanceSha256'].items():
    if p.startswith('src/'):
        blob=subprocess.check_output(['git','show',commit+':'+p],cwd=ROOT)
        assert hashlib.sha256(blob).hexdigest()==h,p
    else:assert sha(ROOT/p)==h,p
for split in ('dev','eval'):
    p=read(FOLDER/f'prompt-{split}.json')
    assert p['itemCount']==100 and p['sameProcessComparisons']==300 and not p['sameProcessMismatches']
assert not v['stageAccepted'] and not v['independentReview'] and not v['newPaidAccuracyMeasured']
assert v['externalProviderCalls']==v['providerTokens']==v['providerCostYuan']==0
if '--runtime' in sys.argv:
    assert not git('diff',commit,'--','src','pom.xml'), 'current sources differ'
    assert sha(ROOT/v['jar'])==v['jarSha256'],'current JAR differs'
print('PASS: Java1185/1185; Gold200/200; boundary14/14; contract review100, conditional ceiling86<89; provider0; stageAccepted=false')
