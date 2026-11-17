"""Read-only acceptance evidence audit; capture freezes existing offline bytes once."""
from pathlib import Path
from decimal import Decimal
import hashlib
import json
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'), parse_float=Decimal)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


plan = read(HERE / 'protocol.json')
assert sha(HERE / 'protocol.json') == (HERE / 'protocol.sha256').read_text().strip()
assert sha(HERE / 'run_chain.py') == plan['runnerSha256']
assert sha(HERE / 'public-config-template.yml') == plan['configSha256']
cold = read(ROOT / 'reports/clean-cache-20261117/result.json')
assert plan['jarSha256'] == cold['executableJarSha256'] and cold['mavenExitCode'] == 0
assert plan['sourceCommit'] == '9d6285a419dbf78f19d4d288625c1438a6fad9e9'
assert plan['personalConfigLoaded'] is False and plan['expectedValue'] == '54011'
assert plan['paidQuestionCount'] == 1 and plan['totalBudgetMs'] == 15000 and plan['maxModelCalls'] == 2
for path, identity in plan['sourceObjects'].items():
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD:' + path], cwd=ROOT, text=True).strip()
    assert actual == identity, 'Source changed: ' + path

database = read(HERE / 'database/database-summary.json')
assert database['databasePrepared'] and database['permissionsPassed']
assert len(database['stages']) == 6 and all(item['exitCode'] == 0 for item in database['stages'])
permissions = read(HERE / 'database/permissions.json')
assert permissions['businessTableCount'] == 37
assert permissions['roWritableBusinessTables'] == permissions['rwWritableBusinessTables'] == 0
assert not permissions['roCreateSchema'] and not permissions['rwCreateSchema']
assert permissions['rwCanAppendLog'] and not permissions['rwCanUpdateLog'] and not permissions['rwCanDeleteLog']
for role in ('text2sql_ro', 'text2sql_rw'):
    for kind in ('update', 'create'):
        raw = (HERE / 'database' / f'{role}-{kind}-denied.txt').read_bytes()
        assert b'permission denied' in raw
counts = read(HERE / 'database/table-counts.json')
assert len(counts) == 41 and sum(counts.values()) == 2616948
binding = read(HERE / 'database-binding.json')
assert binding['ports']['5432/tcp'] == [{'HostIp': '127.0.0.1', 'HostPort': '15433'}]
volumes = [item for item in binding['mounts'] if item['Type'] == 'volume']
assert len(volumes) == 1 and volumes[0]['Name'] == 'text2sql-clean-chain-20261117-data'
assert all(not item['RW'] for item in binding['mounts'] if item['Type'] == 'bind')
for item in read(HERE / 'database/protocol.json')['inputs']:
    assert sha(ROOT / item['path']) == item['sha256'], item['path']

runtime = read(HERE / 'no-key-runtime-result.json')
assert runtime['passed'] and runtime['processExited'] and runtime['portClosed'] and not runtime['apiKeyConfigured']
assert '--agent.llm.api-key=' in runtime['command']
assert '--agent.llm.base-url=http://127.0.0.1:1' in runtime['command']
health = read(HERE / 'no-key-health.json')
assert health['httpStatus'] == 200 and health['body']['mode'] == 'demo'
assert health['body']['schemaTableCount'] == 37 and not health['body']['ready']
assert health['body']['limits']['requestTimeoutMs'] == 15000 and health['body']['limits']['maxModelCalls'] == 2
ask = read(HERE / 'no-key-ask.json')
assert ask['httpStatus'] == 503 and ask['body']['status'] == 'NOT_CONFIGURED'
connections = read(HERE / 'no-key-connections.json')
assert len(connections) == 5 and all(item == dict(role='text2sql_ro', database='olist', application='isolated-chain-20261117') for item in connections)
gold_files = list((HERE / 'gold').glob('eval-*.json'))
assert len(gold_files) == 1
gold = read(gold_files[0])
assert gold['meta']['dryRun'] and gold['overall']['total'] == gold['overall']['correct'] == 200
assert gold['overall']['totalCostYuan'] == 0 and not gold['failures']
assert gold['meta']['datasetSha256'] == read(ROOT / 'reports/clean-cache-20261117/gold-result.json')['datasetSha256']
offline = read(HERE / 'offline-result.json')
assert offline['passed'] and offline['goldExitCode'] == 0 and not offline['paidRunStarted']
assert offline['externalModelCalls'] == offline['externalModelCostYuan'] == 0
shutdown = read(HERE / 'offline-shutdown.json')
assert all(not item['alive'] for item in shutdown['processes']) and shutdown['noKeyHttpPortClosed']
assert not shutdown['isolatedContainerRunning'] and shutdown['isolatedVolumePreserved']
assert shutdown['originalContainer'] == plan['originalContainer'] == read(HERE / 'original-before.json')
failure = read(HERE / 'initial-attempt/failure.json')
assert failure['prepareExitCode'] == 1 and not failure['databaseContainerCreated'] and failure['modelCalls'] == 0
assert [item['exitCode'] for item in failure['diagnosis']] == [1, 0]

