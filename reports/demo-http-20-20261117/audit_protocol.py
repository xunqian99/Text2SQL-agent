"""Audit a frozen rerun without executing SQL, starting an app, or calling a model."""
from pathlib import Path
import hashlib
import json
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
raw = (HERE / 'protocol.json').read_bytes()
assert hashlib.sha256(raw).hexdigest() == (HERE / 'protocol.sha256').read_text().strip()
protocol = json.loads(raw)
previous = json.loads((ROOT / 'reports/demo-http-20-20261116/protocol.json').read_bytes())
assert protocol['denominator'] == 20 and len(protocol['cases']) == 20
assert protocol['datasetSha256'] == previous['datasetSha256']
assert protocol['offPeakPolicy']['version'] == 'deepseek-off-peak-weekday-conservative-v1'
assert protocol['offPeakPolicy']['guardSeconds'] == 120
assert protocol['offPeakPolicy']['zone'] == 'Asia/Shanghai'
for new, old in zip(protocol['cases'], previous['cases']):
    for field in ('id', 'sourceId', 'sessionId', 'question', 'goldSql', 'followUp'):
        assert new[field] == old[field], (new['id'], field)
    # Only execution metadata (elapsedMs) may differ. Do not rewrite either result.
    for field in ('columns', 'rows', 'truncated'):
        assert new['expected'][field] == old['expected'][field], (new['id'], field)
    assert not new['expected']['truncated']
for path, expected in protocol['sourceObjects'].items():
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD:' + path], cwd=ROOT, text=True).strip()
    assert actual == expected, 'Protected source changed: ' + path
peak = json.loads((HERE / 'peak-preflight.json').read_text(encoding='utf-8-sig'))
assert peak['exitCode'] != 0 and peak['providerCalls'] == 0 and not peak['requestsDirectoryExists']
log = (HERE / 'peak-preflight.log').read_text(encoding='utf-8-sig')
assert 'DeepSeekOffPeakPolicy$Rejected' in log and 'PEAK_HOURS' in log
assert 'ConnectException' not in log
print(json.dumps(dict(protocolAudit='PASS', identicalCasesAndGold=20,
                      frozenSource=protocol['sourceCommit'], peakPreflight='REFUSED_BEFORE_HTTP',
                      paidRunStarted=(HERE / 'requests').exists()), ensure_ascii=False))
