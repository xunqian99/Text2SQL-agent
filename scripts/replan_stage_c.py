"""C1 offline verification only. No key resolution or provider transport.

Reuses B's local-only build/HTTP/Gold runners; public dataset paths are fixed.
The 20% gate is the preregistered original200, never a selected subset.
"""
from pathlib import Path
import csv
import hashlib
import json
import math
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

import replan_stage_b as offline

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "reports/replan-stage-c-20261119"
OUT = BASE / "c1-submission-1"
B0 = ROOT / "reports/replan-stage-b-20261119"
DATASETS = {"original200": "data/eval",
            "development100": "data/benchmarks/olist-v4-development-20261119",
            "formal100": "data/benchmarks/olist-v4-formal-20261119"}
PRINCIPLE = "【使用原则】当用户问题涉及以下指标且未指定特殊计算口径时，遵循标准度量的表达式与过滤条件；若用户明确指明了特定的计算口径或聚合逻辑，以用户明确说明为准。\n"
offline.OUT = OUT


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def text_sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def chars(value):
    return len(value.encode("utf-16-le")) // 2


def stats(items):
    values = sorted(sum(chars(m["text"]) for m in i["messages"]) for i in items)
    return dict(items=len(values), totalChars=sum(values), meanChars=sum(values)/len(values),
                p95Chars=values[math.ceil(.95*len(values))-1])


