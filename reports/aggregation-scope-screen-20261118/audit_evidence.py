"""Audit one frozen p15/p16 dev comparison; no model requests or scoring changes."""
import argparse
from datetime import datetime
from decimal import Decimal
import hashlib
import json
from pathlib import Path

from summarize_holdout import dataset_hash, ids_hash, is_dev, read
from summarize_request_budget import audit_run

VERSIONS = {'p15': 'p15-entity-coverage-v15', 'p16': 'p16-aggregation-scope-v16'}


def messages(folder):
    arms = {arm: read(folder / (arm + '-messages.json')) for arm in VERSIONS}
    for arm, capture in arms.items():
        assert capture['promptVersion'] == VERSIONS[arm] and capture['modelCalls'] == 0
        assert len(capture['items']) == len({i['id'] for i in capture['items']}) == 100
        assert capture['datasetSha256'] == dataset_hash('data/eval')
    assert arms['p15']['systemSha256'] == '23608183c2f8f3b03e2c6c948aa5963d3a93a24317590102e8f5894ab6893c71'
    assert arms['p15']['systemChars'] - arms['p16']['systemChars'] == 182
    for left, right in zip(arms['p15']['items'], arms['p16']['items']):
        for field in ('id', 'questionSha256', 'userSha256', 'contextSha256'):
            assert left[field] == right[field], (left['id'], field)
        assert left['promptChars'] - right['promptChars'] == 182
        assert left['promptSha256'] != right['promptSha256']
    return arms


