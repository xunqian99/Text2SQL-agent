"""Offline audit: source/byte identity and real HTTP outcome/history invariants."""
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
    data = subprocess.check_output(["git", "show", item["ref"]], cwd=root)
    assert hashlib.sha256(data).hexdigest() == item["sha256"], item["ref"]
for item in manifest["trees"]:
    actual = subprocess.check_output(["git", "rev-parse", item["ref"]], cwd=root, text=True).strip()
    assert actual == item["object"], item["ref"]

summary = read("summary.json")
assert summary["passed"] and summary["scenarios"] == 3
assert summary["syntheticModelCalls"] == 4
assert summary["externalProviderCalls"] == summary["providerCostYuan"] == 0
normal = read("normal.json")
assert normal["httpStatus"] == 200 and normal["body"]["rows"] == [["54011"]]
for name, status, http, calls in [
    ("generation-timeout", "REQUEST_TIMEOUT", 504, 1),
    ("correction-timeout", "SUCCESS", 200, 2),
]:
    before, after = read(name + "-before.json"), read(name + "-after.json")
    response = before["response"]
    body = response["body"]
    assert response["httpStatus"] == http and body["status"] == status
    assert 14900 <= response["wallMs"] <= 18000
    assert body["budget"]["timeoutMs"] == 15000
    assert body["budget"]["termination"] == "DEADLINE_EXCEEDED"
    assert body["budget"]["modelCalls"] == calls
    assert body["budget"]["unknownUsageCalls"] == 1
    history = before["historyBeforeLateAnswer"]
    assert len(history) == 1 and history[0]["status"] == status and history[0]["sql"] == body["sql"]
    assert after["historyAfterLateAnswer"] == history and after["workerActiveCount"] == 0
    assert before["hasSchemaBeforeLateAnswer"] == after["hasSchemaAfterLateAnswer"] == (status == "SUCCESS")
    for key, payload in [("sessionsBeforeLateAnswer", before), ("sessionsAfterLateAnswer", after)]:
        assert payload[key]["httpStatus"] == 200
        sessions = {s["sessionId"]: s for s in payload[key]["body"]}
        assert sessions[name]["turnCount"] == 1
        assert sessions["probe-good"]["turnCount"] == 1
    if status == "SUCCESS":
        assert body["rows"] == normal["body"]["rows"]
shutdown = read("shutdown.json")
assert shutdown["listenerCount"] == 0 and not shutdown["probeProcessAlive"]
assert read("../session-outcome-fix-20261116/java-tests.json")["tests"] == 566
print(f"Verified {len(manifest['artifacts'])} artifact hashes, source identity, 3 HTTP scenarios, unchanged histories, and shutdown.")
