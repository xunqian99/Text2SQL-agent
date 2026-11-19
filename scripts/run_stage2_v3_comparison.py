"""Frozen V3 batches. prepare/preflight/audit are offline; only run-next may resolve a real key.

No YAML is parsed here. Blind source bytes are hashed and Java reads its own Gold.
Completion timestamps in JSONL are usage evidence, NEVER HTTP-send-time evidence.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
import zipfile
try:
    from zoneinfo import ZoneInfo
    BEIJING = ZoneInfo("Asia/Shanghai")
except (ImportError, KeyError):
    BEIJING = timezone(timedelta(hours=8), "Asia/Shanghai-fixed-UTC+8")

from summarize_holdout import dataset_hash, ids_hash
from summarize_request_budget import audit_run

ROOT = Path(__file__).resolve().parent.parent
FORMAT = "stage2-v3-comparison-v1"
PROTOCOL = "docs/STAGE2_V3_EVALUATION_PROTOCOL_20261119.md"
BASE_COMMIT = "3c0bda099c08183ff3e3b4607bd0a071158fc630"
CANDIDATE_SOURCE = "ea5a8a4ea9d2125a69181d16642bb816c23f1da5"
FROZEN_JARS = {
    CANDIDATE_SOURCE: "e8a3db8efba97e675432b995018b23b016088604cc446513c2d84f85703fe764",
    BASE_COMMIT: "a89dbd431263f0c9356510d71a5dd90fdcd6e0b3ab806ce9e3fdd16cf1121a8b",
}
FORMAL = ["B1", "C1", "C2", "B2", "B3", "C3"]
ORDER = ["DEV_C", *FORMAL, "U_B", "U_C"]
REQUIRED_SECONDS = 100 * 15 + 120 + 120
INTERNAL = {"schema_chunks", "semantic_cache", "llm_call_log", "conversation_session", "conversation_turn"}
GATE_MARKERS = ("OFF_PEAK_REQUIRED", "PEAK_HOURS", "PEAK_BOUNDARY_RESERVE", "DeepSeek评测仅允许空闲时段")
CONFIG = dict(requestBudgetEnabled=True, requestTimeoutMs=15000, requestMaxModelCalls=2,
              requestMinRetryRemainingMs=2000, sdkMaxAttempts=1, maxTokens=8192, temperature=0,
              configuredReasoningEffort="provider-default", explicitReasoningEnabled=True,
              clarificationEnabled=True, resultCheckerEnabled=True, columnPruningEnabled=True,
              joinPlanEnabled=True, joinListEnabled=False, bridgeRepairEnabled=True,
              semanticEnabled=True, fewshotEnabled=True, fewshotMaxExamples=2,
              fewshotRequireAvailableTables=False, cacheEnabled=False, routingEnabled=False,
              toolUseEnabled=False, conversationEnabled=False, inputPricePer1k=.001,
              cacheHitPricePer1k=.00002, outputPricePer1k=.004, costEstimated=True)
PREFLIGHT_ORDER = [(dataset, side) for dataset in ("dev", "formal", "blind") for side in ("B", "C")]


class Refused(RuntimeError):
    pass


class KeyUnavailable(Refused):
    pass


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode()).hexdigest()


def write(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temporary.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def now():
    return datetime.now(timezone.utc)


def stamp():
    return now().isoformat()


def window(at):
    """Conservative weekday peak schedule; no guessed holiday exemptions or sleeping."""
    if at.tzinfo is None:
        raise Refused("Timezone-aware clock required")
    local = at.astimezone(BEIJING)
    for offset in range(8):
        day = local.date() + timedelta(days=offset)
        if day.weekday() >= 5:
            continue
        for start, end in ((9, 12), (14, 18)):
            left = datetime.combine(day, datetime.min.time(), BEIJING).replace(hour=start)
            right = left.replace(hour=end)
            if left <= local < right:
                return {"checkedAt": at.isoformat(), "allowed": False, "reason": "PEAK_HOURS", "remainingSeconds": 0,
                        "nextAllowedAt": right.isoformat(), "requiredSeconds": REQUIRED_SECONDS}
            if local < left:
                remaining = (left - local).total_seconds()
                return {"checkedAt": at.isoformat(), "allowed": remaining >= REQUIRED_SECONDS,
                        "reason": "OFF_PEAK" if remaining >= REQUIRED_SECONDS else "INSUFFICIENT_WHOLE_ROUND_WINDOW",
                        "remainingSeconds": remaining, "nextPeakAt": left.isoformat(),
                        "requiredSeconds": REQUIRED_SECONDS}
    raise Refused("Cannot determine next peak boundary")


def promotion(group):
    layers = group.get("byLayer", {})
    deep, business = layers.get("multi_join_deep", {}), layers.get("business_semantics", {})
    checks = dict(denominator100=group.get("total") == 100,
                  correctAtLeast71=group.get("correct", -1) >= 71,
                  deepAtLeast4of10=deep.get("total") == 10 and deep.get("correct", -1) >= 4,
                  businessAtLeast6of13=business.get("total") == 13 and business.get("correct", -1) >= 6,
                  timeoutsAtMost9=group.get("timeoutCount", 101) <= 9)
    return {"passed": all(checks.values()), "checks": checks}


def identity(name):
    dataset = "dev" if name == "DEV_C" else "blind" if name.startswith("U_") else "formal"
    side = "B" if name.startswith("B") or name == "U_B" else "C"
    return side, dataset


def gates(groups):
    result = {}
    if "DEV_C" in groups:
        result["developmentGate"] = promotion(groups["DEV_C"])
    if all(name in groups for name in FORMAL):
        b = sum(groups[name]["correct"] for name in ("B1", "B2", "B3")) / 3
        c = sum(groups[name]["correct"] for name in ("C1", "C2", "C3")) / 3
        result["formalGate"] = dict(baselineMeanPercent=b, candidateMeanPercent=c,
                                  relativeDeltaPp=c-b, passed=c >= 89 and c >= b-2)
    if "U_C" in groups:
        delta = groups["U_C"]["correct"] - groups["U_B"]["correct"]
        result["blindGate"] = dict(relativeDeltaPp=delta, passed=delta >= -2,
                                 baselineEstablished=True, absolute96Required=False)
    return result


def validate_state(state):
    if state.get("format") != FORMAT or [r["name"] for r in state["runs"]] != ORDER:
        raise Refused("Mutable run registry/order drift")
    phase = state["phase"]
    if phase not in {"PREFLIGHT_PENDING", "PREFLIGHTING", "READY", "RUNNING", "HALTED", "COMPLETE"}:
        raise Refused("Unknown batch phase")
    preflights = [(p["dataset"], p["side"]) for p in state["preflights"]]
    if preflights != PREFLIGHT_ORDER[:len(preflights)] or len(preflights) > len(PREFLIGHT_ORDER):
        raise Refused("Gold preflight registry/order drift")
    for p in state["preflights"]:
        if p["name"] != "gold_" + p["dataset"] + "_" + p["side"]:
            raise Refused("Gold preflight identity drift")
    states = [r["state"] for r in state["runs"]]
    if any(s not in {"PENDING", "STARTED", "COMPLETE", "INTERRUPTED"} for s in states):
        raise Refused("Unknown round state")
    prefix = 0
    while prefix < len(states) and states[prefix] == "COMPLETE":
        prefix += 1
    if prefix < len(states) and any(s != "PENDING" for s in states[prefix+1:]):
        raise Refused("Completed rounds must form the preregistered prefix")
    if any("startedAt" in r for r in state["runs"] if r["state"] == "PENDING"):
        raise Refused("Started round reset to PENDING")
    if phase in {"READY", "RUNNING", "COMPLETE"} and len(preflights) != 6:
        raise Refused("All six Gold preflights required")
    if phase in {"PREFLIGHT_PENDING", "PREFLIGHTING"} and any(s != "PENDING" for s in states):
        raise Refused("Real round started before Gold preflight")
    if phase == "PREFLIGHT_PENDING" and preflights:
        raise Refused("Gold preflight reset")
    if phase == "READY" and (prefix == len(states) or states[prefix] != "PENDING"):
        raise Refused("READY phase cannot replay a started round")
    if phase == "RUNNING" and (prefix == len(states) or states[prefix] != "STARTED"):
        raise Refused("RUNNING phase missing durable started round")
    if phase == "COMPLETE" and prefix != len(states):
        raise Refused("Incomplete comparison cannot be COMPLETE")
    if phase == "HALTED" and not state.get("haltReason"):
        raise Refused("HALTED phase missing cause")


def checked_run(command, cwd, **kwargs):
    return subprocess.run(command, cwd=str(cwd), capture_output=True, check=True, **kwargs)


def git(repo, *args):
    return checked_run(["git", *args], repo).stdout


def seal_dataset(directory, seal_path):
    """Read only metadata and opaque bytes; never parse question/Gold YAML."""
    directory, seal_path = Path(directory).resolve(), Path(seal_path).resolve()
    seal = read(seal_path)
    state = seal.get("state", "")
    frozen = state in {"FROZEN", "REFERENCE_FROZEN_NOT_EVALUATION_ADMITTED", "REFERENCE_FROZEN"} or (
        seal.get("format") == "frozen-eval-suite-v1" and bool(seal.get("frozenAt")) and not state)
    if not (frozen and seal.get("frozenAt") and seal.get("independentReview") is True
            and seal.get("itemCount") == 100):
        raise Refused("Dataset lacks independent final frozen100 seal: " + str(seal_path))
    files = sorted(p for p in directory.iterdir() if re.fullmatch(r"T\d+_.*\.ya?ml", p.name))
    if len(files) != 6:
        raise Refused("Expected six frozen layer files")
    hashes = {p.name: sha(p) for p in files}
    declared = seal.get("candidateManifest", [])
    if declared:
        expected = {Path(x["path"]).name: x["sha256"] for x in declared}
        if expected != hashes:
            raise Refused("Frozen dataset file fingerprint mismatch")
    elif seal.get("datasetSha256") != dataset_hash(directory):
        raise Refused("Frozen suite dataset fingerprint mismatch")
    return dict(directory=str(directory), seal=str(seal_path), sealSha256=sha(seal_path),
                datasetSha256=dataset_hash(directory), files=hashes,
                sampleIdsSha256=seal.get("sampleIdsSha256"),
                referenceDir=seal.get("referenceDir"), referenceDatasetSha256=seal.get("referenceDatasetSha256"),
                javaSuiteManifest=str(seal_path) if seal.get("format") == "frozen-eval-suite-v1" else "")


def production_snapshot(repo, source_commit, jar):
    repo, jar = Path(repo).resolve(), Path(jar).resolve()
    source_commit = git(repo, "rev-parse", source_commit).decode().strip()
    tracked = git(repo, "ls-tree", "-r", "--name-only", source_commit, "src/main", "pom.xml").decode().splitlines()
    for name in tracked:
        expected = git(repo, "cat-file", "--filters", "--path=" + name, source_commit + ":" + name)
        if not (repo / name).is_file() or hashlib.sha256(expected).hexdigest() != sha(repo / name):
            raise Refused("Production source differs from frozen source commit: " + name)
    local = repo / "src/main/resources/application-local.yml"
    if not local.is_file() or not jar.is_file():
        raise Refused("Missing local profile or frozen JAR")
    if sha(jar) != FROZEN_JARS.get(source_commit):
        raise Refused("JAR differs from the preregistered built artifact")
    actual_production = {p.relative_to(repo).as_posix() for p in (repo / "src/main").rglob("*") if p.is_file()}
    expected_production = {p for p in tracked if p.startswith("src/main/")}
    if actual_production != expected_production | {"src/main/resources/application-local.yml"}:
        raise Refused("Unregistered extra/missing production source or resource")
    metric = repo / "src/main/resources/schema/metrics.yml"
    with zipfile.ZipFile(jar) as archive:
        entry = "BOOT-INF/classes/schema/metrics.yml"
        if archive.namelist().count(entry) != 1 or hashlib.sha256(archive.read(entry)).hexdigest() != sha(metric):
            raise Refused("Packaged metric definitions differ from frozen resource")
    protected = [p for p in (repo / "src").rglob("*") if p.is_file()]
    protected += [repo / "pom.xml", repo / "scripts/run_eval.ps1", jar]
    protected += external_config_files(repo)
    return dict(repo=str(repo), sourceCommit=source_commit,
                runtimeHead=git(repo, "rev-parse", "HEAD").decode().strip(), jar=str(jar),
                files={str(p): sha(p) for p in sorted(set(protected))},
                externalConfigFiles=[str(p) for p in external_config_files(repo)],
                metricDefinitionsSha256=sha(metric),
                promptVersion="p21-period-ranking-v21" if source_commit == CANDIDATE_SOURCE else "p9-mdl-alignment-v9")


def database_snapshot():
    """One REPEATABLE READ transaction, 37 business tables, ordered SHA-256 row multiset.

    JSONB canonical text defines column key order; byte hashes sort with C collation.
    All payload values stay inside PostgreSQL; only count/hash metadata leaves it.
    """
    command = ["docker", "exec", "-i", "text2sql-postgres", "psql", "-X", "-qAt",
               "-v", "ON_ERROR_STOP=1", "-U", "text2sql_ro", "-d", "olist"]
    discover = "BEGIN READ ONLY; SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename; COMMIT;"
    tables = checked_run(command, ROOT, input=discover.encode(), timeout=30).stdout.decode().splitlines()
    tables = [t for t in tables if t not in INTERNAL]
    if len(tables) != 37 or any(not re.fullmatch(r"[a-z_][a-z0-9_]*", t) for t in tables):
        raise Refused("Database business table set must be37")
    statements = ["BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;",
                  "SET LOCAL statement_timeout='8s';",
                  "SELECT jsonb_build_object('schema',jsonb_agg(x ORDER BY table_name,ordinal_position)) FROM "
                  "(SELECT table_name,column_name,ordinal_position,data_type,udt_name,is_nullable,column_default,"
                  "character_maximum_length,numeric_precision,numeric_scale,"
                  "obj_description(format('%I.%I',table_schema,table_name)::regclass,'pg_class') AS table_comment,"
                  "col_description(format('%I.%I',table_schema,table_name)::regclass,ordinal_position) AS column_comment "
                  "FROM information_schema.columns WHERE table_schema='public' AND table_name IN ("
                  + ",".join("'" + t + "'" for t in tables) + ")) x;",
                  "SELECT jsonb_build_object('constraints',jsonb_agg(x ORDER BY table_name,definition)) FROM "
                  "(SELECT c.relname AS table_name,pg_get_constraintdef(k.oid) AS definition FROM pg_constraint k "
                  "JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace "
                  "WHERE n.nspname='public' AND c.relname IN (" + ",".join("'" + t + "'" for t in tables) + ")) x;"]
    for table in tables:
        statements.append("SELECT jsonb_build_object('table','" + table + "','rows',COUNT(*),'sha256',"
                          "encode(sha256(convert_to(COALESCE(string_agg(h,'' ORDER BY h COLLATE \"C\"),''),'UTF8')),'hex')) "
                          "FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') AS h FROM public.\""
                          + table + "\" t) rows;")
    statements.append("COMMIT;")
    output = checked_run(command, ROOT, input="\n".join(statements).encode(), timeout=180).stdout.decode()
    records = [json.loads(line) for line in output.splitlines() if line.strip()]
    return {"algorithm": "sha256-canonical-jsonb-row-multiset-C-order-v1", "tables": tables,
            "sha256": digest(records)}


def verify_inputs(plan, check_database=True):
    for side in plan["systems"].values():
        if git(side["repo"], "rev-parse", "HEAD").decode().strip() != side["runtimeHead"]:
            raise Refused("Repository HEAD drift")
        for path, value in side["files"].items():
            if not Path(path).is_file() or sha(path) != value:
                raise Refused("Protected source/config/JAR fingerprint drift: " + path)
        current = {str(p) for p in (Path(side["repo"]) / "src").rglob("*") if p.is_file()}
        frozen = {p for p in side["files"] if Path(p).is_relative_to(Path(side["repo"]) / "src")}
        if current != frozen:
            raise Refused("Source/resource file set drift")
        if [str(p) for p in external_config_files(Path(side["repo"]))] != side.get("externalConfigFiles", []):
            raise Refused("External application configuration file set drift")
    for path, value in plan["controllerFiles"].items():
        if not Path(path).is_file() or sha(path) != value:
            raise Refused("Controller/protocol drift")
    for data in plan["datasets"].values():
        if sha(data["seal"]) != data["sealSha256"] or dataset_hash(data["directory"]) != data["datasetSha256"]:
            raise Refused("Frozen dataset/seal drift")
        for reference in data.get("references", {}).values():
            if dataset_hash(reference["directory"]) != reference["datasetSha256"]:
                raise Refused("Frozen suite reference dataset drift")
    if config_environment_hash() != plan["configEnvironmentSha256"]:
        raise Refused("Runtime configuration environment drift")
    if check_database and database_snapshot() != plan["database"]:
        raise Refused("Business database snapshot drift")


@contextmanager
def locked(directory):
    lock = Path(directory) / ".controller.lock"
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        raise Refused("Controller lock exists; never automatically replay an uncertain started run") from None
    try:
        os.write(descriptor, str(os.getpid()).encode())
        os.close(descriptor)
        yield
    finally:
        lock.unlink(missing_ok=True)


def save(directory, state):
    write(Path(directory) / "state.json", state)


def load(directory):
    plan_path = Path(directory) / "plan.json"
    state = read(Path(directory) / "state.json")
    if sha(plan_path) != state["planSha256"]:
        raise Refused("Frozen plan drift")
    plan = read(plan_path)
    if (plan.get("format") != FORMAT or plan.get("order") != ORDER
            or plan.get("requiredWholeRoundSeconds") != REQUIRED_SECONDS
            or set(plan.get("systems", {})) != {"B", "C"}
            or set(plan.get("datasets", {})) != {"dev", "formal", "blind"}):
        raise Refused("Unregistered protocol/order")
    for side, source, prompt in (("B", BASE_COMMIT, "p9-mdl-alignment-v9"),
                                 ("C", CANDIDATE_SOURCE, "p21-period-ranking-v21")):
        if plan["systems"][side]["sourceCommit"] != source or plan["systems"][side]["promptVersion"] != prompt:
            raise Refused("Unregistered production source/prompt")
    validate_state(state)
    return plan, state


def prepare(args):
    directory = Path(args.directory).resolve()
    if directory.exists():
        raise Refused("Protocol directory already exists; no overwrite or re-preparation")
    datasets = {name: seal_dataset(getattr(args, name + "_dir"), getattr(args, name + "_seal"))
                for name in ("dev", "formal", "blind")}
    systems = {}
    for name, source in (("B", BASE_COMMIT), ("C", CANDIDATE_SOURCE)):
        repo = Path(args.baseline_repo if name == "B" else args.candidate_repo).resolve()
        jar_arg = args.baseline_jar if name == "B" else args.candidate_jar
        jar = Path(jar_arg).resolve() if jar_arg else repo / "target/text2sql-agent-0.1.0-SNAPSHOT.jar"
        systems[name] = production_snapshot(repo, source, jar)
    scorer = "src/main/java/com/text2sql/agent/execution/ResultNormalizer.java"
    scoring_hashes = {side: sha(Path(system["repo"]) / scorer) for side, system in systems.items()}
    if len(set(scoring_hashes.values())) != 1:
        raise Refused("Baseline/candidate result scoring algorithm differs")
    for data in datasets.values():
        if data["referenceDir"]:
            data["references"] = {}
            for side, system in systems.items():
                reference = Path(system["repo"]) / data["referenceDir"]
                if dataset_hash(reference) != data["referenceDatasetSha256"]:
                    raise Refused("Frozen suite reference differs between runtime repositories")
                data["references"][side] = dict(directory=str(reference.resolve()),
                                                datasetSha256=data["referenceDatasetSha256"])
    controller = [Path(__file__).resolve(), ROOT / PROTOCOL, ROOT / "scripts/summarize_request_budget.py",
                  ROOT / "scripts/summarize_holdout.py", ROOT / "scripts/run_eval.ps1"]
    for optional in (ROOT / "scripts/summarize_stage2_v3.py", ROOT / "scripts/test_stage2_v3_comparison.py"):
        if optional.exists():
            controller.append(optional)
    guide = ROOT / "docs/STAGE2_V3_CONTROLLER.md"
    if guide.exists():
        controller.append(guide)
    plan = dict(format=FORMAT, preparedAt=stamp(), order=ORDER, systems=systems, datasets=datasets,
                controllerFiles={str(p): sha(p) for p in controller}, database=database_snapshot(),
                configEnvironmentSha256=config_environment_hash(),
                commonScorerSha256=scoring_hashes["B"],
                requiredWholeRoundSeconds=REQUIRED_SECONDS, port=args.port)
    for p in [str(directory), *(d["directory"] for d in datasets.values())]:
        if re.search(r"[\s\"']", p):
            raise Refused("Current run_eval Preview requires paths without whitespace or quotes")
    directory.mkdir(parents=True, exist_ok=False)
    write(directory / "plan.json", plan)
    state = dict(format=FORMAT, phase="PREFLIGHT_PENDING", planSha256=sha(directory / "plan.json"),
                 runs=[dict(name=n, state="PENDING") for n in ORDER], preflights=[], events=[])
    save(directory, state)
    return {"phase": state["phase"], "plannedRealRounds": 9, "providerCalls": 0}


def external_config_files(repo):
    """Spring Boot's default external application/profile locations, including immediate config children."""
    repo = Path(repo)
    locations = [repo, repo / "config"]
    if (repo / "config").is_dir():
        locations += [p for p in (repo / "config").iterdir() if p.is_dir()]
    return sorted(p for location in locations if location.is_dir() for p in location.iterdir()
                  if p.is_file() and re.fullmatch(r"application(?:-[^.]+)?\.(?:yml|yaml|properties)", p.name))


