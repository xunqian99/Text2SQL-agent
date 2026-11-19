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
from datetime import datetime

from replan_durable_write import replace_with_retry

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "reports/replan-stage-d-20261119"
OLD_BATCH = BASE / "batch-1/revision-2"
BATCH = BASE / "batch-1/reregistered-dev-1"
REVIEW_ROOT = BASE / "independent-review/reregistered-dev-1"
CANDIDATE = BASE / "reregistration-candidate"
AUTH_SCHEMA = CANDIDATE / "human-paid-authorization.schema.json"
AUTH_NAME = "human-paid-authorization.json"
NEW_FORMAT = "replan-d-reregistered-c1-v1"
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


def write(path, value, *, replace=False):
    path = owned(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    require(replace or not path.exists(), "Never overwrite evidence: " + path.name)
    data = (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
    if not replace:
        write_bytes(path, data)
        return
    temp = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    write_bytes(temp, data)
    # Default 0.5s retry/backoff budget and 12 attempts only. No HTTP retry.
    # An individual synchronous os.replace cannot be forcibly time-bounded.
    replace_with_retry(temp, path)


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
    ledger = d.read(OLD_BATCH / "send-ledger.json")
    calls = ledger["calls"]
    known = [c for c in calls if c.get("usageKnown") is True]
    unknown = [c for c in calls if c.get("usageKnown") is False]
    known_cost = sum((Decimal(c["costYuan"]) for c in known), Decimal(0))
    require(ledger.get("planSha256") == OLD_PLAN_SHA and len(calls) == 69 and len(known) == 67
            and len(unknown) == 2 and known_cost == Decimal(LEGACY["knownCostYuan"])
            and sum(bool((c.get("result") or {}).get("upstreamAttempted")) for c in calls) == 68
            and len(ledger["rejections"]) == 2 and not ledger["breaches"], "Legacy common ledger accounting drift")
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
    """Old UNKNOWN identities stay reserved in the new copy for the whole route."""
    old = d.read(OLD_BATCH / "candidate-cost-quota.json")
    quota = d.read(d.BATCH / "candidate-cost-quota.json")
    for key in ("version", "budgetId", "pricingIdentity", "limitYuan", "reservationYuan"):
        require(quota.get(key) == old[key], "Inherited candidate quota identity changed: " + key)
    require(type(quota.get("knownCalls")) is int and quota["knownCalls"] >= old["knownCalls"]
            and Decimal(str(quota.get("knownCostYuan"))) >= Decimal(str(old["knownCostYuan"]))
            and quota.get("reservationBreached") is False, "Candidate known quota/breach regressed")
    require(isinstance(quota.get("holds"), dict)
            and all(quota["holds"].get(k) == v for k, v in old["holds"].items()), "Legacy UNKNOWN hold removed or altered")
    require(Decimal(str(quota["knownCostYuan"])) + d.RESERVE * len(quota["holds"]) <= d.LIMIT,
            "Inherited candidate quota layer is exhausted")
    return quota


def tool_files():
    paths = _old_tool_files() + [Path(__file__), ROOT / "scripts/replan_durable_write.py",
        ROOT / "docs/REPLAN_STAGE_D_INTERRUPTION_20261119.md", AUTH_SCHEMA,
        BASE / "paid-interruption-manifest.json", OLD_BATCH / "plan.json", OLD_BATCH / "state.json",
        OLD_BATCH / "send-ledger.json", OLD_BATCH / "candidate-cost-quota.json"]
    paths.extend(BASE / p for p in PINNED)
    return sorted(set(paths))


def prepare():
    require(not d.BATCH.exists(), "New registration directory already exists; never reset, resume or overwrite")
    legacy = legacy_facts()  # Verify old terminal, audit and every original SHA before creating anything.
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
    database = d.database_snapshot()
    require(database == old_database() == d.read(ROOT / "reports/replan-stage-b-20261119/b0-identity.json")["database"],
            "Database differs from accepted B0 or stopped D plan")
    files = tool_files() + d.dataset_files() + [d.BATCH / (n + "-reference.json") for n in ("dev", "formal", "targets")]
    files.append(d.BATCH / "candidate-quota-initial-snapshot.json")
    files.extend(p for p in (d.BATCH / "tool-runtime").rglob("*") if p.is_file())
    plan = dict(format=NEW_FORMAT, createdAt=d.stamp(), systems=d.SYSTEMS, order=list(d.ORDER),
        registration="batch-1/reregistered-dev-1", statusAtRegistration="DRAFT_REQUIRES_DIRECT_USER_PAID_AUTHORIZATION",
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
        commonScorer="unchanged frozen A scorer on actual saved HTTP body rows; never candidate SQL replay",
        classpath=cp, database=database, files={p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(files))},
        unseen="LOCKED; independent custodian only after every original formal/safety gate passes",
        providerCallsAddedByPreparation=0, priorBatchDisposition="INTERRUPTED_NO_REPLAY; no old question is resumed")
    write(d.BATCH / "plan.json", plan)
    plan_sha = sha(d.BATCH / "plan.json")
    write(d.BATCH / "state.json", dict(format=NEW_FORMAT, planSha256=plan_sha, requestsStarted=0, rounds={}, terminal=None))
    write(d.BATCH / "send-ledger.json", dict(planSha256=plan_sha, legacyManifestSha256=legacy["manifestSha256"],
                                           calls=[], rejections=[], breaches=[]))
    print("New free registration prepared; direct human paid authorization is still absent", flush=True)


def old_database():
    return d.read(OLD_BATCH / "plan.json")["database"]


def verify(plan, *, database=True):
    require(plan.get("format") == NEW_FORMAT and plan.get("registration") == "batch-1/reregistered-dev-1",
            "Wrong new registration identity")
    require(plan.get("legacy") == legacy_facts(), "New plan does not preserve full legacy accounting")
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
    state = d.read(d.BATCH / "state.json")
    ledger = d.read(d.BATCH / "send-ledger.json")
    if state.get("requestsStarted") == 0 and not ledger.get("calls"):
        require(sha(d.BATCH / "candidate-cost-quota.json") == sha(d.BATCH / "candidate-quota-initial-snapshot.json"),
                "Before first paid start, inherited candidate runtime quota must equal its exact seed")


def require_human_authorization():
    """File is minted only from an explicit human reply, never by prepare or review."""
    plan_path = d.BATCH / "plan.json"
    auth_path = d.BATCH / AUTH_NAME
    require(auth_path.is_file() and not auth_path.is_symlink(), "Explicit direct-user authorization for this extra paid DEV100 is absent")
    auth = d.read(auth_path)
    require(set(auth) == {"format", "approved", "authority", "scope", "planSha256", "approvedAt",
            "directUserMessage", "directUserMessageSha256", "legacyManifestSha256", "limits", "acknowledgments"},
            "Human authorization schema/fields mismatch")
    require(auth.get("format") == "replan-d-direct-user-paid-authorization-v1" and auth.get("approved") is True
            and auth.get("authority") == "DIRECT_HUMAN_USER"
            and auth.get("scope") == "ONE_ADDITIONAL_FULL_DEV100_FIXED_C1_EXISTING_GATES"
            and auth.get("planSha256") == sha(plan_path)
            and auth.get("legacyManifestSha256") == PINNED["paid-interruption-manifest.json"],
            "Human authorization is not for this exact new plan/paid scope")
    message = auth.get("directUserMessage")
    require(isinstance(message, str) and bool(message.strip())
            and auth.get("directUserMessageSha256") == hashlib.sha256(message.encode("utf-8")).hexdigest(),
            "Direct human approval message evidence is missing")
    try:
        approved = datetime.fromisoformat(auth["approvedAt"])
        require(approved.tzinfo is not None and approved <= d.now(), "Human authorization timestamp invalid")
    except (ValueError, TypeError, KeyError) as error:
        raise Refused("Human authorization timestamp invalid") from error
    require(auth["limits"] == dict(additionalDevelopmentQuestions=100, currentRouteRequests=902,
        cumulativeRequests=970, cumulativeModelCalls=1940, totalYuan="1.00", reservationYuan="0.10"),
        "Human authorization limits differ from the fixed proposal")
    require(auth["acknowledgments"] == dict(oldBatchInterruptedNoReplay=True,
        legacyRequests=68, legacyApplicationModelCalls=70, knownCostYuan="0.16312188",
        commonUnknownHeldYuan="0.20", candidateUnknownHeldYuan="0.30",
        unknownReservationsNeverReleased=True, twoQuotaLayersNeverSummedAsBill=True,
        unseenStillLocked=True, engineeringAcceptanceAloneIsNotPaidAuthorization=True),
        "Human authorization omits retained UNKNOWN/old-interruption conditions")
    # Once consumed by any new paid round, its bytes are immutable too.
    seal = dict(planSha256=sha(plan_path), authorizationSha256=sha(auth_path), legacyManifestSha256=auth["legacyManifestSha256"])
    seal_path = d.BATCH / "human-authorization-consumed.json"
    if seal_path.exists():
        require(d.read(seal_path) == seal, "Previously consumed human authorization changed")
    return seal


def guarded_real_key():
    seal = require_human_authorization()  # Always before the inherited environment/registry read.
    path = d.BATCH / "human-authorization-consumed.json"
    if not path.exists():
        write(path, seal)
    return _old_real_key()


def app_command(side, port, base_url, out, *, offline=False):
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
    try:
        with _old_application(side, proxy, out, offline=offline) as endpoint:
            yield endpoint
    finally:
        if offline and side == "F" and (Path(out) / "offline-cost-quota.json").is_file():
            seed = d.read(d.BATCH / "candidate-quota-initial-snapshot.json")
            restored = d.read(Path(out) / "offline-cost-quota.json")
            require(all(restored.get(k) == seed[k] for k in ("budgetId", "pricingIdentity", "limitYuan", "reservationYuan")),
                    "Actual offline JAR changed inherited quota identity")
            require(all(restored["holds"].get(k) == v for k, v in seed["holds"].items())
                    and restored["knownCalls"] >= seed["knownCalls"]
                    and Decimal(str(restored["knownCostYuan"])) >= Decimal(str(seed["knownCostYuan"])),
                    "Actual offline JAR lost legacy UNKNOWN/known quota")
            write(Path(out) / "inherited-quota-after-load.json", dict(passed=True,
                budgetId=restored["budgetId"], pricingIdentity=restored["pricingIdentity"], legacyUnknownHoldsPreserved=3,
                legacyKnownCostYuan=LEGACY["knownCostYuan"], legacyUnknownHeldYuan="0.30",
                afterKnownCostYuan=str(restored["knownCostYuan"]), afterKnownCalls=restored["knownCalls"],
                scope="Actual JAR local synthetic preflight only; no additional external charge"))


def offline_preflight(plan, output):
    result = _old_offline_preflight(plan, output)
    result.update(scope="This free preflight adds zero external calls/cost; legacy paid history remains charged separately",
        addedExternalProviderCalls=0, addedProviderCostYuan="0", legacyAccounting=copy.deepcopy(LEGACY),
        syntheticUsageNotAddedToHistoricalRealBill=True)
    write(Path(output) / "reregistration-accounting-scope.json", dict(
        addedExternalProviderCalls=0, addedProviderCostYuan="0", legacyAccounting=LEGACY,
        legacyCommonQuotaAndCandidateQuotaAreSeparate=True, offlineCandidateInheritedQuotaVerified=True))
    return result


class SendGate(_old_send_gate):
    def __init__(self, path, plan_sha, round_name, **kwargs):
        self.legacy = copy.deepcopy(LEGACY)
        super().__init__(path, plan_sha, round_name, **kwargs)
        if self.path.resolve() == (d.BATCH / "send-ledger.json").resolve():
            require(self.data.get("legacyManifestSha256") == PINNED["paid-interruption-manifest.json"],
                    "New send ledger is not bound to immutable legacy accounting")

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
    return d.run_next()


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
    except Refused as error:
        print("REFUSED:", str(error), file=sys.stderr)
        sys.exit(2)
