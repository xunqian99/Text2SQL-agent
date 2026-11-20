"""One real frozen-JAR request against a literal loopback synthetic model only."""
import argparse
import base64
import hashlib
import http.client
import json
import os
import socket
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
PARENT = HERE.parent
sys.path.insert(0, str(PARENT))
PROBE_SOURCE_RAW = (PARENT/"loopback_accounting_probe.py").read_bytes()
from loopback_accounting_probe import LoopbackAccountingProbe
if (PARENT/"loopback_accounting_probe.py").read_bytes() != PROBE_SOURCE_RAW:
    raise RuntimeError("Probe dependency changed during import")

JAVA = Path(r"C:\Users\xunqian\.jdks\corretto-21.0.11\bin\java.exe")
JAR = ROOT/".private/recovery/replan-c1-20261119/text2sql-agent-C1-submission-1.jar"
JAR_SHA = "51874452c5c0acad14a733fc6d0fbec06c81273aaabf7b16eabc3b4b4653c2c2"
SQL = "SELECT COUNT(*) AS synthetic_count FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'"
QUESTION = "查询2018年的订单数"
ACCESS_TOKEN = "synthetic-real-java-access-token-00000000"
SYNTHETIC_USAGE = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                   "prompt_cache_hit_tokens": 0, "prompt_cache_miss_tokens": 10,
                   "prompt_tokens_details": {"cached_tokens": 0}}
