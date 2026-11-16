"""Audit the preregistered 100-question dev screen; never alters questions or calls a model."""
import argparse
import hashlib
import json
from pathlib import Path

from summarize_holdout import dataset_hash, ids_hash, is_dev, read
from summarize_request_budget import audit_run

ORDER = ['gold', 'baseline-8192', 'candidate-2048']


def decision(baseline, candidate):
    gates = {
        'accuracyLossAtMost2pp': candidate['correct'] >= baseline['correct'] - 2,
        'p95ReductionAtLeast10Percent': candidate['wallP95Ms'] <= .9 * baseline['wallP95Ms'],
        'extraTruncatedRequestsAtMost1': len(candidate['truncatedRequests']) <= len(baseline['truncatedRequests']) + 1,
        'completeCostAtMost030': None if candidate['unknownUsage'] else candidate['costYuan'] <= .30,
        'boundedReturn': not candidate['boundedReturnViolations'],
        'noRejectedCleanup': candidate['cancellationRejected'] == 0,
    }
    return {'screenGates': gates, 'candidateForConfirmation': all(value is True for value in gates.values()),
            'candidateP95Below8Seconds': candidate['wallP95Ms'] < 8000,
            'accuracyDeltaPp': candidate['correct'] - baseline['correct'],
            'p95DeltaMs': candidate['wallP95Ms'] - baseline['wallP95Ms'],
            'stageAccepted': False}


