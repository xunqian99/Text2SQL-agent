"""Hash-only preservation: never parses sealed paid answers, SQL or credentials."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
BASE = ROOT / "reports/replan-stage-d-20261119"
USER_PATHS = ("docs/IMPROVEMENT_PLAN.md", "docs/LOCAL_DEMO.md", "docs/ROADMAP.md",
              "docs/STAGE2_V3_CONTROLLER.md", "scripts/run_stage2_v3_comparison.py",
              "scripts/test_stage2_v3_comparison.py",
              "docs/presentation/Text2SQL_Course_Design_Presentation.pptx",
              "docs/presentation/generate_presentation.py", "docs/presentation/index.html")
FROZEN = ("scripts/run_replan_stage_d.py", "scripts/replan_stage_d_proxy.py",
          "reports/replan-stage-a-20261119/ReferenceContractScorer.java",
          ".private/recovery/replan-c1-20261119/text2sql-agent-C1-submission-1.jar",
          ".private/recovery/p9-3c0bda0/text2sql-agent-p9.jar")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot():
    expected = {}
    for relative in ("paid-interruption-manifest.json", "reregistration-first-free-failure.json",
                     "reregistration-candidate/root-revision-3-free-verification/delivery-manifest.json",
                     "independent-review/reregistered-dev-1/revision-3/source-evidence-manifest.json"):
        manifest_path = BASE / relative
        expected.update(json.loads(manifest_path.read_bytes())["files"])
        expected[manifest_path.relative_to(ROOT).as_posix()] = sha(manifest_path)
    for suffix in ("batch-1/reregistered-dev-1/revision-2", "batch-1/reregistered-dev-1/revision-3"):
        expected.update({p.relative_to(ROOT).as_posix(): sha(p) for p in (BASE / suffix).rglob("*") if p.is_file()})
    decision = BASE / "independent-review/reregistered-dev-1/revision-3/decision.json"
    expected[decision.relative_to(ROOT).as_posix()] = sha(decision)
    assert all(sha(ROOT / relative) == identity for relative, identity in expected.items())
    for suffix in ("batch-1/revision-2", "batch-1/reregistered-dev-1", "batch-1/reregistered-dev-1/revision-2",
                   "batch-1/reregistered-dev-1/revision-3", "batch-1/reregistered-dev-1/revision-4"):
        assert not (BASE / suffix / "human-paid-authorization.json").exists()
    return dict(preservedEvidence=expected,
                userFiles={p: sha(ROOT / p) if (ROOT / p).is_file() else None for p in USER_PATHS},
                frozenFiles={p: sha(ROOT / p) for p in FROZEN}, actualHumanAuthorizationAbsent=True,
                providerCalls=0, scope="Hash-only, sealed paid details are not parsed")


if __name__ == "__main__":
    value = snapshot()
    name = sys.argv[1]
    if name != "protected-start.json":
        original = json.loads((OUT / "protected-start.json").read_bytes())
        assert value == original, "Protected bytes or user differences changed"
        result = dict(passed=True, preservedEvidenceFiles=len(value["preservedEvidence"]),
                      userPaths=9, frozenFiles=5, providerCalls=0, actualHumanAuthorizationAbsent=True)
    else:
        result = value
    with (OUT / name).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps(dict(passed=True, preservedEvidenceFiles=len(value["preservedEvidence"]),
                         userPaths=9, frozenFiles=5, providerCalls=0)))
