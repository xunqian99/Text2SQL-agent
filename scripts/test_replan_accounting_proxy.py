"""Actual TCP loopback tests; no provider traffic or credential discovery."""
from __future__ import annotations

import copy
import hashlib
import http.client
import json
from pathlib import Path
import socket
import socketserver
import threading
import time
import unittest
from datetime import datetime, timezone
from unittest import mock

import replan_accounting_proxy as p


KEY = "offline-fake"
REQUEST = b'{"model":"deepseek-flash","messages":[{"role":"user","content":"synthetic"}]}'
BODY = json.dumps(dict(choices=[dict(message=dict(content="SELECT 1 AS synthetic_only"))],
    usage=dict(prompt_tokens=10, completion_tokens=5, total_tokens=15,
               prompt_cache_hit_tokens=2, prompt_cache_miss_tokens=8))).encode()
COUNTS = dict(loopbackPosts=0, externalProviderCalls=0, realKeyReads=0)


class _StubHandler(socketserver.BaseRequestHandler):
    def handle(self):
        owner = self.server.owner
        self.request.settimeout(2)
        with owner.lock:
            owner.sockets.add(self.request)
        try:
            if owner.mode in ("no_read", "tls_stall"):
                self.request.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
                owner.accepted.set()
                owner.release.wait(1.5)
                return
            reader = self.request.makefile("rb")
            first = reader.readline()
            if not first.startswith(b"POST "):
                return
            headers = {}
            while True:
                line = reader.readline()
                if line in (b"\r\n", b""):
                    break
                name, _, value = line.partition(b":")
                headers[name.lower()] = value.strip()
            raw = reader.read(int(headers.get(b"content-length", b"0")))
            with owner.lock:
                owner.requests.append(raw)
                COUNTS["loopbackPosts"] += 1
            owner.received.set()
            if owner.mode == "drop":
                return
            if owner.delay:
                owner.release.wait(owner.delay)
            if owner.mode == "header_trickle":
                self.request.sendall(b"HTTP/1.1 200 OK\r\nX-Slow: ")
                for _ in range(100):
                    self.request.sendall(b"x")
                    if owner.release.wait(.015):
                        return
                return
            if owner.mode in ("chunked", "bad_chunk", "chunk_truncated", "bad_trailer"):
                self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                if owner.mode == "bad_chunk":
                    self.request.sendall(b"+20\r\nnot-a-chunk\r\n")
                    return
                for offset in range(0, len(owner.body), 31):
                    part = owner.body[offset:offset + 31]
                    self.request.sendall(f"{len(part):x}; valid=yes\r\n".encode() + part + b"\r\n")
                    if owner.interval and owner.release.wait(owner.interval):
                        return
                if owner.mode == "chunk_truncated":
                    return
                self.request.sendall(b"0\r\n" + (b"Content-Length: 0\r\n" if owner.mode == "bad_trailer" else b"") + b"\r\n")
                return
            size = len(owner.body) + (7 if owner.mode == "truncated" else 0)
            extra = b"Content-Length: 1\r\n" if owner.mode == "duplicate_length" else b""
            self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + str(size).encode() + b"\r\n" + extra + b"Connection: close\r\n\r\n")
            if owner.mode == "trickle":
                for offset in range(0, len(owner.body), 11):
                    self.request.sendall(owner.body[offset:offset + 11])
                    if owner.release.wait(owner.interval or .03):
                        return
            else:
                self.request.sendall(owner.body)
        except (OSError, ValueError):
            pass
        finally:
            with owner.lock:
                owner.sockets.discard(self.request)


class _StubServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False


