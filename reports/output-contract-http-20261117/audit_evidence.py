"""Read-only audit of six preregistered output contract requests and all model costs."""
from datetime import datetime, timezone, timedelta
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import subprocess

folder = Path(__file__).resolve().parent
root = folder.parents[1]


def read(name):
    return json.loads((folder / name).read_text(encoding="utf-8-sig"), parse_float=Decimal)


manifest = read("manifest.json")
for item in manifest["artifacts"]:
    assert hashlib.sha256((root / item["path"]).read_bytes()).hexdigest() == item["sha256"], item["path"]
protocol = read("protocol.json")
for path, tree in protocol["sourceTrees"].items():
    assert subprocess.check_output(["git", "rev-parse", protocol["sourceCommit"] + ":" + path], cwd=root, text=True).strip() == tree
assert subprocess.check_output(["git", "show", manifest["protocolCommit"] + ":reports/output-contract-http-20261117/protocol.json"], cwd=root) == (folder / "protocol.json").read_bytes()
assert subprocess.run(["git", "merge-base", "--is-ancestor", protocol["sourceCommit"], manifest["protocolCommit"]], cwd=root).returncode == 0
summary = read("summary.json")
assert summary["cases"] == summary["correct"] == summary["modelCalls"] == 6
assert summary["contextClosed"] and summary["responseUnknownUsageCalls"] == 0
assert (folder / "run.exit").read_text(encoding="utf-8-sig").strip() == "0"
calls = [json.loads(line, parse_float=Decimal) for path in (folder / "llm-calls").glob("*.jsonl")
         for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
slots = {(c["requestId"], c["callNumber"]): c for c in calls}
assert len(calls) == len(slots) == 6
prices = read("official-rule-check.json")["flashOffPeakYuanPerMillion"]
cost = Decimal(0)
tokens = dict(prompt=0, completion=0, total=0, cacheHit=0, cacheMiss=0)
actual = {}
for case, outcome in zip(protocol["cases"], summary["outcomes"]):
    response = read("responses/" + case["id"] + ".json")
    body = response["body"]
    assert case["id"] == outcome["id"] and outcome["correct"]
    assert response["httpStatus"] == 200 and body["status"] == "SUCCESS" and not body["truncated"]
    assert body["budget"]["modelCalls"] == 1 and body["budget"]["unknownUsageCalls"] == 0
    rows = sorted([[str(v) if v is not None else None for v in row] for row in body["rows"]])
    # All six references contain only numbers. Decimal removes representational trailing zeros.
    assert sorted([[Decimal(v) for v in r] for r in rows]) == sorted(
        [[Decimal(v) for v in r] for r in case["expectedRows"]]), case["id"]
    actual[case["id"]] = rows
    record = slots[(body["budget"]["requestId"], 1)]
    assert record["usageKnown"] and record["status"] == "OK"
    assert record["prompt"] is None and record["response"] is None
    assert record["configuredPromptVersion"] == "p10-result-contract-v10"
    assert record["configuredMetricDefinitionsSha256"] == protocol["metricDefinitionsSha256"]
    assert record["totalTokens"] == record["promptTokens"] + record["completionTokens"]
    assert record["promptTokens"] == record["cacheHitTokens"] + record["cacheMissTokens"]
    amount = sum(record[field] * Decimal(prices[key]) for field, key in (
        ("cacheHitTokens", "cacheHit"), ("cacheMissTokens", "cacheMiss"), ("completionTokens", "completion"))) / 1_000_000
    assert abs(amount - record["costYuan"]) < Decimal("0.000000000001")
    cost += amount
    for name, field in (("prompt", "promptTokens"), ("completion", "completionTokens"), ("total", "totalTokens"),
                        ("cacheHit", "cacheHitTokens"), ("cacheMiss", "cacheMissTokens")):
        tokens[name] += record[field]
    decision = read(case["id"] + "-offpeak.json")
    assert decision["allowed"] and decision["reason"] == "OFF_PEAK"
    stamp = datetime.fromisoformat(record["timestamp"]).replace(tzinfo=timezone(timedelta(hours=8))).timestamp()
    assert 0 <= Decimal(str(stamp)) - decision["checkedAt"] < 30
assert actual["original-repeat-percent"] == [["3.04"]]
assert actual["explicit-repeat-decimal"] == [["0.0304"]]
assert actual["original-year-rows"] == [["2017", "45101"], ["2018", "54011"]]
assert actual["explicit-year-columns"] == [["45101", "54011"]]
ledger = read("cost-status.json")
assert cost == summary["knownConfiguredCostYuan"] == ledger["knownCostYuan"]
assert ledger["knownCalls"] == 6 and ledger["unknownCalls"] == ledger["pendingCalls"] == 0
shutdown = read("shutdown.json")
assert not shutdown["javaProcessAlive"] and shutdown["listenerCount"] == 0
assert read("credential-scan.json")["knownCredentialMatches"] == 0
print(json.dumps(dict(audit="PASS", strictMatches="6/6", modelCalls=6, tokens=tokens,
                     configuredCostYuan=str(cost), unknownCalls=0, sourceCommit=protocol["sourceCommit"])))