def build_command(plan, side, dataset, output, dry):
    system, data = plan["systems"][side], plan["datasets"][dataset]
    command = ["pwsh", "-NoProfile", "-File", str(Path(system["repo"]) / "scripts/run_eval.ps1"),
               "-Preview", "-Split", "all", "-DatasetDir", data["directory"], "-OutputDir", str(output),
               "-SelfCorrection", "-ResultChecker", "-Port", str(plan["port"])]
    if data["javaSuiteManifest"]:
        command += ["-SuiteManifest", data["javaSuiteManifest"]]
    if dry:
        command.append("-DryRun")
    env = clean_environment()
    preview = checked_run(command, system["repo"], env=env, timeout=30).stdout.decode("utf-8-sig").strip()
    # Preview already forbids whitespace/quotes in paths. POSIX shlex would eat Windows backslashes.
    tokens = preview.split()
    if not tokens or any(not arg.startswith("--") or "api-key" in arg for arg in tokens):
        raise Refused("Unexpected Preview command")
    values = dict(arg[2:].split("=", 1) for arg in tokens)
    values.update({"agent.llm.base-url": "https://api.deepseek.com", "agent.llm.completions-path": "/v1/chat/completions",
                   "agent.eval.layers": "", "agent.retrieval.relation-decay": "0.4", "agent.retrieval.bridge-repair-enabled": "true",
                   "agent.retrieval.bridge-repair-max-tables": "2", "agent.prompt.include-data-profile": "true",
                   "agent.prompt.include-foreign-keys": "true", "agent.guard.limit-mode": "APPEND",
                   "agent.db.max-rows": "200", "agent.db.query-timeout-seconds": "10",
                   "spring.datasource.url": "jdbc:postgresql://localhost:5432/olist",
                   "spring.datasource.username": "text2sql_ro",
                   "agent.llm.input-price-per-1k": "0.001", "agent.llm.output-price-per-1k": "0.004",
                   "agent.llm.cache-hit-price-per-1k": "0.00002",
                   "spring.ai.model.chat": "none", "spring.ai.model.embedding": "none"})
    values["agent.llm.api-key"] = "stage2-v3-offline-not-real" if dry else "${AGENT_LLM_API_KEY}"
    return ["java", "-Duser.timezone=Asia/Shanghai", "-jar", system["jar"],
            *("--" + key + "=" + value for key, value in values.items())]


