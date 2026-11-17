"""Audit actual pruned-field provenance, legacy hints and bounded prompt changes."""
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
assert totals==read(folder/'java-tests.json')==dict(suites=77,tests=831,failures=0,errors=0,skipped=0)
for name in ('java-tests','gold-final','final-prompt-dev','final-prompt-eval','contracts-final','tool-compile'):
    assert (folder/f'{name}.exit').read_text(encoding='utf-8-sig').strip()=='0'
contracts=read(folder/'contracts-final.json')
assert len(contracts['items'])==200 and contracts['providerCalls']==0
active=[]
for row in contracts['items']:
    assert row['actualValueHints']==row['legacyValueHints']+row['contractText']
    if row['contract']['decision']=='MATCHED':
        active.append(row['id'])
        bindings=row['contract']['bindings']
        assert 2<=len(bindings)<=8
        assert len({(b['table'],b['column']) for b in bindings})==1
        assert len({b['value'] for b in bindings})==len(bindings)
        assert all(a['end']<=b['start'] for a,b in zip(bindings,bindings[1:]))
        assert 'REQUEST_RESULT_CONTRACT named_filter_counts_v1' in row['contractText']
    else:assert row['contractText']==''
assert set(active)=={'T2-013','T2-014'}
changes=[]
for split in ('dev','eval'):
    before=read(root/f'reports/count-comparison-year-20261117/after-{split}.json')
    after=read(folder/f'final-{split}.json')
    for key in ('datasetSha256','sampleIdsSha256','schemaSha256','systemPromptSha256'):assert before[key]==after[key],key
    assert after['itemCount']==100 and after['sameProcessMismatches']==[] and after['modelCallCount']==0
    for a,b in zip(before['items'],after['items'],strict=True):
        assert a['id']==b['id']
        changed={k for k in a if a[k]!=b[k]}
        if changed:
            assert changed=={'promptSha256','promptChars','componentChars','valueHintsSha256'}
            assert {k for k in a['componentChars'] if a['componentChars'][k]!=b['componentChars'][k]}=={'valueHints'}
            changes.append(a['id'])
assert set(changes)==set(active) and len(changes)==2
gold=read(next((folder/'gold-final').glob('eval-*.json')))
assert gold['overall']['total']==gold['overall']['correct']==gold['overall']['sqlValid']==200
assert not any(i['calls'] for i in gold['itemEvidence']) and gold['overall']['totalCostYuan']==0
assert read(folder/'credential-scan.json')['knownCredentialMatches']==0
print(json.dumps(dict(audit='PASS',javaTests='831/831',gold='200/200',changedPromptIds=changes,
                     unchangedPrompts=198,unchangedRetrievalAndLegacyValues=200,modelCalls=0)))
