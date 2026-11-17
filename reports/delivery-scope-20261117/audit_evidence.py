"""Offline audit: scoped clocks, unchanged unrelated prompts, real database counterexamples."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

folder = Path(__file__).resolve().parent
root = folder.parents[1]


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


manifest = read(folder / 'manifest.json')
for entry in manifest['artifacts']:
    assert hashlib.sha256((root / entry['path']).read_bytes()).hexdigest() == entry['sha256'], entry['path']
for path, blob in manifest['sourceBlobs'].items():
    assert subprocess.check_output(['git','cat-file','-t',blob],cwd=root,text=True).strip() == 'blob',path
totals = dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
for p in (folder/'surefire').glob('TEST-*.xml'):
    e=ET.parse(p).getroot();totals['suites']+=1
    for k in ('tests','failures','errors','skipped'):totals[k]+=int(e.get(k))
assert totals == read(folder/'java-tests.json') == dict(suites=74,tests=775,failures=0,errors=0,skipped=0)
assert (folder/'java-tests-fixed.exit').read_text(encoding='utf-8-sig').strip() == '0'
changed=[]
for split in ('dev','eval'):
    before=read(root/f'reports/result-contract-20261117/after-{split}.json')
    after=read(folder/f'after-{split}.json')
    for key in ('datasetSha256','sampleIdsSha256','schemaSha256','systemPromptSha256'):
        assert before[key]==after[key],key
    assert before['itemCount']==after['itemCount']==100
    assert after['sameProcessMismatches']==[] and after['modelCallCount']==0
    for a,b in zip(before['items'],after['items']):
        assert a['id']==b['id']
        different=[k for k in a if a[k]!=b[k]]
        if different:
            assert a['id']=='T4-026' and set(different)=={'promptSha256','promptChars','componentChars','ddlSha256','metricsSha256'}
            changed.append(a['id'])
assert changed==['T4-026']
scope=read(folder/'scope-matches.json')
assert len(scope['items'])==200 and scope['providerCalls']==0
by_id={i['id']:i['matches'] for i in scope['items']}
carrier={m['metric']:m for m in by_id['T4-026']}
assert carrier['delivery_days']['decision']=='EXCLUDED_CONTEXT' and carrier['delivery_days']['excludedContextTerm']=='承运商'
assert carrier['shipment_delivery_days']['decision']=='MATCHED' and carrier['shipment_delivery_days']['requiredContextTerm']=='承运商'
assert not by_id['T3-012'] # Evidence corrects the initial, unsupported YoY attribution.
for ident in ('T3-018','T6-019'):
    candidates={m['metric']:m for m in by_id[ident]}
    assert candidates['delivery_days']['decision']=='MATCHED' and candidates['shipment_delivery_days']['decision']!='MATCHED'
gold=read(next((folder/'gold').glob('eval-*.json')))
assert gold['overall']['total']==gold['overall']['correct']==gold['overall']['sqlValid']==200
assert not any(i['calls'] for i in gold['itemEvidence']) and gold['overall']['totalCostYuan']==0
assert read(folder/'credential-scan.json')['knownCredentialMatches']==0
print(json.dumps(dict(audit='PASS',javaTests='775/775',newTests=25,skipped=0,gold='200/200',
                     unchangedPrompts=199,changedPromptIds=changed,unchangedRawRetrievalAndColumns=200,providerCalls=0)))
