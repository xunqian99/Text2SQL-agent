"""Offline provenance for the 2026-11-19 replan. Never resolves or sends a model key."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import run_stage2_v3_comparison as frozen
from summarize_holdout import dataset_hash, ids_hash, is_dev

OUT = ROOT / "reports/replan-stage-a-20261119"
PUBLIC = ("data/eval", "data/benchmarks/olist-v3-candidate",
          "data/benchmarks/olist-v3-dev-runtime-candidate")
SOURCE = frozen.CANDIDATE_SOURCE


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True,
                          capture_output=True).stdout


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # An existing evidence file must never be silently replaced.
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def files_under(paths):
    result = {}
    for name in paths:
        path = ROOT / name
        children = path.rglob("*") if path.is_dir() else [path]
        for child in sorted(children):
            if child.is_file():
                result[child.relative_to(ROOT).as_posix()] = sha(child)
    return result


def items(directory):
    # Existing offline PyYAML installation; no new project/runtime dependency.
    import yaml
    return [item for path in sorted((ROOT / directory).glob("T*.yaml"))
            for item in yaml.safe_load(path.read_text(encoding="utf-8-sig"))]


def public_references():
    result = {}
    for directory in PUBLIC:
        rows = items(directory)
        seal_path = ROOT / directory / "reference-seal.json"
        seal_checks = {}
        if seal_path.is_file():
            seal = json.loads(seal_path.read_text(encoding="utf-8-sig"))
            seal_checks = {p["path"]: sha(ROOT / p["path"]) == p["sha256"]
                           for p in seal["candidateManifest"]}
            if not all(seal_checks.values()):
                raise ValueError("Frozen V3 bytes drifted")
        result[directory] = dict(itemCount=len(rows),
            uniqueIds=len({r["id"] for r in rows}),
            byLayer=dict(Counter(r["difficulty"] for r in rows)),
            datasetSha256=dataset_hash(ROOT / directory),
            sampleIdsSha256=ids_hash(r["id"] for r in rows),
            frozenSealSha256=sha(seal_path) if seal_path.is_file() else None,
            frozenByteChecks=seal_checks)
    return result


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest_path = OUT / "source-baseline.json"
    if manifest_path.exists():
        raise ValueError("Baseline already recorded; use verify, never overwrite")
    baseline_jar = ROOT / ".private/recovery/p9-3c0bda0/text2sql-agent-p9.jar"
    candidate_jar = ROOT / "target/text2sql-agent-0.1.0-SNAPSHOT.jar"
    p21_copy = ROOT / ".private/recovery/p21-ea5a8a4/text2sql-agent-p21.jar"
    source_files = git("ls-tree", "-r", "--name-only", SOURCE, "src/main", "pom.xml").decode().splitlines()
    source_checks = {p: hashlib.sha256(git("cat-file", "--filters", "--path=" + p, SOURCE + ":" + p)).hexdigest()
                     == sha(ROOT / p) for p in source_files}
    if not all(source_checks.values()):
        raise ValueError("Current production differs from declared p21 source")
    expected = set(source_files)
    actual = {p.relative_to(ROOT).as_posix() for p in (ROOT / "src/main").rglob("*") if p.is_file()}
    if actual != {p for p in expected if p.startswith("src/main/")} | {"src/main/resources/application-local.yml"}:
        raise ValueError("Unexpected production file set")
    jars = {"p9Comparison": baseline_jar, "p21Current": candidate_jar, "p21Preserved": p21_copy}
    jar_checks = {name: sha(path) == frozen.FROZEN_JARS[frozen.BASE_COMMIT if name == "p9Comparison" else SOURCE]
                  for name, path in jars.items()}
    if not all(jar_checks.values()):
        raise ValueError("Preserved/current JAR identity differs")
    dependencies = []
    with zipfile.ZipFile(candidate_jar) as archive:
        for entry in sorted(n for n in archive.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")):
            dependencies.append(dict(name=entry.rsplit("/", 1)[-1], bytes=archive.getinfo(entry).file_size,
                                     sha256=hashlib.sha256(archive.read(entry)).hexdigest()))
    safe_roots = ["src", "pom.xml", "scripts", *PUBLIC,
                  "docs/REVISED_IMPROVEMENT_PLAN_20261119.md", "docs/STAGE2_REGRESSION_PROTOCOL_20261117.md"]
    protected = files_under(safe_roots)
    protected = {p: value for p, value in protected.items() if "__pycache__/" not in p}
    for name, path in jars.items():
        protected[path.relative_to(ROOT).as_posix()] = sha(path)
    status = git("-c", "core.quotepath=false", "status", "--porcelain=v1", "-uall").decode("utf-8")
    # Safe tracked diff: hashes represent the exact dirty bytes, not just HEAD.
    dirty = []
    changed = git("diff", "HEAD", "--name-only", "-z").decode("utf-8").split("\0")
    for name in filter(None, changed):
        path = ROOT / name
        dirty.append(dict(path=name, exists=path.is_file(), sha256=sha(path) if path.is_file() else None,
                          headDiffSha256=hashlib.sha256(git("diff", "--binary", "HEAD", "--", name)).hexdigest()))
    database = frozen.database_snapshot()
    value = dict(format="replan-stage-a-source-v1", capturedAt=datetime.now(timezone.utc).isoformat(),
        head=git("rev-parse", "HEAD").decode().strip(), productionSourceCommit=SOURCE,
        comparisonSourceCommit=frozen.BASE_COMMIT, productionSourceChecks=source_checks,
        sourceTreeSha256=frozen.digest({p: protected[p] for p in sorted(protected) if p.startswith("src/") or p == "pom.xml"}),
        jars={name: dict(path=path.relative_to(ROOT).as_posix(), sha256=sha(path), matchesFrozen=jar_checks[name])
              for name, path in jars.items()},
        dependencies=dependencies, publicReferences=public_references(), protectedFiles=protected,
        initialGitStatus=status.splitlines(), initialTrackedDifferences=dirty,
        database=database, runtimeArgumentsSource=".private/recovery/p9-3c0bda0/runtime.json",
        runtimeArgumentsSha256=sha(ROOT / ".private/recovery/p9-3c0bda0/runtime.json"),
        runtimeArguments=json.loads((ROOT / ".private/recovery/p9-3c0bda0/runtime.json").read_text())["arguments"],
        configEnvironmentSha256=frozen.config_environment_hash(),
        offPeakPolicy=frozen.window(datetime.now(timezone.utc)),
        officialPricingSource="https://api-docs.deepseek.com/quick_start/pricing/",
        privacy="Local configuration and environment values are hashed only. No model key is resolved. No blind data is opened.",
        providerCalls=0, providerTokens=0, providerCostYuan=0, stageAccepted=False)
    write(manifest_path, value)
    print(json.dumps(dict(recorded=True, productionFiles=len(source_checks), dependencies=len(dependencies),
                          preservedJars=len(jars), businessTables=len(database["tables"]),
                          publicItems=sum(v["itemCount"] for v in value["publicReferences"].values()),
                          offPeakAllowed=value["offPeakPolicy"]["allowed"])))


def verify(output, database):
    baseline = json.loads((OUT / "source-baseline.json").read_text(encoding="utf-8"))
    drift = [p for p, value in baseline["protectedFiles"].items()
             if not (ROOT / p).is_file() or sha(ROOT / p) != value]
    # New scripts/data/src cannot hide behind a check of old files alone.
    for name in ("src", *PUBLIC):
        old = {p for p in baseline["protectedFiles"] if p.startswith(name + "/")}
        current = set(files_under([name]))
        drift += sorted(old ^ current)
    db_same = frozen.database_snapshot() == baseline["database"] if database else None
    result = dict(format="replan-stage-a-integrity-v1", checkedAt=datetime.now(timezone.utc).isoformat(),
                  checkedFiles=len(baseline["protectedFiles"]), drift=sorted(set(drift)),
                  databaseChecked=database, databaseUnchanged=db_same,
                  passed=not drift and db_same is not False, providerCalls=0, providerCostYuan=0)
    write(OUT / output, result)
    print(json.dumps({k: v for k, v in result.items() if k != "drift"}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "verify"])
    parser.add_argument("--output", default="integrity-after.json")
    parser.add_argument("--database", action="store_true")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare()
    else:
        raise SystemExit(verify(args.output, args.database))
