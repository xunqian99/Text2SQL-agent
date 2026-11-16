"""Offline identity, permissions, Gold self-check and one real HTTP query audit."""
import hashlib
import json
from pathlib import Path
import subprocess

folder = Path(__file__).resolve().parent
root = folder.parents[1]
read = lambda name: json.loads((folder / name).read_text(encoding="utf-8-sig"))
manifest = read("manifest.json")
for item in manifest["artifacts"]:
    assert hashlib.sha256((root / item["path"]).read_bytes()).hexdigest() == item["sha256"], item["path"]
for item in manifest["sources"]:
    assert hashlib.sha256(subprocess.check_output(["git", "show", item["ref"]], cwd=root)).hexdigest() == item["sha256"], item["ref"]
for item in manifest["trees"]:
    assert subprocess.check_output(["git", "rev-parse", item["ref"]], cwd=root, text=True).strip() == item["object"]
protocol = read("protocol.json")
assert protocol["container"] == "text2sql-clean-acceptance-20261116"
assert protocol["volume"] != "text2sql-pgdata" and protocol["address"] == "127.0.0.1"
assert len(protocol["inputs"]) == 15
# Raw CSV files are intentionally not archived; validate local inputs when present.
for item in protocol["inputs"]:
    source = root / item["path"]
    if source.exists():
        assert source.stat().st_size == item["bytes"]
        assert hashlib.sha256(source.read_bytes()).hexdigest() == item["sha256"]
database = read("database-summary.json")
assert database["databasePrepared"] and database["permissionsPassed"]
assert len(database["stages"]) == 6 and all(x["exitCode"] == 0 for x in database["stages"])
assert sum(x["elapsedMs"] for x in database["stages"]) == 25451
counts = read("table-counts.json")
assert len(counts) == 41 and sum(counts.values()) == 2616948
permissions = read("permissions.json")
assert permissions["businessTableCount"] == 37
assert permissions["roWritableBusinessTables"] == permissions["rwWritableBusinessTables"] == 0
assert not permissions["roCreateSchema"] and not permissions["rwCreateSchema"]
assert permissions["rwCanAppendLog"] and not permissions["rwCanUpdateLog"] and not permissions["rwCanDeleteLog"]
for role in ("text2sql_ro", "text2sql_rw"):
    for kind in ("update", "create"):
        assert "permission denied" in (folder / f"{role}-{kind}-denied.txt").read_text(encoding="utf-8-sig")
gold_files = list((folder / "gold").glob("*.json"))
assert len(gold_files) == 1
gold = json.loads(gold_files[0].read_text(encoding="utf-8-sig"))
assert gold["meta"]["dryRun"] and gold["meta"]["split"] == "all"
assert gold["overall"]["total"] == gold["overall"]["correct"] == 200
assert gold["overall"]["totalCostYuan"] == gold["overall"]["avgPromptTokens"] == 0
assert not gold["failures"] and len(gold["itemEvidence"]) == 200
health = read("health.json")
assert health["ready"] and health["mode"] == "demo"
connections = read("http-connections.json")
assert len(connections) == 5 and all(x["role"] == "text2sql_ro" and x["database"] == "olist" for x in connections)
response = read("http-query.json")
body = response["body"]
assert response["httpStatus"] == 200 and body["status"] == "SUCCESS"
assert body["rows"] == [[(folder / "order-count.txt").read_text(encoding="utf-8-sig").strip()]] == [["54011"]]
assert response["wallMs"] == 1418
assert body["budget"]["modelCalls"] == 1 and body["budget"]["unknownUsageCalls"] == 0
calls = [json.loads(line) for path in (folder / "llm-calls").glob("*.jsonl") for line in path.read_text(encoding="utf-8-sig").splitlines()]
assert len(calls) == 1 and calls[0]["usageKnown"] and calls[0]["status"] == "OK"
call = calls[0]
assert call["requestId"] == body["budget"]["requestId"] and call["callNumber"] == 1
assert (call["promptTokens"], call["completionTokens"], call["totalTokens"]) == (2151, 77, 2228)
assert call["promptTokens"] == call["cacheHitTokens"] + call["cacheMissTokens"]
price = call["cacheHitTokens"] / 1000 * 0.00002 + call["cacheMissTokens"] / 1000 * 0.001 + call["completionTokens"] / 1000 * 0.004
assert abs(price - call["costYuan"]) < 1e-12 and abs(price - 0.0005774) < 1e-12
assert abs(body["llm"]["costYuan"] - price) < 1e-12
assert read("session-close.json")["turnCount"] == 1 and not read("session-close.json")["persisted"]
shutdown = read("http-shutdown.json")
assert shutdown["listenerCount"] == 0 and not shutdown["javaProcessAlive"] and not shutdown["goldProcessAlive"]
stopped = read("database-stopped.json")
assert stopped["isolatedRunning"] == "false" and stopped["originalRunning"] == "true" and stopped["volumePreserved"]
ports = read("isolation.json")["isolatedPortBindings"]["5432/tcp"]
assert ports == [{"HostIp": "127.0.0.1", "HostPort": "15432"}]
print(f"Verified {len(manifest['artifacts'])} artifact hashes, 15 input identities, 37-table permissions, Gold 200/200 and real HTTP 1/1; known cost CNY {price:.7f}.")
