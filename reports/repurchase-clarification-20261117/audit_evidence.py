"""Offline audit of evidence-based clarification. No model calls or DB connections."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

folder=Path(__file__).resolve().parent
root=folder.parents[1]
def read(path): return json.loads((root/path).read_text(encoding="utf-8-sig"))
m=read("reports/repurchase-clarification-20261117/manifest.json")
for item in m["artifacts"]:
    assert hashlib.sha256((root/item["path"]).read_bytes()).hexdigest()==item["sha256"],item["path"]
for item in m["sources"]:
    assert hashlib.sha256(subprocess.check_output(["git","show",item["ref"]],cwd=root)).hexdigest()==item["sha256"],item["path"]
tests=dict(tests=0,failures=0,errors=0,skipped=0,suites=0)
for path in (folder/"surefire").glob("TEST-*.xml"):
    suite=ET.parse(path).getroot(); tests["suites"]+=1
    for key in ("tests","failures","errors","skipped"): tests[key]+=int(suite.attrib[key])
assert tests==dict(tests=670,failures=0,errors=0,skipped=0,suites=66)
assert tests==read("reports/repurchase-clarification-20261117/java-tests.json")
before=read("reports/repurchase-clarification-20261117/before.json")
after=read("reports/repurchase-clarification-20261117/after.json")
assert before["datasetSha256"]==after["datasetSha256"]
assert len(before["items"])==len(after["items"])==200
changes=[]
for a,b in zip(before["items"],after["items"]):
    assert a["id"]==b["id"] and a["question"]==b["question"]
    if a["needsClarification"]!=b["needsClarification"]: changes.append((a["id"],a["needsClarification"],b["needsClarification"]))
assert changes==[("T6-003",True,False)]
assert sum(x["needsClarification"] for x in before["items"])==6
assert sum(x["needsClarification"] for x in after["items"])==5
original=next(x for x in after["items"] if x["id"]=="T6-003")["decisions"][0]
assert not original["needsClarification"] and not original["missingGroups"] and not original["unresolvedEvidence"]
assert any(e["source"]=="required-pattern" and e["group"]=="denominator" for e in original["evidence"])
http="reports/clarification-http-20261117/"
summary=read(http+"summary.json")
assert summary["passed"] and summary["cases"]==8 and summary["contextClosed"]
assert summary["syntheticModelCalls"]==2 and summary["externalProviderCalls"]==summary["providerCostYuan"]==0
requests=read(http+"requests.json")
assert len(requests)==8 and len({x["id"] for x in requests})==8
for item in requests:
    expected=item["expectedClarification"]
    assert item["httpStatus"]==(400 if expected else 200)
    assert item["body"]["status"]==("NEEDS_CLARIFICATION" if expected else "SUCCESS")
    assert item["body"]["budget"]["modelCalls"]==(0 if expected else 1)
    assert item["body"]["budget"]["unknownUsageCalls"]==0
    if not expected: assert item["body"]["rows"]==[["1"]]  # synthetic admission marker, never a business accuracy score
choice=next(x for x in requests if x["id"]=="choice")["decision"][0]
assert choice["evidence"] and choice["unresolvedEvidence"] and choice["needsClarification"]
shutdown=read(http+"shutdown.json")
assert not shutdown["javaProcessAlive"] and shutdown["listenerCount"]==0
assert read("reports/repurchase-clarification-20261117/credential-scan.json")["knownCredentialMatches"]==0
print(f"Verified {len(m['artifacts'])} artifact hashes, Java670/670, original200 only T6-003 unblocked, HTTP8/8; provider calls/cost=0. No new accuracy claim.")
