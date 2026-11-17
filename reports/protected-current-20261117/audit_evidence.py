"""Offline audit of current-build protected HTTP behavior and frozen classpath identity."""
from pathlib import Path
from decimal import Decimal
import hashlib
import json
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def read(name):
    return json.loads((HERE / name).read_text(encoding='utf-8-sig'), parse_float=Decimal)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


plan = read('protocol.json')
assert sha(HERE / 'run_acceptance.py') == plan['runnerSha256']
assert plan['sourceCommit'] == '9d6285a419dbf78f19d4d288625c1438a6fad9e9'
assert plan['packagedEntriesMatched'] > 0 and len(plan['classpath']) == 116
assert all(plan['classpath']) and len(plan['dependencyHashes']) == 114
for path, identity in plan['sourceObjects'].items():
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD:' + path], cwd=ROOT, text=True).strip() == identity
assert [item['exitCode'] for item in read('attempts.json')] == [0, 0]
result = read('result.json')
assert result['passed'] and result['accessChecks'] == 36 and result['costScenarios'] == 4
assert result['syntheticModelCalls'] == 5 and result['externalProviderCalls'] == result['externalProviderCostYuan'] == 0
assert not result['javaTestsRerun']
assert len(result['processes']) == len({p['pid'] for p in result['processes']}) == 5
assert all(not p['alive'] for p in result['processes'])
assert len(result['ports']) == 5 and all(p['closed'] for p in result['ports'])
access = read('access/summary.json')
assert access['passed'] and access['checks'] == 36 and access['contextsClosed']
requests = read('access/requests.json')
lookup = {item['name']: item for item in requests}
assert len(requests) == len(lookup) == 36
unauthorized = [item for item in requests if item['name'].startswith('unauthorized-')]
assert len(unauthorized) == 14 and all(item['httpStatus'] == 401 for item in unauthorized)
for item in requests:
    assert item['headers']['cache-control'] == 'no-store'
    assert not any(key.lower() == 'authorization' for key in item['headers'])
for name in ('cross-origin', 'cross-site-get'):
    assert lookup[name]['httpStatus'] == 403
for name, code in [('concurrency-refusal', 'CONCURRENCY_LIMIT'), ('rate-refusal', 'RATE_LIMIT')]:
    assert lookup[name]['httpStatus'] == 429 and lookup[name]['body']['status'] == code
    assert int(lookup[name]['headers']['retry-after']) >= 1
assert lookup['same-origin-ask']['body']['rows'] == [['54011']]
assert lookup['authenticated-close']['body']['turnCount'] == 1
assert lookup['authenticated-delete']['httpStatus'] == 204
assert lookup['effective-limits']['body']['costQuotaEnforced'] is False
cost = read('cost/summary.json')
assert cost['passed'] and cost['scenarios'] == 4 and len(set(cost['pids'])) == 3
assert cost['syntheticModelCalls'] == 3 and cost['externalProviderCalls'] == cost['providerCostYuan'] == 0
before = read('cost/status-before-restart.json')['body']
after = read('cost/status-after-restart.json')['body']
assert before == after and before['knownCostYuan'] == Decimal('0.02')
assert before['heldYuan'] == Decimal('0.10') and before['committedYuan'] == Decimal('0.12')
assert before['unknownCalls'] == 1 and not before['canAdmitCall'] and before['ledgerAvailable']
assert read('cost/known-response.json')['body']['rows'] == [['54011']]
assert read('cost/unknown-response.json')['body']['budget']['unknownUsageCalls'] == 1
for name, termination in [('quota-rejected', 'COST_QUOTA_EXHAUSTED'), ('restart-rejected', 'COST_QUOTA_EXHAUSTED'), ('byok-rejected', 'BYOK_DISABLED_BY_COST_QUOTA')]:
    response = read('cost/' + name + '.json')
    assert response['httpStatus'] == 503
    assert response['body']['budget']['termination'] == termination and response['body']['budget']['modelCalls'] == 0
fallback = read('cost/correction-fallback.json')
assert fallback['httpStatus'] == 200 and fallback['body']['rows'] == [['54011']]
assert fallback['body']['budget']['termination'] == 'COST_QUOTA_EXHAUSTED'
assert fallback['body']['budget']['modelCalls'] == 1 and fallback['body']['budget']['unknownUsageCalls'] == 0
shutdown = read('shutdown.json')
assert not shutdown['liveParentPids'] and not shutdown['isolatedContainerRunning']
assert shutdown['isolatedVolumePreserved'] and shutdown['originalContainer'] == plan['originalContainer']
snapshot = read('resource-state.json')
assert snapshot['volumeName'] == 'text2sql-clean-chain-20261117-data'
assert snapshot['isolatedRunning'] is False and snapshot['originalRunning'] is True

manifest = HERE / 'manifest.json'
if '--capture' in sys.argv:
    assert not manifest.exists(), 'Do not replace frozen evidence'
    artifacts = [dict(path=str(path.relative_to(HERE)).replace('\\', '/'), sha256=sha(path))
                 for path in sorted(HERE.rglob('*')) if path.is_file() and '__pycache__' not in path.parts]
    with manifest.open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(dict(artifacts=artifacts), handle, ensure_ascii=False, indent=2)
        handle.write('\n')
for item in read('manifest.json')['artifacts']:
    assert sha(HERE / item['path']) == item['sha256'], item['path']
if '--live-inputs' in sys.argv:
    source = Path(plan['sourceDirectory'])
    assert not (source / 'target/classes/application-local.yml').exists()
    assert not (source / 'target/test-classes/application-local.yml').exists()
    for path, identity in plan['dependencyHashes'].items():
        assert sha(path) == identity, path
    for path, identity in plan['testClassHashes'].items():
        assert sha(source / 'target/test-classes' / path) == identity, path
print(json.dumps(dict(audit='PASS', access='36/36', costGroups='4/4', syntheticModelCalls=5,
                      providerCalls=0, portsClosed=5, processesExited=5,
                      productionEntriesMatched=plan['packagedEntriesMatched'], javaTestsRerun=False)))