def summarize(folder, live=False):
    manifest = read(folder / 'manifest.json')
    assert manifest['format'] == 'aggregation-scope-screen-v1' and manifest['state'] == 'COMPLETE'
    assert manifest['order'] == [r['name'] for r in manifest['runs']] == ['p15', 'p16']
    assert manifest['distinctRealQuestions'] == 100 and manifest['plannedRealRequests'] == 200
    captures = messages(folder)
    errors, groups, reports = [], {}, {}
    def require(ok, message):
        if not ok:
            errors.append(message)
    if live:
        for mapping in ('protectedSha256', 'runtimeSha256'):
            for name, expected in manifest[mapping].items():
                require(hashlib.sha256(Path(name).read_bytes()).hexdigest() == expected, 'frozen bytes changed: ' + name)
    common = None
    for run in manifest['runs']:
        arm = run['name']; path = folder / arm / run['report']; capture = captures[arm]
        require(run['state'] == 'COMPLETE' and run['exitCode'] == 0, arm + ': failed run')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == run['reportSha256'], arm + ': report bytes changed')
        require(len(list((folder / arm).glob('eval-*.json'))) == 1, arm + ': duplicate report')
        report = reports[arm] = read(path); meta = report['meta']; items = report['itemEvidence']
        require(meta['gitCommit'] == manifest['codeCommit'] and meta['promptVersion'] == VERSIONS[arm], arm + ': runtime identity')
        require(meta['split'] == 'dev' and not meta['dryRun'] and meta['limit'] == 0 and not meta['layers'], arm + ': not original full dev')
        require(meta['datasetSha256'] == capture['datasetSha256'], arm + ': dataset changed')
        require(all(is_dev(i['id']) for i in items), arm + ': includes eval items')
        require(meta['sampleIdsSha256'] == capture['sampleIdsSha256'] == ids_hash(i['id'] for i in items), arm + ': sample changed')
        require(run['correct'] == report['overall']['correct'], arm + ': count differs')
        require(meta['productionSelfCorrectionEnabled'] and meta['productionSelfCorrectionMaxAttempts'] == 2 and not meta['selfCorrectionEnabled'], arm + ': correction differs')
        config = meta['effectiveConfig']
        expected = dict(requestBudgetEnabled=True,requestTimeoutMs=15000,requestMaxModelCalls=2,
                        requestMinRetryRemainingMs=2000,sdkMaxAttempts=1,maxTokens=8192,temperature=0,
                        configuredReasoningEffort='provider-default',explicitReasoningEnabled=True,
                        fewshotMaxExamples=2,fewshotRequireAvailableTables=False,
                        inputPricePer1k=.001,cacheHitPricePer1k=.00002,outputPricePer1k=.004)
        for k,v in expected.items(): require(config.get(k) == v, arm + ': changed ' + k)
        for k in ('clarificationEnabled','resultCheckerEnabled','columnPruningEnabled','joinPlanEnabled','semanticEnabled','fewshotEnabled'):
            require(config[k] is True, arm + ': disabled ' + k)
        for k in ('cacheEnabled','routingEnabled','toolUseEnabled','conversationEnabled','joinListEnabled'):
            require(config[k] is False, arm + ': enabled ' + k)
        require(meta['model'] == 'deepseek-flash' and meta['retrievalEnabled'] and meta['retrievalTopK'] == 8 and meta['joinHintsEnabled'], arm + ': model/retrieval changed')
        comparable = dict(config)
        comparable.update({k:meta[k] for k in ('model','baseUrl','includeDataProfile','includeForeignKeys','limitMode','maxRows')})
        if common is None: common = comparable
        require(common == comparable, arm + ': changed more than system rule9')
        witnesses = {i['id']:i for i in capture['items']}
        for item in items:
            witness = witnesses.get(item['id'])
            require(witness is not None and witness['questionSha256'] == item['questionSha256'], arm + ': question changed')
            if witness and item['calls']:
                require(item['calls'][0]['promptSha256'] == witness['promptSha256'], arm + '/' + item['id'] + ': first message drift')
        ledger = [json.loads(line) for p in sorted((folder / arm / 'llm-calls').glob('*.jsonl'))
                  for line in p.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        group = groups[arm] = audit_run(report, ledger, True, lambda ok,msg: require(ok,arm + ': ' + msg))
        for call in ledger:
            require(call['configuredPromptVersion'] == VERSIONS[arm], arm + ': ledger version')
            stamp = datetime.fromisoformat(call['timestamp'])
            require(not (stamp.weekday() < 5 and (9 <= stamp.hour < 12 or 14 <= stamp.hour < 18)), arm + ': peak call')
        known = [c for c in ledger if c['usageKnown']]
        known_keys = {(c['requestId'],c['callNumber']) for c in known}
        expected_keys = {(i['budget']['requestId'],c['number']) for i in items for c in i['calls']}
        group['unresolvedUsageCalls'] = len(expected_keys-known_keys)
        group['fullKnownCostYuan'] = float(sum(Decimal(str(c['costYuan'])) for c in known))
        group['fullKnownTokens'] = {k:sum(c[k] for c in known) for k in ('promptTokens','completionTokens','totalTokens','cacheHitTokens','cacheMissTokens')}
        group['byLayer'] = report['byLayer']
        group['failureIds'] = [i['id'] for i in items if not i['correct']]
    baseline = {i['id']:i for i in reports['p15']['itemEvidence']}
    for item in reports['p16']['itemEvidence']:
        for field in ('questionSha256','goldSqlSha256','goldResultSha256'):
            require(item[field] == baseline[item['id']][field], item['id'] + ': Gold/input drift')
    left, right = groups['p15'], groups['p16']
    gates = dict(accuracyImprovesAtLeast2pp=right['correct'] >= left['correct'] + 2,
                 timeoutsDoNotIncrease=right['statusCounts'].get('REQUEST_TIMEOUT',0) <= left['statusCounts'].get('REQUEST_TIMEOUT',0),
                 complexLayersLoseAtMostOneEach=all(right['byLayer'][layer]['correct'] >= left['byLayer'][layer]['correct']-1
                    for layer in ('multi_join_deep','business_semantics')),
                 costComplete=right['unresolvedUsageCalls'] == 0,
                 costAtMost030=None if right['unresolvedUsageCalls'] else right['fullKnownCostYuan'] <= .30,
                 boundedReturn=not right['boundedReturnViolations'] and right['cancellationRejected']==0)
    return dict(format='aggregation-scope-screen-summary-v1',passedEvidenceAudit=not errors,errors=errors,
                codeCommit=manifest['codeCommit'],groups=groups,screenGates=gates,
                accuracyDeltaPp=right['correct']-left['correct'],
                candidateForConfirmation=not errors and all(v is True for v in gates.values()),stageAccepted=False,
                gained=[i['id'] for i in reports['p16']['itemEvidence'] if i['correct'] and not baseline[i['id']]['correct']],
                lost=[i['id'] for i in reports['p16']['itemEvidence'] if not i['correct'] and baseline[i['id']]['correct']],
                limits=['One dev run per arm, p15 first: order/cache/time effects remain; no stable causal or blind-test claim.',
                        'Unknown usage implies cost lower bound, not zero; stage3 latency optimization remains deferred.'])


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('directory')
    parser.add_argument('--preflight',action='store_true'); parser.add_argument('--live-inputs',action='store_true')
    args=parser.parse_args(); folder=Path(args.directory)
    if args.preflight:
        messages(folder); print('DEV_MESSAGE_PAIRS=100/100; modelCalls=0')
    else:
        result=summarize(folder,args.live_inputs)
        (folder/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({k:v for k,v in result.items() if k!='groups'},ensure_ascii=False,indent=2))
        raise SystemExit(0 if result['passedEvidenceAudit'] else 1)
