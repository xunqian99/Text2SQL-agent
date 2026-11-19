"""Independent D submission-1 audit. Loopback or local file operations only.

This does not call run_next/real_key or read any unseen dataset.
"""
from __future__ import annotations

import hashlib
import importlib
import io
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "scripts"))
import run_replan_stage_d as d
from replan_stage_d_proxy import ModelProxy

EXPECTED_PLAN = "fc13d6c9ec0eddb5909c32d93dd69fde3a4ce32632b758fd32674c90025813ce"


def save(name, value):
    d.write(OUT / name, value)


def identities():
    plan = d.read(d.BATCH / "plan.json")
    assert d.sha(d.BATCH / "plan.json") == EXPECTED_PLAN
    checked, git_checked, git_absent = [], [], []
    for path, expected in plan["files"].items():
        actual = d.sha(ROOT / path)
        assert actual == expected, path
        checked.append(path)
        if "/tool-runtime/" in path:
            continue
        result = subprocess.run(["git", "show", "HEAD:" + path], cwd=ROOT,
                                env=d.clean_env(), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, check=False)
        if result.returncode:
            git_absent.append(path)
        else:
            assert hashlib.sha256(result.stdout).hexdigest() == expected, path
            git_checked.append(path)
    jars = []
    for side, item in plan["systems"].items():
        assert d.sha(ROOT / item["jar"]) == item["sha256"]
        jars.append(dict(side=side, sha256=item["sha256"]))
    preflight = d.read(d.BATCH / "preflight.json")
    assert preflight["passed"] and preflight["planSha256"] == EXPECTED_PLAN
    for path, expected in preflight["evidence"].items():
        assert d.sha(ROOT / path) == expected, path
    state = d.read(d.BATCH / "state.json")
    ledger = d.read(d.BATCH / "send-ledger.json")
    assert state["requestsStarted"] == 0 and not state["rounds"] and state["terminal"] is None
    assert not ledger["calls"] and not ledger["rejections"] and not ledger["breaches"]
    save("identity-verification.json", dict(planSha256=EXPECTED_PLAN,
        boundFileCount=len(checked), gitRawByteMatches=len(git_checked),
        gitNotPresent=git_absent, runtimeFileCount=len(checked)-len(git_checked)-len(git_absent),
        preflightEvidenceVerified=len(preflight["evidence"]), jars=jars,
        initialStateSha256=d.sha(d.BATCH / "state.json"),
        initialLedgerSha256=d.sha(d.BATCH / "send-ledger.json"), providerCalls=0))
    return plan


def supplied_suite():
    temp_root = OUT / "unit-temp"
    temp_root.mkdir()
    tempfile.tempdir = str(temp_root)
    loader = unittest.TestLoader()
    suite = unittest.TestSuite(loader.loadTestsFromModule(importlib.import_module(name))
                              for name in ("test_replan_stage_d", "test_replan_stage_d_proxy"))
    stream = io.StringIO()
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    d.write_bytes(OUT / "supplied-suite.log", stream.getvalue().encode("utf-8"))
    save("supplied-suite.json", dict(testsRun=result.testsRun, failures=len(result.failures),
        errors=len(result.errors), skipped=len(result.skipped), passed=result.wasSuccessful(),
        scope="Final fixed code, all supplied controller and proxy tests; loopback only", providerCalls=0))


