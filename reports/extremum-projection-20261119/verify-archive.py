"""Verify immutable acceptance evidence. Does not start Java or access any model endpoint."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

folder = Path(__file__).resolve().parent
root = folder.parent.parent
read = lambda p: json.loads(Path(p).read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
v = read(folder/'verification.json')
for rel, expected in v['artifactSha256'].items():
    assert sha(folder/rel) == expected, rel

def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()

commit = v['productionCommit']
assert git('rev-parse', commit+':src') == v['sourceGitTree']
assert git('rev-parse', commit+':pom.xml') == v['pomGitBlob']
assert subprocess.run(['git','-C',str(root),'merge-base','--is-ancestor',commit,'HEAD'],capture_output=True).returncode == 0
for rel, expected in v['originalDataSha256'].items():
    assert sha(root/rel) == expected, rel

with zipfile.ZipFile(folder/'engineering-logs.zip') as archive:
    totals = Counter()
    for name in archive.namelist():
        if name.startswith('surefire/') and name.endswith('.xml'):
            suite = ET.fromstring(archive.read(name))
            for key in ('tests','failures','errors','skipped'):
                totals[key] += int(suite.get(key,'0'))
    assert dict(totals) == dict(tests=1183,failures=0,errors=0,skipped=0), totals
    for name in ('final-maven.exit','package.exit','gold.exit','http.exit'):
        assert archive.read(name).decode('utf-8-sig').strip() == '0', name

with zipfile.ZipFile(folder/'first-full-regression.zip') as archive:
    totals = Counter()
    for name in archive.namelist():
        if name.startswith('surefire/') and name.endswith('.xml'):
            suite=ET.fromstring(archive.read(name))
            for key in ('tests','failures','errors','skipped'):
                totals[key] += int(suite.get(key,'0'))
    assert dict(totals) == dict(tests=1182,failures=1,errors=0,skipped=0),totals

gold = read(folder/v['goldReport'])
assert gold['overall']['total']==gold['overall']['correct']==200
assert gold['meta']['gitCommit']==commit
assert gold['meta']['effectiveConfig']['resultCheckerPolicyVersion']==v['checkerPolicy']
assert not any(i['calls'] for i in gold['itemEvidence'])
http = read(folder/'http/summary.json')
assert http['passed'] and http['httpCases']==6 and http['syntheticModelCalls']==10
assert http['externalProviderCalls']==http['providerTokens']==http['providerCostYuan']==0
requests = read(folder/'http/requests.json')
assert len(requests)==6 and sum(i['syntheticCalls'] for i in requests)==10
assert all(i['httpStatus']==200 and i['response']['budget']['modelCalls']==i['syntheticCalls']
           and i['response']['budget']['unknownUsageCalls']==0 for i in requests)
replay = read(folder/'http/offline-replay.json')
assert len(replay['gold'])==200 and len(replay['executedReplay'])==82
assert Counter(i['decision']['status'] for i in replay['gold'])==dict(NOT_APPLICABLE=199,PRESENT=1)
assert Counter(i['decision']['status'] for i in replay['executedReplay'])==dict(NOT_APPLICABLE=81,MISSING=1)
assert replay['missing']==['T1-028'] and not replay['rescored']
assert sha(root/'reports/clarification-candidate-dev-screen-20261119/eval-20261119-085723.json')==replay['historicalReportSha256']
if '--runtime' in sys.argv:
    assert not git('diff',commit,'--','src','pom.xml'), 'current sources differ'
    assert sha(root/v['jar'])==v['jarSha256'], 'local JAR differs'
print('PASS: Java 1183/1183; HTTP 6/6; Gold 200/200; narrow coverage 1/200; provider calls 0; stageAccepted=false')
