"""Audit the single frozen run, including its retained tie mismatch. Never sends requests."""
from decimal import Decimal
from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path
import subprocess

folder = Path(__file__).resolve().parent
root = folder.parents[1]


def read(name):
    return json.loads((folder / name).read_text(encoding="utf-8-sig"), parse_float=Decimal)


manifest = read("manifest.json")
for e in manifest["artifacts"]:
    assert hashlib.sha256((root / e["path"]).read_bytes()).hexdigest() == e["sha256"], e["path"]
protocol = read("protocol.json")
for path, tree in protocol["sourceTrees"].items():
    assert subprocess.check_output(["git", "rev-parse", protocol["sourceCommit"] + ":" + path], cwd=root, text=True).strip() == tree
frozen = subprocess.check_output(["git", "show", "830b0a9:reports/customer-identity-http-20261117/protocol.json"], cwd=root)
assert frozen == (folder / "protocol.json").read_bytes()
summary = read("summary.json")
assert summary["cases"] == 4 and summary["correct"] == 3 and summary["modelCalls"] == 4
assert summary["responseUnknownUsageCalls"] == 0 and summary["contextClosed"]
assert (folder / "run.exit").read_text(encoding="utf-8-sig").strip() == "1"  # mismatch makes caller fail
calls = [json.loads(line, parse_float=Decimal) for f in (folder / "llm-calls").glob("*.jsonl")
         for line in f.read_text(encoding="utf-8-sig").splitlines() if line]
assert len(calls) == 4
slots = {(r["requestId"], r["callNumber"]): r for r in calls}
assert len(slots) == 4
prices = read("official-rule-check.json")["flashOffPeakYuanPerMillion"]
total = Decimal(0)
tokens = dict(prompt=0, completion=0, total=0, cacheHit=0, cacheMiss=0)
for case, outcome in zip(protocol["cases"], summary["outcomes"]):
    response = read("responses/" + case["id"] + ".json")
    assert response["httpStatus"] == 200 and response["body"]["status"] == "SUCCESS"
    body = response["body"]
    assert not body["truncated"] and body["budget"]["modelCalls"] == 1 and body["budget"]["unknownUsageCalls"] == 0
    record = slots[(body["budget"]["requestId"], 1)]
    assert record["usageKnown"] and record["status"] == "OK" and record["prompt"] is None and record["response"] is None
    assert record["configuredMetricDefinitionsSha256"] == protocol["metricDefinitionsSha256"]
    assert record["promptTokens"] == record["cacheHitTokens"] + record["cacheMissTokens"]
    assert record["totalTokens"] == record["promptTokens"] + record["completionTokens"]
    cost = (record["cacheHitTokens"] * Decimal(prices["cacheHit"]) + record["cacheMissTokens"] * Decimal(prices["cacheMiss"])
            + record["completionTokens"] * Decimal(prices["completion"])) / 1_000_000
    assert abs(cost - record["costYuan"]) < Decimal("0.000000000001")
    total += cost
    for name, key in (("prompt", "promptTokens"), ("completion", "completionTokens"), ("total", "totalTokens"),
                      ("cacheHit", "cacheHitTokens"), ("cacheMiss", "cacheMissTokens")):
        tokens[name] += record[key]
    decision = read(case["id"] + "-offpeak.json")
    assert decision["allowed"] and decision["reason"] == "OFF_PEAK"
    assert decision["beijingTime"][11:13] == "20"
    # LlmCallRecord uses server LocalDateTime; app log confirms +08:00 for this run.
    call_time = datetime.fromisoformat(record["timestamp"]).replace(tzinfo=timezone(timedelta(hours=8))).timestamp()
    assert 0 <= Decimal(str(call_time)) - decision["checkedAt"] < 30
ledger = read("cost-status.json")
assert total == summary["knownConfiguredCostYuan"] == ledger["knownCostYuan"] == Decimal("0.00661856")
assert ledger["knownCalls"] == 4 and ledger["unknownCalls"] == ledger["pendingCalls"] == 0
assert [o["correct"] for o in summary["outcomes"]] == [False, True, True, True]
witness = read("tie-boundary-witness.json")
assert witness["maxOrders"] == 17 and witness["peopleAboveBoundary"] == 5 and witness["peopleAtBoundary"] == 6
assert witness["exactMatchStillFalse"]
actual = read("responses/original-customer-top10.json")["body"]["rows"]
expected = protocol["cases"][0]["expectedRows"]
for identity, count in actual + expected:
    assert witness["selectedCounts"][identity] == int(count)
for identity in witness["actualIdsOnly"] + witness["goldIdsOnly"]:
    assert witness["selectedCounts"][identity] == 6
assert sorted(int(r[1]) for r in actual) == sorted(int(r[1]) for r in expected)
shutdown = read("shutdown.json")
assert not shutdown["javaProcessAlive"] and shutdown["listenerCount"] == 0
assert read("credential-scan.json")["knownCredentialMatches"] == 0
print(json.dumps(dict(audit="PASS", strictMatches="3/4", httpSuccess="4/4", boundaryTieStillMismatch=True,
                     tokens=tokens, configuredCostYuan=str(total), unknownCalls=0, sourceCommit=protocol["sourceCommit"])))
