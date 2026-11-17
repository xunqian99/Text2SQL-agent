"""Recheck current Java/SQL tests, exact prompt scope and immutable original dataset; no model calls."""
from pathlib import Path
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET

folder=Path(__file__).resolve().parent
root=folder.parents[1]

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

manifest=read(folder/'manifest.json')
for artifact in manifest['artifacts']:
    assert hashlib.sha256((folder/artifact['path']).read_bytes()).hexdigest()==artifact['sha256'],artifact['path']
for path,blob in manifest['sourceBlobs'].items():
    assert subprocess.check_output(['git','cat-file','-t',blob],cwd=root,text=True).strip()=='blob',path
totals=dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
for path in (folder/'surefire').glob('TEST-*.xml'):
    suite=ET.parse(path).getroot();totals['suites']+=1
    for key in ('tests','failures','errors','skipped'):totals[key]+=int(suite.get(key))
assert totals==read(folder/'java-tests.json')==dict(suites=78,tests=841,failures=0,errors=0,skipped=0)
for name in ('java-tests','prompt-dev','prompt-eval','gold'):
    assert (folder/f'{name}.exit').read_text(encoding='utf-8-sig').strip()=='0'
changes=[]
for split in ('dev','eval'):
    before=read(root/f'reports/count-contract-metadata-20261117/final-{split}.json')
    after=read(folder/f'after-{split}.json')
    for key in ('datasetSha256','sampleIdsSha256','schemaSha256','systemPromptSha256'):assert before[key]==after[key],key
    assert after['itemCount']==100 and after['sameProcessMismatches']==[] and after['modelCallCount']==0
    for a,b in zip(before['items'],after['items'],strict=True):
        assert a['id']==b['id']
        changed={k for k in a if a[k]!=b[k]}
        if changed:
            assert changed=={'promptSha256','promptChars','componentChars','metricsSha256'},(a['id'],changed)
            assert {k for k in a['componentChars'] if a['componentChars'][k]!=b['componentChars'][k]}=={'metrics'}
            changes.append(a['id'])
comparison=read(folder/'prompt-comparison.json')
assert changes==comparison['changedPromptIds'] and len(changes)==25 and comparison['unchangedPrompts']==175
assert {'T1-009','T2-008','T1-004','T4-001','T6-003'}.issubset(changes)
gold=read(next((folder/'gold').glob('eval-*.json')))
assert gold['overall']['total']==gold['overall']['correct']==gold['overall']['sqlValid']==200
assert not any(item['calls'] for item in gold['itemEvidence']) and gold['overall']['totalCostYuan']==0
assert read(folder/'credential-scan.json')['knownCredentialMatches']==0
print(json.dumps(dict(audit='PASS',javaTests='841/841',gold='200/200',changedPolicyPrompts=len(changes),
                     unchangedPrompts=175,unchangedRetrievalValuesExamplesAndJoin=200,modelCalls=0)))