def captures():
    identity = json.loads((B0 / "b0-identity.json").read_text(encoding="utf-8"))
    expected_system = (BASE / "c1-work/system-draft.txt").read_text(encoding="utf-8").strip()
    rows, comparisons, arms = [], [], {}
    for name, directory in DATASETS.items():
        baseline = B0 / ("attempt-5/messages-B-"+name+".json")
        assert sha(baseline) == identity["firstCallCaptureFiles"][baseline.name], "B0 drift"
        output = OUT / ("messages-C1-"+name+".json")
        offline.run("first-call-C1-"+name, ["java", "-cp", offline.classpath(),
                    "com.text2sql.agent.retrieval.CatalogFirstCallCapture", directory, str(output)])
        old = json.loads(baseline.read_text(encoding="utf-8"))
        new = json.loads(output.read_text(encoding="utf-8"))
        assert old["dataset"] == new["dataset"] == directory
        assert old["providerCalls"] == new["providerCalls"] == 0
        assert len(old["items"]) == len(new["items"]) == (200 if name == "original200" else 100)
        unchanged_users = metrics_users = 0
        for a, b in zip(old["items"], new["items"]):
            assert {k:v for k,v in a.items() if k!="messages"} == {k:v for k,v in b.items() if k!="messages"}
            assert [m["role"] for m in a["messages"]] == [m["role"] for m in b["messages"]] == ["SYSTEM", "USER"]
            old_system, old_user = [m["text"] for m in a["messages"]]
            new_system, new_user = [m["text"] for m in b["messages"]]
            assert new_system == expected_system
            assert old_user.count(PRINCIPLE) in (0, 1)
            assert new_user == old_user.replace(PRINCIPLE, ""), "Unexpected component drift"
            has_metrics = PRINCIPLE in old_user
            metrics_users += has_metrics
            unchanged_users += old_user == new_user
            rows.append(dict(dataset=name, id=a["id"], questionSha256=text_sha(a["question"]),
                 baselineSystemChars=chars(old_system), candidateSystemChars=chars(new_system),
                 baselineUserChars=chars(old_user), candidateUserChars=chars(new_user),
                 savedChars=chars(old_system)+chars(old_user)-chars(new_system)-chars(new_user),
                 hasMetricPrinciple=has_metrics, baselineUserSha256=text_sha(old_user),
                 candidateUserSha256=text_sha(new_user), contextPreserved=True,
                 explanation="universal system consolidation" + ("; repeated metric principle removed" if has_metrics else "; user message unchanged")))
        before, after = stats(old["items"]), stats(new["items"])
        comparisons.append(dict(dataset=name, baseline=before, candidate=after,
            totalReduction=1-after["totalChars"]/before["totalChars"],
            p95NonIncreasing=after["p95Chars"]<=before["p95Chars"],
            unchangedUserMessages=unchanged_users, removedRepeatedMetricPrinciples=metrics_users,
            nonPromptContextEqual=True))
        arms[name] = (old["items"], new["items"])
    first = comparisons[0]
    assert first["totalReduction"] >= .20 and first["p95NonIncreasing"], "C1 not admitted"
    v4old = arms["development100"][0] + arms["formal100"][0]
    v4new = arms["development100"][1] + arms["formal100"][1]
    offline.write(OUT / "input-comparison.json", dict(admissionDataset="original200",
        gatePassed=True, comparisons=comparisons, v4Combined=dict(baseline=stats(v4old), candidate=stats(v4new)),
        characterUnit="UTF-16 code unit; nearest-rank P95", all400ContextsEqual=True,
        all400UserChangesExactlyExplained=True, actualInProcessMockCalls=400, providerCalls=0,
        noTokenLatencyOrAccuracyClaim=True))
    offline.write(OUT / "per-item-input-diff.json", rows)
    with (OUT / "per-item-input-diff.csv").open("x", encoding="utf-8", newline="") as f:
        writer=csv.DictWriter(f, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    print(json.dumps(first), flush=True)


def seal():
    baseline=json.loads((B0 / "attempt-5/candidate-identity.json").read_text(encoding="utf-8"))
    current={p.relative_to(ROOT).as_posix():sha(p) for folder in ("src/main", "src/test")
             for p in (ROOT/folder).rglob("*") if p.is_file() and p.name!="application-local.yml"}
    assert not set(baseline["sourceFiles"]) - set(current)
    changed=sorted(p for p,v in current.items() if baseline["sourceFiles"].get(p)!=v)
    assert changed == ["src/main/java/com/text2sql/agent/generation/PromptTemplate.java",
                       "src/test/java/com/text2sql/agent/generation/PromptVariantTest.java",
                       "src/test/resources/prompts/p21-system.txt"], changed
    assert sha(ROOT/"pom.xml") == baseline["pomSha256"]
    original=json.loads((ROOT/"reports/replan-stage-a-20261119/source-baseline.json").read_text(encoding="utf-8"))
    for item in original["initialTrackedDifferences"]:
        p=ROOT/item["path"]
        assert p.exists()==item["exists"] and (not p.exists() or sha(p)==item["sha256"]), "User change drift"
    database=offline.frozen.database_snapshot()
    assert database==baseline["database"], "Database drift"
    jar=ROOT/"target/text2sql-agent-0.1.0-SNAPSHOT.jar"
    with zipfile.ZipFile(jar) as z:
        deps={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")}
        packaged={n[len("BOOT-INF/classes/"):]:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith("BOOT-INF/classes/") and not n.endswith("/")}
    assert deps==baseline["dependencies"]
    compiled={p.relative_to(ROOT/"target/classes").as_posix():sha(p) for p in (ROOT/"target/classes").rglob("*") if p.is_file()}
    assert compiled==packaged
    trees=[ET.parse(p).getroot() for p in (OUT/"full-build-final/surefire").glob("TEST-*.xml")]
    counts={k:sum(int(t.get(k,0)) for t in trees) for k in ("tests","failures","errors","skipped")}
    assert counts==dict(tests=1480,failures=0,errors=0,skipped=0), counts
    assert json.loads((OUT/"input-comparison.json").read_text(encoding="utf-8"))["gatePassed"]
    assert sum(r["correct"] for r in json.loads((OUT/"gold-execution.json").read_text(encoding="utf-8"))["runs"])==400
    for name in ("catalog-http","access-http","cost-http","session-http","timings-http"):
        assert json.loads((OUT/name/"execution.json").read_text(encoding="utf-8"))["exitCode"]==0
    saved=ROOT/".private/recovery/replan-c1-20261119/text2sql-agent-C1-submission-1.jar"
    saved.parent.mkdir(parents=True, exist_ok=True)
    assert not saved.exists(); shutil.copyfile(jar,saved)
    offline.write(OUT/"candidate-identity.json",dict(route="C1",submission=1,baseSourceCommit="37017182d67a1fba7d16b399cc616565a784aac0",
        sourceFiles=current,changedSources=changed,pomSha256=sha(ROOT/"pom.xml"),jarSha256=sha(jar),
        savedJar=str(saved),dependencies=deps,dependencyCount=len(deps),database=database,
        compiledClasses={p.relative_to(ROOT).as_posix():sha(p) for folder in ("target/classes","target/test-classes")
            for p in (ROOT/folder).rglob("*") if p.is_file()},providerCalls=0,paidEvaluationAdmitted=False))
    summary=dict(status="READY_FOR_INDEPENDENT_REVIEW",route="C1",submission=1,finalMaven=counts,
        testSuites=len(trees),firstCallCaptured=400,goldExecutable=400,httpProbeGroups=5,
        providerCalls=0,providerTokens=0,providerCostYuan=0,paidEvaluationAdmitted=False)
    offline.write(OUT/"verification-summary.json",summary)
    paths=[p for p in BASE.rglob("*") if p.is_file() and "independent-review" not in p.parts]
    paths += [ROOT/p for p in changed]+[ROOT/"scripts/replan_stage_c.py",ROOT/".gitattributes"]
    offline.write(OUT/"submission-1-manifest.json",dict(route="C1",submission=1,
        files={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(paths)},providerCalls=0))
    print(json.dumps(summary),flush=True)


if __name__=="__main__":
    OUT.mkdir(exist_ok=True)
    action=sys.argv[1]
    if action=="build":
        try:
            offline.run("full-build-final",[r"D:\develop\Maven\apache-maven-3.9.4\bin\mvn.cmd","-q","clean","package",
                "dependency:build-classpath","-Dmdep.outputFile=target/replan-b-classpath.txt"],300)
        finally:
            if (ROOT/"target/surefire-reports").exists():
                shutil.copytree(ROOT/"target/surefire-reports",OUT/"full-build-final/surefire")
    elif action=="captures": captures()
    elif action=="probes": offline.probes()
    elif action=="gold": offline.gold()
    elif action=="seal": seal()
    else: raise ValueError("Offline actions only")
