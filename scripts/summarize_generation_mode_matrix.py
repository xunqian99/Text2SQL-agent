"""Audit preregistered eval100 default/none alternating three-round confirmation."""
import argparse
import hashlib
import json
from pathlib import Path
from summarize_holdout import dataset_hash, ids_hash, is_dev, read
from summarize_request_budget import audit_run

ORDER = ['gold', 'r1-default', 'r1-none', 'r2-none', 'r2-default', 'r3-default', 'r3-none']


def decision(default, none, reference=92):
    baseline_accuracy = sum(r['correct'] for r in default) / 3
    accuracy = sum(r['correct'] for r in none) / 3
    p95 = sum(r['wallP95Ms'] for r in none) / 3
    gates = {
        'meanP95Below8Seconds': p95 < 8000,
        'accuracyLossAtMost2ppContemporaneous': accuracy >= baseline_accuracy - 2,
        'accuracyLossAtMost2ppFrozen92': accuracy >= reference - 2,
        'eachRunCostAtMost030': None if any(r['unknownUsage'] for r in none) else all(r['costYuan'] <= .30 for r in none),
        'boundedReturn': not any(r['boundedReturnViolations'] for r in none),
        'noRejectedCleanup': not any(r['cancellationRejected'] for r in none),
        'extraTruncatedRequestsAtMost1PerRound': all(len(n['truncatedRequests']) <= len(b['truncatedRequests']) + 1
                                                    for b, n in zip(default, none)),
    }
    return {'noneMeanAccuracyPercent': accuracy, 'defaultMeanAccuracyPercent': baseline_accuracy,
            'accuracyDeltaPp': accuracy - baseline_accuracy, 'noneMeanP95Ms': p95,
            'defaultMeanP95Ms': sum(r['wallP95Ms'] for r in default) / 3,
            'frozenReferenceAccuracyPercent': reference, 'gates': gates,
            'stageAccepted': all(value is True for value in gates.values())}


def summarize(root, verify_workspace=True):
    root = Path(root)
    manifest = read(root / 'manifest.json')
    errors, summaries, reports = [], {}, {}
    def require(condition, message):
        if not condition:
            errors.append(message)
    require(manifest['state'] == 'COMPLETE', 'matrix incomplete')
    require(manifest['format'] == 'generation-mode-matrix-v1', 'wrong protocol')
    require(manifest['distinctRealQuestions'] == 100 and manifest['plannedRealRequests'] == 600, 'question/request counts changed')
    reference = read('reports/ablation-20261116/summary.json')
    require(reference['passedEvidenceAudit'] and reference['groups']['baseline']['correct']['mean'] == manifest['referenceAccuracyPercent'] == 92,
            'frozen baseline changed')
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
            require(meta['effectiveConfig'].get('configuredReasoningEffort') == 'provider-default'
                    and meta['effectiveConfig']['maxTokens'] == 8192, 'Gold config changed')
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
        mode = 'none' if name.endswith('-none') else 'provider-default'
        run_require(run['configuredReasoningEffort'] == config.get('configuredReasoningEffort') == mode, 'configured mode changed')
        run_require(config['requestBudgetEnabled'] is True, 'request budget disabled')
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
        comparable = {k: v for k, v in config.items() if k != 'configuredReasoningEffort'}
        comparable.update({k: meta[k] for k in ('model', 'baseUrl', 'promptVersion', 'includeDataProfile', 'includeForeignKeys', 'limitMode', 'maxRows')})
        if common_config is None:
            common_config = comparable
        run_require(comparable == common_config, 'more than configuredReasoningEffort changed')
        for item in items:
            for field in ('questionSha256', 'goldSqlSha256', 'goldResultSha256'):
                run_require(item['id'] in gold and item[field] == gold[item['id']][field], f"{item['id']}: {field} changed")
        ledger = [json.loads(line) for p in sorted((root / name / 'llm-calls').glob('*.jsonl'))
                  for line in p.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        run_require(all(row.get('configuredReasoningEffort') == mode for row in ledger), 'raw ledger mode differs')
        group = summaries[name] = audit_run(report, ledger, True, run_require)
        group['configuredReasoningEffort'] = mode
        group['truncatedRequests'] = [i['id'] for i in items if any('OUTPUT_TRUNCATED' in c['status'] for c in i['calls'])]
        for item in items:
            truncated_calls = {c['number'] for c in item['calls'] if 'OUTPUT_TRUNCATED' in c['status']}
            truncated_attempts = {a['number'] for a in item['productionAttempts'] if a['stage'] == 'GENERATION' and a['message'] == 'OUTPUT_TRUNCATED'}
            run_require(truncated_calls == truncated_attempts, f"{item['id']}: truncation evidence differs")
            run_require(all(c['usage'] is None or c['usage']['completionTokens'] <= 8192 for c in item['calls']),
                        f"{item['id']}: completion above cap")
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
    default = [summaries[f'r{i}-default'] for i in (1, 2, 3)]
    none = [summaries[f'r{i}-none'] for i in (1, 2, 3)]
    result = decision(default, none, reference=92)
    result['stageAccepted'] &= not errors
    return {'format': 'generation-mode-matrix-audit-v1', 'codeCommit': manifest['codeCommit'],
            'verifiedCurrentWorkspaceBytes': verify_workspace,
            'passedEvidenceAudit': not errors, 'errors': errors, 'groups': summaries,
            'distinctRealQuestions': 100, 'realRequests': 600, **result,
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
    print(json.dumps({k: result.get(k) for k in ('passedEvidenceAudit', 'errors', 'noneMeanAccuracyPercent', 'defaultMeanAccuracyPercent',
                                               'noneMeanP95Ms', 'defaultMeanP95Ms', 'gates', 'stageAccepted', 'verifiedCurrentWorkspaceBytes')}, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['passedEvidenceAudit'] else 1)
