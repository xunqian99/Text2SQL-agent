"""Measure the real revision-4 gate against representative synthetic storage.

No Java, key, supplier, database or paid answers. Construction is deliberately
batched (not a request replay); the measured callbacks use the real gate/monitor.
17 observations/question with two calls is conservative for journal size; the
numbers describe this local machine and cannot establish application P95.
"""
from __future__ import annotations

import base64
import copy
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from datetime import datetime
from unittest.mock import patch

import replan_quota_monitor as q
import run_replan_stage_d_reregistered as n

OUTPUT = n.CANDIDATE / "quota-monitor-performance-7"


def plain(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(q.packed(value) + b"\n")


def java_snapshot(path, value):
    temporary = path.with_name(path.name + ".synthetic-java-tmp")
    plain(temporary, value)
    os.replace(temporary, path)


def build(questions):
    batch = OUTPUT / str(questions)
    batch.mkdir(parents=True, exist_ok=False)
    seed = q.quota_semantic(q.read(n.OLD_BATCH / "candidate-cost-quota.json", exact=True))
    history_questions = questions - 1
    item = dict(id=f"SYNTHETIC-{history_questions:04d}", question="合成性能尾题")
    reference = batch / "dev-reference.json"
    plain(reference, dict(items=[item]))
    plain(batch / "plan.json", dict(synthetic=True, questions=questions, noPaidAuthorization=True,
          files={reference.relative_to(n.ROOT).as_posix(): n.sha(reference)}))
    plan_sha = n.sha(batch / "plan.json")
    plain(batch / "candidate-quota-initial-snapshot.json", seed)
    plain(batch / "candidate-quota-genesis-policy.json", {**q.POLICY, "seedSha256": n.sha(batch / "candidate-quota-initial-snapshot.json")})
    quota = q.quota_semantic(seed)
    quota.update(knownCalls=seed["knownCalls"] + history_questions * 2,
                 knownCostYuan=q.money(q.decimal(seed["knownCostYuan"]) + q.Decimal(".000005") * history_questions * 2))
    plain(batch / "candidate-cost-quota.json", quota)
    raw_quota = (batch / "candidate-cost-quota.json").read_bytes()
    event_dir = batch / "send-ledger-events"; event_dir.mkdir()
    ledger = dict(planSha256=plan_sha, legacyManifestSha256=n.PINNED["paid-interruption-manifest.json"], calls=[], rejections=[], breaches=[])
    groups = []
    for i in range(history_questions * 2):
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
    state = dict(planSha256=plan_sha, requestsStarted=history_questions, rounds={"DEV_F": dict(status="RUNNING", side="F")}, terminal=None)
    state_sha, ledger_sha = q.value_sha(state), q.value_sha(ledger)
    journal = batch / "candidate-quota-observations.jsonl"
    journal_stream = journal.open("xb")
    journal_hasher = q.hashlib.sha256()
    knowledge = dict(knownCalls=quota["knownCalls"], knownCostYuan=quota["knownCostYuan"], unknowns=quota["holds"],
                     pending={}, bindings={}, retired={}, runtimeStarted=True, activeSide="F", activeQuestion=None)
    previous = None; sequence = 0; total_bytes = 0
    def emit(context, event_delta=None, knowledge_delta=None):
        nonlocal previous, sequence, total_bytes
        event = dict(format=q.FORMAT, planSha256=plan_sha, sequence=sequence, previousSha256=previous,
                     context=context, runtimeRawSha256=q.digest(raw_quota), runtimeRawBase64=base64.b64encode(raw_quota).decode(),
                     quota=quota, knowledgeDelta=knowledge_delta or [],
                     stateSha256=state_sha, ledgerSha256=ledger_sha, sendEventDelta=event_delta or {},
                     evidence=dict(syntheticStorageShapeOnly=True))
        raw = q.packed(event) + b"\n"; journal_stream.write(raw); journal_hasher.update(raw)
        total_bytes += len(raw); previous = q.digest(raw); sequence += 1
    emit("SYNTHETIC_GENESIS", knowledge_delta=q.changes({}, knowledge))
    for question in range(history_questions):
        for stage in ("VERIFY", "STATE_WRITE", "QUESTION_BEGIN"): emit(stage)
        for index in range(question * 2, question * 2 + 2):
            identity = f"synthetic-hold-{index}"; token = f"{index:032x}"
            hold = dict(requestId=f"synthetic-request-{question}", callNumber=index % 2 + 1, promptSha256="a" * 64, status="PENDING")
            tracked = dict(hold=hold, questionId=f"SYNTHETIC-{question:04d}")
            knowledge["pending"][identity] = tracked
            emit("PROXY_BEFORE", knowledge_delta=[dict(path=["pending", identity], value=tracked)])
            emit("LEDGER_WRITE", {k: v for k, v in groups[index].items() if k.endswith("-permit.json")})
            knowledge["bindings"][identity] = token
            emit("PERMIT_BINDING", knowledge_delta=[dict(path=["bindings", identity], value=token)])
            emit("LEDGER_WRITE", {k: v for k, v in groups[index].items() if not k.endswith("-permit.json")})
            emit("PROXY_AFTER")
            knowledge["pending"].pop(identity); retired = dict(hold=hold, outcome="KNOWN", token=token)
            knowledge["retired"][identity] = retired
            emit("JAVA_SETTLEMENT", knowledge_delta=[dict(path=["pending", identity], removed=True),
                dict(path=["retired", identity], value=retired)])
        emit("HTTP_RETURN"); emit("QUESTION_END")
    journal_stream.close()
    head = dict(planSha256=plan_sha, sequence=sequence-1, eventSha256=previous,
                journalBytes=total_bytes, journalSha256=journal_hasher.hexdigest())
    state[q.ANCHOR] = head; ledger[q.ANCHOR] = head
    plain(batch / "state.json", state); plain(batch / "send-ledger.json", ledger); plain(batch / "candidate-quota-checkpoint.json", head)
    return dict(planSha256=plan_sha, item=item, questions=questions, historyQuestions=history_questions,
                initialObservations=sequence, initialJournalBytes=total_bytes,
                initialMeanEventBytes=round(total_bytes/sequence, 2), runtimeQuotaBytes=len(raw_quota))


def measure(shape):
    batch = n.d.BATCH
    timings = {}; parts = {}; sequences = {}
    def timed(name, action):
        started = time.perf_counter(); result = action()
        timings[name] = round((time.perf_counter() - started) * 1000, 3)
        parts[name] = dict(n.quota_monitor().last_metrics)
        sequences[name] = q.read(batch / "candidate-quota-checkpoint.json")["sequence"]
        print(json.dumps(dict(questions=shape["questions"], stage=name, elapsedMs=timings[name])), flush=True)
        return result
    with n.controller_locked():
        monitor = n.quota_monitor()
        timed("firstNewInstanceFull", lambda: monitor.observe("PERF_PREQUESTION_FULL"))
        gate = timed("gateOpenFull", lambda: n.SendGate(batch / "send-ledger.json", shape["planSha256"], "DEV_F",
            clock=lambda: datetime(2026, 11, 21, 12, tzinfo=n.d.BEIJING), monotonic=time.monotonic))
        state = q.read(monitor.state); state["requestsStarted"] += 1
        timed("admissionBeforeClock", lambda: n.write(monitor.state, state, replace=True))
        sequence_before_begin = sequences["admissionBeforeClock"]
        measured_started = time.perf_counter()
        timed("begin", lambda: gate.begin(shape["item"], time.monotonic() + 15.))
        callback_names = []
        for attempt in (1, 2):
            user = "=== 用户问题 ===\n" + shape["item"]["question"] + "\n"
            if attempt == 2: user += "\n\n=== 上一次 SQL 与评估反馈 ===\nsynthetic correction"
            payload = dict(model="deepseek-flash", temperature=0, max_tokens=8192,
                messages=[dict(role="system", content="synthetic performance"), dict(role="user", content=user)])
            current = q.read(monitor.runtime)
            hold_id = f"tail-hold-{attempt}"
            current["holds"][hold_id] = dict(requestId="tail-request", callNumber=attempt,
                promptSha256=q.prompt_sha(payload), status="PENDING")
            java_snapshot(monitor.runtime, current)  # Java writes are simulated, never monitor shortcuts.
            before_name, after_name = f"before{attempt}", f"after{attempt}"
            permit = timed(before_name, lambda: gate.before(payload, q.packed(payload)))
            body = b'{"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":1}}'
            result = dict(responseBodyRaw=body, responseBodySha256=q.digest(body), responseBytes=len(body),
                responseComplete=True, upstreamAttempted=True, responseJson=json.loads(body))
            timed(after_name, lambda: gate.after(permit, result))
            current = q.read(monitor.runtime); current["holds"].pop(hold_id)
            current["knownCalls"] += 1; current["knownCostYuan"] = q.money(q.decimal(current["knownCostYuan"]) + q.Decimal(".000005"))
            java_snapshot(monitor.runtime, current)
            callback_names.extend((before_name, after_name))
        evidence = batch / "http-answers" / (shape["item"]["id"] + ".json")
        response = dict(status="SUCCESS", budget=dict(requestId="tail-request", modelCalls=2, timeoutMs=15000,
            maxModelCalls=2, termination="COMPLETE"))
        def local_return(*args, **kwargs):
            raw = q.packed(response); n.write_bytes(evidence.with_suffix(".body.bin"), raw)
            n.raw_write(evidence, dict(responseComplete=True, responseBodySha256=q.digest(raw), responseBytes=len(raw)))
            return 200, response
        with patch.object(n, "_old_http_json", side_effect=local_return), patch.object(n, "_real_endpoints", {"http://synthetic.invalid"}):
            timed("httpReturnAndEnd", lambda: n.http_json("http://synthetic.invalid/api/ask", evidence=evidence))
        timed("outerIdempotentEnd", gate.end)
        timings["beginThroughHttpAndEnd"] = round((time.perf_counter() - measured_started) * 1000, 3)
        timings["fourCallbacksCombined"] = round(sum(timings[name] for name in callback_names), 3)
        sequence_after_end = sequences["outerIdempotentEnd"]
        final = timed("postQuestionFull", lambda: monitor.observe("PERF_POSTQUESTION_FULL"))
        require = q.require
        require(sequence_after_end - sequence_before_begin == 5, "Expected exactly five timed durable commits")
        require(sequences["begin"] == sequence_before_begin and sequences["outerIdempotentEnd"] == sequences["httpReturnAndEnd"],
                "begin/end unexpectedly appended observations")
        require(final["knownCalls"] == 67 + shape["questions"] * 2 and len(final["holds"]) == 3,
                "Final synthetic liability/settlement mismatch")
        latest = monitor._load()[1]
        require(latest["knowledge"].get("activeQuestion") is None and not latest["knowledge"]["pending"], "Question did not close/settle")
    return {**shape, "timingsMs": timings, "parts": parts, "sequences": sequences,
            "timedCommitCount": sequence_after_end-sequence_before_begin,
            "commonEvents": len(list((batch / "send-ledger-events").iterdir())),
            "finalLedgerBytes": (batch / "send-ledger.json").stat().st_size,
            "engineeringTargets": dict(fourCallbacksAtMost1000Ms=timings["fourCallbacksCombined"] <= 1000,
                beginThroughHttpAndEndAtMost1500Ms=timings["beginThroughHttpAndEnd"] <= 1500),
            "note": "One sample per scale; first new instance has no decode cache, OS cache uncontrolled; no application P95/accuracy claim"}


def plain_writer(path, value, *, replace=False):
    # Production persistent writer, with owned() restricted to this fixture.
    return n.raw_write(path, value, replace=replace)


if __name__ == "__main__":
    OUTPUT.mkdir(parents=True, exist_ok=False)
    results = []
    for questions in (102, 902):
        batch = OUTPUT / str(questions)
        n.d.BATCH = batch
        shape = build(questions)
        with patch.object(n, "_old_real_key", side_effect=AssertionError("Key forbidden")), \
             patch.object(socket.socket, "connect", side_effect=AssertionError("Network forbidden")), \
             patch.object(subprocess, "Popen", side_effect=AssertionError("Process forbidden")):
            result = measure(shape)
        results.append(result)
        print(json.dumps(result), flush=True)
    plain(OUTPUT / "verification.json", dict(results=results, supplierCalls=0, keyReads=0, networkCalls=0, javaProcesses=0, sqlCalls=0,
          noP95OrAccuracyClaim=True, driverSha256=n.sha(Path(n.__file__)), helperSha256=n.sha(Path(q.__file__)),
          benchmarkSha256=n.sha(Path(__file__))))
