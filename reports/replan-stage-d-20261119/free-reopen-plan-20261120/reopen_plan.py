"""Prepare, preflight, and audit an append-only Stage D reopen registration.

The module is intentionally metadata-only.  It does not import the production
runner, resolve a key, open a socket, contact DeepSeek, alter the closed
revision-4 directory, or offer a run-next action.  A future execution adapter
would have to consume the sealed files after an independent review.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "reports/replan-stage-d-20261119"
CLOSED = BASE / "batch-1/reregistered-dev-1/revision-4"
SCOPE_DECISION = BASE / "post-interruption-feasibility-20261120/human-scope-decision.json"
SCOPE_PROPOSAL = BASE / "post-interruption-feasibility-20261120/proposal.json"
CURRENT_DRIVER = ROOT / "scripts/run_replan_stage_d_reregistered.py"
OLD_DRIVER = ROOT / "scripts/run_replan_stage_d.py"
QUOTA_MONITOR = ROOT / "scripts/replan_quota_monitor.py"
STABLE_PROXY = ROOT / "scripts/replan_stable_proxy.py"
DURABLE_WRITE = ROOT / "scripts/replan_durable_write.py"
HERE = Path(__file__).resolve().parent

CLOSED_PLAN_SHA256 = "da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe"
CLOSED_DECISION_SHA256 = "4e5b824568e7f2a87070b4390a24f6fa5f713c27afb17b68ee374adc8e509f87"
CLOSED_PROPOSAL_SHA256 = "0c72656803e63dce968d8d94edf3eb5ed191274bdfc304c6004fee2018422c0c"
CLOSED_PLAN_DRIVER_SHA256 = "bda005614aa90c7f872f9451d0654a958204b2d4f5f807038d999245892d1d4e"
CURRENT_DRIVER_SHA256 = "132627384d885e1255a00607474dbc632080b7d1ebf2c64c513a614a7674669e"
OLD_DRIVER_SHA256 = "079ec048dc29348cc032e05b592dfbf2540f3b3480fe078c461a8a10e344e4b8"
QUOTA_MONITOR_SHA256 = "35b86ea00752c4e4a986480c8be266e87345c0617bd041214cbf17f53e7cb9db"
STABLE_PROXY_SHA256 = "49f52f18feca918a0b3fef89babfb7e4d8cf6fdc4dade75dda13f78e017aa1c5"
DURABLE_WRITE_SHA256 = "7e17fd3dbfdcc097984b3f3026beeb0113ab4d16d4f11b9e66df5d2f570703d2"

AUTHORIZATION_STATEMENT = (
    "用户现已明确重新允许付费评测，但保留硬约束：只在 DeepSeek 官方空闲时段调用；"
    "不要提额、不要重置旧账本、不要改旧拒绝证据。只做 prepare/preflight/audit，"
    "不读Key、不发请求、不运行run-next。"
)
AUTHORIZATION_ID = "direct-user-stage-d-reopen-20261120-frozen-cap-v1"

EXPECTED_LIMITS = {
    "requests": 902,
    "calls": 1870,
    "cumulativeRequests": 970,
    "cumulativeCalls": 1940,
    "legacyRequestsCharged": 68,
    "legacyApplicationCallsCharged": 70,
    "selectedNewRouteMaximumCalls": 1804,
    "globalPlanRequests": 1002,
    "globalPlanCalls": 2004,
    "callsPerRequest": 2,
    "requestMs": 15000,
    "sdkAttempts": 1,
    "roundGuardSeconds": 1740,
    "sendGuardSeconds": 120,
    "totalYuan": "1.00",
    "reservationYuan": "0.10",
    "maxPayloadBytes": 65536,
    "maxCompletionTokens": 8192,
    "inputFramingEstimate": 1024,
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


def write_new(path: Path, value: Any) -> None:
    if path.exists():
        raise Refused(f"append-only output already exists: {path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise Refused(message)


def relative(path: Path) -> str:
    return path.resolve().relative_to(ROOT.resolve()).as_posix()


def current_driver_lock_verified() -> bool:
    text = CURRENT_DRIVER.read_text(encoding="utf-8")
    markers = (
        "def require_human_authorization():",
        "Revision-4 paid scope is permanently closed by direct-user decision",
        "DECLINED_SCOPE_DECISION_SHA256",
        "raise Refused(\"Revision-4 paid scope is permanently closed by direct-user decision\")",
    )
    return all(marker in text for marker in markers)


def off_peak_window(at: datetime, guard_seconds: int) -> dict[str, Any]:
    """Copy the existing deterministic Beijing overlap rule without importing it."""

    require(at.tzinfo is not None, "off-peak check requires timezone-aware UTC input")
    require(0 < guard_seconds <= 86400, "invalid guard seconds")
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
    rows = []
    for hold_id, hold in sorted(quota.get("holds", {}).items()):
        rows.append({"holdId": hold_id, **hold})
    return sha_bytes(canonical(rows))


def verify_base() -> dict[str, Any]:
    """Read only metadata from the closed registration and return its snapshot."""

    required = (CLOSED / "plan.json", CLOSED / "state.json", CLOSED / "send-ledger.json",
                CLOSED / "candidate-cost-quota.json", SCOPE_DECISION, SCOPE_PROPOSAL,
                CURRENT_DRIVER, OLD_DRIVER, QUOTA_MONITOR, STABLE_PROXY, DURABLE_WRITE)
    require(CLOSED.is_dir() and all(path.is_file() and not path.is_symlink() for path in required), "base registration file missing")
    require(sha(CLOSED / "plan.json") == CLOSED_PLAN_SHA256, "closed plan identity drift")
    require(sha(SCOPE_DECISION) == CLOSED_DECISION_SHA256, "old declined decision identity drift")
    require(sha(SCOPE_PROPOSAL) == CLOSED_PROPOSAL_SHA256, "old proposal identity drift")
    require(sha(CURRENT_DRIVER) == CURRENT_DRIVER_SHA256, "current permanent-lock driver drift")
    require(sha(OLD_DRIVER) == OLD_DRIVER_SHA256, "old controller source drift")
    require(sha(QUOTA_MONITOR) == QUOTA_MONITOR_SHA256, "quota monitor source drift")
    require(sha(STABLE_PROXY) == STABLE_PROXY_SHA256, "stable proxy source drift")
    require(sha(DURABLE_WRITE) == DURABLE_WRITE_SHA256, "durable write source drift")
    require(current_driver_lock_verified(), "permanent revision-4 rejection lock is not present")

    plan = read_json(CLOSED / "plan.json")
    state = read_json(CLOSED / "state.json")
    ledger = read_json(CLOSED / "send-ledger.json")
    quota = read_json(CLOSED / "candidate-cost-quota.json")
    decision = read_json(SCOPE_DECISION)
    require(plan.get("limits") == EXPECTED_LIMITS, "closed plan limits changed")
    require(plan.get("registration") == "batch-1/reregistered-dev-1/revision-4", "wrong closed registration")
    require(plan.get("statusAtRegistration") == "DRAFT_REQUIRES_DIRECT_USER_PAID_AUTHORIZATION", "closed status drift")
    require(state.get("planSha256") == CLOSED_PLAN_SHA256 and state.get("requestsStarted") == 76
            and state.get("terminal") == "INTERRUPTED_NO_REPLAY", "closed state/counters changed")
    require(ledger.get("planSha256") == CLOSED_PLAN_SHA256 and len(ledger.get("calls", [])) == 76
            and not ledger.get("rejections") and not ledger.get("breaches"), "closed send ledger changed")
    unknown = [hold for hold in quota.get("holds", {}).values() if hold.get("status") == "UNKNOWN"]
    require(quota.get("limitYuan") == 1.0 and quota.get("reservationYuan") == 0.1
            and quota.get("knownCalls") == 139 and str(quota.get("knownCostYuan")) in ("0.29376746", "0.293767460")
            and len(unknown) == 7 and len(unknown) == len(quota.get("holds", {})), "candidate quota identity changed")
    require(decision.get("decision") == "DECLINED_NO_ADDITIONAL_PAID_EVALUATION"
            and decision.get("newPaidAuthorizationGranted") is False, "old decline decision is not closed")

    base_paths = {
        "closedPlan": CLOSED / "plan.json",
        "closedState": CLOSED / "state.json",
        "closedLedger": CLOSED / "send-ledger.json",
        "candidateQuota": CLOSED / "candidate-cost-quota.json",
        "oldDecision": SCOPE_DECISION,
        "oldProposal": SCOPE_PROPOSAL,
        "currentDriver": CURRENT_DRIVER,
        "oldController": OLD_DRIVER,
        "quotaMonitor": QUOTA_MONITOR,
        "stableProxy": STABLE_PROXY,
        "durableWrite": DURABLE_WRITE,
    }
    hashes = {name: sha(path) for name, path in base_paths.items()}
    known_cost = Decimal(str(quota["knownCostYuan"]))
    reserve = Decimal("0.10")
    limit = Decimal("1.00")
    held_unknown = reserve * len(unknown)
    committed = known_cost + held_unknown
    next_commitment = committed + reserve
    return {
        "format": "stage-d-reopen-base-snapshot-v1",
        "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        "files": {relative(path): digest for (name, path), digest in zip(base_paths.items(), hashes.values())},
        "namedHashes": hashes,
        "closedPlanSha256": CLOSED_PLAN_SHA256,
        "closedDecisionSha256": CLOSED_DECISION_SHA256,
        "closedProposalSha256": CLOSED_PROPOSAL_SHA256,
        "closedPlanDriverSha256": plan["files"].get("scripts/run_replan_stage_d_reregistered.py"),
        "currentLockedDriverSha256": CURRENT_DRIVER_SHA256,
        "currentPermanentRejectLockVerified": True,
        "state": {
            "requestsStarted": state["requestsStarted"],
            "terminal": state["terminal"],
            "rounds": sorted(state.get("rounds", {}).keys()),
        },
        "ledger": {
            "calls": len(ledger.get("calls", [])),
            "rejections": len(ledger.get("rejections", [])),
            "breaches": len(ledger.get("breaches", [])),
        },
        "candidateQuota": {
            "budgetId": quota["budgetId"],
            "pricingIdentity": quota["pricingIdentity"],
            "limitYuan": "1.00",
            "reservationYuan": "0.10",
            "knownCalls": quota["knownCalls"],
            "knownCostYuan": f"{known_cost:.9f}",
            "unknownHolds": len(unknown),
            "unknownHoldIdentitySha256": _hold_digest(quota),
            "unknownHeldYuan": f"{held_unknown:.2f}",
            "configuredCommittedYuan": f"{committed:.9f}",
            "nextReservationCommitmentYuan": f"{next_commitment:.9f}",
            "nextReservationWouldExceedLimit": next_commitment > limit,
        },
        "derivedCounters": {
            "legacyRequests": 68,
            "legacyApplicationCalls": 70,
            "newRequestsStarted": state["requestsStarted"],
            "newApplicationCallsStarted": len(ledger.get("calls", [])),
            "cumulativeRequestsSeen": 68 + state["requestsStarted"],
            "cumulativeApplicationCallsSeen": 70 + len(ledger.get("calls", [])),
            "remainingCurrentPlanRequestSlots": EXPECTED_LIMITS["requests"] - state["requestsStarted"],
            "remainingCurrentPlanCallSlots": EXPECTED_LIMITS["calls"] - len(ledger.get("calls", [])),
            "remainingSelectedNewRouteCallSlots": EXPECTED_LIMITS["selectedNewRouteMaximumCalls"] - len(ledger.get("calls", [])),
        },
    }


def build_authorization(base: dict[str, Any]) -> dict[str, Any]:
    return {
        "format": "direct-user-stage-d-reopen-authorization-v1",
        "authorizationId": AUTHORIZATION_ID,
        "source": "parent-relayed direct-user authorization in the current task",
        "statement": AUTHORIZATION_STATEMENT,
        "statementSha256": sha_bytes(AUTHORIZATION_STATEMENT.encode("utf-8")),
        "decision": "APPROVED_FUTURE_PAID_SCOPE_WITH_FROZEN_CAPS",
        "paidEvaluationScope": {
            "futureScopeApproved": True,
            "executionEnabledInThisTask": False,
            "runNextInvoked": False,
            "keyReadAllowedInThisTask": False,
            "providerCallsAllowedInThisTask": False,
            "limitIncreaseAllowed": False,
            "quotaResetAllowed": False,
            "oldEvidenceRewriteAllowed": False,
            "oldUnknownReleaseAllowed": False,
            "peakWindowBypassAllowed": False,
        },
        "boundClosedRegistration": {
            "registration": "batch-1/reregistered-dev-1/revision-4",
            "closedPlanSha256": base["closedPlanSha256"],
            "closedDecisionSha256": base["closedDecisionSha256"],
            "closedProposalSha256": base["closedProposalSha256"],
            "closedTerminal": base["state"]["terminal"],
        },
        "limitsMustRemainExact": EXPECTED_LIMITS,
        "offPeakOnly": {
            "timezone": "Asia/Shanghai",
            "weekdayPeakIntervals": ["09:00-12:00", "14:00-18:00"],
            "guardOverlapRejects": True,
            "weekendsAreOffPeakSubjectToOtherGates": True,
        },
        "unknownAccounting": {
            "candidateUnknownNeverReleased": True,
            "commonAndCandidateLayersAreNotSummedAsPaidCost": True,
            "nextReservationMustPassOriginalOneYuanLimit": True,
        },
    }


def build_plan(base: dict[str, Any], authorization: dict[str, Any], base_snapshot_sha256: str) -> dict[str, Any]:
    return {
        "format": "stage-d-append-only-reopen-plan-v1",
        "registration": "batch-1/reregistered-dev-1/revision-5-reopen",
        "createdAtUtc": datetime.now(timezone.utc).isoformat(),
        "appendOnly": True,
        "executionAdapterRequired": True,
        "legacyRevision4RemainsPermanentlyClosed": True,
        "legacyPermanentRejectLockVerified": True,
        "baseRegistration": "batch-1/reregistered-dev-1/revision-4",
        "basePlanSha256": base["closedPlanSha256"],
        "baseDecisionSha256": base["closedDecisionSha256"],
        "baseProposalSha256": base["closedProposalSha256"],
        "baseSnapshotFile": "base-snapshot.json",
        "baseSnapshotSha256": base_snapshot_sha256,
        "authorizationFile": "reopen-authorization.json",
        "authorizationId": authorization["authorizationId"],
        "authorizationStatementSha256": authorization["statementSha256"],
        "scopeDecision": "APPROVED_FUTURE_PAID_SCOPE_WITH_FROZEN_CAPS",
        "executionState": "PREPARE_PREFLIGHT_AUDIT_ONLY",
        "runNext": {
            "actionExposed": False,
            "invokedDuringPreparation": False,
            "requiresIndependentFinalReview": True,
            "requiresOffPeakCheckBeforeEverySend": True,
            "requiresOriginalQuotaAdmission": True,
        },
        "limits": EXPECTED_LIMITS,
        "inheritedCounters": base["derivedCounters"],
        "inheritedCandidateQuota": base["candidateQuota"],
        "gates": {
            "candidate": "C1 only; C2 not admitted",
            "order": ["DEV_F", "TARGETS", "B1", "F1", "F2", "B2", "B3", "F3", "U_B", "U_F"],
            "formalRounds": ["B1", "F1", "F2", "B2", "B3", "F3"],
            "unseen": "LOCKED until all original formal/safety gates pass",
            "model": "deepseek-flash",
            "temperature": 0,
            "maxCompletionTokens": 8192,
            "oneCompletion": True,
            "toolsAndReasoningModes": "disabled as in closed plan",
            "maxModelCallsPerQuestion": 2,
            "sdkAttempts": 1,
            "parentRequestTimeoutMs": 15000,
            "roundGuardSeconds": 1740,
            "sendGuardSeconds": 120,
            "maxPayloadBytes": 65536,
            "quotaLimitYuan": "1.00",
            "reservationYuan": "0.10",
            "candidateUnknownNeverReleased": True,
        },
        "offPeakPolicy": {
            "timezone": "Asia/Shanghai",
            "weekdayPeakIntervals": ["09:00-12:00", "14:00-18:00"],
            "rule": "reject if send guard overlaps either interval",
            "source": "inherited controller window() rule; source SHA bound below",
        },
        "sourceBinding": {
            "currentLockedDriverSha256": base["currentLockedDriverSha256"],
            "closedPlanDriverSha256": base["closedPlanDriverSha256"],
            "oldControllerSha256": OLD_DRIVER_SHA256,
            "quotaMonitorSha256": QUOTA_MONITOR_SHA256,
            "stableProxySha256": STABLE_PROXY_SHA256,
            "durableWriteSha256": DURABLE_WRITE_SHA256,
            "noSourceFileModifiedByPrepare": True,
        },
        "providerCallsAddedByPrepare": 0,
        "keyReadsByPrepare": 0,
        "oldLedgerReset": False,
        "oldDecisionRewritten": False,
        "oldUnknownReleased": 0,
    }


def _expected_output_files(output: Path) -> set[str]:
    return {
        "reopen_plan.py",
        "test_reopen_plan.py",
        "run_reopen_plan.py",
        "base-snapshot.json",
        "reopen-authorization.json",
        "plan.json",
        "authorization-binding.json",
        "prepare-result.json",
        "preflight.json",
        "audit.json",
    }


def _load_output(output: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]:
    base = read_json(output / "base-snapshot.json")
    authorization = read_json(output / "reopen-authorization.json")
    plan = read_json(output / "plan.json")
    binding = read_json(output / "authorization-binding.json")
    return base, authorization, plan, binding


def verify_output(output: Path) -> dict[str, Any]:
    base, authorization, plan, binding = _load_output(output)
    require(sha(output / "base-snapshot.json") == plan["baseSnapshotSha256"], "base snapshot hash mismatch")
    require(sha(output / "reopen-authorization.json") == binding["authorizationSha256"], "authorization hash mismatch")
    require(sha(output / "plan.json") == binding["planSha256"], "plan hash mismatch")
    require(binding.get("authorizationId") == authorization.get("authorizationId") == plan.get("authorizationId"), "authorization identity mismatch")
    require(binding.get("basePlanSha256") == base["closedPlanSha256"] == plan["basePlanSha256"], "base plan binding mismatch")
    require(plan.get("limits") == EXPECTED_LIMITS, "reopen plan limits changed")
    require(plan.get("executionState") == "PREPARE_PREFLIGHT_AUDIT_ONLY", "prepare unexpectedly enables execution")
    require(plan.get("providerCallsAddedByPrepare") == 0 and plan.get("keyReadsByPrepare") == 0, "prepare scope widened")
    require(plan.get("oldLedgerReset") is False and plan.get("oldDecisionRewritten") is False
            and plan.get("oldUnknownReleased") == 0, "append-only invariants changed")
    require(authorization.get("paidEvaluationScope", {}).get("limitIncreaseAllowed") is False
            and authorization.get("paidEvaluationScope", {}).get("quotaResetAllowed") is False,
            "authorization permits an out-of-scope change")
    base_now = verify_base()
    require(base_now["namedHashes"] == base["namedHashes"], "base registration changed after preparation")
    return {
        "baseUnchanged": True,
        "authorizationBound": True,
        "limitsExact": True,
        "executionDisabled": True,
        "legacyPermanentLockVerified": True,
    }


def prepare(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve(), "reopen output must be this independent directory")
    # The source module lives in the independent directory.  Preparation may
    # add only the append-only evidence files; it may never reset an existing
    # generated artifact or touch the closed registration.
    require(output.is_dir(), "reopen source directory is missing")
    generated = {path.name for path in output.iterdir() if path.is_file()
                 and path.name not in {"reopen_plan.py", "test_reopen_plan.py", "run_reopen_plan.py"}}
    require(not generated, "new reopen directory already contains generated files; never reset or overwrite")
    base = verify_base()
    write_new(output / "base-snapshot.json", base)
    base_snapshot_sha = sha(output / "base-snapshot.json")
    authorization = build_authorization(base)
    write_new(output / "reopen-authorization.json", authorization)
    plan = build_plan(base, authorization, base_snapshot_sha)
    write_new(output / "plan.json", plan)
    binding = {
        "format": "stage-d-reopen-authorization-binding-v1",
        "authorizationId": authorization["authorizationId"],
        "authorizationSha256": sha(output / "reopen-authorization.json"),
        "planSha256": sha(output / "plan.json"),
        "basePlanSha256": base["closedPlanSha256"],
        "baseDecisionSha256": base["closedDecisionSha256"],
        "baseSnapshotSha256": base_snapshot_sha,
        "appendOnly": True,
        "executionEnabled": False,
    }
    write_new(output / "authorization-binding.json", binding)
    result = {
        "format": "stage-d-reopen-prepare-result-v1",
        "passed": True,
        "scope": "metadata-only append registration; no key/provider/run-next",
        "planSha256": binding["planSha256"],
        "authorizationSha256": binding["authorizationSha256"],
        "basePlanSha256": base["closedPlanSha256"],
        "providerCalls": 0,
        "keyReads": 0,
        "runNextInvoked": False,
        "oldLedgerReset": False,
        "oldDecisionRewritten": False,
        "oldUnknownReleased": 0,
        "nextReservationWouldExceedLimit": base["candidateQuota"]["nextReservationWouldExceedLimit"],
        "sendAdmissionNow": "REFUSED_EXISTING_ONE_YUAN_COMMITMENT",
    }
    write_new(output / "prepare-result.json", result)
    return result


def preflight(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve(), "reopen output must be this independent directory")
    require(output.is_dir(), "prepare must run before preflight")
    checks = verify_output(output)
    peak_cases = {
        "insideMorningPeak": off_peak_window(datetime(2026, 11, 20, 2, 0, tzinfo=timezone.utc), 120),
        "morningBoundaryOverlap": off_peak_window(datetime(2026, 11, 20, 0, 59, tzinfo=timezone.utc), 120),
        "afterMorningPeak": off_peak_window(datetime(2026, 11, 20, 4, 0, tzinfo=timezone.utc), 120),
        "weekend": off_peak_window(datetime(2026, 11, 21, 2, 0, tzinfo=timezone.utc), 120),
    }
    require(peak_cases["insideMorningPeak"]["allowed"] is False
            and peak_cases["insideMorningPeak"]["reason"] == "PEAK_HOURS", "morning peak guard failed")
    require(peak_cases["morningBoundaryOverlap"]["allowed"] is False
            and peak_cases["morningBoundaryOverlap"]["reason"] == "PEAK_BOUNDARY_RESERVE", "peak boundary guard failed")
    require(peak_cases["afterMorningPeak"]["allowed"] is True and peak_cases["weekend"]["allowed"] is True,
            "off-peak guard failed")
    base, _authorization, plan, _binding = _load_output(output)
    preflight_result = {
        "format": "stage-d-reopen-preflight-v1",
        "passed": True,
        "checks": {**checks, "peakWindowRules": True, "originalLimits": plan["limits"] == EXPECTED_LIMITS,
                    "quotaRefusesNextReservation": base["candidateQuota"]["nextReservationWouldExceedLimit"]},
        "peakCases": peak_cases,
        "providerCalls": 0,
        "keyReads": 0,
        "runNextInvoked": False,
        "executionEnabled": False,
        "quotaAdmission": "REFUSED_EXISTING_ONE_YUAN_COMMITMENT",
        "limitYuan": "1.00",
        "reservationYuan": "0.10",
        "knownCostYuan": base["candidateQuota"]["knownCostYuan"],
        "unknownHolds": base["candidateQuota"]["unknownHolds"],
        "configuredCommittedYuan": base["candidateQuota"]["configuredCommittedYuan"],
        "nextReservationCommitmentYuan": base["candidateQuota"]["nextReservationCommitmentYuan"],
        "sourceScope": "only metadata and deterministic clock checks; no database/JAR/provider access",
    }
    write_new(output / "preflight.json", preflight_result)
    return preflight_result


def audit(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve(), "reopen output must be this independent directory")
    require(output.is_dir() and (output / "preflight.json").is_file(), "preflight must run before audit")
    preflight_result = read_json(output / "preflight.json")
    require(preflight_result.get("passed") is True, "preflight did not pass")
    checks = verify_output(output)
    files = sorted(path.name for path in output.iterdir() if path.is_file())
    require(set(files).issubset(_expected_output_files(output)), "unexpected output file in reopen directory")
    audit_result = {
        "format": "stage-d-reopen-independent-audit-v1",
        "passed": True,
        "checks": {
            **checks,
            "preflightPassed": True,
            "onlyNewDirectoryWritten": True,
            "oldStateLedgerQuotaUnchanged": True,
            "oldDeclinedDecisionUnchanged": True,
            "currentPermanentRejectLockStillPresent": current_driver_lock_verified(),
            "originalOneYuanLimitPreserved": True,
            "originalCountersPreserved": True,
            "originalGatesPreserved": True,
            "beijingPeakRejectionPreserved": True,
        },
        "providerCalls": 0,
        "keyReads": 0,
        "runNextInvoked": False,
        "actualBatchModified": False,
        "quotaMigrationPerformed": False,
        "oldUnknownReleased": 0,
        "outputFiles": files,
        "note": "This audit creates no execution credential and does not authorize a send by itself.",
    }
    write_new(output / "audit.json", audit_result)
    return audit_result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "preflight", "audit"))
    args = parser.parse_args()
    action = {"prepare": prepare, "preflight": preflight, "audit": audit}[args.action]
    try:
        print(json.dumps(action(), ensure_ascii=False, indent=2))
    except Refused as exc:
        print("REFUSED:", exc)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
