"""A new, reviewable D registration; never resume the interrupted paid batch.

prepare/preflight/audit are free and do not resolve credentials. run-next requires
a separate direct-user authorization bound to this exact new plan, in addition
to the inherited independent review, preflight, safety, time and quota gates.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import uuid
from contextlib import contextmanager
from decimal import Decimal

from replan_durable_write import replace_with_retry
from replan_stable_proxy import StableModelProxy, BASE_URL as STABLE_PROXY_URL
from replan_quota_monitor import (QuotaMonitor, QuotaRefused, POLICY as QUOTA_POLICY,
                                 read as quota_read, validate_seed)

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "reports/replan-stage-d-20261119"
OLD_BATCH = BASE / "batch-1/revision-2"
FIRST_FREE_BATCH = BASE / "batch-1/reregistered-dev-1"
REJECTED_FREE_BATCH = FIRST_FREE_BATCH / "revision-2"
REJECTED_FREE_REVIEW = BASE / "independent-review/reregistered-dev-1/revision-2"
REJECTED_REV3_BATCH = FIRST_FREE_BATCH / "revision-3"
REJECTED_REV3_REVIEW = BASE / "independent-review/reregistered-dev-1/revision-3"
REJECTED_REV3_PLAN_SHA = "9b32def424104c383d8088cb538ec54698f9bda12bbbb45a3d6472b65db5d41c"
REJECTED_REV3_DECISION_SHA = "7997a901b684d5c3715cfdfca5258798413374dd428db2562c2923567a5aaa67"
REJECTED_REV3_MANIFEST_SHA = "49b7b39bd8aabf505a59ac9184b105e06a574da7c5a1fe1feac0ebfba6146eee"
BATCH = FIRST_FREE_BATCH / "revision-4"
REVIEW_ROOT = BASE / "independent-review/reregistered-dev-1/revision-4"
CANDIDATE = BASE / "reregistration-candidate"
AUTH_SCHEMA = CANDIDATE / "human-paid-authorization.schema.json"
AUTH_NAME = "human-paid-authorization.json"
SCOPE_DECISION = BASE / "post-interruption-feasibility-20261120/human-scope-decision.json"
SCOPE_PROPOSAL = BASE / "post-interruption-feasibility-20261120/proposal.json"
NEW_FORMAT = "replan-d-reregistered-c1-v1"
REGISTRATION = "batch-1/reregistered-dev-1/revision-4"
# Revision-4 is permanently closed by the recorded direct-user decision.  These
# identities are deliberately pinned in source: changing a mutable JSON field
# cannot reopen this registration or reach the old authorization/key path.
DECLINED_SCOPE_DECISION_SHA256 = "4e5b824568e7f2a87070b4390a24f6fa5f713c27afb17b68ee374adc8e509f87"
DECLINED_SCOPE_PROPOSAL_SHA256 = "0c72656803e63dce968d8d94edf3eb5ed191274bdfc304c6004fee2018422c0c"
DECLINED_SCOPE_SOURCE_PLAN_SHA256 = "da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe"
DECLINED_SCOPE_DECISION = "DECLINED_NO_ADDITIONAL_PAID_EVALUATION"
REJECTED_FREE_PLAN_SHA = "91c67f5a2484fd6fd0e63a1f86c09ebb8e20737f5a7a3d4c4850b9b63c9c17b4"
REJECTED_FREE_DECISION_SHA = "d1050ff782c6ab98e96dae5db29bc1b65d8f3aa13c5e378162dba74a97179242"
REJECTED_FREE_MANIFEST_SHA = "af434a921708d5acbf5a402f9c733ff2e43ce60fbb506ca629caa708ef5b10b4"
REJECTED_DRIVER_SHA = "fb80cc04eb939bb48ab3f3d8408a678fb92a363f882f8cbb7a9cb26a454f3e4c"
FIRST_FREE_PLAN_SHA = "c5ee4606b41f88f9933f69a423b10fa9eb2edab604e3460658194dc87d8d2c2f"
FIRST_FREE_MANIFEST_SHA = "b2f872984fdaf7e42aec9858bae51b6640136c8a21fe30cd487c2a6b2f9e7ce1"
OLD_PLAN_SHA = "de6c10f1365f53345a37d238efafd1c00afadb506bab75d102b36f050d601016"
OLD_CONTROLLER_SHA = "079ec048dc29348cc032e05b592dfbf2540f3b3480fe078c461a8a10e344e4b8"
PINNED = {
    "paid-interruption-manifest.json": "89c090e079a2293858289f84fa67f0e008561ec3aca9e065d9fb2a0db1b836eb",
    "independent-review/submission-2/interruption-audit/accounting-and-interruption.json":
        "9d76cc6818248003429478f4d332d5ae2a49627dda27040efc542146a0c64f91",
    "independent-review/submission-2/interruption-audit/source-evidence-manifest.json":
        "ffc00814f019c75d31b19e77b442b99649451764ef07b2c9228346d59739b4c7",
    "independent-review/submission-2/interruption-audit/git-byte-verification.json":
        "b0c935f3c208f7833c63ef62efc720a8233b7bc86e57620d93748b8e887eab1e",
}
LEGACY = dict(requestsStarted=68, applicationModelCalls=70, commonSendReservations=69,
              transportAttempts=68, knownUsageCalls=67, knownCostYuan="0.16312188",
              commonUnknownReservations=2, commonUnknownHeldYuan="0.20",
              commonCommittedYuan="0.36312188", candidateUnknownHolds=3,
              candidateUnknownHeldYuan="0.30", candidateCommittedYuan="0.46312188")
CUMULATIVE_REQUESTS = 970
CUMULATIVE_CALLS = 1940
CURRENT_REQUESTS = 902
CURRENT_CALLS = CUMULATIVE_CALLS - LEGACY["applicationModelCalls"]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


_old_source = ROOT / "scripts/run_replan_stage_d.py"
if sha(_old_source) != OLD_CONTROLLER_SHA:
    raise RuntimeError("Interrupted controller source drift; refusing to import")
_spec = importlib.util.spec_from_file_location("_replan_d_frozen_legacy", _old_source)
d = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(d)
Refused = d.Refused
_old_verify = d.verify
_old_tool_files = d.tool_files
_old_send_gate = d.SendGate
_old_real_key = d.real_key
_old_app_command = d.app_command
_old_application = d.application
_old_offline_preflight = d.offline_preflight
_old_http_json = d.http_json
_old_locked = d.locked
_old_preflight = d.preflight
_active_controller = None
_monitors = {}
_real_endpoints = set()
_owner_gate = {}


def require(condition, message):
    if not condition:
        raise Refused(message)


def owned(path):
    path = Path(path).resolve()
    require(path.is_relative_to(d.BATCH.resolve()), "New controller may only write its new batch directory")
    require(not path.is_relative_to(OLD_BATCH.resolve()), "Interrupted evidence is immutable")
    return path


def write_bytes(path, data):
    path = owned(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def raw_write(path, value, *, replace=False):
    path = owned(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    require(replace or not path.exists(), "Never overwrite evidence: " + path.name)
    compact = path.parent == d.BATCH.resolve() and path.name in ("state.json", "send-ledger.json")
    # Mutable current anchors use compact JSON; every immutable original
    # permit/completion/raw/rejection retains the frozen writer's format.
    data = (json.dumps(value, ensure_ascii=False, indent=None if compact else 2,
                       separators=(",", ":") if compact else None, allow_nan=False) + "\n").encode("utf-8")
    if not replace:
        write_bytes(path, data)
        return
    temp = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    write_bytes(temp, data)
    # Default 0.5s retry/backoff budget and 12 attempts only. No HTTP retry.
    # An individual synchronous os.replace cannot be forcibly time-bounded.
    replace_with_retry(temp, path)


def write(path, value, *, replace=False):
    path = owned(path)
    if replace and path.name in ("state.json", "send-ledger.json") and path.parent == d.BATCH.resolve():
        gate = _owner_gate.get(controller_identity())
        if path.name == "state.json" and gate is not None:
            return gate.persist_state(value)
        return quota_monitor().persist(path, value)
    return raw_write(path, value, replace=replace)


@contextmanager
def controller_locked():
    """One process owns all mutable observations, including audit/preflight."""
    global _active_controller
    path = d.BATCH.resolve()
    require(_active_controller is None, "Controller session cannot be reentered")
    with _old_locked():
        owner = object()
        _active_controller = (path, owner)
        try:
            yield
        finally:
            _owner_gate.pop(owner, None)
            for monitor in _monitors.values():
                monitor.invalidate_owner(owner)
            _active_controller = None


@contextmanager
def observation_owner():
    if controller_identity() is not None:
        yield  # Proxy callbacks belong to the live owning controller process.
    else:
        with controller_locked():
            yield


def controller_identity():
    return (_active_controller[1] if _active_controller is not None and _active_controller[0] == d.BATCH.resolve() else None)


def quota_monitor():
    path = d.BATCH.resolve()
    plan_sha = sha(path / "plan.json")
    key = (path, plan_sha)
    if key not in _monitors:
        _monitors[key] = QuotaMonitor(path, plan_sha, raw_write, observation_owner, controller_identity)
    return _monitors[key]


def bound_files(mapping):
    require(isinstance(mapping, dict) and mapping, "Missing immutable evidence manifest")
    for relative, expected in mapping.items():
        path = (ROOT / relative).resolve()
        require(path.is_relative_to(ROOT.resolve()) and path.is_file() and not path.is_symlink()
                and sha(path) == expected, "Immutable legacy evidence drift: " + relative)


def legacy_facts():
    """Read and verify the stopped public batch; no changes, no SQL/model calls."""
    for relative, expected in PINNED.items():
        require(sha(BASE / relative) == expected, "Independent interruption identity drift: " + relative)
    manifest = d.read(BASE / "paid-interruption-manifest.json")
    require(manifest.get("planSha256") == OLD_PLAN_SHA and len(manifest["files"]) == 401,
            "Stopped manifest identity/denominator drift")
    bound_files(manifest["files"])
    audit_base = BASE / "independent-review/submission-2/interruption-audit"
    source = d.read(audit_base / "source-evidence-manifest.json")
    require(source.get("planSha256") == OLD_PLAN_SHA and len(source["files"]) == 356,
            "Independent audit source manifest identity drift")
    bound_files(source["files"])
    git = d.read(audit_base / "git-byte-verification.json")
    require(git.get("allMatched") is True and git.get("filesVerifiedAgainstGitOriginalBytes") == 356,
            "Independent original-byte verification missing")
    old_plan = d.read(OLD_BATCH / "plan.json")
    require(sha(OLD_BATCH / "plan.json") == OLD_PLAN_SHA, "Stopped plan drift")
    _old_verify(old_plan, database=False)
    state = d.read(OLD_BATCH / "state.json")
    require(state == manifest["state"] and state["terminal"] == "INTERRUPTED_NO_REPLAY"
            and state["requestsStarted"] == 68 and set(state["rounds"]) == {"DEV_F"}
            and state["rounds"]["DEV_F"]["status"] == "INTERRUPTED"
            and state["rounds"]["DEV_F"]["completedAnswers"] == 68,
            "Legacy interrupted terminal/counters are not intact")
    audit = d.read(audit_base / "accounting-and-interruption.json")
    expected = dict(planSha256=OLD_PLAN_SHA, terminal="INTERRUPTED_NO_REPLAY", plannedQuestions=100,
                    questionsStarted=68, httpAnswersPreserved=68, applicationModelCalls=70,
                    applicationUnknownUsageCalls=3, commonSendReservations=69,
                    transportAttemptResults=68, knownUsageCalls=67, knownCostYuan="0.16312188",
                    commonUnknownReservations=2, commonUnknownHeldYuan="0.20",
                    commonConfiguredCommittedYuan="0.36312188", candidateUnknownHolds=3,
                    candidateHeldYuan="0.30", candidateConfiguredCommittedYuan="0.46312188",
                    full100AccuracyOrP95Available=False, roundSafetyAcceptedForPromotion=False,
                    auditProviderCalls=0)
    require(all(audit.get(k) == v for k, v in expected.items()), "Independent interruption accounting changed")
    # Detailed paid answers are sealed, hash-checked above, never reparsed here.
    # The independent aggregate audit is the source of the retained cost/counts.
    known_cost = Decimal(audit["knownCostYuan"])
    quota = d.read(OLD_BATCH / "candidate-cost-quota.json")
    require(quota["knownCalls"] == 67 and Decimal(str(quota["knownCostYuan"])) == known_cost
            and Decimal(str(quota["limitYuan"])) == d.LIMIT
            and Decimal(str(quota["reservationYuan"])) == d.RESERVE
            and len(quota["holds"]) == 3 and all(h.get("status") == "UNKNOWN" for h in quota["holds"].values())
            and quota.get("reservationBreached") is False, "Legacy candidate quota drift")
    return {**LEGACY, "planSha256": OLD_PLAN_SHA,
            "manifestSha256": PINNED["paid-interruption-manifest.json"],
            "ledgerSha256": sha(OLD_BATCH / "send-ledger.json"),
            "candidateQuotaSha256": sha(OLD_BATCH / "candidate-cost-quota.json"),
            "interruptionAuditSha256": PINNED["independent-review/submission-2/interruption-audit/accounting-and-interruption.json"],
            "candidateBudgetId": quota["budgetId"], "candidatePricingIdentity": quota["pricingIdentity"]}


def check_candidate_quota():
    """Always require the initialized independent history, never seed-only fallback."""
    return quota_monitor().observe("VERIFY")


def rejected_free_facts():
    """Preserve the rejected free package without re-verifying its old driver."""
    decision_path = REJECTED_FREE_REVIEW / "decision.json"
    manifest_path = REJECTED_FREE_REVIEW / "source-evidence-manifest.json"
    require(sha(decision_path) == REJECTED_FREE_DECISION_SHA and sha(manifest_path) == REJECTED_FREE_MANIFEST_SHA,
            "Rejected revision-2 independent evidence changed")
    decision = d.read(decision_path)
    require(decision.get("accepted") is False and decision.get("planSha256") == REJECTED_FREE_PLAN_SHA,
            "Revision-2 is not independently rejected")
    manifest = d.read(manifest_path); bound_files(manifest["files"])
    protected = d.read(REJECTED_FREE_REVIEW / "protected-state-final-verification.json")["protectedFiles"]
    for relative, expected in protected.items():
        if relative.startswith(REJECTED_FREE_BATCH.relative_to(ROOT).as_posix() + "/"):
            require(sha(ROOT / relative) == expected, "Rejected free package bytes changed")
    rejected_plan = d.read(REJECTED_FREE_BATCH / "plan.json")
    prefix = REJECTED_FREE_BATCH.relative_to(ROOT).as_posix() + "/"
    original_files = {relative: expected for relative, expected in rejected_plan["files"].items() if relative.startswith(prefix)}
    original_files.update(d.read(REJECTED_FREE_BATCH / "preflight.json")["evidence"])
    original_files.update({relative: expected for relative, expected in protected.items() if relative.startswith(prefix)})
    bound_files(original_files)
    require({p.relative_to(ROOT).as_posix() for p in REJECTED_FREE_BATCH.rglob("*") if p.is_file()} == set(original_files),
            "Rejected free package file set changed")
    # The rejected plan intentionally binds the earlier source bytes. Its exact
    # Git source identity is recorded, not rewritten to match this new revision.
    require(protected.get("scripts/run_replan_stage_d_reregistered.py") == REJECTED_DRIVER_SHA,
            "Rejected driver identity lost")
    state = d.read(REJECTED_FREE_BATCH / "state.json")
    ledger = d.read(REJECTED_FREE_BATCH / "send-ledger.json")
    require(sha(REJECTED_FREE_BATCH / "plan.json") == REJECTED_FREE_PLAN_SHA
            and state.get("requestsStarted") == 0 and state.get("rounds") == {} and state.get("terminal") is None
            and not ledger["calls"] and not ledger["rejections"] and not ledger["breaches"]
            and not (REJECTED_FREE_BATCH / AUTH_NAME).exists(), "Rejected free revision acquired runtime/paid state")
    return dict(planSha256=REJECTED_FREE_PLAN_SHA, decisionSha256=REJECTED_FREE_DECISION_SHA,
                manifestSha256=REJECTED_FREE_MANIFEST_SHA, driverSha256=REJECTED_DRIVER_SHA,
                driverGitCommit="02364ab073360c352d94c698f69d59b0496fd5c1", requestsStarted=0, addedProviderCalls=0)


def rejected_revision3_facts():
    decision_path = REJECTED_REV3_REVIEW / "decision.json"
    manifest_path = REJECTED_REV3_REVIEW / "source-evidence-manifest.json"
    require(sha(decision_path) == REJECTED_REV3_DECISION_SHA and sha(manifest_path) == REJECTED_REV3_MANIFEST_SHA,
            "Rejected revision-3 independent identity changed")
    decision = d.read(decision_path)
    require(decision.get("accepted") is False and decision.get("planSha256") == REJECTED_REV3_PLAN_SHA
            and decision.get("implementationCommit") == "b178a4b2fe454cf5b5df90e4f62de665fce1fe6c"
            and decision.get("sourceDriverSha256") == "7fb43d4450b5db061ac544694ed5a7b0207399dd18b2d0595d51920b2a6da277"
            and decision.get("sourceHelperSha256") == "20871566cf63cee846e5e7813f23d267fe22e3340f458258cfd7767286d2db1a",
            "Revision-3 rejection or original Git source identity changed")
    bound_files(d.read(manifest_path)["files"])
    original = d.read(REJECTED_REV3_REVIEW / "actual-batch-before.json")
    after = d.read(REJECTED_REV3_REVIEW / "actual-batch-after.json")
    require(original["files"] == after["files"] and len(original["files"]) == 380,
            "Revision-3 sealed actual package denominator changed")
    bound_files(original["files"])
    require({p.relative_to(ROOT).as_posix() for p in REJECTED_REV3_BATCH.rglob("*") if p.is_file()} == set(original["files"]),
            "Rejected revision-3 actual package file set changed")
    state = d.read(REJECTED_REV3_BATCH / "state.json")
    ledger = d.read(REJECTED_REV3_BATCH / "send-ledger.json")
    require(sha(REJECTED_REV3_BATCH / "plan.json") == REJECTED_REV3_PLAN_SHA
            and state.get("planSha256") == REJECTED_REV3_PLAN_SHA and state.get("requestsStarted") == 0
            and state.get("rounds") == {} and state.get("terminal") is None
            and ledger.get("planSha256") == REJECTED_REV3_PLAN_SHA
            and not ledger["calls"] and not ledger["rejections"] and not ledger["breaches"]
            and not (REJECTED_REV3_BATCH / AUTH_NAME).exists(), "Rejected revision-3 acquired paid/runtime state")
    return dict(planSha256=REJECTED_REV3_PLAN_SHA, decisionSha256=REJECTED_REV3_DECISION_SHA,
                manifestSha256=REJECTED_REV3_MANIFEST_SHA, actualFiles=380, requestsStarted=0, addedProviderCalls=0,
                sourceGitCommit=decision["implementationCommit"], sourceDriverSha256=decision["sourceDriverSha256"],
                sourceHelperSha256=decision["sourceHelperSha256"])


def first_free_facts():
    """The failed free draft is superseded without changing or replaying it."""
    manifest_path = BASE / "reregistration-first-free-failure.json"
    require(sha(manifest_path) == FIRST_FREE_MANIFEST_SHA, "First free failure manifest drift")
    manifest = d.read(manifest_path)
    require(manifest.get("planSha256") == FIRST_FREE_PLAN_SHA and manifest.get("addedProviderCalls") == 0
            and manifest.get("actualHumanAuthorizationAbsent") is True and len(manifest["files"]) == 33,
            "First free failure identity changed")
    bound_files(manifest["files"])
    require(sha(FIRST_FREE_BATCH / "plan.json") == FIRST_FREE_PLAN_SHA, "First free plan drift")
    state = d.read(FIRST_FREE_BATCH / "state.json")
    ledger = d.read(FIRST_FREE_BATCH / "send-ledger.json")
    require(state == manifest["state"] and state.get("requestsStarted") == 0 and state.get("rounds") == {}
            and state.get("terminal") is None and not ledger["calls"] and not ledger["rejections"]
            and not ledger["breaches"] and ledger.get("planSha256") == FIRST_FREE_PLAN_SHA
            and not (FIRST_FREE_BATCH / AUTH_NAME).exists() and not (FIRST_FREE_BATCH / "preflight.json").exists(),
            "Only the unchanged never-paid first free draft may be superseded")
    return dict(planSha256=FIRST_FREE_PLAN_SHA, manifestSha256=FIRST_FREE_MANIFEST_SHA,
                addedProviderCalls=0, requestsStarted=0, reason="F failed before readiness: inherited quota identity included random proxy port")


def tool_files():
    paths = _old_tool_files() + [Path(__file__), ROOT / "scripts/replan_durable_write.py", ROOT / "scripts/replan_quota_monitor.py",
        ROOT / "scripts/replan_stable_proxy.py", BASE / "reregistration-first-free-failure.json",
        FIRST_FREE_BATCH / "plan.json", FIRST_FREE_BATCH / "state.json", FIRST_FREE_BATCH / "send-ledger.json",
        ROOT / "docs/REPLAN_STAGE_D_INTERRUPTION_20261119.md", AUTH_SCHEMA,
        BASE / "paid-interruption-manifest.json", OLD_BATCH / "plan.json", OLD_BATCH / "state.json",
        OLD_BATCH / "send-ledger.json", OLD_BATCH / "candidate-cost-quota.json"]
    paths.extend(REJECTED_FREE_REVIEW / name for name in ("decision.json", "source-evidence-manifest.json", "protected-state-final-verification.json"))
    paths.extend(p for p in REJECTED_FREE_BATCH.rglob("*") if p.is_file())
    paths.extend(REJECTED_REV3_REVIEW / name for name in ("decision.json", "source-evidence-manifest.json", "actual-batch-before.json", "actual-batch-after.json"))
    paths.extend(p for p in REJECTED_REV3_BATCH.rglob("*") if p.is_file())
    paths.extend(BASE / p for p in PINNED)
    return sorted(set(paths))


def prepare():
    require(not d.BATCH.exists(), "New registration directory already exists; never reset, resume or overwrite")
    legacy = legacy_facts()  # Verify old terminal, audit and every original SHA before creating anything.
    superseded_free = first_free_facts()
    rejected_free = rejected_free_facts()
    rejected_rev3 = rejected_revision3_facts()
    for side in d.SYSTEMS.values():
        require(sha(ROOT / side["jar"]) == side["sha256"], "Frozen JAR drift")
    decision = d.read(ROOT / "reports/replan-stage-c-20261119/independent-review/c1-submission-1/decision.json")
    require(decision.get("decision") == "ACCEPT_LOCAL_C1", "C1 is not locally accepted")
    require(AUTH_SCHEMA.is_file(), "Human authorization schema missing")
    d.BATCH.mkdir(parents=True)
    cp = d.compile_bridge()
    # Reuse already audited complete references byte-for-byte; no redundant Gold execution.
    for name in ("dev", "formal", "targets"):
        write_bytes(d.BATCH / (name + "-reference.json"), (OLD_BATCH / (name + "-reference.json")).read_bytes())
    write_bytes(d.BATCH / "candidate-cost-quota.json", (OLD_BATCH / "candidate-cost-quota.json").read_bytes())
    write_bytes(d.BATCH / "candidate-quota-initial-snapshot.json", (OLD_BATCH / "candidate-cost-quota.json").read_bytes())
    write(d.BATCH / "candidate-quota-genesis-policy.json", {**QUOTA_POLICY,
          "seedSha256": sha(d.BATCH / "candidate-quota-initial-snapshot.json")})
    database = d.database_snapshot()
    require(database == old_database() == d.read(ROOT / "reports/replan-stage-b-20261119/b0-identity.json")["database"],
            "Database differs from accepted B0 or stopped D plan")
    files = tool_files() + d.dataset_files() + [d.BATCH / (n + "-reference.json") for n in ("dev", "formal", "targets")]
    files.append(d.BATCH / "candidate-quota-initial-snapshot.json")
    files.append(d.BATCH / "candidate-quota-genesis-policy.json")
    files.extend(p for p in (d.BATCH / "tool-runtime").rglob("*") if p.is_file())
    plan = dict(format=NEW_FORMAT, createdAt=d.stamp(), systems=d.SYSTEMS, order=list(d.ORDER),
        registration=REGISTRATION, freeRevision=4, supersededFreeDraft=superseded_free, rejectedFreeRevision2=rejected_free,
        rejectedFreeRevision3=rejected_rev3, quotaObservationPolicy=copy.deepcopy(QUOTA_POLICY),
        statusAtRegistration="DRAFT_REQUIRES_DIRECT_USER_PAID_AUTHORIZATION",
        selectedCandidate="C1 only; unchanged candidate and gates; C2 not admitted", legacy=legacy,
        limits=dict(requests=CURRENT_REQUESTS, calls=CURRENT_CALLS,
            cumulativeRequests=CUMULATIVE_REQUESTS, cumulativeCalls=CUMULATIVE_CALLS,
            legacyRequestsCharged=68, legacyApplicationCallsCharged=70,
            selectedNewRouteMaximumCalls=1804, globalPlanRequests=1002, globalPlanCalls=2004,
            callsPerRequest=2, requestMs=15000, sdkAttempts=1, roundGuardSeconds=d.ROUND_GUARD,
            sendGuardSeconds=d.SEND_GUARD, totalYuan=str(d.LIMIT), reservationYuan=str(d.RESERVE),
            maxPayloadBytes=d.MAX_PAYLOAD, maxCompletionTokens=8192, inputFramingEstimate=1024),
        durableWrite=dict(helper="scripts/replan_durable_write.py", defaultRetryBackoffBudgetSeconds=.5,
                          maximumReplaceAttempts=12, sameBytesOnly=True, httpOrModelRetry=False,
                          synchronousReplaceAbsoluteWallBoundGuaranteed=False),
        humanAuthorization=dict(file=AUTH_NAME, schema=AUTH_SCHEMA.relative_to(ROOT).as_posix(),
            requiredBeforeRealKey=True, engineeringReviewIsNotHumanPaidAuthorization=True),
        stableProxy=dict(baseUrl=STABLE_PROXY_URL, exclusiveWindowsAddress=True, reuseAddress=False,
            inheritedQuotaPricingIdentityPreserved=True, randomPortFallback=False,
            unchangedHandlerTransportAndShutdown=True),
        commonScorer="unchanged frozen A scorer on actual saved HTTP body rows; never candidate SQL replay",
        classpath=cp, database=database, files={p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(files))},
        unseen="LOCKED; independent custodian only after every original formal/safety gate passes",
        providerCallsAddedByPreparation=0, priorBatchDisposition="INTERRUPTED_NO_REPLAY; no old question is resumed")
    write(d.BATCH / "plan.json", plan)
    plan_sha = sha(d.BATCH / "plan.json")
    write(d.BATCH / "state.json", dict(format=NEW_FORMAT, planSha256=plan_sha, requestsStarted=0, rounds={}, terminal=None))
    write(d.BATCH / "send-ledger.json", dict(planSha256=plan_sha, legacyManifestSha256=legacy["manifestSha256"],
                                           calls=[], rejections=[], breaches=[]))
    quota_monitor().initialize()
    print("New free registration prepared; direct human paid authorization is still absent", flush=True)


def old_database():
    return d.read(OLD_BATCH / "plan.json")["database"]


def verify(plan, *, database=True):
    require(plan.get("format") == NEW_FORMAT and plan.get("registration") == REGISTRATION,
            "Wrong new registration identity")
    require(plan.get("legacy") == legacy_facts(), "New plan does not preserve full legacy accounting")
    require(plan.get("supersededFreeDraft") == first_free_facts(), "First free failure was not preserved")
    require(plan.get("rejectedFreeRevision2") == rejected_free_facts(), "Rejected revision-2 was not preserved")
    require(plan.get("rejectedFreeRevision3") == rejected_revision3_facts()
            and plan.get("quotaObservationPolicy") == QUOTA_POLICY, "Revision-3 or observation policy was not preserved")
    limits = plan.get("limits", {})
    for key, value in dict(requests=902, calls=1870, cumulativeRequests=970, cumulativeCalls=1940,
                           legacyRequestsCharged=68, legacyApplicationCallsCharged=70,
                           totalYuan="1.00", reservationYuan="0.10", requestMs=15000, sdkAttempts=1,
                           callsPerRequest=2, roundGuardSeconds=1740, sendGuardSeconds=120).items():
        require(limits.get(key) == value, "New plan limit changed: " + key)
    require(plan.get("systems") == d.SYSTEMS and plan.get("order") == list(d.ORDER), "Candidate/round route changed")
    require(all(p.relative_to(ROOT).as_posix() in plan["files"] for p in tool_files()), "New helper/source/schema not bound in plan")
    _old_verify(plan, database=database)
    check_candidate_quota()


def require_human_authorization():
    """Reject the closed revision before touching authorization or credentials.

    The current registration is sealed by the exact decision/proposal/plan
    identities below.  A future reopening therefore needs a new registration,
    plan, direct authorization and independent review; editing this decision
    file can never turn revision-4 back into an executable paid scope.
    """
    require(SCOPE_DECISION.is_file() and not SCOPE_DECISION.is_symlink(),
            "Current paid-scope decision is missing; refuse paid execution")
    require(sha(SCOPE_DECISION) == DECLINED_SCOPE_DECISION_SHA256,
            "Current paid-scope decision identity changed; refuse paid execution")
    require(SCOPE_PROPOSAL.is_file() and not SCOPE_PROPOSAL.is_symlink(),
            "Bound paid-scope proposal is missing; refuse paid execution")
    require(sha(SCOPE_PROPOSAL) == DECLINED_SCOPE_PROPOSAL_SHA256,
            "Bound paid-scope proposal identity changed; refuse paid execution")
    plan_path = d.BATCH / "plan.json"
    require(plan_path.is_file() and not plan_path.is_symlink()
            and sha(plan_path) == DECLINED_SCOPE_SOURCE_PLAN_SHA256,
            "Bound revision-4 source plan identity changed; refuse paid execution")
    scope_decision = d.read(SCOPE_DECISION)
    require(scope_decision.get("proposalSha256") == DECLINED_SCOPE_PROPOSAL_SHA256,
            "Scope decision is not bound to the recorded proposal")
    require(scope_decision.get("decision") == DECLINED_SCOPE_DECISION
            and scope_decision.get("newPaidAuthorizationGranted") is False,
            "Direct user decision declines additional paid evaluation")
    # This revision is closed, even though the historical authorization file
    # remains on disk as immutable evidence.  Never fall through to it.
    raise Refused("Revision-4 paid scope is permanently closed by direct-user decision")


def guarded_real_key():
    seal = require_human_authorization()  # Always before the inherited environment/registry read.
    path = d.BATCH / "human-authorization-consumed.json"
    if not path.exists():
        write(path, seal)
    return _old_real_key()


def app_command(side, port, base_url, out, *, offline=False):
    require(base_url == STABLE_PROXY_URL, "Proxy address differs from preserved candidate quota pricing identity")
    if offline and side == "F":
        seed = d.BATCH / "candidate-quota-initial-snapshot.json"
        original = OLD_BATCH / "candidate-cost-quota.json"
        require(sha(seed) == sha(original), "Offline candidate quota seed drift")
        write_bytes(Path(out) / "offline-cost-quota.json", seed.read_bytes())
        write(Path(out) / "inherited-quota-before-load.json", dict(
            scope="offline synthetic only; inherited historical cost is not new cost",
            seedSha256=sha(seed), offlineCopySha256=sha(Path(out) / "offline-cost-quota.json"),
            legacyKnownCostYuan=LEGACY["knownCostYuan"], legacyUnknownHeldYuan="0.30", legacyUnknownHolds=3))
    return _old_app_command(side, port, base_url, out, offline=offline)


@contextmanager
def application(side, proxy, out, *, offline=False):
    reached_ready = False
    body_completed = False
    endpoint_url = None
    if not offline:
        quota_monitor().observe("APPLICATION_ENTER", update=lambda k, q: k.update(runtimeStarted=True, activeSide=side),
                                evidence=dict(side=side, output=Path(out).relative_to(d.BATCH).as_posix()))
    try:
        with _old_application(side, proxy, out, offline=offline) as endpoint:
            reached_ready = True
            if not offline:
                endpoint_url = endpoint[0]
                _real_endpoints.add(endpoint_url)
                quota_monitor().observe("APPLICATION_READY", evidence=dict(side=side))
            yield endpoint
            body_completed = True
    finally:
        if endpoint_url is not None:
            _real_endpoints.discard(endpoint_url)
        if not offline:
            quota_monitor().observe("APPLICATION_EXIT", update=lambda k, q: k.update(activeSide=None),
                                    evidence=dict(side=side, reachedReady=reached_ready, bodyCompleted=body_completed))
        if offline and side == "F" and (Path(out) / "offline-cost-quota.json").is_file():
            seed = d.read(d.BATCH / "candidate-quota-initial-snapshot.json")
            restored = d.read(Path(out) / "offline-cost-quota.json")
            require(all(restored.get(k) == seed[k] for k in ("budgetId", "pricingIdentity", "limitYuan", "reservationYuan")),
                    "Actual offline JAR changed inherited quota identity")
            require(all(restored["holds"].get(k) == v for k, v in seed["holds"].items())
                    and restored["knownCalls"] >= seed["knownCalls"]
                    and Decimal(str(restored["knownCostYuan"])) >= Decimal(str(seed["knownCostYuan"])),
                    "Actual offline JAR lost legacy UNKNOWN/known quota")
            write(Path(out) / "inherited-quota-after-load.json", dict(passed=reached_ready and body_completed,
                applicationReachedReady=reached_ready, applicationBodyCompleted=body_completed,
                inheritedQuotaDataPreserved=True,
                budgetId=restored["budgetId"], pricingIdentity=restored["pricingIdentity"], legacyUnknownHoldsPreserved=3,
                legacyKnownCostYuan=LEGACY["knownCostYuan"], legacyUnknownHeldYuan="0.30",
                afterKnownCostYuan=str(restored["knownCostYuan"]), afterKnownCalls=restored["knownCalls"],
                scope=("Actual JAR reached readiness and local preflight body completed; no additional external charge"
                       if reached_ready and body_completed else "Seed/UNKNOWN data preserved; application load or preflight not verified")))


def http_json(url, **kwargs):
    real = url.endswith("/api/ask") and url[:-len("/api/ask")] in _real_endpoints
    if not real:
        return _old_http_json(url, **kwargs)
    response = None
    try:
        status, response = _old_http_json(url, **kwargs)
        return status, response
    finally:
        gate = _owner_gate.get(controller_identity())
        require(gate is not None and gate.current_fence is not None, "Real HTTP return has no owning admitted question")
        fence = copy.deepcopy(gate.current_fence)
        gate.end()  # Close new sends immediately; late completions retain their token/fence.
        evidence = kwargs.get("evidence")
        record = dict(returnedBodyAvailable=response is not None)
        if evidence:
            record.update(httpEvidence=Path(evidence).relative_to(d.BATCH).as_posix(),
                          questionId=Path(evidence).stem, httpEvidenceSha256=sha(evidence),
                          responseBodySha256=sha(Path(evidence).with_suffix(".body.bin")))
        if response:
            record["budget"] = response.get("budget")
        quota_monitor().finish_http(fence, response=response, evidence=record)


def offline_preflight(plan, output):
    with fixed_proxy_runtime():
        result = _old_offline_preflight(plan, output)
    result.update(scope="This free preflight adds zero external calls/cost; legacy paid history remains charged separately",
        addedExternalProviderCalls=0, addedProviderCostYuan="0", legacyAccounting=copy.deepcopy(LEGACY),
        syntheticUsageNotAddedToHistoricalRealBill=True)
    write(Path(output) / "reregistration-accounting-scope.json", dict(
        addedExternalProviderCalls=0, addedProviderCostYuan="0", legacyAccounting=LEGACY,
        legacyCommonQuotaAndCandidateQuotaAreSeparate=True, offlineCandidateInheritedQuotaVerified=True))
    return result


def bound_round_items(round_name):
    dataset = "dev" if round_name == "DEV_F" else "targets" if round_name == "TARGETS" else "formal"
    reference = d.BATCH / (dataset + "-reference.json")
    plan = d.read(d.BATCH / "plan.json")
    require(plan.get("files", {}).get(reference.relative_to(ROOT).as_posix()) == sha(reference),
            "Question admission reference is not bound to the exact current plan")
    return d.read(reference)["items"]


class SendGate(_old_send_gate):
    def __init__(self, path, plan_sha, round_name, **kwargs):
        self.legacy = copy.deepcopy(LEGACY)
        self.real = not round_name.startswith("OFFLINE_")
        self._stage = None
        self.current_fence = None
        self._admitted_fence = None
        self._admission_used = False
        self.owner = controller_identity()
        if self.real:
            with quota_monitor().serial():
                require(self.owner is not None, "Real gate requires an owning controller session")
                super().__init__(path, plan_sha, round_name, **kwargs)
                quota_monitor().open_round(round_name)
                require(self.owner not in _owner_gate, "Only one real gate may belong to a controller owner")
                _owner_gate[self.owner] = self
                state = d.read(d.BATCH / "state.json")
                self.requests_base = state["requestsStarted"]
                self.items = bound_round_items(round_name)
        else:
            super().__init__(path, plan_sha, round_name, **kwargs)
        if self.path.resolve() == (d.BATCH / "send-ledger.json").resolve():
            require(self.data.get("legacyManifestSha256") == PINNED["paid-interruption-manifest.json"],
                    "New send ledger is not bound to immutable legacy accounting")

    def persist_state(self, value):
        prior = d.read(d.BATCH / "state.json")
        require(value.get("requestsStarted") in (prior["requestsStarted"], prior["requestsStarted"] + 1),
                "Question counter must remain fixed or admit exactly one request")
        if value.get("requestsStarted") == prior["requestsStarted"] + 1:
            require(self.real and self.owner is controller_identity() and self.current is None
                    and value.get("terminal") is None and value.get("rounds", {}).get(self.round, {}).get("status") == "RUNNING"
                    and set(value["rounds"]) == set(prior["rounds"]), "Question increment has no valid owning round")
            index = value["requestsStarted"] - self.requests_base - 1
            require(0 <= index < len(self.items), "Question admission index outside fixed reference")
            item = self.items[index]
            fence = dict(planSha256=self.plan_sha, round=self.round, ordinal=value["requestsStarted"],
                         questionId=item["id"], questionSha256=hashlib.sha256(item["question"].strip().encode("utf-8")).hexdigest())
            result = quota_monitor().admit(value, fence)
            self._admitted_fence = fence
            self._admission_used = False
            return result
        return quota_monitor().persist(d.BATCH / "state.json", value)

    def save(self):
        if self._stage is not None:
            require(self.real and self.owner is controller_identity()
                    and self._stage["threadId"] == d.threading.get_ident(), "Ledger staging belongs to another callback owner/thread")
            # The monitor->gate locks and thread identity cover this entire
            # callback. No nested data is mutated after inherited save returns
            # and before immediate validation/hash/persistence completes.
            self._stage["ledger"] = self.data
            return
        return super().save()

    def begin(self, item, deadline):
        if not self.real:
            return super().begin(item, deadline)
        with quota_monitor().serial():
            fence = self._admitted_fence
            require(self.owner is controller_identity() and fence is not None and not self._admission_used
                    and fence["questionId"] == item["id"]
                    and fence["questionSha256"] == hashlib.sha256(item["question"].strip().encode("utf-8")).hexdigest(),
                    "Question begin does not match its durable admission fence")
            result = super().begin(item, deadline)
            self.current_fence = copy.deepcopy(fence)
            self._admission_used = True
            return result

    def end(self):
        if not self.real:
            return super().end()
        with quota_monitor().serial():
            super().end()
            self.current_fence = None

    def before(self, payload, raw):
        if not self.real:
            return super().before(payload, raw)
        # Uniform lock order: monitor transaction first, then inherited gate lock.
        with quota_monitor().serial():
            current = self.current or {}
            attempt = 1 + sum(c["round"] == self.round and c["questionId"] == current.get("id") for c in self.data["calls"])
            with quota_monitor().callback("BEFORE", self.data, fence=self.current_fence, payload=payload, attempt=attempt) as stage:
                stage["threadId"] = d.threading.get_ident()
                self._stage = stage
                try:
                    permit = super().before(payload, raw)
                    stage["permit"] = permit
                finally:
                    self._stage = None
            return permit

    def after(self, permit, result):
        if not self.real:
            return super().after(permit, result)
        with quota_monitor().serial():
            with quota_monitor().callback("AFTER", self.data, permit=permit) as stage:
                stage["threadId"] = d.threading.get_ident()
                self._stage = stage
                try:
                    super().after(permit, result)
                finally:
                    self._stage = None

    def committed(self):
        return Decimal(self.legacy["commonCommittedYuan"]) + super().committed()

    def summary(self, round_name=None, reconciliation=None):
        current = super().summary(round_name, reconciliation)
        all_current = super().summary()
        if self.round.startswith("OFFLINE_"):
            return {**current, "currentAccountingScope": "SYNTHETIC_LOCAL_FIXTURE_USAGE_ONLY",
                "syntheticCurrentAccounting": current, "legacyAccounting": copy.deepcopy(self.legacy),
                "simulatedConfiguredCommitmentYuan": str(self.committed()),
                "addedExternalProviderCalls": 0, "addedRealProviderCostYuan": "0",
                "syntheticUsageNotAddedToHistoricalRealBill": True,
                "candidateQuotaIsSeparateNotSummedAsBill": True}
        cumulative = dict(sendReservations=69 + all_current["sendReservations"],
            transportAttempts=68 + all_current["transportAttempts"],
            knownUsageCalls=67 + all_current["knownUsageCalls"],
            unknownUsageCalls=2 + all_current["unknownUsageCalls"],
            knownCostYuan=str(Decimal(LEGACY["knownCostYuan"]) + Decimal(all_current["knownCostYuan"])),
            unknownHeldYuan=str(Decimal("0.20") + Decimal(all_current["unknownHeldYuan"])),
            configuredCommittedYuan=str(self.committed()),
            applicationModelCallsLegacyCharged=70, applicationModelCallsCumulativeLimit=1940,
            currentSendPermitLimit=1870, reservationsAreNotActualTransport=True)
        return {**current, "currentRoundAccounting": current, "currentAllRoundsAccounting": all_current,
                "legacyAccounting": copy.deepcopy(self.legacy), "cumulativeCommonAccounting": cumulative,
                "candidateQuotaIsSeparateNotSummedAsBill": True}


def run_next():
    require_human_authorization()  # Entry barrier: even an accepted engineering decision cannot bypass it.
    with fixed_proxy_runtime():
        return d.run_next()


def preflight():
    with controller_locked():
        return _old_preflight()


@contextmanager
def fixed_proxy_runtime():
    """Limit the proxy-class binding to this new controller's active call."""
    import replan_stage_d_proxy
    previous = replan_stage_d_proxy.ModelProxy
    replan_stage_d_proxy.ModelProxy = StableModelProxy
    try:
        yield
    finally:
        replan_stage_d_proxy.ModelProxy = previous