class Stub:
    def __init__(self, mode="timely", delay=0, body=BODY, interval=0):
        self.mode, self.delay, self.body, self.interval = mode, delay, body, interval
        self.requests, self.sockets = [], set()
        self.received, self.accepted, self.release = threading.Event(), threading.Event(), threading.Event()
        self.lock = threading.Lock()
        self.server = _StubServer(("127.0.0.1", 0), _StubHandler)
        if mode == "no_read":
            self.server.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
        self.server.owner = self
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/v1/chat/completions"
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs=dict(poll_interval=.01), daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.release.set()
        self.server.shutdown()
        with self.lock:
            sockets = list(self.sockets)
        for raw in sockets:
            try:
                raw.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.server.server_close()
        self.thread.join(1)


class Recorder:
    def __init__(self, budget=.16, grace=.45, round_seconds=2, after_delay=0):
        self.budget, self.grace, self.round_seconds, self.after_delay = budget, grace, round_seconds, after_delay
        self.before, self.after, self.checks = [], [], []

    def before_send(self, payload, raw):
        now = time.monotonic()
        permit = dict(token="synthetic-" + str(len(self.before)), planSha256="a" * 64,
            round="DEV_F", ordinal=len(self.before), questionId="synthetic-question-" + str(len(self.before)),
            attempt=1, requestId="request-" + str(len(self.before)), callNumber=1,
            requestBodySha256=hashlib.sha256(raw).hexdigest(), requestBytes=len(raw),
            promptSha256="b" * 64, candidateHoldId="hold-" + str(len(self.before)),
            fence=dict(questionId="frozen-synthetic-question", generation=1),
            upstreamDeadlineMonotonic=now + self.budget,
            answerDeadlineMonotonic=now + self.budget,
            accountingDeadlineMonotonic=now + self.budget + self.grace,
            wholeRoundDeadlineMonotonic=now + self.round_seconds)
        self.before.append(permit)
        return permit

    def after_send(self, permit, result):
        if self.after_delay:
            time.sleep(self.after_delay)
        self.after.append((permit, result))

    def check_send(self, permit):
        self.checks.append(permit)


def proxy(stub, recorder, **options):
    return p.ModelProxy(KEY, recorder.before_send, recorder.after_send,
                        upstream_url=stub.url, allow_loopback_test=True,
                        check_send=recorder.check_send,
                        clock=lambda: datetime(2026, 11, 21, 4, tzinfo=timezone.utc),
                        accounting_extra_seconds=recorder.grace, **options)


def request(raw=REQUEST):
    client = http.client.HTTPConnection("127.0.0.1", 59900, timeout=3)
    started = time.monotonic()
    try:
        client.request("POST", "/v1/chat/completions", raw, {"Content-Type": "application/json"})
        response = client.getresponse()
        return response.status, response.read(), time.monotonic() - started
    finally:
        client.close()


