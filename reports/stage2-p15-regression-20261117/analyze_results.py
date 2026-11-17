"""Read-only comparison of two completed stage2 regressions; never sends requests."""
from collections import Counter
import json
from pathlib import Path

def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))

folder = Path('reports/stage2-p15-regression-20261117')
old_folder = Path('reports/stage2-regression-20261117')
manifest = read(folder / 'manifest.json')
assert manifest['state'] == 'COMPLETE'
old_manifest = read(old_folder / 'manifest.json')
reports = {entry['name']: read(folder / entry['name'] / entry['report']) for entry in manifest['runs']}
old_reports = {entry['name']: read(old_folder / entry['name'] / entry['report']) for entry in old_manifest['runs']}
gold = {item['id']: item for item in reports['gold']['itemEvidence']}
current_by_id, old_by_id, details, rounds = {}, {}, [], []
for name in ('r1', 'r2', 'r3'):
    report, old = reports[name], old_reports[name]
    for item in report['itemEvidence']:
        current_by_id.setdefault(item['id'], []).append(item)
    for item in old['itemEvidence']:
        old_by_id.setdefault(item['id'], []).append(item)
    failure_by_id = {failure['id']: failure for failure in report['failures']}
    for item in report['itemEvidence']:
        if item['correct']:
            continue
        failure = failure_by_id[item['id']]
        details.append(dict(round=name, id=item['id'], question=failure['question'],
                            status=item['status'], goldRuntimeSql=gold[item['id']]['finalSql'],
                            actualSql=item['finalSql'], message=failure.get('message'),
                            callCount=len(item['calls']), selectedAttempt=item['selectedAttempt'],
                            retryTrigger=item['retryTrigger']))
    rounds.append(dict(name=name, correct=report['overall']['correct'],
                       sqlSuccess=report['overall']['sqlValid'],
                       failedStatusCounts=dict(Counter(item['status'] for item in report['itemEvidence'] if not item['correct'])),
                       calls=sum(len(item['calls']) for item in report['itemEvidence'])))
assert set(current_by_id) == set(old_by_id) and len(current_by_id) == 100
changes = []
for ident in sorted(current_by_id):
    before = sum(item['correct'] for item in old_by_id[ident])
    after = sum(item['correct'] for item in current_by_id[ident])
    if before != 3 or after != 3:
        changes.append(dict(id=ident, oldCorrect=before, newCorrect=after, runs=3,
                            deltaCorrect=after-before,
                            statuses=[item['status'] for item in current_by_id[ident]]))
result = dict(scope='Historical three-round comparison, not causal attribution or independent blind testing.',
              oldScores=[old_reports[name]['overall']['correct'] for name in ('r1','r2','r3')],
              scores=[reports[name]['overall']['correct'] for name in ('r1','r2','r3')],
              rounds=rounds, failureOccurrences=len(details),
              distinctFailureIds=len({item['id'] for item in details}),
              failureStatusCounts=dict(Counter(item['status'] for item in details)),
              perQuestionComparison=changes, details=details)
(folder / 'failure-analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k not in ('details','perQuestionComparison')}, ensure_ascii=False))
print('perQuestionComparison=', json.dumps(changes, ensure_ascii=False))
