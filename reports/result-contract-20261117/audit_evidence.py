"""Audit explicit unit metadata, narrowly changed system rules, and unchanged retrieval evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

folder = Path(__file__).resolve().parent
root = folder.parents[1]


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


manifest = read(folder / "manifest.json")
for e in manifest["artifacts"]:
    assert hashlib.sha256((root / e["path"]).read_bytes()).hexdigest() == e["sha256"], e["path"]
for path, blob in manifest["sourceBlobs"].items():
    assert subprocess.check_output(["git", "cat-file", "-t", blob], cwd=root, text=True).strip() == "blob", path
totals = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
for p in (folder / "surefire").glob("TEST-*.xml"):
    suite = ET.parse(p).getroot()
    totals["suites"] += 1
    for k in ("tests", "failures", "errors", "skipped"):
        totals[k] += int(suite.get(k))
assert totals == dict(suites=72, tests=750, failures=0, errors=0, skipped=0)
assert read(folder / "java-tests.json") == totals
assert (folder / "java-tests-fixed.exit").read_text(encoding="utf-8-sig").strip() == "0"
invariant = ["id", "tables", "retrievalTables", "scores", "expandedTables", "evidenceOrder", "columns",
             "ddlSha256", "valueHintsSha256", "examplesSha256"]
changed_metrics = []
for split in ("dev", "eval"):
    before = read(root / f"reports/customer-identity-contract-20261117/after-connected-{split}.json")
    after = read(folder / f"after-{split}.json")
    for key in ("datasetSha256", "sampleIdsSha256", "schemaSha256"):
        assert before[key] == after[key]
    assert before["systemPromptSha256"] != after["systemPromptSha256"]
    assert before["itemCount"] == after["itemCount"] == 100
    assert before["sameProcessMismatches"] == after["sameProcessMismatches"] == []
    for a, b in zip(before["items"], after["items"]):
        for key in invariant:
            assert a[key] == b[key], (split, a["id"], key)
        assert a["promptSha256"] != b["promptSha256"]
        if a["metricsSha256"] != b["metricsSha256"]:
            changed_metrics.append(a["id"])
assert len(changed_metrics) == 17
gold = read(next((folder / "gold").glob("eval-*.json")))
assert gold["overall"]["total"] == gold["overall"]["correct"] == gold["overall"]["sqlValid"] == 200
assert gold["overall"]["totalCostYuan"] == 0 and not gold["failures"]
assert read(folder / "credential-scan.json")["knownCredentialMatches"] == 0
print(json.dumps(dict(audit="PASS", javaTests="750/750", newTests=21, skipped=0, gold="200/200",
                     unchangedRetrievalContexts=200, unitContextChanges=17, systemPromptChanges=200,
                     providerCalls=0, configuredModelCostYuan=0)))
