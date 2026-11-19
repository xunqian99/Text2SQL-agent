"""Stage D's single-attempt, gated model transport (Python standard library only).

This module never resolves keys, reads environment proxies, writes evidence, or
retries requests. The controller owns question identity, the off-peak window,
reservation accounting and durable evidence through the two callbacks. A permit
must contain a unique ``token`` and an absolute ``upstreamDeadlineMonotonic``.
``upstreamAttempted`` means transport was attempted, not provider receipt.
"""
from __future__ import annotations

import hashlib
import http.client
import io
import ipaddress
import json
import math
import re
import socket
import ssl
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Callable
from urllib.parse import urlsplit


OFFICIAL_URL = "https://api.deepseek.com/v1/chat/completions"
MODEL_PATH = "/v1/chat/completions"
MAX_BODY_BYTES = 1024 * 1024
LOCAL_BODY_TIMEOUT_SECONDS = 15
MAX_CHUNK_LINE_BYTES = 1024
MAX_CHUNKS = 4096
MAX_TRAILER_BYTES = 8192
MAX_TRAILER_FIELDS = 32
_TOKEN = rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+"
_QUOTED = rb'"(?:[\t\x20-\x21\x23-\x5b\x5d-\x7e\x80-\xff]|\\[\t\x20-\x7e\x80-\xff])*"'
_CHUNK_LINE = re.compile(rb"([0-9A-Fa-f]+)(?:[ \t]*;[ \t]*" + _TOKEN
                         + rb"(?:[ \t]*=[ \t]*(?:" + _TOKEN + rb"|" + _QUOTED + rb"))?)*")
_FORBIDDEN_TRAILERS = frozenset({
    "transfer-encoding", "content-length", "host", "connection", "trailer", "te",
    "upgrade", "keep-alive", "proxy-connection", "authorization", "proxy-authorization",
    "www-authenticate", "proxy-authenticate", "authentication-info", "proxy-authentication-info",
    "content-type", "content-encoding", "content-range", "range", "expect", "cookie",
    "set-cookie", "cache-control", "max-forwards", "if-match", "if-none-match",
    "if-modified-since", "if-unmodified-since", "if-range",
})


class _DuplicateJSONKey(ValueError):
    pass


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            # Do not echo model/user-controlled member names into evidence.
            raise _DuplicateJSONKey("duplicate JSON object member")
        value[key] = item
    return value


def _strict_json(raw_body):
    # object_pairs_hook applies recursively, including objects inside arrays and
    # escaped spellings of the same member name. Never accept last-key-wins.
    return json.loads(raw_body, object_pairs_hook=_unique_object)


class _BodyError(ValueError):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


class _LocalRequestReader(io.RawIOBase):
    """Enforce one body deadline at each recv, even within buffered readline."""

    def __init__(self, handler):
        super().__init__()
        self.handler = handler

    def readable(self):
        return True

    def readinto(self, buffer):
        handler = self.handler
        handler.connection.settimeout(handler._body_remaining())
        return handler.connection.recv_into(buffer)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _remaining(deadline: float) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("absolute upstream deadline expired")
    return remaining


class _DeadlineReader(io.RawIOBase):
    """Reset to the *remaining* budget at every recv, including header reads."""

    def __init__(self, wrapped: "_DeadlineSocket"):
        super().__init__()
        self._wrapped = wrapped
        self._released = False
        wrapped._readers += 1

    def readable(self):
        return True

    def readinto(self, buffer):
        wrapped = self._wrapped
        wrapped.raw.settimeout(_remaining(wrapped.deadline))
        return wrapped.raw.recv_into(buffer)

    def close(self):
        if not self._released:
            self._released = True
            self._wrapped._readers -= 1
            self._wrapped._close_if_unused()
        super().close()


