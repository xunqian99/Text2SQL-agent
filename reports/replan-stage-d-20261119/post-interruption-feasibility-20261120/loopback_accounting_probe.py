"""Local-only feasibility probe, NOT an admitted evaluation transport.

Separates a send/answer cutoff from a bounded read-only accounting cutoff.
Cannot select the official endpoint or accept a real key. Leaves frozen sources,
JARs, quota ledgers and evaluation answers untouched.
"""
import argparse
import hashlib
import http.client
import json
import math
import sys
import threading
import time
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "scripts"))
from replan_stage_d_proxy import ModelProxy


class LoopbackAccountingProbe(ModelProxy):
    def __init__(self, before, after, *, upstream_url, grace_seconds):
        endpoint = urlsplit(upstream_url)
        if endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1" or endpoint.username or endpoint.password:
            raise ValueError("Probe accepts literal HTTP loopback only")
        if not isinstance(grace_seconds, (int, float)) or not math.isfinite(grace_seconds) or not 0 < grace_seconds <= 1:
            raise ValueError("Scaled local grace must be in (0, 1] seconds")
        self.grace = grace_seconds
        self.cutoffs = threading.local()
        super().__init__("synthetic-loopback-only", before, after,
                         upstream_url=upstream_url, allow_loopback_test=True)

    def _validate_permit(self, permit):
        send_cutoff = super()._validate_permit(permit)
        self.cutoffs.send = send_cutoff
        self.cutoffs.accounting = send_cutoff + self.grace
        return self.cutoffs.accounting

    def _check_send_open(self):
        super()._check_send_open()
        if time.monotonic() >= getattr(self.cutoffs, "send", float("-inf")):
            raise TimeoutError("Probe original send deadline expired")

    def _forward(self, handler, payload, raw_body):
        original_reply = handler._reply

        def no_late_sql(status, body, content_type="application/json", content_encoding=None):
            if time.monotonic() >= getattr(self.cutoffs, "send", float("inf")):
                status = 504
                body = b'{"error":{"message":"PROBE:answer deadline expired; late body is accounting only"}}'
                content_type, content_encoding = "application/json", None
            return original_reply(status, body, content_type, content_encoding)

        handler._reply = no_late_sql
        try:
            return super()._forward(handler, payload, raw_body)
        finally:
            handler._reply = original_reply


@contextmanager
def stub(delay, body):
    received = []
    disconnected_writes = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            received.append({"bodySha256": hashlib.sha256(raw).hexdigest(), "receivedMonotonic": time.monotonic()})
            time.sleep(delay)
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
            except ConnectionError as exc:
                disconnected_writes.append(type(exc).__name__)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = False
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .01})
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/v1/chat/completions", received, disconnected_writes
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)


PAYLOAD = json.dumps({"model": "synthetic-local", "messages": [{"role": "user", "content": "synthetic question"}]}).encode()
COMPLETE = json.dumps({"id": "local-probe", "choices": [{"message": {"content": "SELECT 1 AS synthetic_only"}}],
    "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
              "prompt_cache_hit_tokens": 0, "prompt_cache_miss_tokens": 10}}).encode()


def valid_usage(provider, response_complete):
    usage = provider.get("usage") if isinstance(provider, dict) else None
    fields = ("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens")
    if not response_complete or not isinstance(usage, dict) or not all(type(usage.get(k)) is int and usage[k] >= 0 for k in fields):
        return False
    p, c, t, h, m = (usage[k] for k in fields)
    return p > 0 and t > 0 and p+c == t and h+m == p


