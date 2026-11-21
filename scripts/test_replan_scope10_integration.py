"""Free integration of the final controller, frozen Java, real local sockets.

Synthetic loopback usage is never combined with the historical provider bill.
"""
from __future__ import annotations

import copy
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import run_replan_stage_d_scope10 as c
from replan_accounting_proxy import ModelProxy

d, n = c.d, c.n


def preflight():
    c.configure()
    plan = d.read(c.BATCH / "plan.json")
    c.verify(plan)
    plan_sha = c.sha(c.BATCH / "plan.json")
    output = c.BATCH / ("local-preflight-" + uuid.uuid4().hex[:10])
    output.mkdir()
    prepared = d.read(c.BATCH / "dev-reference.json")
    fixture = prepared["items"][0]
    c.require(fixture["id"] == "T1-002", "Public smoke fixture drift")
    sql = "SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'"
    received, current = [], {"side": None, "ordinal": 0}

    class Stub(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            payload = json.loads(raw)
            side, ordinal = current["side"], current["ordinal"]
            received.append(dict(side=side, ordinal=ordinal, bytes=len(raw), model=payload.get("model")))
            # F2 outlives the answer deadline, while F3 is active. This tests
            # that the callback retains F2's token/fence instead of F3's.
            delay = 20 if side == "F" and ordinal == 2 else 6 if side == "F" and ordinal == 3 else 0
            if delay:
                time.sleep(delay)
            value = dict(id="local-only", object="chat.completion", created=1, model="deepseek-flash",
                         choices=[dict(index=0, message=dict(role="assistant", content=sql), finish_reason="stop")],
                         usage=dict(prompt_tokens=10, completion_tokens=10, total_tokens=20,
                                    prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=10))
            data = json.dumps(value).encode("utf-8")
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except OSError:
                pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    systems = []
    try:
        for side in ("B", "F"):
            batch = output / side
            batch.mkdir()
            c.configure(batch)
            count = 3 if side == "F" else 1
            items = [dict(id="LOCAL-" + side + "-" + str(i), question=fixture["question"])
                     for i in range(1, count + 1)]
            n.raw_write(batch / "dev-reference.json", dict(items=items))
            local_plan = dict(format="scope10-isolated-loopback-preflight-v1",
                files={(batch / "dev-reference.json").relative_to(c.ROOT).as_posix(): c.sha(batch / "dev-reference.json")})
            with n.controller_locked():
                c.initialize(batch, local_plan)
                state = d.read(batch / "state.json")
                deadline = time.monotonic() + 180
                gate = c.SendGate(batch / "send-ledger.json", c.sha(batch / "plan.json"), "DEV_F", round_deadline=deadline)
                out = batch / "DEV_F"
                out.mkdir()
                state["rounds"]["DEV_F"] = dict(status="RUNNING", side=side)
                n.write(batch / "state.json", state, replace=True)
                rows = []
                current["side"] = side
                with ModelProxy("synthetic-local-key-no-provider", gate.before, gate.after,
                        upstream_url=f"http://127.0.0.1:{server.server_port}/v1/chat/completions",
                        allow_loopback_test=True, check_send=c.check_send, durable_before_answer=True) as proxy:
                    with n.application(side, proxy, out) as (url, token):
                        for ordinal, item in enumerate(items, 1):
                            current["ordinal"] = ordinal
                            n.quota_monitor().observe("BEFORE_QUESTION")
                            state["requestsStarted"] += 1
                            n.write(batch / "state.json", state, replace=True)
                            started = time.monotonic()
                            gate.begin(item, started + 15)
                            evidence = out / "http-answers" / (item["id"] + ".json")
                            try:
                                status, body = n.http_json(url + "/api/ask", data={"question": item["question"]},
                                    token=token, timeout=20, evidence=evidence)
                            finally:
                                gate.end()
                            meta = d.read(evidence)
                            row = dict(id=item["id"], httpStatus=status, body=body, returnedAt=meta["returnedAt"],
                                wallLatencyMs=(meta["httpReadCompletedMonotonic"] - started) * 1000,
                                httpEvidence=evidence.relative_to(c.ROOT).as_posix())
                            rows.append(row)
                            if side == "F" and ordinal == 2:
                                c.require(body.get("status") in ("REQUEST_TIMEOUT", "GENERATION_FAILED")
                                          and not body.get("sql") and body["budget"].get("unknownUsageCalls") == 1
                                          and 14000 <= row["wallLatencyMs"] < 18000,
                                          "Real Java late answer escaped its 15-second budget")
                            else:
                                c.require(status == 200 and body.get("status") == "SUCCESS",
                                          "Real local Java success path failed: " + item["id"])
                            time.sleep(2.15)
                    proxy.drain(timeout_seconds=max(0, deadline - time.monotonic()))
                    c.require(not proxy.errors, "Real loopback proxy callback error: " + repr(proxy.errors))
                    n.quota_monitor().observe("ROUND_END")
                    metrics = copy.deepcopy(proxy.metrics)
                    decisions = proxy.get_answer_decisions()
                    n.write(out / "proxy-answer-decisions.json", decisions)
                c.require(not gate.data["rejections"] and not gate.data["breaches"], "Local send/usage gate failed")
                quota = d.read(batch / "candidate-cost-quota.json")
                seed = d.read(batch / "candidate-quota-initial-snapshot.json")
                c.require(all(quota["holds"].get(k) == v for k, v in seed["holds"].items()), "Legacy UNKNOWN lost")
                c.require(len(gate.data["calls"]) == count and all(call["usageKnown"] for call in gate.data["calls"]),
                          "Single-send accounting evidence is incomplete")
                if side == "F":
                    late = gate.data["calls"][1]
                    c.require(late["result"].get("late") is True
                              and late["result"].get("accountingOnly") is True
                              and late["result"].get("answerEligible") is False,
                              "Late response flags did not freeze the original answer")
                    c.require(quota["knownCalls"] == 141 and len(quota["holds"]) == 8
                              and all(h["status"] == "UNKNOWN" for h in quota["holds"].values()),
                              "Candidate late UNKNOWN was incorrectly released")
                    c.require(c.deadline_counts(rows, gate.data["calls"], decisions)["timeoutCount"] == 1,
                              "GENERATION_FAILED must not hide an actual answer deadline")
                else:
                    c.require(quota == seed, "Baseline must not edit candidate quota")
                reconciliation = gate.reconcile(rows)
                n.write(out / "http-reconciliation.json", reconciliation)
                n.write(out / "responses.json", dict(items=rows, syntheticLocalOnly=True))
                # Frozen scorer consumes one genuine local HTTP success plus
                # 99 explicitly synthetic Gold rows: plumbing, not accuracy.
                mixed = [dict(rows[0], id=fixture["id"]) if item["id"] == fixture["id"]
                         else dict(id=item["id"], httpStatus=200, body={"status": "SUCCESS", **item["gold"]})
                         for item in prepared["items"]]
                n.write(out / "score-fixture.json", dict(items=mixed, syntheticLocalOnly=True))
                d.bridge(plan["classpath"], "score", c.BATCH / "dev-reference.json", out / "score-fixture.json", out / "score.json")
                score = d.read(out / "score.json")
                c.require(score["total"] == 100 and score["correct"] == 100, "Frozen HTTP scorer integration failed")
                systems.append(dict(side=side, actualLocalHttp=count, localModelPosts=count,
                    knownUsage=count, candidateKnownCalls=quota["knownCalls"], candidateUnknown=len(quota["holds"]),
                    lateSqlAccepted=0, scorerCorrect=100, scorerTotal=100, scorerIsNotModelAccuracy=True,
                    proxyMetrics=metrics, httpWallMs=[r["wallLatencyMs"] for r in rows]))
                n.write(batch / "result.json", systems[-1])
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)
        c.configure()
    result = dict(passed=True, planSha256=plan_sha, systems=systems,
                  localSupplierPosts=len(received), externalProviderCalls=0, realKeyReads=0,
                  lateSqlAccepted=0, syntheticUsageNotAddedToHistoricalBill=True,
                  evidence={p.relative_to(c.ROOT).as_posix(): c.sha(p) for p in output.rglob("*") if p.is_file()})
    n.raw_write(c.BATCH / "preflight.json", result)
    print(json.dumps({k: v for k, v in result.items() if k != "evidence"}, ensure_ascii=False), flush=True)
    return result


if __name__ == "__main__":
    preflight()