class _DeadlineSocket:
    def __init__(self, raw: socket.socket, deadline: float, check_send):
        self.raw = raw
        self.deadline = deadline
        self._readers = 0
        self._closed = False
        self._check_send = check_send

    def sendall(self, data):
        self._check_send()
        self.raw.settimeout(_remaining(self.deadline))
        self.raw.sendall(data)

    def makefile(self, mode, buffering=None):
        if mode != "rb":
            raise ValueError("transport only supports binary response reads")
        return io.BufferedReader(_DeadlineReader(self))

    def close(self):
        # HTTPConnection may close immediately after headers when the provider
        # says Connection: close. Its response reader must retain the socket,
        # matching socket.makefile's reference-counted lifetime.
        self._closed = True
        self._close_if_unused()

    def _close_if_unused(self):
        if self._closed and not self._readers:
            self.raw.close()

    def shutdown(self, how):
        self.raw.shutdown(how)


def _connect_socket(host: str, port: int, deadline: float, check_send) -> socket.socket:
    # One address, one connection attempt. DNS itself is an OS call; if it takes
    # the remaining budget, the post-resolution check prevents any TCP send.
    check_send()
    _remaining(deadline)
    addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    check_send()
    _remaining(deadline)
    if not addresses:
        raise OSError("upstream address unavailable")
    family, kind, proto, _, address = addresses[0]
    raw = socket.socket(family, kind, proto)
    try:
        raw.settimeout(_remaining(deadline))
        raw.connect(address)
        _remaining(deadline)
        return raw
    except BaseException:
        raw.close()
        raise


class _HTTPConnection(http.client.HTTPConnection):
    def __init__(self, host, port, deadline, check_send):
        super().__init__(host, port, timeout=_remaining(deadline))
        self.deadline = deadline
        self._check_send = check_send

    def connect(self):
        self.sock = _DeadlineSocket(_connect_socket(self.host, self.port, self.deadline, self._check_send),
                                    self.deadline, self._check_send)


class _HTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, host, port, deadline, check_send):
        super().__init__(host, port, timeout=_remaining(deadline), context=ssl.create_default_context())
        self.deadline = deadline
        self._check_send = check_send

    def connect(self):
        raw = _connect_socket(self.host, self.port, self.deadline, self._check_send)
        try:
            raw.settimeout(_remaining(self.deadline))
            secured = self._context.wrap_socket(raw, server_hostname=self.host)
            _remaining(self.deadline)
        except BaseException:
            raw.close()
            raise
        self.sock = _DeadlineSocket(secured, self.deadline, self._check_send)


