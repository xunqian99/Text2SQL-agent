"""Read-only audit: frozen inputs, observed HTTP/session evidence, complete call slots and prices."""
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
folder = Path(__file__).resolve().parent
read = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
protocol = read(folder / "protocol.json")
summary = read(folder / "summary.json")
run = read(folder / "run-start.json")
assert hashlib.sha256((folder / "protocol.json").read_bytes()).hexdigest() == (folder / "protocol.sha256").read_text().strip()
assert subprocess.check_output(["git", "show", run["commit"] + ":reports/demo-http-20-20261116/protocol.json"], cwd=root) == (folder / "protocol.json").read_bytes()
for path, tree in protocol["sourceObjects"].items():
    assert subprocess.check_output(["git", "rev-parse", run["commit"] + ":" + path], cwd=root, text=True).strip() == tree
cases = protocol["cases"]
assert len(cases) == 20 and len({c["sourceId"] for c in cases}) == 18
original = {c["sourceId"] for c in cases if not c["followUp"]}
assert original == {f"T{layer}-{number:03}" for layer in range(1, 7) for number in range(1, 4)}
assert [c["sourceId"] for c in cases[:4]] == ["T1-002", "T6-001", "T1-002", "T6-001"]
assert cases[2]["question"] == cases[3]["question"]
for parent, child in ((cases[0], cases[2]), (cases[1], cases[3])):
    assert child["goldSql"] == parent["goldSql"].replace("2018-01-01", "2017-01-01").replace("2019-01-01", "2018-01-01")
    assert child["sessionId"] == parent["sessionId"]
records = [read(path) for path in sorted((folder / "requests").glob("*.json"))]
assert len(records) == 20
latencies, slots, ids, expected_counts, mismatches = [], {}, set(), {}, []
for index, (case, record) in enumerate(zip(cases, records), 1):
    assert record["index"] == index and record["caseId"] == case["id"]
    assert record["request"] == dict(question=case["question"], sessionId=case["sessionId"])
    body = record["exchange"]["body"]
    assert body["sessionId"] == case["sessionId"]
    budget = body["budget"]
    assert budget["requestId"] not in ids
    ids.add(budget["requestId"])
    for call in range(1, budget["modelCalls"] + 1):
        slots[(budget["requestId"], call)] = case["id"]
    assert record["healthAfter"]["httpStatus"] == 200
    expected_counts[case["sessionId"]] = expected_counts.get(case["sessionId"], 0) + 1
    actual = {s["sessionId"]: s["turnCount"] for s in record["sessionsAfter"]["body"]}
    assert set(actual) == set(expected_counts)
    if actual != expected_counts:
        mismatches.append(case["id"])
        assert actual["demo20-T5-002"] == 0 and expected_counts["demo20-T5-002"] == 1
        assert {k: v for k, v in actual.items() if k != "demo20-T5-002"} == {k: v for k, v in expected_counts.items() if k != "demo20-T5-002"}
    latencies.append(record["exchange"]["httpWallMs"])
assert sum(r["exchange"]["httpStatus"] == 200 and r["exchange"]["body"]["status"] == "SUCCESS" for r in records) == summary["httpSuccess"] == 18
assert sum(r["businessCorrect"] for r in records) == summary["businessCorrect"] == 17
assert sorted(latencies)[18] == summary["p95HttpMs"] == 10083
assert sum(latencies) / 20 == summary["meanHttpMs"] == 3217.15
assert max(latencies) == summary["maxHttpMs"] == 15014
assert summary["healthyAfterRequests"] == 20
assert mismatches == ["T5-002", "T5-003", "T6-002", "T6-003"]
logs = {}
for path in (folder / "llm-calls").glob("*.jsonl"):
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        call = json.loads(line)
        key = (call["requestId"], call["callNumber"])
        assert key not in logs and key in slots
        assert call["prompt"] is None and call["response"] is None
        assert call["model"] == "deepseek-flash" and call["configuredReasoningEffort"] == "provider-default"
        assert call["promptTokens"] == call["cacheHitTokens"] + call["cacheMissTokens"]
        assert call["totalTokens"] == call["promptTokens"] + call["completionTokens"]
        expected = Decimal(call["cacheHitTokens"]) * Decimal("0.00002") / 1000 + Decimal(call["cacheMissTokens"]) * Decimal("0.001") / 1000 + Decimal(call["completionTokens"]) * Decimal("0.004") / 1000
        assert abs(expected - Decimal(str(call["costYuan"]))) < Decimal("0.000000001")
        logs[key] = call
assert len(slots) == summary["modelCalls"] == 21
assert len(logs) == 20 and [slots[key] for key in slots.keys() - logs.keys()] == ["T5-002"]
assert abs(sum(Decimal(str(c["costYuan"])) for c in logs.values()) - Decimal("0.03040434")) < Decimal("0.000000001")
assert not summary["completeCost"] and summary["snapshotUnknownUsageCalls"] == summary["ledgerUnknownUsageSlots"] == 1
assert not summary["sessionIsolation"]["sessionCountsMatch"]
assert all(v for k, v in summary["sessionIsolation"].items() if k != "sessionCountsMatch")
assert read(folder / "sessions-after-cleanup.json")["body"] == []
assert read(folder / "health-after.json")["httpStatus"] == 200
print("Verified frozen 20 inputs, 18 original IDs, 20 responses, 21 call slots/20 known records, retained failures and session-count gap.")
