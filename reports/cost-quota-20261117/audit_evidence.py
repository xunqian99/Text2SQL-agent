"""Verify frozen monetary-admission evidence without calling a model or opening the database."""
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

folder = Path(__file__).resolve().parent
root = folder.parents[1]


def read(path):
    return json.loads((root / path).read_text(encoding="utf-8-sig"), parse_float=Decimal)


manifest = read("reports/cost-quota-20261117/manifest.json")
for item in manifest["artifacts"]:
    assert hashlib.sha256((root / item["path"]).read_bytes()).hexdigest() == item["sha256"], item["path"]
for item in manifest["sources"]:
    blob = subprocess.check_output(["git", "show", item["ref"]], cwd=root)
    assert hashlib.sha256(blob).hexdigest() == item["sha256"], item["path"]
for item in manifest["trees"]:
    assert subprocess.check_output(["git", "rev-parse", item["ref"]], cwd=root, text=True).strip() == item["object"]

tests = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "suites": 0}
for xml in (folder / "surefire").glob("TEST-*.xml"):
    suite = ET.parse(xml).getroot()
    tests["suites"] += 1
    for key in ("tests", "failures", "errors", "skipped"):
        tests[key] += int(suite.attrib[key])
assert tests == read("reports/cost-quota-20261117/java-tests.json")
assert tests == {"tests": 625, "failures": 0, "errors": 0, "skipped": 0, "suites": 63}
cost_cases = []
for name in ("CostQuotaTest", "CostQuotaGenerationTest"):
    suite = ET.parse(folder / "surefire" / f"TEST-com.text2sql.agent.cost.{name}.xml").getroot()
    cost_cases.extend(case.attrib["name"] for case in suite.findall("testcase"))
assert len(cost_cases) == 20 and len(set(cost_cases)) == 20

cost = "reports/cost-http-20261117-v2/"
summary = read(cost + "summary.json")
assert summary["passed"] and summary["scenarios"] == 4 and summary["contextsClosed"]
assert summary["syntheticModelCalls"] == 3
assert summary["externalProviderCalls"] == summary["providerCostYuan"] == 0
children = [read(cost + phase + "-summary.json") for phase in ("initial", "restart", "correction")]
assert len(set(summary["pids"])) == 3
assert [x["pid"] for x in children] == summary["pids"]
assert [x["port"] for x in children] == summary["ports"]
assert all(x["passed"] and x["contextsClosed"] for x in children)
assert sum(x["syntheticModelCalls"] for x in children) == 3

before = read(cost + "status-before-restart.json")["body"]
after = read(cost + "status-after-restart.json")["body"]
assert before == after
assert before["knownCostYuan"] == Decimal("0.02")
assert before["heldYuan"] == Decimal("0.10") and before["committedYuan"] == Decimal("0.12")
assert before["unknownCalls"] == 1 and before["pendingCalls"] == 0
assert not before["canAdmitCall"] and before["ledgerAvailable"]
assert before["meaning"] == "durable-configured-price-admission-not-provider-billing-cap"
ledger = read(cost + "ledger.json")
assert ledger["budgetId"] == before["budgetId"] and len(ledger["holds"]) == 1
hold = next(iter(ledger["holds"].values()))
assert hold["status"] == "UNKNOWN" and hold["requestId"] and len(hold["promptSha256"]) == 64
assert read(cost + "known-response.json")["body"]["rows"] == [["54011"]]
unknown = read(cost + "unknown-response.json")
assert unknown["httpStatus"] == 200 and unknown["body"]["budget"]["unknownUsageCalls"] == 1
assert read(cost + "unauthorized.json")["httpStatus"] == 401
for name, reason in (("quota-rejected", "COST_QUOTA_EXHAUSTED"), ("restart-rejected", "COST_QUOTA_EXHAUSTED"),
                     ("byok-rejected", "BYOK_DISABLED_BY_COST_QUOTA")):
    response = read(cost + name + ".json")
    assert response["httpStatus"] == 503
    budget = response["body"]["budget"]
    assert budget["termination"] == reason and budget["modelCalls"] == budget["unknownUsageCalls"] == 0
fallback = read(cost + "correction-fallback.json")
assert fallback["httpStatus"] == 200 and fallback["body"]["status"] == "SUCCESS"
assert fallback["body"]["rows"] == [["54011"]]
assert fallback["body"]["budget"]["termination"] == "COST_QUOTA_EXHAUSTED"
assert fallback["body"]["budget"]["modelCalls"] == 1
assert fallback["body"]["budget"]["unknownUsageCalls"] == 0
shutdown = read(cost + "shutdown.json")
assert not any(x["alive"] for x in shutdown["pids"])
assert all(x["listenerCount"] == 0 for x in shutdown["ports"])

auth = "reports/access-http-cost-regression-20261117/"
auth_summary = read(auth + "summary.json")
assert auth_summary["passed"] and auth_summary["checks"] == 36 and auth_summary["contextsClosed"]
assert auth_summary["syntheticModelCalls"] == 2 and auth_summary["externalProviderCalls"] == auth_summary["providerCostYuan"] == 0
requests = read(auth + "requests.json")
assert len(requests) == 36
lookup = {item["name"]: item for item in requests}
assert lookup["effective-limits"]["body"]["costQuotaEnforced"] is False
assert lookup["same-origin-ask"]["body"]["rows"] == [["54011"]]
assert lookup["concurrency-refusal"]["httpStatus"] == lookup["rate-refusal"]["httpStatus"] == 429
auth_shutdown = read(auth + "shutdown.json")
assert not auth_shutdown["javaProcessAlive"] and all(x["listenerCount"] == 0 for x in auth_shutdown["ports"])
assert read("reports/cost-quota-20261117/credential-scan.json")["knownCredentialMatches"] == 0
assert not read("reports/cost-quota-20261117/failed-same-process/attempt.json")["passed"]
print(f"Verified {len(manifest['artifacts'])} artifact hashes, source identity, Java625/625, cost HTTP4/4 and auth HTTP36/36; provider calls/cost=0.")
