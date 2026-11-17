"""Offline evidence audit. No app startup, network calls, SQL execution or answer regrading."""
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
for entry in manifest["artifacts"]:
    assert hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest() == entry["sha256"], entry["path"]
for path, blob in manifest["sourceBlobs"].items():
    assert subprocess.check_output(["git", "cat-file", "-t", blob], cwd=root, text=True).strip() == "blob", path

totals = dict(tests=0, failures=0, errors=0, skipped=0, suites=0)
for path in (folder / "surefire").glob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    totals["suites"] += 1
    for key in ("tests", "failures", "errors", "skipped"):
        totals[key] += int(suite.get(key))
assert totals == dict(tests=729, failures=0, errors=0, skipped=0, suites=70), totals
assert read(folder / "java-tests.json") == totals
for name, count in (("EntityPolicyTest", 12), ("CustomerIdentityContractTest", 7)):
    suite = ET.parse(folder / "surefire" / f"TEST-com.text2sql.agent.semantic.{name}.xml").getroot()
    assert int(suite.get("tests")) == count and int(suite.get("skipped")) == 0
assert (folder / "java-tests-connected.exit").read_text(encoding="utf-8-sig").strip() == "0"

invariants = ["id", "tables", "retrievalTables", "scores", "expandedTables", "evidenceOrder", "columns",
              "ddlSha256", "valueHintsSha256", "examplesSha256"]
changed = []
for split in ("dev", "eval"):
    before = read(folder / f"before-{split}.json")
    after = read(folder / f"after-connected-{split}.json")
    for key in ("datasetSha256", "sampleIdsSha256", "schemaSha256", "systemPromptSha256"):
        assert before[key] == after[key], (split, key)
    assert before["itemCount"] == after["itemCount"] == 100
    assert before["sameProcessMismatches"] == after["sameProcessMismatches"] == []
    for a, b in zip(before["items"], after["items"]):
        for key in invariants:
            assert a[key] == b[key], (split, a["id"], key)
        if a["promptSha256"] != b["promptSha256"]:
            assert b["componentChars"]["metrics"] - a["componentChars"]["metrics"] in (548, 550)
            changed.append(a["id"])
assert len(changed) == 25 and "T4-001" in changed
gold = read(next((folder / "gold-connected").glob("eval-*.json")))
assert gold["overall"]["total"] == gold["overall"]["correct"] == gold["overall"]["sqlValid"] == 200
assert gold["overall"]["totalCostYuan"] == 0 and not gold["failures"]
assert read(folder / "credential-scan.json")["knownCredentialMatches"] == 0
print(json.dumps(dict(audit="PASS", javaTests="729/729", skipped=0, newTests=19,
                     unchangedRetrievalContexts=200, policyAddedToContexts=25,
                     gold="200/200", providerCalls=0, configuredModelCostYuan=0)))