def install():
    """Replace only this imported module's process globals; never edit its source."""
    d.BATCH = BATCH
    d.REVIEW_ROOT = REVIEW_ROOT
    d.MAX_REQUESTS = CURRENT_REQUESTS
    d.MAX_CALLS = CURRENT_CALLS
    d.write = write
    d.write_bytes = write_bytes
    d.tool_files = tool_files
    d.verify = verify
    d.SendGate = SendGate
    d.real_key = guarded_real_key
    d.app_command = app_command
    d.application = application
    d.offline_preflight = offline_preflight
    d.http_json = http_json
    d.locked = controller_locked
    d.preflight = preflight


install()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "preflight", "run-next", "audit"))
    args = parser.parse_args()
    if args.action == "prepare":
        prepare()
    elif args.action == "preflight":
        d.preflight()
    elif args.action == "run-next":
        run_next()
    else:
        with controller_locked():
            plan = d.read(d.BATCH / "plan.json")
            verify(plan)
            state = d.read(d.BATCH / "state.json")
            d.validate_completed_rounds(state)
            print(json.dumps(dict(current=d.audited_rounds(state), legacy=plan["legacy"],
                                 humanPaidAuthorizationPresent=(d.BATCH / AUTH_NAME).is_file()), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    try:
        main()
    except (Refused, QuotaRefused) as error:
        print("REFUSED:", str(error), file=sys.stderr)
        sys.exit(2)
