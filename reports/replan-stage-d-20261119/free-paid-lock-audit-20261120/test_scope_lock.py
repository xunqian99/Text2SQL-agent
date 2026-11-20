"""Verify the current declined scope stops before the credential reader.

This test imports the controller only. It never invokes its paid runner, opens a
socket, reads an environment/registry key, or touches the batch ledger.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CONTROLLER = ROOT / "scripts/run_replan_stage_d_reregistered.py"
DECISION = ROOT / "reports/replan-stage-d-20261119/post-interruption-feasibility-20261120/human-scope-decision.json"
PLAN = ROOT / "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4/plan.json"
QUOTA = ROOT / "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4/candidate-cost-quota.json"
STATE = ROOT / "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4/state.json"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(output: Path) -> None:
    decision = json.loads(DECISION.read_bytes())
    state = json.loads(STATE.read_bytes())
    quota = json.loads(QUOTA.read_bytes(), parse_float=float)
    if decision.get("decision") != "DECLINED_NO_ADDITIONAL_PAID_EVALUATION":
        raise AssertionError("scope decision is no longer the recorded decline")
    if decision.get("newPaidAuthorizationGranted") is not False:
        raise AssertionError("scope decision unexpectedly grants paid evaluation")
    if state.get("terminal") != "INTERRUPTED_NO_REPLAY":
        raise AssertionError("interrupted batch is not terminal")
    next_commitment = float(quota["knownCostYuan"]) + float(quota["reservationYuan"]) * (len(quota["holds"]) + 1)
    if next_commitment <= float(quota["limitYuan"]):
        raise AssertionError("next reservation unexpectedly fits the frozen limit")

    sys.path.insert(0, str(ROOT / "scripts"))
    spec = importlib.util.spec_from_file_location("scope_lock_controller", CONTROLLER)
    if spec is None or spec.loader is None:
        raise AssertionError("controller import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    credential_reader_called = False

    def forbidden_credential_reader():
        nonlocal credential_reader_called
        credential_reader_called = True
        raise AssertionError("credential reader reached after declined scope")

    module._old_real_key = forbidden_credential_reader
    try:
        module.guarded_real_key()
    except module.Refused as refused:
        refusal = str(refused)
    else:
        raise AssertionError("declined paid scope was accepted")
    if credential_reader_called:
        raise AssertionError("credential reader was reached")

    result = {
        "format": "replan-d-declined-paid-scope-lock-v1",
        "passed": True,
        "refusal": refusal,
        "scopeDecision": decision["decision"],
        "scopeDecisionSha256": sha(DECISION),
        "controllerSha256": sha(CONTROLLER),
        "boundPlanSha256": sha(PLAN),
        "candidateQuotaSha256": sha(QUOTA),
        "stateSha256": sha(STATE),
        "credentialReaderCalled": credential_reader_called,
        "providerCalls": 0,
        "keyReads": 0,
        "batchWrites": 0,
        "actualBatchReplay": False,
        "nextConfiguredCommitmentYuan": f"{next_commitment:.9f}",
        "frozenLimitYuan": f"{float(quota['limitYuan']):.2f}",
        "oldPlanStillBoundToPreLockSource": sha(CONTROLLER) != json.loads(PLAN.read_bytes())["files"].get("scripts/run_replan_stage_d_reregistered.py"),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_scope_lock.py OUTPUT.json")
    main(Path(sys.argv[1]).resolve())