def summarize(root, verify_workspace=True):
    root = Path(root)
    manifest = read(root / 'manifest.json')
    errors, reports, groups = [], {}, {}
    def require(condition, message):
        if not condition:
            errors.append(message)
    require(manifest['state'] == 'COMPLETE', 'screen incomplete')
    require(manifest['format'] == 'output-budget-screen-v1', 'unexpected protocol')
    require(not manifest['dryRunOnly'], 'Gold-only cannot establish screening results')
    require(manifest['distinctRealQuestions'] == 100 and manifest['plannedRealRequests'] == 200, 'question/request counts changed')
    require(manifest['order'] == ORDER and [r['name'] for r in manifest['runs']] == ORDER, 'run order changed')
    if verify_workspace:
        for name, expected in manifest['protectedSha256'].items():
            path = Path(name)
            require(path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == expected, f'frozen bytes changed: {name}')
    common_config = None
    for run in manifest['runs']:
        name = run['name']
        path = root / name / run['report']
        require(run['state'] == 'COMPLETE', f'{name}: incomplete')
        require(len(list((root / name).glob('eval-*.json'))) == 1, f'{name}: missing/duplicate report')
        require(hashlib.sha256(path.read_bytes()).hexdigest() == run['reportSha256'], f'{name}: report bytes changed')
        report = reports[name] = read(path)
        meta, items = report['meta'], report['itemEvidence']
        require(meta['gitCommit'] == manifest['codeCommit'], f'{name}: code changed')
        require(meta['datasetSha256'] == dataset_hash('data/eval'), f'{name}: source dataset changed')
        require(run['correct'] == report['overall']['correct'] and run['total'] == report['overall']['total'], f'{name}: manifest counts differ')
        if name == 'gold':
            require(meta['dryRun'] and meta['split'] == 'all' and len(items) == 200 and report['overall']['correct'] == 200, 'Gold preflight failed')
            require(not any(i['calls'] for i in items), 'Gold called model')
            continue
        def check(condition, message):
            require(condition, f'{name}: {message}')
        gold = {i['id']: i for i in reports['gold']['itemEvidence']}
        expected_ids = {ident for ident in gold if is_dev(ident)}
        check(len(items) == len(expected_ids) == 100 and {i['id'] for i in items} == expected_ids, 'dev sample set changed')
        check(meta['sampleIdsSha256'] == ids_hash(expected_ids), 'sample hash changed')
        check(meta['split'] == 'dev' and not meta['dryRun'] and meta['limit'] == 0 and not meta['layers'], 'filtered/wrong split')
        check(meta['productionSelfCorrectionEnabled'] and meta['productionSelfCorrectionMaxAttempts'] == 2
              and not meta['selfCorrectionEnabled'], 'retry configuration changed')
        config = meta['effectiveConfig']
        cap = 8192 if name == 'baseline-8192' else 2048
        check(run['maxTokens'] == config['maxTokens'] == cap, 'output cap changed')
        for key, value in {'requestBudgetEnabled': True, 'requestTimeoutMs': 15000, 'requestMaxModelCalls': 2,
                           'requestMinRetryRemainingMs': 2000, 'sdkMaxAttempts': 1, 'temperature': 0,
                           'fewshotMaxExamples': 2, 'inputPricePer1k': .001,
                           'cacheHitPricePer1k': .00002, 'outputPricePer1k': .004}.items():
            check(config[key] == value, f'{key} changed')
        for key in ('clarificationEnabled', 'resultCheckerEnabled', 'columnPruningEnabled', 'joinPlanEnabled', 'semanticEnabled', 'fewshotEnabled'):
            check(config[key] is True, f'{key} disabled')
        for key in ('cacheEnabled', 'routingEnabled', 'toolUseEnabled', 'conversationEnabled'):
            check(config[key] is False, f'{key} enabled')
        check(meta['model'] == 'deepseek-flash' and meta['retrievalEnabled'] and meta['retrievalTopK'] == 8 and meta['joinHintsEnabled'],
              'model/retrieval changed')
        comparable = {k: v for k, v in config.items() if k != 'maxTokens'}
        comparable.update({k: meta[k] for k in ('model', 'baseUrl', 'promptVersion', 'includeDataProfile', 'includeForeignKeys', 'limitMode', 'maxRows')})
        if common_config is None:
            common_config = comparable
        check(comparable == common_config, 'more than maxTokens changed')
        for item in items:
            for field in ('questionSha256', 'goldSqlSha256', 'goldResultSha256'):
                check(item['id'] in gold and item[field] == gold[item['id']][field], f"{item['id']}: {field} changed")
            truncated_calls = {c['number'] for c in item['calls'] if 'OUTPUT_TRUNCATED' in c['status']}
            truncated_attempts = {a['number'] for a in item['productionAttempts']
                                  if a['stage'] == 'GENERATION' and a['message'] == 'OUTPUT_TRUNCATED'}
            check(truncated_calls == truncated_attempts, f"{item['id']}: truncation call/attempt evidence differs")
        ledger = [json.loads(line) for p in sorted((root / name / 'llm-calls').glob('*.jsonl'))
                  for line in p.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        group = groups[name] = audit_run(report, ledger, True, check)
        group['maxTokens'] = cap
        group['truncatedRequests'] = [i['id'] for i in items if any('OUTPUT_TRUNCATED' in c['status'] for c in i['calls'])]
        group['truncatedCalls'] = sum('OUTPUT_TRUNCATED' in c['status'] for i in items for c in i['calls'])
        group['knownMaxCompletionTokens'] = max((c['usage']['completionTokens'] for i in items for c in i['calls'] if c['usage']), default=0)
        group['completionAboveCap'] = [f"{i['id']}:{c['number']}" for i in items for c in i['calls']
                                       if c['usage'] and c['usage']['completionTokens'] > cap]
        check(not group['completionAboveCap'], 'provider returned completion usage above configured maxTokens')
        group['truncatedRequestOutcomes'] = [{'id': i['id'], 'status': i['status'], 'correct': i['correct'], 'calls': len(i['calls']),
                                             'wallLatencyMs': i['wallLatencyMs']}
                                            for i in items if i['id'] in group['truncatedRequests']]
    if set(reports) != set(ORDER):
        return {'passedEvidenceAudit': False, 'errors': errors, 'groups': groups}
    baseline = {i['id']: i for i in reports['baseline-8192']['itemEvidence']}
    no_prompt = []
    for item in reports['candidate-2048']['itemEvidence']:
        original = baseline[item['id']]
        if item['calls'] and original['calls']:
            require(item['calls'][0]['promptSha256'] == original['calls'][0]['promptSha256'], f"{item['id']}: first prompt differs")
        else:
            no_prompt.append(item['id'])
    result = decision(groups['baseline-8192'], groups['candidate-2048'])
    result['candidateForConfirmation'] &= not errors
    return {'format': 'output-budget-screen-audit-v1', 'codeCommit': manifest['codeCommit'],
            'passedEvidenceAudit': not errors, 'errors': errors, 'verifiedCurrentWorkspaceBytes': verify_workspace,
            'distinctRealQuestions': 100, 'realRequests': 200, 'groups': groups, 'commonConfig': common_config,
            'noFirstPromptPair': no_prompt, **result,
            'limits': ['Single dev screen with baseline first; no stable causal or blind-test claim.',
                       'Unknown usage is a cost lower bound, not zero.', 'No production default change or stage acceptance.']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory')
    parser.add_argument('--evidence-only', action='store_true', help='Do not verify current private workspace bytes or overwrite summary.')
    args = parser.parse_args()
    result = summarize(args.directory, verify_workspace=not args.evidence_only)
    if not args.evidence_only:
        (Path(args.directory) / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: result.get(k) for k in ('passedEvidenceAudit', 'errors', 'distinctRealQuestions', 'realRequests',
                                               'screenGates', 'candidateForConfirmation', 'accuracyDeltaPp', 'p95DeltaMs',
                                               'candidateP95Below8Seconds', 'stageAccepted')}, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['passedEvidenceAudit'] else 1)
