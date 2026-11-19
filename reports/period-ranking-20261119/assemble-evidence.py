"""Archive completed p21 local checks, without provider access."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
FOLDER=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
git=lambda *a:subprocess.check_output(['git',*a],cwd=ROOT,text=True).strip()
commit=git('rev-parse','HEAD')
assert commit.startswith('ea5a8a4') and not git('diff',commit,'--','src','pom.xml')
totals=Counter()
xmls=sorted((ROOT/'target/surefire-reports').glob('TEST-*.xml'))
for p in xmls:
    suite=ET.parse(p).getroot()
    for k in ('tests','failures','errors','skipped'):totals[k]+=int(suite.get(k,0))
assert dict(totals)==dict(tests=1186,failures=0,errors=0,skipped=0)
for name in ('maven','package','gold','capture-dev','capture-eval'):
    assert (FOLDER/(name+'.exit')).read_text(encoding='utf-8-sig').strip()=='0'
gold_path=next((FOLDER/'gold').glob('eval-*.json')); gold=read(gold_path)
assert gold['overall']['correct']==gold['overall']['total']==200
assert gold['meta']['gitCommit']==commit and gold['meta']['promptVersion']=='p21-period-ranking-v21'
assert not any(i['calls'] for i in gold['itemEvidence'])
review=read(FOLDER/'reviewer-basic-time-review.json')
assert review['openFindingCount']==0
assert read(FOLDER/'boundaries.json')['passed']==17
impact={}
stable=['ddlSha256','metricsSha256','valueHintsSha256','examplesSha256','tables','retrievalTables',
        'scores','expandedTables','evidenceOrder','columns']
for split in ('dev','eval'):
    before=read(ROOT/f'reports/calendar-window-20261119/prompt-{split}.json')
    after=read(FOLDER/f'prompt-{split}.json')
    assert len(before['items'])==len(after['items'])==100
    assert after['sameProcessComparisons']==300 and not after['sameProcessMismatches']
    for a,b in zip(before['items'],after['items']):
        assert a['id']==b['id'] and a['promptSha256']!=b['promptSha256']
        assert all(a[k]==b[k] for k in stable)
        assert {k:v for k,v in a['componentChars'].items() if k!='system'}=={
            k:v for k,v in b['componentChars'].items() if k!='system'}
    impact[split]=dict(items=100,changedSystemPrompts=100,unchangedContextItems=100,
        sameProcessComparisons=300,mismatches=0,oldSystemChars=4740,
        newSystemChars=after['items'][0]['componentChars']['system'],
        unit='Java UTF16 code units; not tokens')
original=read(ROOT/'reports/calendar-window-20261119/verification.json')['originalDataSha256']
for p,h in original.items():assert sha(ROOT/p)==h,p
jar=ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar'
with zipfile.ZipFile(jar) as z:
    entry='com/text2sql/agent/generation/PromptTemplate.class'
    assert z.read('BOOT-INF/classes/'+entry)==(ROOT/'target/classes'/entry).read_bytes()
with zipfile.ZipFile(FOLDER/'engineering-logs.zip','x',compression=zipfile.ZIP_DEFLATED) as z:
    for p in xmls:z.write(p,'surefire/'+p.name)
    for pattern in ('*.log','*.exit','reviewer-*'):
        for p in sorted(FOLDER.glob(pattern)):
            if p.is_file():z.write(p,p.name)
    z.write(ROOT/'src/main/java/com/text2sql/agent/generation/PromptTemplate.java','PromptTemplate.java')
# Keep original reviewer bytes in the archive and use portable readable LF copies.
for p in FOLDER.rglob('*'):
    if p.is_file() and p.suffix in ('.json','.sql','.py','.md'):
        p.write_bytes(p.read_bytes().replace(b'\r\n',b'\n'))
verification=dict(format='period-ranking-verification-v1',productionCommit=commit,
    sourceGitTree=git('rev-parse',commit+':src'),promptVersion='p21-period-ranking-v21',
    java=dict(totals),fullJavaRuns=1,targetedJavaReruns=0,sqlBoundariesPassed=17,
    reviewerSqlExamplesPassed=8,openRuleReviewFindings=0,goldCorrect=200,goldTotal=200,
    goldReport=str(gold_path.relative_to(FOLDER)).replace('\\','/'),promptImpact=impact,
    providerCalls=0,providerTokens=0,providerCostYuan=0,newModelAccuracyMeasured=False,
    latestMeasuredFormalPercent=78,originalThresholdPercent=89,stageAccepted=False,stage3Deferred=True,
    originalDataSha256=original,jarSha256=sha(jar),artifactSha256={})
for p in sorted(FOLDER.rglob('*')):
    if p.is_file() and p.suffix in ('.json','.sql','.py','.md','.zip') and p.name!='verification.json':
        verification['artifactSha256'][str(p.relative_to(FOLDER)).replace('\\','/')]=sha(p)
with (FOLDER/'verification.json').open('x',encoding='utf-8',newline='\n') as f:
    json.dump(verification,f,ensure_ascii=False,indent=2);f.write('\n')
print('ARCHIVED Java1186, Gold200, SQL17, independent examples8, stable context200, provider0')
