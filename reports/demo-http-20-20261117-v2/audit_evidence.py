"""Audit complete frozen HTTP evidence; does not query a database or a model."""
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
import hashlib
import json
import math
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OLD = ROOT / 'reports/demo-http-20-20261117'
BEIJING = timezone(timedelta(hours=8))


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'), parse_float=Decimal)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def require_off_peak(instant):
    local = instant.astimezone(BEIJING)
    seconds = local.hour * 3600 + local.minute * 60 + local.second
    assert local.weekday() >= 5 or not (9 * 3600 <= seconds < 12 * 3600 or 14 * 3600 <= seconds < 18 * 3600)


protocol = read(HERE / 'protocol.json')
binding = read(HERE / 'binding.json')
assert sha(HERE / 'protocol.json') == sha(OLD / 'protocol.json') == binding['protocolSha256']
assert sha(HERE / 'protocol.json') == (HERE / 'protocol.sha256').read_text().strip()
assert sha(HERE / 'TimeAwareProbe.java') == binding['bridgeSourceSha256']
assert sha(HERE / 'classes/TimeAwareProbe.class') == binding['bridgeClassSha256']
assert 'TIME_DECISION_ROUNDTRIP 2/2' in (HERE / 'time-roundtrip.log').read_text(encoding='utf-8')
for path, identity in protocol['sourceObjects'].items():
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD:' + path], cwd=ROOT, text=True).strip() == identity
run = read(HERE / 'run-start.json')
assert subprocess.check_output(['git', 'show', run['commit'] + ':reports/demo-http-20-20261117-v2/protocol.json'], cwd=ROOT) == (HERE / 'protocol.json').read_bytes()
assert run['commit'] == subprocess.check_output(['git','rev-parse','d396701'],cwd=ROOT,text=True).strip()
failure = read(OLD / 'no-paid-startup-failure.json')
assert failure['responseFiles'] == failure['modelLogRecords'] == 0 and failure['applicationStopped']
assert not list((OLD / 'requests').glob('*.json')) and not list((OLD / 'llm-calls').glob('*.jsonl'))

records = [read(path) for path in sorted((HERE / 'requests').glob('*.json'))]
assert len(records) == len(protocol['cases']) == 20
expected_counts = {}
latencies = []
slots = {}
for number, (record, case) in enumerate(zip(records, protocol['cases']), 1):
    assert record['index'] == number and record['caseId'] == case['id']
    assert record['request'] == dict(question=case['question'], sessionId=case['sessionId'])
    exchange = record['exchange']
    assert json.loads(exchange['rawBody'], parse_float=Decimal) == exchange['body']
    require_off_peak(datetime.fromisoformat(exchange['startedAt']))
    body = exchange['body']
    assert body['sessionId'] == case['sessionId']
    assert record['healthAfter']['httpStatus'] == 200
    expected_counts[case['sessionId']] = expected_counts.get(case['sessionId'], 0) + 1
    actual_counts = {item['sessionId']: item['turnCount'] for item in record['sessionsAfter']['body']}
    assert record['sessionsAfter']['httpStatus'] == 200 and actual_counts == expected_counts
    budget = body['budget']
    assert budget['timeoutMs'] == 15000 and budget['maxModelCalls'] == 2
    for call_number in range(1, budget['modelCalls'] + 1):
        key = (budget['requestId'], call_number)
        assert key not in slots
        slots[key] = case['id']
    latencies.append(exchange['httpWallMs'])
    window = read(HERE / 'window-checks' / f'{number:02}.json')
    assert window['allowed'] and window['reason'] == 'OFF_PEAK' and window['guardSeconds'] == 120
    require_off_peak(datetime.fromtimestamp(float(window['checkedAt']), timezone.utc))
    if case['followUp']:
        assert record['businessCorrect'] and body['schemaReused'] and '2017' in body['rewrittenQuestion']

