"""Audit explicit interval selection without changing the original benchmark."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

folder = Path(__file__).resolve().parent
root = folder.parents[1]


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


manifest = read(folder/'manifest.json')
for artifact in manifest['artifacts']:
    assert hashlib.sha256((folder/artifact['path']).read_bytes()).hexdigest() == artifact['sha256']
for path, blob in manifest['sourceBlobs'].items():
    assert subprocess.check_output(['git','cat-file','-t',blob],cwd=root,text=True).strip() == 'blob',path
totals = dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
for path in (folder/'surefire').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot(); totals['suites'] += 1
    for key in ('tests','failures','errors','skipped'): totals[key] += int(suite.get(key))
assert totals == read(folder/'java-tests.json') == dict(suites=74,tests=783,failures=0,errors=0,skipped=0)
for name in ('java-tests','gold','prompt-dev','prompt-eval'):
    assert (folder/f'{name}.exit').read_text(encoding='utf-8-sig').strip() == '0'
for split in ('dev','eval'):
    before = read(root/f'reports/delivery-scope-20261117/after-{split}.json')
    after = read(folder/f'after-{split}.json')
    for key in ('datasetSha256','sampleIdsSha256','schemaSha256','systemPromptSha256','items'):
        assert before[key] == after[key],key
    assert after['itemCount'] == 100 and not after['sameProcessMismatches'] and after['modelCallCount'] == 0
assert read(folder/'prompt-comparison.json')['unchangedPrompts'] == 200
scope = read(folder/'scope-matches.json')
assert len(scope['items']) == 200 and scope['providerCalls'] == 0
carrier = next(i for i in scope['items'] if i['id'] == 'T4-026')
order = next(m for m in carrier['matches'] if m['metric']=='delivery_days')
assert order['decision'] == 'EXCLUDED_CONTEXT' and order['preferredContextTerm'] is None
gold = read(next((folder/'gold').glob('eval-*.json')))
assert gold['overall']['total'] == gold['overall']['correct'] == gold['overall']['sqlValid'] == 200
assert not any(i['calls'] for i in gold['itemEvidence']) and gold['overall']['totalCostYuan'] == 0
assert read(folder/'credential-scan.json')['knownCredentialMatches'] == 0
print(json.dumps(dict(audit='PASS',javaTests='783/783',newTests=8,gold='200/200',unchangedPrompts=200,modelCalls=0)))
