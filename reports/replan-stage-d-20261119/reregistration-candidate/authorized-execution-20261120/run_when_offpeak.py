"""Wait without credentials, then invoke the frozen paid controller exactly once."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "scripts"))
import run_replan_stage_d_reregistered as n

PLAN_SHA = "da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe"
AUTH_SHA = "4f37505d9850713a20a95200a13941abcde66f6cd6668925c64232cfd38ed65e"


def record(name, value):
    with (OUT / name).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def unchanged_pristine():
    assert n.sha(n.BATCH / "plan.json") == PLAN_SHA
    assert n.sha(n.BATCH / n.AUTH_NAME) == AUTH_SHA
    n.require_human_authorization()
    state = n.d.read(n.BATCH / "state.json")
    ledger = n.d.read(n.BATCH / "send-ledger.json")
    assert state["requestsStarted"] == 0 and state["rounds"] == {} and state["terminal"] is None
    assert not ledger["calls"] and not ledger["rejections"] and not ledger["breaches"]
    assert not (n.BATCH / "human-authorization-consumed.json").exists()


if __name__ == "__main__":
    unchanged_pristine()
    record("wait-started.json", dict(pid=os.getpid(), startedAt=n.d.stamp(), planSha256=PLAN_SHA,
           authorizationSha256=AUTH_SHA, waitingReadsCredentials=False, automaticPaidRetry=False))
    previous = None
    while True:
        unchanged_pristine()
        decision = n.d.window(n.d.now(), n.d.ROUND_GUARD)
        if decision["allowed"]:
            break
        marker = (decision["reason"], decision.get("nextAllowedAt"))
        if marker != previous:
            print(json.dumps(dict(status="WAITING_OFF_PEAK", window=decision, keyReads=0, providerCalls=0)), flush=True)
            previous = marker
        time.sleep(30)
    record("dispatch.json", dict(dispatchedAt=n.d.stamp(), window=decision, planSha256=PLAN_SHA,
           authorizationSha256=AUTH_SHA, singleInvocation=True,
           controllerRechecksWholeWindowAndEverySend=True))
    command = [sys.executable, "-u", str(ROOT / "scripts/run_replan_stage_d_reregistered.py"), "run-next"]
    with (OUT / "controller.stdout.log").open("xb") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(json.dumps(dict(status="CONTROLLER_STARTED", pid=process.pid, dispatchedAt=n.d.stamp())), flush=True)
        for raw in process.stdout:
            log.write(raw)
            log.flush()
            line = raw.decode("utf-8", errors="replace").strip()
            if "HTTP answers" in line or line.startswith("REFUSED:"):
                print(line, flush=True)
        code = process.wait()
    state = n.d.read(n.BATCH / "state.json")
    result = dict(finishedAt=n.d.stamp(), exitCode=code, requestsStarted=state["requestsStarted"],
                  terminal=state["terminal"], roundStatuses={k: v["status"] for k, v in state["rounds"].items()},
                  automaticPaidRetry=False)
    record("process-result.json", result)
    print(json.dumps(result), flush=True)
    sys.exit(code)
