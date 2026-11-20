"""Append-only, offline quota activation workflow for revision-6 ¥10 scope.

The workflow reads the revision-4 candidate quota and the already-approved
revision-6 scope, then writes a new-directory staged runtime, a program-bound
migration proof, and an execution manifest.  It never mutates the old quota,
state, ledger, rejection evidence, driver, Key, or provider connection.

Actions are intentionally narrow:

* ``dry-run`` computes the target bytes and transaction identity in memory;
* ``activate-preflight`` writes only a free off-peak/precondition report; and
* ``activate-commit`` writes the new directory and marks only its manifest
  ``executionEnabled=true``.  It still records zero Key/provider activity.
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
OLD_QUOTA = CLOSED_DIR / "candidate-cost-quota.json"

SCOPE_PLAN_SHA256 = "a0f9e86a32b78addf68204be76115953cf082b61791ec74a4339ac65fbab3595"
SCOPE_AUTH_SHA256 = "bcf686fefa4468633a87de1d3b4bbcefa87c04a10889f8643149ed97733bcdd0"
SCOPE_BINDING_SHA256 = "2bf2ed7d8a1cf7e79fb6960fab5bd8fe2413cc2dc7701632966a0a3e5db5325b"
SCOPE_BASE_SHA256 = "cb6aece13b01e8098c8f4491ea34d18cf594b2d4827d4cb170827487f212d27c"
SCOPE_STAGED_QUOTA_SHA256 = "57931f5a2a6df10e654ff3322791e8eebc7675dbb10abf1c6f7305a019de41bf"
CLOSED_PLAN_SHA256 = "da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe"
CLOSED_STATE_SHA256 = "dca6197ce04a4fc1ed87f665b7954cf25a1f0f3216620b78eb2dc6f3f9d11025"
CLOSED_LEDGER_SHA256 = "a5097cec99f0a8c9b2f2b9596a46a682dde6a0dd8bbf4ad6bdad4af602d3ca23"
CLOSED_QUOTA_SHA256 = "f9ed89b775ff2c57e4394c1bf39d55533752f0d19e3c0bae13286dc4aceccc86"

AUTHORIZATION_ID = "direct-user-stage-d-paid-scope10-20261120-v1"
PROOF_FORMAT = "stage-d-paid-scope10-quota-migration-proof-v1"
MANIFEST_FORMAT = "stage-d-paid-scope10-activation-manifest-v1"
TRANSACTION_PREFIX = "quota-migration-"
SOURCE_QUOTA_RELATIVE_PATH = (
    "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4/"
    "candidate-cost-quota.json"
)
TARGET_QUOTA_RELATIVE_PATH = "staged-runtime-quota.json"
SOURCE_LIMIT_YUAN = "1.00"
TARGET_LIMIT_YUAN = "10.00"
RESERVATION_YUAN = "0.10"
STAGED_ACTIVATION_STATE = "STAGED_RUNTIME_READY_NO_PROVIDER_EXECUTION"
ACTIVATION_TIME_ANCHOR_UTC = "2026-11-20T15:00:00+00:00"
OFF_PEAK_GUARD_SECONDS = 120

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


class ActivationRefused(RuntimeError):
    """A migration or activation precondition failed closed."""


def sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha(path: Path) -> str:
    try:
        return sha_bytes(path.read_bytes())
    except (OSError, ValueError) as exc:
        raise ActivationRefused(f"cannot hash {path}: {exc}") from exc


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def render_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def read_json(path: Path) -> dict[str, Any]:
    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in items:
            if key in result:
                raise ActivationRefused(f"duplicate JSON member: {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=pairs)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ActivationRefused(f"cannot read JSON: {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ActivationRefused(f"expected JSON object: {path}")
    return value


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ActivationRefused(message)


def _parse_utc_timestamp(value: Any, field: str) -> datetime:
    require(isinstance(value, str), f"{field} timestamp missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ActivationRefused(f"{field} timestamp invalid") from exc
    require(parsed.tzinfo is not None and parsed.utcoffset() == timedelta(0),
            f"{field} timestamp must be UTC")
    return parsed


def _activation_anchor_datetime() -> datetime:
    anchor = _parse_utc_timestamp(ACTIVATION_TIME_ANCHOR_UTC, "activation time anchor")
    require(anchor.isoformat() == ACTIVATION_TIME_ANCHOR_UTC, "activation time anchor is not canonical")
    return anchor


def write_new(path: Path, value: Any) -> None:
    write_new_bytes(path, render_json(value))


def write_new_bytes(path: Path, content: bytes) -> None:
    require(not path.exists(), f"append-only output already exists: {path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def off_peak_window(at: datetime, guard_seconds: int = 120) -> dict[str, Any]:
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


def hold_digest(quota: dict[str, Any]) -> str:
    rows = [{"holdId": hold_id, **hold} for hold_id, hold in sorted(quota.get("holds", {}).items())]
    return sha_bytes(canonical(rows))


def make_target_quota(old_quota: dict[str, Any]) -> dict[str, Any]:
    target = copy.deepcopy(old_quota)
    target["limitYuan"] = 10.0
    return target


def expected_transaction_id(source_sha: str, target_sha: str, generator_sha: str, unknown_digest: str) -> str:
    identity = {
        "sourceBytesSha256": source_sha,
        "targetBytesSha256": target_sha,
        "scopePlanSha256": SCOPE_PLAN_SHA256,
        "scopeAuthorizationSha256": SCOPE_AUTH_SHA256,
        "scopeBindingSha256": SCOPE_BINDING_SHA256,
        "unknownHoldDigest": unknown_digest,
        "generatorProgramSha256": generator_sha,
        "activationTimeAnchorUtc": ACTIVATION_TIME_ANCHOR_UTC,
    }
    return TRANSACTION_PREFIX + sha_bytes(canonical(identity))[:32]


def _scope_paths() -> dict[str, Path]:
    return {
        "plan": SCOPE_DIR / "plan.json",
        "authorization": SCOPE_DIR / "paid-scope10-authorization.json",
        "binding": SCOPE_DIR / "authorization-binding.json",
        "base": SCOPE_DIR / "base-snapshot.json",
        "staged": SCOPE_DIR / "staged-candidate-quota.json",
        "closedPlan": CLOSED_DIR / "plan.json",
        "closedState": CLOSED_DIR / "state.json",
        "closedLedger": CLOSED_DIR / "send-ledger.json",
        "closedQuota": OLD_QUOTA,
    }


def verify_inputs() -> dict[str, Any]:
    paths = _scope_paths()
    require(all(path.is_file() and not path.is_symlink() for path in paths.values()), "activation source missing")
    hashes = {name: sha(path) for name, path in paths.items()}
    require(hashes["plan"] == SCOPE_PLAN_SHA256, "scope plan SHA drift")
    require(hashes["authorization"] == SCOPE_AUTH_SHA256, "scope authorization SHA drift")
    require(hashes["binding"] == SCOPE_BINDING_SHA256, "scope binding SHA drift")
    require(hashes["base"] == SCOPE_BASE_SHA256, "scope base SHA drift")
    require(hashes["staged"] == SCOPE_STAGED_QUOTA_SHA256, "scope staged quota SHA drift")
    require(hashes["closedPlan"] == CLOSED_PLAN_SHA256, "old plan SHA drift")
    require(hashes["closedState"] == CLOSED_STATE_SHA256, "old state SHA drift")
    require(hashes["closedLedger"] == CLOSED_LEDGER_SHA256, "old ledger SHA drift")
    require(hashes["closedQuota"] == CLOSED_QUOTA_SHA256, "old quota source SHA drift")

    plan = read_json(paths["plan"])
    authorization = read_json(paths["authorization"])
    binding = read_json(paths["binding"])
    base = read_json(paths["base"])
    staged_scope_quota = read_json(paths["staged"])
    old_quota = read_json(paths["closedQuota"])
    old_state = read_json(paths["closedState"])
    old_ledger = read_json(paths["closedLedger"])

    require(plan.get("scope") == SCOPE10
            and plan.get("executionState") == "PREPARE_PREFLIGHT_AUDIT_ONLY"
            and plan.get("runNextExposed") is False,
            "paid scope identity or execution state drift")
    require(authorization.get("authorizationId") == AUTHORIZATION_ID
            and authorization.get("scope") == SCOPE10
            and authorization.get("executionEnabledInThisTask") is False
            and authorization.get("keyReadAllowedInThisTask") is False
            and authorization.get("providerCallsAllowedInThisTask") is False,
            "user authorization drift")
    require(binding.get("format") == "stage-d-paid-scope10-authorization-binding-v1"
            and binding.get("authorizationId") == AUTHORIZATION_ID
            and binding.get("appendOnly") is True
            and binding.get("executionEnabled") is False
            and binding.get("authorizationSha256") == SCOPE_AUTH_SHA256
            and binding.get("planSha256") == SCOPE_PLAN_SHA256
            and binding.get("baseSnapshotSha256") == SCOPE_BASE_SHA256
            and binding.get("stagedQuotaSha256") == SCOPE_STAGED_QUOTA_SHA256,
            "authorization binding drift")

    require(old_state.get("planSha256") == CLOSED_PLAN_SHA256
            and old_state.get("requestsStarted") == 76
            and old_state.get("terminal") == "INTERRUPTED_NO_REPLAY",
            "old interrupted state drift")
    require(old_ledger.get("planSha256") == CLOSED_PLAN_SHA256
            and len(old_ledger.get("calls", [])) == 76,
            "old ledger inheritance drift")
    unknown = [hold for hold in old_quota.get("holds", {}).values() if hold.get("status") == "UNKNOWN"]
    require(old_quota.get("limitYuan") == 1.0
            and old_quota.get("reservationYuan") == 0.1
            and old_quota.get("knownCalls") == 139
            and str(old_quota.get("knownCostYuan")) in ("0.29376746", "0.293767460")
            and len(unknown) == 7
            and len(unknown) == len(old_quota.get("holds", {})),
            "old quota identity or UNKNOWN holds drift")

    staged_compare = copy.deepcopy(staged_scope_quota)
    staged_compare["limitYuan"] = old_quota["limitYuan"]
    require(staged_compare == old_quota and staged_scope_quota.get("limitYuan") == 10.0,
            "approved staged quota is not an exact limit-only copy")
    unknown_digest = hold_digest(old_quota)
    require(unknown_digest == base.get("oldCandidateQuota", {}).get("unknownHoldIdentitySha256"),
            "old UNKNOWN hold digest drift")

    for relative, expected in base.get("sourceFiles", {}).items():
        source = ROOT / Path(relative)
        require(source.is_file() and not source.is_symlink() and sha(source) == expected,
                f"read-only source drift: {relative}")

    target = make_target_quota(old_quota)
    target_bytes = render_json(target)
    generator_sha = sha(HERE / "activation_workflow.py")
    transaction_id = expected_transaction_id(hashes["closedQuota"], sha_bytes(target_bytes), generator_sha, unknown_digest)
    known_cost = Decimal(str(old_quota["knownCostYuan"]))
    current_commitment = known_cost + Decimal("0.10") * Decimal(len(unknown))
    next_commitment = current_commitment + Decimal("0.10")
    require(next_commitment <= Decimal("10.00"), "next ¥0.10 reserve exceeds target ¥10 limit")
    return {
        "paths": paths,
        "hashes": hashes,
        "plan": plan,
        "authorization": authorization,
        "binding": binding,
        "base": base,
        "oldQuota": old_quota,
        "oldUnknownCount": len(unknown),
        "unknownDigest": unknown_digest,
        "targetQuota": target,
        "targetBytes": target_bytes,
        "targetSha256": sha_bytes(target_bytes),
        "generatorSha256": generator_sha,
        "transactionId": transaction_id,
        "currentCommitmentYuan": f"{current_commitment:.9f}",
        "nextCommitmentYuan": f"{next_commitment:.9f}",
    }


def _allowed_source_files() -> set[str]:
    return {"activation_workflow.py", "test_activation_workflow.py", "README.md"}


def _ensure_fresh_output() -> None:
    require(HERE.is_dir(), "activation directory missing")
    generated = {path.name for path in HERE.iterdir() if path.is_file() and path.name not in _allowed_source_files()}
    require(not generated, "activation output already exists; duplicate activation refused")


def _ensure_commit_slot() -> None:
    generated = {path.name for path in HERE.iterdir() if path.is_file() and path.name not in _allowed_source_files()
                 and path.name not in {"dry-run.json", "activate-preflight.json"}}
    require(not generated, "DUPLICATE_ACTIVATION")


def dry_run() -> dict[str, Any]:
    _ensure_fresh_output()
    context = verify_inputs()
    result = {
        "format": "stage-d-paid-scope10-quota-activation-dry-run-v1",
        "passed": True,
        "mode": "metadata-only; no files written except this report",
        "sourceQuotaBytesSha256": context["hashes"]["closedQuota"],
        "targetQuotaBytesSha256": context["targetSha256"],
        "scopePlanSha256": SCOPE_PLAN_SHA256,
        "scopeAuthorizationSha256": SCOPE_AUTH_SHA256,
        "scopeBindingSha256": SCOPE_BINDING_SHA256,
        "unknownHoldDigest": context["unknownDigest"],
        "unknownHoldCount": context["oldUnknownCount"],
        "migrationTransactionId": context["transactionId"],
        "activationTimeAnchorUtc": ACTIVATION_TIME_ANCHOR_UTC,
        "generatorProgramSha256": context["generatorSha256"],
        "sourceLimitYuan": SOURCE_LIMIT_YUAN,
        "targetLimitYuan": TARGET_LIMIT_YUAN,
        "reservationYuan": RESERVATION_YUAN,
        "currentCommitmentYuan": context["currentCommitmentYuan"],
        "nextReservationCommitmentYuan": context["nextCommitmentYuan"],
        "oldUnknownReleased": 0,
        "providerCalls": 0,
        "keyReads": 0,
        "oldFilesModified": False,
    }
    write_new(HERE / "dry-run.json", result)
    return result


def activate_preflight() -> dict[str, Any]:
    require((HERE / "dry-run.json").is_file(), "dry-run must run first")
    dry = read_json(HERE / "dry-run.json")
    require(dry.get("passed") is True, "dry-run failed")
    context = verify_inputs()
    samples = {
        "morningPeak": off_peak_window(datetime(2026, 11, 20, 2, 0, tzinfo=timezone.utc)),
        "morningBoundary": off_peak_window(datetime(2026, 11, 20, 0, 59, tzinfo=timezone.utc)),
        "afterMorningPeak": off_peak_window(datetime(2026, 11, 20, 4, 0, tzinfo=timezone.utc)),
        "weekend": off_peak_window(datetime(2026, 11, 21, 2, 0, tzinfo=timezone.utc)),
    }
    checks = {
        "scopePlanAuthBinding": True,
        "oldLedgerInheritance": context["hashes"]["closedLedger"] == CLOSED_LEDGER_SHA256,
        "oldUnknownPermanent": context["oldUnknownCount"] == 7,
        "targetQuotaOnlyLimitChange": context["targetQuota"]["limitYuan"] == 10.0,
        "reservationUnderTarget": Decimal(context["nextCommitmentYuan"]) <= Decimal("10.00"),
        "peakRejected": not samples["morningPeak"]["allowed"],
        "boundaryRejected": not samples["morningBoundary"]["allowed"],
        "offPeakAllowed": samples["afterMorningPeak"]["allowed"] and samples["weekend"]["allowed"],
        "commitSlotFresh": not any(path.name in {"staged-runtime-quota.json", "migration-proof.json", "execution-manifest.json", "activate-result.json"}
                                     for path in HERE.iterdir()),
    }
    result = {
        "format": "stage-d-paid-scope10-quota-activation-preflight-v1",
        "passed": all(checks.values()),
        "checks": checks,
        "peakCases": samples,
        "migrationTransactionId": context["transactionId"],
        "activationTimeAnchorUtc": ACTIVATION_TIME_ANCHOR_UTC,
        "sourceQuotaBytesSha256": context["hashes"]["closedQuota"],
        "targetQuotaBytesSha256": context["targetSha256"],
        "generatorProgramSha256": context["generatorSha256"],
        "oldUnknownReleased": 0,
        "providerCalls": 0,
        "keyReads": 0,
        "oldFilesModified": False,
        "executionEnabled": False,
    }
    write_new(HERE / "activate-preflight.json", result)
    return result


def verify_activation(output: Path = HERE) -> dict[str, Any]:
    """Verify a generated activation; no externally supplied proof is accepted."""

    proof_path = output / "migration-proof.json"
    target_path = output / "staged-runtime-quota.json"
    manifest_path = output / "execution-manifest.json"
    require(proof_path.is_file() and target_path.is_file() and manifest_path.is_file(), "activation artifacts missing")
    context = verify_inputs()
    anchor = _activation_anchor_datetime()
    proof = read_json(proof_path)
    manifest = read_json(manifest_path)
    target = read_json(target_path)
    target_hash = sha(target_path)
    proof_hash = sha(proof_path)
    require(proof.get("format") == PROOF_FORMAT
            and proof.get("generatedBy") == "activation_workflow.py"
            and proof.get("generatorProgramSha256") == context["generatorSha256"],
            "migration proof is not generated by the bound program")
    require(proof.get("sourceBytesSha256") == context["hashes"]["closedQuota"] == CLOSED_QUOTA_SHA256,
            "migration proof source bytes mismatch")
    require(proof.get("targetBytesSha256") == target_hash == context["targetSha256"],
            "migration proof target bytes mismatch")
    require(proof.get("scopePlanSha256") == SCOPE_PLAN_SHA256
            and proof.get("scopeAuthorizationSha256") == SCOPE_AUTH_SHA256
            and proof.get("scopeBindingSha256") == SCOPE_BINDING_SHA256,
            "migration proof scope binding mismatch")
    require(proof.get("unknownHoldDigest") == context["unknownDigest"]
            and proof.get("unknownHoldCount") == 7
            and proof.get("oldUnknownReleased") == 0,
            "migration proof UNKNOWN accounting mismatch")
    require(proof.get("migrationTransactionId") == context["transactionId"], "migration transaction mismatch")
    require(proof.get("activationTimeAnchorUtc") == ACTIVATION_TIME_ANCHOR_UTC,
            "migration proof activation time anchor mismatch")
    require(proof.get("sourceBudgetId") == context["oldQuota"].get("budgetId")
            and proof.get("sourcePricingIdentity") == context["oldQuota"].get("pricingIdentity")
            and proof.get("sourcePath") == SOURCE_QUOTA_RELATIVE_PATH
            and proof.get("targetPath") == TARGET_QUOTA_RELATIVE_PATH
            and proof.get("sourceLimitYuan") == SOURCE_LIMIT_YUAN
            and proof.get("targetLimitYuan") == TARGET_LIMIT_YUAN
            and proof.get("reservationYuan") == RESERVATION_YUAN
            and proof.get("productionApplied") is False,
            "migration proof quota identity mismatch")
    proof_generated_at = _parse_utc_timestamp(proof.get("generatedAtUtc"), "migration proof")
    require(proof_generated_at == anchor, "migration proof timestamp is not the fixed activation anchor")
    target_compare = copy.deepcopy(target)
    target_compare["limitYuan"] = context["oldQuota"]["limitYuan"]
    require(target_compare == context["oldQuota"] and target.get("limitYuan") == 10.0,
            "target runtime changed fields beyond limitYuan")
    require(manifest.get("format") == MANIFEST_FORMAT
            and manifest.get("activationState") == STAGED_ACTIVATION_STATE
            and manifest.get("executionEnabled") is True
            and manifest.get("scopePlanSha256") == SCOPE_PLAN_SHA256
            and manifest.get("scopeAuthorizationSha256") == SCOPE_AUTH_SHA256
            and manifest.get("scopeBindingSha256") == SCOPE_BINDING_SHA256
            and manifest.get("sourceQuotaSha256") == context["hashes"]["closedQuota"]
            and manifest.get("targetQuotaSha256") == target_hash
            and manifest.get("migrationProofSha256") == proof_hash
            and manifest.get("migrationTransactionId") == context["transactionId"]
            and manifest.get("generatorProgramSha256") == context["generatorSha256"]
            and manifest.get("activationTimeAnchorUtc") == ACTIVATION_TIME_ANCHOR_UTC
            and manifest.get("unknownHoldDigest") == context["unknownDigest"]
            and manifest.get("oldUnknownReleased") == 0
            and manifest.get("providerCalls") == 0
            and manifest.get("keyReads") == 0
            and manifest.get("oldFilesModified") is False
            and manifest.get("productionRunnerModified") is False
            and manifest.get("runtimeQuotaAppliedOnlyInNewDirectory") is True,
            "execution manifest or proof binding mismatch")
    manifest_generated_at = _parse_utc_timestamp(manifest.get("generatedAtUtc"), "execution manifest")
    off_peak = manifest.get("offPeak")
    require(isinstance(off_peak, dict), "execution manifest off-peak record missing")
    require(off_peak.get("allowed") is True
            and off_peak.get("reason") == "OFF_PEAK"
            and off_peak.get("guardSeconds") == OFF_PEAK_GUARD_SECONDS,
            "execution manifest off-peak semantics mismatch")
    off_peak_checked_at = _parse_utc_timestamp(off_peak.get("checkedAt"), "execution manifest off-peak")
    require(proof_generated_at == manifest_generated_at == off_peak_checked_at == anchor,
            "activation artifact timestamps are not bound")
    return {
        "proofSha256": proof_hash,
        "targetSha256": target_hash,
        "migrationTransactionId": context["transactionId"],
        "generatorProgramSha256": context["generatorSha256"],
        "unknownHoldDigest": context["unknownDigest"],
        "oldUnknownReleased": 0,
        "executionEnabled": True,
    }


def activate_commit(*, at: datetime | None = None) -> dict[str, Any]:
    require((HERE / "dry-run.json").is_file() and (HERE / "activate-preflight.json").is_file(),
            "dry-run and activate-preflight must run first")
    require(read_json(HERE / "dry-run.json").get("passed") is True
            and read_json(HERE / "activate-preflight.json").get("passed") is True,
            "activation preflight failed")
    context = verify_inputs()
    checked_at = at or _activation_anchor_datetime()
    window = off_peak_window(checked_at, OFF_PEAK_GUARD_SECONDS)
    require(window["allowed"], "OFF_PEAK_REQUIRED")
    _ensure_commit_slot()
    require(checked_at == _activation_anchor_datetime(), "ACTIVATION_TIME_ANCHOR_REQUIRED")

    target_path = HERE / "staged-runtime-quota.json"
    proof_path = HERE / "migration-proof.json"
    manifest_path = HERE / "execution-manifest.json"
    target_bytes = context["targetBytes"]
    generated_at = checked_at.isoformat()
    write_new_bytes(target_path, target_bytes)
    proof = {
        "format": PROOF_FORMAT,
        "generatedBy": "activation_workflow.py",
        "generatorProgramSha256": context["generatorSha256"],
        "generatedAtUtc": generated_at,
        "activationTimeAnchorUtc": ACTIVATION_TIME_ANCHOR_UTC,
        "migrationTransactionId": context["transactionId"],
        "sourcePath": SOURCE_QUOTA_RELATIVE_PATH,
        "sourceBytesSha256": context["hashes"]["closedQuota"],
        "targetPath": TARGET_QUOTA_RELATIVE_PATH,
        "targetBytesSha256": sha(target_path),
        "scopePlanSha256": SCOPE_PLAN_SHA256,
        "scopeAuthorizationSha256": SCOPE_AUTH_SHA256,
        "scopeBindingSha256": SCOPE_BINDING_SHA256,
        "unknownHoldDigest": context["unknownDigest"],
        "unknownHoldCount": context["oldUnknownCount"],
        "oldUnknownReleased": 0,
        "sourceBudgetId": context["oldQuota"]["budgetId"],
        "sourcePricingIdentity": context["oldQuota"]["pricingIdentity"],
        "sourceLimitYuan": SOURCE_LIMIT_YUAN,
        "targetLimitYuan": TARGET_LIMIT_YUAN,
        "reservationYuan": RESERVATION_YUAN,
        "productionApplied": False,
    }
    write_new(proof_path, proof)
    manifest = {
        "format": MANIFEST_FORMAT,
        "activationState": STAGED_ACTIVATION_STATE,
        "executionEnabled": True,
        "generatedAtUtc": generated_at,
        "activationTimeAnchorUtc": ACTIVATION_TIME_ANCHOR_UTC,
        "migrationTransactionId": context["transactionId"],
        "generatorProgramSha256": context["generatorSha256"],
        "scopePlanSha256": SCOPE_PLAN_SHA256,
        "scopeAuthorizationSha256": SCOPE_AUTH_SHA256,
        "scopeBindingSha256": SCOPE_BINDING_SHA256,
        "sourceQuotaSha256": context["hashes"]["closedQuota"],
        "targetQuotaSha256": sha(target_path),
        "migrationProofSha256": sha(proof_path),
        "unknownHoldDigest": context["unknownDigest"],
        "oldUnknownReleased": 0,
        "providerCalls": 0,
        "keyReads": 0,
        "oldFilesModified": False,
        "productionRunnerModified": False,
        "runtimeQuotaAppliedOnlyInNewDirectory": True,
        "offPeak": window,
    }
    write_new(manifest_path, manifest)
    verified = verify_activation(HERE)
    result = {
        "format": "stage-d-paid-scope10-quota-activation-commit-v1",
        "passed": True,
        "executionEnabled": True,
        "activationState": manifest["activationState"],
        "migrationTransactionId": verified["migrationTransactionId"],
        "generatorProgramSha256": verified["generatorProgramSha256"],
        "sourceQuotaSha256": context["hashes"]["closedQuota"],
        "targetQuotaSha256": verified["targetSha256"],
        "migrationProofSha256": verified["proofSha256"],
        "unknownHoldDigest": verified["unknownHoldDigest"],
        "oldUnknownReleased": 0,
        "providerCalls": 0,
        "keyReads": 0,
        "oldFilesModified": False,
        "productionRunnerModified": False,
        "offPeak": window,
        "note": "Only this new directory was written; executionEnabled does not authorize Key/provider access before independent review.",
    }
    write_new(HERE / "activate-result.json", result)
    return result


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("dry-run", "activate-preflight", "activate-commit"))
    args = parser.parse_args()
    try:
        action = {"dry-run": dry_run, "activate-preflight": activate_preflight, "activate-commit": activate_commit}[args.action]
        print(json.dumps(action(), ensure_ascii=False, indent=2))
    except ActivationRefused as exc:
        print(json.dumps({"passed": False, "refused": True, "reason": str(exc), "providerCalls": 0, "keyReads": 0}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
