"""Real Windows loopback-only checks. Never resolve a provider key or call an external endpoint."""
from __future__ import annotations

import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import unittest
import uuid

import replan_stable_proxy as stable

ROOT = Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / "reports/replan-stage-d-20261119/reregistration-candidate/stable-proxy-checks-1"


def local_request():
    raw = json.dumps(dict(model="deepseek-flash", messages=[dict(role="user", content="synthetic localhost only")])).encode()
    connection = http.client.HTTPConnection(stable.HOST, stable.PORT, timeout=5)
    try:
        connection.request("POST", "/v1/chat/completions", raw, {"Content-Type": "application/json"})
        response = connection.getresponse()
        body = response.read()
        return response.status, json.loads(body)
    finally:
        connection.close()


def callbacks():
    records = {"before": [], "after": []}
    def before(payload, raw):
        permit = dict(token=uuid.uuid4().hex, upstreamDeadlineMonotonic=time.monotonic() + 5)
        records["before"].append(permit)
        return permit
    def after(permit, result):
        records["after"].append(dict(token=permit["token"], upstreamAttempted=result["upstreamAttempted"],
                                     responseComplete=result["responseComplete"]))
    return records, before, after


def proxy(endpoint):
    records, before, after = callbacks()
    instance = stable.StableModelProxy("SYNTHETIC-LOCAL-TEST-NOT-A-PROVIDER-KEY", before, after,
                                      upstream_url=endpoint, allow_loopback_test=True)
    return instance, records


def child(endpoint, expect_occupied=False):
    instance, records = proxy(endpoint)
    threads_before = len(threading.enumerate())
    try:
        with instance as running:
            status, body = local_request()
            running.drain(5)
            result = dict(bound=True, baseUrl=running.base_url, httpStatus=status,
                          beforeCount=len(records["before"]), afterCount=len(records["after"]),
                          errors=running.errors, synthetic=True)
    except OSError as error:
        if not expect_occupied:
            raise
        result = dict(bound=False, exclusiveBindRefused=True, winerror=getattr(error, "winerror", None),
                      beforeCount=len(records["before"]), afterCount=len(records["after"]), synthetic=True)
    result["extraThreadsAfterClose"] = len(threading.enumerate()) - threads_before
    result["addedProviderCalls"] = 0
    print(json.dumps(result))
    return 0


class RealWindowsChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.stub_requests = []
        class Stub(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                cls.stub_requests.append(dict(path=self.path, synthetic=True))
                answer = dict(id="local-fixed-listener-check", object="chat.completion", created=1,
                    model="deepseek-flash", choices=[dict(index=0, message=dict(role="assistant", content="SELECT 1"), finish_reason="stop")],
                    usage=dict(prompt_tokens=10, completion_tokens=10, total_tokens=20,
                               prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=10))
                raw = json.dumps(answer).encode()
                self.send_response(200); self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
        cls.stub = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
        cls.thread = threading.Thread(target=cls.stub.serve_forever, daemon=True); cls.thread.start()
        cls.endpoint = f"http://127.0.0.1:{cls.stub.server_port}/v1/chat/completions"

    @classmethod
    def tearDownClass(cls):
        cls.stub.shutdown(); cls.stub.server_close(); cls.thread.join(timeout=5)

    def setUp(self):
        self.events = []

    def tearDown(self):
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        with (EVIDENCE / (self._testMethodName + ".json")).open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(dict(events=self.events, addedProviderCalls=0, realCredentialReads=0), stream, indent=2)
            stream.write("\n")

    def child_run(self, occupied=False):
        # Deliberately discard AGENT_*/provider-key/proxy environment variables.
        allowed = {"PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA"}
        env = {k: v for k, v in os.environ.items() if k.upper() in allowed}
        args = [sys.executable, str(Path(__file__).resolve()), "--child", self.endpoint]
        if occupied: args.append("--expect-occupied")
        completed = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", timeout=20)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        result = json.loads(completed.stdout)
        self.events.append(result)
        return result

    def test_b_then_f_same_process_close_and_immediate_reopen(self):
        for side in ("B", "F"):
            instance, records = proxy(self.endpoint)
            with instance as running:
                self.assertEqual(running.base_url, stable.BASE_URL)
                status, body = local_request(); self.assertEqual(status, 200)
                running.drain(5); self.assertFalse(running.errors)
                self.assertEqual(len(records["before"]), 1); self.assertEqual(len(records["after"]), 1)
            self.assertFalse(instance._server_thread.is_alive())
            self.events.append(dict(side=side, beforeCount=len(records["before"]), afterCount=len(records["after"]),
                                    transportAttempted=records["after"][0]["upstreamAttempted"], closed=True))
        netstat = subprocess.run(["netstat", "-ano", "-p", "tcp"], capture_output=True, text=True, timeout=10)
        observed = [line.strip() for line in netstat.stdout.splitlines() if "127.0.0.1:59900" in line]
        self.events.append(dict(postCloseSocketRows=observed,
            scope="Observed Windows state only; rapid reopen passed after server Connection:close. No universal TIME_WAIT duration assumption."))

    def test_cross_process_close_and_immediate_reopen(self):
        first = self.child_run(); second = self.child_run()
        for result in (first, second):
            self.assertTrue(result["bound"]); self.assertEqual(result["baseUrl"], stable.BASE_URL)
            self.assertEqual(result["httpStatus"], 200); self.assertEqual(result["beforeCount"], 1)
            self.assertEqual(result["afterCount"], 1); self.assertEqual(result["extraThreadsAfterClose"], 0)

    def test_exclusive_active_owner_rejects_second_proxy_without_callbacks(self):
        owner, records = proxy(self.endpoint)
        with owner:
            intruder, rejected = proxy(self.endpoint)
            with self.assertRaises(OSError):
                with intruder: pass
            self.assertEqual(rejected, dict(before=[], after=[]))
            self.assertIsNone(intruder._server_thread)
            status, body = local_request(); self.assertEqual(status, 200)
            owner.drain(5)
            self.events.append(dict(secondProxyRefused=True, rejectedBefore=0, rejectedAfter=0, originalOwnerStillServes=True))

    def test_external_exclusive_owner_rejects_child_no_random_port_fallback(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as owner:
            owner.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
            owner.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            owner.bind((stable.HOST, stable.PORT)); owner.listen(1)
            result = self.child_run(occupied=True)
            self.assertFalse(result["bound"]); self.assertTrue(result["exclusiveBindRefused"])
            self.assertEqual(result["beforeCount"], 0); self.assertEqual(result["afterCount"], 0)
            self.assertEqual(result["extraThreadsAfterClose"], 0)

    def test_real_listener_has_exclusive_one_reuse_zero(self):
        instance, records = proxy(self.endpoint)
        with instance:
            sock = instance._server.socket
            exclusive = sock.getsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE)
            reuse = sock.getsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR)
            self.assertEqual(exclusive, 1); self.assertEqual(reuse, 0)
            self.events.append(dict(exclusive=exclusive, reuseAddress=reuse, address=instance._server.server_address))

    def test_frozen_handler_forward_deadline_and_shutdown_are_inherited(self):
        base = stable._OriginalModelProxy
        for name in ("_forward", "_validate_permit", "_validate_endpoint", "_check_send_open", "drain", "__exit__"):
            self.assertIs(getattr(stable.StableModelProxy, name), getattr(base, name))
        for name in ("process_request", "_run_handler", "handle_error"):
            self.assertIs(getattr(stable.StableServer, name), getattr(stable.frozen._Server, name))
        self.assertEqual(hashlib.sha256(Path(stable.frozen.__file__).read_bytes()).hexdigest(), stable.FROZEN_PROXY_SHA)
        self.events.append(dict(frozenProxySha256=stable.FROZEN_PROXY_SHA, listenerOnly=True))

    def test_instances_remain_single_use(self):
        instance, records = proxy(self.endpoint)
        with instance: pass
        with self.assertRaises(RuntimeError):
            with instance: pass
        self.events.append(dict(reentryRejected=True, before=0, after=0))


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--child":
        sys.exit(child(sys.argv[2], "--expect-occupied" in sys.argv[3:]))
    EVIDENCE.mkdir(parents=True, exist_ok=False)
    result = unittest.TestResult()
    unittest.defaultTestLoader.loadTestsFromTestCase(RealWindowsChecks).run(result)
    report = dict(format="stable-exclusive-windows-listener-free-checks-v1", testsRun=result.testsRun,
        passed=result.testsRun-len(result.failures)-len(result.errors), failures=len(result.failures), errors=len(result.errors),
        failureDetails=[dict(test=str(t), trace=s) for t, s in result.failures+result.errors],
        syntheticLoopbackUpstreamRequests=len(RealWindowsChecks.stub_requests), addedExternalProviderCalls=0,
        realCredentialReads=0, unchangedFrozenProxySha256=stable.FROZEN_PROXY_SHA,
        helperSourceSha256=hashlib.sha256(Path(stable.__file__).read_bytes()).hexdigest(),
        exclusiveAddressUse=1, reuseAddress=0, randomPortFallback=False,
        scope="Actual Windows loopback listener/handler/transport and child-process reopen; no real JAR/model calls")
    with (EVIDENCE / "verification.json").open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, indent=2); stream.write("\n")
    print(json.dumps({k: report[k] for k in ("testsRun", "passed", "failures", "errors", "syntheticLoopbackUpstreamRequests")}))
    if not result.wasSuccessful(): sys.exit(1)
