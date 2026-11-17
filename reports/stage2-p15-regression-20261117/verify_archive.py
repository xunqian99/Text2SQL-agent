"""Verify this completed, failed acceptance archive without sending model requests or editing files."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

folder = Path(__file__).resolve().parent
root = folder.parents[1]
os.chdir(root)
sys.path.insert(0, str(folder))
manifest = json.loads((folder / 'evidence-manifest.json').read_text(encoding='utf-8-sig'))
for item in manifest['artifacts']:
    assert hashlib.sha256((root / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
spec = importlib.util.spec_from_file_location('stage2_p15_archive_auditor', folder / 'audit_evidence.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
actual = module.summarize(folder)
expected = json.loads((folder / 'summary.json').read_text(encoding='utf-8-sig'))
assert actual == expected, 'Recomputed summary differs'
assert actual['passedEvidenceAudit'] and not actual['stage2MeasuredGatesPassed']
assert [group['correct'] for group in actual['groups'].values()] == [84, 84, 88]
assert json.loads((folder / 'credential-scan.json').read_text(encoding='utf-8-sig'))['knownCredentialMatches'] == 0
shutdown = json.loads((folder / 'shutdown.json').read_text(encoding='utf-8-sig'))
assert shutdown['listenerCount'] == shutdown['matchingJavaProcessCount'] == 0
print(json.dumps(dict(archiveAudit='PASS', stage2Acceptance='FAIL', scores=[84,84,88],
                      meanAccuracyPercent=actual['meanAccuracyPercent'],
                      unresolvedUsageCalls=actual['unresolvedUsageCalls'],
                      configuredCostLowerBoundYuan=actual['fullKnownConfiguredCostYuan'])))
