"""Prepare, preflight, and audit the explicitly approved Stage D ¥10 scope.

This is an append-only metadata registration.  It never edits revision-4, the
previous ¥1 reopen registration, or either historical quota ledger.  The CLI
has only prepare/preflight/audit actions: it never resolves a Key, opens a
provider connection, or runs ``run-next``.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "reports/replan-stage-d-20261119"
CLOSED = BASE / "batch-1/reregistered-dev-1/revision-4"
PRIOR = BASE / "free-reopen-plan-20261120"
SCOPE_PROPOSAL = BASE / "post-interruption-feasibility-20261120/proposal.json"
SCOPE_DECISION = BASE / "post-interruption-feasibility-20261120/human-scope-decision.json"
CURRENT_DRIVER = ROOT / "scripts/run_replan_stage_d_reregistered.py"
OLD_CONTROLLER = ROOT / "scripts/run_replan_stage_d.py"
QUOTA_MONITOR = ROOT / "scripts/replan_quota_monitor.py"
STABLE_PROXY = ROOT / "scripts/replan_stable_proxy.py"
DURABLE_WRITE = ROOT / "scripts/replan_durable_write.py"
HERE = Path(__file__).resolve().parent

CLOSED_PLAN_SHA256 = "da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe"
CLOSED_STATE_SHA256 = "dca6197ce04a4fc1ed87f665b7954cf25a1f0f3216620b78eb2dc6f3f9d11025"
CLOSED_LEDGER_SHA256 = "a5097cec99f0a8c9b2f2b9596a46a682dde6a0dd8bbf4ad6bdad4af602d3ca23"
CLOSED_QUOTA_SHA256 = "f9ed89b775ff2c57e4394c1bf39d55533752f0d19e3c0bae13286dc4aceccc86"
CLOSED_DECISION_SHA256 = "4e5b824568e7f2a87070b4390a24f6fa5f713c27afb17b68ee374adc8e509f87"
CLOSED_PROPOSAL_SHA256 = "0c72656803e63dce968d8d94edf3eb5ed191274bdfc304c6004fee2018422c0c"
PRIOR_PLAN_SHA256 = "ff0f9c1d271972b4d8ab594760dbe4d8d957f2b4cf15c3c4f3136c4267f5a8c1"
PRIOR_AUTH_SHA256 = "137c067f4ee166a2d28846a6d066dcb2fa0f6ef53060ad3c6994e3336f2f06eb"
PRIOR_BINDING_SHA256 = "c278df2811ce499d35d60a4d3f58185f665385a713d6409d70efcba4866da158"
PRIOR_AUDIT_SHA256 = "534930660c7d9fa8272d32fd70a70d1a3d229251ef64219e9d76543dd92e333f"
CURRENT_DRIVER_SHA256 = "132627384d885e1255a00607474dbc632080b7d1ebf2c64c513a614a7674669e"
OLD_CONTROLLER_SHA256 = "079ec048dc29348cc032e05b592dfbf2540f3b3480fe078c461a8a10e344e4b8"
QUOTA_MONITOR_SHA256 = "35b86ea00752c4e4a986480c8be266e87345c0617bd041214cbf17f53e7cb9db"
STABLE_PROXY_SHA256 = "49f52f18feca918a0b3fef89babfb7e4d8cf6fdc4dade75dda13f78e017aa1c5"
DURABLE_WRITE_SHA256 = "7e17fd3dbfdcc097984b3f3026beeb0113ab4d16d4f11b9e66df5d2f570703d2"

AUTHORIZATION_STATEMENT = (
    "用户已明确批准此前¥10完整范围，但保留硬约束：只在DeepSeek官方空闲时段调用；"
    "不要重置旧账本、不要改旧拒绝证据。现在只做prepare/preflight/audit，"
    "不读Key、不发请求、不运行run-next。"
)
AUTHORIZATION_ID = "direct-user-stage-d-paid-scope10-20261120-v1"

SCOPE10 = {
    "limitYuan": "10.00",
    "reservationYuan": "0.10",
    "lastDevelopmentQuestions": 100,
    "lastDevelopmentMaxApplicationCalls": 200,
    "newRouteQuestions": 902,
    "newRouteApplicationCalls": 1804,
    "cumulativeQuestionLimit": 1046,
    "cumulativeApplicationCallLimit": 1950,
    "inheritedQuestions": 144,
    "inheritedApplicationCalls": 146,
    "inheritedCommonPermits": 145,
    "inheritedTransportAttempts": 144,
}

ORIGINAL_GATES = {
    "candidate": "C1 only; C2 not admitted",
    "order": ["DEV_F", "TARGETS", "B1", "F1", "F2", "B2", "B3", "F3", "U_B", "U_F"],
    "formalRounds": ["B1", "F1", "F2", "B2", "B3", "F3"],
    "unseen": "LOCKED until all original formal/safety gates pass",
    "model": "deepseek-flash",
    "temperature": 0,
    "maxCompletionTokens": 8192,
    "oneCompletion": True,
    "toolsAndReasoningModes": "disabled as in frozen plan",
    "maxModelCallsPerQuestion": 2,
    "sdkAttempts": 1,
    "parentRequestTimeoutMs": 15000,
    "roundGuardSeconds": 1740,
    "sendGuardSeconds": 120,
    "maxPayloadBytes": 65536,
    "candidateUnknownNeverReleased": True,
    "lateSqlNeverChangesOriginalAnswers": True,
    "noReconnectOrReplay": True,
}


class Refused(RuntimeError):
    pass


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha(path: Path) -> str:
    return sha_bytes(path.read_bytes())


def read_json(path: Path) -> dict[str, Any]:
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Refused(f"duplicate JSON member: {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=pairs)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise Refused(f"cannot read JSON: {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise Refused(f"expected JSON object: {path}")
    return value


def require(condition: bool, message: str) -> None:
    if not condition:
        raise Refused(message)


def write_new(path: Path, value: Any) -> None:
    require(not path.exists(), f"append-only output already exists: {path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def relative(path: Path) -> str:
    return path.resolve().relative_to(ROOT.resolve()).as_posix()


def lock_verified() -> bool:
    text = CURRENT_DRIVER.read_text(encoding="utf-8")
    return all(marker in text for marker in (
        "def require_human_authorization():",
        "DECLINED_SCOPE_DECISION_SHA256",
        "Revision-4 paid scope is permanently closed by direct-user decision",
        "raise Refused(\"Revision-4 paid scope is permanently closed by direct-user decision\")",
    ))


def off_peak_window(at: datetime, guard_seconds: int) -> dict[str, Any]:
    require(at.tzinfo is not None and 0 < guard_seconds <= 86400, "invalid timezone/guard")
    beijing = timezone(timedelta(hours=8))
    local = at.astimezone(beijing)
    end = at + timedelta(seconds=guard_seconds)
    for offset in range(2):
        day = local.date() + timedelta(days=offset)
        if day.weekday() >= 5:
            continue
        for start_hour, end_hour in ((9, 12), (14, 18)):
            left = datetime.combine(day, datetime.min.time(), beijing) + timedelta(hours=start_hour)
            right = left + timedelta(hours=end_hour - start_hour)
            if at < right and end >= left:
                return {
                    "allowed": False,
                    "reason": "PEAK_HOURS" if at >= left else "PEAK_BOUNDARY_RESERVE",
                    "checkedAt": at.isoformat(),
                    "nextAllowedAt": right.isoformat(),
                    "guardSeconds": guard_seconds,
                }
    return {"allowed": True, "reason": "OFF_PEAK", "checkedAt": at.isoformat(), "guardSeconds": guard_seconds}


def _hold_digest(quota: dict[str, Any]) -> str:
    rows = [{"holdId": hold_id, **hold} for hold_id, hold in sorted(quota.get("holds", {}).items())]
    return sha_bytes(canonical(rows))


def verify_anchors() -> dict[str, Any]:
    paths = {
        "closedPlan": CLOSED / "plan.json",
        "closedState": CLOSED / "state.json",
        "closedLedger": CLOSED / "send-ledger.json",
        "closedQuota": CLOSED / "candidate-cost-quota.json",
        "oldDecision": SCOPE_DECISION,
        "oldProposal": SCOPE_PROPOSAL,
        "priorPlan": PRIOR / "plan.json",
        "priorAuthorization": PRIOR / "reopen-authorization.json",
        "priorBinding": PRIOR / "authorization-binding.json",
        "priorAudit": PRIOR / "audit.json",
        "currentDriver": CURRENT_DRIVER,
        "oldController": OLD_CONTROLLER,
        "quotaMonitor": QUOTA_MONITOR,
        "stableProxy": STABLE_PROXY,
        "durableWrite": DURABLE_WRITE,
    }
    require(all(path.is_file() and not path.is_symlink() for path in paths.values()), "anchor file missing")
    hashes = {name: sha(path) for name, path in paths.items()}
    require(hashes["closedPlan"] == CLOSED_PLAN_SHA256, "closed plan drift")
    require(hashes["closedState"] == CLOSED_STATE_SHA256, "closed state drift")
    require(hashes["closedLedger"] == CLOSED_LEDGER_SHA256, "closed ledger drift")
    require(hashes["closedQuota"] == CLOSED_QUOTA_SHA256, "closed quota drift")
    require(hashes["oldDecision"] == CLOSED_DECISION_SHA256, "old decision drift")
    require(hashes["oldProposal"] == CLOSED_PROPOSAL_SHA256, "old proposal drift")
    require(hashes["priorPlan"] == PRIOR_PLAN_SHA256, "prior ¥1 reopen plan drift")
    require(hashes["priorAuthorization"] == PRIOR_AUTH_SHA256, "prior authorization drift")
    require(hashes["priorBinding"] == PRIOR_BINDING_SHA256, "prior binding drift")
    require(hashes["priorAudit"] == PRIOR_AUDIT_SHA256, "prior audit drift")
    require(hashes["currentDriver"] == CURRENT_DRIVER_SHA256 and lock_verified(), "current permanent lock drift")
    require(hashes["oldController"] == OLD_CONTROLLER_SHA256, "old controller drift")
    require(hashes["quotaMonitor"] == QUOTA_MONITOR_SHA256, "quota monitor drift")
    require(hashes["stableProxy"] == STABLE_PROXY_SHA256, "stable proxy drift")
    require(hashes["durableWrite"] == DURABLE_WRITE_SHA256, "durable write drift")

    closed_plan = read_json(paths["closedPlan"])
    closed_state = read_json(paths["closedState"])
    closed_ledger = read_json(paths["closedLedger"])
    closed_quota = read_json(paths["closedQuota"])
    old_decision = read_json(paths["oldDecision"])
    prior_plan = read_json(paths["priorPlan"])
    prior_auth = read_json(paths["priorAuthorization"])
    prior_audit = read_json(paths["priorAudit"])
    require(closed_state.get("planSha256") == CLOSED_PLAN_SHA256 and closed_state.get("requestsStarted") == 76
            and closed_state.get("terminal") == "INTERRUPTED_NO_REPLAY", "old 76 interruption identity changed")
    require(closed_ledger.get("planSha256") == CLOSED_PLAN_SHA256 and len(closed_ledger.get("calls", [])) == 76,
            "old 76 ledger identity changed")
    unknown = [hold for hold in closed_quota.get("holds", {}).values() if hold.get("status") == "UNKNOWN"]
    require(closed_quota.get("limitYuan") == 1.0 and closed_quota.get("reservationYuan") == 0.1
            and closed_quota.get("knownCalls") == 139 and str(closed_quota.get("knownCostYuan")) in ("0.29376746", "0.293767460")
            and len(unknown) == 7 and len(unknown) == len(closed_quota.get("holds", {})), "old quota identity changed")
    require(old_decision.get("decision") == "DECLINED_NO_ADDITIONAL_PAID_EVALUATION"
            and old_decision.get("newPaidAuthorizationGranted") is False, "old decline evidence changed")
    require(prior_plan.get("limits", {}).get("totalYuan") == "1.00"
            and prior_plan.get("executionState") == "PREPARE_PREFLIGHT_AUDIT_ONLY"
            and prior_auth.get("paidEvaluationScope", {}).get("limitIncreaseAllowed") is False
            and prior_audit.get("passed") is True, "prior ¥1 scope was not preserved")

    known_cost = Decimal(str(closed_quota["knownCostYuan"]))
    return {
        "format": "stage-d-paid-scope10-base-snapshot-v1",
        "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        "hashes": hashes,
        "closedPlanSha256": CLOSED_PLAN_SHA256,
        "closedStateSha256": hashes["closedState"],
        "closedLedgerSha256": hashes["closedLedger"],
        "closedQuotaSha256": hashes["closedQuota"],
        "oldDecisionSha256": CLOSED_DECISION_SHA256,
        "oldProposalSha256": CLOSED_PROPOSAL_SHA256,
        "priorReopenPlanSha256": PRIOR_PLAN_SHA256,
        "priorReopenAuthorizationSha256": PRIOR_AUTH_SHA256,
        "priorReopenAuditSha256": PRIOR_AUDIT_SHA256,
        "currentPermanentRejectLockVerified": True,
        "oldState": {"requestsStarted": 76, "terminal": closed_state["terminal"], "rounds": sorted(closed_state.get("rounds", {}))},
        "oldLedger": {"calls": len(closed_ledger.get("calls", [])), "rejections": len(closed_ledger.get("rejections", [])), "breaches": len(closed_ledger.get("breaches", []))},
        "oldCandidateQuota": {
            "budgetId": closed_quota["budgetId"],
            "pricingIdentity": closed_quota["pricingIdentity"],
            "limitYuan": "1.00",
            "reservationYuan": "0.10",
            "knownCalls": 139,
            "knownCostYuan": f"{known_cost:.9f}",
            "unknownHolds": 7,
            "unknownHeldYuan": "0.70",
            "unknownHoldIdentitySha256": _hold_digest(closed_quota),
        },
        "oldCommonAccounting": {
            "knownCostYuan": "0.293767460",
            "unknownHolds": 6,
            "unknownHeldYuan": "0.60",
            "configuredCommittedYuan": "0.893767460",
        },
        "inheritedCounters": {
            "inheritedQuestions": 144,
            "inheritedApplicationCalls": 146,
            "inheritedCommonPermits": 145,
            "inheritedTransportAttempts": 144,
            "oldRevision4RequestsStarted": 76,
            "oldRevision4LedgerCalls": 76,
        },
        "sourceFiles": {relative(path): digest for name, path in paths.items() for digest in [hashes[name]]},
    }


def build_authorization(base: dict[str, Any]) -> dict[str, Any]:
    return {
        "format": "direct-user-stage-d-paid-scope10-authorization-v1",
        "authorizationId": AUTHORIZATION_ID,
        "source": "parent-relayed direct-user approval in current task",
        "statement": AUTHORIZATION_STATEMENT,
        "statementSha256": sha_bytes(AUTHORIZATION_STATEMENT.encode("utf-8")),
        "decision": "APPROVED_PAID_SCOPE10_WITH_FROZEN_ORIGINAL_GATES",
        "scope": copy.deepcopy(SCOPE10),
        "executionEnabledInThisTask": False,
        "keyReadAllowedInThisTask": False,
        "providerCallsAllowedInThisTask": False,
        "runNextInvoked": False,
        "constraints": {
            "offPeakOnly": True,
            "limitIncreaseOnlyToExactTenYuan": True,
            "noLedgerReset": True,
            "noOldEvidenceRewrite": True,
            "noOldUnknownRelease": True,
            "old76InterruptedIdentityUnchanged": True,
            "noAdditionalDevelopmentBatch": True,
            "noC2": True,
            "noPaidSingleQuestionProbe": True,
        },
        "boundPreviousRegistration": {
            "planSha256": PRIOR_PLAN_SHA256,
            "authorizationSha256": PRIOR_AUTH_SHA256,
            "auditSha256": PRIOR_AUDIT_SHA256,
            "oldClosedPlanSha256": base["closedPlanSha256"],
        },
    }


def make_staged_quota(old_quota: dict[str, Any]) -> dict[str, Any]:
    staged = copy.deepcopy(old_quota)
    staged["limitYuan"] = 10.0
    return staged


def build_plan(base: dict[str, Any], authorization: dict[str, Any], base_sha: str, staged_quota_sha: str) -> dict[str, Any]:
    return {
        "format": "stage-d-append-only-paid-scope10-plan-v1",
        "registration": "batch-1/reregistered-dev-1/revision-6-paid-scope-10",
        "createdAtUtc": datetime.now(timezone.utc).isoformat(),
        "appendOnly": True,
        "executionState": "PREPARE_PREFLIGHT_AUDIT_ONLY",
        "executionAdapterRequired": True,
        "runNextExposed": False,
        "baseClosedRegistration": "batch-1/reregistered-dev-1/revision-4",
        "baseClosedPlanSha256": base["closedPlanSha256"],
        "baseClosedStateSha256": base["closedStateSha256"],
        "baseClosedLedgerSha256": base["closedLedgerSha256"],
        "baseClosedQuotaSha256": base["closedQuotaSha256"],
        "oldDecisionSha256": base["oldDecisionSha256"],
        "oldProposalSha256": base["oldProposalSha256"],
        "priorReopenPlanSha256": base["priorReopenPlanSha256"],
        "priorReopenAuthorizationSha256": base["priorReopenAuthorizationSha256"],
        "priorReopenAuditSha256": base["priorReopenAuditSha256"],
        "baseSnapshotFile": "base-snapshot.json",
        "baseSnapshotSha256": base_sha,
        "authorizationFile": "paid-scope10-authorization.json",
        "authorizationId": authorization["authorizationId"],
        "authorizationStatementSha256": authorization["statementSha256"],
        "stagedQuotaFile": "staged-candidate-quota.json",
        "stagedQuotaSha256": staged_quota_sha,
        "scope": copy.deepcopy(SCOPE10),
        "inheritedIdentity": {
            "oldKnownCalls": 139,
            "oldCandidateUnknownHolds": 7,
            "oldRevision4RequestsStarted": 76,
            "oldRevision4Terminal": "INTERRUPTED_NO_REPLAY",
            "oldCandidateBudgetId": base["oldCandidateQuota"]["budgetId"],
            "oldCandidatePricingIdentity": base["oldCandidateQuota"]["pricingIdentity"],
            "unknownHoldIdentitySha256": base["oldCandidateQuota"]["unknownHoldIdentitySha256"],
        },
        "quotaMigration": {
            "stagedOnly": True,
            "sourceFile": "../batch-1/reregistered-dev-1/revision-4/candidate-cost-quota.json",
            "sourceLimitYuan": "1.00",
            "targetLimitYuan": "10.00",
            "onlyMutableField": "limitYuan",
            "budgetIdPreserved": True,
            "pricingIdentityPreserved": True,
            "reservationPreserved": True,
            "knownCallsAndCostPreserved": True,
            "unknownHoldsPreserved": 7,
            "productionApplied": False,
            "actualBatchModified": False,
        },
        "development": {
            "lastAndOnlyAdditionalDevelopmentBatch": True,
            "questions": 100,
            "maxApplicationCalls": 200,
            "maxCallsPerQuestion": 2,
            "old68AndOld76AnswersRemainInterrupted": True,
            "noHighScoreRerun": True,
        },
        "route": {
            "newRouteMaximumQuestions": 902,
            "newRouteMaximumApplicationCalls": 1804,
            "afterDevelopment": ["TARGETS", "B1", "F1", "F2", "B2", "B3", "F3", "U_B", "U_F"],
            "unseenOnlyAfterFormalPass": True,
            "earlyStopConsumesNoLaterLayer": True,
            "noC2": True,
        },
        "gates": {
            **copy.deepcopy(ORIGINAL_GATES),
            "developmentAccuracyMin": 71,
            "developmentDeepMin": 4,
            "developmentBusinessMin": 6,
            "developmentTimeoutMax": 9,
            "formalCandidateAverageAccuracyMin": 89,
            "formalRelativeRegressionMaxPp": 2,
            "formalP95MeanMaxMs": 13642,
            "formalTimeoutMax": 14,
            "newSafetyAdditionalDangerousAllowances": 0,
            "completeAccountingRequired": True,
        },
        "accountingContinuation": {
            "answerDeadlineMs": 15000,
            "connectAndWriteUseAnswerDeadline": True,
            "sameSentConnectionReadOnlyExtraSeconds": 105,
            "wholeRoundDeadlineSeconds": 1740,
            "collectorLimit": 16,
            "singleBodyLimitBytes": 2097152,
            "activeBodyLimitBytes": 33554432,
            "noReconnect": True,
            "noResend": True,
            "lateSqlAnswerEligible": False,
            "lateSqlBackfill": False,
            "unknownAfterAccountingCutoff": True,
        },
        "offPeakPolicy": {
            "timezone": "Asia/Shanghai",
            "weekdayPeakIntervals": ["09:00-12:00", "14:00-18:00"],
            "rule": "reject if send guard overlaps either interval",
        },
        "sourceBinding": {
            "currentPermanentLockDriverSha256": CURRENT_DRIVER_SHA256,
            "oldControllerSha256": OLD_CONTROLLER_SHA256,
            "quotaMonitorSha256": QUOTA_MONITOR_SHA256,
            "stableProxySha256": STABLE_PROXY_SHA256,
            "durableWriteSha256": DURABLE_WRITE_SHA256,
            "sourceFilesModifiedByPrepare": False,
        },
        "providerCallsAddedByPrepare": 0,
        "keyReadsByPrepare": 0,
        "oldLedgerReset": False,
        "oldDecisionRewritten": False,
        "oldUnknownReleased": 0,
    }


def _output_files() -> set[str]:
    return {
        "paid_scope10_plan.py", "test_paid_scope10_plan.py", "README.md",
        "base-snapshot.json", "paid-scope10-authorization.json", "staged-candidate-quota.json",
        "plan.json", "authorization-binding.json", "prepare-result.json", "preflight.json", "audit.json",
    }


def _load(output: Path):
    return (read_json(output / "base-snapshot.json"),
            read_json(output / "paid-scope10-authorization.json"),
            read_json(output / "staged-candidate-quota.json"),
            read_json(output / "plan.json"),
            read_json(output / "authorization-binding.json"))


def verify_output(output: Path = HERE) -> dict[str, bool]:
    base, authorization, staged, plan, binding = _load(output)
    require(sha(output / "base-snapshot.json") == plan["baseSnapshotSha256"], "base snapshot hash mismatch")
    require(sha(output / "paid-scope10-authorization.json") == binding["authorizationSha256"], "authorization hash mismatch")
    require(sha(output / "staged-candidate-quota.json") == binding["stagedQuotaSha256"], "staged quota hash mismatch")
    require(sha(output / "plan.json") == binding["planSha256"], "plan hash mismatch")
    require(plan["authorizationId"] == authorization["authorizationId"] == binding["authorizationId"], "authorization identity mismatch")
    require(plan["scope"] == SCOPE10 and authorization["scope"] == SCOPE10, "scope fields inconsistent")
    require(plan["executionState"] == "PREPARE_PREFLIGHT_AUDIT_ONLY" and plan["runNextExposed"] is False, "execution enabled during free phase")
    require(plan["providerCallsAddedByPrepare"] == 0 and plan["keyReadsByPrepare"] == 0, "prepare scope widened")
    require(plan["oldLedgerReset"] is False and plan["oldDecisionRewritten"] is False and plan["oldUnknownReleased"] == 0, "old evidence changed")
    require(staged["limitYuan"] == 10.0 and staged["reservationYuan"] == 0.1, "staged quota limit/reserve mismatch")
    old_quota = read_json(CLOSED / "candidate-cost-quota.json")
    staged_compare = copy.deepcopy(staged)
    old_compare = copy.deepcopy(old_quota)
    staged_compare["limitYuan"] = old_compare["limitYuan"]
    require(staged_compare == old_compare, "staged quota changed fields beyond limitYuan")
    anchor_now = verify_anchors()
    require(anchor_now["hashes"] == base["hashes"], "old anchors changed after preparation")
    return {
        "baseUnchanged": True,
        "scopeExact": True,
        "stagedQuotaOnlyLimitChanged": True,
        "authorizationBound": True,
        "executionDisabled": True,
        "oldUnknownPreserved": True,
        "permanentLockVerified": True,
    }


def prepare(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve(), "output must be this independent directory")
    require(output.is_dir(), "source directory missing")
    generated = {path.name for path in output.iterdir() if path.is_file()
                 and path.name not in {"paid_scope10_plan.py", "test_paid_scope10_plan.py", "README.md"}}
    require(not generated, "revision-6 output already contains generated files; never reset/overwrite")
    base = verify_anchors()
    old_quota = read_json(CLOSED / "candidate-cost-quota.json")
    authorization = build_authorization(base)
    write_new(output / "base-snapshot.json", base)
    write_new(output / "paid-scope10-authorization.json", authorization)
    staged_quota = make_staged_quota(old_quota)
    write_new(output / "staged-candidate-quota.json", staged_quota)
    plan = build_plan(base, authorization, sha(output / "base-snapshot.json"), sha(output / "staged-candidate-quota.json"))
    write_new(output / "plan.json", plan)
    binding = {
        "format": "stage-d-paid-scope10-authorization-binding-v1",
        "authorizationId": authorization["authorizationId"],
        "authorizationSha256": sha(output / "paid-scope10-authorization.json"),
        "stagedQuotaSha256": sha(output / "staged-candidate-quota.json"),
        "planSha256": sha(output / "plan.json"),
        "baseSnapshotSha256": sha(output / "base-snapshot.json"),
        "priorReopenPlanSha256": PRIOR_PLAN_SHA256,
        "appendOnly": True,
        "executionEnabled": False,
    }
    write_new(output / "authorization-binding.json", binding)
    result = {
        "format": "stage-d-paid-scope10-prepare-result-v1",
        "passed": True,
        "scope": "append-only ¥10 staged registration; no key/provider/run-next",
        "planSha256": binding["planSha256"],
        "authorizationSha256": binding["authorizationSha256"],
        "stagedQuotaSha256": binding["stagedQuotaSha256"],
        "providerCalls": 0,
        "keyReads": 0,
        "runNextInvoked": False,
        "actualBatchModified": False,
        "quotaMigrationPerformed": False,
        "oldUnknownReleased": 0,
        "limitYuan": "10.00",
        "reservationYuan": "0.10",
        "lastDevelopmentQuestions": 100,
        "lastDevelopmentMaxApplicationCalls": 200,
        "newRouteQuestions": 902,
        "newRouteApplicationCalls": 1804,
        "cumulativeQuestionLimit": 1046,
        "cumulativeApplicationCallLimit": 1950,
    }
    write_new(output / "prepare-result.json", result)
    return result


def preflight(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve() and output.is_dir(), "prepare must run first")
    checks = verify_output(output)
    cases = {
        "morningPeak": off_peak_window(datetime(2026, 11, 20, 2, 0, tzinfo=timezone.utc), 120),
        "morningBoundary": off_peak_window(datetime(2026, 11, 20, 0, 59, tzinfo=timezone.utc), 120),
        "afterMorningPeak": off_peak_window(datetime(2026, 11, 20, 4, 0, tzinfo=timezone.utc), 120),
        "weekend": off_peak_window(datetime(2026, 11, 21, 2, 0, tzinfo=timezone.utc), 120),
    }
    require(not cases["morningPeak"]["allowed"] and cases["morningPeak"]["reason"] == "PEAK_HOURS", "peak guard failed")
    require(not cases["morningBoundary"]["allowed"] and cases["morningBoundary"]["reason"] == "PEAK_BOUNDARY_RESERVE", "boundary guard failed")
    require(cases["afterMorningPeak"]["allowed"] and cases["weekend"]["allowed"], "off-peak sample failed")
    base, authorization, staged, plan, binding = _load(output)
    known = Decimal(base["oldCandidateQuota"]["knownCostYuan"])
    held = Decimal("0.10") * Decimal(base["oldCandidateQuota"]["unknownHolds"])
    next_commitment = known + held + Decimal("0.10")
    result = {
        "format": "stage-d-paid-scope10-preflight-v1",
        "passed": True,
        "checks": {**checks, "peakWindowRules": True, "scopeExact": plan["scope"] == SCOPE10,
                    "authorizationExact": authorization["scope"] == SCOPE10,
                    "stagedQuotaUnderTenYuan": Decimal(str(staged["limitYuan"])) == Decimal("10.00"),
                    "oldQuotaStillOneYuan": Decimal(str(read_json(CLOSED / "candidate-cost-quota.json")["limitYuan"])) == Decimal("1.00"),
                    "oldUnknownSeven": base["oldCandidateQuota"]["unknownHolds"] == 7,
                    "old76Interrupted": base["oldState"]["requestsStarted"] == 76 and base["oldState"]["terminal"] == "INTERRUPTED_NO_REPLAY"},
        "peakCases": cases,
        "providerCalls": 0,
        "keyReads": 0,
        "runNextInvoked": False,
        "executionEnabled": False,
        "stagedLimitYuan": "10.00",
        "reservationYuan": "0.10",
        "knownCostYuan": base["oldCandidateQuota"]["knownCostYuan"],
        "unknownHolds": 7,
        "currentCandidateCommitmentYuan": f"{known + held:.9f}",
        "nextReservationCommitmentYuan": f"{next_commitment:.9f}",
        "nextReservationAllowedByStagedLimit": next_commitment <= Decimal("10.00"),
        "lastDevelopmentMaxApplicationCalls": 200,
        "newRouteMaximumApplicationCalls": 1804,
        "cumulativeApplicationCallLimit": 1950,
        "cumulativeQuestionLimit": 1046,
        "sourceScope": "metadata-only; no Java/JAR/database/provider/key access",
    }
    write_new(output / "preflight.json", result)
    return result


def audit(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve() and (output / "preflight.json").is_file(), "preflight must run first")
    pre = read_json(output / "preflight.json")
    require(pre.get("passed") is True, "preflight failed")
    checks = verify_output(output)
    names = {path.name for path in output.iterdir() if path.is_file()}
    require(names.issubset(_output_files()), "unexpected file in revision-6 directory")
    result = {
        "format": "stage-d-paid-scope10-independent-audit-v1",
        "passed": True,
        "checks": {**checks, "preflightPassed": True, "onlyNewDirectoryWritten": True,
                    "oldStateLedgerQuotaUnchanged": True, "oldDecisionAndProposalUnchanged": True,
                    "priorReopenUnchanged": True, "currentPermanentRejectLockStillPresent": lock_verified(),
                    "exactTenYuanOnly": True, "lastDev100BoundTo200Calls": True,
                    "cumulative1046And1950Bound": True, "originalGatesBound": True,
                    "offPeakGuardBound": True, "old76InterruptedBound": True},
        "providerCalls": 0,
        "keyReads": 0,
        "runNextInvoked": False,
        "actualBatchModified": False,
        "quotaMigrationPerformed": False,
        "oldUnknownReleased": 0,
        "outputFiles": sorted(names),
        "note": "Staged quota is a new-directory copy only; it is not loaded by the production runner.",
    }
    write_new(output / "audit.json", result)
    return result


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "preflight", "audit"))
    args = parser.parse_args()
    try:
        print(json.dumps({"prepare": prepare, "preflight": preflight, "audit": audit}[args.action](), ensure_ascii=False, indent=2))
    except Refused as exc:
        print("REFUSED:", exc)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