calls = [json.loads(line, parse_float=Decimal) for file in (HERE / 'llm-calls').glob('*.jsonl')
         for line in file.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
assert len(calls) == len(slots) == 22
assert {(call['requestId'], call['callNumber']) for call in calls} == set(slots)
assert sum(call['status'] == 'CONVERSATION_REWRITE' for call in calls) == 2
assert sum(call['status'] == 'OK' for call in calls) == 20
cost = Decimal(0)
tokens = dict(prompt=0, completion=0, cacheHit=0, cacheMiss=0)
for call in calls:
    assert call['usageKnown'] and call['status'] in {'OK', 'CONVERSATION_REWRITE'}
    assert call['promptTokens'] == call['cacheHitTokens'] + call['cacheMissTokens']
    assert call['totalTokens'] == call['promptTokens'] + call['completionTokens']
    price = (Decimal(call['cacheHitTokens']) * Decimal('.00002')
             + Decimal(call['cacheMissTokens']) * Decimal('.001')
             + Decimal(call['completionTokens']) * Decimal('.004')) / 1000
    assert abs(price - call['costYuan']) < Decimal('.000000000001')
    cost += price
    for key, field in [('prompt','promptTokens'),('completion','completionTokens'),('cacheHit','cacheHitTokens'),('cacheMiss','cacheMissTokens')]:
        tokens[key] += call[field]
    require_off_peak(datetime.fromisoformat(call['timestamp']).replace(tzinfo=BEIJING))
summary = read(HERE / 'summary.json')
assert summary['total'] == 20 and summary['httpSuccess'] == sum(r['exchange']['httpStatus'] == 200 for r in records) == 19
assert summary['businessCorrect'] == sum(r['businessCorrect'] for r in records) == 16
assert summary['meanHttpMs'] == Decimal(sum(latencies)) / 20
assert summary['p95HttpMs'] == sorted(latencies)[math.ceil(.95 * 20) - 1] == 11030
assert summary['knownCostYuan'] == cost == Decimal('0.03923214')
assert summary['tokens'] == tokens and summary['completeCost']
assert all(summary['sessionIsolation'].values())
assert all(summary[key] == 0 for key in ('snapshotUnknownUsageCalls','ledgerUnknownUsageSlots','unassignedLogRecords','extraLogSlots','missingRequestBudgets'))
assert read(HERE / 'sessions-after-cleanup.json')['body'] == []
assert read(HERE / 'health-after.json')['httpStatus'] == 200
execution = read(HERE / 'execution.json')
assert execution['completed'] and execution['callerExitCode'] == 0 and execution['portClosed']
assert read(HERE / 'summary-execution.json')['exitCode'] == 0
shutdown = read(HERE / 'shutdown.json')
assert all(not item['alive'] for item in shutdown['processes']) and all(item['closed'] for item in shutdown['ports'])
assert not shutdown['isolatedContainerRunning'] and shutdown['isolatedVolumePreserved'] and shutdown['originalContainer']['running']

manifest = HERE / 'manifest.json'
if '--capture' in sys.argv:
    assert not manifest.exists(), 'Never overwrite evidence'
    artifacts = [dict(path=str(path.relative_to(ROOT)).replace('\\', '/'), sha256=sha(path))
                 for path in sorted(HERE.rglob('*')) if path.is_file() and '__pycache__' not in path.parts]
    with manifest.open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(dict(artifacts=artifacts), handle, ensure_ascii=False, indent=2)
        handle.write('\n')
for item in read(manifest)['artifacts']:
    assert sha(ROOT / item['path']) == item['sha256'], item['path']
print(json.dumps(dict(audit='PASS', httpSuccess='19/20', strictResultMatches='16/20',
    p95HttpMs=11030, configuredCostYuan=str(cost), completeUsage=True,
    modelCalls=22, sessionInvariants=True, offPeakWindows=20, originalFailedAttemptPaidCalls=0)))