class _Server(HTTPServer):
    allow_reuse_address = False

    def __init__(self, proxy):
        self.proxy = proxy
        super().__init__(("127.0.0.1", 0), _Handler)

    def process_request(self, request, client_address):
        proxy = self.proxy
        with proxy._condition:
            if proxy._closing.is_set():
                self.shutdown_request(request)
                return
            thread = threading.Thread(target=self._run_handler,
                                      args=(request, client_address),
                                      name="stage-d-proxy-handler", daemon=False)
            proxy._handlers.add(thread)
            proxy._all_threads.add(thread)
            proxy._clients.add(request)
            try:
                thread.start()
            except BaseException:
                proxy._handlers.remove(thread)
                proxy._all_threads.remove(thread)
                proxy._clients.remove(request)
                self.shutdown_request(request)
                raise

    def _run_handler(self, request, client_address):
        proxy = self.proxy
        try:
            request.settimeout(15)
            self.finish_request(request, client_address)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            # A local client's disconnect cannot invalidate upstream evidence.
            pass
        except Exception as exc:
            proxy._record_error("handler", exc)
        finally:
            self.shutdown_request(request)
            with proxy._condition:
                proxy._clients.discard(request)
                proxy._handlers.discard(threading.current_thread())
                proxy._condition.notify_all()

    def handle_error(self, request, client_address):
        # BaseServer's default prints request-related tracebacks to stderr.
        self.proxy._record_error("server", RuntimeError("request handling failed"))


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "StageDLocalProxy"
    sys_version = ""

    def setup(self):
        super().setup()
        self._body_deadline = None
        self.rfile.close()
        self.rfile = io.BufferedReader(_LocalRequestReader(self))

    def _start_body_deadline(self):
        if self._body_deadline is None:
            self._body_deadline = time.monotonic() + LOCAL_BODY_TIMEOUT_SECONDS

    def _body_remaining(self):
        if self._body_deadline is None:
            return LOCAL_BODY_TIMEOUT_SECONDS
        remaining = self._body_deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("absolute local body deadline expired")
        return remaining

    def log_message(self, format, *args):
        pass

    def _reply(self, status, body, content_type="application/json", content_encoding=None):
        self.close_connection = True
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            if content_encoding:
                self.send_header("Content-Encoding", content_encoding)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, TimeoutError, OSError):
            pass

    def _error(self, status, message):
        safe_message = self.server.proxy._redact(message)
        self._reply(status, json.dumps({"error": {"message": safe_message}},
                                     ensure_ascii=False).encode("utf-8"))

    def handle_expect_100(self):
        # Do not invite an unbounded body before validating its framing.
        if not self._valid_request_headers():
            return False
        self._start_body_deadline()
        self.send_response_only(100)
        self.end_headers()
        return True

    def _valid_request_headers(self):
        if self.command != "POST":
            self._error(405, "D_LOCAL_REQUEST:only POST is accepted")
            return False
        if self.path != MODEL_PATH:
            self._error(404, "D_LOCAL_REQUEST:unknown path")
            return False
        encodings = self.headers.get_all("Transfer-Encoding", [])
        lengths = self.headers.get_all("Content-Length", [])
        if encodings:
            if lengths:
                self._error(400, "D_LOCAL_REQUEST:Transfer-Encoding conflicts with Content-Length")
                return False
            if len(encodings) != 1 or encodings[0].strip().lower() != "chunked":
                self._error(400, "D_LOCAL_REQUEST:one chunked transfer encoding is required")
                return False
            if self.request_version != "HTTP/1.1":
                self._error(400, "D_LOCAL_REQUEST:chunked requires HTTP/1.1")
                return False
            try:
                self._declared_trailers = self._trailer_declarations()
            except _BodyError as exc:
                self._error(exc.status, "D_LOCAL_REQUEST:" + str(exc))
                return False
            self._chunked_body = True
            return True
        if self.headers.get_all("Trailer", []):
            self._error(400, "D_LOCAL_REQUEST:trailers require chunked transfer encoding")
            return False
        if not lengths:
            self._error(411, "D_LOCAL_REQUEST:Content-Length or chunked transfer encoding is required")
            return False
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]+", lengths[0]):
            self._error(400, "D_LOCAL_REQUEST:invalid Content-Length")
            return False
        # Check the digit count first; Python also limits conversion length.
        if len(lengths[0]) > 7 or int(lengths[0]) > MAX_BODY_BYTES:
            self._error(413, "D_LOCAL_REQUEST:body exceeds 1 MiB")
            return False
        self._chunked_body = False
        return True

    def _trailer_declarations(self):
        headers = self.headers.get_all("Trailer", [])
        if not headers:
            return set()
        if len(headers) != 1 or len(headers[0]) > MAX_TRAILER_BYTES:
            raise _BodyError(400, "invalid trailer declaration")
        names = [part.strip().lower() for part in headers[0].split(",")]
        connection_names = {name.strip().lower() for value in self.headers.get_all("Connection", [])
                            for name in value.split(",")}
        forbidden = _FORBIDDEN_TRAILERS | connection_names
        if (len(names) > MAX_TRAILER_FIELDS or len(names) != len(set(names))
                or any(not re.fullmatch(_TOKEN, name.encode("latin-1")) or name in forbidden for name in names)):
            raise _BodyError(400, "forbidden or invalid trailer declaration")
        return set(names)

    def _body_read_exact(self, size):
        self._body_remaining()
        block = self.rfile.read(size)
        if len(block) != size:
            raise _BodyError(400, "incomplete request body")
        return block

    def _body_read_line(self, limit):
        self._body_remaining()
        line = self.rfile.readline(limit + 1)
        if len(line) > limit or not line.endswith(b"\r\n"):
            raise _BodyError(400, "invalid or overlong chunk/trailer line")
        return line

    def _read_trailers(self):
        total, fields, seen = 0, 0, set()
        while True:
            line = self._body_read_line(MAX_CHUNK_LINE_BYTES)
            total += len(line)
            if total > MAX_TRAILER_BYTES:
                raise _BodyError(400, "trailer section exceeds limit")
            if line == b"\r\n":
                return
            fields += 1
            if fields > MAX_TRAILER_FIELDS:
                raise _BodyError(400, "too many trailer fields")
            name, separator, value = line[:-2].partition(b":")
            if (not separator or not re.fullmatch(_TOKEN, name)
                    or any(byte < 32 and byte != 9 or byte == 127 for byte in value)):
                raise _BodyError(400, "invalid trailer field")
            normalized = name.decode("ascii").lower()
            if normalized not in self._declared_trailers or normalized in seen:
                raise _BodyError(400, "undeclared or duplicate trailer field")
            seen.add(normalized)

    def _read_chunked_body(self):
        decoded = bytearray()
        chunks = 0
        while True:
            line = self._body_read_line(MAX_CHUNK_LINE_BYTES)
            matched = _CHUNK_LINE.fullmatch(line[:-2])
            if matched is None:
                raise _BodyError(400, "invalid chunk size or extension")
            size = int(matched[1], 16)
            if size == 0:
                self._read_trailers()
                return bytes(decoded)
            chunks += 1
            if chunks > MAX_CHUNKS:
                raise _BodyError(400, "too many body chunks")
            if size > MAX_BODY_BYTES - len(decoded):
                raise _BodyError(413, "decoded body exceeds 1 MiB")
            decoded.extend(self._body_read_exact(size))
            if self._body_read_exact(2) != b"\r\n":
                raise _BodyError(400, "missing chunk data CRLF")

    def do_POST(self):
        self._start_body_deadline()
        if not self._valid_request_headers():
            return
        try:
            raw_body = (self._read_chunked_body() if self._chunked_body
                        else self._body_read_exact(int(self.headers["Content-Length"])))
            payload = _strict_json(raw_body)
        except _BodyError as exc:
            self._error(exc.status, "D_LOCAL_REQUEST:" + str(exc))
            return
        except TimeoutError:
            self._error(408, "D_LOCAL_REQUEST:absolute local body deadline expired")
            return
        except _DuplicateJSONKey:
            self._error(400, "D_LOCAL_REQUEST:duplicate JSON object member")
            return
        except (ValueError, UnicodeError, RecursionError):
            self._error(400, "D_LOCAL_REQUEST:invalid JSON")
            return
        if not isinstance(payload, dict):
            self._error(400, "D_LOCAL_REQUEST:JSON object is required")
            return
        if payload.get("stream", False) is not False:
            self._error(400, "D_LOCAL_REQUEST:stream is unsupported")
            return
        self.server.proxy._forward(self, payload, raw_body)

    def _reject_method(self):
        self._error(405, "D_LOCAL_REQUEST:only POST is accepted")

    do_GET = do_HEAD = do_PUT = do_PATCH = do_DELETE = do_OPTIONS = do_TRACE = do_CONNECT = _reject_method