class AccountingProxyTests(unittest.TestCase):
    def assert_result(self, rec, complete=True, late=False):
        self.assertEqual(len(rec.after), 1)
        permit, result = rec.after[0]
        self.assertEqual(permit, result["permitSnapshot"])
        self.assertEqual(permit["token"], rec.before[0]["token"])
        self.assertEqual(result["responseComplete"], complete)
        self.assertEqual(result["late"], late)
        self.assertEqual(result["accountingOnly"], late)
        raw = result["responseBodyRaw"]
        self.assertEqual(result["responseBytes"], len(raw))
        self.assertEqual(result["responseBodySha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(result["resends"], 0)
        self.assertEqual(result["reconnects"], 0)
        self.assertFalse(result["lateSqlBackfill"])
        return result

    def test_timely_response_is_forwarded_once_with_exact_raw(self):
        rec = Recorder()
        with Stub() as stub, proxy(stub, rec) as running:
            status, body, elapsed = request()
            running.drain(1)
            self.assertEqual((status, body), (200, BODY))
            result = self.assert_result(rec)
            self.assertTrue(result["answerEligible"])
            self.assertTrue(result["usageKnown"])
            self.assertTrue(result["requestWireComplete"])
            self.assertEqual(stub.requests, [REQUEST])
            self.assertEqual(running.base_url, "http://127.0.0.1:59900")
            self.assertEqual(running.metrics["upstreamPosts"], 1)
            self.assertGreaterEqual(len(rec.checks), 6)
            self.assertFalse(running.errors)

    def test_late_headers_read_original_connection_without_answer_backfill(self):
        rec = Recorder()
        with Stub(delay=.3) as stub, proxy(stub, rec) as running:
            status, body, elapsed = request()
            self.assertEqual(status, 504)
            self.assertLess(elapsed, .27)
            self.assertNotIn(b"SELECT", body)
            running.drain(1)
            result = self.assert_result(rec, late=True)
            self.assertTrue(result["usageKnown"])
            self.assertFalse(result["answerEligible"])
            self.assertEqual(result["responseBodyRaw"], BODY)
            self.assertEqual(len(stub.requests), 1)

    def test_slow_after_callback_cannot_delay_timely_answer(self):
        rec = Recorder(after_delay=.3)
        with Stub() as stub, proxy(stub, rec) as running:
            status, body, elapsed = request()
            self.assertEqual(status, 200)
            self.assertLess(elapsed, .16)
            running.drain(1)
            self.assert_result(rec)

    def test_header_trickle_stops_at_absolute_accounting_deadline(self):
        rec = Recorder(budget=.08, grace=.16)
        with Stub(mode="header_trickle") as stub, proxy(stub, rec) as running:
            started = time.monotonic()
            self.assertEqual(request()[0], 504)
            running.drain(1)
            result = self.assert_result(rec, complete=False, late=True)
            self.assertLess(time.monotonic() - started, .45)
            self.assertEqual(result["responseBodyRaw"], b"")
            self.assertFalse(result["usageKnown"])

    def test_body_trickle_keeps_prefix_and_cannot_extend_deadline(self):
        rec = Recorder(budget=.08, grace=.16)
        with Stub(mode="trickle") as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 504)
            running.drain(1)
            result = self.assert_result(rec, complete=False, late=True)
            self.assertGreater(len(result["responseBodyRaw"]), 0)
            self.assertTrue(BODY.startswith(result["responseBodyRaw"]))
            self.assertIsNone(result["responseJson"])

    def test_valid_chunked_late_response_is_complete_accounting_only(self):
        rec = Recorder(budget=.08)
        with Stub(mode="chunked", interval=.025) as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 504)
            running.drain(1)
            result = self.assert_result(rec, late=True)
            self.assertEqual(result["responseBodyRaw"], BODY)
            self.assertTrue(result["usageKnown"])

    def test_invalid_chunk_framing_is_rejected(self):
        for mode in ("bad_chunk", "bad_trailer", "chunk_truncated", "duplicate_length"):
            with self.subTest(mode=mode):
                rec = Recorder()
                with Stub(mode=mode) as stub, proxy(stub, rec) as running:
                    self.assertEqual(request()[0], 502)
                    running.drain(1)
                    result = self.assert_result(rec, complete=False)
                    self.assertFalse(result["usageKnown"])
                    self.assertEqual(len(stub.requests), 1)

    def test_truncated_body_is_unknown_even_if_prefix_is_valid_json(self):
        rec = Recorder()
        with Stub(mode="truncated") as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 502)
            running.drain(1)
            result = self.assert_result(rec, complete=False)
            self.assertEqual(result["responseBodyRaw"], BODY)
            self.assertIsNone(result["responseJson"])
            self.assertFalse(result["usageKnown"])

    def test_duplicate_upstream_json_is_preserved_but_never_answer_or_usage(self):
        rec = Recorder()
        body = b'{"usage":{"total_tokens":1,"total_tokens":2}}'
        with Stub(body=body) as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 502)
            running.drain(1)
            result = self.assert_result(rec)
            self.assertEqual(result["responseBodyRaw"], body)
            self.assertIsNone(result["responseJson"])
            self.assertFalse(result["usageKnown"])
            self.assertFalse(result["answerEligible"])

    def test_duplicate_local_json_never_acquires_permit(self):
        rec = Recorder()
        with Stub() as stub, proxy(stub, rec) as running:
            self.assertEqual(request(b'{"model":"a","model":"b"}')[0], 400)
            running.drain(1)
            self.assertEqual(rec.before, [])
            self.assertEqual(rec.after, [])
            self.assertEqual(stub.requests, [])

    def test_response_body_cap_preserves_only_bounded_prefix(self):
        rec = Recorder()
        with Stub(body=b"x" * 8192) as stub, proxy(stub, rec, max_body_bytes=1024) as running:
            self.assertEqual(request()[0], 502)
            running.drain(1)
            result = self.assert_result(rec, complete=False)
            self.assertEqual(len(result["responseBodyRaw"]), 1024)
            self.assertLessEqual(running.metrics["peakBufferedBodyBytes"], 1024)

    def test_default_two_mib_hard_cap_is_enforced_over_actual_network(self):
        rec = Recorder(budget=.5)
        with Stub(body=b"x" * (p.MAX_RESPONSE_BYTES + 1)) as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 502)
            running.drain(1)
            result = self.assert_result(rec, complete=False)
            self.assertEqual(result["responseBytes"], p.MAX_RESPONSE_BYTES)

    def _capacity(self, **limits):
        rec = Recorder(budget=.08)
        with Stub(delay=.3) as stub, proxy(stub, rec, **limits) as running:
            self.assertEqual(request()[0], 504)
            self.assertTrue(stub.received.is_set())
            self.assertEqual(request()[0], 503)
            self.assertEqual(len(rec.before), 1)
            running.drain(1)
            self.assert_result(rec, late=True)
            self.assertEqual(len(stub.requests), 1)
            self.assertEqual(running.metrics["resourceRejections"], 1)
            self.assertEqual(running._reserved, 0)
            self.assertEqual(running._buffered, 0)

    def test_collector_slot_is_reserved_before_quota_or_wire(self):
        self._capacity(max_collectors=1)

    def test_body_capacity_is_reserved_before_quota_or_wire(self):
        self._capacity(max_active_body_bytes=p.MAX_RESPONSE_BYTES)

    def test_limits_cannot_exceed_two_mib_sixteen_or_thirtytwo_mib(self):
        rec = Recorder()
        with Stub() as stub:
            for limits in (dict(max_body_bytes=p.MAX_RESPONSE_BYTES + 1), dict(max_collectors=17),
                           dict(max_active_body_bytes=p.MAX_ACTIVE_BODY_BYTES + 1), dict(accounting_extra_seconds=106)):
                with self.subTest(limits=limits), self.assertRaises(ValueError):
                    p.ModelProxy(KEY, rec.before_send, rec.after_send, upstream_url=stub.url,
                                 allow_loopback_test=True, **limits)

    def test_absolute_round_deadline_shortens_accounting_and_drain(self):
        rec = Recorder(budget=.08, round_seconds=.22)
        with Stub(delay=.6) as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 504)
            try:
                running.drain(1)
            except TimeoutError:
                # Reaching the absolute round boundary must cancel, not renew.
                pass
            deadline = time.monotonic() + .25
            while not rec.after and time.monotonic() < deadline:
                time.sleep(.005)
            result = self.assert_result(rec, complete=False, late=True)
            self.assertEqual(result["accountingDeadlineMonotonic"], rec.before[0]["wholeRoundDeadlineMonotonic"])

    def test_offpeak_boundary_shortens_accounting(self):
        rec = Recorder(budget=.06)
        before = rec.before_send
        def bounded(payload, raw):
            permit = before(payload, raw)
            permit["offPeakBoundaryMonotonic"] = time.monotonic() + .18
            return permit
        rec.before_send = bounded
        with Stub(delay=.4) as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 504)
            running.drain(1)
            result = self.assert_result(rec, complete=False, late=True)
            self.assertEqual(result["accountingDeadlineMonotonic"], rec.before[0]["offPeakBoundaryMonotonic"])

    def test_period_change_at_write_is_rechecked_and_refuses_wire(self):
        rec = Recorder()
        checks = []
        with Stub() as stub:
            running = proxy(stub, rec)
            original = running._connect
            def connect_then_peak(state):
                original(state)
                running._clock = lambda: datetime(2026, 11, 20, 2, tzinfo=timezone.utc)
            running._connect = connect_then_peak
            with running:
                self.assertEqual(request()[0], 503)
                running.drain(1)
                result = self.assert_result(rec, complete=False)
                self.assertFalse(result["requestWireComplete"])
                self.assertEqual(stub.requests, [])

    def test_controller_check_send_exception_blocks_post(self):
        rec = Recorder()
        def reject(permit):
            raise ValueError("D_LOCAL_GATE:controller stop")
        rec.check_send = reject
        with Stub() as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 503)
            running.drain(1)
            result = self.assert_result(rec, complete=False)
            self.assertFalse(result["upstreamAttempted"])
            self.assertEqual(stub.requests, [])

    def test_after_uses_original_fence_when_next_question_mutates_input(self):
        rec = Recorder(budget=.08)
        with Stub(delay=.25) as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 504)
            snapshot = copy.deepcopy(rec.before[0])
            rec.before[0]["fence"]["questionId"] = "NEXT-QUESTION"
            running.drain(1)
            permit, result = rec.after[0]
            self.assertEqual(permit, snapshot)
            self.assertEqual(result["permitSnapshot"], snapshot)

    def test_close_interrupts_collector_and_calls_after_exactly_once(self):
        rec = Recorder(budget=.06)
        with Stub(delay=1) as stub:
            running = proxy(stub, rec)
            with running:
                self.assertEqual(request()[0], 504)
                started = time.monotonic()
            self.assertLess(time.monotonic() - started, .4)
            self.assert_result(rec, complete=False, late=True)
            self.assertFalse(running._collectors)
            self.assertFalse(any(t.is_alive() for t in running._all_threads))

    def test_disconnected_java_client_does_not_drop_accounting(self):
        rec = Recorder()
        with Stub(delay=.08) as stub, proxy(stub, rec) as running:
            client = socket.create_connection(("127.0.0.1", 59900), timeout=1)
            client.sendall(b"POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                           + str(len(REQUEST)).encode() + b"\r\n\r\n" + REQUEST)
            self.assertTrue(stub.received.wait(1))
            client.close()
            running.drain(1)
            self.assert_result(rec)

    def test_missing_accounting_or_round_deadline_refuses_before_wire(self):
        for field in ("accountingDeadlineMonotonic", "wholeRoundDeadlineMonotonic"):
            with self.subTest(field=field):
                rec = Recorder()
                before = rec.before_send
                def missing(payload, raw):
                    permit = before(payload, raw)
                    permit.pop(field)
                    return permit
                rec.before_send = missing
                with Stub() as stub, proxy(stub, rec) as running:
                    self.assertEqual(request()[0], 503)
                    running.drain(1)
                    self.assertEqual(len(rec.after), 1)
                    self.assertFalse(rec.after[0][1]["upstreamAttempted"])
                    self.assertEqual(stub.requests, [])

    def test_dns_delay_cannot_extend_answer_or_later_send(self):
        rec = Recorder(budget=.07)
        original = socket.getaddrinfo
        with Stub() as stub, proxy(stub, rec) as running:
            port = stub.server.server_address[1]
            def delayed(host, target_port, *args, **kwargs):
                if target_port == port:
                    time.sleep(.2)
                return original(host, target_port, *args, **kwargs)
            with mock.patch.object(socket, "getaddrinfo", delayed):
                status, body, elapsed = request()
                self.assertEqual(status, 504)
                self.assertLess(elapsed, .17)
                running.drain(1)
                self.assertEqual(len(rec.after), 1)
                self.assertFalse(rec.after[0][1]["requestWireComplete"])
                time.sleep(.21)
                self.assertEqual(stub.requests, [])

    def test_real_blocked_write_cannot_enter_read_only_grace(self):
        rec = Recorder(budget=.08)
        with Stub(mode="no_read") as stub:
            running = proxy(stub, rec)
            connect = running._connect
            def small_send_buffer(state):
                connect(state)
                state.raw.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1024)
            running._connect = small_send_buffer
            with running:
                raw = json.dumps(dict(model="synthetic", prompt="x" * 900000)).encode()
                status, body, elapsed = request(raw)
                self.assertEqual(status, 504)
                running.drain(1)
                self.assertLess(elapsed, .3)
                result = rec.after[0][1]
                self.assertFalse(result["requestWireComplete"])
                self.assertFalse(result["sameSentConnectionReadOnly"])
                self.assertEqual(result["responseBodyRaw"], b"")
                self.assertEqual(running.metrics["upstreamPosts"], 0)

    def test_real_blocked_tls_handshake_uses_original_deadline(self):
        rec = Recorder(budget=.12)
        with Stub(mode="tls_stall") as stub:
            running = proxy(stub, rec)
            running._endpoint = ("https", "127.0.0.1", stub.server.server_address[1], p.frozen.MODEL_PATH)
            with running:
                status, body, elapsed = request()
                self.assertEqual(status, 504)
                running.drain(1)
                self.assertLess(elapsed, .3)
                self.assertFalse(rec.after[0][1]["requestWireComplete"])

    def test_after_failure_is_visible_and_stops_next_send(self):
        rec = Recorder()
        def fail(permit, result):
            rec.after.append((permit, result))
            raise OSError("synthetic durable storage failure")
        rec.after_send = fail
        with Stub() as stub, proxy(stub, rec) as running:
            self.assertEqual(request()[0], 200)
            running.drain(1)
            self.assertTrue(running.errors)
            self.assertEqual(request()[0], 503)
            running.drain(1)
            self.assertEqual(len(stub.requests), 1)

    def test_durable_before_answer_waits_for_successful_after_before_sql(self):
        rec = Recorder(budget=.25)
        settled = threading.Event()
        def after(permit, result):
            time.sleep(.045)
            rec.after.append((permit, result))
            settled.set()
        rec.after_send = after
        with Stub() as stub, proxy(stub, rec, durable_before_answer=True) as running:
            status, body, elapsed = request()
            self.assertEqual((status, body), (200, BODY))
            self.assertTrue(settled.is_set())
            self.assertGreaterEqual(elapsed, .04)
            running.drain(1)
            result = self.assert_result(rec)
            self.assertTrue(result["providerBodyTimely"])
            self.assertTrue(result["answerEligible"])
            self.assertTrue(result["durableBeforeAnswer"])
            self.assertIsNone(result["answerReturnedAtUtc"])
            self.assertIn("PROVIDER_BODY_READINESS_ONLY", result["eligibilityScope"])
            self.assertIsNotNone(result["bodyReadyAtUtc"])
            self.assertEqual(running.metrics["afterCalls"], 1)

    def test_slow_durable_after_cannot_extend_http_deadline_or_backfill_sql(self):
        rec = Recorder(budget=.08, after_delay=.3)
        with Stub() as stub, proxy(stub, rec, durable_before_answer=True) as running:
            status, body, elapsed = request()
            self.assertEqual(status, 504)
            self.assertNotIn(b"SELECT", body)
            self.assertLess(elapsed, .2)
            self.assertEqual(rec.after, [])
            running.drain(1)
            result = self.assert_result(rec)
            # Qualification is explicitly about the timely body. Actual HTTP is
            # 504 above; these fields must never be read as delivery evidence.
            self.assertTrue(result["providerBodyTimely"])
            self.assertTrue(result["answerEligible"])
            self.assertFalse(result["late"])
            self.assertFalse(result["accountingOnly"])
            self.assertIsNone(result["answerReturnedAtUtc"])
            self.assertIn("HTTP delivery is controller evidence", result["eligibilityScope"])
            self.assertEqual(running.metrics["afterCalls"], 1)
            self.assertEqual(len(stub.requests), 1)

    def test_durable_after_failure_never_delivers_timely_sql_and_blocks_next_send(self):
        rec = Recorder()
        def fail(permit, result):
            rec.after.append((permit, result))
            raise OSError("synthetic durable settlement failed")
        rec.after_send = fail
        with Stub() as stub, proxy(stub, rec, durable_before_answer=True) as running:
            status, body, elapsed = request()
            self.assertEqual(status, 502)
            self.assertNotIn(b"SELECT", body)
            self.assertIn(b"durable settlement failed", body)
            running.drain(1)
            self.assertTrue(running.errors)
            self.assertEqual(request()[0], 503)
            running.drain(1)
            self.assertEqual(len(rec.before), 1)
            self.assertEqual(len(rec.after), 1)
            self.assertEqual(len(stub.requests), 1)
            self.assertEqual(running.metrics["afterCalls"], 1)

    def test_late_body_in_durable_mode_stays_accounting_only_with_one_after(self):
        rec = Recorder(budget=.08, after_delay=.025)
        with Stub(delay=.2) as stub, proxy(stub, rec, durable_before_answer=True) as running:
            status, body, elapsed = request()
            self.assertEqual(status, 504)
            self.assertLess(elapsed, .18)
            self.assertNotIn(b"SELECT", body)
            running.drain(1)
            result = self.assert_result(rec, late=True)
            self.assertFalse(result["providerBodyTimely"])
            self.assertFalse(result["answerEligible"])
            self.assertTrue(result["accountingOnly"])
            self.assertIsNone(result["answerReturnedAtUtc"])
            self.assertTrue(result["usageKnown"])
            self.assertEqual(result["responseBodyRaw"], BODY)
            self.assertEqual(running.metrics["afterCalls"], 1)
            self.assertEqual(len(stub.requests), 1)

    def test_second_listener_is_refused_with_no_alternate_port(self):
        rec = Recorder()
        with Stub() as stub, proxy(stub, rec) as owner:
            rejected = Recorder()
            with self.assertRaises(OSError):
                with proxy(stub, rejected):
                    pass
            self.assertEqual(rejected.before, [])
            self.assertEqual(request()[0], 200)
            owner.drain(1)

    def test_official_default_and_arbitrary_endpoint_denial(self):
        rec = Recorder()
        instance = p.ModelProxy(KEY, rec.before_send, rec.after_send)
        self.assertEqual(instance._endpoint, ("https", "api.deepseek.com", 443, p.frozen.MODEL_PATH))
        for url in ("https://example.com/v1/chat/completions", "http://192.0.2.1:80/v1/chat/completions"):
            with self.assertRaises(ValueError):
                p.ModelProxy(KEY, rec.before_send, rec.after_send, upstream_url=url, allow_loopback_test=True)

    def test_frozen_source_and_listener_remain_unmodified(self):
        self.assertEqual(hashlib.sha256(Path(p.frozen.__file__).read_bytes()).hexdigest(),
                         "e3aa8b4c04d15f990a5a15a160e874d3a9edd4a74c45f90e1661172debf731f1")


if __name__ == "__main__":
    started = time.perf_counter()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(AccountingProxyTests))
    print(json.dumps(dict(testsRun=result.testsRun, failures=len(result.failures), errors=len(result.errors),
                          wallSeconds=round(time.perf_counter()-started, 3), **COUNTS)))
    raise SystemExit(0 if result.wasSuccessful() else 1)
