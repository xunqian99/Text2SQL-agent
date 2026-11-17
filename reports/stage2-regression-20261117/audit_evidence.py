"""Audit the preregistered stage 2 historical regression; no model requests."""
import argparse
from collections import Counter
from datetime import datetime, timezone, timedelta
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import subprocess

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from summarize_holdout import read, dataset_hash, ids_hash, is_dev
from summarize_request_budget import audit_run


def summarize(directory, live=False):
    folder = Path(directory)
    manifest = read(folder / 'manifest.json')
    errors, groups = [], {}

    def require(ok, message):
        if not ok:
            errors.append(message)

    require(manifest['format'] == 'stage2-regression-v1' and manifest['state'] == 'COMPLETE', 'incomplete/wrong protocol')
    require(manifest['order'] == [r['name'] for r in manifest['runs']] == ['gold', 'r1', 'r2', 'r3'], 'run order differs')
    require(manifest['referenceAccuracyPercent'] == 91 and manifest['minimumMeanAccuracyPercent'] == 89, 'threshold changed')
    require(manifest['distinctRealQuestions'] == 100 and manifest['plannedRealRequests'] == 300, 'denominator changed')
    for name, sha in manifest['protectedSha256'].items():
        if name.endswith('application-local.yml'):
            if live:
                require(hashlib.sha256(Path(name).read_bytes()).hexdigest() == sha, 'private config changed')
            continue
        blob = subprocess.check_output(['git', 'show', manifest['codeCommit'] + ':' + name])
        # PowerShell scripts have CRLF working bytes; compare Git's checkout conversion.
        indexed = subprocess.check_output(['git', 'cat-file', '--filters', '--path=' + name,
                                           manifest['codeCommit'] + ':' + name])
        if hashlib.sha256(indexed).hexdigest() != sha:
            archived = folder / 'source-bytes' / name
            require(archived.is_file(), 'missing original byte witness: ' + name)
            if archived.is_file():
                raw = archived.read_bytes()
                require(hashlib.sha256(raw).hexdigest() == sha, 'original byte witness changed: ' + name)
                require(raw.replace(b'\r\n', b'\n') == indexed.replace(b'\r\n', b'\n'),
                        'difference beyond CRLF/LF: ' + name)
        if live:
            require(hashlib.sha256(Path(name).read_bytes()).hexdigest() == sha, 'working file changed: ' + name)
    if live:
        for name, sha in manifest['runtimeSha256'].items():
            require(hashlib.sha256(Path(name).read_bytes()).hexdigest() == sha, 'runtime changed: ' + name)

    reference_root = Path('reports/generation-mode-matrix-20261116')
    baseline = read(reference_root / 'manifest.json')
    baseline_reports = []
    for i in (1, 2, 3):
        entry = next(r for r in baseline['runs'] if r['name'] == f'r{i}-default')
        p = reference_root / entry['name'] / entry['report']
        require(hashlib.sha256(p.read_bytes()).hexdigest() == entry['reportSha256'], 'baseline report changed')
        baseline_reports.append(read(p))
    require([r['overall']['correct'] for r in baseline_reports] == [91, 90, 92], 'baseline scores changed')
    require(baseline['codeCommit'].startswith('1e3a11f'), 'baseline commit changed')
    reports, prompts, common = {}, {}, None
    for entry in manifest['runs']:
        name = entry['name']
        require(entry['state'] == 'COMPLETE' and entry['exitCode'] == 0, name + ': run failed')
        p = folder / name / entry['report']
        require(len(list((folder / name).glob('eval-*.json'))) == 1, name + ': duplicate report')
        require(hashlib.sha256(p.read_bytes()).hexdigest() == entry['reportSha256'], name + ': report changed')
        report = reports[name] = read(p)
        meta, items = report['meta'], report['itemEvidence']
        require(meta['gitCommit'] == manifest['codeCommit'], name + ': code changed')
        require(meta['datasetSha256'] == dataset_hash('data/eval'), name + ': dataset changed')
        require(meta['promptVersion'] == 'p10-result-contract-v10', name + ': prompt changed')
        require(entry['total'] == report['overall']['total'] and entry['correct'] == report['overall']['correct'], name + ': counts differ')
        if name == 'gold':
            require(meta['dryRun'] and meta['split'] == 'all' and len(items) == 200 and report['overall']['correct'] == 200,
                    'Gold preflight failed')
            require(not any(i['calls'] for i in items), 'Gold called model')
            continue
        gold = {i['id']: i for i in reports['gold']['itemEvidence']}
        expected = {i for i in gold if not is_dev(i)}
        require(len(items) == 100 and {i['id'] for i in items} == expected and meta['sampleIdsSha256'] == ids_hash(expected), name + ': sample changed')
        require(not meta['dryRun'] and meta['split'] == 'eval' and meta['limit'] == 0 and not meta['layers'], name + ': filtered run')
        require(meta['productionSelfCorrectionEnabled'] and meta['productionSelfCorrectionMaxAttempts'] == 2 and not meta['selfCorrectionEnabled'], name + ': retry config differs')
        config = meta['effectiveConfig']
        expected_values = dict(requestBudgetEnabled=True,requestTimeoutMs=15000,requestMaxModelCalls=2,
                               requestMinRetryRemainingMs=2000,sdkMaxAttempts=1,maxTokens=8192,temperature=0,
                               fewshotMaxExamples=2,configuredReasoningEffort='provider-default',
                               explicitReasoningEnabled=True,fewshotRequireAvailableTables=False,
                               inputPricePer1k=.001,cacheHitPricePer1k=.00002,outputPricePer1k=.004)
        for k, v in expected_values.items():
            require(config.get(k) == v, name + ': ' + k + ' differs')
        for flag in ('clarificationEnabled','resultCheckerEnabled','columnPruningEnabled','joinPlanEnabled','semanticEnabled','fewshotEnabled'):
            require(config[flag] is True, name + ': disabled ' + flag)
        for flag in ('cacheEnabled','routingEnabled','toolUseEnabled','conversationEnabled','joinListEnabled'):
            require(config[flag] is False, name + ': enabled ' + flag)
        require(meta['model'] == 'deepseek-flash' and meta['retrievalEnabled'] and meta['retrievalTopK'] == 8 and meta['joinHintsEnabled'], name + ': model/retrieval differs')
        comparable = dict(config)
        comparable.update({k: meta[k] for k in ('model','baseUrl','promptVersion','includeDataProfile','includeForeignKeys','limitMode','maxRows')})
        if common is None:
            common = comparable
        require(comparable == common, name + ': round config differs')
        old = baseline_reports[int(name[-1]) - 1]
        for k, v in old['meta']['effectiveConfig'].items():
            require(config.get(k) == v, name + ': original baseline config differs: ' + k)
        for k in ('model','baseUrl','includeDataProfile','includeForeignKeys','limitMode','maxRows','retrievalTopK','joinHintsEnabled'):
            require(meta[k] == old['meta'][k], name + ': baseline metadata differs: ' + k)
        old_items = {i['id']: i for i in old['itemEvidence']}
        for item in items:
            for field in ('questionSha256','goldSqlSha256','goldResultSha256'):
                require(item[field] == gold[item['id']][field] == old_items[item['id']][field], name + '/' + item['id'] + ': input/Gold differs')
            if item['calls']:
                h = item['calls'][0]['promptSha256']
                require(h == prompts.setdefault(item['id'], h), name + '/' + item['id'] + ': first prompt differs')
        ledger = [json.loads(line) for path in sorted((folder / name / 'llm-calls').glob('*.jsonl'))
                  for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        group = groups[name] = audit_run(report, ledger, True, lambda ok, msg: require(ok, name + ': ' + msg))
        for call in ledger:
            require(call['configuredPromptVersion'] == meta['promptVersion'], name + ': ledger prompt differs')
            require(call['configuredReasoningEffort'] == 'provider-default', name + ': ledger mode differs')
            stamp = datetime.fromisoformat(call['timestamp']).replace(tzinfo=timezone(timedelta(hours=8)))
            require(not (stamp.weekday() < 5 and (9 <= stamp.hour < 12 or 14 <= stamp.hour < 18)), name + ': peak call')
        known = [c for c in ledger if c['usageKnown']]
        known_keys = {(c['requestId'],c['callNumber']) for c in known}
        expected_keys = {(i['budget']['requestId'],c['number']) for i in items for c in i['calls']}
        group['unresolvedUsageCalls'] = len(expected_keys - known_keys)
        group['fullKnownConfiguredCostYuan'] = float(sum(Decimal(str(c['costYuan'])) for c in known))
        group['fullKnownTokens'] = {k: sum(c[k] for c in known) for k in ('promptTokens','completionTokens','totalTokens','cacheHitTokens','cacheMissTokens')}
        group['byLayer'] = report['byLayer']
        group['byTableCount'] = report['byTableCount']
        group['failureIds'] = [i['id'] for i in items if not i['correct']]
    require(len(groups) == 3, 'missing real round')
    accuracy = sum(g['correct'] for g in groups.values()) / 3
    target = read('reports/output-contract-http-20261117/summary.json')
    original_targets = {r['id']:r['correct'] for r in target['outcomes']}
    target_fixed = sum(original_targets.get(i) is True for i in ('original-year-rows','original-repeat-percent'))
    require(target_fixed == 2, 'target case evidence differs')
    gates = dict(targetFailuresFixedAtLeastHalf=target_fixed >= 2,
                 meanAccuracyAtLeast89=accuracy >= 89,
                 gold200=reports['gold']['overall']['correct'] == 200)
    return dict(format='stage2-regression-summary-v1',passedEvidenceAudit=not errors,errors=errors,
                codeCommit=manifest['codeCommit'],groups=groups,commonConfig=common,distinctRealQuestions=100,realRequests=300,
                meanAccuracyPercent=accuracy,referenceAccuracyPercent=91,accuracyDeltaPp=accuracy-91,
                targetStrictFixes='2/3',gates=gates,stage2MeasuredGatesPassed=not errors and all(gates.values()),
                meanP95Ms=sum(g['wallP95Ms'] for g in groups.values())/3,
                fullKnownConfiguredCostYuan=sum(g['fullKnownConfiguredCostYuan'] for g in groups.values()),
                unresolvedUsageCalls=sum(g['unresolvedUsageCalls'] for g in groups.values()),
                failureUnion=sorted({i for g in groups.values() for i in g['failureIds']}),
                failureFrequency=dict(Counter(i for g in groups.values() for i in g['failureIds'])),
                independentBlindTestPassed=False,stage3Deferred=True,
                limits=['Historical comparison is not causal attribution or independent blind testing.',
                        'Target Top10 exact-list mismatch remains; no scorer/Gold changes.',
                        'Configured prices are estimates; unresolved usage is not zero cost.'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory')
    parser.add_argument('--live-inputs', action='store_true')
    args = parser.parse_args()
    result = summarize(args.directory, args.live_inputs)
    (Path(args.directory) / 'summary.json').write_bytes((json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode())
    print(json.dumps({k:v for k,v in result.items() if k not in ('groups','commonConfig')},ensure_ascii=False,indent=2))
    raise SystemExit(0 if result['passedEvidenceAudit'] else 1)