class ModelProxy:
    """Context-managed local HTTP endpoint with controller-owned send permits.

    ``before_send(payload, raw_body)`` runs immediately before the one possible
    upstream attempt. Its exception returns 503/D_LOCAL_GATE and no attempt.
    Once it returns a permit, ``after_send(permit, result)`` runs exactly once,
    even for an invalid/expired permit, cancellation or a disconnected client.
    Callback failures are redacted and exposed in ``errors``; callers must check
    that list after ``drain``. ``result["responseBodyRaw"]`` contains immutable
    bytes, including partial or empty responses; the controller must persist
    those bytes before replacing them with a reference for JSON evidence.
    Callbacks must be bounded local operations.
    """

    def __init__(self, key: str, before_send: Callable, after_send: Callable,
                 upstream_url=OFFICIAL_URL, allow_loopback_test=False):
        if not isinstance(key, str) or not key or "\r" in key or "\n" in key:
            raise ValueError("a nonempty, single-line in-memory key is required")
        if not callable(before_send) or not callable(after_send):
            raise TypeError("both controller callbacks are required")
        self._endpoint = self._validate_endpoint(upstream_url, allow_loopback_test)
        self._key = key
        self._before_send = before_send
        self._after_send = after_send
        self._condition = threading.Condition()
        self._send_lock = threading.Lock()
        self._closing = threading.Event()
        self._handlers = set()
        self._all_threads = set()
        self._clients = set()
        self._connections = set()
        self._tokens = set()
        self._server = None
        self._server_thread = None
        self._entered = False
        self.base_url = None
        self.errors = []

    @staticmethod
    def _validate_endpoint(url, allow_loopback_test):
        if url == OFFICIAL_URL:
            return ("https", "api.deepseek.com", 443, MODEL_PATH)
        try:
            parsed = urlsplit(url)
            host = parsed.hostname
            loopback = host is not None and ipaddress.ip_address(host).is_loopback
            valid = (allow_loopback_test is True and loopback and parsed.scheme == "http"
                     and parsed.path == MODEL_PATH and parsed.port is not None
                     and not parsed.query and not parsed.fragment
                     and parsed.username is None and parsed.password is None)
            if valid:
                return ("http", host, parsed.port, MODEL_PATH)
        except (ValueError, TypeError):
            pass
        raise ValueError("only the exact official endpoint or an explicitly enabled loopback HTTP test is allowed")

    def _redact(self, message):
        return str(message).replace(self._key, "[REDACTED]")[:2000]

    def _record_error(self, source, exc):
        with self._condition:
            self.errors.append(self._redact(f"{source}:{type(exc).__name__}:{exc}"))

    def _check_send_open(self):
        if self._closing.is_set():
            raise ValueError("D_LOCAL_GATE:proxy is closing")

    def __enter__(self):
        if self._entered:
            raise RuntimeError("ModelProxy instances are single-use")
        self._entered = True
        self._server = _Server(self)
        self.base_url = f"http://127.0.0.1:{self._server.server_port}"
        self._server_thread = threading.Thread(
            target=self._server.serve_forever, kwargs={"poll_interval": .05},
            name="stage-d-proxy-server", daemon=False)
        self._server_thread.start()
        return self

    def drain(self, timeout_seconds=120):
        """Wait for all accepted handlers and callbacks, or raise TimeoutError."""
        if not isinstance(timeout_seconds, (int, float)) or not math.isfinite(timeout_seconds) or timeout_seconds < 0:
            raise ValueError("drain timeout must be a finite nonnegative number")
        deadline = time.monotonic() + timeout_seconds
        with self._condition:
            while self._handlers:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(f"proxy drain timed out with {len(self._handlers)} active handler(s)")
                self._condition.wait(min(remaining, 1))
        return None

    def _validate_permit(self, permit):
        if not isinstance(permit, dict):
            raise ValueError("permit must be an object")
        token = permit.get("token")
        if not isinstance(token, str) or not token:
            raise ValueError("permit token is required")
        deadline = permit.get("upstreamDeadlineMonotonic")
        if isinstance(deadline, bool) or not isinstance(deadline, (int, float)) or not math.isfinite(deadline):
            raise ValueError("permit requires a finite absolute monotonic deadline")
        with self._condition:
            if token in self._tokens:
                raise ValueError("permit token was already used")
            self._tokens.add(token)
        _remaining(deadline)
        return deadline

    def _forward(self, handler, payload, raw_body):
        if self._closing.is_set():
            handler._error(503, "D_LOCAL_GATE:proxy is closing")
            return
        try:
            permit = self._before_send(payload, raw_body)
        except Exception as exc:
            handler._error(503, "D_LOCAL_GATE:" + self._redact(f"{type(exc).__name__}:{exc}"))
            return

        started = _utc_now()
        result = dict(startedAtUtc=started, finishedAtUtc=None, httpStatus=None,
                      responseJson=None, responseBodySha256=None, responseBytes=0,
                      error=None, upstreamAttempted=False, responseComplete=False)
        response_body = bytearray()
        connection = None
        response = None
        local_status = 502
        response_headers = {}
        try:
            try:
                deadline = self._validate_permit(permit)
            except (ValueError, TimeoutError) as exc:
                local_status = 503
                raise ValueError("D_LOCAL_GATE:" + str(exc)) from None
            scheme, host, port, path = self._endpoint
            connection_type = _HTTPSConnection if scheme == "https" else _HTTPConnection
            connection = connection_type(host, port, deadline, self._check_send_open)
            with self._condition:
                self._connections.add(connection)
            # Serialize request construction. Closing is checked independently
            # here and at every connect/send, including after OS DNS returns.
            # before_send never holds this lock.
            with self._send_lock:
                if self._closing.is_set():
                    local_status = 503
                    raise ValueError("D_LOCAL_GATE:proxy is closing")
                try:
                    _remaining(deadline)
                except TimeoutError:
                    local_status = 503
                    raise ValueError("D_LOCAL_GATE:absolute upstream deadline expired") from None
                result["upstreamAttempted"] = True
                connection.request("POST", path, body=raw_body, headers={
                    "Authorization": "Bearer " + self._key,
                    "Content-Type": "application/json",
                    "Accept": "application/json",
                    "Connection": "close",
                })
            response = connection.getresponse()
            result["httpStatus"] = response.status
            response_headers = {
                "content_type": response.getheader("Content-Type", "application/json"),
                "content_encoding": response.getheader("Content-Encoding"),
            }
            while True:
                _remaining(deadline)
                block = response.read1(65536)
                if not block:
                    if response.length not in (None, 0):
                        raise http.client.IncompleteRead(b"", response.length)
                    break
                response_body.extend(block)
            result["responseComplete"] = True
        except Exception as exc:
            result["error"] = self._redact(f"{type(exc).__name__}:{exc}")
            if str(exc).startswith("D_LOCAL_GATE:"):
                local_status = 503
            elif isinstance(exc, TimeoutError):
                if result["upstreamAttempted"]:
                    local_status = 504
                else:
                    local_status = 503
                    result["error"] = "D_LOCAL_GATE:" + result["error"]
        finally:
            if response is not None:
                response.close()
            if connection is not None:
                connection.close()
                with self._condition:
                    self._connections.discard(connection)
            body = bytes(response_body)
            result["finishedAtUtc"] = _utc_now()
            result["responseBodyRaw"] = body
            result["responseBodySha256"] = hashlib.sha256(body).hexdigest()
            result["responseBytes"] = len(body)
            try:
                result["responseJson"] = _strict_json(body)
            except _DuplicateJSONKey:
                duplicate_error = "D_LOCAL_RESPONSE:duplicate JSON object member; usage is unknown"
                result["error"] = (result["error"] + "; " + duplicate_error
                                   if result["error"] else duplicate_error)
            except (ValueError, UnicodeError, RecursionError):
                pass
            try:
                self._after_send(permit, result)
            except Exception as exc:
                self._record_error("after_send", exc)

        if result["responseComplete"]:
            handler._reply(result["httpStatus"], body, **response_headers)
        else:
            handler._error(local_status, result["error"] or "D_LOCAL_TRANSPORT:incomplete response")

    def __exit__(self, exc_type, exc, traceback):
        # Prevent an unsent callback from resuming into a request after shutdown.
        # Setting this never waits behind DNS/connect. Both the gate boundary
        # and every future connect/send recheck it before any upstream bytes.
        self._closing.set()
        if self._server is None:
            return False
        self._server.shutdown()
        self._server.server_close()
        self._server_thread.join(timeout=5)
        shutdown_error = None
        try:
            self.drain(timeout_seconds=120)
        except TimeoutError as failure:
            shutdown_error = failure
            with self._condition:
                clients = list(self._clients)
                connections = list(self._connections)
            for client in clients:
                try:
                    client.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                client.close()
            for connection in connections:
                try:
                    if connection.sock is not None:
                        connection.sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
        with self._condition:
            threads = list(self._all_threads)
        join_deadline = time.monotonic() + 5
        for thread in threads:
            thread.join(timeout=max(0, join_deadline - time.monotonic()))
        if self._server_thread.is_alive() or any(thread.is_alive() for thread in threads):
            shutdown_error = RuntimeError("proxy shutdown could not join a bounded local callback")
        if shutdown_error is not None:
            self._record_error("shutdown", shutdown_error)
            if exc_type is None:
                raise shutdown_error
        return False
