"""Read-only review of synthetic POC metadata; imports no execution code."""
from __future__ import annotations

import ast
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
DRAFT = ROOT / 'reports/replan-stage-d-20261119/post-interruption-feasibility-20261120'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_bytes())


def main():
    source = DRAFT / 'loopback_accounting_probe.py'
    ast.parse(source.read_text(encoding='utf-8'))
    report_path = DRAFT / 'loopback-check-3/result.json'
    report = read(report_path)
    historical_path = DRAFT / 'loopback-check-2/result.json'
    historical = read(historical_path)
    sources = read(OUT / 'source-and-draft-review.json')['sources']
    production = ('scripts/replan_stage_d_proxy.py', 'scripts/run_replan_stage_d.py', 'scripts/replan_quota_monitor.py')
    assert all(sha(ROOT / name) == sources[name] for name in production)
    assert report['sourceSha256'] == sha(source)
    assert report['frozenTransportSha256'] == sha(ROOT / production[0])
    assert report['passed'] is True and len(report['checks']) == 9 and all(report['checks'].values())
    cases = {row['case']: row for row in report['cases']}
    assert len(cases) == 8
    assert all(row['stubRequests'] <= 1 and row['callbacks'] == 1 for row in cases.values())
    old = cases['frozen-close-at-answer-cutoff']
    late = cases['late-usage-after-caller-disconnect']
    waiting = cases['late-sql-suppressed-for-waiting-client']
    bounded = cases['accounting-deadline-still-bounded']
    expired = cases['expired-send-permit-never-contacts-stub']
    missing = cases['missing-usage-stays-unknown']
    contradictory = cases['contradictory-usage-stays-unknown']
    assert not old['completeUsageCollected'] and late['completeUsageCollected']
    assert late['callerError'] == 'TimeoutError' and late['responseBytes'] == 228
    assert waiting['callerStatus'] == 504 and not waiting['callerReceivedSyntheticSql']
    assert waiting['callerElapsedMs'] > report['scaledAnswerSeconds'] * 1000
    assert not bounded['completeUsageCollected'] and not bounded['responseComplete']
    assert expired['stubRequests'] == 0 and not expired['upstreamAttempted']
    assert missing['responseComplete'] and not missing['completeUsageCollected']
    assert contradictory['responseComplete'] and not contradictory['completeUsageCollected']
    for name in ('realJavaHttpIntegrationTested', 'realFifteenSecondDeadlineTested',
                 'productionMonitorAndQuotaIntegrationTested', 'officialTlsAndPeakBoundaryTested', 'productionAdmission'):
        assert report[name] is False
    for name in ('realProviderCalls', 'realKeysRead', 'candidateUnknownHoldsReleased', 'frozenArtifactsChanged'):
        assert report[name] == 0
    proposal_path = DRAFT / 'proposal.json'
    proposal = read(proposal_path)
    proposal_doc = ROOT / proposal['document']
    assert sha(proposal_path) == '0c72656803e63dce968d8d94edf3eb5ed191274bdfc304c6004fee2018422c0c'
    assert sha(proposal_doc) == proposal['documentSha256']
    assert all(proposal[field] is False for field in ('paidAuthorized', 'engineeringAccepted', 'canExecute'))
    limits = proposal['proposedLimits']
    assert (limits['newQuestions'], limits['newApplicationCalls'], limits['cumulativeQuestions'], limits['cumulativeApplicationCalls']) == (902, 1804, 1046, 1950)
    assert (limits['inheritedQuestions'], limits['inheritedApplicationCalls'], limits['inheritedCommonPermits'], limits['inheritedTransportAttempts']) == (144, 146, 145, 144)
    assert (limits['cumulativeConfiguredYuan'], limits['reservationYuan']) == ('10.00', '0.10')
    assert (limits['parentRequestMs'], limits['modelCallsPerQuestion'], limits['sdkAttempts']) == (15000, 2, 1)
    assert (limits['extraReadOnlyAccountingSeconds'], limits['hardWholeRoundSeconds']) == (105, 1740)
    assert (limits['maxCollectors'], limits['maxBodyBytes'], limits['maxTotalActiveBodyBytes']) == (16, 2097152, 33554432)
    assert all(proposal['retained'].values())
    assert proposal['migration']['candidateUnknownHoldsPreserved'] == 7
    assert proposal['migration']['commonUnknownHoldsPreserved'] == 6
    output = dict(
        format='independent-loopback-concept-source-and-metadata-review-v1',
        reviewMethod='Read source and existing synthetic result only; did not rerun POC or invoke production monitor',
        sourceHashes={str(path.relative_to(ROOT)).replace('\\', '/'): sha(path)
                      for path in (source, report_path, historical_path, proposal_path, proposal_doc, *(ROOT / name for name in production))},
        currentSourceMatchesCheck3=True, check2HistoricalSourceHash=historical['sourceSha256'],
        check2HistoricalSourceIsNotCurrentSource=historical['sourceSha256'] != sha(source),
        historicalSourceDriftCaughtBeforeReviewAndResolvedByNewCheck3=True,
        suppliedScenarios=8, suppliedAssertions=9, suppliedAssertionsTrue=9,
        suppliedRunWallMs=report['wallMs'], independentNetworkReruns=0,
        scopedAcceptance='Conceptual late accounting collection feasibility only',
        productionAccepted=False, newPaidEvaluationAuthorized=False,
        observed=dict(
            scaledAnswerMs=report['scaledAnswerSeconds'] * 1000,
            waitingClient504ElapsedMs=waiting['callerElapsedMs'],
            waitingClientAnswerDeadlinePreservationProven=False,
            lateResponseBytes=late['responseBytes'],
            expiredPermitPosts=expired['stubRequests'],
            oldTransportCollectsLateUsage=old['completeUsageCollected'],
            extendedTransportCollectsLateUsage=late['completeUsageCollected'],
            collectionCutoffStillUnknown=True, missingUsageStillUnknown=True, contradictoryUsageStillUnknown=True),
        blockers=[
            dict(id='P0-send-deadline',
                 detail='Override returns accounting deadline to inherited connect/TLS/sendall socket timeouts; pre-send checks do not bound blocking writes to original answer deadline.'),
            dict(id='P0-answer-timing',
                 detail='Waiting client receives 504 after 553.744 ms, beyond 250 ms answer window; late SQL suppression alone does not preserve the business deadline.'),
            dict(id='P0-integration-provenance',
                 detail='Minimal callbacks do not exercise Java CostQuota, RequestBudget, durable common events, monitor A/B snapshots, permit fences, or concurrent next-question completion.'),
            dict(id='P0-accounting-validity',
                 detail='Check3 adds five integer fields and p+c=t/h+m=p, but does not execute real SendGate.after estimate/cost guards, events or persistence.'),
            dict(id='P0-round-and-peak',
                 detail='Current per-question 1620 s check excludes final drain; all collection, shutdown, callbacks and strict off-peak boundary need explicit end-to-end bounds.'),
            dict(id='P0-resource-lifecycle',
                 detail='Bound collector count, response bytes, stale sockets, shutdown/crash behavior, callback exactly-once persistence and all accepted-thread joins before admission.')],
        sourceLevelMonitorCompatibility=dict(
            commonLateKnownWithUnchangedCandidateUnknown='Conditionally compatible by source inspection; not integration tested',
            originalIdentityFields=['permit.token', 'permitFences', 'requestFence', 'hold.requestId', 'hold.callNumber', 'hold.promptSha256'],
            observedUnknownsMustRemainExact=True,
            finalCommonCompletionWrittenOnce=True,
            historicalEmptyCompletionMayBeOverwritten=False,
            commonKnownDoesNotReleaseCandidateHold=True),
        proposalReview=dict(scopeConcrete=True, totalsIndependentlyMatch=True,
                            proposed1740SecondsIsNewRequiredHardBoundNotExistingGuarantee=True,
                            tenYuanDoesNotGuaranteeCompletion=True,
                            onlyLimitFieldMayChangeInDerivedCandidateSeed=True,
                            scopeReviewIsNotEngineeringAcceptanceOrHumanAuthorization=True,
                            noRequiredScopeExpansionOrGateRelaxationFound=True),
        separateRealJavaProbeNotReviewedHere=True,
        noActualBatchReadOrWritten=True, old68AnswersOrSqlParsed=False,
        unseenRead=False, providerCallsAdded=0, keyReadsAdded=0,
        productionMonitorInvoked=False, oldUnknownsReleased=False)
    with (OUT / 'loopback-concept-review.json').open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(output, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps(dict(sourceMatches=True, suppliedScenarios=8, suppliedAssertions=9,
                          independentNetworkReruns=0, productionAccepted=False,
                          newPaidEvaluationAuthorized=False)))


if __name__ == '__main__':
    main()
