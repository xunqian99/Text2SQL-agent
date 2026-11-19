"""Local-only Stage D transport tests; no vendor keys or remote endpoints.

Run once with --evidence PATH. Files are exclusive-created, so first results
cannot be overwritten. To verify a repair, supply individual test method names
and a new evidence path; the original failed run remains intact.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.parse import urlsplit

from replan_stage_d_proxy import MAX_BODY_BYTES, MAX_CHUNKS, MAX_CHUNK_LINE_BYTES, ModelProxy, OFFICIAL_URL


KEY = "unit-test-key-not-a-provider-credential"
BODY = b'{ "id":"local-response", "choices":[], "usage": {"prompt_tokens":17,"completion_tokens":5,"total_tokens":22,"prompt_cache_hit_tokens":3} }\n'
REQUEST = {"model": "local-test-model", "messages": [{"role": "user", "content": "local synthetic question"}], "stream": False}
COUNTERS = {"localUpstreamRequests": 0, "gateCalls": 0, "afterCalls": 0}


class _StubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        pass

    def do_POST(self):
        stub = self.server.owner
        raw_body = self.rfile.read(int(self.headers["Content-Length"]))
        with stub.lock:
            stub.requests.append(raw_body)
            stub.auth_matches.append(self.headers.get("Authorization") == "Bearer " + KEY)
            COUNTERS["localUpstreamRequests"] += 1
        stub.received.set()
        try:
            if stub.mode == "wait":
                stub.release.wait(2)
            if stub.mode == "drop":
                self.connection.shutdown(socket.SHUT_RDWR)
                self.close_connection = True
                return
            if stub.mode == "header_trickle":
                self.connection.sendall(b"HTTP/1.1 200 OK\r\nX-Slow: ")
                for _ in range(10):
                    stub.release.wait(.04)
                    self.connection.sendall(b"x")
                self.connection.sendall(b"\r\nContent-Length: 2\r\n\r\n{}")
                self.close_connection = True
                return
            self.send_response(stub.status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(stub.body) + (5 if stub.mode == "incomplete" else 0)))
            self.send_header("Connection", "close")
            self.end_headers()
            if stub.mode == "body_trickle":
                for offset in range(0, len(stub.body), 5):
                    self.wfile.write(stub.body[offset:offset + 5])
                    self.wfile.flush()
                    stub.release.wait(.04)
            else:
                self.wfile.write(stub.body)
            self.close_connection = True
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass


class LocalUpstream:
    def __init__(self, mode="success", status=200, body=BODY):
        self.mode, self.status, self.body = mode, status, body
        self.requests, self.auth_matches = [], []
        self.lock = threading.Lock()
        self.received, self.release = threading.Event(), threading.Event()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _StubHandler)
        self.server.owner = self
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={"poll_interval": .01}, daemon=True)
        self.url = f"http://127.0.0.1:{self.server.server_port}/v1/chat/completions"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)


class Recorder:
    def __init__(self, budget=2):
        self.budget = budget
        self.before = []
        self.after = []

    def before_send(self, payload, raw_body):
        COUNTERS["gateCalls"] += 1
        self.before.append((payload, raw_body))
        return {"token": f"local-permit-{len(self.before)}",
                "upstreamDeadlineMonotonic": time.monotonic() + self.budget}

    def after_send(self, permit, result):
        COUNTERS["afterCalls"] += 1
        self.after.append((permit, result))


def request(base_url, raw=None, path="/v1/chat/completions", method="POST", headers=None):
    parsed = urlsplit(base_url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=3)
    try:
        connection.request(method, path, body=json.dumps(REQUEST).encode() if raw is None else raw,
                           headers=headers or {"Content-Type": "application/json"})
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def wire_request(base_url, headers, framed_body=b"", version=b"HTTP/1.1"):
    """Preserve duplicate headers and deliberately malformed framing for tests."""
    parsed = urlsplit(base_url)
    client = socket.create_connection((parsed.hostname, parsed.port), timeout=3)
    try:
        wire = (b"POST /v1/chat/completions " + version + b"\r\nHost: 127.0.0.1\r\n"
                + b"\r\n".join(headers) + b"\r\n\r\n" + framed_body)
        try:
            client.sendall(wire)
            client.shutdown(socket.SHUT_WR)
        except (BrokenPipeError, ConnectionResetError):
            pass
        response = http.client.HTTPResponse(client)
        response.begin()
        return response.status, response.read()
    finally:
        client.close()


def chunk_frame(raw, size=4088):
    # JDK-style ~4KiB wire chunks: 0xff8 payload + 3 hex + 2 CRLF pairs.
    return b"".join(f"{len(raw[i:i+size]):x}\r\n".encode() + raw[i:i+size] + b"\r\n"
                    for i in range(0, len(raw), size)) + b"0\r\n\r\n"


def proxy_for(stub, recorder=None, **overrides):
    recorder = recorder or Recorder()
    return ModelProxy(KEY, overrides.get("before_send", recorder.before_send),
                      overrides.get("after_send", recorder.after_send),
                      upstream_url=stub.url, allow_loopback_test=True)


class ProxyTests(unittest.TestCase):
    def assert_record(self, recorder, attempted, status, complete):
        self.assertEqual(1, len(recorder.after))
        permit, result = recorder.after[0]
        self.assertEqual("local-permit-1", permit["token"])
        self.assertEqual(attempted, result["upstreamAttempted"])
        self.assertEqual(status, result["httpStatus"])
        self.assertEqual(complete, result["responseComplete"])
        self.assertIsNotNone(result["startedAtUtc"])
        self.assertIsNotNone(result["finishedAtUtc"])
        self.assertNotIn(KEY, json.dumps(result))
        self.assertNotIn("Authorization", json.dumps(result))
        return result

    def test_peak_gate_rejection_has_zero_upstream_attempts(self):
        recorder = Recorder()
        def reject(payload, raw_body):
            raise RuntimeError("peak window: less than 120 seconds")
        with LocalUpstream() as stub, proxy_for(stub, recorder, before_send=reject) as proxy:
            status, body = request(proxy.base_url)
            proxy.drain(1)
            self.assertEqual(503, status)
            self.assertIn("D_LOCAL_GATE:", json.loads(body)["error"]["message"])
            self.assertEqual([], stub.requests)
            self.assertEqual([], recorder.after)
            self.assertEqual([], proxy.errors)

    def test_success_preserves_original_body_usage_and_single_attempt(self):
        recorder = Recorder()
        raw = json.dumps(REQUEST, separators=(",", ":")).encode()
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            status, body = request(proxy.base_url, raw=raw)
            proxy.drain(1)
            self.assertEqual((200, BODY), (status, body))
            self.assertEqual([raw], stub.requests)
            self.assertEqual([True], stub.auth_matches)
            self.assertEqual([(REQUEST, raw)], recorder.before)
            result = self.assert_record(recorder, True, 200, True)
            self.assertEqual(json.loads(BODY), result["responseJson"])
            self.assertEqual(22, result["responseJson"]["usage"]["total_tokens"])
            self.assertEqual(hashlib.sha256(BODY).hexdigest(), result["responseBodySha256"])
            self.assertEqual(len(BODY), result["responseBytes"])
            self.assertIsNone(result["error"])
            self.assertEqual([], proxy.errors)

    def test_client_abandonment_keeps_known_usage(self):
        recorder = Recorder()
        with LocalUpstream(mode="wait") as stub, proxy_for(stub, recorder) as proxy:
            parsed = urlsplit(proxy.base_url)
            client = socket.create_connection((parsed.hostname, parsed.port), timeout=2)
            raw = json.dumps(REQUEST).encode()
            client.sendall(b"POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: "
                           + str(len(raw)).encode() + b"\r\n\r\n" + raw)
            self.assertTrue(stub.received.wait(1))
            client.close()
            stub.release.set()
            proxy.drain(2)
            result = self.assert_record(recorder, True, 200, True)
            self.assertEqual(22, result["responseJson"]["usage"]["total_tokens"])
            self.assertEqual(1, len(stub.requests))
            self.assertEqual([], proxy.errors)

    def test_timeout_preserves_unknown_usage_and_attempt_evidence(self):
        recorder = Recorder(budget=.12)
        with LocalUpstream(mode="wait") as stub, proxy_for(stub, recorder) as proxy:
            status, _ = request(proxy.base_url)
            proxy.drain(1)
            self.assertEqual(504, status)
            result = self.assert_record(recorder, True, None, False)
            self.assertIsNone(result["responseJson"])
            self.assertEqual(0, result["responseBytes"])
            self.assertIn("TimeoutError", result["error"])
            self.assertNotIn("usage", result)
            self.assertEqual(1, len(stub.requests))

    def test_network_failure_does_not_invent_zero_usage_or_retry(self):
        recorder = Recorder()
        with LocalUpstream(mode="drop") as stub, proxy_for(stub, recorder) as proxy:
            status, _ = request(proxy.base_url)
            proxy.drain(1)
            self.assertEqual(502, status)
            result = self.assert_record(recorder, True, None, False)
            self.assertIsNone(result["responseJson"])
            self.assertIn("RemoteDisconnected", result["error"])
            self.assertEqual(1, len(stub.requests))

    def test_upstream_4xx_body_is_preserved_without_retry(self):
        recorder = Recorder()
        upstream_body = b'{"error":{"message":"local rate limit"}}'
        with LocalUpstream(status=429, body=upstream_body) as stub, proxy_for(stub, recorder) as proxy:
            status, body = request(proxy.base_url)
            proxy.drain(1)
            self.assertEqual((429, upstream_body), (status, body))
            result = self.assert_record(recorder, True, 429, True)
            self.assertNotIn("usage", result["responseJson"])
            self.assertEqual(1, len(stub.requests))

    def test_bad_local_requests_never_reach_gate_or_upstream(self):
        recorder = Recorder()
        cases = [
            dict(path="/wrong", status=404),
            dict(path="/v1/chat/completions?x=1", status=404),
            dict(method="GET", status=405),
            dict(raw=b'{"stream":true}', status=400),
            dict(raw=b'{"stream":null}', status=400),
            dict(raw=b"[]", status=400),
            dict(raw=b"{broken", status=400),
            dict(raw=b"", headers={"Content-Length": str(MAX_BODY_BYTES + 1)}, status=413),
            dict(raw=b"", headers={"Content-Length": "-1"}, status=400),
            dict(raw=b"", headers={"Transfer-Encoding": "chunked"}, status=400),
        ]
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            for case in cases:
                with self.subTest(case={k:v for k,v in case.items() if k != "raw"}):
                    status = case.pop("status")
                    self.assertEqual(status, request(proxy.base_url, **case)[0])
            proxy.drain(1)
            self.assertEqual([], recorder.before)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)

    def test_endpoint_validation_allows_only_exact_official_or_opted_in_loopback(self):
        recorder = Recorder()
        ModelProxy(KEY, recorder.before_send, recorder.after_send)
        invalid = [
            "http://api.deepseek.com/v1/chat/completions",
            "https://api.deepseek.com:443/v1/chat/completions",
            "https://api.deepseek.com/v1/chat/completions?x=1",
            "https://api.deepseek.com/v1/chat/completions#x",
            "https://user@api.deepseek.com/v1/chat/completions",
            "https://other.api.deepseek.com/v1/chat/completions",
            "http://127.0.0.1:12345/v1/chat/completions",
        ]
        for url in invalid:
            with self.subTest(url=url), self.assertRaises(ValueError):
                ModelProxy(KEY, recorder.before_send, recorder.after_send, url)
        for url in ["http://192.0.2.1:12345/v1/chat/completions",
                    "http://127.0.0.1:12345/wrong", "http://127.0.0.1:12345/v1/chat/completions?x=1"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                ModelProxy(KEY, recorder.before_send, recorder.after_send, url, allow_loopback_test=True)

    def test_expired_or_invalid_permits_are_reconciled_without_sending(self):
        invalid = [
            {"token": "local-permit-1", "upstreamDeadlineMonotonic": time.monotonic() - 1},
            {"token": "local-permit-1", "upstreamDeadlineMonotonic": float("nan")},
            {"token": "local-permit-1", "upstreamDeadlineMonotonic": True},
            {"token": "local-permit-1"},
        ]
        for permit in invalid:
            with self.subTest(permitFields=sorted(permit)):
                recorder = Recorder()
                with LocalUpstream() as stub, proxy_for(stub, recorder, before_send=lambda p,b:permit) as proxy:
                    status, body = request(proxy.base_url)
                    proxy.drain(1)
                    self.assertEqual(503, status)
                    self.assertIn("D_LOCAL_GATE:", json.loads(body)["error"]["message"])
                    result = self.assert_record(recorder, False, None, False)
                    self.assertIsNone(result["responseJson"])
                    self.assertEqual([], stub.requests)

    def test_duplicate_permit_token_cannot_send_twice(self):
        recorder = Recorder()
        permit = {"token": "same-token", "upstreamDeadlineMonotonic": time.monotonic() + 2}
        with LocalUpstream() as stub, proxy_for(stub, recorder, before_send=lambda p,b:permit) as proxy:
            self.assertEqual(200, request(proxy.base_url)[0])
            self.assertEqual(503, request(proxy.base_url)[0])
            proxy.drain(1)
            self.assertEqual(1, len(stub.requests))
            self.assertEqual(2, len(recorder.after))
            self.assertTrue(recorder.after[0][1]["upstreamAttempted"])
            self.assertFalse(recorder.after[1][1]["upstreamAttempted"])

    def test_expiry_during_connection_setup_is_zero_attempt_local_gate(self):
        recorder = Recorder()
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            with patch("replan_stage_d_proxy._HTTPConnection", side_effect=TimeoutError("absolute upstream deadline expired")):
                status, body = request(proxy.base_url)
            proxy.drain(1)
            self.assertEqual(503, status)
            self.assertIn("D_LOCAL_GATE:", json.loads(body)["error"]["message"])
            self.assert_record(recorder, False, None, False)
            self.assertEqual([], stub.requests)

    def test_after_callback_failure_is_exposed_and_key_redacted(self):
        def broken_after(permit, result):
            raise RuntimeError("synthetic callback failure " + KEY)
        with LocalUpstream() as stub, proxy_for(stub, after_send=broken_after) as proxy:
            status, body = request(proxy.base_url)
            proxy.drain(1)
            self.assertEqual((200, BODY), (status, body))
            self.assertEqual(1, len(proxy.errors))
            self.assertIn("after_send", proxy.errors[0])
            self.assertIn("[REDACTED]", proxy.errors[0])
            self.assertNotIn(KEY, json.dumps(proxy.errors))

    def test_gate_error_redacts_key_and_does_not_save_headers(self):
        def broken_before(payload, raw_body):
            raise ValueError("synthetic gate failure " + KEY)
        recorder = Recorder()
        with LocalUpstream() as stub, proxy_for(stub, recorder, before_send=broken_before) as proxy:
            status, body = request(proxy.base_url)
            proxy.drain(1)
            self.assertEqual(503, status)
            self.assertNotIn(KEY.encode(), body)
            self.assertIn(b"[REDACTED]", body)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)

    def test_header_trickle_cannot_reset_absolute_deadline(self):
        recorder = Recorder(budget=.13)
        with LocalUpstream(mode="header_trickle") as stub, proxy_for(stub, recorder) as proxy:
            started = time.monotonic()
            status, _ = request(proxy.base_url)
            elapsed = time.monotonic() - started
            proxy.drain(1)
            self.assertEqual(504, status)
            self.assertLess(elapsed, .45)
            result = self.assert_record(recorder, True, None, False)
            self.assertIsNone(result["responseJson"])
            self.assertEqual(1, len(stub.requests))

    def test_body_trickle_keeps_partial_bytes_and_absolute_deadline(self):
        recorder = Recorder(budget=.13)
        with LocalUpstream(mode="body_trickle") as stub, proxy_for(stub, recorder) as proxy:
            started = time.monotonic()
            status, _ = request(proxy.base_url)
            elapsed = time.monotonic() - started
            proxy.drain(1)
            self.assertEqual(504, status)
            self.assertLess(elapsed, .45)
            result = self.assert_record(recorder, True, 200, False)
            self.assertIsNone(result["responseJson"])
            self.assertGreater(result["responseBytes"], 0)
            self.assertLess(result["responseBytes"], len(BODY))
            self.assertEqual(hashlib.sha256(BODY[:result["responseBytes"]]).hexdigest(), result["responseBodySha256"])

    def test_incomplete_body_keeps_parseable_usage_but_marks_failure(self):
        recorder = Recorder()
        with LocalUpstream(mode="incomplete") as stub, proxy_for(stub, recorder) as proxy:
            status, _ = request(proxy.base_url)
            proxy.drain(1)
            self.assertEqual(502, status)
            result = self.assert_record(recorder, True, 200, False)
            self.assertEqual(22, result["responseJson"]["usage"]["total_tokens"])
            self.assertIn("IncompleteRead", result["error"])
            self.assertEqual(len(BODY), result["responseBytes"])

    def test_environment_proxies_are_ignored(self):
        recorder = Recorder()
        invalid_proxy = "http://127.0.0.1:1"
        with patch.dict(os.environ, {"HTTP_PROXY": invalid_proxy, "HTTPS_PROXY": invalid_proxy,
                                    "ALL_PROXY": invalid_proxy, "NO_PROXY": ""}):
            with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
                self.assertEqual((200, BODY), request(proxy.base_url))
                proxy.drain(1)
                self.assertEqual(1, len(stub.requests))

    def test_drain_timeout_and_shutdown_cancel_unsent_callback(self):
        recorder = Recorder()
        entered, release = threading.Event(), threading.Event()
        request_results, close_errors = [], []
        def blocked_before(payload, raw_body):
            entered.set()
            if not release.wait(2):
                raise RuntimeError("local test did not release callback")
            return recorder.before_send(payload, raw_body)
        with LocalUpstream() as stub:
            proxy = proxy_for(stub, recorder, before_send=blocked_before)
            proxy.__enter__()
            worker = threading.Thread(target=lambda:request_results.append(request(proxy.base_url)))
            worker.start()
            try:
                self.assertTrue(entered.wait(1))
                with self.assertRaises(TimeoutError):
                    proxy.drain(.02)
                def close_proxy():
                    try:
                        proxy.__exit__(None, None, None)
                    except Exception as exc:
                        close_errors.append(type(exc).__name__)
                closer = threading.Thread(target=close_proxy)
                closer.start()
                self.assertTrue(proxy._closing.wait(1))
                release.set()
                closer.join(3)
                worker.join(3)
                self.assertFalse(closer.is_alive())
                self.assertFalse(worker.is_alive())
                self.assertEqual([], close_errors)
                self.assertEqual(503, request_results[0][0])
                self.assertEqual([], stub.requests)
                self.assert_record(recorder, False, None, False)
                self.assertFalse(proxy._server_thread.is_alive())
                self.assertEqual(set(), proxy._handlers)
            finally:
                release.set()
                worker.join(3)
                if not proxy._closing.is_set():
                    proxy.__exit__(None, None, None)

    def test_context_exit_joins_handlers_and_closes_listener(self):
        recorder = Recorder()
        with LocalUpstream() as stub:
            with proxy_for(stub, recorder) as proxy:
                self.assertEqual(200, request(proxy.base_url)[0])
                proxy.drain(1)
                self.assertEqual(set(), proxy._handlers)
            self.assertFalse(proxy._server_thread.is_alive())
            self.assertEqual(set(), proxy._handlers)
            with self.assertRaises((ConnectionRefusedError, OSError)):
                request(proxy.base_url)
            self.assertEqual(1, len(stub.requests))
            with self.assertRaises(RuntimeError):
                proxy.__enter__()

    def test_shutdown_during_dns_cannot_resume_into_upstream_send(self):
        recorder = Recorder()
        entered, release = threading.Event(), threading.Event()
        request_results, close_errors = [], []
        original_resolve = socket.getaddrinfo
        with LocalUpstream() as stub:
            upstream_port = urlsplit(stub.url).port
            def blocked_resolution(host, port, *args, **kwargs):
                if port == upstream_port:
                    entered.set()
                    if not release.wait(2):
                        raise RuntimeError("local test did not release DNS")
                return original_resolve(host, port, *args, **kwargs)
            proxy = proxy_for(stub, recorder)
            proxy.__enter__()
            with patch("replan_stage_d_proxy.socket.getaddrinfo", side_effect=blocked_resolution):
                worker = threading.Thread(target=lambda:request_results.append(request(proxy.base_url)))
                worker.start()
                try:
                    self.assertTrue(entered.wait(1))
                    def close_proxy():
                        try:
                            proxy.__exit__(None, None, None)
                        except Exception as exc:
                            close_errors.append(type(exc).__name__)
                    closer = threading.Thread(target=close_proxy)
                    closer.start()
                    self.assertTrue(proxy._closing.wait(1))
                    release.set()
                    closer.join(3)
                    worker.join(3)
                    self.assertFalse(closer.is_alive())
                    self.assertFalse(worker.is_alive())
                    self.assertEqual([], close_errors)
                    self.assertEqual(503, request_results[0][0])
                    self.assertEqual([], stub.requests)
                    # request() was entered, but no provider HTTP receipt is
                    # claimed: attempted=True includes DNS/connect failures.
                    self.assert_record(recorder, True, None, False)
                    self.assertTrue(all(not t.is_alive() for t in proxy._all_threads))
                finally:
                    release.set()
                    worker.join(3)
                    if not proxy._closing.is_set():
                        proxy.__exit__(None, None, None)

    def test_chunked_jdk_style_preserves_decoded_json_bytes_and_usage(self):
        recorder = Recorder()
        payload = dict(REQUEST, messages=[{"role": "user", "content": "订单数量和金额 " * 3500}])
        raw = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
        self.assertGreater(len(raw), 4088)
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            status, body = wire_request(proxy.base_url, [
                b"Transfer-Encoding: chunked", b"Content-Type: application/json; charset=UTF-8",
                b"User-Agent: Java/17", b"Connection: keep-alive"], chunk_frame(raw))
            proxy.drain(1)
            self.assertEqual((200, BODY), (status, body))
            self.assertEqual([(payload, raw)], recorder.before)
            self.assertEqual([raw], stub.requests)
            result = self.assert_record(recorder, True, 200, True)
            self.assertEqual(22, result["responseJson"]["usage"]["total_tokens"])
            self.assertEqual([], proxy.errors)

    def test_chunked_extensions_declared_trailer_and_expect_continue(self):
        recorder = Recorder()
        raw = json.dumps(REQUEST, indent=2).encode()
        framed = (f"{len(raw):X}; name=token; quoted=\"escaped\\\" value\"\r\n".encode()
                  + raw + b"\r\n0; end=true\r\nX-Trace-Id: local-trace\r\n\r\n")
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            status, body = wire_request(proxy.base_url, [b"Transfer-Encoding: ChUnKeD",
                b"Trailer: X-Trace-Id", b"Expect: 100-continue"], framed)
            proxy.drain(1)
            self.assertEqual((200, BODY), (status, body))
            self.assertEqual([(REQUEST, raw)], recorder.before)
            self.assertEqual([raw], stub.requests)

    def test_chunked_framing_ambiguities_are_rejected_before_gate(self):
        recorder = Recorder()
        cases = [
            [b"Transfer-Encoding: chunked", b"Content-Length: 0"],
            [b"Transfer-Encoding: chunked", b"Transfer-Encoding: chunked"],
            [b"Transfer-Encoding: chunked, chunked"],
            [b"Transfer-Encoding: gzip, chunked"],
            [b"Transfer-Encoding: gzip"],
            [b"Transfer-Encoding:"],
            [b"Transfer-Encoding: chunked; x=1"],
            [b"Transfer-Encoding: chunked", b"Content-Length: 100", b"Expect: 100-continue"],
        ]
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            for headers in cases:
                with self.subTest(headers=headers):
                    self.assertEqual(400, wire_request(proxy.base_url, headers, b"0\r\n\r\n")[0])
            self.assertEqual(400, wire_request(proxy.base_url, [b"Transfer-Encoding: chunked"],
                                               b"0\r\n\r\n", version=b"HTTP/1.0")[0])
            proxy.drain(1)
            self.assertEqual([], recorder.before)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)

    def test_chunked_invalid_sizes_crlf_and_extensions_never_send(self):
        recorder = Recorder()
        bodies = [
            b"g\r\n{}\r\n0\r\n\r\n", b"+2\r\n{}\r\n0\r\n\r\n",
            b" 2\r\n{}\r\n0\r\n\r\n", b"0x2\r\n{}\r\n0\r\n\r\n",
            b"2\n{}\r\n0\r\n\r\n", b"2\r\n{}xx0\r\n\r\n",
            b"3\r\n{}", b"2\r\n{}\r\n0\r\n",
            b"2;=missingname\r\n{}\r\n0\r\n\r\n",
            b'2;x="unterminated\r\n{}\r\n0\r\n\r\n',
            b"2;bad name=x\r\n{}\r\n0\r\n\r\n",
            b"1" * (MAX_CHUNK_LINE_BYTES + 1) + b"\r\n",
        ]
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            for index, framed in enumerate(bodies):
                with self.subTest(framing=index):
                    self.assertEqual(400, wire_request(proxy.base_url,
                        [b"Transfer-Encoding: chunked"], framed)[0])
            proxy.drain(1)
            self.assertEqual([], recorder.before)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)

    def test_chunked_exact_one_mib_decoded_limit_is_accepted(self):
        recorder = Recorder()
        raw = json.dumps(REQUEST).encode()
        raw += b" " * (MAX_BODY_BYTES - len(raw))
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            self.assertEqual(200, wire_request(proxy.base_url,
                [b"Transfer-Encoding: chunked"], chunk_frame(raw))[0])
            proxy.drain(1)
            self.assertEqual([(REQUEST, raw)], recorder.before)
            self.assertEqual([raw], stub.requests)

    def test_chunked_cumulative_size_and_chunk_count_limits_reject(self):
        recorder = Recorder()
        cases = [
            (f"{MAX_BODY_BYTES+1:x}\r\n".encode(), 413),
            (f"{MAX_BODY_BYTES:x}\r\n".encode() + b" " * MAX_BODY_BYTES + b"\r\n1\r\n", 413),
            (b"1\r\n \r\n" * (MAX_CHUNKS + 1) + b"0\r\n\r\n", 400),
        ]
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            for index, (framed, status) in enumerate(cases):
                with self.subTest(limit=index):
                    self.assertEqual(status, wire_request(proxy.base_url,
                        [b"Transfer-Encoding: chunked"], framed)[0])
            proxy.drain(1)
            self.assertEqual([], recorder.before)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)

    def test_chunked_illegal_trailers_are_rejected(self):
        recorder = Recorder()
        cases = [
            ([], b"X-Trace: undeclared\r\n"),
            ([b"Trailer: Authorization"], b"Authorization: never-forward\r\n"),
            ([b"Trailer: Content-Length"], b"Content-Length: 2\r\n"),
            ([b"Trailer: X-Trace", b"Connection: X-Trace"], b"X-Trace: x\r\n"),
            ([b"Trailer: X-Trace", b"Trailer: X-Other"], b""),
            ([b"Trailer: X-Trace, X-Trace"], b""),
            ([b"Trailer: X-Trace"], b" X-Trace: folded\r\n"),
            ([b"Trailer: X-Trace"], b"X-Trace : whitespace-name\r\n"),
            ([b"Trailer: X-Trace"], b"X-Trace: bad\x00value\r\n"),
            ([b"Trailer: X-Trace"], b"X-Trace: bad\x7fvalue\r\n"),
            ([b"Trailer: X-Trace"], b"X-Trace: a\r\nX-Trace: b\r\n"),
            ([b"Trailer: X-Trace"], b"X-Trace: missing-crlf\n"),
            ([b"Trailer: X-Trace"], b"X-Trace: " + b"x" * MAX_CHUNK_LINE_BYTES + b"\r\n"),
        ]
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            for index, (headers, trailer) in enumerate(cases):
                with self.subTest(trailer=index):
                    self.assertEqual(400, wire_request(proxy.base_url,
                        [b"Transfer-Encoding: chunked"] + headers,
                        b"2\r\n{}\r\n0\r\n" + trailer + b"\r\n")[0])
            proxy.drain(1)
            self.assertEqual([], recorder.before)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)

    def test_chunked_trailer_section_and_field_limits_reject(self):
        recorder = Recorder()
        names = [f"X-Local-{i}".encode() for i in range(33)]
        cases = [
            (names, b""),
            (names[:9], b"".join(name + b": " + b"a" * 995 + b"\r\n" for name in names[:9])),
            (names[:32], b"".join(name + b": x\r\n" for name in names[:32]) + names[0] + b": x\r\n"),
        ]
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            for index, (declared, trailer) in enumerate(cases):
                with self.subTest(limit=index):
                    self.assertEqual(400, wire_request(proxy.base_url,
                        [b"Transfer-Encoding: chunked", b"Trailer: " + b", ".join(declared)],
                        b"2\r\n{}\r\n0\r\n" + trailer + b"\r\n")[0])
            proxy.drain(1)
            self.assertEqual([], recorder.before)
            self.assertEqual([], stub.requests)

    def test_chunked_line_and_body_trickle_share_absolute_local_deadline(self):
        self._assert_local_body_deadline(b"Transfer-Encoding: chunked", b"1\r\n \r\n")
        self._assert_local_body_deadline(b"Transfer-Encoding: chunked", b"f")

    def test_content_length_trickle_uses_absolute_local_body_deadline(self):
        self._assert_local_body_deadline(b"Content-Length: 100", b" ")

    def _assert_local_body_deadline(self, framing_header, repeated_part):
        recorder = Recorder()
        stopped = threading.Event()
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            parsed = urlsplit(proxy.base_url)
            client = socket.create_connection((parsed.hostname, parsed.port), timeout=2)
            with patch("replan_stage_d_proxy.LOCAL_BODY_TIMEOUT_SECONDS", .13):
                client.sendall(b"POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                               + framing_header + b"\r\n\r\n")
                def trickle():
                    try:
                        while not stopped.is_set():
                            client.sendall(repeated_part)
                            stopped.wait(.04)
                    except OSError:
                        pass
                sender = threading.Thread(target=trickle)
                started = time.monotonic()
                sender.start()
                try:
                    response = http.client.HTTPResponse(client)
                    response.begin()
                    response.read()
                    self.assertEqual(408, response.status)
                    self.assertLess(time.monotonic() - started, .45)
                finally:
                    stopped.set()
                    sender.join(1)
                    client.close()
            proxy.drain(1)
            self.assertEqual([], recorder.before)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)
            self.assertEqual([], proxy.errors)

    def test_chunked_decoding_precedes_gate_rejection_without_upstream(self):
        seen = []
        recorder = Recorder()
        raw = json.dumps(REQUEST, indent=2).encode()
        def reject(payload, raw_body):
            seen.append((payload, raw_body))
            raise ValueError("synthetic peak gate")
        with LocalUpstream() as stub, proxy_for(stub, recorder, before_send=reject) as proxy:
            status, body = wire_request(proxy.base_url, [b"Transfer-Encoding: chunked"], chunk_frame(raw, 7))
            proxy.drain(1)
            self.assertEqual(503, status)
            self.assertIn("D_LOCAL_GATE:", json.loads(body)["error"]["message"])
            self.assertEqual([(REQUEST, raw)], seen)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)

    def test_request_recursive_duplicate_json_keys_never_reach_gate(self):
        recorder = Recorder()
        bodies = [
            b'{"model":"a","model":"b"}',
            b'{"model":"a","\\u006dodel":"b"}',
            b'{"messages":[{"role":"user","content":"a","content":"b"}]}',
            b'{"options":{"nested":[{"max_tokens":1,"max_tokens":2}]}}',
            b'{"stream":false,"stream":true}',
        ]
        with LocalUpstream() as stub, proxy_for(stub, recorder) as proxy:
            for index, raw in enumerate(bodies):
                for chunked in (False, True):
                    with self.subTest(body=index, chunked=chunked):
                        status, body = (wire_request(proxy.base_url, [b"Transfer-Encoding: chunked"], chunk_frame(raw, 7))
                                        if chunked else request(proxy.base_url, raw=raw))
                        self.assertEqual(400, status)
                        self.assertIn("duplicate JSON object member", json.loads(body)["error"]["message"])
            proxy.drain(1)
            self.assertEqual([], recorder.before)
            self.assertEqual([], recorder.after)
            self.assertEqual([], stub.requests)

    def test_response_recursive_duplicate_json_keys_leave_usage_unknown(self):
        bodies = [
            b'{"usage":{"total_tokens":10,"total_tokens":0}}',
            b'{"usage":{"total_tokens":10},"usage":{"total_tokens":0}}',
            b'{"usage":{"total_tokens":10},"choices":[{"message":{"content":"a","content":"b"}}]}',
            b'{"usage":{"total_tokens":10,"\\u0074otal_tokens":0}}',
        ]
        for index, raw in enumerate(bodies):
            with self.subTest(response=index):
                recorder = Recorder()
                with LocalUpstream(body=raw) as stub, proxy_for(stub, recorder) as proxy:
                    self.assertEqual((200, raw), request(proxy.base_url))
                    proxy.drain(1)
                    result = self.assert_record(recorder, True, 200, True)
                    self.assertIsNone(result["responseJson"])
                    self.assertIn("D_LOCAL_RESPONSE:duplicate JSON object member; usage is unknown", result["error"])
                    self.assertEqual(hashlib.sha256(raw).hexdigest(), result["responseBodySha256"])
                    self.assertEqual(1, len(stub.requests))


class EvidenceResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.records = []

    def startTest(self, test):
        self._started_at = time.monotonic()
        self._status = "passed"
        super().startTest(test)

    def addFailure(self, test, err):
        self._status = "failed"
        super().addFailure(test, err)

    def addError(self, test, err):
        self._status = "error"
        super().addError(test, err)

    def addSubTest(self, test, subtest, err):
        if err is not None:
            self._status = "failed"
        super().addSubTest(test, subtest, err)

    def stopTest(self, test):
        self.records.append({"test": test.id(), "status": self._status,
                             "durationMs": round((time.monotonic() - self._started_at) * 1000, 3)})
        super().stopTest(test)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("tests", nargs="*", help="Optional test method names for repair-only verification")
    arguments = parser.parse_args()
    if arguments.evidence.exists():
        raise SystemExit("Evidence path already exists; preserve the first run and choose a new path")
    names = ["ProxyTests." + name if "." not in name else name for name in arguments.tests]
    suite = (unittest.defaultTestLoader.loadTestsFromNames(names, module=__import__(__name__))
             if names else unittest.defaultTestLoader.loadTestsFromTestCase(ProxyTests))
    started_at = time.monotonic()
    result = unittest.TextTestRunner(verbosity=2, resultclass=EvidenceResult).run(suite)
    payload = {"scope": "Stage D local transport only; synthetic local HTTP stubs",
               "providerCalls": 0, "externalRequests": 0,
               "testsRun": result.testsRun, "passed": sum(r["status"] == "passed" for r in result.records),
               "failureEntries": len(result.failures), "errorEntries": len(result.errors),
               "durationMs": round((time.monotonic() - started_at) * 1000, 3),
               "counters": COUNTERS, "records": result.records,
               "failures": [{"test": test.id(), "trace": trace.replace(KEY, "[REDACTED]")}
                            for test, trace in result.failures + result.errors]}
    arguments.evidence.parent.mkdir(parents=True, exist_ok=True)
    with arguments.evidence.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(payload, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps({k:payload[k] for k in ("testsRun", "passed", "providerCalls", "durationMs")}))
    raise SystemExit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