def clean_environment():
    # Preview and offline children never inherit model keys or external JVM/config injection.
    blocked = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "SPRING_APPLICATION_JSON"}
    return {k: v for k, v in os.environ.items() if k.upper() not in blocked
            and not k.upper().endswith(("_API_KEY", "_APIKEY")) and not k.upper().startswith("SPRING_CONFIG_")}


def config_environment_hash():
    return digest({k: v for k, v in clean_environment().items()
                   if k.upper().startswith(("AGENT_", "SPRING_", "SERVER_", "JDBC_", "DB_"))})


def real_key():
    # Called only after all preflight, fingerprints and whole-window gates pass.
    value = os.environ.get("AGENT_LLM_API_KEY", "").strip()
    if not value and os.name == "nt":
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as registry:
                value = winreg.QueryValueEx(registry, "AGENT_LLM_API_KEY")[0].strip()
        except FileNotFoundError:
            pass
    if not value:
        raise KeyUnavailable("No model key configured; no run started")
    return value


def launch(command, side, output, env):
    output.mkdir(exist_ok=False)
    with (output / "process.log").open("xb") as log:
        process = subprocess.run(command, cwd=side["repo"], env=env, stdout=log,
                                 stderr=subprocess.STDOUT, timeout=REQUIRED_SECONDS)
    return process.returncode


