"""Verify the paid supplement without changing the frozen offline evidence auditor."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import hashlib
import json
import subprocess
import sys

HERE = Path(__file__).resolve().parent
for item in json.loads((HERE / 'paid-manifest.json').read_text(encoding='utf-8'))['artifacts']:
    assert hashlib.sha256((HERE / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
result = json.loads((HERE / 'paid-result.json').read_text(encoding='utf-8'))
assert result['passed'] and result['businessCorrect'] and result['completeCost'] and result['modelCalls'] == 1
assert result['sessionTurnCount'] == 1 and result['closeDeletePassed']
shutdown = json.loads((HERE / 'paid-shutdown.json').read_text(encoding='utf-8'))
assert all(not p['alive'] for p in shutdown['processes']) and all(p['closed'] for p in shutdown['ports'])
assert not shutdown['isolatedContainerRunning'] and shutdown['isolatedVolumePreserved']
assert shutdown['originalContainer']['running']
window = json.loads((HERE / 'paid-window-check.json').read_text(encoding='utf-8'))
assert window['allowed'] and datetime.fromisoformat(window['checkedAt']).hour >= 18
subprocess.run([sys.executable, str(HERE / 'audit_evidence.py'), '--paid'], check=True)
print('PAID_SUPPLEMENT PASS; raw hashes, complete usage/cost, one session turn, close/delete and process/database shutdown verified.')
