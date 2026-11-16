"""Historical dev-only tail diagnosis. No production router, no model calls, no new scores."""
import argparse
import hashlib
import json
import math
from pathlib import Path

from summarize_holdout import is_dev, read
from summarize_generation_mode import summarize as audit_mode
from summarize_prompt_directive import summarize as audit_directive

SOURCES = (
    ('reports/generation-mode-screen-20261116', 'provider-default', audit_mode),
    ('reports/low-reasoning-screen-20261116', 'provider-default', audit_mode),
    ('reports/prompt-directive-screen-20261116', 'explicit-steps', audit_directive),
)
EASY_LAYERS = {'single_table_basic', 'single_table_agg', 'time_window'}


def percentile95(values):
    if not values:
        raise ValueError('Empty observations')
    if any(isinstance(v, bool) or not isinstance(v, int) or v < 0 for v in values):
        raise ValueError('Latency must be a nonnegative integer')
    return sorted(values)[math.ceil(.95 * len(values)) - 1]


def diagnose(items, threshold=8000):
    if not items or len({i['id'] for i in items}) != len(items):
        raise ValueError('Empty or duplicate observations')
    if not all(is_dev(i['id']) for i in items):
        raise ValueError('Only historical development questions may enter this diagnosis')
    original = [i['wallLatencyMs'] for i in items]
    optimistic = [0 if i['difficulty'] in EASY_LAYERS else i['wallLatencyMs'] for i in items]
    observed_p95 = percentile95(original)
    optimistic_p95 = percentile95(optimistic)
    # For strict P95 < threshold, at most N-ceil(.95*N) observations may remain >= threshold.
    allowed_slow = len(items) - math.ceil(.95 * len(items))
    hard_slow = [i for i in items if i['difficulty'] not in EASY_LAYERS and i['wallLatencyMs'] >= threshold]
    all_slow = [i for i in items if i['wallLatencyMs'] >= threshold]
    slow_model = sum((i.get('budget') or {}).get('stageMs', {}).get('MODEL', 0) for i in all_slow)
    slow_wall = sum(i['wallLatencyMs'] for i in all_slow)
    return {
        'observations': len(items),
        'historicalObservedCorrect': sum(i['correct'] for i in items),
        'observedP95Ms': observed_p95,
        'historicalSlowCount': len(all_slow),
        'optimisticallyZeroedCount': sum(i['difficulty'] in EASY_LAYERS for i in items),
        'optimisticP95Ms': optimistic_p95,
        'optimisticPassesP95': optimistic_p95 < threshold,
        'remainingHardSlowCount': len(hard_slow),
        'allowedSlowCountAtStrictThreshold': allowed_slow,
        'minimumHardSlowRequestsThatMustAlsoImprove': max(0, len(hard_slow) - allowed_slow),
        'slowRequestRecordedModelStageFraction': slow_model / slow_wall if slow_wall else None,
        'slowRequestUnknownUsageCalls': sum((i.get('budget') or {}).get('unknownUsageCalls', 0) for i in all_slow),
        'slowItems': [{
            'id': i['id'], 'difficulty': i['difficulty'], 'wallLatencyMs': i['wallLatencyMs'],
            'correct': i['correct'], 'status': i['status'],
            'stageMs': (i.get('budget') or {}).get('stageMs', {}),
            'terminationStage': (i.get('budget') or {}).get('stage'),
            'unknownUsageCalls': (i.get('budget') or {}).get('unknownUsageCalls', 0),
        } for i in all_slow],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Never overwrite historical evidence')
    groups, sources = [], {}
    for directory, name, auditor in SOURCES:
        root = Path(directory)
        audited = auditor(root, verify_workspace=False)
        if not audited['passedEvidenceAudit']:
            raise ValueError(f'Historical evidence audit failed: {directory}: {audited["errors"]}')
        manifest = read(root / 'manifest.json')
        run = next(r for r in manifest['runs'] if r['name'] == name)
        report_path = root / name / run['report']
        report = read(report_path)
        if report['meta']['dryRun'] or report['meta']['split'] != 'dev' or len(report['itemEvidence']) != 100:
            raise ValueError('Expected a real historical dev100 report')
        result = diagnose(report['itemEvidence'])
        if result['observedP95Ms'] != report['overall']['p95LatencyMs']:
            raise ValueError('Historical percentile does not reproduce')
        groups.append({'sourceDirectory': directory, 'sourceCommit': manifest['codeCommit'],
                       'name': name, 'passedHistoricalEvidenceAudit': True, **result})
        # Pin every file used by the full historical audit, including ledgers and witness records.
        for p in sorted(root.rglob('*')):
            if p.is_file() and p.suffix in {'.json', '.jsonl'}:
                sources[p.as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
    output = {
        'format': 'historical-dev-latency-routing-feasibility-v1',
        'hypothesis': 'Only accelerate single-table and time-window questions; leave harder requests unchanged.',
        'optimisticAssumption': 'All 54 easy/time requests take exactly zero milliseconds, with perfect offline classification.',
        'classificationUse': 'Difficulty is used only for an optimistic offline bound, never as a production routing input.',
        'thresholdMs': 8000, 'groups': groups, 'sourceSha256': sources,
        'newModelCalls': 0, 'newTokens': 0, 'newModelCostYuan': 0,
        'decision': 'Reject simple-only routing as a solution for the observed P95 tail.'
                    if all(not g['optimisticPassesP95'] for g in groups) else 'Requires further diagnosis.',
        'limits': ['Historical records from different commits are not a new paired experiment.',
                   'Counterfactual latency is a diagnostic bound, not a measured optimization or new accuracy.',
                   'An unimplemented real classifier can be less accurate and has its own overhead.',
                   'Current production metric definition differs from these historical runs; no current P95 claim.',
                   'Historical sample size is 100 distinct dev questions, reused across three experiments.'],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8', newline='\n') as f:
        json.dump(output, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print(json.dumps({'decision': output['decision'], 'newModelCalls': 0,
                      'groups': [{k: g[k] for k in ('sourceDirectory', 'observedP95Ms', 'optimisticP95Ms',
                                  'minimumHardSlowRequestsThatMustAlsoImprove')} for g in groups]}, ensure_ascii=False))


if __name__ == '__main__':
    main()
