"""The authorized final C1 development batch, using real frozen Java over HTTP.

Only run-next resolves credentials. Local preflight uses a loopback supplier and
an isolated copy of the inherited quota. Old interrupted batches stay closed.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import time
import uuid
from contextlib import contextmanager
from decimal import Decimal

import run_replan_stage_d_reregistered as n
from replan_quota_monitor import POLICY, validate_seed, read as quota_read, decimal
from replan_stable_proxy import BASE_URL

d = n.d
ROOT = n.ROOT
BASE = n.BASE
BATCH = BASE / "scope10-final-dev-20261121/revision-3"
SCOPE = BASE / "free-paid-scope10-revision6-20261120"
ACTIVATION = BASE / "free-paid-scope10-quota-activation-revision6c-20261120"
CLOSED = BASE / "batch-1/reregistered-dev-1/revision-4"
LIMIT = Decimal("10.00")
RESERVE = Decimal("0.10")
MAX_REQUESTS = 902
MAX_CALLS = 1804
LEGACY = dict(requestsStarted=144, applicationModelCalls=146,
              commonSendReservations=145, transportAttempts=144,
              knownUsageCalls=139, knownCostYuan="0.293767460",
              commonUnknownReservations=6, commonUnknownHeldYuan="0.60",
              commonCommittedYuan="0.893767460", candidateUnknownHolds=7,
              candidateUnknownHeldYuan="0.70", candidateCommittedYuan="0.993767460")
PINNED = {
    SCOPE / "plan.json": "a0f9e86a32b78addf68204be76115953cf082b61791ec74a4339ac65fbab3595",
    SCOPE / "paid-scope10-authorization.json": "bcf686fefa4468633a87de1d3b4bbcefa87c04a10889f8643149ed97733bcdd0",
    SCOPE / "authorization-binding.json": "2bf2ed7d8a1cf7e79fb6960fab5bd8fe2413cc2dc7701632966a0a3e5db5325b",
    SCOPE / "staged-candidate-quota.json": "57931f5a2a6df10e654ff3322791e8eebc7675dbb10abf1c6f7305a019de41bf",
    CLOSED / "plan.json": "da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe",
    CLOSED / "state.json": "dca6197ce04a4fc1ed87f665b7954cf25a1f0f3216620b78eb2dc6f3f9d11025",
    CLOSED / "send-ledger.json": "a5097cec99f0a8c9b2f2b9596a46a682dde6a0dd8bbf4ad6bdad4af602d3ca23",
    CLOSED / "candidate-cost-quota.json": "f9ed89b775ff2c57e4394c1bf39d55533752f0d19e3c0bae13286dc4aceccc86",
    ACTIVATION / "execution-manifest.json": "6d6e4db64190b2c949b5bb26838bfe375eba05b91d42519a2bd7eee7f4eb23c7",
    ACTIVATION / "migration-proof.json": "2f8ac38e6574c8204085ab47a3f6a7c8e3bcad3ebb68d5cde816741891193fee",
}
require = n.require
sha = n.sha


def configure(batch=BATCH):
    d.BATCH = Path(batch).resolve()
    d.REVIEW_ROOT = BATCH / "independent-review"
    d.LIMIT, d.RESERVE = LIMIT, RESERVE
    d.MAX_REQUESTS, d.MAX_CALLS = MAX_REQUESTS, MAX_CALLS
    n.LEGACY = copy.deepcopy(LEGACY)
    d.app_command = app_command
    d.SendGate = SendGate


def authorized_scope():
    for path, expected in PINNED.items():
        require(path.is_file() and not path.is_symlink() and sha(path) == expected,
                "Authorized scope/history identity drift: " + path.name)
    scope = d.read(SCOPE / "plan.json")
    require(scope["scope"]["limitYuan"] == "10.00"
            and scope["scope"]["newRouteQuestions"] == MAX_REQUESTS
            and scope["scope"]["newRouteApplicationCalls"] == MAX_CALLS,
            "Paid scope differs from direct-user authorization")
    original = d.read(CLOSED / "candidate-cost-quota.json")
    seed = d.read(SCOPE / "staged-candidate-quota.json")
    expected = {**original, "limitYuan": 10.00}
    require(seed == expected, "Quota migration must change only the cumulative limit")
    return seed


def production_files():
    names = ("run_replan_stage_d_scope10.py", "run_replan_stage_d_reregistered.py",
             "run_replan_stage_d.py", "replan_accounting_proxy.py", "replan_stage_d_proxy.py",
             "replan_stable_proxy.py", "replan_quota_monitor.py", "replan_durable_write.py")
    return [ROOT / "scripts" / name for name in names]


def app_command(side, port, base_url, out, *, offline=False):
    require(base_url == BASE_URL, "Frozen candidate pricing requires the exclusive 59900 listener")
    require(not offline, "Preflight uses an isolated real quota, never a reset offline quota")
    command = n._old_app_command(side, port, base_url, out, offline=False)
    return ["--agent.cost-quota.total-yuan=10.00" if arg.startswith("--agent.cost-quota.total-yuan=")
            else arg for arg in command]


def verify(plan, *, database=True):
    authorized_scope()
    for relative, expected in plan["files"].items():
        path = ROOT / relative
        require(path.is_file() and not path.is_symlink() and sha(path) == expected,
                "Frozen runtime/reference drift: " + relative)
    for side in d.SYSTEMS.values():
        require(sha(ROOT / side["jar"]) == side["sha256"], "Frozen Java JAR drift")
    if database:
        require(d.database_snapshot() == plan["database"], "Read-only business data drift")


def initialize(batch, plan):
    n.raw_write(batch / "plan.json", plan)
    plan_sha = sha(batch / "plan.json")
    n.raw_write(batch / "state.json", dict(format=plan["format"], planSha256=plan_sha,
                                         requestsStarted=0, rounds={}, terminal=None))
    n.raw_write(batch / "send-ledger.json", dict(planSha256=plan_sha,
                legacyManifestSha256=n.PINNED["paid-interruption-manifest.json"],
                inheritedRevision4LedgerSha256=PINNED[CLOSED / "send-ledger.json"],
                calls=[], rejections=[], breaches=[]))
    seed_bytes = (SCOPE / "staged-candidate-quota.json").read_bytes()
    for name in ("candidate-cost-quota.json", "candidate-quota-initial-snapshot.json"):
        n.write_bytes(batch / name, seed_bytes)
    n.raw_write(batch / "candidate-quota-genesis-policy.json", {**POLICY,
                "seedSha256": hashlib.sha256(seed_bytes).hexdigest()})
    n.quota_monitor().initialize()


def prepare():
    configure()
    authorized_scope()
    require(not BATCH.exists(), "Final paid batch already exists; never reset or replay")
    for side in d.SYSTEMS.values():
        require(sha(ROOT / side["jar"]) == side["sha256"], "Frozen JAR drift")
    BATCH.mkdir(parents=True)
    cp = d.compile_bridge()
    for name in ("dev", "formal", "targets"):
        d.bridge(cp, "prepare", name, BATCH / (name + "-reference.json"))
    database = d.database_snapshot()
    require(database == d.read(ROOT / "reports/replan-stage-b-20261119/b0-identity.json")["database"],
            "Database differs from locally accepted B0")
    files = production_files() + d.dataset_files()
    files += [ROOT / "scripts/replan/StageDScoringBridge.java",
              ROOT / "reports/replan-stage-a-20261119/ReferenceContractScorer.java",
              ROOT / "scripts/test_replan_scope10_integration.py"]
    files += n._old_tool_files()
    files += [BATCH / (name + "-reference.json") for name in ("dev", "formal", "targets")]
    files += [p for p in (BATCH / "tool-runtime").rglob("*") if p.is_file()]
    plan = dict(format="replan-d-real-scope10-v1", createdAt=d.stamp(),
                systems=d.SYSTEMS, classpath=cp, database=database, order=list(d.ORDER),
                inheritedAccounting=LEGACY, limits=dict(totalYuan="10.00", reservationYuan="0.10",
                newRequests=902, newApplicationCalls=1804, cumulativeRequests=1046,
                cumulativeApplicationCalls=1950, devQuestions=100, devCalls=200,
                requestMs=15000, accountingExtraSeconds=105, roundSeconds=1740,
                sendGuardSeconds=120, sdkAttempts=1, maxPayloadBytes=65536,
                maxResponseBytes=2097152, maxCollectors=16, activeBodyBytes=33554432),
                unseen="LOCKED; independent custodian only after formal pass",
                authorizedScopePlanSha256=PINNED[SCOPE / "plan.json"],
                files={p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(set(files))})
    with n.controller_locked():
        initialize(BATCH, plan)
    print("Prepared final DEV100; no provider call and no key read", flush=True)


class SendGate(n.SendGate):
    def __init__(self, *args, round_deadline=None, **kwargs):
        self.round_deadline = round_deadline
        super().__init__(*args, **kwargs)

    def before(self, payload, raw):
        with n.quota_monitor().serial():
            current = self.current or {}
            attempt = 1 + sum(c["round"] == self.round and c["questionId"] == current.get("id")
                              for c in self.data["calls"])
            with n.quota_monitor().callback("BEFORE", self.data, fence=self.current_fence,
                                            payload=payload, attempt=attempt) as stage:
                stage["threadId"] = d.threading.get_ident()
                self._stage = stage
                try:
                    permit = self._before(payload, raw)
                    stage["permit"] = permit
                finally:
                    self._stage = None
            return permit

    def _before(self,payload,raw):
        with self.lock:
            try:
                if self.data["breaches"] or self.data["rejections"]: raise d.Refused("PRIOR_USAGE_COST_OR_LOCAL_GATE_STOP")
                current=self.current
                if current is None or self.monotonic()>=current["deadline"]: raise d.Refused("NO_ACTIVE_REQUEST_OR_DEADLINE")
                # Any DNS/connect delay is bounded by the remaining parent deadline.
                # Reserve that delay too, so the eventual wire attempt still has 120s.
                decision=d.window(self.clock(),120+math.ceil(current["deadline"]-self.monotonic()))
                if not decision["allowed"]: raise d.Refused(decision["reason"])
                if set(payload)-{"model","messages","temperature","max_tokens","max_completion_tokens","stream","n"}: raise d.Refused("UNREGISTERED_MODEL_OPTION")
                if payload.get("model")!="deepseek-flash" or payload.get("temperature")!=0: raise d.Refused("MODEL_OR_TEMPERATURE_DRIFT")
                if payload.get("max_tokens",payload.get("max_completion_tokens"))!=8192: raise d.Refused("OUTPUT_LIMIT_DRIFT")
                if "max_tokens" in payload and "max_completion_tokens" in payload and payload["max_tokens"]!=payload["max_completion_tokens"]: raise d.Refused("CONFLICTING_OUTPUT_LIMITS")
                if payload.get("n",1)!=1: raise d.Refused("ONE_COMPLETION_ONLY")
                if any(k in payload for k in ("reasoning_effort","thinking","tools","tool_choice","web_search","response_format")): raise d.Refused("MODE_OR_TOOL_DRIFT")
                if payload.get("stream",False) is not False or len(raw)>65536: raise d.Refused("PAYLOAD_BOUND")
                messages=payload.get("messages")
                if not isinstance(messages,list) or [m.get("role") for m in messages]!=["system","user"]: raise d.Refused("MESSAGE_SHAPE")
                if any(not isinstance(m.get("content"),str) for m in messages): raise d.Refused("TEXT_MESSAGES_ONLY")
                user=messages[1]["content"]; marker="=== 用户问题 ===\n"
                tail=user.partition(marker)[2]; expected=current["question"]+"\n"
                if not tail.startswith(expected): raise d.Refused("QUESTION_BINDING")
                suffix=tail[len(expected):]
                calls=[c for c in self.data["calls"] if c["round"]==self.round and c["questionId"]==current["id"]]
                if len(calls)>=2: raise d.Refused("PER_QUESTION_CALL_LIMIT")
                if (not calls and suffix) or (calls and not suffix.startswith("\n\n=== 上一次 SQL 与评估反馈 ===\n")): raise d.Refused("FIRST_OR_CORRECTION_BINDING")
                if len(self.data["calls"])>=MAX_CALLS: raise d.Refused("GLOBAL_CALL_LIMIT")
                estimate=Decimal(len(raw)+1024)/Decimal(1000000)+Decimal(8192)*Decimal(4)/Decimal(1000000)
                if estimate>RESERVE or self.committed()+RESERVE>LIMIT: raise d.Refused("GLOBAL_COST_RESERVATION")
                require(self.round_deadline is not None and self.monotonic() < self.round_deadline,
                        "WHOLE_ROUND_DEADLINE")
                from replan_accounting_proxy import _peak_boundary
                boundary = _peak_boundary(self.clock())
                offpeak_deadline = self.monotonic() + (boundary - self.clock()).total_seconds()
                accounting_deadline = min(current["deadline"] + 105,
                                          self.round_deadline, offpeak_deadline)
                token=uuid.uuid4().hex
                permit=dict(token=token,round=self.round,questionId=current["id"],attempt=len(calls)+1,
                  upstreamDeadlineMonotonic=current["deadline"],
                  answerDeadlineMonotonic=current["deadline"],
                  wholeRoundDeadlineMonotonic=self.round_deadline,
                  offPeakBoundaryMonotonic=offpeak_deadline,
                  accountingDeadlineMonotonic=accounting_deadline,admittedAt=self.clock().isoformat(),offPeak=decision,
                  requestBodySha256=hashlib.sha256(raw).hexdigest(),requestBytes=len(raw),inputTokenEstimate=len(raw)+1024,
                  promptChars=sum(len(m["content"].encode("utf-16-le"))//2 for m in messages),reservedYuan=str(RESERVE))
                call={**permit,"usageKnown":False,"costYuan":None,"result":None}
                n.write(self.events/(token+"-permit.json"),call)
                self.data["calls"].append(call)
                self.save()  # Durable before upstream; failed persistence means no send.
                return permit
            except Exception as error:
                rejection=dict(eventId=uuid.uuid4().hex,round=self.round,questionId=(self.current or {}).get("id"),at=self.clock().isoformat(),reason=str(error))
                n.write(self.events/(rejection["eventId"]+"-rejection.json"),rejection)
                self.data["rejections"].append(rejection)
                self.save(); raise

    def summary(self, round_name=None, reconciliation=None):
        current = n._old_send_gate.summary(self, round_name, reconciliation)
        all_current = n._old_send_gate.summary(self)
        cumulative = dict(sendReservations=145 + all_current["sendReservations"],
            transportAttempts=144 + all_current["transportAttempts"],
            knownUsageCalls=139 + all_current["knownUsageCalls"],
            unknownUsageCalls=6 + all_current["unknownUsageCalls"],
            knownCostYuan=str(Decimal(LEGACY["knownCostYuan"]) + Decimal(all_current["knownCostYuan"])),
            unknownHeldYuan=str(Decimal("0.60") + Decimal(all_current["unknownHeldYuan"])),
            configuredCommittedYuan=str(self.committed()), applicationCallsLegacy=146,
            cumulativeApplicationCallLimit=1950, currentPermitLimit=1804)
        return {**current, "currentRoundAccounting": current, "currentAllRoundsAccounting": all_current,
                "legacyAccounting": copy.deepcopy(LEGACY), "cumulativeCommonAccounting": cumulative,
                "candidateQuotaIsSeparateNotSummedAsBill": True}


def check_send(permit):
    return d.require_window(120)


def candidate_headroom():
    quota = validate_seed(quota_read(d.BATCH / "candidate-quota-initial-snapshot.json", exact=True),
                          quota_read(d.BATCH / "candidate-cost-quota.json", exact=True))
    committed = decimal(quota["knownCostYuan"]) + decimal(quota["reservationYuan"]) * len(quota["holds"])
    require(committed + RESERVE <= LIMIT, "Candidate quota has no room for the next call")
    return quota


def deadline_counts(rows, calls, decisions=()):
    runtime = {r["id"] for r in rows if r["body"].get("status") == "REQUEST_TIMEOUT"
               or (r["body"].get("budget") or {}).get("termination") == "DEADLINE_EXCEEDED"}
    transport = {call["questionId"] for call in calls if (call.get("result") or {}).get("late") is True}
    transport |= {item["questionId"] for item in decisions if item.get("answerDeadlineExceeded") is True}
    return dict(timeoutCount=len(runtime | transport), originalRuntimeTimeoutCount=len(runtime),
                transportDeadlineQuestionCount=len(transport),
                timeoutDefinition="unique questions: original runtime timeout OR original proxy answer deadline; no double counting")


def require_engineering(plan_sha):
    review_path = BATCH / "independent-engineering-review.json"
    review = d.read(review_path)
    require(review.get("accepted") is True and review.get("planSha256") == plan_sha
            and review.get("reviewerIndependentOfImplementation") is True,
            "Real transport/Java integration awaits independent engineering acceptance")
    preflight = d.read(BATCH / "preflight.json")
    require(preflight.get("passed") is True and preflight.get("planSha256") == plan_sha,
            "Real Java preflight is required")
    for relative, expected in {**preflight["evidence"], **review.get("evidence", {})}.items():
        require(sha(ROOT / relative) == expected, "Engineering acceptance evidence drift")


def finish_round(plan, state, name, side, dataset, out, gate, responses, decisions):
    require(not gate.data["rejections"] and not gate.data["breaches"], "Send/usage stop")
    verify(plan)
    n.write(out / "responses.json", dict(items=responses))
    d.bridge(plan["classpath"], "score", BATCH / (dataset + "-reference.json"),
             out / "responses.json", out / "score.json")
    score = d.read(out / "score.json")
    reconciliation = gate.reconcile(responses)
    n.write(out / "http-reconciliation.json", reconciliation)
    summary = {k: score[k] for k in ("total", "correct", "byLayer")}
    summary.update(wallP95Ms=d.percentile([r["wallLatencyMs"] for r in responses]),
        wallMeanMs=sum(r["wallLatencyMs"] for r in responses) / len(responses),
        **deadline_counts(responses, [call for call in gate.data["calls"] if call["round"] == name], decisions),
        accounting=gate.summary(name, reconciliation),
        newDangerousAllowances=None, newDangerousAllowancesScope="PENDING independent review of actual HTTP outputs")
    result = dict(status="COMPLETED", side=side, dataset=dataset, summary=summary,
                  finishedAt=d.stamp(), independentSafetyReviewPending=True)
    if name == "DEV_F":
        result.update(gate=d.dev_gate(summary), metricGate=d.dev_metric_gate(summary))
        if not result["metricGate"]["passed"]:
            state["terminal"] = "DEVELOPMENT_FAILED_NO_C2"
    elif name == "TARGETS":
        reused = [r for r in d.read(BATCH / "DEV_F/score.json")["items"] if r["id"] == "T3-002"]
        require(len(reused) == 1 and "originalTargetCorrect" in reused[0], "Missing original T3 judgement")
        hits = sum(bool(r["correct"]) for r in score["items"]) + int(reused[0]["originalTargetCorrect"])
        result["gate"] = dict(passed=hits >= 2, correct=hits, total=3, T3Reused=True, extraQuestions=2)
        if hits < 2:
            state["terminal"] = "ORIGINAL_TARGETS_FAILED"
    state["rounds"][name] = result
    if name == "F3":
        state["formalGate"] = d.formal_gate(state["rounds"])
        state["formalMetricGate"] = d.formal_metric_gate(state["rounds"])
        if not state["formalMetricGate"]["passed"]:
            state["terminal"] = "FORMAL_FAILED_NO_UNSEEN"
    tokens = {c["token"] for c in gate.data["calls"] if c["round"] == name}
    events = [p for p in gate.events.iterdir() if any(p.name.startswith(token + "-") for token in tokens)]
    result["evidence"] = {p.relative_to(ROOT).as_posix(): sha(p) for p in [*out.rglob("*"), *events] if p.is_file()}
    n.write(out / "summary.json", result)
    state["rounds"][name] = {**result, "summarySha256": sha(out / "summary.json")}
    n.write(BATCH / "state.json", state, replace=True)
    print(json.dumps({"round": name, "total": summary["total"], "correct": summary["correct"],
                      "p95Ms": summary["wallP95Ms"], "timeouts": summary["timeoutCount"],
                      "metricGate": result.get("metricGate"), "independentSafetyReviewPending": True},
                     ensure_ascii=False), flush=True)


def run_next():
    from replan_accounting_proxy import ModelProxy
    configure()
    with n.controller_locked():
        plan, state = d.read(BATCH / "plan.json"), d.read(BATCH / "state.json")
        plan_sha = sha(BATCH / "plan.json")
        require(state["planSha256"] == plan_sha, "State/plan identity drift")
        verify(plan)
        require_engineering(plan_sha)
        d.validate_completed_rounds(state)
        effective = d.audited_rounds(state)
        name = d.next_round(effective)
        whole_window = d.require_window(1740)
        side = "B" if name.startswith("B") else "F"
        dataset = "dev" if name == "DEV_F" else "targets" if name == "TARGETS" else "formal"
        items = d.read(BATCH / (dataset + "-reference.json"))["items"]
        require(state["requestsStarted"] + len(items) <= MAX_REQUESTS, "Global question limit")
        began = time.monotonic()
        round_deadline = began + 1740
        gate = SendGate(BATCH / "send-ledger.json", plan_sha, name, round_deadline=round_deadline)
        require(not gate.data["breaches"] and not gate.data["rejections"]
                and gate.committed() + RESERVE <= LIMIT, "Existing common send/cost stop")
        candidate_headroom()
        key = n._old_real_key()  # Last: authorization, frozen runtime, review, clock, both quotas already checked.
        out = BATCH / name
        out.mkdir()
        state["rounds"][name] = dict(status="RUNNING", startedAt=d.stamp(), side=side,
                                    dataset=dataset, wholeWindow=whole_window)
        n.write(BATCH / "state.json", state, replace=True)
        responses = []
        try:
            with ModelProxy(key, gate.before, gate.after, check_send=check_send,
                            durable_before_answer=True) as proxy:
                key = None
                with n.application(side, proxy, out) as (url, token):
                    last_start = 0.
                    with (out / "responses.jsonl").open("xb") as journal:
                        for item in items:
                            for path in production_files():
                                require(sha(path) == plan["files"][path.relative_to(ROOT).as_posix()],
                                        "Controller/transport drift before question")
                            n.quota_monitor().observe("BEFORE_QUESTION")
                            if side == "F":
                                candidate_headroom()
                            require(time.monotonic() < began + 1620, "Round answer bound; no replay")
                            require(not gate.data["breaches"] and not gate.data["rejections"]
                                    and not proxy.errors, "Transport/usage/local gate stop")
                            while time.monotonic() - last_start < 2.1:
                                time.sleep(.05)
                            d.require_window(120)
                            state["requestsStarted"] += 1
                            n.write(BATCH / "state.json", state, replace=True)
                            started = last_start = time.monotonic()
                            gate.begin(item, started + 15)
                            evidence = out / "http-answers" / (item["id"] + ".json")
                            try:
                                status, body = n.http_json(url + "/api/ask", data={"question": item["question"]},
                                                          token=token, timeout=20, evidence=evidence)
                            finally:
                                gate.end()
                            observed = d.read(evidence)
                            row = dict(id=item["id"], httpStatus=status, body=body,
                                wallLatencyMs=(observed["httpReadCompletedMonotonic"] - started) * 1000,
                                returnedAt=observed["returnedAt"], httpEvidence=evidence.relative_to(ROOT).as_posix())
                            responses.append(row)
                            journal.write((json.dumps(row, ensure_ascii=False) + "\n").encode("utf-8"))
                            journal.flush()
                            os.fsync(journal.fileno())
                            budget = body.get("budget") or {}
                            require(budget.get("timeoutMs") == 15000 and budget.get("maxModelCalls") == 2
                                    and type(budget.get("modelCalls")) is int and 0 <= budget["modelCalls"] <= 2,
                                    "Runtime request budget drift")
                            require(status not in (401, 403, 429) and body.get("status") != "RESOURCE_LIMITED",
                                    "Protected application admission stop")
                            if len(responses) % 10 == 0:
                                print(name, "HTTP answers", len(responses), "/", len(items), flush=True)
                proxy.drain(timeout_seconds=max(0, round_deadline - time.monotonic()))
                require(not proxy.errors, "Transport/callback lifecycle error")
                n.quota_monitor().observe("ROUND_END")
                decisions = proxy.get_answer_decisions()
                n.write(out / "proxy-answer-decisions.json", decisions)
            finish_round(plan, state, name, side, dataset, out, gate, responses, decisions)
        except BaseException as error:
            state["rounds"][name].update(status="INTERRUPTED", reason=type(error).__name__ + ": " + str(error),
                                        completedAnswers=len(responses), finishedAt=d.stamp())
            state["terminal"] = "INTERRUPTED_NO_REPLAY"
            n.write(out / "interruption.json", state["rounds"][name])
            n.write(BATCH / "state.json", state, replace=True)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "preflight", "run-next", "audit"))
    args = parser.parse_args()
    configure()
    if args.action == "prepare":
        prepare()
    elif args.action == "preflight":
        from test_replan_scope10_integration import preflight
        preflight()
    elif args.action == "run-next":
        run_next()
    else:
        with n.controller_locked():
            verify(d.read(BATCH / "plan.json"))
            n.quota_monitor().observe("READ_ONLY_AUDIT")
            state = d.read(BATCH / "state.json")
            print(json.dumps(dict(requestsStarted=state["requestsStarted"], rounds={k: v["status"] for k, v in state["rounds"].items()},
                                  terminal=state["terminal"]), ensure_ascii=False))


configure()

if __name__ == "__main__":
    main()
