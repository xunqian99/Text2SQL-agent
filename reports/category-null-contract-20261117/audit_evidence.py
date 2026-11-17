"""Verify frozen category semantics, context scope and targeted real HTTP evidence."""
import hashlib
import json
from decimal import Decimal, InvalidOperation
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

folder = Path(__file__).resolve().parent
root = folder.parents[1]


def read(path):
    return json.loads((root / path).read_text(encoding="utf-8-sig"), parse_float=Decimal)


manifest = read("reports/category-null-contract-20261117/manifest.json")
for item in manifest["artifacts"]:
    assert hashlib.sha256((root / item["path"]).read_bytes()).hexdigest() == item["sha256"], item["path"]
for item in manifest["sources"]:
    data = subprocess.check_output(["git", "show", item["ref"]], cwd=root)
    assert hashlib.sha256(data).hexdigest() == item["sha256"], item["path"]

tests = dict(tests=0, failures=0, errors=0, skipped=0, suites=0)
for path in (folder / "surefire").glob("TEST-*.xml"):
    suite = ET.parse(path).getroot(); tests["suites"] += 1
    for key in ("tests", "failures", "errors", "skipped"):
        tests[key] += int(suite.attrib[key])
assert tests == read("reports/category-null-contract-20261117/java-tests.json")
assert tests == dict(tests=640, failures=0, errors=0, skipped=0, suites=65)

changed = []
invariant = ["id", "tables", "retrievalTables", "scores", "expandedTables", "evidenceOrder", "columns",
             "ddlSha256", "valueHintsSha256", "examplesSha256"]
for split in ("dev", "eval"):
    before = read(f"reports/category-null-contract-20261117/before-{split}.json")
    after = read(f"reports/category-null-contract-20261117/after-{split}.json")
    for key in ("datasetSha256", "sampleIdsSha256", "schemaSha256", "systemPromptSha256"):
        assert before[key] == after[key], (split, key)
    assert before["itemCount"] == after["itemCount"] == 100
    assert before["sameProcessMismatches"] == after["sameProcessMismatches"] == []
    for a, b in zip(before["items"], after["items"]):
        for key in invariant:
            assert a[key] == b[key], (split, a["id"], key)
        if a["promptSha256"] != b["promptSha256"]:
            assert a["metricsSha256"] != b["metricsSha256"]
            assert b["componentChars"]["metrics"] > a["componentChars"]["metrics"]
            changed.append(a["id"])
assert len(changed) == 15 and "T4-003" in changed

live = "reports/category-http-20261117/"
protocol = read(live + "protocol.json")
for path, identity in protocol["sourceTrees"].items():
    assert subprocess.check_output(["git", "rev-parse", protocol["sourceCommit"] + ":" + path], cwd=root, text=True).strip() == identity
assert [c["rowCount"] for c in protocol["cases"]] == [73, 74, 1]
assert hashlib.sha256(subprocess.check_output(["git", "show", manifest["frozenProtocolCommit"] + ":" + live + "protocol.json"], cwd=root)).hexdigest() == hashlib.sha256((root / live / "protocol.json").read_bytes()).hexdigest()


def normalize(rows):
    def cell(value):
        if value is None: return "NULL"
        value = str(value).strip()
        try: return format(Decimal(value).normalize(), "f")
        except InvalidOperation: return value
    return sorted(tuple(cell(v) for v in row) for row in rows)


for case in protocol["cases"]:
    response = read(live + "responses/" + case["id"] + ".json")
    assert response["httpStatus"] == 200
    body = response["body"]
    assert body["status"] == "SUCCESS" and not body["truncated"]
    assert normalize(body["rows"]) == normalize(case["expectedRows"])
    assert body["budget"]["modelCalls"] == 1 and body["budget"]["unknownUsageCalls"] == 0

summary = read(live + "summary.json")
assert summary["passed"] and summary["correct"] == summary["cases"] == summary["modelCalls"] == 3
assert summary["completeCost"] and summary["responseUnknownUsageCalls"] == 0
assert summary["knownConfiguredCostYuan"] == Decimal("0.00584928")
ledger = read(live + "cost-ledger.json")
assert ledger["knownCalls"] == 3 and not ledger["holds"] and ledger["knownCostYuan"] == Decimal("0.00584928")
records = [json.loads(line, parse_float=Decimal) for path in (root / live / "llm-calls").glob("*.jsonl") for line in path.read_text(encoding="utf-8-sig").splitlines() if line]
assert len(records) == 3
prices = read("reports/category-null-contract-20261117/price-audit.json")
assert prices["localConfigSha256"] == protocol["localConfigSha256"]
cost = Decimal(0)
for record in records:
    assert record["usageKnown"] and record["status"] == "OK"
    assert record["promptTokens"] == record["cacheHitTokens"] + record["cacheMissTokens"]
    assert record["totalTokens"] == record["promptTokens"] + record["completionTokens"]
    expected = (record["cacheHitTokens"] * Decimal(str(prices["cacheHitPer1kYuan"]))
                + record["cacheMissTokens"] * Decimal(str(prices["inputPer1kYuan"]))
                + record["completionTokens"] * Decimal(str(prices["outputPer1kYuan"]))) / 1000
    # Legacy call logs serialize a double; the new durable ledger uses exact BigDecimal.
    assert abs(record["costYuan"] - expected) <= Decimal("0.000000000001")
    cost += expected
    assert record["prompt"] is None and record["response"] is None
assert cost == summary["knownConfiguredCostYuan"]
assert sum(x["totalTokens"] for x in records) == 7772
assert len({(x["requestId"], x["callNumber"]) for x in records}) == 3
shutdown = read(live + "shutdown.json")
assert not shutdown["javaProcessAlive"] and shutdown["listenerCount"] == 0
assert read("reports/category-null-contract-20261117/credential-scan.json")["knownCredentialMatches"] == 0
print(f"Verified {len(manifest['artifacts'])} artifact hashes, Java640/640, 200 unchanged retrieval contexts, 15 policy additions and real targeted HTTP3/3; configured cost=0.00584928 yuan.")
