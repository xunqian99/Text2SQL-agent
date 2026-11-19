"""Fixed, exclusive Windows loopback listener preserving the legacy quota identity.

Only listener construction changes. The frozen request handler, outbound
transport, permit/deadline checks, response parser and shutdown are inherited.
No address reuse, alternate port, key lookup or transport retry is introduced.
"""
from __future__ import annotations

import hashlib
from http.server import HTTPServer
import os
from pathlib import Path
import socket
import threading

import replan_stage_d_proxy as frozen

HOST = "127.0.0.1"
PORT = 59900
BASE_URL = "http://127.0.0.1:59900"
FROZEN_PROXY_SHA = "e3aa8b4c04d15f990a5a15a160e874d3a9edd4a74c45f90e1661172debf731f1"
if hashlib.sha256(Path(frozen.__file__).read_bytes()).hexdigest() != FROZEN_PROXY_SHA:
    raise RuntimeError("Frozen proxy source drift; fixed listener refused")
_OriginalModelProxy = frozen.ModelProxy


class StableServer(frozen._Server):
    allow_reuse_address = False
    allow_reuse_port = False

    def __init__(self, proxy):
        if os.name != "nt" or not hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            raise RuntimeError("Fixed exclusive listener is validated for Windows only")
        self.proxy = proxy
        HTTPServer.__init__(self, (HOST, PORT), frozen._Handler, bind_and_activate=False)
        try:
            # Bind failure is fatal. Never use SO_REUSEADDR to take over an owner.
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            if self.socket.getsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR) != 0:
                raise RuntimeError("Address reuse unexpectedly enabled")
            if self.socket.getsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE) != 1:
                raise RuntimeError("Exclusive address ownership unavailable")
            self.server_bind()
            self.server_activate()
        except BaseException:
            self.server_close()
            raise


class StableModelProxy(_OriginalModelProxy):
    def __enter__(self):
        if self._entered:
            raise RuntimeError("ModelProxy instances are single-use")
        self._entered = True
        self._server = StableServer(self)
        self.base_url = BASE_URL
        self._server_thread = threading.Thread(
            target=self._server.serve_forever, kwargs={"poll_interval": .05},
            name="stage-d-proxy-server", daemon=False)
        self._server_thread.start()
        return self
