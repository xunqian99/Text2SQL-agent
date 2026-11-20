"""Build representative synthetic end-state journals, then time real validation.

No Java, key, supplier, database or paid answers. Construction is deliberately
batched (not a request replay); the measured observation uses the real monitor.
17 observations/question with two calls is conservative for journal size; the
numbers describe this local machine and cannot establish application P95.
"""
from __future__ import annotations

import base64
import copy
import json
from pathlib import Path
import time

import replan_quota_monitor as q
import run_replan_stage_d_reregistered as n

OUTPUT = n.CANDIDATE / "quota-monitor-performance-3"


def plain(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(q.packed(value) + b"\n")


def build(questions):
    batch = OUTPUT / str(questions)
    batch.mkdir(parents=True, exist_ok=False)
    seed = q.quota_semantic(q.read(n.OLD_BATCH / "candidate-cost-quota.json", exact=True))
    plain(batch / "plan.json", dict(synthetic=True, questions=questions, noPaidAuthorization=True))
    plan_sha = n.sha(batch / "plan.json")
    plain(batch / "candidate-quota-initial-snapshot.json", seed)
    plain(batch / "candidate-quota-genesis-policy.json", {**q.POLICY, "seedSha256": n.sha(batch / "candidate-quota-initial-snapshot.json")})
    quota = q.quota_semantic(seed)
    quota.update(knownCalls=seed["knownCalls"] + questions * 2,
                 knownCostYuan=q.money(q.decimal(seed["knownCostYuan"] if not isinstance(seed["knownCostYuan"], float) else str(seed["knownCostYuan"])) + q.Decimal(".000005") * questions * 2))
    plain(batch / "candidate-cost-quota.json", quota)
    raw_quota = (batch / "candidate-cost-quota.json").read_bytes()
    event_dir = batch / "send-ledger-events"; event_dir.mkdir()
    ledger = dict(planSha256=plan_sha, legacyManifestSha256=n.PINNED["paid-interruption-manifest.json"], calls=[], rejections=[], breaches=[])
    groups = []
    for i in range(questions * 2):
        token = f"{i:032x}"
        permit = dict(token=token, round="DEV_F", questionId=f"SYNTHETIC-{i//2:04d}", attempt=i % 2 + 1,
                      upstreamDeadlineMonotonic=15., admittedAt="2026-11-21T12:00:00+08:00", offPeak=dict(allowed=True),
                      requestBodySha256="a" * 64, requestBytes=1024, inputTokenEstimate=2048,
                      promptChars=1000, reservedYuan="0.10", usageKnown=False, costYuan=None, result=None)
        plain(event_dir / (token + "-permit.json"), permit)
        body = b'{"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":1}}'
        raw_name = token + "-upstream.body.bin"; (event_dir / raw_name).write_bytes(body)
        completed = {**permit, "usageKnown": True, "costYuan": ".000005", "usage": json.loads(body)["usage"],
                     "result": dict(responseBodyFile=raw_name, responseBodySha256=q.digest(body), responseBytes=len(body),
                                    upstreamAttempted=True, responseComplete=True, responseJson=json.loads(body))}
        plain(event_dir / (token + "-completion.json"), completed)
        ledger["calls"].append(completed)
        groups.append({name: n.sha(event_dir / name) for name in (token + "-permit.json", token + "-completion.json", raw_name)})
    state = dict(planSha256=plan_sha, requestsStarted=questions, rounds={"DEV_F": dict(status="RUNNING", side="F")}, terminal=None)
    state_sha, ledger_sha = q.value_sha(state), q.value_sha(ledger)
    journal = batch / "candidate-quota-observations.jsonl"
    journal_stream = journal.open("xb")
    journal_hasher = q.hashlib.sha256()
    knowledge = dict(knownCalls=quota["knownCalls"], knownCostYuan=quota["knownCostYuan"], unknowns=quota["holds"],
                     pending={}, bindings={}, retired={}, runtimeStarted=True, activeSide="F", activeQuestion=None)
    prior_knowledge = {}; previous = None; sequence = 0; total_bytes = 0
    def emit(context, event_delta=None):
        nonlocal previous, sequence, prior_knowledge, total_bytes
        event = dict(format=q.FORMAT, planSha256=plan_sha, sequence=sequence, previousSha256=previous,
                     context=context, runtimeRawSha256=q.digest(raw_quota), runtimeRawBase64=base64.b64encode(raw_quota).decode(),
                     quota=quota, knowledgeDelta=q.changes(prior_knowledge, knowledge),
                     stateSha256=state_sha, ledgerSha256=ledger_sha, sendEventDelta=event_delta or {},
                     evidence=dict(syntheticStorageShapeOnly=True))
        raw = q.packed(event) + b"\n"; journal_stream.write(raw); journal_hasher.update(raw)
        total_bytes += len(raw); previous = q.digest(raw); sequence += 1; prior_knowledge = copy.deepcopy(knowledge)
    emit("SYNTHETIC_GENESIS")
    for question in range(questions):
        for stage in ("VERIFY", "STATE_WRITE", "QUESTION_BEGIN"): emit(stage)
        for index in range(question * 2, question * 2 + 2):
            identity = f"synthetic-hold-{index}"; token = f"{index:032x}"
            hold = dict(requestId=f"synthetic-request-{question}", callNumber=index % 2 + 1, promptSha256="a" * 64, status="PENDING")
            knowledge["pending"][identity] = dict(hold=hold, questionId=f"SYNTHETIC-{question:04d}")
            emit("PROXY_BEFORE")
            emit("LEDGER_WRITE", {k: v for k, v in groups[index].items() if k.endswith("-permit.json")})
            knowledge["bindings"][identity] = token; emit("PERMIT_BINDING")
            emit("LEDGER_WRITE", {k: v for k, v in groups[index].items() if not k.endswith("-permit.json")})
            emit("PROXY_AFTER")
            knowledge["pending"].pop(identity); knowledge["retired"][identity] = dict(hold=hold, outcome="KNOWN", token=token)
            emit("JAVA_SETTLEMENT")
        emit("HTTP_RETURN"); emit("QUESTION_END")
    journal_stream.close()
    head = dict(planSha256=plan_sha, sequence=sequence-1, eventSha256=previous,
                journalBytes=total_bytes, journalSha256=journal_hasher.hexdigest())
    state[q.ANCHOR] = head; ledger[q.ANCHOR] = head
    plain(batch / "state.json", state); plain(batch / "send-ledger.json", ledger); plain(batch / "candidate-quota-checkpoint.json", head)
    monitor = q.QuotaMonitor(batch, plan_sha, plain_writer)
    measures = []; parts = []
    for attempt in range(3):
        started = time.perf_counter()
        monitor.observe("SYNTHETIC_MEASURE", evidence=dict(attempt=attempt))
        measures.append(round((time.perf_counter()-started)*1000, 3))
        parts.append(dict(monitor.last_metrics))
        print(json.dumps(dict(questions=questions, attempt=attempt, observedMs=measures[-1], parts=parts[-1])), flush=True)
        if measures[-1] > 15000: break
    return dict(questions=questions, calls=questions*2, initialObservations=sequence, initialJournalBytes=total_bytes,
                initialMeanEventBytes=round(total_bytes/sequence, 2), endObservationMs=measures,
                observationParts=parts,
                finalLedgerBytes=(batch/"send-ledger.json").stat().st_size,
                commonEvents=questions*6, runtimeQuotaBytes=len(raw_quota),
                fullChainAndCommonRawHashesRecheckedEveryObservation=True,
                note="Representative synthetic terminal storage shape; not execution, real model latency or P95")


def plain_writer(path, value, *, replace=False):
    # Production persistent writer, with owned() restricted to this fixture.
    return n.raw_write(path, value, replace=replace)


if __name__ == "__main__":
    OUTPUT.mkdir(parents=True, exist_ok=False)
    results = []
    for questions in (102, 902):
        batch = OUTPUT / str(questions)
        n.d.BATCH = batch
        result = build(questions); results.append(result)
        print(json.dumps(result), flush=True)
    plain(OUTPUT / "verification.json", dict(results=results, supplierCalls=0, keyReads=0,
          noP95OrAccuracyClaim=True, sourceSha256=n.sha(Path(q.__file__))))
