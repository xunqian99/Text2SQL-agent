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


def load_controller(name: str):
    sys.path.insert(0, str(ROOT / "scripts"))
    spec = importlib.util.spec_from_file_location(name, CONTROLLER)
    if spec is None or spec.loader is None:
        raise AssertionError("controller import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def refusal_without_credential(module) -> str:
    credential_reader_called = False

    def forbidden_credential_reader():
        nonlocal credential_reader_called
        credential_reader_called = True
        raise AssertionError("credential reader reached after scope tamper")

    module._old_real_key = forbidden_credential_reader
    try:
        module.guarded_real_key()
    except module.Refused as refused:
        refusal = str(refused)
    else:
        raise AssertionError("tampered paid scope was accepted")
    if credential_reader_called:
        raise AssertionError("credential reader was reached after scope tamper")
    return refusal


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

    module = load_controller("scope_lock_controller")
    refusal = refusal_without_credential(module)

    # Each mutation is isolated in a temporary copy.  None of these probes
    # changes the live decision, proposal, plan, quota, or batch evidence.
    import tempfile
    with tempfile.TemporaryDirectory(prefix="scope-lock-negative-") as directory:
        root = Path(directory)
        altered_decision = json.loads(DECISION.read_bytes())
        altered_decision["decision"] = "APPROVED_NEW_SCOPE"
        altered_decision["newPaidAuthorizationGranted"] = True
        altered_decision_path = root / "decision-approved.json"
        altered_decision_path.write_text(json.dumps(altered_decision, ensure_ascii=False), encoding="utf-8")
        altered_module = load_controller("scope_lock_decision_tamper")
        altered_module.SCOPE_DECISION = altered_decision_path
        decision_tamper_refusal = refusal_without_credential(altered_module)

        altered_bool = json.loads(DECISION.read_bytes())
        altered_bool["newPaidAuthorizationGranted"] = True
        altered_bool_path = root / "decision-bool.json"
        altered_bool_path.write_text(json.dumps(altered_bool, ensure_ascii=False), encoding="utf-8")
        bool_module = load_controller("scope_lock_bool_tamper")
        bool_module.SCOPE_DECISION = altered_bool_path
        bool_tamper_refusal = refusal_without_credential(bool_module)

        altered_proposal = json.loads(DECISION.read_bytes())
        altered_proposal["proposalSha256"] = "0" * 64
        altered_proposal_path = root / "decision-proposal-sha.json"
        altered_proposal_path.write_text(json.dumps(altered_proposal, ensure_ascii=False), encoding="utf-8")
        proposal_module = load_controller("scope_lock_proposal_sha_tamper")
        proposal_module.SCOPE_DECISION = altered_proposal_path
        proposal_tamper_refusal = refusal_without_credential(proposal_module)

        altered_plan_path = root / "plan.json"
        altered_plan_path.write_bytes(PLAN.read_bytes() + b"\n")
        plan_module = load_controller("scope_lock_source_plan_tamper")
        plan_module.d.BATCH = root
        plan_tamper_refusal = refusal_without_credential(plan_module)

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
        "credentialReaderCalled": False,
        "tamperProbes": {
            "decision": {"credentialReaderCalls": 0, "refusal": decision_tamper_refusal},
            "newPaidAuthorizationGranted": {"credentialReaderCalls": 0, "refusal": bool_tamper_refusal},
            "proposalSha256": {"credentialReaderCalls": 0, "refusal": proposal_tamper_refusal},
            "sourcePlanSha256": {"credentialReaderCalls": 0, "refusal": plan_tamper_refusal},
        },
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