def report_files(directory):
    return {str(p.relative_to(directory)): sha(p) for p in sorted(Path(directory).rglob("*")) if p.is_file()}


def aggregate_run(report, ledger, dry, system, data, gold=None):
    errors = []
    require = lambda ok, message: None if ok else errors.append(message)
    meta, items, overall = report["meta"], report["itemEvidence"], report["overall"]
    require(len(items) == overall["total"] == meta["itemCount"] == 100, "denominator100")
    require(len({i["id"] for i in items}) == 100, "duplicate item identity")
    require(meta["gitCommit"] == system["runtimeHead"], "runtime HEAD")
    require(meta["promptVersion"] == system["promptVersion"], "prompt version")
    require(meta["datasetSha256"] == data["datasetSha256"], "dataset hash")
    require(meta["sampleIdsSha256"] == ids_hash(i["id"] for i in items), "sample IDs hash")
    if data.get("sampleIdsSha256"):
        require(meta["sampleIdsSha256"] == data["sampleIdsSha256"], "sealed sample IDs hash")
    require(meta["dryRun"] is dry and meta["split"] == "all" and meta["limit"] == 0 and not meta["layers"], "filtered/dry mode")
    require(meta["baseUrl"] == "https://api.deepseek.com" and meta["model"] == "deepseek-flash", "provider endpoint/model")
    require(meta["retrievalEnabled"] and meta["retrievalTopK"] == 8 and meta["joinHintsEnabled"], "retrieval")
    require(meta["includeDataProfile"] and meta["includeForeignKeys"] and meta["limitMode"] == "APPEND"
            and meta["maxRows"] == 200 and 0 < meta["schemaTableCount"] <= 37, "schema/profile/result configuration")
    require(meta["productionSelfCorrectionEnabled"] and meta["productionSelfCorrectionMaxAttempts"] == 2
            and not meta["selfCorrectionEnabled"], "retry contract")
    config = meta["effectiveConfig"]
    legacy = system.get("sourceCommit") == BASE_COMMIT
    legacy_unreported = {"explicitReasoningEnabled", "fewshotRequireAvailableTables", "metricDefinitionsSha256"}
    for k, v in CONFIG.items():
        if legacy and k in legacy_unreported and k not in config:
            continue
        require(config.get(k) == v, "configuration " + k)
    if not legacy or "metricDefinitionsSha256" in config:
        require(config.get("metricDefinitionsSha256") == system["metricDefinitionsSha256"], "metric definitions resource")
    if data["javaSuiteManifest"]:
        suite = config.get("evaluationSuite", {})
        require(suite.get("manifestSha256") == data["sealSha256"] and suite.get("declaredIndependentReview") is True
                and suite.get("exactOverlapCheckPassed") is True, "frozen suite admission")
    for key in ("questionSha256", "goldSqlSha256", "goldResultSha256"):
        require(all(re.fullmatch("[0-9a-f]{64}", i.get(key) or "") for i in items), "missing input/Gold hash")
    require(sum(i["correct"] for i in items) == overall["correct"], "item correctness counts")
    for category, field in (("byLayer", "difficulty"), ("byTableCount", "expectedTableCount")):
        if all(field in item for item in items):
            expected_groups = {}
            for item in items:
                label = str(item[field]) if field == "difficulty" else table_group(item[field])
                group = expected_groups.setdefault(label, {"total": 0, "correct": 0})
                group["total"] += 1
                group["correct"] += int(item["correct"])
            require({k: {f: v[f] for f in ("total", "correct")} for k, v in report[category].items()}
                    == expected_groups, "item group counts")
    if dry:
        require(overall["correct"] == 100 and all(i["correct"] for i in items)
                and not ledger and not any(i["calls"] for i in items), "offline Gold/model count")
        group = {"total": overall["total"], "correct": overall["correct"], "byLayer": report["byLayer"]}
    else:
        # Existing audit fixes the denominator and reconciles all attempts, known/unknown and late usage.
        detailed = audit_run(report, ledger, True, require)
        group = {k: detailed[k] for k in ("total", "correct", "sqlValid", "calls", "promptTokens", "completionTokens",
                  "totalTokens", "cacheHitTokens", "cacheMissTokens", "costYuan", "wallP50Ms", "wallP95Ms", "wallAvgMs",
                  "lateKnownCostYuan", "statusCounts", "byLayer", "byTableCount")}
        group.update(unknownUsageCount=len(detailed["unknownUsage"]), lateUsageCount=len(detailed["lateUsage"]),
                     boundedReturnViolationCount=len(detailed["boundedReturnViolations"]))
        known = [row for row in ledger if row.get("usageKnown")]
        group["fullKnownConfiguredCostYuan"] = sum(row["costYuan"] for row in known)
        group["fullKnownTokens"] = {k: sum(row[k] for row in known) for k in
                                    ("promptTokens", "completionTokens", "totalTokens", "cacheHitTokens", "cacheMissTokens")}
        known_keys = {(row["requestId"], row["callNumber"]) for row in known}
        all_keys = {(i["budget"]["requestId"], c["number"]) for i in items for c in i["calls"]}
        group["unresolvedUsageCalls"] = len(all_keys - known_keys)
        for row in ledger:
            require(row.get("configuredPromptVersion") == meta["promptVersion"], "ledger prompt")
            require(row.get("configuredReasoningEffort") == "provider-default", "ledger mode")
        group["timeoutCount"] = sum(i["status"] == "REQUEST_TIMEOUT" or
                                    (i.get("budget") or {}).get("termination") == "DEADLINE_EXCEEDED" for i in items)
        group["noFirstCallCount"] = sum(not i["calls"] for i in items)
        if gold:
            expected = {i["id"]: i for i in gold["itemEvidence"]}
            require(meta["sampleIdsSha256"] == gold["meta"]["sampleIdsSha256"], "sample IDs")
            for item in items:
                require(item["id"] in expected, "unknown sample")
                for key in ("questionSha256", "goldSqlSha256", "goldResultSha256"):
                    require(item.get(key) == expected.get(item["id"], {}).get(key), "input/Gold fingerprint")
    group["datasetSha256"] = meta["datasetSha256"]
    group["sampleIdsSha256"] = meta["sampleIdsSha256"]
    group["effectiveConfigSha256"] = digest(config)
    group["legacyUnreportedFields"] = sorted(legacy_unreported - set(config)) if legacy else []
    # Blind errors never include identities, SQL, questions, selections or slowest-item lists.
    if errors:
        raise Refused("Evidence audit failed: " + str(len(errors)) + " invariants; sha256=" + digest(errors))
    return group


