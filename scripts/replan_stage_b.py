"""Offline-only B verification. Public data whitelist, loopback model endpoint, no key resolution."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "reports/replan-stage-b-20261119"
sys.path.insert(0, str(ROOT / "scripts"))
import run_stage2_v3_comparison as frozen


def write(path, value):
    with Path(path).open("x", encoding="utf-8", newline="\n") as f:
        json.dump(value, f, indent=2, ensure_ascii=False)
        f.write("\n")


def run(name, command, timeout=240):
    path = OUT / name
    path.mkdir(exist_ok=False)
    started = time.monotonic()
    with (path / "process-output.txt").open("x", encoding="utf-8") as log:
        result = subprocess.run(command, cwd=ROOT, env=frozen.clean_environment(),
                                stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    write(path / "execution.json", dict(command=command, exitCode=result.returncode,
          wallSeconds=round(time.monotonic()-started, 3), providerCalls=0,
          scope="local-only; credentials absent, loopback model endpoint or mock model"))
    print(name, "exit=", result.returncode, flush=True)
    if result.returncode:
        print((path / "process-output.txt").read_text(encoding="utf-8")[-6500:], flush=True)
        raise SystemExit(result.returncode)


def classpath(frozen_classes=False):
    libs = (ROOT / "target/replan-b-classpath.txt").read_text(encoding="utf-8").strip()
    classes = ROOT / ("target/replan-stage-a-frozen/BOOT-INF/classes" if frozen_classes else "target/classes")
    return str(classes) + ";" + str(ROOT / "target/test-classes") + ";" + libs


def probes():
    cp = classpath()
    for name, cls in [
        ("catalog-http", "orchestrator.CatalogHttpAcceptanceProbe"),
        ("access-http", "access.AccessHttpAcceptanceProbe"),
        ("cost-http", "cost.CostHttpAcceptanceProbe"),
        ("session-http", "session.SessionOutcomeAcceptanceProbe"),
        ("timings-http", "budget.BudgetTimingsAcceptanceProbe"),
    ]:
        run(name, ["java", "-cp", cp, "com.text2sql.agent." + cls, str(OUT / (name + "-evidence"))])


def captures():
    for name, directory in [("original200", "data/eval"),
            ("development100", "data/benchmarks/olist-v4-development-20261119"),
            ("formal100", "data/benchmarks/olist-v4-formal-20261119")]:
        for system in ("p21", "B"):
            if system == "p21" and OUT.name in ("attempt-2", "attempt-3"):
                source = OUT.parent / ("messages-p21-"+name+".json")
                manifest = json.loads((OUT.parent / "submission-1-manifest.json").read_text(encoding="utf-8"))
                if hashlib.sha256(source.read_bytes()).hexdigest() != manifest["files"][source.relative_to(ROOT).as_posix()]:
                    raise ValueError("Frozen baseline capture drift")
                shutil.copyfile(source, OUT / source.name)
                continue
            run("first-call-"+system+"-"+name, ["java", "-cp", classpath(system=="p21"),
                "com.text2sql.agent.retrieval.CatalogFirstCallCapture", directory,
                str(OUT / ("messages-"+system+"-"+name+".json"))])
    comparisons = []
    for name in ("original200", "development100", "formal100"):
        old = json.loads((OUT / ("messages-p21-"+name+".json")).read_text(encoding="utf-8"))
        new = json.loads((OUT / ("messages-B-"+name+".json")).read_text(encoding="utf-8"))
        if old != new:
            raise ValueError("Actual first-call message/context drift: " + name)
        comparisons.append(dict(dataset=name, equal=len(new["items"]), total=len(new["items"])))
    write(OUT / "first-call-invariance.json", dict(comparisons=comparisons,
          exactMessagesAndRetrievalEqual=True, providerCalls=0,
          mockCalls=400 if OUT.name in ("attempt-2", "attempt-3") else 800,
          reusedFrozenBaselineMockCalls=400 if OUT.name in ("attempt-2", "attempt-3") else 0,
          boundary="Actual first ChatModel.call via ask; synthetic response, not provider transport"))


def gold():
    jar = ROOT / "target/text2sql-agent-0.1.0-SNAPSHOT.jar"
    reports = []
    for name, directory, count in [("original200", "data/eval", 200),
            ("development100", "data/benchmarks/olist-v4-development-20261119", 100),
            ("formal100", "data/benchmarks/olist-v4-formal-20261119", 100)]:
        evidence = OUT / ("gold-"+name+"-evidence")
        evidence.mkdir(exist_ok=False)
        plan=dict(systems={"C":dict(repo=str(ROOT),jar=str(jar))},
                  datasets={"public":dict(directory=directory,javaSuiteManifest="")},port=18091)
        cmd=frozen.build_command(plan,"C","public",str(evidence.relative_to(ROOT)),True)
        overrides={"server.address":"127.0.0.1","agent.llm.base-url":"http://127.0.0.1:1",
                   "agent.llm.api-key":"","agent.persistence.enabled":"false"}
        cmd=[a for a in cmd if not any(a.startswith("--"+key+"=") for key in overrides)]
        cmd += ["--"+k+"="+v for k,v in overrides.items()]
        run("gold-"+name,cmd)
        files=list(evidence.glob("eval-*-dryrun.json"))
        if len(files)!=1: raise ValueError("Missing dry report")
        data=json.loads(files[0].read_text(encoding="utf-8-sig"))
        if data["overall"]["correct"]!=count or data["overall"]["total"]!=count:
            raise ValueError("Gold execution failed")
        if any(r["calls"] for r in data["itemEvidence"]): raise ValueError("Unexpected model call")
        reports.append(dict(name=name,correct=count,total=count,report=str(files[0].relative_to(ROOT))))
    write(OUT / "gold-execution.json",dict(runs=reports,providerCalls=0,officialV4RuntimeAdmitted=False))


if __name__ == "__main__":
    for attempt in ("attempt-2", "attempt-3"):
        if "--"+attempt in sys.argv:
            sys.argv.remove("--"+attempt)
            OUT = OUT / attempt
            OUT.mkdir(exist_ok=True)
            break
    action=sys.argv[1]
    if action in ("focused", "build"):
        command=[shutil.which("mvn.cmd"),"-q"]
        if action=="focused": command += ["-Dtest=CatalogSendBoundaryTest,CatalogValidationBoundaryTest,SqlValidatorTest,ToolExecutorTest,AgentLoopTest,OrchestratorSelfCorrectionTest,MultiTurnOrchestratorTest,SessionOutcomeFinalizationTest,ClarificationReplyTest,BudgetOrchestratorTest,SqlExtractionPipelineTest", "test"]
        else: command += (["clean"] if OUT.name == "attempt-3" else []) + ["package", "dependency:build-classpath", "-Dmdep.outputFile=target/replan-b-classpath.txt"]
        try:
            run(sys.argv[2],command,300)
        finally:
            source=ROOT / "target/surefire-reports"
            if source.exists(): shutil.copytree(source,OUT / sys.argv[2] / "surefire",dirs_exist_ok=False)
    elif action=="probes": probes()
    elif action=="captures": captures()
    elif action=="gold": gold()
    else: raise ValueError("Unsupported offline action")
