"""Verify archive bytes and recompute the failed acceptance decision without modifying files."""
import hashlib
import importlib.util
import json
from pathlib import Path

folder = Path(__file__).resolve().parent
root = folder.parents[1]
manifest = json.loads((folder / 'evidence-manifest.json').read_text(encoding='utf-8-sig'))
for item in manifest['artifacts']:
    assert hashlib.sha256((root / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
spec = importlib.util.spec_from_file_location('stage2_archive_auditor', folder / 'audit_evidence.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
actual = module.summarize(folder)
expected = json.loads((folder / 'summary.json').read_text(encoding='utf-8-sig'))
assert actual == expected, 'Recomputed summary differs'
assert actual['passedEvidenceAudit'] and not actual['stage2MeasuredGatesPassed']
correction = json.loads((folder / 'audit-correction.json').read_text(encoding='utf-8-sig'))
assert hashlib.sha256((folder / 'audit_evidence.py').read_bytes()).hexdigest() == correction['archiveAuditorSha256']
assert json.loads((folder / 'credential-scan.json').read_text(encoding='utf-8-sig'))['knownCredentialMatches'] == 0
assert not json.loads((folder / 'shutdown.json').read_text(encoding='utf-8-sig'))['listenerCount']
print(json.dumps(dict(archiveAudit='PASS', stage2Acceptance='FAIL', scores=[g['correct'] for g in actual['groups'].values()],
                     meanAccuracyPercent=actual['meanAccuracyPercent'], unresolvedUsageCalls=actual['unresolvedUsageCalls'],
                     configuredCostLowerBoundYuan=actual['fullKnownConfiguredCostYuan'])))
