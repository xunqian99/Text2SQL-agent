"""Independent source/metadata-only calculations. No execution-controller import."""
from __future__ import annotations
import csv,hashlib,json,subprocess,zipfile
from decimal import Decimal,ROUND_FLOOR
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent
AUDIT=ROOT/'reports/replan-stage-d-20261119/independent-review/reregistered-dev-1/revision-4/interruption-audit'
ROOT_DRAFT=ROOT/'reports/replan-stage-d-20261119/post-interruption-feasibility-20261120'
REV='e114648a963bf109a38ad232628f1df70915a6d4';D=Decimal;Q=D('.10')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_bytes())
def text(v):return format(v,'f')
def save(name,value):
    with (OUT/name).open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)
def scenario(layer,opening,n,c,u,limit):
    final=opening+(n-u)*c+u*Q
    worst=final+(Q-c if u<n else D(0))
    best=worst if u==0 else final
    return dict(layer=layer,openingCommitmentYuan=text(opening),calls=n,knownCostPerCallYuan=text(c),unknowns=u,limitYuan=text(limit),
        finalCommitmentYuan=text(final),bestOrderingAdmissionPeakYuan=text(best),worstOrderingAdmissionPeakYuan=text(worst),
        someOrderingPasses=best<=limit,everyOrderingPasses=worst<=limit,
        meaning='constant hypothetical known cost, single-layer capacity only; not forecast or payment authorization')
