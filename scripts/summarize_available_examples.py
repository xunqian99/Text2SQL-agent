"""Audit the frozen available-example-tables dev screen, including actual pre-call message identities."""
import argparse
import hashlib
import json
from pathlib import Path
from summarize_holdout import dataset_hash, ids_hash, is_dev, read
from summarize_request_budget import audit_run
from summarize_output_budget import decision

VERSION = 'p9-mdl-alignment-v9'
ORDER = ['gold', 'overlap', 'available-tables']


def audit_pairs(pairs, require):
    require(pairs['format'] == 'available-examples-pairs-v1' and pairs['split'] == 'dev', 'pair protocol/split differs')
    require(pairs['modelCalls'] == 0 and pairs['sameProcessComparisons'] == 300 and not pairs['sameProcessMismatches'], 'pair preflight incomplete')
    require(pairs['fullProviderChecks'] == 2 and pairs['legacyMessagesMatchPreviousWitness'] == 100, 'provider/legacy preflight incomplete')
    require(pairs['promptVersion'] == VERSION and pairs['systemChars'] == 2795
            and pairs['systemSha256'] == 'f50464555aceac53dc657613a5887c1a72128ab18ef66a3d41c5413fb2419f0e', 'system prompt changed')
    require(pairs['defaultPolicy'] == 'overlap-v1' and pairs['candidatePolicy'] == 'available-tables-v1', 'pair policy changed')
    witnesses = {i['id']: i for i in pairs['items']}
    require(len(witnesses) == len(pairs['items']) == 100, 'duplicate/missing pair items')
    require(pairs['datasetSha256'] == dataset_hash('data/eval') and pairs['sampleIdsSha256'] == ids_hash(witnesses), 'pair dataset/sample differs')
    library = {e['id']: e for e in pairs['examplesLibrary']}
    require(len(library) == len(pairs['examplesLibrary']) > 0, 'duplicate/missing example library')
    for ident, witness in witnesses.items():
        require(is_dev(ident), 'eval item in preflight')
        for field in ('questionSha256', 'commonContextSha256', 'defaultUserSha256', 'candidateUserSha256', 'defaultPromptSha256', 'candidatePromptSha256'):
            value = witness[field]
            require(isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value), f'{ident}: invalid witness fingerprint')
        for kind in ('defaultExamples', 'candidateExamples'):
            examples = witness[kind]
            require(len(examples) <= 2 and len({e['id'] for e in examples}) == len(examples), f'{ident}: duplicate/too many examples')
            for ex in examples:
                require(ex['id'] in library and all(ex[k] == library[ex['id']][k] for k in ('questionSha256', 'sqlSha256', 'tables')), f'{ident}: example identity differs')
                require(ex['questionSha256'] != witness['questionSha256'], f'{ident}: self example selected')
                require(set(ex['missingTables']) == set(ex['tables']) - set(witness['tables']), f'{ident}: missing-dependency evidence differs')
                if kind == 'candidateExamples':
                    require(bool(ex['tables']) and not ex['missingTables'], f'{ident}: incompatible candidate example')
        same = witness['defaultExamples'] == witness['candidateExamples']
        require((witness['defaultPromptSha256'] == witness['candidatePromptSha256']) == same, f'{ident}: message identity does not follow selected examples')
    return witnesses