def progression_counterexample():
    dev = dict(total=100, correct=71, timeoutCount=9,
               byLayer={"multi_join_deep": dict(total=10, correct=4),
                        "business_semantics": dict(total=13, correct=6)})
    dev_result = d.dev_gate(dev)
    summary = dict(total=100, correct=89, wallP95Ms=13000, timeoutCount=4,
                   accounting=dict(unknownUsageCalls=0))
    rounds = {"DEV_F": dict(status="COMPLETED", gate=dev_result),
              "TARGETS": dict(status="COMPLETED", gate=dict(passed=True))}
    for name in d.FORMAL:
        rounds[name] = dict(status="COMPLETED", summary=dict(summary))
    formal_result = d.formal_gate(rounds)
    next_after_dev = d.next_round(dict(rounds={"DEV_F": rounds["DEV_F"]}, terminal=None))
    try:
        d.next_round(dict(rounds=rounds, terminal=None))
    except d.Refused as error:
        final_transition = str(error)
    else:
        final_transition = "UNEXPECTEDLY_NO_REFUSAL"
    save("safety-progression-counterexample.json", dict(
        planSha256=EXPECTED_PLAN,
        inputContainsIndependentRuntimeSafetyEvidence=False,
        devGate=dev_result, nextAfterDev=next_after_dev, formalGate=formal_result,
        formalCompletionTransition=final_transition,
        codeObservation="run_replan_stage_d.py:674 writes newDangerousAllowances=0 unconditionally; dev/formal gates also default absence to zero",
        demonstrates="A performance/accuracy-qualified real batch can advance without batch-bound independent dangerous-allowance evidence",
        expected="Pending independent runtime safety judgment cannot count as zero or unlock subsequent rounds",
        providerCalls=0))


def duplicate_response_retention():
    raw = b'{"choices":[],"usage":{"prompt_tokens":10,"prompt_tokens":11},"audit_marker":"original-duplicate-body"}'
    out = OUT / "duplicate-upstream-body"; out.mkdir()
    class Stub(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            self.rfile.read(int(self.headers["Content-Length"]))
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    ledger = out / "ledger.json"
    d.write(ledger, dict(planSha256=EXPECTED_PLAN, calls=[], rejections=[], breaches=[]))
    gate = d.SendGate(ledger, EXPECTED_PLAN, "INDEPENDENT_DUPLICATE_RESPONSE",
                      clock=lambda: datetime(2026, 11, 21, 12, tzinfo=d.BEIJING))
    gate.begin(dict(id="PUBLIC_SYNTHETIC", question="原问题"), time.monotonic()+15)
    payload = dict(model="deepseek-flash", temperature=0, max_tokens=8192,
                   messages=[dict(role="system", content="只读"), dict(role="user", content="=== 用户问题 ===\n原问题\n")])
    try:
        with ModelProxy("independent-loopback-only-key", gate.before, gate.after,
             upstream_url=f"http://127.0.0.1:{server.server_port}/v1/chat/completions",
             allow_loopback_test=True) as proxy:
            request = urllib.request.Request(proxy.base_url+"/v1/chat/completions",
                data=json.dumps(payload, ensure_ascii=False).encode(), headers={"Content-Type":"application/json"})
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(request, timeout=20) as response:
                received = response.read()
            proxy.drain(timeout_seconds=30)
        gate.end()
        assert received == raw and len(gate.data["calls"]) == 1
        call = gate.data["calls"][0]
        assert call["result"]["responseJson"] is None and call["usageKnown"] is False
        assert call["result"]["responseBodySha256"] == hashlib.sha256(raw).hexdigest()
        d.write_bytes(out/"independent-fixture-original-body.bin", raw)
        save("duplicate-upstream-retention-counterexample.json", dict(
            planSha256=EXPECTED_PLAN, localActualRequests=1, externalProviderCalls=0,
            originalBodyPassedThroughToLocalClient=received==raw,
            persistedResultKeys=sorted(call["result"]),
            persistedResponseJson=call["result"]["responseJson"],
            originalBodyBytesPersistedByControllerOrProxy=False,
            onlyDigestAndLengthRetained=True, heldYuan=str(gate.committed()),
            terminalBreach=gate.data["breaches"],
            demonstrates="The fixed implementation cannot recover duplicate-key original supplier bytes from its evidence; the independent fixture body is not implementation evidence"))
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)