BODY = json.dumps({"id": "synthetic-real-java-probe", "object": "chat.completion", "created": 1,
    "model": "deepseek-flash", "choices": [{"index": 0, "message": {"role": "assistant", "content": SQL},
    "finish_reason": "stop"}], "usage": SYNTHETIC_USAGE}).encode()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def write(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def read(path):
    return json.loads(path.read_bytes())


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def local_http(port, path, *, data=None, timeout=2):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        raw = None if data is None else json.dumps(data, ensure_ascii=False).encode()
        headers = {"Authorization": "Basic " + base64.b64encode(("demo:"+ACCESS_TOKEN).encode()).decode(),
                   "Content-Type": "application/json"}
        conn.request("GET" if data is None else "POST", path, raw, headers)
        response = conn.getresponse()
        return response.status, response.read()
    finally:
        conn.close()


def command(port, base_url, output):
    values = {
        "spring.config.location": "classpath:/application.yml", "spring.profiles.active": "",
        "server.address": "127.0.0.1", "server.port": port,
        "spring.datasource.url": "jdbc:postgresql://localhost:5432/olist", "spring.datasource.username": "text2sql_ro",
        "spring.datasource.password": "text2sql_ro", "spring.datasource.hikari.read-only": "true",
        "spring.ai.model.chat": "none", "spring.ai.model.embedding": "none", "spring.ai.model.image": "none",
        "spring.ai.model.audio.speech": "none", "spring.ai.model.audio.transcription": "none", "spring.ai.model.moderation": "none",
        "agent.eval.enabled": "false", "agent.persistence.enabled": "false",
        "agent.llm.base-url": base_url, "agent.llm.completions-path": "/v1/chat/completions",
        "agent.llm.api-key": "synthetic-local-only", "agent.llm.model": "deepseek-flash",
        "agent.llm.max-tokens": 8192, "agent.llm.temperature": 0, "agent.llm.reasoning-effort": "provider-default",
        "agent.llm.input-price-per-1k": ".001", "agent.llm.output-price-per-1k": ".004", "agent.llm.cache-hit-price-per-1k": ".00002",
        "agent.request-budget.enabled": "true", "agent.request-budget.timeout-ms": 15000,
        "agent.request-budget.max-model-calls": 2, "agent.request-budget.min-retry-remaining-ms": 2000,
        "agent.retrieval.enabled": "true", "agent.retrieval.top-k": 8, "agent.retrieval.column-pruning-enabled": "true",
        "agent.retrieval.join-hints-enabled": "true", "agent.retrieval.join-plan-enabled": "true", "agent.retrieval.join-list-enabled": "false",
        "agent.retrieval.relation-decay": ".4", "agent.retrieval.bridge-repair-enabled": "true", "agent.retrieval.bridge-repair-max-tables": 2,
        "agent.semantic.enabled": "true", "agent.fewshot.enabled": "true", "agent.fewshot.max-examples": 2,
        "agent.fewshot.require-available-tables": "false", "agent.clarification.enabled": "true",
        "agent.self-correction.enabled": "true", "agent.self-correction.max-attempts": 2, "agent.result-checker.enabled": "true",
        "agent.prompt.explicit-reasoning-enabled": "true", "agent.prompt.include-data-profile": "true", "agent.prompt.include-foreign-keys": "true",
        "agent.guard.limit-mode": "APPEND", "agent.db.max-rows": 200, "agent.db.query-timeout-seconds": 10,
        "agent.cache.enabled": "false", "agent.routing.enabled": "false", "agent.tool-use.enabled": "false", "agent.conversation.enabled": "false",
        "agent.observability.enabled": "true", "agent.observability.include-content": "false", "agent.observability.dir": str(output/"runtime-calls"),
        "agent.access.enabled": "true", "agent.access.token": "${AGENT_ACCESS_TOKEN}",
        "agent.cost-quota.enabled": "true", "agent.cost-quota.total-yuan": "1.00", "agent.cost-quota.reservation-yuan": ".10",
        "agent.cost-quota.state-file": str(output/"synthetic-cost-quota.json"),
    }
    return [str(JAVA), "-Duser.timezone=Asia/Shanghai", "-jar", str(JAR), *["--"+k+"="+str(v) for k,v in values.items()]]


def main(output):
    if output.parent != HERE or output.exists():
        raise ValueError("Output must be a new immediate child of probe directory")
    if sha(JAR.read_bytes()) != JAR_SHA:
        raise ValueError("Frozen candidate JAR identity drift")
    manifest = read(ROOT/"reports/replan-stage-d-20261119/reregistration-candidate/authorized-execution-20261120/interrupted-batch-manifest.json")
    for rel, expected in manifest["files"].items():
        if sha((ROOT/rel).read_bytes()) != expected:
            raise ValueError("Actual batch changed before probe")
    output.mkdir()
    launcher_source = Path(__file__).read_bytes()
    (output/"imported-loopback-probe-source.py.txt").write_bytes(PROBE_SOURCE_RAW)
    (output/"launcher-source.py.txt").write_bytes(launcher_source)
    # Names are allowlisted before accessing values; no Key-related variable is read.
    env = {name: os.environ[name] for name in ("SystemRoot", "WINDIR", "PATH", "TEMP", "TMP", "COMSPEC", "PATHEXT") if name in os.environ}
    env["AGENT_ACCESS_TOKEN"] = ACCESS_TOKEN
    write(output/"environment-policy.json", {"allowedInheritedNames": sorted(k for k in env if k != "AGENT_ACCESS_TOKEN"),
        "accessToken": "fixed-synthetic-only", "credentialsRead": 0, "externalEndpointConfigured": False})
    active, calls, callbacks = {}, [], []
    token = uuid.uuid4().hex
    quota_path = output/"synthetic-cost-quota.json"
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            request_raw = self.rfile.read(int(self.headers["Content-Length"]))
            calls.append({"requestBodySha256": sha(request_raw), "requestBytes": len(request_raw), "startedMonotonic": time.monotonic()})
            # Complete 0.4 seconds after the original answer deadline, still before +1 accounting cutoff.
            time.sleep(max(0, active["deadline"]+.4-time.monotonic()))
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(BODY)))
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                self.wfile.write(BODY)
            except ConnectionError as exc:
                calls[-1]["disconnectedWrite"] = type(exc).__name__
            calls[-1]["finishedMonotonic"] = time.monotonic()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = False
    server_thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .01})
    server_thread.start()
    def before(payload, raw):
        pending_raw = quota_path.read_bytes()
        pending = json.loads(pending_raw)
        if len(pending["holds"]) != 1 or next(iter(pending["holds"].values()))["status"] != "PENDING":
            raise AssertionError("Expected exactly one synthetic durable pending hold")
        (output/"quota-before-send.json").write_bytes(pending_raw)
        permit = {"token": token, "upstreamDeadlineMonotonic": active["deadline"], "requestBodySha256": sha(raw), "requestBytes": len(raw)}
        write(output/"synthetic-permit.json", permit)
        return permit
    def after(permit, result):
        copied = dict(result)
        body = copied.pop("responseBodyRaw")
        (output/"synthetic-upstream.body.bin").write_bytes(body)
        copied["token"] = permit["token"]
        callbacks.append(copied)
        write(output/"synthetic-completion.json", copied)
    process = None
    started = time.perf_counter()
    try:
        with LoopbackAccountingProbe(before, after,
                upstream_url=f"http://127.0.0.1:{server.server_port}/v1/chat/completions", grace_seconds=1) as proxy:
            port = free_port()
            args = command(port, proxy.base_url, output)
            write(output/"command.json", {"arguments": args, "allCredentialStringsSynthetic": True})
            with (output/"application.log").open("xb") as log:
                process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
                ready_deadline = time.monotonic()+90
                while time.monotonic() < ready_deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Frozen application exited before health")
                    try:
                        status, health = local_http(port, "/api/health")
                        if status == 200 and json.loads(health).get("llmConfigured"):
                            (output/"health.body.bin").write_bytes(health)
                            break
                    except (OSError, http.client.HTTPException, ValueError):
                        pass
                    time.sleep(.2)
                else:
                    raise TimeoutError("Health readiness timed out")
                initial_raw = quota_path.read_bytes()
                (output/"quota-initial.json").write_bytes(initial_raw)
                if read(quota_path)["holds"] or read(quota_path)["knownCalls"] != 0:
                    raise AssertionError("New synthetic quota was not pristine")
                active["started"] = time.monotonic()
                active["deadline"] = active["started"]+15
                status, answer_raw = local_http(port, "/api/ask", data={"question": QUESTION}, timeout=22)
                answer_at = time.monotonic()
                (output/"http-answer.body.bin").write_bytes(answer_raw)
                answer = json.loads(answer_raw)
                write(output/"http-answer.json", {"httpStatus": status, "body": answer,
                    "wallMs": (answer_at-active["started"])*1000, "responseSha256": sha(answer_raw)})
                write(output/"quota-at-http-return.json", read(quota_path))
                proxy.drain(timeout_seconds=3)
                time.sleep(.1)
                final_raw = quota_path.read_bytes()
                (output/"quota-after-drain.json").write_bytes(final_raw)
                if len(callbacks) != 1 or len(calls) != 1 or proxy.errors:
                    raise AssertionError("Exactly one loopback call/callback required")
                pending, final = read(output/"quota-before-send.json"), json.loads(final_raw)
                hold_id = next(iter(pending["holds"]))
                before_hold, after_hold = pending["holds"][hold_id], final["holds"].get(hold_id)
                response = callbacks[0]
                budget = answer.get("budget") or {}
                checks = {
                    "actualFrozenJarUsed": sha(JAR.read_bytes()) == JAR_SHA,
                    "realJavaBudgetStill15000Ms": budget.get("timeoutMs") == 15000,
                    "realHttpAnswerRemainsTimeout": answer.get("status") == "REQUEST_TIMEOUT" and budget.get("termination") == "DEADLINE_EXCEEDED",
                    "deadlineWallWithin16000Ms": 15000 <= (answer_at-active["started"])*1000 <= 16000,
                    "oneModelCallOnly": budget.get("modelCalls") == 1 and len(calls) == len(callbacks) == 1,
                    "noLateSqlInAnswer": not answer.get("sql") and SQL.encode() not in answer_raw,
                    "sameAttemptCompleteSyntheticUsageCollected": response["responseComplete"] and response["responseJson"].get("usage") == SYNTHETIC_USAGE,
                    "usageArrivedAfterHttpReturn": calls[0]["finishedMonotonic"] > answer_at,
                    "originalHoldIdentityPreserved": after_hold is not None and {k:v for k,v in before_hold.items() if k != "status"} == {k:v for k,v in after_hold.items() if k != "status"},
                    "holdMatchesReturnedRequestIdentity": after_hold is not None and after_hold["requestId"] == budget.get("requestId") and after_hold["callNumber"] == budget.get("modelCalls"),
                    "candidateHoldStillUnknown": after_hold is not None and after_hold["status"] == "UNKNOWN",
                    "candidateNeverRefundedOrSettled": len(final["holds"]) == 1 and final["knownCalls"] == 0 and final["knownCostYuan"] == 0,
                    "syntheticBudgetIdentityPreserved": all(final[k] == pending[k] for k in ("version", "budgetId", "pricingIdentity", "limitYuan", "reservationYuan")),
                    "importedSourceBytesUnchanged": (PARENT/"loopback_accounting_probe.py").read_bytes() == PROBE_SOURCE_RAW,
                    "launcherSourceBytesUnchanged": Path(__file__).read_bytes() == launcher_source,
                }
                for rel, expected in manifest["files"].items():
                    if sha((ROOT/rel).read_bytes()) != expected:
                        raise AssertionError("Actual batch changed during probe")
                checks["all768ActualArtifactsUnchanged"] = True
                result = {"format": "real-frozen-java-loopback-late-accounting-probe-v1", "passed": all(checks.values()), "checks": checks,
                    "question": QUESTION, "httpStatus": status, "wallMs": (answer_at-active["started"])*1000,
                    "javaBudget": budget, "loopbackPosts": len(calls), "callbacks": len(callbacks),
                    "accountingGraceSeconds": 1, "syntheticUsage": SYNTHETIC_USAGE,
                    "candidateUnknownHoldId": hold_id, "candidateUnknownHold": after_hold,
                    "syntheticTransportCalls": calls, "supplierRequests": 0, "realCredentialsRead": 0,
                    "productionMonitorIntegrated": False, "productionLedgerUsed": False, "tlsOrPeakBoundaryTested": False,
                    "resourceExhaustionTested": False, "productionAdmission": False,
                    "sourceSha256": sha(launcher_source), "probeSourceSha256": sha(PROBE_SOURCE_RAW),
                    "frozenJarSha256": JAR_SHA, "totalWallMs": (time.perf_counter()-started)*1000}
                write(output/"result.json", result)
                if not result["passed"]:
                    raise AssertionError("One or more integration checks failed")
                print(json.dumps(result, ensure_ascii=True))
    except BaseException as exc:
        if not (output/"failure.json").exists():
            write(output/"failure.json", {"error": type(exc).__name__+": "+str(exc), "supplierRequests": 0, "realCredentialsRead": 0})
        raise
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        server.shutdown()
        server.server_close()
        server_thread.join(2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    main(parser.parse_args().output.resolve())
