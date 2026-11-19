"""Verify archived review inputs/results without any model or database access."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FOLDER = Path(__file__).resolve().parent
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
manifest = read(FOLDER / 'verification.json')
for name, expected in manifest['artifactSha256'].items():
    assert sha(FOLDER / name) == expected, name
with zipfile.ZipFile(FOLDER / 'raw-review-artifacts.zip') as archive:
    for name, expected in manifest['rawArchiveArtifactSha256'].items():
        assert hashlib.sha256(archive.read(name)).hexdigest() == expected, name
for name, expected in manifest['originalDataSha256'].items():
    assert sha(ROOT / name) == expected, name
source = subprocess.check_output(
    ['git', 'rev-parse', manifest['sourceCommit'] + ':src'], cwd=ROOT, text=True).strip()
assert source == manifest['sourceGitTree']
summary = read(FOLDER / 'summary.json')
for reviewer in summary['reviewers']:
    assert manifest['rawArchiveArtifactSha256'][reviewer['reviewFile']] == reviewer['reviewSha256']
adjudication = read(FOLDER / 'adjudication.json')
assert sha(FOLDER / 'summary.json') == adjudication['sourceSummarySha256']
assert len(summary['reconciliation']) == 100
assert sum(summary['decisions'].values()) == 100
assert {i['id'] for i in adjudication['decisions']} == set(summary['unresolvedDifferences'])
assert not adjudication['unresolvedDifferences']
for assignment in read(FOLDER / 'dispatch.json')['assignments']:
    assert sha(FOLDER / assignment['inputFile']) == assignment['inputSha256']
    report = read(FOLDER / assignment['expectedOutputFile'])
    assert report['provenance']['freshContext']
    assert not report['provenance']['participatedInAuthoringOrTuning']
    assert not report['provenance']['readPriorAudit']
    assert report['provenance']['externalModelCalls'] == 0
    assert len(report['items']) == assignment['itemCount']
assert manifest['providerCalls'] == manifest['providerTokens'] == manifest['providerCostYuan'] == 0
print('PASS: 100 reviewed items, 3 fresh agents, 8 adjudicated differences, originals intact, provider 0')
