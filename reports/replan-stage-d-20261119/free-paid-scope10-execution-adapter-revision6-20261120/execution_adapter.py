"""Offline execution adapter for the revision-6 paid-scope-10 registration.

The adapter is deliberately a separate, fail-closed directory.  Its four
entry points are ``prepare``, ``preflight``, ``dry-run`` and ``execute``.
``execute`` performs the same immutable scope checks again and then refuses
because this free work package has no real quota-migration proof and real
execution is disabled.  It never reads a Key, opens a socket, imports the
production driver, or sends a provider request.
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
HERE = Path(__file__).resolve().parent
SCOPE_DIR = ROOT / "reports/replan-stage-d-20261119/free-paid-scope10-revision6-20261120"
CLOSED_DIR = ROOT / "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4"

SCOPE_PLAN_SHA256 = "a0f9e86a32b78addf68204be76115953cf082b61791ec74a4339ac65fbab3595"
SCOPE_AUTH_SHA256 = "bcf686fefa4468633a87de1d3b4bbcefa87c04a10889f8643149ed97733bcdd0"
SCOPE_BINDING_SHA256 = "2bf2ed7d8a1cf7e79fb6960fab5bd8fe2413cc2dc7701632966a0a3e5db5325b"
SCOPE_BASE_SHA256 = "cb6aece13b01e8098c8f4491ea34d18cf594b2d4827d4cb170827487f212d27c"
SCOPE_STAGED_QUOTA_SHA256 = "57931f5a2a6df10e654ff3322791e8eebc7675dbb10abf1c6f7305a019de41bf"
CLOSED_PLAN_SHA256 = "da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe"
CLOSED_STATE_SHA256 = "dca6197ce04a4fc1ed87f665b7954cf25a1f0f3216620b78eb2dc6f3f9d11025"
CLOSED_LEDGER_SHA256 = "a5097cec99f0a8c9b2f2b9596a46a682dde6a0dd8bbf4ad6bdad4af602d3ca23"
CLOSED_QUOTA_SHA256 = "f9ed89b775ff2c57e4394c1bf39d55533752f0d19e3c0bae13286dc4aceccc86"

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

AUTHORIZATION_ID = "direct-user-stage-d-paid-scope10-20261120-v1"
ADAPTER_FORMAT = "stage-d-paid-scope10-execution-adapter-v1"
PROOF_FORMAT = "stage-d-real-quota-migration-proof-v1"
EXECUTION_DISABLED_REASON = "EXECUTION_DISABLED_UNTIL_INDEPENDENT_ACCEPTANCE"


class AdapterRefused(RuntimeError):
    """A scope or execution precondition failed closed."""


def sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha(path: Path) -> str:
    try:
        return sha_bytes(path.read_bytes())
    except (OSError, ValueError) as exc:
        raise AdapterRefused(f"cannot hash {path}: {exc}") from exc


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def read_json(path: Path) -> dict[str, Any]:
    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in items:
            if key in result:
                raise AdapterRefused(f"duplicate JSON member: {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=pairs)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise AdapterRefused(f"cannot read JSON: {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise AdapterRefused(f"expected JSON object: {path}")
    return value


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AdapterRefused(message)


def write_new(path: Path, value: Any) -> None:
    require(not path.exists(), f"append-only output already exists: {path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def _hold_digest(quota: dict[str, Any]) -> str:
    rows = [{"holdId": hold_id, **hold} for hold_id, hold in sorted(quota.get("holds", {}).items())]
    return sha_bytes(canonical(rows))


def off_peak_window(at: datetime, guard_seconds: int = 120) -> dict[str, Any]:
    """Reject a send guard that overlaps weekday Beijing peak hours."""

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


def _scope_paths() -> dict[str, Path]:
    return {
        "plan": SCOPE_DIR / "plan.json",
        "authorization": SCOPE_DIR / "paid-scope10-authorization.json",
        "binding": SCOPE_DIR / "authorization-binding.json",
        "base": SCOPE_DIR / "base-snapshot.json",
        "stagedQuota": SCOPE_DIR / "staged-candidate-quota.json",
        "closedPlan": CLOSED_DIR / "plan.json",
        "closedState": CLOSED_DIR / "state.json",
        "closedLedger": CLOSED_DIR / "send-ledger.json",
        "closedQuota": CLOSED_DIR / "candidate-cost-quota.json",
    }


def verify_scope() -> dict[str, Any]:
    """Load and re-verify every identity needed before a future execution."""

    paths = _scope_paths()
    require(all(path.is_file() and not path.is_symlink() for path in paths.values()), "scope anchor missing")
    hashes = {name: sha(path) for name, path in paths.items()}
    require(hashes["plan"] == SCOPE_PLAN_SHA256, "revision-6 plan SHA drift")
    require(hashes["authorization"] == SCOPE_AUTH_SHA256, "user authorization SHA drift")
    require(hashes["binding"] == SCOPE_BINDING_SHA256, "authorization binding SHA drift")
    require(hashes["base"] == SCOPE_BASE_SHA256, "scope base snapshot SHA drift")
    require(hashes["stagedQuota"] == SCOPE_STAGED_QUOTA_SHA256, "staged quota SHA drift")
    require(hashes["closedPlan"] == CLOSED_PLAN_SHA256, "closed plan SHA drift")
    require(hashes["closedState"] == CLOSED_STATE_SHA256, "closed state SHA drift")
    require(hashes["closedLedger"] == CLOSED_LEDGER_SHA256, "closed ledger SHA drift")
    require(hashes["closedQuota"] == CLOSED_QUOTA_SHA256, "closed quota SHA drift")

    plan = read_json(paths["plan"])
    authorization = read_json(paths["authorization"])
    binding = read_json(paths["binding"])
    base = read_json(paths["base"])
    staged = read_json(paths["stagedQuota"])
    closed_plan = read_json(paths["closedPlan"])
    closed_state = read_json(paths["closedState"])
    closed_ledger = read_json(paths["closedLedger"])
    closed_quota = read_json(paths["closedQuota"])

    require(binding.get("format") == "stage-d-paid-scope10-authorization-binding-v1", "binding format drift")
    require(binding.get("appendOnly") is True and binding.get("executionEnabled") is False,
            "binding execution flags drift")
    require(binding.get("authorizationId") == AUTHORIZATION_ID, "authorization id drift")
    require(binding.get("authorizationSha256") == hashes["authorization"], "authorization binding target drift")
    require(binding.get("planSha256") == hashes["plan"], "plan binding target drift")
    require(binding.get("stagedQuotaSha256") == hashes["stagedQuota"], "staged quota binding target drift")
    require(binding.get("baseSnapshotSha256") == hashes["base"], "base binding target drift")
    require(binding.get("priorReopenPlanSha256") == base.get("priorReopenPlanSha256"), "prior reopen binding drift")

    require(authorization.get("authorizationId") == AUTHORIZATION_ID
            and authorization.get("decision") == "APPROVED_PAID_SCOPE10_WITH_FROZEN_ORIGINAL_GATES"
            and authorization.get("scope") == SCOPE10
            and authorization.get("executionEnabledInThisTask") is False
            and authorization.get("keyReadAllowedInThisTask") is False
            and authorization.get("providerCallsAllowedInThisTask") is False,
            "user authorization scope or execution flags drift")
    require(plan.get("scope") == SCOPE10
            and plan.get("executionState") == "PREPARE_PREFLIGHT_AUDIT_ONLY"
            and plan.get("runNextExposed") is False
            and plan.get("baseClosedPlanSha256") == CLOSED_PLAN_SHA256,
            "revision-6 plan scope or execution state drift")
    require(plan.get("gates", {}).get("maxModelCallsPerQuestion") == 2
            and plan.get("gates", {}).get("sdkAttempts") == 1
            and plan.get("gates", {}).get("sendGuardSeconds") == 120,
            "original call or send gates drift")
    require(plan.get("accountingContinuation", {}).get("sameSentConnectionReadOnlyExtraSeconds") == 105
            and plan.get("accountingContinuation", {}).get("lateSqlAnswerEligible") is False
            and plan.get("accountingContinuation", {}).get("noReconnect") is True
            and plan.get("accountingContinuation", {}).get("noResend") is True,
            "accounting continuation gates drift")

    require(closed_plan.get("registration") == "batch-1/reregistered-dev-1/revision-4",
            "closed registration identity drift")
    require(closed_state.get("planSha256") == CLOSED_PLAN_SHA256
            and closed_state.get("requestsStarted") == 76
            and closed_state.get("terminal") == "INTERRUPTED_NO_REPLAY",
            "old interrupted state inheritance drift")
    require(closed_ledger.get("planSha256") == CLOSED_PLAN_SHA256
            and len(closed_ledger.get("calls", [])) == 76,
            "old ledger inheritance drift")

    unknown = [hold for hold in closed_quota.get("holds", {}).values() if hold.get("status") == "UNKNOWN"]
    require(closed_quota.get("limitYuan") == 1.0
            and closed_quota.get("reservationYuan") == 0.1
            and closed_quota.get("knownCalls") == 139
            and str(closed_quota.get("knownCostYuan")) in ("0.29376746", "0.293767460")
            and len(unknown) == 7
            and len(unknown) == len(closed_quota.get("holds", {})),
            "old candidate quota inheritance drift")
    staged_compare = copy.deepcopy(staged)
    staged_compare["limitYuan"] = closed_quota["limitYuan"]
    require(staged_compare == closed_quota, "staged quota changed fields beyond limitYuan")
    require(staged.get("limitYuan") == 10.0 and staged.get("reservationYuan") == 0.1,
            "staged quota is not exact ¥10/¥0.10")
    require(_hold_digest(staged) == _hold_digest(closed_quota), "candidate UNKNOWN hold identity drift")

    old_source_files = base.get("sourceFiles", {})
    require(old_source_files, "base source binding missing")
    for relative, expected in old_source_files.items():
        source = ROOT / Path(relative)
        require(source.is_file() and not source.is_symlink() and sha(source) == expected,
                f"read-only source anchor drift: {relative}")
    require(base.get("oldState", {}).get("requestsStarted") == 76
            and base.get("oldState", {}).get("terminal") == "INTERRUPTED_NO_REPLAY"
            and base.get("oldLedger", {}).get("calls") == 76
            and base.get("oldCandidateQuota", {}).get("unknownHolds") == 7,
            "base snapshot inherited identity drift")

    known_cost = Decimal(str(closed_quota["knownCostYuan"]))
    unknown_held = Decimal("0.10") * Decimal(len(unknown))
    current_commitment = known_cost + unknown_held
    next_commitment = current_commitment + Decimal("0.10")
    require(next_commitment <= Decimal("10.00"), "¥0.10 reservation exceeds staged ¥10 limit")
    return {
        "hashes": hashes,
        "plan": plan,
        "authorization": authorization,
        "binding": binding,
        "base": base,
        "stagedQuota": staged,
        "closedQuota": closed_quota,
        "oldUnknownCount": len(unknown),
        "oldUnknownHeldYuan": f"{unknown_held:.2f}",
        "currentCommitmentYuan": f"{current_commitment:.9f}",
        "nextReservationCommitmentYuan": f"{next_commitment:.9f}",
        "holdDigest": _hold_digest(closed_quota),
    }


def _generated_files() -> set[str]:
    return {
        "execution_adapter.py",
        "test_execution_adapter.py",
        "README.md",
        "adapter-manifest.json",
        "prepare-result.json",
        "preflight.json",
        "dry-run.json",
        "execute-result.json",
    }


def _verify_manifest(output: Path) -> dict[str, Any]:
    path = output / "adapter-manifest.json"
    require(path.is_file(), "prepare must run first")
    manifest = read_json(path)
    require(manifest.get("format") == ADAPTER_FORMAT, "adapter manifest format drift")
    require(manifest.get("scopePlanSha256") == SCOPE_PLAN_SHA256
            and manifest.get("scopeAuthorizationSha256") == SCOPE_AUTH_SHA256
            and manifest.get("scopeBindingSha256") == SCOPE_BINDING_SHA256
            and manifest.get("closedLedgerSha256") == CLOSED_LEDGER_SHA256,
            "adapter manifest scope identity drift")
    require(manifest.get("executionEnabled") is False
            and manifest.get("realQuotaMigrationProofRequired") is True
            and manifest.get("providerCalls") == 0
            and manifest.get("keyReads") == 0,
            "adapter manifest execution flags drift")
    return manifest


def prepare(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve(), "prepare output must be the new adapter directory")
    require(output.is_dir(), "adapter directory missing")
    generated = {path.name for path in output.iterdir() if path.is_file()
                 and path.name not in {"execution_adapter.py", "test_execution_adapter.py", "README.md"}}
    require(not generated, "adapter output already contains generated files; never overwrite")
    context = verify_scope()
    manifest = {
        "format": ADAPTER_FORMAT,
        "registration": "batch-1/reregistered-dev-1/revision-6-paid-scope-10/execution-adapter",
        "createdAtUtc": datetime.now(timezone.utc).isoformat(),
        "scopePlanSha256": SCOPE_PLAN_SHA256,
        "scopeAuthorizationSha256": SCOPE_AUTH_SHA256,
        "scopeBindingSha256": SCOPE_BINDING_SHA256,
        "scopeBaseSnapshotSha256": SCOPE_BASE_SHA256,
        "scopeStagedQuotaSha256": SCOPE_STAGED_QUOTA_SHA256,
        "closedLedgerSha256": CLOSED_LEDGER_SHA256,
        "closedStateSha256": CLOSED_STATE_SHA256,
        "closedQuotaSha256": CLOSED_QUOTA_SHA256,
        "authorizationId": AUTHORIZATION_ID,
        "executionEnabled": False,
        "realQuotaMigrationProofRequired": True,
        "stagedQuotaOnly": True,
        "oldUnknownNeverReleased": True,
        "noKeyRead": True,
        "noProviderCalls": True,
        "noProductionDriverImport": True,
        "providerCalls": 0,
        "keyReads": 0,
        "oldLedgerModified": False,
        "oldEvidenceModified": False,
        "scopeCurrentCommitmentYuan": context["currentCommitmentYuan"],
        "nextReservationCommitmentYuan": context["nextReservationCommitmentYuan"],
    }
    write_new(output / "adapter-manifest.json", manifest)
    result = {
        "format": "stage-d-paid-scope10-adapter-prepare-v1",
        "passed": True,
        "adapterManifestSha256": sha(output / "adapter-manifest.json"),
        "scopePlanSha256": SCOPE_PLAN_SHA256,
        "scopeAuthorizationSha256": SCOPE_AUTH_SHA256,
        "providerCalls": 0,
        "keyReads": 0,
        "oldLedgerModified": False,
        "oldEvidenceModified": False,
        "executionEnabled": False,
        "realQuotaMigrationProofPresent": False,
        "nextEntry": "preflight",
    }
    write_new(output / "prepare-result.json", result)
    return result


def _require_prepared(output: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    context = verify_scope()
    manifest = _verify_manifest(output)
    return context, manifest


def preflight(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve(), "preflight output must be the new adapter directory")
    context, manifest = _require_prepared(output)
    samples = {
        "morningPeak": off_peak_window(datetime(2026, 11, 20, 2, 0, tzinfo=timezone.utc)),
        "morningBoundary": off_peak_window(datetime(2026, 11, 20, 0, 59, tzinfo=timezone.utc)),
        "afterMorningPeak": off_peak_window(datetime(2026, 11, 20, 4, 0, tzinfo=timezone.utc)),
        "weekend": off_peak_window(datetime(2026, 11, 21, 2, 0, tzinfo=timezone.utc)),
    }
    checks = {
        "scopePlanShaVerified": context["hashes"]["plan"] == SCOPE_PLAN_SHA256,
        "authorizationBindingVerified": context["binding"]["authorizationId"] == AUTHORIZATION_ID,
        "oldLedgerInheritanceVerified": context["closedQuota"].get("knownCalls") == 139,
        "oldUnknownPermanent": context["oldUnknownCount"] == 7 and manifest["oldUnknownNeverReleased"] is True,
        "stagedQuotaExact": context["stagedQuota"].get("limitYuan") == 10.0,
        "reservationWithinLimit": Decimal(context["nextReservationCommitmentYuan"]) <= Decimal("10.00"),
        "peakRejected": not samples["morningPeak"]["allowed"],
        "boundaryRejected": not samples["morningBoundary"]["allowed"],
        "offPeakAllowed": samples["afterMorningPeak"]["allowed"] and samples["weekend"]["allowed"],
        "executionDisabled": manifest["executionEnabled"] is False,
        "providerCallsZero": manifest["providerCalls"] == 0,
        "keyReadsZero": manifest["keyReads"] == 0,
    }
    result = {
        "format": "stage-d-paid-scope10-adapter-preflight-v1",
        "passed": all(checks.values()),
        "checks": checks,
        "peakCases": samples,
        "stagedLimitYuan": "10.00",
        "reservationYuan": "0.10",
        "currentCandidateCommitmentYuan": context["currentCommitmentYuan"],
        "nextReservationCommitmentYuan": context["nextReservationCommitmentYuan"],
        "oldUnknownHolds": context["oldUnknownCount"],
        "oldUnknownReleased": 0,
        "providerCalls": 0,
        "keyReads": 0,
        "runNextInvoked": False,
        "executionEnabled": False,
        "realQuotaMigrationProofPresent": False,
    }
    write_new(output / "preflight.json", result)
    return result


def dry_run(output: Path = HERE) -> dict[str, Any]:
    require(output.resolve() == HERE.resolve(), "dry-run output must be the new adapter directory")
    context, manifest = _require_prepared(output)
    preflight_result = read_json(output / "preflight.json")
    require(preflight_result.get("passed") is True, "preflight did not pass")
    plan = context["plan"]
    max_calls = plan["gates"]["maxModelCallsPerQuestion"]
    attempts = [
        {"questionId": "dry-q-001", "callNumber": 1, "postCount": 1, "providerCall": False},
        {"questionId": "dry-q-001", "callNumber": 2, "postCount": 1, "providerCall": False},
    ]
    require(len(attempts) <= max_calls and all(row["postCount"] == 1 for row in attempts),
            "dry-run call/post contract violated")
    answer_deadline_ms = 15000
    accounting_grace_ms = 105000
    accounting_deadline_ms = answer_deadline_ms + accounting_grace_ms
    accounting = {
        "sameSentConnectionReadOnlyExtraSeconds": 105,
        "answerDeadlineMs": answer_deadline_ms,
        "accountingDeadlineMs": accounting_deadline_ms,
        "late": True,
        "accountingOnly": True,
        "answerEligible": False,
        "lateSqlBackfill": False,
        "usageKnown": True,
        "callerStatus": 504,
        "callerSql": None,
    }
    result = {
        "format": "stage-d-paid-scope10-adapter-dry-run-v1",
        "passed": True,
        "mode": "synthetic-local-only",
        "questionCount": 1,
        "maxModelCallsPerQuestion": max_calls,
        "attempts": attempts,
        "plannedPostCount": sum(row["postCount"] for row in attempts),
        "maxPostCountPerAttempt": max(row["postCount"] for row in attempts),
        "providerCalls": 0,
        "keyReads": 0,
        "externalRequests": 0,
        "oldUnknownHolds": context["oldUnknownCount"],
        "oldUnknownReleased": 0,
        "oldUnknownRefundAllowed": False,
        "candidateHoldDigest": context["holdDigest"],
        "accounting": accounting,
        "executionEnabled": manifest["executionEnabled"],
        "realQuotaMigrationProofPresent": False,
    }
    write_new(output / "dry-run.json", result)
    return result


def _read_migration_proof(output: Path, context: dict[str, Any]) -> tuple[bool, str, dict[str, Any] | None]:
    path = output / "real-quota-migration-proof.json"
    if not path.is_file():
        return False, "REAL_QUOTA_MIGRATION_PROOF_MISSING", None
    try:
        proof = read_json(path)
    except AdapterRefused:
        return False, "REAL_QUOTA_MIGRATION_PROOF_INVALID", None
    valid = (
        proof.get("format") == PROOF_FORMAT
        and proof.get("productionApplied") is True
        and proof.get("sourceQuotaSha256") == context["hashes"]["closedQuota"]
        and proof.get("targetStagedQuotaSha256") == context["hashes"]["stagedQuota"]
        and proof.get("budgetId") == context["closedQuota"].get("budgetId")
        and proof.get("pricingIdentity") == context["closedQuota"].get("pricingIdentity")
        and proof.get("targetLimitYuan") == "10.00"
        and proof.get("unknownHoldDigest") == context["holdDigest"]
        and proof.get("oldUnknownReleased") == 0
    )
    return (valid, "OK" if valid else "REAL_QUOTA_MIGRATION_PROOF_MISMATCH", proof)


def execute(output: Path = HERE, *, at: datetime | None = None, persist: bool = True) -> dict[str, Any]:
    """Recheck all execution gates and refuse before any real side effect."""

    require(output.resolve() == HERE.resolve(), "execute output must be the new adapter directory")
    context, manifest = _require_prepared(output)
    preflight_result = read_json(output / "preflight.json")
    dry_result = read_json(output / "dry-run.json")
    require(preflight_result.get("passed") is True and dry_result.get("passed") is True,
            "preflight and dry-run must pass before execute")
    checked_at = at or datetime.now(timezone.utc)
    window = off_peak_window(checked_at, 120)
    proof_valid, proof_reason, proof = _read_migration_proof(output, context)
    checks = {
        "userAuthorizationBinding": context["binding"].get("authorizationId") == AUTHORIZATION_ID,
        "planSha": context["hashes"]["plan"] == SCOPE_PLAN_SHA256,
        "oldLedgerInheritance": context["closedQuota"].get("knownCalls") == 139,
        "offPeakWindow": window["allowed"],
        "stagedQuotaTenYuan": context["stagedQuota"].get("limitYuan") == 10.0,
        "reservationWithinLimit": Decimal(context["nextReservationCommitmentYuan"]) <= Decimal("10.00"),
        "oldUnknownPermanent": context["oldUnknownCount"] == 7 and context["stagedQuota"].get("holds") == context["closedQuota"].get("holds"),
        "realQuotaMigrationProof": proof_valid,
        "executionEnabled": manifest.get("executionEnabled") is True,
    }
    refusal_reason = next((name for name, passed in (
        ("OFF_PEAK_REQUIRED", not checks["offPeakWindow"]),
        ("REAL_QUOTA_MIGRATION_PROOF_MISSING", not checks["realQuotaMigrationProof"] and proof_reason == "REAL_QUOTA_MIGRATION_PROOF_MISSING"),
        ("REAL_QUOTA_MIGRATION_PROOF_MISMATCH", not checks["realQuotaMigrationProof"]),
        ("EXECUTION_DISABLED", not checks["executionEnabled"]),
    ) if passed), EXECUTION_DISABLED_REASON)
    result = {
        "format": "stage-d-paid-scope10-adapter-execute-v1",
        "passed": False,
        "refused": True,
        "refusalReason": refusal_reason,
        "checkedAt": checked_at.isoformat(),
        "offPeak": window,
        "checks": checks,
        "migrationProofReason": proof_reason,
        "migrationProofPresent": proof is not None,
        "providerCalls": 0,
        "keyReads": 0,
        "externalRequests": 0,
        "oldLedgerModified": False,
        "oldEvidenceModified": False,
        "oldUnknownReleased": 0,
        "executionEnabled": False,
        "note": "Free adapter stops before Key/provider access; independent acceptance and real quota migration proof are required.",
    }
    if persist:
        write_new(output / "execute-result.json", result)
    return result


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "preflight", "dry-run", "execute"))
    args = parser.parse_args()
    try:
        action = {"prepare": prepare, "preflight": preflight, "dry-run": dry_run, "execute": execute}[args.action]
        print(json.dumps(action(), ensure_ascii=False, indent=2))
    except AdapterRefused as exc:
        print(json.dumps({"passed": False, "refused": True, "reason": str(exc), "providerCalls": 0, "keyReads": 0}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
