"""Offline Gold only. Rejects other datasets, no key resolution, refuses output reuse."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import run_stage2_v3_comparison as frozen

OUT = Path(__file__).resolve().parent
JAR = ROOT / "target/text2sql-agent-0.1.0-SNAPSHOT.jar"
PUBLIC = {
    "original200": ("data/eval", 200),
    "v4development100": ("data/benchmarks/olist-v4-development-20261119", 100),
    "v4formal100": ("data/benchmarks/olist-v4-formal-20261119", 100),
}


def main(resume_existing=False):
    if frozen.sha(JAR) != frozen.FROZEN_JARS[frozen.CANDIDATE_SOURCE]:
        raise ValueError("Frozen p21 JAR drift")
    reports = {}
    for name, (directory, count) in PUBLIC.items():
        output = OUT / "gold-attempt-02" / name
        reused = output.is_dir()
        if reused and not resume_existing:
            raise ValueError("Output exists; explicit --resume-existing validates completed dry reports without replay")
        if not reused:
            output.mkdir(parents=True, exist_ok=False)
        plan = dict(systems={"C": dict(repo=str(ROOT), jar=str(JAR))},
                    datasets={"public": dict(directory=directory, javaSuiteManifest="")}, port=18091)
        command = frozen.build_command(plan, "C", "public", str(output.relative_to(ROOT)), True)
        # Model construction is disabled in the dry path. Even a regression cannot use a real endpoint/key.
        overrides = {"server.address": "127.0.0.1", "agent.llm.base-url": "http://127.0.0.1:1",
                     "agent.llm.api-key": "", "agent.persistence.enabled": "false"}
        command = [arg for arg in command if not any(arg.startswith("--" + key + "=") for key in overrides)]
        command += ["--" + key + "=" + value for key, value in overrides.items()]
        started = time.monotonic()
        if not reused:
            with (output / "process.log").open("x", encoding="utf-8") as log:
                process = subprocess.run(command, cwd=ROOT, env=frozen.clean_environment(), stdout=log,
                                         stderr=subprocess.STDOUT, timeout=180)
            if process.returncode != 0:
                raise ValueError("Offline process failed; preserve output")
        paths = list(output.glob("eval-*-dryrun.json"))
        if len(paths) != 1:
            raise ValueError("Offline Gold failed; preserve logs at " + str(output))
        report = json.loads(paths[0].read_text(encoding="utf-8-sig"))
        rows = report["itemEvidence"]
        if not (report["meta"]["dryRun"] and len(rows) == report["overall"]["total"] == report["overall"]["correct"] == count):
            raise ValueError("Gold execution mismatch at " + name)
        if report["meta"]["datasetSha256"] != frozen.dataset_hash(ROOT / directory):
            raise ValueError("Dry report dataset identity drift")
        if any(row["calls"] or (row["budget"] is not None and row["budget"]["modelCalls"]) for row in rows):
            raise ValueError("Offline verification attempted a model call")
        reports[name] = dict(report=paths[0].relative_to(ROOT).as_posix(), sha256=frozen.sha(paths[0]),
            correct=count, total=count, providerCalls=0, operationWallSeconds=round(time.monotonic()-started, 3),
            operationIncludesJvmAndGoldExecution=not reused,
            reusedCompletedReportWithoutReplay=reused, nullDryRunBudgetIsExpected=True,
            boundary="Gold executable through frozen p21 fixed-SQL path only; old-JAR grade is not official V4 model grade")
        print(json.dumps({name: reports[name]}), flush=True)
    summary = dict(format="replan-stage-a-gold-execution-v1", runs=reports, providerCalls=0,
                   providerTokens=0, providerCostYuan=0, officialV4RuntimeAdmitted=False)
    with (OUT / "free-verification.json").open("x", encoding="utf-8") as stream:
        json.dump(summary, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resume-existing", action="store_true")
    main(parser.parse_args().resume_existing)
