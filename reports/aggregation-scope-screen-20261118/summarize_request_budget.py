"""Audit shared-deadline off/on runs, including stopped attempts and late usage. No model calls."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

from summarize_holdout import dataset_hash, ids_hash, is_dev, read

FIELDS = ('promptTokens', 'completionTokens', 'totalTokens', 'cacheHitTokens', 'cacheMissTokens', 'costYuan')
ORDER = ['gold', 'r1-off', 'r1-on', 'r2-on', 'r2-off', 'r3-off', 'r3-on']


def audit_run(report, ledger, enabled, require):
    items, overall = report['itemEvidence'], report['overall']
    require(len(items) == overall['total'] == 100, 'denominator must be 100')
    require(sum(i['correct'] for i in items) == overall['correct'], 'correct count mismatch')
    require(sum(i['status'] == 'SUCCESS' for i in items) == overall['sqlValid'], 'SQL success count mismatch')
    config = report['meta']['effectiveConfig']
    calls, unknown, retries, recovered, regressed, stops, fallbacks, late = [], [], [], [], [], [], [], []
    raw_by_key = {}
    for row in ledger:
        require(row.get('prompt') is None and row.get('response') is None, 'raw content unexpectedly stored')
        if enabled:
            key = (row.get('requestId'), row.get('callNumber'))
            require(key not in raw_by_key, 'duplicate raw request/call identity')
            raw_by_key[key] = row
    seen_keys = set()
    for item in items:
        ident = item['id']
        actual, attempts, budget = item['calls'], item['productionAttempts'], item.get('budget')
        require([c['number'] for c in actual] == list(range(1, len(actual) + 1)), f'{ident}: call numbering')
        require([a['number'] for a in attempts] == list(range(1, len(attempts) + 1)), f'{ident}: attempt numbering')
        require(len(actual) <= 2, f'{ident}: more than two model calls')
        require(len(attempts) <= 3, f'{ident}: too many attempts including REQUEST_STOP')
        require((budget is not None) == enabled, f'{ident}: missing/unexpected budget')
        if budget:
            require(budget['timeoutMs'] == 15000 and budget['maxModelCalls'] == 2, f'{ident}: budget changed')
            require(budget['modelCalls'] == len(actual), f'{ident}: call ledger differs from budget')
            require(budget['unknownUsageCalls'] == sum(c['usage'] is None for c in actual), f'{ident}: unknown ledger differs')
            require(item['timings']['totalMs'] == budget['elapsedMs'], f'{ident}: total timing excludes budget wait')
            require(abs(item['wallLatencyMs'] - budget['elapsedMs']) <= 1000, f'{ident}: elapsed/wall mismatch')
            if budget['termination'] != 'COMPLETE':
                stops.append({'id': ident, 'reason': budget['termination'], 'stage': budget['stage'],
                              'wallLatencyMs': item['wallLatencyMs'], 'unknownUsageCalls': budget['unknownUsageCalls']})
                require(bool(attempts) and attempts[-1]['stage'] == 'REQUEST_STOP', f'{ident}: stop evidence missing')
                if item['status'] == 'SUCCESS':
                    fallbacks.append({'id': ident, 'correct': item['correct']})
        if item['status'] == 'NEEDS_CLARIFICATION':
            require(not actual and not attempts and not item['correct'], f'{ident}: clarification graded as answer')
        else:
            selected = item['selectedAttempt']
            require(1 <= selected <= len(attempts), f'{ident}: invalid selection')
            if 1 <= selected <= len(attempts):
                chosen = attempts[selected - 1]
                for left, right in (('sql', 'finalSql'), ('status', 'status'), ('correct', 'correct')):
                    require(chosen[left] == item[right], f'{ident}: selected {left} differs')
        if len(actual) > 1:
            retries.append({'id': ident, 'trigger': item['retryTrigger']})
            require(item['retryTrigger'] != 'NONE', f'{ident}: missing retry trigger')
            if attempts and not attempts[0]['correct'] and item['correct']:
                recovered.append(ident)
            if attempts and attempts[0]['correct'] and not item['correct']:
                regressed.append(ident)
        known = []
        for call in actual:
            require(bool(re.fullmatch('[0-9a-f]{64}', call.get('promptSha256') or '')), f'{ident}: prompt hash missing')
            usage = call['usage']
            if usage is None:
                unknown.append(f"{ident}:{call['number']}")
            else:
                known.append(usage)
                require(usage['totalTokens'] > 0 and usage['totalTokens'] == usage['promptTokens'] + usage['completionTokens'],
                        f'{ident}: token components')
                require(usage['promptTokens'] == usage['cacheHitTokens'] + usage['cacheMissTokens'], f'{ident}: cache components')
                price = (usage['cacheMissTokens'] * config['inputPricePer1k'] + usage['cacheHitTokens'] * config['cacheHitPricePer1k']
                         + usage['completionTokens'] * config['outputPricePer1k']) / 1000
                require(abs(price - usage['costYuan']) <= 0.00000051, f'{ident}: price mismatch')
            if enabled:
                key = (budget['requestId'], call['number']) if budget else (None, call['number'])
                seen_keys.add(key)
                row = raw_by_key.get(key)
                if row:
                    require(row['promptSha256'] == call['promptSha256'], f'{ident}: raw prompt mismatch')
                    if row['status'].startswith('LATE_'):
                        require(usage is None, f'{ident}: late usage counted twice')
                        late.append({'id': ident, 'callNumber': call['number'], 'usageKnown': row.get('usageKnown', False),
                                     'costYuan': row['costYuan'], 'totalTokens': row['totalTokens']})
                    else:
                        require(row['status'] == call['status'], f'{ident}: raw status mismatch')
                        require(row.get('usageKnown') == (usage is not None), f'{ident}: raw usage provenance mismatch')
                        if usage:
                            for field in FIELDS:
                                require(abs(row[field] - usage[field]) <= 0.00000051, f'{ident}: raw {field} differs')
                else:
                    require(usage is None, f'{ident}: known call missing JSONL')
        request = item.get('totalUsage') or {}
        for field in FIELDS:
            require(abs(sum(u[field] for u in known) - request.get(field, 0)) <= 0.00000051,
                    f'{ident}: request {field} ledger mismatch')
        calls.extend(actual)
    known_calls = [c for c in calls if c['usage'] is not None]
    if enabled:
        require(set(raw_by_key) <= seen_keys, 'JSONL contains unknown request/call')
    else:
        raw_known = [r for r in ledger if r.get('usageKnown') and not r['status'].startswith('LATE_')]
        require(len(raw_known) == len(known_calls), 'off JSONL known count differs')
        for row, call in zip(raw_known, known_calls):
            require(row['promptSha256'] == call['promptSha256'] and row['status'] == call['status'], 'off raw call identity differs')
            for field in FIELDS:
                require(abs(row[field] - call['usage'][field]) <= 0.00000051, f'off raw {field} differs')
    totals = {f: sum(c['usage'][f] for c in known_calls) for f in FIELDS}
    require(abs(totals['costYuan'] - overall['totalCostYuan']) <= 0.00000051, 'overall cost differs')
    require(abs(totals['promptTokens'] / 100 - overall['avgPromptTokens']) <= .0051, 'overall prompt tokens differ')
    for name in ('byLayer', 'byTableCount'):
        require(sum(g['total'] for g in report[name].values()) == 100 and sum(g['correct'] for g in report[name].values()) == overall['correct'],
                f'{name}: counts differ')
    wall = sorted(i['wallLatencyMs'] for i in items)
    violations = [i['id'] for i in items if enabled and i['wallLatencyMs'] > 16000]
    cleanup_rejected = sum((i.get('budget') or {}).get('cancellationRejected', 0) for i in items)
    return {'total': 100, 'correct': overall['correct'], 'sqlValid': overall['sqlValid'], 'calls': len(calls), **totals,
            'wallAvgMs': sum(wall) / 100, 'wallP50Ms': wall[49], 'wallP95Ms': wall[94], 'wallMaxMs': wall[-1],
            'unknownUsage': unknown, 'lateUsage': late, 'lateKnownCostYuan': sum(r['costYuan'] for r in late if r['usageKnown']),
            'retries': retries, 'recovered': recovered, 'regressed': regressed, 'stops': stops, 'successfulFallbacks': fallbacks,
            'statusCounts': dict(Counter(i['status'] for i in items)), 'boundedReturnViolations': violations,
            'cancellationRejected': cleanup_rejected, 'byLayer': report['byLayer'], 'byTableCount': report['byTableCount'],
            'failures': [i['id'] for i in items if not i['correct']],
            'slowest': [{'id': i['id'], 'wallLatencyMs': i['wallLatencyMs'], 'timings': i['timings'], 'budget': i.get('budget')}
                        for i in sorted(items, key=lambda x: x['wallLatencyMs'], reverse=True)[:10]]}


def summarize(root, verify_workspace=True):
    root = Path(root)
    manifest = read(root / 'manifest.json')
    errors, summaries, reports = [], {}, {}
    def require(condition, message):
        if not condition:
            errors.append(message)
    require(manifest['state'] == 'COMPLETE', 'matrix incomplete')
    if verify_workspace:
        for filename, fingerprint in manifest['protectedSha256'].items():
            path = Path(filename)
            require(path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == fingerprint,
                    f'protected file bytes changed: {filename}')
    require(manifest['order'] == ORDER and [r['name'] for r in manifest['runs']] == ORDER, 'run order changed')
    require(not manifest.get('dryRunOnly'), 'Gold-only run cannot establish gates')
    common_config = None
    for run in manifest['runs']:
        name = run['name']
        require(run['state'] == 'COMPLETE', f'{name}: incomplete')
        path = root / name / run['report']
        require(len(list((root / name).glob('eval-*.json'))) == 1, f'{name}: missing/duplicate report')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == run['reportSha256'], f'{name}: report bytes changed')
        report = reports[name] = read(path)
        meta, items = report['meta'], report['itemEvidence']
        require(meta['gitCommit'] == manifest['codeCommit'], f'{name}: code changed')
        require(meta['datasetSha256'] == dataset_hash('data/eval'), f'{name}: dataset changed')
        require(run['correct'] == report['overall']['correct'] and run['total'] == report['overall']['total'], f'{name}: manifest counts differ')
        if name == 'gold':
            require(meta['dryRun'] and meta['split'] == 'all' and len(items) == 200 and report['overall']['correct'] == 200,
                    'Gold preflight failed')
            require(not any(i['calls'] for i in items), 'Gold called model')
            continue
        def run_require(condition, message):
            require(condition, f'{name}: {message}')
        gold = {i['id']: i for i in reports['gold']['itemEvidence']}
        expected = {ident for ident in gold if not is_dev(ident)}
        run_require({i['id'] for i in items} == expected and len(items) == len(expected), 'sample set changed')
        run_require(meta['sampleIdsSha256'] == ids_hash(expected), 'sample hash changed')
        run_require(not meta['dryRun'] and meta['split'] == 'eval' and meta['limit'] == 0 and not meta['layers'], 'filtered run')
        run_require(meta['productionSelfCorrectionEnabled'] and meta['productionSelfCorrectionMaxAttempts'] == 2
                    and not meta['selfCorrectionEnabled'], 'retry configuration changed')
        config = meta['effectiveConfig']
        enabled = name.endswith('-on')
        run_require(run['enabled'] == config['requestBudgetEnabled'] == enabled, 'wrong switch')
        expected_config = {'requestTimeoutMs': 15000, 'requestMaxModelCalls': 2, 'requestMinRetryRemainingMs': 2000,
                           'sdkMaxAttempts': 1, 'maxTokens': 8192, 'temperature': 0, 'fewshotMaxExamples': 2,
                           'inputPricePer1k': .001, 'cacheHitPricePer1k': .00002, 'outputPricePer1k': .004}
        for key, value in expected_config.items():
            run_require(config[key] == value, f'{key} changed')
        for flag in ('clarificationEnabled', 'resultCheckerEnabled', 'columnPruningEnabled', 'joinPlanEnabled', 'semanticEnabled', 'fewshotEnabled'):
            run_require(config[flag] is True, f'{flag} disabled')
        for flag in ('cacheEnabled', 'routingEnabled', 'toolUseEnabled', 'conversationEnabled'):
            run_require(config[flag] is False, f'{flag} enabled')
        run_require(meta['model'] == 'deepseek-flash' and meta['retrievalEnabled'] and meta['retrievalTopK'] == 8
                    and meta['joinHintsEnabled'], 'model/retrieval changed')
        comparable = {k: v for k, v in config.items() if k != 'requestBudgetEnabled'}
        comparable.update({k: meta[k] for k in ('model', 'baseUrl', 'promptVersion', 'includeDataProfile', 'includeForeignKeys', 'limitMode', 'maxRows')})
        if common_config is None:
            common_config = comparable
        run_require(comparable == common_config, 'more than the budget switch changed')
        for item in items:
            for field in ('questionSha256', 'goldSqlSha256', 'goldResultSha256'):
                run_require(item['id'] in gold and item[field] == gold[item['id']][field], f"{item['id']}: {field} changed")
        ledger = [json.loads(line) for p in sorted((root / name / 'llm-calls').glob('*.jsonl'))
                  for line in p.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        summaries[name] = audit_run(report, ledger, enabled, run_require)
    if set(reports) != set(ORDER):
        return {'passedEvidenceAudit': False, 'errors': errors, 'groups': summaries}
    prompts, missing = {}, []
    for name in ORDER[1:]:
        for item in reports[name]['itemEvidence']:
            if item['calls']:
                value = item['calls'][0]['promptSha256']
                require(value == prompts.setdefault(item['id'], value), f"{name}/{item['id']}: first prompt differs")
            else:
                missing.append(f"{name}/{item['id']}")
    on, off = [summaries[f'r{i}-on'] for i in (1, 2, 3)], [summaries[f'r{i}-off'] for i in (1, 2, 3)]
    on_accuracy, off_accuracy = sum(r['correct'] for r in on) / 3, sum(r['correct'] for r in off) / 3
    p95 = sum(r['wallP95Ms'] for r in on) / 3
    gates = {'meanP95Below8Seconds': p95 < 8000, 'accuracyLossAtMost2pp': on_accuracy >= off_accuracy - 2,
             'eachRunCostAtMost030': None if any(r['unknownUsage'] for r in on) else all(r['costYuan'] <= .30 for r in on),
             'boundedReturn': not any(r['boundedReturnViolations'] for r in on),
             'noRejectedCleanup': not any(r['cancellationRejected'] for r in on)}
    return {'format': 'request-budget-audit-v1', 'codeCommit': manifest['codeCommit'],
            'verifiedCurrentWorkspaceBytes': verify_workspace,
            'passedEvidenceAudit': not errors, 'errors': errors, 'groups': summaries,
            'onMeanAccuracyPercent': on_accuracy, 'offMeanAccuracyPercent': off_accuracy,
            'accuracyDeltaPp': on_accuracy - off_accuracy, 'onMeanP95Ms': p95,
            'offMeanP95Ms': sum(r['wallP95Ms'] for r in off) / 3,
            'gates': gates, 'stageAccepted': not errors and all(v is True for v in gates.values()),
            'noFirstCall': missing, 'commonConfig': common_config,
            'independentBlindTestPassed': False,
            'limits': ['Seen internal eval, not independent blind testing.', 'Cost uses configured prices, not a supplier invoice.',
                       'Unknown usage is a lower bound; late billing is reconciled separately.',
                       'Three sequential runs do not fix provider load/cache/weights or establish concurrent throughput.']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory')
    parser.add_argument('--evidence-only', action='store_true',
                        help='Read-only archive audit: skip current source/private-config byte checks and do not rewrite summary.')
    args = parser.parse_args()
    result = summarize(args.directory, verify_workspace=not args.evidence_only)
    if not args.evidence_only:
        (Path(args.directory) / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: result.get(k) for k in ('passedEvidenceAudit', 'errors', 'onMeanAccuracyPercent', 'offMeanAccuracyPercent',
                                               'onMeanP95Ms', 'offMeanP95Ms', 'gates', 'stageAccepted', 'verifiedCurrentWorkspaceBytes')}, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['passedEvidenceAudit'] else 1)
