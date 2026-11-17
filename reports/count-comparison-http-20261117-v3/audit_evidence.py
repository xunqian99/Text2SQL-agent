"""Audit all ten preregistered outcomes, prompt version, complete usage and closure."""
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import subprocess
import sys

folder=Path(__file__).resolve().parent
root=folder.parents[1]
sys.path.insert(0,str(root/'scripts'))
from eval_lib import normalize_result


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'),parse_float=Decimal)


manifest=read(folder/'manifest.json')
for entry in manifest['artifacts']:
    assert hashlib.sha256((folder/entry['path']).read_bytes()).hexdigest()==entry['sha256'],entry['path']
protocol=read(folder/'protocol.json')
frozen=manifest['protocolCommit']
assert subprocess.check_output(['git','show',f'{frozen}:{folder.relative_to(root).as_posix()}/protocol.json'],cwd=root)==(folder/'protocol.json').read_bytes()
for path,tree in protocol['sourceTrees'].items():
    for commit in (protocol['sourceCommit'],frozen):
        assert subprocess.check_output(['git','rev-parse',f'{commit}:{path}'],cwd=root,text=True).strip()==tree
assert len(protocol['cases'])==10 and protocol['format']=='named-count-comparison-http-v3'
previous=read(root/'reports/count-comparison-http-20261117-v2/protocol.json')
assert protocol['cases']==previous['cases'] and protocol['datasetSha256']==previous['datasetSha256']
summary=read(folder/'summary.json')
calls=[json.loads(line,parse_float=Decimal) for path in (folder/'llm-calls').glob('*.jsonl')
       for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
assert len(calls)==summary['modelCalls']
assert len({(c['requestId'],c['callNumber']) for c in calls})==len(calls)
assert all(c['usageKnown'] and c['status']=='OK' for c in calls)
assert all(c['configuredPromptVersion']=='p13-result-contract-v13' for c in calls)
assert all(c['configuredMetricDefinitionsSha256']==protocol['metricDefinitionsSha256'] for c in calls)
numeric=0
for item,outcome in zip(protocol['cases'],summary['outcomes'],strict=True):
    response=read(folder/'responses'/f"{item['id']}.json");body=response['body']
    assert response['id']==outcome['id']==item['id']
    match=response['httpStatus']==200 and body['status']=='SUCCESS' and not body['truncated'] \
        and normalize_result(body['rows'],ordered=False)==item['expectedRows']
    numeric+=int(match)
    assert match==outcome['correct'] and response['wallMs']==outcome['wallMs']
    request_calls=[c for c in calls if c['requestId']==body['budget']['requestId']]
    assert len(request_calls)==body['budget']['modelCalls']
    assert body['budget']['unknownUsageCalls']==0
    assert read(folder/f"{item['id']}-offpeak.json")['allowed']
    for key in ('promptTokens','completionTokens','totalTokens'):
        assert body['llm'][key]==sum(c[key] for c in request_calls)
for call in calls:
    assert call['promptTokens']==call['cacheHitTokens']+call['cacheMissTokens']
    assert call['totalTokens']==call['promptTokens']+call['completionTokens']
    cost=(Decimal('0.02')*call['cacheHitTokens']+call['cacheMissTokens']+4*call['completionTokens'])/1_000_000
    assert abs(cost-call['costYuan'])<Decimal('0.000000000001')
cost=sum((Decimal('0.02')*c['cacheHitTokens']+c['cacheMissTokens']+4*c['completionTokens'])/1_000_000 for c in calls)
assert cost==summary['knownConfiguredCostYuan']==read(folder/'cost-status.json')['knownCostYuan']
ledger=read(folder/'cost-ledger.json')
assert ledger['knownCalls']==len(calls) and ledger['knownCostYuan']==cost and not ledger['holds']
assert numeric==summary['correct'] and summary['cases']==10 and summary['responseUnknownUsageCalls']==0
assert (folder/'run.exit').read_text(encoding='utf-8-sig').strip()==('0' if numeric==10 else '1')
closure=read(folder/'shutdown.json')
assert summary['contextClosed'] and not closure['pidPresent'] and not closure['listenerPresent']
assert read(folder/'credential-scan.json')['knownCredentialMatches']==0
print(json.dumps(dict(archive='PASS',matches=f'{numeric}/10',modelCalls=len(calls),unknownCalls=0,
                     totalTokens=sum(c['totalTokens'] for c in calls),knownCostYuan=str(cost))))