def main():
    metadata=read(AUDIT/'accounting-and-interruption.json');conclusion=read(AUDIT/'conclusion.json')
    assert metadata['current']['knownCostYuan']=='0.13064558' and metadata['cumulative']['knownCostYuan']=='0.29376746'
    assert conclusion['replayAllowed'] is False and conclusion['newUnknownUsageCalls']==4
    source_paths=[
        'src/main/java/com/text2sql/agent/cost/CostQuota.java',
        'src/main/java/com/text2sql/agent/generation/LlmSqlGenerator.java',
        'src/main/java/com/text2sql/agent/budget/RequestBudget.java',
        'src/main/java/com/text2sql/agent/budget/RequestBudgetRunner.java',
        'src/main/java/com/text2sql/agent/budget/BudgetHttpRequestFactory.java',
        'src/main/java/com/text2sql/agent/observability/LlmCallLog.java']
    java=[]
    for name in source_paths:
        original=subprocess.check_output(['git','cat-file','blob',REV+':'+name],cwd=ROOT)
        assert original==(ROOT/name).read_bytes()
        java.append(dict(path=name,sha256=sha(ROOT/name),frozenCommit=REV,gitOriginalBytesMatch=True))
    jars=[]
    for side,path,expected in (
        ('B','.private/recovery/p9-3c0bda0/text2sql-agent-p9.jar','a89dbd431263f0c9356510d71a5dd90fdcd6e0b3ab806ce9e3fdd16cf1121a8b'),
        ('F','.private/recovery/replan-c1-20261119/text2sql-agent-C1-submission-1.jar','51874452c5c0acad14a733fc6d0fbec06c81273aaabf7b16eabc3b4b4653c2c2')):
        jar=ROOT/path;assert sha(jar)==expected
        with zipfile.ZipFile(jar) as z:
            cost=[n for n in z.namelist() if '/cost/CostQuota' in n]
            named=[n for n in z.namelist() if 'BudgetedModelCall' in n]
        assert bool(cost)==(side=='F') and not named
        jars.append(dict(side=side,path=path,sha256=expected,costQuotaClassEntries=cost,budgetedModelCallClassEntries=named))
    common=D(metadata['cumulative']['commonConfiguredCommitmentYuan']);candidate=D(metadata['cumulative']['candidateConfiguredCommitmentYuan'])
    assert (common,candidate)==(D('.89376746'),D('.99376746'))
    layers=(('candidate-dev-one-call-per-question',candidate,100),('candidate-dev-call-maximum',candidate,200),
        ('candidate-F-route-one-call-per-question',candidate,502),('candidate-F-route-call-maximum',candidate,1004),
        ('common-B-and-F-route-one-call-per-question',common,902),('common-B-and-F-route-call-maximum',common,1804))
    scenarios=[scenario(layer,opening,n,c,u,limit) for layer,opening,n in layers for limit in (D(5),D(10))
        for c in (D('.002'),D('.005'),D('.010')) for u in (0,10,20,40,n)]
    assert len(scenarios)==180
    root=read(ROOT_DRAFT/'check-2/feasibility.json');assert root['format']=='post-interruption-offline-feasibility-v2'
    root_source=read(ROOT_DRAFT/'check-2/source-manifest.json')
    key=(ROOT_DRAFT/'audit.py').relative_to(ROOT).as_posix();assert root_source[key]==sha(ROOT_DRAFT/'audit.py')
    root_checks=0
    for s in root['scenarios']:
        actual=scenario(s['layer'],D(s['openingCommitmentYuan']),s['futureCalls'],D(s['syntheticKnownCostPerCallYuan']),s['syntheticUnknownCalls'],D(10))
        assert D(s['finalConfiguredCommitmentYuan'])==D(actual['finalCommitmentYuan'])
        assert D(s['bestOrderPreSendPeakYuan'])==D(actual['bestOrderingAdmissionPeakYuan'])
        assert D(s['worstOrderPreSendPeakYuan'])==D(actual['worstOrderingAdmissionPeakYuan'])
        assert s['allCallsAdmissibleForAnyOrdering']==actual['everyOrderingPasses'] and s['allCallsAdmissibleForSomeOrdering']==actual['someOrderingPasses']
        root_checks+=1
    assert root_checks==60 and root['futureCandidateFApplicationModelCallMaximum']==1004 and root['futureBaselineBApplicationModelCallMaximum']==800
    assert (root['conditionalCumulativeQuestions'],root['conditionalCumulativeApplicationModelCalls'])==(1046,1950)
    capacity=[]
    for layer,opening in (('common',common),('candidate-F',candidate)):
        for limit in (D(5),D(10)):
            entry=dict(layer=layer,openingCommitmentYuan=text(opening),limitYuan=text(limit),allUnknownCallCapacity=int(((limit-opening)/Q).to_integral_value(rounding=ROUND_FLOOR)),allKnownCallCapacity={})
            for c in (D('.002'),D('.005'),D('.010')):
                count=int(((limit-opening-Q)/c).to_integral_value(rounding=ROUND_FLOOR))+1
                entry['allKnownCallCapacity'][text(c)]=count
                assert opening+(count-1)*c+Q<=limit and opening+count*c+Q>limit
            capacity.append(entry)
    route=dict(historicalQuestions=68+76,historicalApplicationCalls=70+76,futureDevQuestions=100,futureTargetsQuestions=2,futureFormalQuestions=600,futureUnseenQuestions=200,
        futureQuestions=902,futureFQuestions=502,futureBQuestions=400,futureMaximumApplicationCalls=1804,futureFMaximumCalls=1004,futureBMaximumCalls=800,
        cumulativeQuestions=1046,cumulativeMaximumApplicationCalls=1950,overCurrent970QuestionLimit=76,overOriginal1002GlobalQuestionLimit=44,overCurrent1940CallLimit=10,underOriginal2004GlobalCallLimit=54,
        historicalCommonPermits=69+76,historicalTransportAttempts=68+76,historicalApplicationCallsNotInterchangeableWithPermits=True,
        futureNewCommonCapIfCurrent76LedgerContinues=76+1804,originalLegacy70ApplicationCallsMustStillBeCharged=True,
        routeOnlyHypothetical=True,newEvaluationAuthorized=False)
    boundaries=dict(
        exactCommonAllUnknownYuan=text(common+1804*Q),exactCandidateAllUnknownYuan=text(candidate+1004*Q),
        exactIntegerConfigurationEnvelope=182,configurationEnvelopeIsNotProviderHardBillingCap=True,
        commonTenYuanZeroUnknownFiveMilliCost=scenario('common',common,1804,D('.005'),0,D(10)),
        commonTenYuanZeroUnknownOneCentOneCallPerQuestion=scenario('common',common,902,D('.010'),0,D(10)),
        oldCandidateAll1804FiveYuanFourUnknownWasOverestimate=scenario('overestimate-only-not-actual-F-layer',candidate,1804,D('.002'),4,D(5)))
    assert boundaries['commonTenYuanZeroUnknownFiveMilliCost']['worstOrderingAdmissionPeakYuan']=='10.00876746'
    assert boundaries['exactCommonAllUnknownYuan']=='181.29376746' and boundaries['exactCandidateAllUnknownYuan']=='101.39376746'
    save('sensitivity.json',dict(format='independent-post-interruption-sensitivity-v1',metadataSources=[(AUDIT/n).relative_to(ROOT).as_posix() for n in ('accounting-and-interruption.json','conclusion.json')],
        reservationYuan='.10',route=route,scenarios=scenarios,capacities=capacity,boundaries=boundaries,old4UnknownsReleased=False,newEvaluationAuthorized=False))
    with (OUT/'sensitivity.csv').open('x',encoding='utf-8-sig',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=list(scenarios[0]));writer.writeheader();writer.writerows(scenarios)
    scripts=['scripts/replan_stage_d_proxy.py','scripts/replan_quota_monitor.py','scripts/run_replan_stage_d.py','scripts/run_replan_stage_d_reregistered.py']
    sources={name:sha(ROOT/name) for name in source_paths+scripts}
    for p in (AUDIT/'accounting-and-interruption.json',AUDIT/'conclusion.json',ROOT_DRAFT/'audit.py',ROOT_DRAFT/'check-2/feasibility.json',ROOT_DRAFT/'check-2/source-manifest.json'):
        sources[p.relative_to(ROOT).as_posix()]=sha(p)
    save('source-and-draft-review.json',dict(format='independent-post-interruption-source-review-v1',sources=sources,frozenJava=java,jars=jars,
        noBudgetedModelCallClass=True,actualWrapper='LlmSqlGenerator.invoke / CostQuota.Lease / RequestBudget.startModelCall',
        rootCheck2SixtyScenariosIndependentlyMatched=True,rootCheck1SupersededConservativeOverestimate=True,
        sourceReviewDoesNotAuthorizeNewEvaluation=True,actualBatchReadOrWrittenByThisScript=False,old68AnswersOrSqlParsed=False,unseenRead=False,providerCalls=0,keyReads=0))
    print(json.dumps(dict(javaOriginalsMatched=len(java),independentScenarios=180,rootCheck2ScenariosMatched=60,route=route,boundaries=boundaries)))
if __name__=='__main__':main()
