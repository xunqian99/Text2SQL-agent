"""Single-send transport with an independent, bounded accounting read window.

The listener and local HTTP decoder are the frozen exclusive Windows listener.
Keys arrive only as in-memory arguments. No environment lookup, retry, redirect,
reconnect, or usage-query path exists. Callbacks must be bounded local operations;
after_send owns durable persistence of the raw body/prefix before settlement.
"""
from __future__ import annotations

import copy
import errno
import hashlib
import http.client
import io
import json
import math
import re
import select
import socket
import ssl
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

import replan_stage_d_proxy as frozen
from replan_stable_proxy import StableModelProxy


MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_COLLECTORS = 16
MAX_ACTIVE_BODY_BYTES = 32 * 1024 * 1024
ACCOUNTING_EXTRA_SECONDS = 105.0
SEND_GUARD_SECONDS = 120
ANSWER_MAX_SECONDS = 15.0
_DNS_SLOTS = threading.BoundedSemaphore(MAX_COLLECTORS)


def _number(value, name):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError("D_LOCAL_GATE:finite " + name + " required")
    return float(value)


def _peak_boundary(at):
    if at.tzinfo is None:
        raise ValueError("D_LOCAL_GATE:timezone-aware clock required")
    local = at.astimezone(timezone(timedelta(hours=8)))
    for offset in range(8):
        day = local.date() + timedelta(days=offset)
        if day.weekday() >= 5:
            continue
        for start, end in ((9, 12), (14, 18)):
            left = datetime.combine(day, datetime.min.time(), local.tzinfo) + timedelta(hours=start)
            right = left + timedelta(hours=end - start)
            if left <= local < right:
                raise ValueError("D_LOCAL_GATE:PEAK_HOURS")
            if left > local:
                return left
    raise ValueError("D_LOCAL_GATE:off-peak boundary unavailable")


def _window(at):
    boundary = _peak_boundary(at)
    if at + timedelta(seconds=SEND_GUARD_SECONDS) >= boundary:
        raise ValueError("D_LOCAL_GATE:PEAK_BOUNDARY_RESERVE")
    return boundary


@dataclass(eq=False)
class _Collection:
    permit: object
    body: bytearray = field(default_factory=bytearray)
    done: threading.Event = field(default_factory=threading.Event)
    after_done: threading.Event = field(default_factory=threading.Event)
    answer_decided: threading.Event = field(default_factory=threading.Event)
    abort: threading.Event = field(default_factory=threading.Event)
    lock: threading.Lock = field(default_factory=threading.Lock)
    raw: object = None
    reader: object = None
    answer_deadline: float = 0.0
    upstream_deadline: float = 0.0
    accounting_deadline: float = 0.0
    round_deadline: float = 0.0
    offpeak_deadline: float = 0.0
    wire_complete: bool = False
    answer_frozen: bool = False
    answer_returned: str | None = None
    ready_at: float = 0.0
    after_succeeded: bool = False
    decision_owner: bool = False
    after_error: str | None = None
    status: int = 502
    headers: dict = field(default_factory=dict)
    result: dict = field(default_factory=dict)
    validation_error: object = None


class _ReadSocket(io.RawIOBase):
    def __init__(self, state):
        super().__init__()
        self.state = state

    def readable(self):
        return True

    def readinto(self, buffer):
        s = self.state
        while True:
            if s.abort.is_set() or not s.wire_complete:
                raise OSError("accounting read closed or request wire incomplete")
            # On Windows another thread's shutdown need not interrupt a blocked
            # recv immediately. Short polls bound cancellation without resetting
            # the absolute deadline or losing any received prefix.
            s.raw.settimeout(min(.025, frozen._remaining(s.accounting_deadline)))
            try:
                return s.raw.recv_into(buffer)
            except socket.timeout:
                frozen._remaining(s.accounting_deadline)


