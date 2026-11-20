"""Hash-only preservation checks; no model/key/database/answer parsing."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
BASE = ROOT / "reports/replan-stage-d-20261119"
USER_PATHS = (
    "docs/IMPROVEMENT_PLAN.md", "docs/LOCAL_DEMO.md", "docs/ROADMAP.md",
    "docs/STAGE2_V3_CONTROLLER.md", "scripts/run_stage2_v3_comparison.py",
    "scripts/test_stage2_v3_comparison.py",
    "docs/presentation/Text2SQL_Course_Design_Presentation.pptx",
    "docs/presentation/generate_presentation.py", "docs/presentation/index.html",
)
FROZEN = (
    "scripts/run_replan_stage_d.py", "scripts/replan_stage_d_proxy.py",
    "reports/replan-stage-a-20261119/ReferenceContractScorer.java",
    ".private/recovery/replan-c1-20261119/text2sql-agent-C1-submission-1.jar",
    ".private/recovery/p9-3c0bda0/text2sql-agent-p9.jar",
)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, value):
    with (OUT / name).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def current():
    user = {relative: sha(ROOT / relative) if (ROOT / relative).is_file() else None for relative in USER_PATHS}
    frozen = {relative: sha(ROOT / relative) for relative in FROZEN}
    paid = json.loads((BASE / "paid-interruption-manifest.json").read_bytes())["files"]
    first_free = json.loads((BASE / "reregistration-first-free-failure.json").read_bytes())["files"]
    preserved = {**paid, **first_free}
    for directory in (BASE / "batch-1/reregistered-dev-1/revision-2",
                      BASE / "independent-review/reregistered-dev-1/revision-2"):
        for path in directory.rglob("*"):
            if path.is_file():
                preserved[path.relative_to(ROOT).as_posix()] = sha(path)
    assert all(sha(ROOT / relative) == expected for relative, expected in preserved.items())
    actual_batches = [BASE / "batch-1/revision-2", BASE / "batch-1/reregistered-dev-1",
                      BASE / "batch-1/reregistered-dev-1/revision-2",
                      BASE / "batch-1/reregistered-dev-1/revision-3"]
    assert all(not (batch / "human-paid-authorization.json").exists() for batch in actual_batches)
    return dict(userFiles=user, frozenFiles=frozen, preservedEvidence=preserved,
                paidManifestFiles=len(paid), firstFreeManifestFiles=len(first_free),
                actualAdditionalPaidAuthorizationAbsent=True, providerCalls=0,
                scope="Hash-only evidence preservation; never parse paid answers, SQL or secrets")


if __name__ == "__main__":
    value = current()
    if sys.argv[1] == "start":
        write("protected-start.json", value)
    else:
        initial = json.loads((OUT / "protected-start.json").read_bytes())
        assert value == initial, "Protected bytes or user differences changed"
        write(sys.argv[1], dict(passed=True, userPathsVerified=len(USER_PATHS),
                               frozenFilesVerified=len(FROZEN), preservedEvidenceFiles=len(value["preservedEvidence"]),
                               actualAdditionalPaidAuthorizationAbsent=True, providerCalls=0))
    print(json.dumps(dict(passed=True, userPaths=len(USER_PATHS), frozenFiles=len(FROZEN),
                          preservedEvidenceFiles=len(value["preservedEvidence"]), providerCalls=0)))