manifest_path = HERE / 'offline-manifest.json'
if '--capture-offline' in sys.argv:
    assert not manifest_path.exists(), 'Never overwrite a frozen manifest'
    artifacts = [{'path': str(path.relative_to(HERE)).replace('\\', '/'), 'sha256': sha(path)}
                 for path in sorted(HERE.rglob('*')) if path.is_file() and '__pycache__' not in path.parts]
    with manifest_path.open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(dict(kind='offline-bytes-only-before-paid-request', artifacts=artifacts), handle, ensure_ascii=False, indent=2)
        handle.write('\n')
manifest = read(manifest_path)
for item in manifest['artifacts']:
    assert sha(HERE / item['path']) == item['sha256'], item['path']

if '--live-inputs' in sys.argv:
    assert sha(plan['jar']) == plan['jarSha256']
    assert sha(Path(plan['configDirectory']) / 'application-local.yml') == plan['configSha256']
    source = Path(plan['sourceDirectory'])
    assert not (source / 'src/main/resources/application-local.yml').exists()
    witness = read(ROOT / 'reports/clean-cache-20261117/dependency-witness.json')
    for path, identity in witness['sourceFilesSha256'].items():
        assert sha(source / path) == identity, path

result = dict(offlineAudit='PASS', gold='200/200', noKeyHttp='503/NOT_CONFIGURED',
              taggedReadonlyConnections=5, businessTables=37, businessWritableTables=0,
              archivedArtifacts=len(manifest['artifacts']), offlineProviderCalls=0,
              paidAttemptRecorded=(HERE / 'paid-window-check.json').exists(),
              paidResponseRecorded=(HERE / 'paid-ask.json').exists())
if '--paid' in sys.argv:
    response = read(HERE / 'paid-ask.json')
    body = response['body']
    budget = body['budget']
    calls = [json.loads(line, parse_float=Decimal) for path in (HERE / 'paid-calls').glob('*.jsonl')
             for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    assert all(call['requestId'] == budget['requestId'] for call in calls)
    assert len({call['callNumber'] for call in calls}) == len(calls)
    assert all(1 <= call['callNumber'] <= budget['modelCalls'] for call in calls)
    known = [call for call in calls if call['usageKnown']]
    cost = Decimal(0)
    for call in known:
        assert call['cacheHitTokens'] + call['cacheMissTokens'] == call['promptTokens']
        assert call['promptTokens'] + call['completionTokens'] == call['totalTokens']
        value = (Decimal(call['cacheHitTokens']) * Decimal('0.00002')
                 + Decimal(call['cacheMissTokens']) * Decimal('0.001')
                 + Decimal(call['completionTokens']) * Decimal('0.004')) / 1000
        assert abs(value - call['costYuan']) < Decimal('0.000000000001')
        cost += value
    complete_cost = len(known) == budget['modelCalls'] and budget['unknownUsageCalls'] == 0
    result.update(paidHttpStatus=response['httpStatus'], businessCorrect=body.get('rows') == [['54011']],
                  httpWallMs=response['wallMs'], modelCalls=budget['modelCalls'],
                  responseUnknownUsageCalls=budget['unknownUsageCalls'], completeCost=complete_cost,
                  knownConfiguredCostYuan=str(cost), knownTotalTokens=sum(call['totalTokens'] for call in known),
                  runtimePassed=read(HERE / 'paid-runtime-result.json')['passed'])
    assert read(HERE / 'paid-window-check.json')['allowed']
print(json.dumps(result, ensure_ascii=False))