def table_group(count):
    return "4+ 表" if count >= 4 else str(count) + " 表"


def analyze_output(output, dry, side, data, gold_path=None):
    paths = list(Path(output).glob("eval-*.json"))
    if len(paths) != 1:
        raise Refused("Missing or duplicate report; preserve incomplete run")
    report = read(paths[0])
    ledger = [json.loads(line) for p in sorted((Path(output) / "llm-calls").glob("*.jsonl"))
              for line in p.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    # These are explicit local refusal markers, not inferred from completion timestamps.
    log = (Path(output) / "process.log").read_text(encoding="utf-8", errors="replace")
    if dry and "结果集超过" in log:
        raise Refused("Gold preflight result was truncated; cannot establish a full reference")
    if not dry and (any(marker in log for marker in GATE_MARKERS)
                    or any(any(marker in json.dumps(item, ensure_ascii=False) for marker in GATE_MARKERS)
                           for item in report.get("itemEvidence", []))):
        raise Refused("Local admission refusal interrupted this run; not model accuracy")
    group = aggregate_run(report, ledger, dry, side, data, read(gold_path) if gold_path else None)
    return paths[0], group


def gold_identity(path):
    report = read(path)
    return {i["id"]: [i.get(k) for k in ("questionSha256", "goldSqlSha256", "goldResultSha256")]
            for i in report["itemEvidence"]}


def preflight(directory):
    with locked(directory):
        plan, state = load(directory)
        if state["phase"] != "PREFLIGHT_PENDING":
            raise Refused("Preflight already started/completed; no replacement")
        state["phase"] = "PREFLIGHTING"
        save(directory, state)
        try:
            verify_inputs(plan)
            for dataset in ("dev", "formal", "blind"):
                identities = []
                for side in ("B", "C"):
                    verify_inputs(plan)
                    name = "gold_" + dataset + "_" + side
                    output = Path(directory).resolve() / name
                    command = build_command(plan, side, dataset, output, True)
                    env = clean_environment()
                    env["AGENT_LLM_API_KEY"] = "stage2-v3-offline-not-real"
                    code = launch(command, plan["systems"][side], output, env)
                    if code:
                        raise Refused("Gold process failed; private log retained")
                    report, group = analyze_output(output, True, plan["systems"][side], plan["datasets"][dataset])
                    verify_inputs(plan)
                    identities.append(gold_identity(report))
                    state["preflights"].append(dict(name=name, side=side, dataset=dataset, report=str(report),
                                                       summary=group, files=report_files(output)))
                    save(directory, state)
                if identities[0] != identities[1]:
                    raise Refused("Baseline/candidate Gold input/result fingerprints differ")
            verify_inputs(plan)
            state["phase"] = "READY"
        except Exception as error:
            state["phase"] = "HALTED"
            state["haltReason"] = type(error).__name__ + ": " + str(error) if isinstance(error, Refused) else type(error).__name__
            save(directory, state)
            raise Refused(state["haltReason"]) from None
        save(directory, state)
        return {"phase": state["phase"], "goldPreflights": len(state["preflights"]), "providerCalls": 0}


def next_pending(state):
    validate_state(state)
    if state["phase"] != "READY":
        raise Refused("Batch not READY; halted, running or incomplete batches cannot replay")
    for run in state["runs"]:
        if run["state"] == "COMPLETE":
            continue
        if run["state"] != "PENDING":
            raise Refused("A prior run started; never replay or silently fill an interrupted round")
        return run
    return None


def verify_archives(directory, state):
    for entry in [*state["preflights"], *(r for r in state["runs"] if r["state"] == "COMPLETE")]:
        output = Path(directory) / entry["name"]
        if report_files(output) != entry["files"]:
            raise Refused("Archived run evidence drift")
        reports = list(output.glob("eval-*.json"))
        if len(reports) != 1 or Path(entry["report"]).resolve() != reports[0].resolve():
            raise Refused("Archived report path escaped its registered round")


def run_next(directory):
    with locked(directory):
        plan, state = load(directory)
        run = next_pending(state)
        if run is None:
            raise Refused("No unstarted round remains")
        try:
            audit(directory, live=False)
            verify_inputs(plan)
            admission = window(now())
            if not admission["allowed"]:
                state["waiting"] = admission
                save(directory, state)
                return {"phase": "WAITING", "nextUnstarted": run["name"], **admission}
            name = run["name"]
            side, dataset = identity(name)
            output = Path(directory).resolve() / name
            if output.exists():
                raise Refused("Round output exists without completion; cannot replay")
            command = build_command(plan, side, dataset, output, False)
            # The last gate occurs after Preview/DB work and before resolving a real secret.
            admission = window(now())
            if not admission["allowed"]:
                state["waiting"] = admission
                save(directory, state)
                return {"phase": "WAITING", "nextUnstarted": name, **admission}
            env = clean_environment()
            env["AGENT_LLM_API_KEY"] = real_key()
            admission = window(now())
            if not admission["allowed"]:
                state["waiting"] = admission
                save(directory, state)
                return {"phase": "WAITING", "nextUnstarted": name, **admission}
            run.update(state="STARTED", startedAt=stamp(), admission=admission)
            state["phase"] = "RUNNING"
            state.pop("waiting", None)
            save(directory, state)  # Durable before any paid process. Crash cannot cause replay.
            code = launch(command, plan["systems"][side], output, env)
            run["exitCode"] = code
            if code:
                raise Refused("Paid process interrupted/failed; private log retained")
            verify_inputs(plan)
            gold = next(p for p in state["preflights"] if p["side"] == side and p["dataset"] == dataset)
            report, group = analyze_output(output, False, plan["systems"][side], plan["datasets"][dataset], gold["report"])
            run.update(state="COMPLETE", completedAt=stamp(), report=str(report), summary=group, files=report_files(output))
            state["phase"] = "READY"
            state.update(gates({r["name"]: r["summary"] for r in state["runs"] if r["state"] == "COMPLETE"}))
            if name == "DEV_C":
                if not state["developmentGate"]["passed"]:
                    state.update(phase="HALTED", haltReason="DEVELOPMENT_PROMOTION_FAILED")
            if name == "U_C":
                state["phase"] = "COMPLETE"
            save(directory, state)
            audit(directory, live=False)
            return {"phase": state["phase"], "completed": name, "summary": group,
                    "developmentGate": state.get("developmentGate"), "formalGate": state.get("formalGate"),
                    "blindGate": state.get("blindGate")}
        except Exception as error:
            if isinstance(error, KeyUnavailable) and run["state"] == "PENDING":
                state["phase"] = "READY"
                save(directory, state)
                raise error
            if run["state"] == "STARTED":
                run.update(state="INTERRUPTED", interruptedAt=stamp())
            state.update(phase="HALTED", haltReason=str(error) if isinstance(error, Refused) else type(error).__name__)
            save(directory, state)
            raise Refused(state["haltReason"]) from None


def audit(directory, live=True):
    plan, state = load(directory)
    verify_archives(directory, state)
    if live:
        verify_inputs(plan)
    groups, golds, configurations, prompts = {}, {}, {}, {}
    for entry in state["preflights"]:
        side, dataset = entry["side"], entry["dataset"]
        report, group = analyze_output(Path(directory) / entry["name"], True, plan["systems"][side], plan["datasets"][dataset])
        if group != entry["summary"]:
            raise Refused("Gold preflight summary drift")
        golds[(side, dataset)] = gold_identity(report)
        configurations[(side, dataset)] = group["effectiveConfigSha256"]
    for dataset in ("dev", "formal", "blind"):
        if ("B", dataset) in golds and ("C", dataset) in golds and golds[("B", dataset)] != golds[("C", dataset)]:
            raise Refused("Baseline/candidate Gold fingerprints differ")
    previous_completion = None
    for entry in state["runs"]:
        if entry["state"] != "COMPLETE":
            continue
        name = entry["name"]
        side, dataset = identity(name)
        gold = next(p for p in state["preflights"] if p["side"] == side and p["dataset"] == dataset)
        report, group = analyze_output(Path(directory) / name, False, plan["systems"][side], plan["datasets"][dataset], gold["report"])
        if group != entry["summary"]:
            raise Refused("Archived summary mismatch")
        if group["effectiveConfigSha256"] != configurations[(side, dataset)]:
            raise Refused("Runtime configuration differs from Gold preflight")
        admission = entry["admission"]
        checked = datetime.fromisoformat(admission["checkedAt"])
        started, completed = datetime.fromisoformat(entry["startedAt"]), datetime.fromisoformat(entry["completedAt"])
        if (window(checked) != admission or not admission["allowed"] or started < checked or completed < started
                or (previous_completion is not None and started < previous_completion)):
            raise Refused("Round admission/order evidence drift")
        previous_completion = completed
        # Compare only the first call: retries legitimately include different failure feedback.
        # A completion timestamp is never read for send-time admission.
        for item in read(report)["itemEvidence"]:
            if item["calls"]:
                key = (side, dataset, item["id"])
                value = item["calls"][0]["promptSha256"]
                if prompts.setdefault(key, value) != value:
                    raise Refused("Same-system first prompt drift across repeated rounds")
        groups[name] = group
    expected_gates = gates(groups)
    if any(state.get(k) != expected_gates.get(k) for k in ("developmentGate", "formalGate", "blindGate")):
        raise Refused("Stored gate differs from recomputed evidence")
    if "developmentGate" in expected_gates and not expected_gates["developmentGate"]["passed"]:
        if state["phase"] != "HALTED" or any(r["state"] != "PENDING" for r in state["runs"][1:]):
            raise Refused("Development promotion failed but later rounds admitted")
    return dict(format="stage2-v3-summary-v1", phase=state["phase"], passedEvidenceAudit=True,
                complete=state["phase"] == "COMPLETE", groups=groups, developmentGate=state.get("developmentGate"),
                formalGate=state.get("formalGate"), blindGate=state.get("blindGate"),
                blindDetailsReleased=False, completionTimestampsAreSendEvidence=False,
                comparisonGatesPassed=state["phase"] == "COMPLETE" and all(g["passed"] for g in expected_gates.values()),
                limits=["Configured cost is a lower bound when usage is unknown, not a supplier invoice.",
                        "Local gate refusals are not provider billable calls.",
                        "Shared filesystem independence is declared workflow provenance, not OS isolation."])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("prepare")
    p.add_argument("--directory", required=True)
    p.add_argument("--candidate-repo", default=str(ROOT))
    p.add_argument("--baseline-repo", default="C:/Users/xunqian/.codex/worktrees/stage2-v3-baseline/agent")
    p.add_argument("--candidate-jar")
    p.add_argument("--baseline-jar")
    p.add_argument("--port", type=int, default=18080)
    for kind in ("dev", "formal", "blind"):
        p.add_argument("--" + kind + "-dir", required=True)
        p.add_argument("--" + kind + "-seal", required=True)
    for name in ("preflight", "run-next", "audit"):
        sub = commands.add_parser(name)
        sub.add_argument("directory")
        if name == "audit":
            sub.add_argument("--evidence-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args)
        elif args.command == "preflight":
            result = preflight(args.directory)
        elif args.command == "run-next":
            result = run_next(args.directory)
        else:
            result = audit(args.directory, not args.evidence_only)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (Refused, OSError, subprocess.SubprocessError, ValueError, KeyError) as error:
        # Subprocess stdout/stderr can contain blind material or environment data: never echo it.
        print(json.dumps({"refused": True, "reason": str(error) if isinstance(error, Refused) else type(error).__name__},
                         ensure_ascii=False), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
