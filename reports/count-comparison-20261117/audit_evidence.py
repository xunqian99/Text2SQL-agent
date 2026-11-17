"""Read-only audit of p11 scope, original quantities and SQL counterexamples."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

folder=Path(__file__).resolve().parent
root=folder.parents[1]


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


manifest=read(folder/'manifest.json')
for entry in manifest['artifacts']:
    assert hashlib.sha256((folder/entry['path']).read_bytes()).hexdigest()==entry['sha256'],entry['path']
for path,blob in manifest['sourceBlobs'].items():
    assert subprocess.check_output(['git','cat-file','-t',blob],cwd=root,text=True).strip()=='blob',path
totals=dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
for path in (folder/'surefire').glob('TEST-*.xml'):
    suite=ET.parse(path).getroot();totals['suites']+=1
    for key in ('tests','failures','errors','skipped'):totals[key]+=int(suite.get(key))
assert totals==read(folder/'java-tests.json')==dict(suites=76,tests=804,failures=0,errors=0,skipped=0)
for name in ('java-tests','gold','prompt-dev','prompt-eval'):
    assert (folder/f'{name}.exit').read_text(encoding='utf-8-sig').strip()=='0'
changes=[]
for split in ('dev','eval'):
    before=read(root/f'reports/delivery-explicit-clock-20261117/after-{split}.json')
    after=read(folder/f'after-{split}.json')
    for key in ('datasetSha256','sampleIdsSha256','schemaSha256'):assert before[key]==after[key],key
    assert before['systemPromptSha256']!=after['systemPromptSha256']
    assert after['itemCount']==100 and after['sameProcessMismatches']==[] and after['modelCallCount']==0
    for a,b in zip(before['items'],after['items'],strict=True):
        assert a['id']==b['id']
        changed={k for k in a if a[k]!=b[k]}
        assert changed=={'promptSha256','promptChars','componentChars'},(a['id'],changed)
        assert {k for k in a['componentChars'] if a['componentChars'][k]!=b['componentChars'][k]}=={'system'}
        changes.append(a['id'])
assert len(changes)==len(set(changes))==200
gold=read(next((folder/'gold').glob('eval-*.json')))
assert gold['overall']['total']==gold['overall']['correct']==gold['overall']['sqlValid']==200
assert not any(i['calls'] for i in gold['itemEvidence']) and gold['overall']['totalCostYuan']==0
witness=read(folder/'shape-witness.json')
assert witness['readOnly'] and witness['providerCalls']==0 and len(witness['rounds'])==3
expected=dict(zip(witness['reference']['columns'],witness['reference']['rows'][0],strict=True))
for row in witness['rounds']:
    assert row['namedCountsMatch'] and not row['shapeMatches']
    assert dict(row['actual']['rows'])==expected
assert read(folder/'credential-scan.json')['knownCredentialMatches']==0
print(json.dumps(dict(audit='PASS',javaTests='804/804',gold='200/200',changedPrompts=200,
                     unchangedUserContextAndRetrieval=200,oldQuantityMapsMatch=3,modelCalls=0)))
