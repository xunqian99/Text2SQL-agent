"""Validate complete fresh-context reviews and reconcile their independent decisions.

Does not load a provider client, execute SQL, edit original data or change a score.
"""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[2]
FOLDER=Path(__file__).resolve().parent
read=lambda p:json.loads(Path(p).read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
dispatch=read(FOLDER/'dispatch.json')
assert dispatch['forkTurns']=='none' and not dispatch['humanReviewClaimed']
allowed={'KEEP','CONTRACT_CONFLICT','REFERENCE_DEFECT','AMBIGUOUS','COINCIDENTAL_EQUIVALENCE'}
all_items=[]
provenance=[]
for assignment in dispatch['assignments']:
    source=FOLDER/assignment['inputFile']
    assert sha(source)==assignment['inputSha256']
    original=read(source)['items']
    p=FOLDER/assignment['expectedOutputFile']
    report=read(p)
    claimed=report['provenance']
    assert claimed['freshContext'] is True
    assert claimed['participatedInAuthoringOrTuning'] is False
    assert claimed['readPriorAudit'] is False
    assert claimed['externalModelCalls']==0
    assert str(report['reviewer']).endswith(assignment['reviewer'].split('/')[-1])
    items=report['items']
    assert len(items)==len(original)==assignment['itemCount']
    assert len({x['id'] for x in items})==len(items)
    assert {x['id'] for x in items}=={x['id'] for x in original}
    for item in items:
        assert item['decision'] in allowed,(item['id'],item['decision'])
        assert isinstance(item['reason'],str) and len(item['reason'])>=12,item['id']
        assert item['contractSource'] and isinstance(item['checks'],list),item['id']
        assert item['proposedResolution'],item['id']
        for check in item['checks']:
            assert isinstance(check['sql'],str) and check['sql'].strip()
            assert 'result' in check
        all_items.append(dict(item,reviewer=report['reviewer']))
    provenance.append(dict(reviewer=report['reviewer'],reviewFile=p.name,reviewSha256=sha(p),
        items=len(items),decisions=dict(Counter(i['decision'] for i in items)),
        recordedQueryChecks=sum(len(i['checks']) for i in items),declaredProvenance=claimed))
assert len(all_items)==len({x['id'] for x in all_items})==100

# Previous conclusions become visible to this assembler only after all independent outputs validate.
prior=read(ROOT/'reports/calendar-window-20261119/formal-contract-audit.json')
old={x['id']:x for x in prior['items']}
reconciliation=[]
for item in sorted(all_items,key=lambda i:i['id']):
    was_conflict=bool(old[item['id']]['confirmedConflictKinds'])
    clear_conflict=item['decision'] in {'CONTRACT_CONFLICT','REFERENCE_DEFECT'}
    reconciliation.append(dict(id=item['id'],reviewer=item['reviewer'],decision=item['decision'],
        previousConfirmedConflict=was_conflict,nowExplicitConflict=clear_conflict,
        needsReconciliation=was_conflict!=clear_conflict,
        reason=item['reason'],proposedResolution=item['proposedResolution']))

current_source=subprocess.check_output(['git','rev-parse','HEAD:src'],cwd=ROOT,text=True).strip()
dispatch_source=subprocess.check_output(['git','rev-parse',dispatch['sourceCommit']+':src'],cwd=ROOT,text=True).strip()
assert current_source==dispatch_source,'Production changed during independent review'
summary=dict(format='fresh-agent-contract-review-v1',sourceCommit=dispatch['sourceCommit'],sourceGitTree=current_source,
    userAuthorization=dispatch['userAuthorization'],reviewerType='AI_AGENT',freshContexts=3,
    humanReviewClaimed=False,unseenBlindTestClaimed=False,reviewedOriginalFormalItems=100,
    providerEvaluationCalls=0,providerTokens=0,providerCostYuan=0,
    decisions=dict(Counter(i['decision'] for i in all_items)),reviewers=provenance,
    previousConfirmedConflicts=prior['confirmedConflictCount'],
    reconciliation=reconciliation,
    unresolvedDifferences=[i['id'] for i in reconciliation if i['needsReconciliation']],
    originalDataChanged=False,originalScoresChanged=False,formalGateChanged=False,stageAccepted=False,
    limits=['Fresh-context agents share a filesystem; provenance declarations do not enforce OS access isolation.',
            'Review improves reference credibility; original100 were already seen and do not become a blind test.',
            'Primary review classifications are preserved; disagreements require evidence, not majority vote.'])
out=FOLDER/'summary.json'
with out.open('x',encoding='utf-8',newline='\n') as f:
    json.dump(summary,f,ensure_ascii=False,indent=2);f.write('\n')
print(json.dumps({k:v for k,v in summary.items() if k not in {'reconciliation','limits','reviewers'}},ensure_ascii=False))
