"""Read-only archive audit; numeric equality does not certify timestamp semantics."""
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import subprocess
import sys

folder = Path(__file__).resolve().parent
root = folder.parents[1]
sys.path.insert(0, str(root / 'scripts'))
from eval_lib import normalize_result


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'), parse_float=Decimal)


manifest = read(folder / 'manifest.json')
for entry in manifest['artifacts']:
    assert hashlib.sha256((folder / entry['path']).read_bytes()).hexdigest() == entry['sha256'], entry['path']
protocol = read(folder / 'protocol.json')
frozen = manifest['protocolCommit']
original = subprocess.check_output(['git', 'show', f'{frozen}:{folder.relative_to(root).as_posix()}/protocol.json'], cwd=root)
assert original == (folder / 'protocol.json').read_bytes()
for path, tree in protocol['sourceTrees'].items():
    for commit in (protocol['sourceCommit'], frozen):
        assert subprocess.check_output(['git', 'rev-parse', f'{commit}:{path}'], cwd=root, text=True).strip() == tree
summary = read(folder / 'summary.json')
calls = [json.loads(line, parse_float=Decimal) for p in (folder/'llm-calls').glob('*.jsonl')
         for line in p.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
assert len(calls) == summary['modelCalls'] == 4
assert len({(c['requestId'], c['callNumber']) for c in calls}) == len(calls)
assert all(c['usageKnown'] and c['status'] == 'OK' for c in calls)
assert all(c['configuredMetricDefinitionsSha256'] == protocol['metricDefinitionsSha256'] for c in calls)
for item, outcome in zip(protocol['cases'], summary['outcomes'], strict=True):
    response = read(folder/'responses'/f"{item['id']}.json")
    body = response['body']
    assert response['id'] == outcome['id'] == item['id']
    assert response['httpStatus'] == outcome['httpStatus'] == 200
    assert body['status'] == 'SUCCESS' and not body['truncated']
    assert normalize_result(body['rows'], ordered=False) == item['expectedRows']
    assert outcome['correct'] and response['wallMs'] == outcome['wallMs']
    request_calls = [c for c in calls if c['requestId'] == body['budget']['requestId']]
    assert len(request_calls) == body['budget']['modelCalls'] == 1
    assert body['budget']['unknownUsageCalls'] == 0
    assert read(folder/f"{item['id']}-offpeak.json")['allowed']
    call = request_calls[0]
    for key in ('promptTokens', 'completionTokens', 'totalTokens'):
        assert call[key] == body['llm'][key]
    assert call['promptTokens'] == call['cacheHitTokens'] + call['cacheMissTokens']
    assert call['totalTokens'] == call['promptTokens'] + call['completionTokens']
    calculated = (Decimal('0.02')*call['cacheHitTokens'] + call['cacheMissTokens'] + 4*call['completionTokens'])/1_000_000
    assert abs(calculated-call['costYuan']) < Decimal('0.000000000001')
cost = sum((Decimal('0.02')*c['cacheHitTokens']+c['cacheMissTokens']+4*c['completionTokens'])/1_000_000 for c in calls)
assert cost == summary['knownConfiguredCostYuan'] == read(folder/'cost-status.json')['knownCostYuan']
ledger = read(folder/'cost-ledger.json')
assert ledger['knownCalls'] == 4 and ledger['knownCostYuan'] == cost and not ledger['holds']
assert summary['correct'] == summary['cases'] == 4 and summary['responseUnknownUsageCalls'] == 0
assert (folder/'run.exit').read_text(encoding='utf-8-sig').strip() == '0'
shutdown = read(folder/'shutdown.json')
assert summary['contextClosed'] and not shutdown['pidPresent'] and not shutdown['listenerPresent']
witness = read(folder/'endpoint-witness.json')
assert witness['joinedShipments'] == 98202 and witness['differentEndpoints'] == 0
diagnostic = read(folder/'semantic-diagnostic.json')
assert diagnostic['explicitOrderClockPassed'] is False
sql = read(folder/'responses/explicit-order-clock-by-carrier.json')['body']['sql']
assert 's.delivered_at - o.order_purchase_timestamp' in sql
assert 'order_delivered_customer_date' not in sql
assert read(folder/'credential-scan.json')['knownCredentialMatches'] == 0
print(json.dumps(dict(archive='PASS',numericMatches='4/4',explicitOrderClock='FAIL',modelCalls=4,
                     totalTokens=sum(c['totalTokens'] for c in calls),knownCostYuan=str(cost),unknownCalls=0)))
