"""Offline audit of source identity, full regression and production-server access probes."""
import hashlib
import json
from pathlib import Path
import subprocess

folder = Path(__file__).resolve().parent
root = folder.parents[1]
read = lambda p: json.loads((root / p).read_text(encoding="utf-8-sig"))
manifest = read("reports/access-control-20261117/manifest.json")
for item in manifest["artifacts"]:
    assert hashlib.sha256((root / item["path"]).read_bytes()).hexdigest() == item["sha256"], item["path"]
for item in manifest["sources"]:
    assert hashlib.sha256(subprocess.check_output(["git", "show", item["ref"]], cwd=root)).hexdigest() == item["sha256"], item["path"]
for item in manifest["trees"]:
    assert subprocess.check_output(["git", "rev-parse", item["ref"]], cwd=root, text=True).strip() == item["object"]
tests = read("reports/access-control-20261117/java-tests.json")
assert tests == {"tests": 605, "failures": 0, "errors": 0, "skipped": 0, "suites": 61}
summary = read("reports/access-http-20261117/summary.json")
assert summary["passed"] and summary["checks"] == 36 and summary["contextsClosed"]
assert summary["syntheticModelCalls"] == 2 and summary["externalProviderCalls"] == summary["providerCostYuan"] == 0
requests = read("reports/access-http-20261117/requests.json")
assert len(requests) == 36
lookup = {item["name"]: item for item in requests}
assert len(lookup) == 36
assert len([x for x in requests if x["name"].startswith("unauthorized-")]) == 14
for item in requests:
    if item["name"].startswith("unauthorized-") or item["name"] in {"byok-is-not-login", "wrong-password", "malformed-basic", "cannot-delete-without-login", "cannot-close-without-login"}:
        assert item["httpStatus"] == 401
        assert item["headers"]["www-authenticate"].startswith("Basic ")
    assert item["headers"]["cache-control"] == "no-store"
for name in ("cross-origin", "cross-site-get"):
    assert lookup[name]["httpStatus"] == 403
for name, code in [("concurrency-refusal", "CONCURRENCY_LIMIT"), ("rate-refusal", "RATE_LIMIT")]:
    item = lookup[name]
    assert item["httpStatus"] == 429 and item["body"]["status"] == code
    assert int(item["headers"]["retry-after"]) >= 1
assert lookup["permit-released"]["httpStatus"] == lookup["health-during-saturation"]["httpStatus"] == lookup["health-after-rate-refusal"]["httpStatus"] == 200
assert lookup["authenticated-delete"]["httpStatus"] == 204
assert lookup["authenticated-close"]["body"]["turnCount"] == 1
assert lookup["same-origin-ask"]["body"]["rows"] == [["54011"]]
assert lookup["effective-limits"]["body"]["enabled"] and not lookup["effective-limits"]["body"]["costQuotaEnforced"]
shutdown = read("reports/access-http-20261117/shutdown.json")
assert not shutdown["javaProcessAlive"] and all(x["listenerCount"] == 0 for x in shutdown["ports"])
print(f"Verified {len(manifest['artifacts'])} artifact hashes, source identities, Java 605/605 and real HTTP 36/36; provider calls/cost=0.")