def real_jar_corrections(plan):
    prepared = d.read(d.BATCH / "dev-reference.json")
    item = prepared["items"][0]
    assert item["id"] == "T1-002"
    received = []
    current_side = [None]
    class Stub(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            value = d.strict_json(raw)
            attempt = sum(x["side"] == current_side[0] for x in received) + 1
            received.append(dict(side=current_side[0], attempt=attempt, rawSha256=hashlib.sha256(raw).hexdigest(),
                                 payload=value))
            sql = ("SELECT stage_d_review_missing_column FROM orders LIMIT 1" if attempt == 1 else
                   "SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'")
            response = dict(id="independent-local-fixture", object="chat.completion", created=1,
                model="deepseek-flash", choices=[dict(index=0, message=dict(role="assistant", content=sql), finish_reason="stop")],
                usage=dict(prompt_tokens=10, completion_tokens=10, total_tokens=20,
                           prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=10))
            body = json.dumps(response).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    outputs = []
    try:
        for side in ("B", "F"):
            current_side[0] = side
            out = OUT / "actual-correction" / side; out.mkdir(parents=True)
            ledger = out / "local-ledger.json"
            d.write(ledger, dict(planSha256=EXPECTED_PLAN, calls=[], rejections=[], breaches=[]))
            gate = d.SendGate(ledger, EXPECTED_PLAN, "INDEPENDENT_CORRECTION_"+side,
                              clock=lambda: datetime(2026, 11, 21, 12, tzinfo=d.BEIJING))
            with ModelProxy("independent-loopback-only-key", gate.before, gate.after,
                 upstream_url=f"http://127.0.0.1:{server.server_port}/v1/chat/completions",
                 allow_loopback_test=True) as proxy:
                with d.application(side, proxy, out, offline=True) as (url, token):
                    start = time.monotonic(); gate.begin(item, start+15)
                    evidence = out/"answer.json"
                    try:
                        status, body = d.http_json(url+"/api/ask", data=dict(question=item["question"]),
                                                  token=token, timeout=20, evidence=evidence)
                    finally:
                        gate.end()
                    meta = d.read(evidence)
                    row = dict(id=item["id"], httpStatus=status, body=body,
                               returnedAt=meta["returnedAt"], httpEvidence=evidence.relative_to(ROOT).as_posix(),
                               wallLatencyMs=(meta["httpReadCompletedMonotonic"]-start)*1000)
                    d.write(out/"answer-parsed.json", row)
                proxy.drain(timeout_seconds=30)
                proxy_errors = list(proxy.errors)
            reconciliation = gate.reconcile([row])
            d.write(out/"reconciliation.json", reconciliation)
            calls = [r for r in received if r["side"] == side]
            assert len(calls) == 2 and not gate.data["rejections"] and not gate.data["breaches"] and not proxy_errors
            assert status == 200 and body["status"] == "SUCCESS"
            assert body["rows"] == item["gold"]["rows"]
            assert body["budget"]["modelCalls"] == 2
            outputs.append(dict(side=side, actualHttpAnswers=1, localModelRequests=len(calls),
                firstAndCorrectionAdmitted=True, finalStatus=body["status"], runtimeModelCalls=body["budget"]["modelCalls"],
                rowsMatchFrozenReference=True, summary=gate.summary(reconciliation=reconciliation),
                proxyErrors=proxy_errors))
        save("actual-correction-payloads.json", dict(scope="public T1-002, local substitute; no supplier request", calls=received, providerCalls=0))
        save("actual-correction-results.json", dict(planSha256=EXPECTED_PLAN, systems=outputs,
            localActualHttpAnswers=2, loopbackModelRequests=4, externalProviderCalls=0,
            notModelAccuracy=True))
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)


def main():
    action = sys.argv[1]
    plan = identities() if action == "identities" else d.read(d.BATCH/"plan.json")
    if action == "units": supplied_suite(); progression_counterexample()
    elif action == "correction": real_jar_corrections(plan)
    elif action == "retention": duplicate_response_retention()


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