class ModelProxy(StableModelProxy):
    """Compatible before/after transport on 127.0.0.1:59900.

    Required permit fields: token, upstreamDeadlineMonotonic,
    accountingDeadlineMonotonic (or postSendAccountingDeadlineMonotonic), and
    wholeRoundDeadlineMonotonic. answerDeadlineMonotonic defaults to upstream.
    An optional offPeakBoundaryMonotonic can shorten the independently computed
    Asia/Shanghai boundary. Every deadline is absolute time.monotonic().

    check_send(permit) runs before/after DNS, before connect/TLS, and before every
    write. The proxy also independently enforces the 120-second wall-clock guard.
    Lower resource limits and shorter accounting grace support free loopback
    tests; they can never increase the production limits.

    durable_before_answer=True withholds a timely SQL response until after_send
    has successfully persisted the raw evidence and settlement. The independent
    HTTP deadline still returns 504 while slow accounting completes. In this
    mode late/accountingOnly/answerEligible describe provider-body readiness,
    never successful HTTP delivery; the controller records actual HTTP facts.
    """

    def __init__(self, key, before_send, after_send, upstream_url=frozen.OFFICIAL_URL,
                 allow_loopback_test=False, *, check_send=None, clock=None,
                 accounting_extra_seconds=ACCOUNTING_EXTRA_SECONDS,
                 max_collectors=MAX_COLLECTORS, max_body_bytes=MAX_RESPONSE_BYTES,
                 max_active_body_bytes=MAX_ACTIVE_BODY_BYTES,
                 durable_before_answer=False):
        super().__init__(key, before_send, after_send, upstream_url, allow_loopback_test)
        for value, ceiling, name in ((max_collectors, MAX_COLLECTORS, "collectors"),
                                     (max_body_bytes, MAX_RESPONSE_BYTES, "body bytes"),
                                     (max_active_body_bytes, MAX_ACTIVE_BODY_BYTES, "active body bytes")):
            if type(value) is not int or not 0 < value <= ceiling:
                raise ValueError("invalid " + name)
        grace = _number(accounting_extra_seconds, "accounting grace")
        if not 0 < grace <= ACCOUNTING_EXTRA_SECONDS:
            raise ValueError("accounting grace cannot exceed 105 seconds")
        if check_send is not None and not callable(check_send):
            raise TypeError("check_send must be callable")
        if type(durable_before_answer) is not bool:
            raise TypeError("durable_before_answer must be a boolean")
        if clock is not None and (not allow_loopback_test or self._endpoint[0] != "http"):
            raise ValueError("clock injection is restricted to loopback tests")
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._send_check = check_send
        self._durable_before_answer = durable_before_answer
        self._grace = grace
        self._max_collectors = max_collectors
        self._max_body = max_body_bytes
        self._max_active_body = max_active_body_bytes
        self._states = set()
        self._collectors = set()
        self._reserved = 0
        self._buffered = 0
        self._answer_decisions = {}
        self.metrics = dict(peakCollectors=0, peakReservedBodyBytes=0,
                            peakBufferedBodyBytes=0, resourceRejections=0,
                            upstreamPosts=0, afterCalls=0)

    def get_answer_decisions(self):
        """Return detached final HTTP decisions, independent of body completions.

        These are selected responses, not proof the client received them. Drain
        before create-only persistence, then reconcile with controller HTTP
        evidence. A token gets one decision; callers cannot mutate the archive.
        """
        with self._condition:
            return copy.deepcopy(list(self._answer_decisions.values()))

    def _record_answer_decision(self, state, status, exceeded):
        permit = state.permit if isinstance(state.permit, dict) else {}
        token = permit.get("token")
        if not state.decision_owner or not isinstance(token, str) or not token:
            return
        decision = dict(token=token, questionId=permit.get("questionId"),
                        round=permit.get("round"), ordinal=permit.get("ordinal"),
                        attempt=permit.get("attempt"), requestId=permit.get("requestId"),
                        answerDeadlineExceeded=bool(exceeded), selectedHttpStatus=status,
                        decisionAtUtc=frozen._utc_now(),
                        bodyTimely=bool(state.result.get("providerBodyTimely", False)),
                        durableSettlementSucceeded=state.after_succeeded,
                        durableBeforeAnswer=self._durable_before_answer,
                        decisionScope="SELECTED_HTTP_RESPONSE; client receipt is controller evidence")
        with self._condition:
            # An attempted token replay cannot replace the original decision.
            if token not in self._answer_decisions:
                self._answer_decisions[token] = decision

    def _reserve(self):
        with self._condition:
            self._check_send_open()
            if self.errors:
                raise ValueError("D_LOCAL_GATE:prior accounting failure")
            if len(self._states) >= self._max_collectors or self._reserved + self._max_body > self._max_active_body:
                self.metrics["resourceRejections"] += 1
                raise ValueError("D_LOCAL_GATE:ACCOUNTING_RESOURCE_CAPACITY")
            state = _Collection(None)
            self._states.add(state)
            self._reserved += self._max_body
            self.metrics["peakCollectors"] = max(self.metrics["peakCollectors"], len(self._states))
            self.metrics["peakReservedBodyBytes"] = max(self.metrics["peakReservedBodyBytes"], self._reserved)
            return state

    def _release(self, state):
        with self._condition:
            if state in self._states:
                self._states.remove(state)
                self._reserved -= self._max_body
                self._buffered -= state.result.get("responseBytes", len(state.body))
            self._condition.notify_all()

    def _validate(self, state, raw_body):
        permit = state.permit
        with self._condition:
            token = permit.get("token") if isinstance(permit, dict) else None
            state.decision_owner = isinstance(token, str) and bool(token) and token not in self._tokens
            upstream = self._validate_permit(permit)
        answer = _number(permit.get("answerDeadlineMonotonic", upstream), "answer deadline")
        round_end = _number(permit.get("wholeRoundDeadlineMonotonic"), "round deadline")
        accounting = permit.get("accountingDeadlineMonotonic", permit.get("postSendAccountingDeadlineMonotonic"))
        accounting = _number(accounting, "accounting deadline")
        if ("accountingDeadlineMonotonic" in permit and "postSendAccountingDeadlineMonotonic" in permit
                and permit["accountingDeadlineMonotonic"] != permit["postSendAccountingDeadlineMonotonic"]):
            raise ValueError("D_LOCAL_GATE:conflicting accounting deadlines")
        if upstream > answer:
            raise ValueError("D_LOCAL_GATE:upstream deadline exceeds answer deadline")
        if answer > time.monotonic() + ANSWER_MAX_SECONDS:
            raise ValueError("D_LOCAL_GATE:answer deadline exceeds 15 seconds")
        if "requestBodySha256" in permit and permit["requestBodySha256"] != hashlib.sha256(raw_body).hexdigest():
            raise ValueError("D_LOCAL_GATE:request body SHA mismatch")
        if "requestBytes" in permit and permit["requestBytes"] != len(raw_body):
            raise ValueError("D_LOCAL_GATE:request bytes mismatch")
        wall = self._clock()
        wall_boundary = _window(wall)
        boundary = time.monotonic() + (wall_boundary - wall).total_seconds()
        if "offPeakBoundaryMonotonic" in permit:
            boundary = min(boundary, _number(permit["offPeakBoundaryMonotonic"], "off-peak boundary"))
        state.upstream_deadline = min(upstream, round_end, boundary)
        state.answer_deadline = min(answer, round_end, boundary)
        state.accounting_deadline = min(accounting, answer + self._grace, round_end, boundary)
        if state.accounting_deadline < state.answer_deadline:
            raise ValueError("D_LOCAL_GATE:accounting deadline precedes answer deadline")
        state.round_deadline, state.offpeak_deadline = round_end, boundary
        frozen._remaining(state.upstream_deadline)
        frozen._remaining(state.accounting_deadline)
        # A private snapshot preserves nested fences when the caller reuses or
        # mutates the dict on its next question. Never pass this copy to a gate.
        state.result.update(permitSnapshot=copy.deepcopy(permit),
                            permitSha256=hashlib.sha256(json.dumps(permit, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest(),
                            answerDeadlineMonotonic=state.answer_deadline,
                            upstreamDeadlineMonotonic=state.upstream_deadline,
                            accountingDeadlineMonotonic=state.accounting_deadline,
                            postSendAccountingDeadlineMonotonic=state.accounting_deadline,
                            wholeRoundDeadlineMonotonic=round_end,
                            offPeakBoundaryMonotonic=boundary)

    def _check_send(self, state):
        self._check_send_open()
        if self.errors:
            raise ValueError("D_LOCAL_GATE:prior accounting failure")
        if state.abort.is_set() or state.wire_complete:
            raise ValueError("D_LOCAL_GATE:send closed")
        frozen._remaining(state.upstream_deadline)
        _window(self._clock())
        if self._send_check is not None:
            self._send_check(copy.deepcopy(state.permit))
        self._check_send_open()
        _window(self._clock())
        frozen._remaining(state.upstream_deadline)

    def _resolve(self, host, port, state):
        self._check_send(state)
        # OS getaddrinfo cannot be cancelled. Bound the caller's wait, cap resolver
        # concurrency, and never let a delayed resolver connect/send on its own.
        if not _DNS_SLOTS.acquire(blocking=False):
            raise ValueError("D_LOCAL_GATE:DNS_RESOURCE_CAPACITY")
        done, answer = threading.Event(), []
        def resolve():
            try:
                answer.append(socket.getaddrinfo(host, port, type=socket.SOCK_STREAM))
            except Exception as exc:
                answer.append(exc)
            finally:
                _DNS_SLOTS.release()
                done.set()
        threading.Thread(target=resolve, name="accounting-dns", daemon=True).start()
        while not done.wait(min(.02, frozen._remaining(state.upstream_deadline))):
            self._check_send(state)
        self._check_send(state)
        if isinstance(answer[0], Exception):
            raise answer[0]
        if not answer[0]:
            raise OSError("upstream address unavailable")
        return answer[0][0]

    def _connect(self, state):
        scheme, host, port, _ = self._endpoint
        family, kind, proto, _, address = self._resolve(host, port, state)
        self._check_send(state)
        raw = socket.socket(family, kind, proto)
        state.raw = raw
        raw.setblocking(False)
        code = raw.connect_ex(address)
        if code not in (0, errno.EISCONN, errno.EWOULDBLOCK, errno.EINPROGRESS, errno.EALREADY, 10035, 10036, 10037):
            raise OSError(code, "upstream connect failed")
        if code not in (0, errno.EISCONN):
            self._wait_socket(state)
            error = raw.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR)
            if error:
                raise OSError(error, "upstream connect failed")
        self._check_send(state)
        if scheme == "https":
            # Handshake bytes share the original upstream deadline, never grace.
            context = ssl.create_default_context()
            self._check_send(state)
            secured = context.wrap_socket(raw, server_hostname=host, do_handshake_on_connect=False)
            state.raw = secured
            secured.setblocking(False)
            while True:
                self._check_send(state)
                try:
                    secured.do_handshake()
                    break
                except ssl.SSLWantReadError:
                    self._wait_socket(state, read=True)
                except ssl.SSLWantWriteError:
                    self._wait_socket(state)
            self._check_send(state)

    def _wait_socket(self, state, read=False):
        while True:
            self._check_send(state)
            duration = min(.025, frozen._remaining(state.upstream_deadline))
            readers, writers, errors = select.select([state.raw] if read else [],
                                                     [] if read else [state.raw], [state.raw], duration)
            if readers or writers or errors:
                self._check_send(state)
                return

    def _send(self, state, data):
        view = memoryview(data)
        state.raw.setblocking(False)
        while view:
            self._check_send(state)
            try:
                sent = state.raw.send(view[:65536])
            except ssl.SSLWantReadError:
                self._wait_socket(state, read=True)
                continue
            except (BlockingIOError, ssl.SSLWantWriteError):
                self._wait_socket(state)
                continue
            if not sent:
                raise OSError("request write made no progress")
            view = view[sent:]
        self._check_send(state)

    def _request(self, state, raw_body):
        self._connect(state)
        _, host, port, path = self._endpoint
        host_header = ("[" + host + "]") if ":" in host else host
        head = (f"POST {path} HTTP/1.1\r\nHost: {host_header}:{port}\r\n"
                f"Authorization: Bearer {self._key}\r\nContent-Type: application/json\r\n"
                f"Accept: application/json\r\nConnection: close\r\nContent-Length: {len(raw_body)}\r\n\r\n").encode("latin-1")
        state.result["requestWireStartedAtUtc"] = frozen._utc_now()
        state.result["upstreamAttempted"] = True
        self._send(state, head)
        self._send(state, raw_body)
        state.wire_complete = True
        state.result["requestWireCompleteAtUtc"] = frozen._utc_now()
        state.result["requestWireComplete"] = True
        with self._condition:
            self.metrics["upstreamPosts"] += 1
        state.reader = io.BufferedReader(_ReadSocket(state))

    def _line(self, state, limit=8192):
        frozen._remaining(state.accounting_deadline)
        line = state.reader.readline(limit + 1)
        if len(line) > limit or not line.endswith(b"\r\n"):
            raise ValueError("D_LOCAL_RESPONSE:invalid or truncated HTTP line")
        return line

    def _headers(self, state):
        line = self._line(state)
        match = re.fullmatch(rb"HTTP/1\.[01] ([0-9]{3})(?: [^\r\n]*)?\r\n", line)
        if not match or not 200 <= int(match[1]) <= 599:
            raise ValueError("D_LOCAL_RESPONSE:unsupported HTTP status")
        status = int(match[1])
        headers, total = {}, len(line)
        for _ in range(101):
            line = self._line(state)
            total += len(line)
            if total > 65536:
                raise ValueError("D_LOCAL_RESPONSE:header bytes limit")
            if line == b"\r\n":
                return status, headers
            name, sep, value = line[:-2].partition(b":")
            if not sep or not re.fullmatch(frozen._TOKEN, name) or any(b < 32 and b != 9 or b == 127 for b in value):
                raise ValueError("D_LOCAL_RESPONSE:invalid HTTP header")
            headers.setdefault(name.decode("ascii").lower(), []).append(value.strip().decode("latin-1"))
        raise ValueError("D_LOCAL_RESPONSE:header fields limit")

    def _append(self, state, block):
        available = self._max_body - len(state.body)
        keep = block[:available]
        state.body.extend(keep)
        with self._condition:
            self._buffered += len(keep)
            self.metrics["peakBufferedBodyBytes"] = max(self.metrics["peakBufferedBodyBytes"], self._buffered)
        if len(block) > available:
            raise ValueError("D_LOCAL_RESPONSE:response body limit")

    def _body_exact(self, state, size):
        while size:
            frozen._remaining(state.accounting_deadline)
            block = state.reader.read1(min(size, 65536, self._max_body - len(state.body) + 1))
            if not block:
                raise http.client.IncompleteRead(b"", size)
            self._append(state, block)
            size -= len(block)

    def _read_body(self, state, status, headers):
        lengths, encodings = headers.get("content-length", []), headers.get("transfer-encoding", [])
        if (len(lengths) > 1 or len(encodings) > 1 or lengths and encodings
                or lengths and not re.fullmatch(r"[0-9]{1,12}", lengths[0])
                or encodings and encodings != ["chunked"]):
            raise ValueError("D_LOCAL_RESPONSE:ambiguous response framing")
        if status in (204, 304):
            if encodings or lengths and int(lengths[0]) != 0:
                raise ValueError("D_LOCAL_RESPONSE:body framing on bodyless status")
            return
        if encodings:
            declarations = headers.get("trailer", [])
            declared = {s.strip().lower() for s in declarations[0].split(",")} if len(declarations) == 1 else set()
            if len(declarations) > 1 or declared & frozen._FORBIDDEN_TRAILERS:
                raise ValueError("D_LOCAL_RESPONSE:invalid trailer declaration")
            for _ in range(frozen.MAX_CHUNKS + 1):
                line = self._line(state, frozen.MAX_CHUNK_LINE_BYTES)
                match = frozen._CHUNK_LINE.fullmatch(line[:-2])
                if not match:
                    raise ValueError("D_LOCAL_RESPONSE:invalid chunk size")
                size = int(match[1], 16)
                if size == 0:
                    total, seen = 0, set()
                    for _ in range(frozen.MAX_TRAILER_FIELDS + 1):
                        line = self._line(state, frozen.MAX_CHUNK_LINE_BYTES)
                        total += len(line)
                        if total > frozen.MAX_TRAILER_BYTES:
                            raise ValueError("D_LOCAL_RESPONSE:trailer bytes limit")
                        if line == b"\r\n":
                            return
                        name, sep, value = line[:-2].partition(b":")
                        normalized = name.decode("ascii", errors="replace").lower()
                        if (not sep or not re.fullmatch(frozen._TOKEN, name) or normalized not in declared
                                or normalized in seen or normalized in frozen._FORBIDDEN_TRAILERS
                                or any(b < 32 and b != 9 or b == 127 for b in value)):
                            raise ValueError("D_LOCAL_RESPONSE:invalid trailer")
                        seen.add(normalized)
                    raise ValueError("D_LOCAL_RESPONSE:trailer fields limit")
                self._body_exact(state, size)
                if self._line(state, 2) != b"\r\n":
                    raise ValueError("D_LOCAL_RESPONSE:missing chunk CRLF")
            raise ValueError("D_LOCAL_RESPONSE:chunk count limit")
        if lengths:
            self._body_exact(state, int(lengths[0]))
        else:
            while True:
                block = state.reader.read1(min(65536, self._max_body - len(state.body) + 1))
                if not block:
                    return
                self._append(state, block)

    def _collect(self, state, raw_body):
        try:
            if state.validation_error is not None:
                raise state.validation_error
            self._request(state, raw_body)
            status, headers = self._headers(state)
            state.result["httpStatus"] = status
            state.result["responseHeaderAtUtc"] = frozen._utc_now()
            state.headers = dict(content_type=headers.get("content-type", ["application/json"])[0],
                                 content_encoding=headers.get("content-encoding", [None])[0])
            self._read_body(state, status, headers)
            frozen._remaining(state.accounting_deadline)
            if state.abort.is_set():
                raise OSError("accounting collector cancelled")
            state.result["responseComplete"] = True
            state.result["responseCompleteAtUtc"] = frozen._utc_now()
            parsed = frozen._strict_json(state.body)
            if not isinstance(parsed, dict):
                raise ValueError("D_LOCAL_RESPONSE:JSON object required")
            state.result["responseJson"] = parsed
            usage = parsed.get("usage")
            fields = ("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens")
            valid = isinstance(usage, dict) and all(type(usage.get(k)) is int and usage[k] >= 0 for k in fields)
            state.result["usageKnown"] = bool(valid and usage["prompt_tokens"] + usage["completion_tokens"] == usage["total_tokens"]
                                               and usage["prompt_cache_hit_tokens"] + usage["prompt_cache_miss_tokens"] == usage["prompt_tokens"])
            state.status = status
        except Exception as exc:
            prefix = "D_LOCAL_RESPONSE:" if isinstance(exc, frozen._DuplicateJSONKey) else ""
            if state.result["responseComplete"] and not prefix and isinstance(exc, (ValueError, UnicodeError, RecursionError)):
                prefix = "D_LOCAL_RESPONSE:"
            state.result["error"] = self._redact(prefix + f"{type(exc).__name__}:{exc}")
            state.status = 503 if str(exc).startswith("D_LOCAL_GATE:") else 504 if isinstance(exc, TimeoutError) else 502
        finally:
            self._close_socket(state)
            body = bytes(state.body)
            state.body.clear()
            state.result.update(responseBodyRaw=body, responseBytes=len(body),
                                responseBodySha256=hashlib.sha256(body).hexdigest(),
                                finishedAtUtc=frozen._utc_now())
            state.ready_at = time.monotonic()
            body_timely = state.result["responseComplete"] and state.ready_at < state.answer_deadline
            state.result.update(providerBodyTimely=body_timely,
                                bodyReadyAtUtc=frozen._utc_now(),
                                bodyReadyAtMonotonic=state.ready_at,
                                durableBeforeAnswer=self._durable_before_answer)
            state.done.set()
            if self._durable_before_answer:
                # This immutable callback snapshot precedes HTTP delivery. A
                # timely body remains timely if the local durable write is slow;
                # only the independent HTTP evidence can claim answer delivery.
                eligible = body_timely and state.result["error"] is None
                state.result.update(late=state.ready_at >= state.answer_deadline,
                                    accountingOnly=not eligible, answerEligible=eligible,
                                    answerReturnedAtUtc=None,
                                    eligibilityScope="PROVIDER_BODY_READINESS_ONLY; HTTP delivery is controller evidence")
            else:
                state.answer_decided.wait()
                state.result.update(late=state.answer_frozen,
                                    accountingOnly=state.answer_frozen,
                                    answerEligible=not state.answer_frozen and state.result["error"] is None,
                                    answerReturnedAtUtc=state.answer_returned,
                                    eligibilityScope="PROXY_ANSWER_DECISION_ONLY; not client receipt")
            state.result.update(lateSqlBackfill=False,
                                sameSentConnectionReadOnly=state.wire_complete,
                                reconnects=0, resends=0)
            try:
                with self._condition:
                    self.metrics["afterCalls"] += 1
                self._after_send(copy.deepcopy(state.permit), copy.deepcopy(state.result))
                state.after_succeeded = True
            except Exception as exc:
                state.after_error = self._redact(f"{type(exc).__name__}:{exc}")
                self._record_error("after_send", exc)
            finally:
                state.after_done.set()
                self._release(state)
                with self._condition:
                    self._collectors.discard(threading.current_thread())
                    self._condition.notify_all()

    def _forward(self, handler, payload, raw_body):
        try:
            state = self._reserve()
        except Exception as exc:
            handler._error(503, self._redact(exc))
            return
        try:
            permit = self._before_send(payload, raw_body)
        except Exception as exc:
            self._release(state)
            handler._error(503, "D_LOCAL_GATE:" + self._redact(exc))
            return
        state.permit = copy.deepcopy(permit)
        state.result = dict(startedAtUtc=frozen._utc_now(), httpStatus=None, responseJson=None,
                            responseComplete=False, upstreamAttempted=False, requestWireComplete=False,
                            requestWireStartedAtUtc=None, requestWireCompleteAtUtc=None,
                            usageKnown=False, error=None)
        try:
            self._validate(state, raw_body)
            answer_deadline = state.answer_deadline
        except Exception as exc:
            state.validation_error = ValueError("D_LOCAL_GATE:" + str(exc))
            answer_deadline = time.monotonic() + .05
        worker = threading.Thread(target=self._collect, args=(state, raw_body), name="accounting-collector", daemon=False)
        with self._condition:
            self._collectors.add(worker)
            self._all_threads.add(worker)
        try:
            worker.start()
        except Exception as exc:
            with self._condition:
                self._collectors.discard(worker)
                self._all_threads.discard(worker)
            state.result["error"] = "D_LOCAL_GATE:collector start failed"
            state.answer_decided.set()
            state.result.update(responseBodyRaw=b"", responseBytes=0,
                                responseBodySha256=hashlib.sha256(b"").hexdigest(),
                                finishedAtUtc=frozen._utc_now(), late=False,
                                accountingOnly=False, answerEligible=False)
            try:
                self.metrics["afterCalls"] += 1
                self._after_send(copy.deepcopy(state.permit), copy.deepcopy(state.result))
            except Exception as callback_error:
                self._record_error("after_send", callback_error)
            finally:
                self._release(state)
            self._record_answer_decision(state, 503, False)
            handler._error(503, "D_LOCAL_GATE:collector start failed")
            return
        try:
            state.done.wait(max(0, answer_deadline - time.monotonic()))
            if self._durable_before_answer and state.done.is_set():
                state.after_done.wait(max(0, answer_deadline - time.monotonic()))
            # A timely provider body is still ineligible while the durable
            # callback is outstanding.  The old check could wake from
            # ``after_done.wait`` at the deadline edge, observe
            # ``after_succeeded`` as false, and return 502 even though the
            # callback had not settled yet.  That is a transport timeout
            # with UNKNOWN accounting, so freeze it as 504 until the
            # callback event is durably observed.
            state.answer_frozen = (
                not state.done.is_set()
                or state.ready_at > answer_deadline
                or time.monotonic() >= answer_deadline
                or (self._durable_before_answer and not state.after_done.is_set())
            )
            state.answer_returned = frozen._utc_now()
            state.answer_decided.set()
            if state.answer_frozen:
                self._record_answer_decision(state, 504, True)
                handler.connection.settimeout(1)
                handler._error(504, "D_LOCAL_TRANSPORT:answer deadline expired; accounting only")
            elif self._durable_before_answer and not state.after_succeeded:
                self._record_answer_decision(state, 502, False)
                handler._error(502, "D_LOCAL_ACCOUNTING:durable settlement failed before answer")
            elif state.result["error"] is None:
                self._record_answer_decision(state, state.status, False)
                handler.connection.settimeout(max(.001, answer_deadline - time.monotonic()))
                handler._reply(state.status, state.result["responseBodyRaw"], **state.headers)
            else:
                self._record_answer_decision(state, state.status, False)
                handler._error(state.status, state.result["error"])
        finally:
            state.answer_decided.set()

    @staticmethod
    def _close_socket(state):
        if state.reader is not None:
            try:
                state.reader.close()
            except OSError:
                pass
        if state.raw is not None:
            try:
                state.raw.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            state.raw.close()

    def _abort_all(self):
        with self._condition:
            states = list(self._states)
        for state in states:
            state.abort.set()
            if state.raw is not None:
                try:
                    state.raw.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def drain(self, timeout_seconds=120):
        timeout = _number(timeout_seconds, "drain timeout")
        if timeout < 0:
            raise ValueError("negative drain timeout")
        requested_end = time.monotonic() + timeout
        with self._condition:
            while self._handlers or self._collectors:
                boundaries = [s.round_deadline for s in self._states if s.round_deadline]
                end = min([requested_end] + boundaries)
                remaining = end - time.monotonic()
                if remaining <= 0:
                    self._abort_all()
                    raise TimeoutError("accounting drain reached caller or absolute round deadline")
                self._condition.wait(min(remaining, .05))

    def __exit__(self, exc_type, exc, traceback):
        self._closing.set()
        if self._server is None:
            return False
        self._abort_all()
        with self._condition:
            clients = list(self._clients)
        for client in clients:
            try:
                client.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self._server.shutdown()
        self._server.server_close()
        self._server_thread.join(1)
        with self._condition:
            threads = list(self._all_threads)
            states = list(self._states)
        # Closing never grants a fresh accounting window. Wake all reads first;
        # the brief joins below wait only for bounded local callback cleanup.
        boundaries = [s.round_deadline for s in states if s.round_deadline]
        end = min([time.monotonic() + 1] + boundaries)
        for thread in threads:
            thread.join(max(0, end - time.monotonic()))
        live = [thread for thread in threads if thread.is_alive()]
        if live:
            failure = RuntimeError("accounting shutdown has unjoined bounded callback/handler")
            self._record_error("shutdown", failure)
            if exc_type is None:
                raise failure
        return False
