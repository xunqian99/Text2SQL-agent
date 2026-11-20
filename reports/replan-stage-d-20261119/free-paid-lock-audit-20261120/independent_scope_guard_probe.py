"""Safe residual probe for the paid scope lock.

This probe never calls the paid runner, starts an application, opens a socket,
or resolves a real credential. It supplies a temporary synthetic scope file
and replaces the inherited key reader with a sentinel. The live batch is only
hashed before and after; no ledger, state, quota, answer, or unseen file is
parsed or written.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
CONTROLLER = ROOT / "scripts/run_replan_stage_d_reregistered.py"
DECISION = ROOT / "reports/replan-stage-d-20261119/post-interruption-feasibility-20261120/human-scope-decision.json"
BATCH = ROOT / "reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4"
OBSERVED = tuple(BATCH / name for name in (
    "plan.json", "state.json", "candidate-cost-quota.json",
    "human-paid-authorization.json", "human-authorization-consumed.json",
))


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot() -> dict[str, str]:
    return {path.name: sha(path) for path in OBSERVED}


def main(output: Path) -> None:
    before = snapshot()
    with tempfile.TemporaryDirectory(prefix="scope-lock-probe-") as directory:
        synthetic = json.loads(DECISION.read_bytes())
        synthetic["decision"] = "APPROVED_NEW_SCOPE"
        synthetic["newPaidAuthorizationGranted"] = True
        synthetic_path = Path(directory) / "human-scope-decision.json"
        synthetic_path.write_text(json.dumps(synthetic, ensure_ascii=False), encoding="utf-8")

        sys.path.insert(0, str(ROOT / "scripts"))
        spec = importlib.util.spec_from_file_location("scope_lock_probe_controller", CONTROLLER)
        if spec is None or spec.loader is None:
            raise AssertionError("controller import spec unavailable")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.SCOPE_DECISION = synthetic_path
        credential_reads = 0

        def synthetic_key_reader() -> str:
            nonlocal credential_reads
            credential_reads += 1
            return "SYNTHETIC_SENTINEL; no provider credential"

        module._old_real_key = synthetic_key_reader
        value = module.guarded_real_key()

    after = snapshot()
    result = {
        "format": "replan-d-scope-lock-residual-probe-v1",
        "passed": value == "SYNTHETIC_SENTINEL; no provider credential",
        "syntheticScopeDecision": "APPROVED_NEW_SCOPE",
        "syntheticNewPaidAuthorizationGranted": True,
        "credentialReaderCalls": credential_reads,
        "realProviderCalls": 0,
        "socketCalls": 0,
        "paidRunnerCalled": False,
        "liveBatchChanged": before != after,
        "residual": "guard accepts mutable APPROVED_NEW_SCOPE JSON without a pinned decision/proposal hash",
        "before": before,
        "after": after,
        "interpretation": "The current DECLINED file still stops the real path; this isolated result shows why a mutable-file-only approval check is not a complete future fail-closed boundary.",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: independent_scope_guard_probe.py OUTPUT.json")
    main(Path(sys.argv[1]).resolve())
