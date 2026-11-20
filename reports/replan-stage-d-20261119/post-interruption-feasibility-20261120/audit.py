"""Offline metadata audit. Never imports execution code or reads credentials.

Writes a new report directory only after validating immutable source bytes.
No evaluation datasets or HTTP answer files are opened. Embedded provider SQL
in the common ledger is never inspected or scored.
"""
import argparse
import hashlib
import json
import time
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
BATCH = ROOT / "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4"
EXEC = ROOT / "reports/replan-stage-d-20261119/reregistration-candidate/authorized-execution-20261120"
PLAN_SHA = "da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe"
RESERVE = Decimal(".10")
PROPOSED_LIMIT = Decimal("10.00")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def read(path):
    return json.loads(path.read_bytes(), parse_float=Decimal)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def text_decimal(value):
    return format(value, "f")


def run(output):
    started = time.perf_counter()
    require(output.parent == HERE and not output.exists(), "Output must be a new immediate child of audit directory")
    manifest = read(EXEC / "interrupted-batch-manifest.json")
    require(len(manifest["files"]) == manifest["fileCount"] == 768, "Actual-file denominator drift")
    actual = {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in BATCH.rglob("*") if p.is_file()}
    require(actual == manifest["files"], "Interrupted actual bytes or path set changed")
    require(digest((BATCH / "plan.json").read_bytes()) == PLAN_SHA, "Frozen plan changed")
    plan = read(BATCH / "plan.json")
    bound = plan["files"]
    require(len(bound) == 1140, "Bound-file denominator drift")
    for relative, expected in bound.items():
        require(digest((ROOT / relative).read_bytes()) == expected, "Bound source changed: " + relative)
    state = read(BATCH / "state.json")
    quota = read(BATCH / "candidate-cost-quota.json")
    initial = read(BATCH / "candidate-quota-initial-snapshot.json")
    ledger = read(BATCH / "send-ledger.json")
    metrics = read(EXEC / "interruption-metrics.json")
    require(state["terminal"] == "INTERRUPTED_NO_REPLAY" and state["requestsStarted"] == 76,
            "Terminal is no longer sealed")
    require(plan["quotaObservationPolicy"]["unknownNeverReleased"] is True, "UNKNOWN policy changed")
    require(quota["reservationYuan"] == RESERVE and quota["limitYuan"] == Decimal("1.00"), "Live budget drift")
    for field in ("version", "budgetId", "pricingIdentity", "reservationYuan", "reservationBreached"):
        require(quota[field] == initial[field], "Inherited identity changed: " + field)
    require(len(initial["holds"]) == 3 and all(quota["holds"].get(k) == v for k, v in initial["holds"].items()),
            "Old candidate UNKNOWN identity/content changed")
    require(len(quota["holds"]) == 7 and all(h["status"] == "UNKNOWN" for h in quota["holds"].values()),
            "Candidate UNKNOWN count/status drift")
    require(len(ledger["calls"]) == 76, "Current send count drift")
    unknown = [c for c in ledger["calls"] if c.get("usageKnown") is False]
    known = [c for c in ledger["calls"] if c.get("usageKnown") is True]
    require(len(unknown) == 4 and len(known) == 72, "Known/UNKNOWN count drift")
    identity_fields = ("token", "questionId", "requestBodySha256", "requestBytes", "admittedAt")
    unknown_evidence = []
    for call in unknown:
        result = call["result"]
        raw = BATCH / "send-ledger-events" / result["responseBodyFile"]
        require(raw.read_bytes() == b"" and result["responseBytes"] == 0 and result["responseComplete"] is False,
                "New provider body is no longer empty/incomplete")
        require(result["responseJson"] is None and call["costYuan"] is None and call.get("usage") is None,
                "UNKNOWN silently given cost/usage")
        unknown_evidence.append({**{k: call[k] for k in identity_fields},
            "httpStatus": result["httpStatus"], "error": result["error"], "responseBytes": 0,
            "responseBodySha256": result["responseBodySha256"], "completeUsageRecoverableFromSavedBody": False})
    new_known = sum((Decimal(c["costYuan"]) for c in known), Decimal(0))
    require(new_known == Decimal(".13064558"), "Known-cost recomputation differs")
    require(quota["knownCalls"] == 139 and quota["knownCostYuan"] == initial["knownCostYuan"] + new_known,
            "Candidate known high-water mismatch")
    candidate_commitment = quota["knownCostYuan"] + RESERVE * len(quota["holds"])
    common_commitment = Decimal(plan["legacy"]["commonCommittedYuan"]) + new_known + RESERVE * len(unknown)
    require(candidate_commitment == Decimal(metrics["candidateConfiguredCommitmentYuan"]), "Candidate total mismatch")
    require(common_commitment == Decimal(metrics["commonConfiguredCommitmentYuan"]), "Common total mismatch")
    prior_questions = plan["legacy"]["requestsStarted"] + state["requestsStarted"]
    prior_app_calls = plan["legacy"]["applicationModelCalls"] + metrics["applicationBudgetModelCalls"]
    future_questions = 100 + 2 + 600 + 200
    future_calls = 2 * future_questions
    require((prior_questions, prior_app_calls, future_questions, future_calls) == (144, 146, 902, 1804), "Route count mismatch")
    scenarios = []
    candidate_future_calls = 2 * (100 + 2 + 300 + 100)
    for layer, opening, calls in (("candidate-dev-one-call-per-question", candidate_commitment, 100),
            ("candidate-dev-call-maximum", candidate_commitment, 200),
            ("candidate-F-route-call-maximum", candidate_commitment, candidate_future_calls),
            ("common-B-and-F-route-call-maximum", common_commitment, future_calls)):
        for known_cost in (Decimal(".002"), Decimal(".005"), Decimal(".010")):
            for unknowns in sorted({0, min(10, calls), min(20, calls), min(40, calls), calls}):
                committed = opening + (calls - unknowns) * known_cost + unknowns * RESERVE
                # Final settled commitment alone is insufficient: the last known
                # call must first reserve .10. Put all UNKNOWNs first for the
                # largest possible pre-send commitment in this synthetic case.
                worst_peak = committed + (RESERVE-known_cost if calls > unknowns else Decimal(0))
                best_peak = worst_peak if unknowns == 0 else committed
                scenarios.append(dict(layer=layer, openingCommitmentYuan=text_decimal(opening), futureCalls=calls,
                    syntheticKnownCostPerCallYuan=text_decimal(known_cost),
                    syntheticUnknownCalls=unknowns, finalConfiguredCommitmentYuan=text_decimal(committed),
                    finalCommitmentWithinProposedTenYuan=committed <= PROPOSED_LIMIT,
                    worstOrderPreSendPeakYuan=text_decimal(worst_peak), bestOrderPreSendPeakYuan=text_decimal(best_peak),
                    allCallsAdmissibleForAnyOrdering=worst_peak <= PROPOSED_LIMIT,
                    allCallsAdmissibleForSomeOrdering=best_peak <= PROPOSED_LIMIT,
                    meaning="hypothetical capacity calculation, not forecast, not admission evidence"))
    source_paths = [BATCH / n for n in ("plan.json", "state.json", "candidate-cost-quota.json",
        "candidate-quota-initial-snapshot.json", "send-ledger.json")]
    source_paths += [EXEC / n for n in ("interrupted-batch-manifest.json", "interruption-metrics.json")]
    source_paths += [Path(__file__).resolve()]
    source_paths += [ROOT / n for n in ("src/main/java/com/text2sql/agent/cost/CostQuota.java",
        "src/main/java/com/text2sql/agent/generation/LlmSqlGenerator.java",
        "src/main/java/com/text2sql/agent/budget/RequestBudget.java",
        "src/main/java/com/text2sql/agent/budget/RequestBudgetRunner.java",
        "src/main/java/com/text2sql/agent/budget/BudgetHttpRequestFactory.java",
        "scripts/replan_stage_d_proxy.py", "scripts/replan_quota_monitor.py", "scripts/run_replan_stage_d.py",
        "scripts/run_replan_stage_d_reregistered.py")]
    source = {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in source_paths}
    report = dict(format="post-interruption-offline-feasibility-v2", passed=True,
        actualBatchFilesVerified=len(actual), planBoundFilesVerified=len(bound),
        priorQuestions=prior_questions, priorApplicationModelCalls=prior_app_calls,
        futureQuestions=future_questions, futureApplicationModelCallMaximum=future_calls,
        futureCandidateFApplicationModelCallMaximum=candidate_future_calls,
        futureBaselineBApplicationModelCallMaximum=future_calls-candidate_future_calls,
        conditionalCumulativeQuestions=prior_questions+future_questions,
        conditionalCumulativeApplicationModelCalls=prior_app_calls+future_calls,
        currentCommonCommitmentYuan=text_decimal(common_commitment),
        currentCandidateCommitmentYuan=text_decimal(candidate_commitment),
        currentNextReservationWouldBeYuan=text_decimal(candidate_commitment+RESERVE),
        proposedLimitYuan=text_decimal(PROPOSED_LIMIT), proposedLimitIsAuthorization=False,
        worstCaseCommonAllFutureReservationsPersistYuan=text_decimal(common_commitment+future_calls*RESERVE),
        worstCaseCandidateAllFutureReservationsPersistYuan=text_decimal(candidate_commitment+candidate_future_calls*RESERVE),
        unknownEvidence=unknown_evidence, scenarios=scenarios,
        currentTerminalPreserved=True, completeAccuracyAndP95Available=False,
        unknownSettlementPermittedByFrozenPolicy=False, providerCallsAdded=0, credentialsRead=0,
        noLiveLedgerWrites=True, noEvaluationDatasetsOrHttpAnswersOpened=True, generatedSqlNeverInspectedByAudit=True,
        auditWallMs=round((time.perf_counter()-started)*1000, 3))
    # Recheck exact live bytes after analysis. Only separate new artifacts may be written below.
    require(actual == {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in BATCH.rglob("*") if p.is_file()},
            "Actual batch changed during audit")
    for relative, expected in source.items():
        require(digest((ROOT/relative).read_bytes()) == expected, "Source changed during audit")
    output.mkdir()
    for name, data in (("feasibility.json", report), ("source-manifest.json", source)):
        with (output/name).open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    print(json.dumps({k: v for k, v in report.items() if k not in ("unknownEvidence", "scenarios")}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    run(parser.parse_args().output.resolve())