def case(name, *, delay, client_timeout, grace, extend, before_delay=0, body=COMPLETE, answer_seconds=.25):
    callbacks = []
    cutoff = time.monotonic() + answer_seconds
    token = uuid.uuid4().hex
    def before(payload, raw):
        if before_delay:
            time.sleep(before_delay)
        return {"token": token, "upstreamDeadlineMonotonic": cutoff}
    def after(permit, result):
        callbacks.append({"token": permit["token"], **result})
    with stub(delay, body) as (endpoint, received, disconnected_writes):
        proxy = (LoopbackAccountingProbe(before, after, upstream_url=endpoint, grace_seconds=grace) if extend else
                 ModelProxy("synthetic-loopback-only", before, after, upstream_url=endpoint, allow_loopback_test=True))
        with proxy:
            target = urlsplit(proxy.base_url)
            client = http.client.HTTPConnection(target.hostname, target.port, timeout=client_timeout)
            start = time.monotonic()
            reply_status, reply_body, client_error = None, b"", None
            try:
                client.request("POST", "/v1/chat/completions", PAYLOAD, {"Content-Type": "application/json"})
                response = client.getresponse()
                reply_status, reply_body = response.status, response.read()
            except (OSError, http.client.HTTPException) as exc:
                client_error = type(exc).__name__
            finally:
                elapsed = (time.monotonic()-start)*1000
                client.close()
            proxy.drain(timeout_seconds=3)
        if len(callbacks) != 1 or callbacks[0]["token"] != token or len(received) > 1 or proxy.errors:
            raise AssertionError("Callback/request identity or lifecycle mismatch: " + name)
        result = callbacks[0]
        provider = result["responseJson"]
        usage_complete = valid_usage(provider, result["responseComplete"])
        row = {"case": name, "extendedAccounting": extend, "stubRequests": len(received), "callbacks": 1,
            "callerStatus": reply_status, "callerError": client_error, "callerElapsedMs": round(elapsed, 3),
            "callerReceivedSyntheticSql": b"SELECT 1 AS synthetic_only" in reply_body,
            "completeUsageCollected": bool(usage_complete), "responseComplete": result["responseComplete"],
            "responseBytes": len(result["responseBodyRaw"]), "transportError": result["error"],
            "upstreamAttempted": result["upstreamAttempted"], "syntheticOnly": True}
    # The stub may attempt its write after the client/proxy has closed. Exiting
    # the context joins it before recording this expected disconnect evidence.
    row["stubDisconnectedWrites"] = list(disconnected_writes)
    return row


def run(output):
    if output.parent != HERE or output.exists():
        raise ValueError("Output must be a new immediate child")
    started = time.perf_counter()
    rows = [
        case("frozen-close-at-answer-cutoff", delay=.55, client_timeout=.25, grace=1, extend=False),
        case("late-usage-after-caller-disconnect", delay=.55, client_timeout=.25, grace=1, extend=True),
        case("late-sql-suppressed-for-waiting-client", delay=.55, client_timeout=2, grace=1, extend=True),
        case("timely-answer-unchanged", delay=.03, client_timeout=2, grace=1, extend=True),
        case("accounting-deadline-still-bounded", delay=.8, client_timeout=.25, grace=.2, extend=True),
        case("expired-send-permit-never-contacts-stub", delay=0, client_timeout=2, grace=1, extend=True, before_delay=.35),
        case("missing-usage-stays-unknown", delay=.55, client_timeout=.25, grace=1, extend=True,
             body=b'{"id":"no-usage","choices":[]}'),
        case("contradictory-usage-stays-unknown", delay=.55, client_timeout=.25, grace=1, extend=True,
             body=b'{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":99,"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":10}}'),
    ]
    checks = {
        "oldTransportDropsLateUsage": not rows[0]["completeUsageCollected"],
        "boundedLateUsageCapturedAfterDisconnect": rows[1]["completeUsageCollected"] and rows[1]["callerError"] is not None,
        "lateSqlNeverDeliveredToWaitingClient": rows[2]["callerStatus"] == 504 and not rows[2]["callerReceivedSyntheticSql"],
        "timelySqlAndUsageDelivered": rows[3]["callerStatus"] == 200 and rows[3]["callerReceivedSyntheticSql"] and rows[3]["completeUsageCollected"],
        "accountingCutoffKeepsUsageUnknown": not rows[4]["completeUsageCollected"] and not rows[4]["responseComplete"],
        "noPostDeadlineSend": rows[5]["stubRequests"] == 0 and not rows[5]["upstreamAttempted"],
        "noInventedUsage": rows[6]["responseComplete"] and not rows[6]["completeUsageCollected"],
        "contradictoryUsageNotAccepted": rows[7]["responseComplete"] and not rows[7]["completeUsageCollected"],
        "everyCaseHasAtMostOneRequestAndOneCallback": all(r["stubRequests"] <= 1 and r["callbacks"] == 1 for r in rows),
    }
    if not all(checks.values()):
        raise AssertionError(json.dumps({"checks": checks, "cases": rows}))
    report = {"format": "loopback-accounting-feasibility-only-v1", "passed": True, "checks": checks, "cases": rows,
        "scaledAnswerSeconds": .25, "maximumScaledAccountingGraceSeconds": 1,
        "realJavaHttpIntegrationTested": False, "realFifteenSecondDeadlineTested": False,
        "productionMonitorAndQuotaIntegrationTested": False, "officialTlsAndPeakBoundaryTested": False,
        "productionAdmission": False, "realProviderCalls": 0, "realKeysRead": 0,
        "candidateUnknownHoldsReleased": 0, "frozenArtifactsChanged": 0,
        "wallMs": round((time.perf_counter()-started)*1000, 3),
        "sourceSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "frozenTransportSha256": hashlib.sha256((ROOT/"scripts/replan_stage_d_proxy.py").read_bytes()).hexdigest()}
    output.mkdir()
    with (output/"result.json").open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    run(parser.parse_args().output.resolve())
