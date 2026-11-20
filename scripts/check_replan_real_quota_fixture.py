"""Free frozen-C1 integration of the real observation hooks, in new fixtures.

Only loopback synthetic upstream is constructible here. No human authorization
is minted, no real key is resolved, no actual registration is prepared, no score
or model-accuracy claim is produced. Run once with an explicit new output path.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
from unittest.mock import patch

import replan_quota_monitor as q
import run_replan_stage_d_reregistered as n


def initialize(batch):
    n.d.BATCH = batch
    batch.mkdir(parents=True, exist_ok=False)
    # The inherited quota is permitted aggregate/identity evidence, not paid SQL.
    seed_raw = (n.OLD_BATCH / "candidate-cost-quota.json").read_bytes()
    reference = batch / "dev-reference.json"
    n.write_bytes(reference, (n.OLD_BATCH / "dev-reference.json").read_bytes())
    n.raw_write(batch / "plan.json", dict(syntheticEngineeringFixture=True,
                frozenCandidate=n.d.SYSTEMS["F"], sourceSha256=n.sha(Path(n.__file__)),
                monitorSha256=n.sha(Path(q.__file__)), noPaidAuthorization=True,
                files={reference.relative_to(n.ROOT).as_posix(): n.sha(reference)}))
    plan_sha = n.sha(batch / "plan.json")
    n.write_bytes(batch / "candidate-cost-quota.json", seed_raw)
    n.write_bytes(batch / "candidate-quota-initial-snapshot.json", seed_raw)
    n.raw_write(batch / "candidate-quota-genesis-policy.json", {**q.POLICY, "seedSha256": q.digest(seed_raw)})
    n.raw_write(batch / "state.json", dict(planSha256=plan_sha, requestsStarted=0, rounds={}, terminal=None))
    n.raw_write(batch / "send-ledger.json", dict(planSha256=plan_sha,
                legacyManifestSha256=n.PINNED["paid-interruption-manifest.json"], calls=[], rejections=[], breaches=[]))
    n.quota_monitor().initialize()
    return plan_sha


def validate_existing(batch):
    batch = batch.resolve()
    n.require(batch.is_relative_to(n.CANDIDATE.resolve()) and batch.is_dir(), "Only an existing candidate fixture may be cold-validated")
    n.d.BATCH = batch
    plan = n.d.read(batch / "plan.json")
    n.require(plan.get("syntheticEngineeringFixture") is True and plan.get("noPaidAuthorization") is True
              and plan.get("sourceSha256") == n.sha(Path(n.__file__))
              and plan.get("monitorSha256") == n.sha(Path(q.__file__))
              and plan.get("frozenCandidate") == n.d.SYSTEMS["F"]
              and not (batch / n.AUTH_NAME).exists(), "Existing fixture/source identity mismatch")
    with patch.object(n, "_old_real_key", side_effect=AssertionError("Cold child cannot read real credentials")), \
         patch.object(socket.socket, "connect", side_effect=AssertionError("Cold child cannot use network")):
        with n.controller_locked():
            monitor = q.QuotaMonitor(batch, n.sha(batch / "plan.json"), n.raw_write, n.observation_owner)
            started = time.perf_counter(); current = monitor.observe("CHILD_PROCESS_COLD_VALIDATE")
            elapsed = (time.perf_counter() - started) * 1000
            n.raw_write(batch / "child-cold-verification.json", dict(passed=True, independentPythonProcess=True,
                        knownCalls=current["knownCalls"], knownCostYuan=current["knownCostYuan"],
                        elapsedMs=elapsed, parts=monitor.last_metrics, networkCalls=0, keyReads=0))
    print(json.dumps(dict(passed=True, independentPythonProcess=True, elapsedMs=elapsed)), flush=True)


def running(batch):
    state = n.d.read(batch / "state.json")
    state["rounds"]["DEV_F"] = dict(status="RUNNING", side="F", syntheticEngineeringOnly=True)
    n.write(batch / "state.json", state, replace=True)


def run(output):
    output = output.resolve()
    n.require(output.is_relative_to(n.CANDIDATE.resolve()) and not output.exists(),
              "Fixture output must be a new candidate subdirectory")
    actual_batch = n.BATCH
    actual_before = {p.name: n.sha(p) for p in actual_batch.iterdir() if p.is_file()} if actual_batch.exists() else None
    output.mkdir(parents=True)
    n.require(n.sha(n.ROOT / n.d.SYSTEMS["F"]["jar"]) == n.d.SYSTEMS["F"]["sha256"], "Frozen C1 JAR identity drift")
    upstream_calls = []
    class Stub(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            n.require(self.path == "/v1/chat/completions", "Local fixture path drift")
            request_raw = self.rfile.read(int(self.headers["Content-Length"]))
            upstream_calls.append(dict(requestBodySha256=q.digest(request_raw), requestBytes=len(request_raw)))
            sql = "SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'"
            body = dict(id="synthetic-engineering-fixture", object="chat.completion", created=1, model="deepseek-flash",
                        choices=[dict(index=0, message=dict(role="assistant", content=sql), finish_reason="stop")],
                        usage=dict(prompt_tokens=10, completion_tokens=10, total_tokens=20,
                                   prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=10))
            raw = json.dumps(body).encode("utf-8")
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    original_batch = n.d.BATCH
    results = []
    try:
        with patch.object(n, "_old_real_key", side_effect=AssertionError("No real credential reader in free fixture")):
            for ready_only in (True, False):
                batch = output / ("ready-only" if ready_only else "known-one")
                plan_sha = initialize(batch)
                out = batch / "DEV_F"; out.mkdir()
                with n.controller_locked():
                    running(batch)
                    gate = n.SendGate(batch / "send-ledger.json", plan_sha, "DEV_F",
                                      clock=lambda: datetime(2026, 11, 21, 12, tzinfo=n.d.BEIJING))
                    with n.StableModelProxy("synthetic-local-key", gate.before, gate.after,
                            upstream_url=f"http://127.0.0.1:{server.server_port}/v1/chat/completions", allow_loopback_test=True) as proxy:
                        with n.application("F", proxy, out, offline=False) as (url, token):
                            n.check_candidate_quota()
                            if not ready_only:
                                # Public Gold reference, never a previous paid model answer.
                                prepared = n.d.read(n.OLD_BATCH / "dev-reference.json")
                                item = next(i for i in prepared["items"] if i["id"] == "T1-002")
                                state = n.d.read(batch / "state.json"); state["requestsStarted"] = 1
                                n.write(batch / "state.json", state, replace=True)
                                began = time.monotonic(); gate.begin(item, began + 15)
                                try:
                                    status, body = n.http_json(url + "/api/ask", data={"question": item["question"]}, token=token,
                                                timeout=20, evidence=out / "http-answers" / (item["id"] + ".json"))
                                finally:
                                    gate.end()
                                n.require(status == 200 and body.get("status") == "SUCCESS", "Synthetic real HTTP request did not succeed")
                                n.require(body.get("budget", {}).get("modelCalls") == 1, "Expected exactly one local synthetic model call")
                        proxy.drain(timeout_seconds=30)
                        n.require(not proxy.errors, "Real monitor proxy callback failure: " + str(proxy.errors))
                    current = n.check_candidate_quota()
                    restarted = q.QuotaMonitor(batch, plan_sha, n.raw_write, n.observation_owner)
                    restarted.observe("FRESH_INSTANCE_VALIDATE")
                    n.require(len(gate.data["calls"]) == (0 if ready_only else 1), "Synthetic permit count differs")
                    n.require(current["knownCalls"] == (67 if ready_only else 68), "Java known count differs from expected synthetic settlement")
                    n.require(q.decimal(current["knownCostYuan"]) == q.Decimal(".16312188") + (q.Decimal(0) if ready_only else q.Decimal(".00005")),
                              "Java/proxy configured synthetic cost mismatch")
                    n.require(len(current["holds"]) == 3 and all(h["status"] == "UNKNOWN" for h in current["holds"].values()),
                              "Inherited UNKNOWN identities did not survive real lifecycle")
                    result = dict(readyOnly=ready_only, readyReached=True, monitorHooksActive=True,
                                  questions=0 if ready_only else 1, proxySyntheticCalls=len(gate.data["calls"]),
                                  knownCalls=current["knownCalls"], knownCostYuan=current["knownCostYuan"],
                                  freshInstanceValidationPassed=True, noAccuracyClaim=True, externalProviderCalls=0)
                child = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--validate-existing", str(batch)],
                                       capture_output=True, text=True, timeout=60)
                n.write_bytes(batch / "child-process.stdout.txt", child.stdout.encode("utf-8"))
                n.write_bytes(batch / "child-process.stderr.txt", child.stderr.encode("utf-8"))
                n.require(child.returncode == 0, "Independent cold-validation child failed")
                result["independentChildColdValidationPassed"] = True
                n.raw_write(batch / "fixture-verification.json", result); results.append(result)
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)
        n.d.BATCH = original_batch
    actual_after = {p.name: n.sha(p) for p in actual_batch.iterdir() if p.is_file()} if actual_batch.exists() else None
    n.require(actual_after == actual_before, "Actual registration changed during synthetic integration")
    n.require(len(upstream_calls) == 1, "Expected exactly one synthetic loopback upstream")
    n.d.BATCH = output
    try:
        n.raw_write(output / "verification.json", dict(passed=True, results=results, localSyntheticUpstream=upstream_calls,
                    javaProcesses=2, supplierCalls=0, keyReads=0, authorizationFilesCreated=0,
                    actualRegistrationUnchanged=True, noAccuracyClaim=True))
    finally:
        n.d.BATCH = original_batch
    print(json.dumps(dict(passed=True, actualRegistrationUnchanged=True, supplierCalls=0, localSyntheticUpstreamCalls=1)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--output", type=Path); group.add_argument("--validate-existing", type=Path)
    args = parser.parse_args()
    if args.validate_existing: validate_existing(args.validate_existing)
    else: run(args.output)
