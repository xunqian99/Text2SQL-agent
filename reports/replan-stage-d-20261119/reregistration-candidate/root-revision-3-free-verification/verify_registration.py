"""Free registered-candidate checks; direct paid entry stays blocked."""
from __future__ import annotations

import json
from pathlib import Path
import socket
import sys
import time
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "scripts"))
import run_replan_stage_d_reregistered as n


def write(name, value):
    with (OUT / name).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    plan_path = n.BATCH / "plan.json"
    plan = n.d.read(plan_path)
    preflight = n.d.read(n.BATCH / "preflight.json")
    assert preflight["passed"] is True and preflight["planSha256"] == n.sha(plan_path)
    assert all(n.sha(ROOT / relative) == expected for relative, expected in preflight["evidence"].items())
    timings = []
    with patch.object(n, "_old_real_key", side_effect=AssertionError("Real key forbidden in free registration verification")), \
         patch.object(socket.socket, "connect", side_effect=AssertionError("Network forbidden in free registration verification")):
        for _ in range(3):
            began = time.perf_counter()
            n.verify(plan, database=False)
            timings.append(round((time.perf_counter() - began) * 1000, 3))
        before = {p.name: n.sha(p) for p in n.BATCH.iterdir() if p.is_file()}
        try:
            n.run_next()
        except n.Refused as error:
            refusal = str(error)
        else:
            raise AssertionError("Missing human authorization reached paid path")
        after = {p.name: n.sha(p) for p in n.BATCH.iterdir() if p.is_file()}
    assert before == after
    state = n.d.read(n.BATCH / "state.json")
    ledger = n.d.read(n.BATCH / "send-ledger.json")
    assert state["requestsStarted"] == 0 and state["rounds"] == {} and state["terminal"] is None
    assert not ledger["calls"] and not ledger["rejections"] and not ledger["breaches"]
    assert not (n.BATCH / n.AUTH_NAME).exists() and not (n.BATCH / "human-authorization-consumed.json").exists()
    assert n.sha(n.BATCH / "candidate-cost-quota.json") == n.sha(n.BATCH / "candidate-quota-initial-snapshot.json")
    result = dict(passed=True, planSha256=n.sha(plan_path), boundFiles=len(plan["files"]),
                  runtimeFiles=sum("/tool-runtime/" in relative for relative in plan["files"]),
                  preflightFiles=len(preflight["evidence"]), fullPerQuestionVerifyMs=timings,
                  verifyIncludesFullLegacyAndBoundFileHashes=True, databaseRecheck=False,
                  preflightAlreadyRecheckedDatabase=True, noPaidAuthorizationRefusal=refusal,
                  paidEntryAttemptLeftTopLevelBytesUnchanged=True,
                  actualPaidState=state, actualCalls=0, actualHumanAuthorizationAbsent=True,
                  realKeyReads=0, networkCalls=0, providerCalls=0, providerCostYuan="0")
    write("registration-verification.json", result)
    print(json.dumps({k: v for k, v in result.items() if k != "actualPaidState"}, ensure_ascii=False))