def summarize(root, verify_workspace=True):
    root = Path(root)
    manifest = read(root / 'manifest.json')
    errors, reports, groups = [], {}, {}
    def require(condition, message):
        if not condition: errors.append(message)
    require(manifest['state'] == 'COMPLETE' and manifest['format'] == 'available-examples-screen-v1', 'incomplete/wrong screen protocol')
    require(manifest.get('configuredReasoningEffort') == 'provider-default', 'provider reasoning changed')
    candidate_name = 'available-tables'
    order = ORDER
    require(not manifest['dryRunOnly'], 'Gold-only cannot establish screening results')
    require(manifest['distinctRealQuestions'] == 100 and manifest['plannedRealRequests'] == 200, 'question/request counts changed')
    require(manifest['order'] == order and [r['name'] for r in manifest['runs']] == order, 'run order changed')
    pair_path = root / 'prompt-pairs.json'
    require(hashlib.sha256(pair_path.read_bytes()).hexdigest() == manifest['promptPairsSha256'], 'prompt pair bytes changed')
    pairs = read(pair_path)
    require(pairs['codeCommit'] == manifest['codeCommit'], 'pair commit differs')
    witnesses = audit_pairs(pairs, require)
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
            require(meta['effectiveConfig'].get('configuredReasoningEffort') == 'provider-default'
                    and meta['effectiveConfig']['maxTokens'] == 8192
                    and meta['effectiveConfig'].get('explicitReasoningEnabled') is True
                    and meta['promptVersion'] == VERSION, 'Gold generation config changed')
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
        cap = 8192
        strict = name == 'available-tables'
        mode = 'provider-default'
        version = VERSION
        policy = 'available-tables-v1' if strict else 'overlap-v1'
        check(run['requireAvailableExampleTables'] is strict and config.get('fewshotRequireAvailableTables') is strict, 'configured example policy changed')
        check(config.get('explicitReasoningEnabled') is True and config.get('fewshotSelectionPolicy') == policy, 'reasoning/policy version changed')
        check(config.get('metricDefinitionsSha256') == pairs['metricDefinitionsSha256'], 'metric source changed')
        check(config.get('configuredReasoningEffort') == mode, 'configured mode changed')
        check(meta['promptVersion'] == version, 'configured prompt version changed')
        check(config['maxTokens'] == cap, 'output cap changed')
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
        comparable = {k: v for k, v in config.items() if k not in ('fewshotRequireAvailableTables', 'fewshotSelectionPolicy')}
        comparable.update({k: meta[k] for k in ('model', 'baseUrl', 'includeDataProfile', 'includeForeignKeys', 'limitMode', 'maxRows')})
        if common_config is None:
            common_config = comparable
        check(comparable == common_config, 'more than example selection policy changed')
        check(set(witnesses) == expected_ids, 'pair ID set differs from frozen dev')
        for item in items:
            check(item['promptHashVersion'] == pairs['promptHashVersion'], f"{item['id']}: hash protocol changed")
            witness = witnesses.get(item['id'])
            check(witness is not None and witness['questionSha256'] == item['questionSha256'], f"{item['id']}: pair question differs")
            if witness and item['calls']:
                first = item['calls'][0]
                prefix = 'candidate' if strict else 'default'
                check(first['promptSha256'] == witness[prefix + 'PromptSha256'], f"{item['id']}: first prompt differs from paired witness")
                if first['usage']:
                    check(first['usage']['promptChars'] == witness[prefix + 'PromptChars'], f"{item['id']}: first length differs from paired witness")
            for field in ('questionSha256', 'goldSqlSha256', 'goldResultSha256'):
                check(item['id'] in gold and item[field] == gold[item['id']][field], f"{item['id']}: {field} changed")
            truncated_calls = {c['number'] for c in item['calls'] if 'OUTPUT_TRUNCATED' in c['status']}
            truncated_attempts = {a['number'] for a in item['productionAttempts']
                                  if a['stage'] == 'GENERATION' and a['message'] == 'OUTPUT_TRUNCATED'}
            check(truncated_calls == truncated_attempts, f"{item['id']}: truncation call/attempt evidence differs")
        ledger = [json.loads(line) for p in sorted((root / name / 'llm-calls').glob('*.jsonl'))
                  for line in p.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        group = groups[name] = audit_run(report, ledger, True, check)
        group['configuredReasoningEffort'] = mode
        group['fewshotRequireAvailableTables'] = strict
        group['fewshotSelectionPolicy'] = policy
        group['promptVersion'] = version
        check(all(row.get('configuredReasoningEffort') == mode for row in ledger), 'raw ledger mode differs')
        check(all(row.get('configuredPromptVersion') == version for row in ledger), 'raw ledger prompt version differs')
        check(all(row.get('configuredFewshotRequireAvailableTables') is strict and row.get('configuredFewshotSelectionPolicy') == policy for row in ledger), 'raw ledger example policy differs')
        check(all(row.get('configuredMetricDefinitionsSha256') == pairs['metricDefinitionsSha256'] for row in ledger), 'raw ledger metric identity differs')
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
    if set(reports) != set(order):
        return {'passedEvidenceAudit': False, 'errors': errors, 'groups': groups}
    baseline = {i['id']: i for i in reports['overlap']['itemEvidence']}
    candidate = {i['id']: i for i in reports[candidate_name]['itemEvidence']}
    no_prompt = [ident for ident in baseline if not baseline[ident]['calls'] or not candidate[ident]['calls']]
    result = decision(groups['overlap'], groups[candidate_name])
    result['candidateForConfirmation'] &= not errors
    return {'format': 'available-examples-screen-audit-v1', 'codeCommit': manifest['codeCommit'],
            'passedEvidenceAudit': not errors, 'errors': errors, 'verifiedCurrentWorkspaceBytes': verify_workspace,
            'distinctRealQuestions': 100, 'realRequests': 200, 'groups': groups, 'commonConfig': common_config,
            'noFirstPromptPair': no_prompt, 'pairedTransitions': {
                'lost': [ident for ident in baseline if baseline[ident]['correct'] and not candidate[ident]['correct']],
                'gained': [ident for ident in baseline if not baseline[ident]['correct'] and candidate[ident]['correct']]}, **result,
            'limits': ['Single dev screen with overlap first; not a blind test or stable causal estimate.',
                       'Unknown usage is a cost lower bound, not zero.', 'No production default change or stage acceptance.']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory')
    parser.add_argument('--evidence-only', action='store_true')
    args = parser.parse_args()
    result = summarize(args.directory, verify_workspace=not args.evidence_only)
    if not args.evidence_only:
        (Path(args.directory) / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: result.get(k) for k in ('passedEvidenceAudit', 'errors', 'distinctRealQuestions', 'realRequests', 'screenGates', 'candidateForConfirmation', 'accuracyDeltaPp', 'p95DeltaMs', 'candidateP95Below8Seconds', 'stageAccepted')}, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['passedEvidenceAudit'] else 1)
