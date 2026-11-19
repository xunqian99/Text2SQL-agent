"""Candidate local I/O repair; deliberately not wired into the stopped D batch.

Retry only the same atomic filesystem replace for Windows sharing/access errors.
This module has no HTTP, model, key, batch, or question replay capability.
"""
from __future__ import annotations

import os
from pathlib import Path
import time


def replace_with_retry(source: Path, destination: Path, *, max_wait_seconds=0.5,
                       max_attempts=12, clock=time.monotonic, sleep=time.sleep,
                       replace=None):
    if not 0 < max_wait_seconds <= 1 or type(max_attempts) is not int or not 1 <= max_attempts <= 20:
        raise ValueError("Local replace retry must be strictly bounded")
    replace = os.replace if replace is None else replace
    started=clock();deadline=started+max_wait_seconds;attempts=0;delay=.01
    while True:
        attempts+=1
        try:
            replace(source,destination)
            return dict(attempts=attempts,elapsedSeconds=clock()-started)
        except OSError as error:
            remaining=deadline-clock()
            # Error 5 can also be a permanent ACL failure: never assume it is
            # transient, and preserve the source temp file if the bound expires.
            if getattr(error,"winerror",None) not in (5,32,33) or attempts>=max_attempts or remaining<=0:
                raise
            sleep(min(delay,remaining));delay=min(delay*2,.1)
            if clock()>=deadline: raise
