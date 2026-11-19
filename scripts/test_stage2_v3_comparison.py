"""Offline safety regression. Synthetic bytes/reports only: no keys, Docker, Java or API."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_stage2_v3_comparison as ctl


def at(value):
    return datetime.fromisoformat(value).replace(tzinfo=ctl.BEIJING)


GOOD_TIME = at("2026-11-19T18:01:00")
PEAK_TIME = at("2026-11-19T10:00:00")


def reports(dry=False, correct=100):
    config = dict(ctl.CONFIG, metricDefinitionsSha256="a" * 64,
                  resultCheckerPolicyVersion="synthetic", fewshotSelectionPolicy="synthetic")
    system = dict(runtimeHead="runtime-head", promptVersion="synthetic", metricDefinitionsSha256="a" * 64)
    data = dict(datasetSha256="b" * 64, javaSuiteManifest="", sampleIdsSha256=None)
    items, ledger, by_layer = [], [], {}
    for i in range(100):
        layer = "multi_join_deep" if i < 10 else "business_semantics" if i < 23 else "single_table_basic"
        okay = i < correct
        usage = dict(promptTokens=100, completionTokens=20, totalTokens=120, cacheHitTokens=40,
                     cacheMissTokens=60, costYuan=(60 * .001 + 40 * .00002 + 20 * .004) / 1000)
        call = dict(number=1, promptSha256="c" * 64, status="SUCCESS", usage=usage)
        item = dict(id="HIDDEN_ID_" + str(i), difficulty=layer, questionSha256="d" * 64,
                    goldSqlSha256="e" * 64, goldResultSha256="f" * 64, status="SUCCESS",
                    finalSql="HIDDEN_SQL", correct=okay, wallLatencyMs=100,
                    timings=dict(totalMs=100), totalUsage=None if dry else usage, retryTrigger="NONE",
                    selectedAttempt=1, calls=[] if dry else [call], productionAttempts=[] if dry else [
                        dict(number=1, stage="GENERATE", status="SUCCESS", sql="HIDDEN_SQL", correct=okay)],
                    budget=None if dry else dict(requestId="r" + str(i), timeoutMs=15000,
                        maxModelCalls=2, modelCalls=1, unknownUsageCalls=0, elapsedMs=100,
                        termination="COMPLETE", stage="COMPLETE", cancellationRejected=0))
        items.append(item)
        if not dry:
            ledger.append(dict(requestId="r" + str(i), callNumber=1, status="SUCCESS", promptSha256="c" * 64,
                usageKnown=True, prompt=None, response=None, timestamp="2026-11-19T10:00:00+08:00",
                configuredPromptVersion="synthetic", configuredReasoningEffort="provider-default", **usage))
        group = by_layer.setdefault(layer, dict(total=0, correct=0))
        group["total"] += 1
        group["correct"] += int(okay)
    report = dict(meta=dict(gitCommit=system["runtimeHead"], promptVersion="synthetic", datasetSha256="b" * 64,
        sampleIdsSha256=ctl.ids_hash(i["id"] for i in items), dryRun=dry, split="all", limit=0, layers=[],
        baseUrl="https://api.deepseek.com", model="deepseek-flash", retrievalEnabled=True, retrievalTopK=8,
        joinHintsEnabled=True, includeDataProfile=True, includeForeignKeys=True, limitMode="APPEND", maxRows=200,
        schemaTableCount=37, productionSelfCorrectionEnabled=True, productionSelfCorrectionMaxAttempts=2,
        selfCorrectionEnabled=False, effectiveConfig=config, itemCount=100), itemEvidence=items,
        overall=dict(total=100, correct=correct, sqlValid=100,
            totalCostYuan=0 if dry else sum(r["costYuan"] for r in ledger), avgPromptTokens=0 if dry else 100),
        byLayer=by_layer, byTableCount={"1 表": dict(total=100, correct=correct)}, failures=["HIDDEN_FAILURE"])
    return report, ledger, system, data


def valid_plan():
    _, _, system, data = reports()
    systems = {side: dict(system, repo="synthetic-" + side, sourceCommit=commit, promptVersion=prompt)
               for side, commit, prompt in (("B", ctl.BASE_COMMIT, "p9-mdl-alignment-v9"),
                                            ("C", ctl.CANDIDATE_SOURCE, "p21-period-ranking-v21"))}
    return dict(format=ctl.FORMAT, order=ctl.ORDER, requiredWholeRoundSeconds=ctl.REQUIRED_SECONDS,
                systems=systems, datasets={k: dict(data) for k in ("dev", "formal", "blind")}, port=18080)


def state_for(directory, phase="READY"):
    plan = valid_plan()
    ctl.write(directory / "plan.json", plan)
    state = dict(format=ctl.FORMAT, phase=phase, planSha256=ctl.sha(directory / "plan.json"),
                 runs=[dict(name=n, state="PENDING") for n in ctl.ORDER], preflights=[], events=[])
    if phase != "PREFLIGHT_PENDING":
        for dataset, side in ctl.PREFLIGHT_ORDER:
            name = "gold_" + dataset + "_" + side
            state["preflights"].append(dict(name=name, side=side, dataset=dataset, report="synthetic-report"))
    ctl.save(directory, state)
    return plan, state


def pass_group(correct=71):
    return dict(total=100, correct=correct, timeoutCount=9, byLayer={
        "multi_join_deep": dict(total=10, correct=4), "business_semantics": dict(total=13, correct=6)})


class Windows(unittest.TestCase):
    def test_peak_boundaries(self):
        for value, allowed in (("08:31:00", True), ("08:31:00.001", False), ("09:00:00", False),
                               ("11:59:59", False), ("12:00:00", True), ("13:31:00", True),
                               ("13:31:00.001", False), ("14:00:00", False), ("17:59:59", False),
                               ("18:00:00", True)):
            with self.subTest(value=value):
                self.assertEqual(ctl.window(at("2026-11-19T" + value))["allowed"], allowed)

    def test_weekend_and_utc_conversion(self):
        self.assertTrue(ctl.window(at("2026-11-21T10:00:00"))["allowed"])
        self.assertTrue(ctl.window(at("2026-11-22T15:00:00"))["allowed"])
        self.assertFalse(ctl.window(datetime(2026, 11, 19, 2, tzinfo=timezone.utc))["allowed"])
        self.assertEqual(ctl.window(at("2026-11-19T08:31:00"))["remainingSeconds"], 1740)

    def test_naive_clock_rejected(self):
        with self.assertRaises(ctl.Refused):
            ctl.window(datetime(2026, 11, 19))


class Gates(unittest.TestCase):
    def test_exact_development_thresholds(self):
        self.assertTrue(ctl.promotion(pass_group())["passed"])

    def test_each_development_failure(self):
        failures = []
        for key, value in (("correct", 70), ("total", 99), ("timeoutCount", 10)):
            group = pass_group()
            group[key] = value
            failures.append(group)
        for layer, key, value in (("multi_join_deep", "correct", 3), ("multi_join_deep", "total", 9),
                                  ("business_semantics", "correct", 5), ("business_semantics", "total", 12)):
            group = pass_group()
            group["byLayer"][layer][key] = value
            failures.append(group)
        for group in failures:
            self.assertFalse(ctl.promotion(group)["passed"])

    def test_formal_and_blind_thresholds(self):
        groups = {"DEV_C": pass_group(), **{n: {"correct": 91 if n.startswith("B") else 89} for n in ctl.FORMAL},
                  "U_B": {"correct": 80}, "U_C": {"correct": 78}}
        self.assertTrue(all(g["passed"] for g in ctl.gates(groups).values()))
        groups["C3"]["correct"] = 88
        groups["U_C"]["correct"] = 77
        self.assertFalse(ctl.gates(groups)["formalGate"]["passed"])
        self.assertFalse(ctl.gates(groups)["blindGate"]["passed"])


class Evidence(unittest.TestCase):
    def test_real_summary_redacted_and_completion_time_ignored(self):
        report, ledger, system, data = reports()
        group = ctl.aggregate_run(report, ledger, False, system, data)
        self.assertEqual(group["calls"], 100)
        self.assertEqual(group["unresolvedUsageCalls"], 0)
        self.assertNotIn("HIDDEN", json.dumps(group))
        # These synthetic completion timestamps are in peak hours; no send-time claim follows.
        for row in ledger:
            row["timestamp"] = "unparseable completion timestamp"
        self.assertEqual(group, ctl.aggregate_run(report, ledger, False, system, data))

    def test_gold_zero_provider_calls(self):
        report, ledger, system, data = reports(True)
        self.assertEqual(ctl.aggregate_run(report, ledger, True, system, data)["correct"], 100)
        report["itemEvidence"][0]["calls"] = [dict(number=1)]
        with self.assertRaises(ctl.Refused):
            ctl.aggregate_run(report, ledger, True, system, data)

    def test_fingerprint_configuration_and_group_drift(self):
        for mutate in (lambda r: r["meta"].update(datasetSha256="changed"),
                       lambda r: r["meta"]["effectiveConfig"].update(metricDefinitionsSha256="changed"),
                       lambda r: r["meta"].update(maxRows=201),
                       lambda r: r["meta"]["effectiveConfig"].update(requestTimeoutMs=16000),
                       lambda r: r["byLayer"]["multi_join_deep"].update(correct=9)):
            report, ledger, system, data = reports()
            mutate(report)
            with self.assertRaises(ctl.Refused):
                ctl.aggregate_run(report, ledger, False, system, data)

    def test_timeout_includes_success_fallback_and_deduplicates(self):
        report, ledger, system, data = reports()
        for item in report["itemEvidence"][:2]:
            item["budget"].update(termination="DEADLINE_EXCEEDED")
            item["productionAttempts"].append(dict(number=2, stage="REQUEST_STOP", status="REQUEST_TIMEOUT",
                                                    sql=None, correct=False))
        item = report["itemEvidence"][1]
        item.update(status="REQUEST_TIMEOUT", finalSql=None, correct=False, selectedAttempt=2)
        report["overall"].update(correct=99, sqlValid=99)
        report["byLayer"]["multi_join_deep"]["correct"] -= 1
        report["byTableCount"]["1 表"]["correct"] -= 1
        self.assertEqual(ctl.aggregate_run(report, ledger, False, system, data)["timeoutCount"], 2)

    def test_late_usage_reconciled_without_double_cost(self):
        report, ledger, system, data = reports()
        item, row = report["itemEvidence"][0], ledger[0]
        previous = row["costYuan"]
        item["calls"][0]["usage"] = None
        item["budget"]["unknownUsageCalls"] = 1
        item["totalUsage"] = None
        row["status"] = "LATE_SUCCESS"
        report["overall"]["totalCostYuan"] -= previous
        report["overall"]["avgPromptTokens"] -= 1
        group = ctl.aggregate_run(report, ledger, False, system, data)
        self.assertEqual(group["lateUsageCount"], 1)
        self.assertEqual(group["unknownUsageCount"], 1)
        self.assertEqual(group["unresolvedUsageCalls"], 0)
        self.assertAlmostEqual(group["fullKnownConfiguredCostYuan"], group["costYuan"] + previous)

    def test_missing_late_usage_remains_unknown(self):
        report, ledger, system, data = reports()
        item, row = report["itemEvidence"][0], ledger.pop(0)
        item["calls"][0]["usage"] = None
        item["budget"]["unknownUsageCalls"] = 1
        item["totalUsage"] = None
        report["overall"]["totalCostYuan"] -= row["costYuan"]
        report["overall"]["avgPromptTokens"] -= 1
        self.assertEqual(ctl.aggregate_run(report, ledger, False, system, data)["unresolvedUsageCalls"], 1)

    def test_failed_audit_does_not_disclose_id(self):
        report, ledger, system, data = reports()
        ledger[0]["promptSha256"] = "changed"
        with self.assertRaises(ctl.Refused) as caught:
            ctl.aggregate_run(report, ledger, False, system, data)
        self.assertNotIn("HIDDEN", str(caught.exception))


class Registry(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self.tmp.name)
        self.plan, self.state = state_for(self.directory)

    def tearDown(self):
        self.tmp.cleanup()

    def test_existing_prepare_directory_rejected_without_other_work(self):
        with patch.object(ctl, "seal_dataset") as seal:
            with self.assertRaises(ctl.Refused):
                ctl.prepare(type("Args", (), {"directory": self.directory})())
            seal.assert_not_called()

    def test_started_interrupted_and_out_of_order_cannot_replay(self):
        for phase, status in (("READY", "STARTED"), ("HALTED", "INTERRUPTED"), ("RUNNING", "STARTED")):
            state = deepcopy(self.state)
            state.update(phase=phase, haltReason="synthetic")
            state["runs"][0]["state"] = status
            with self.assertRaises(ctl.Refused):
                ctl.next_pending(state)
        state = deepcopy(self.state)
        state["runs"][1]["state"] = "COMPLETE"
        with self.assertRaises(ctl.Refused):
            ctl.validate_state(state)

    def test_missing_gold_preflight_rejected(self):
        self.state["preflights"].pop()
        with self.assertRaises(ctl.Refused):
            ctl.validate_state(self.state)

    def test_plan_drift_rejected(self):
        plan_path = self.directory / "plan.json"
        plan_path.write_text(plan_path.read_text(encoding="utf-8") + " ", encoding="utf-8")
        with self.assertRaises(ctl.Refused):
            ctl.load(self.directory)

    def test_lock_is_exclusive(self):
        with ctl.locked(self.directory):
            with self.assertRaises(ctl.Refused):
                with ctl.locked(self.directory):
                    self.fail("second lock entered")
        self.assertFalse((self.directory / ".controller.lock").exists())

    def run_mocked(self, group=None, clock=GOOD_TIME, launch_error=None):
        group = pass_group() if group is None else group
        self.patches = [patch.object(ctl, "audit", return_value={}), patch.object(ctl, "verify_inputs"),
            patch.object(ctl, "now", return_value=clock), patch.object(ctl, "build_command", return_value=["NO-EXEC"]),
            patch.object(ctl, "real_key", return_value="SYNTHETIC-NOT-REAL"),
            patch.object(ctl, "launch", side_effect=launch_error, return_value=0),
            patch.object(ctl, "analyze_output", return_value=(self.directory / "synthetic.json", group)),
            patch.object(ctl, "report_files", return_value={})]
        mocks = [p.start() for p in self.patches]
        try:
            result = ctl.run_next(self.directory)
            return result, mocks
        finally:
            for p in reversed(self.patches):
                p.stop()

    def test_peak_never_resolves_key_or_launches_and_resumes_same_unstarted(self):
        result, mocks = self.run_mocked(clock=PEAK_TIME)
        self.assertEqual(result["phase"], "WAITING")
        self.assertEqual(result["nextUnstarted"], "DEV_C")
        mocks[3].assert_not_called()
        mocks[4].assert_not_called()
        mocks[5].assert_not_called()
        self.assertEqual(ctl.read(self.directory / "state.json")["runs"][0]["state"], "PENDING")
        result, _ = self.run_mocked()
        self.assertEqual(result["phase"], "READY")
        self.assertEqual(ctl.next_pending(ctl.read(self.directory / "state.json"))["name"], "B1")

    def test_window_closing_during_preview_does_not_resolve_key(self):
        with patch.object(ctl, "audit"), patch.object(ctl, "verify_inputs"), \
                patch.object(ctl, "now", side_effect=[GOOD_TIME, PEAK_TIME]), \
                patch.object(ctl, "build_command", return_value=["NO-EXEC"]), \
                patch.object(ctl, "real_key") as key, patch.object(ctl, "launch") as launch:
            self.assertEqual(ctl.run_next(self.directory)["phase"], "WAITING")
            key.assert_not_called()
            launch.assert_not_called()

    def test_missing_key_does_not_start_or_poison_unstarted_round(self):
        with patch.object(ctl, "audit"), patch.object(ctl, "verify_inputs"), \
                patch.object(ctl, "now", return_value=GOOD_TIME), \
                patch.object(ctl, "build_command", return_value=["NO-EXEC"]), \
                patch.object(ctl, "real_key", side_effect=ctl.KeyUnavailable("synthetic missing key")), \
                patch.object(ctl, "launch") as launch:
            with self.assertRaises(ctl.KeyUnavailable):
                ctl.run_next(self.directory)
            launch.assert_not_called()
        state = ctl.read(self.directory / "state.json")
        self.assertEqual(state["phase"], "READY")
        self.assertEqual(ctl.next_pending(state)["name"], "DEV_C")

    def test_dev_failure_halts_and_cannot_admit_formal(self):
        result, _ = self.run_mocked(group=pass_group(correct=70))
        self.assertEqual(result["phase"], "HALTED")
        state = ctl.read(self.directory / "state.json")
        self.assertTrue(all(r["state"] == "PENDING" for r in state["runs"][1:]))
        with self.assertRaises(ctl.Refused):
            ctl.next_pending(state)

    def test_formal_failure_still_admits_blind(self):
        for run in self.state["runs"][:6]:
            run.update(state="COMPLETE", summary=pass_group() if run["name"] == "DEV_C" else {"correct": 80})
        self.state.update(ctl.gates({r["name"]: r["summary"] for r in self.state["runs"][:6]}))
        ctl.save(self.directory, self.state)
        result, _ = self.run_mocked(group={"correct": 80})
        self.assertFalse(result["formalGate"]["passed"])
        self.assertEqual(result["phase"], "READY")
        self.assertEqual(ctl.next_pending(ctl.read(self.directory / "state.json"))["name"], "U_B")

    def test_interruption_durable_and_no_replay(self):
        with self.assertRaises(ctl.Refused):
            self.run_mocked(launch_error=TimeoutError("synthetic"))
        state = ctl.read(self.directory / "state.json")
        self.assertEqual(state["runs"][0]["state"], "INTERRUPTED")
        self.assertEqual(state["phase"], "HALTED")
        with self.assertRaises(ctl.Refused):
            ctl.next_pending(state)

    def test_started_state_durable_before_launch(self):
        def launch(*_):
            state = ctl.read(self.directory / "state.json")
            self.assertEqual(state["phase"], "RUNNING")
            self.assertEqual(state["runs"][0]["state"], "STARTED")
            return 0
        with patch.object(ctl, "audit"), patch.object(ctl, "verify_inputs"), \
                patch.object(ctl, "now", return_value=GOOD_TIME), \
                patch.object(ctl, "build_command", return_value=["NO-EXEC"]), \
                patch.object(ctl, "real_key", return_value="SYNTHETIC-NOT-REAL"), \
                patch.object(ctl, "launch", side_effect=launch), \
                patch.object(ctl, "analyze_output", return_value=(self.directory / "synthetic.json", pass_group())), \
                patch.object(ctl, "report_files", return_value={}):
            ctl.run_next(self.directory)


class FrozenInputs(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def fake_dataset(self, state="REFERENCE_FROZEN_NOT_EVALUATION_ADMITTED", reviewed=True):
        directory = self.root / "dataset"
        directory.mkdir()
        for n in range(6):
            (directory / ("T" + str(n+1) + "_synthetic.yaml")).write_bytes(b"not even parseable YAML: [")
        seal = directory / "reference-seal.json"
        ctl.write(seal, dict(format="reviewed-reference-v3-seal-v1", state=state, frozenAt="synthetic",
            independentReview=reviewed, itemCount=100, datasetSha256=ctl.dataset_hash(directory)))
        return directory, seal

    def test_opaque_bytes_no_yaml_parsing(self):
        directory, seal = self.fake_dataset()
        self.assertEqual(len(ctl.seal_dataset(directory, seal)["files"]), 6)

    def test_unreviewed_author_and_not_frozen_seals_rejected(self):
        directory, seal = self.fake_dataset(state="NOT_FROZEN", reviewed=False)
        for state, reviewed in (("NOT_FROZEN", True), ("FROZEN", False), ("AUTHOR_FROZEN", True)):
            value = ctl.read(seal)
            value.update(state=state, independentReview=reviewed)
            ctl.write(seal, value)
            with self.assertRaises(ctl.Refused):
                ctl.seal_dataset(directory, seal)

    def test_jar_source_dataset_and_seal_fingerprint_drift(self):
        repo = self.root / "repo"
        resource = repo / "src/main/resource"
        resource.parent.mkdir(parents=True)
        resource.write_bytes(b"source")
        jar = repo / "frozen.jar"
        jar.write_bytes(b"jar")
        directory, seal = self.fake_dataset()
        data = ctl.seal_dataset(directory, seal)
        plan = dict(systems={"C": dict(repo=str(repo), runtimeHead="runtime-head",
                    files={str(p): ctl.sha(p) for p in (resource, jar)})}, controllerFiles={}, datasets={"dev": data},
                    configEnvironmentSha256=ctl.config_environment_hash())
        with patch.object(ctl, "git", return_value=b"runtime-head"):
            ctl.verify_inputs(plan, False)
            for path in (resource, jar, directory / "T1_synthetic.yaml", seal):
                content = path.read_bytes()
                path.write_bytes(content + b"DRIFT")
                with self.assertRaises(ctl.Refused):
                    ctl.verify_inputs(plan, False)
                path.write_bytes(content)
            extra = resource.parent / "extra-resource"
            extra.write_bytes(b"extra")
            with self.assertRaises(ctl.Refused):
                ctl.verify_inputs(plan, False)

    def test_no_real_keys_or_jvm_injection_in_children(self):
        values = dict(AGENT_LLM_API_KEY="DO-NOT-INHERIT", OPENAI_API_KEY="DO-NOT-INHERIT",
                      JAVA_TOOL_OPTIONS="DO-NOT-INHERIT", SPRING_CONFIG_IMPORT="DO-NOT-INHERIT",
                      AGENT_DB_USERNAME="readonly")
        with patch.dict(os.environ, values):
            clean = ctl.clean_environment()
            self.assertEqual(clean["AGENT_DB_USERNAME"], "readonly")
            self.assertTrue(all(k not in clean for k in values if k != "AGENT_DB_USERNAME"))

    def test_preview_preserves_windows_paths_and_has_no_real_secret(self):
        plan = valid_plan()
        plan["systems"]["C"].update(jar=r"C:\synthetic\frozen.jar")
        plan["datasets"]["formal"]["directory"] = r"C:\synthetic\data"
        preview = type("Process", (), {"stdout":
            b"--agent.eval.dir=C:\\synthetic\\data --agent.eval.out-dir=C:\\synthetic\\output"})()
        with patch.object(ctl, "checked_run", return_value=preview), \
                patch.object(ctl, "real_key") as key:
            command = ctl.build_command(plan, "C", "formal", Path(r"C:\synthetic\output"), False)
            self.assertIn(r"--agent.eval.dir=C:\synthetic\data", command)
            self.assertIn("--agent.llm.api-key=${AGENT_LLM_API_KEY}", command)
            self.assertIn("--agent.llm.input-price-per-1k=0.001", command)
            key.assert_not_called()


class ArchivedAudit(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self.tmp.name)
        self.plan, self.state = state_for(self.directory)
        self.state["preflights"] = []
        for dataset, side in ctl.PREFLIGHT_ORDER:
            name = "gold_" + dataset + "_" + side
            entry = self.archive(name, side, dataset, True)
            entry.update(side=side, dataset=dataset)
            self.state["preflights"].append(entry)
        for index, run in enumerate(self.state["runs"]):
            side, dataset = ctl.identity(run["name"])
            run.update(self.archive(run["name"], side, dataset, False), state="COMPLETE",
                admission=ctl.window(GOOD_TIME + timedelta(seconds=index * 3)),
                startedAt=(GOOD_TIME + timedelta(seconds=index * 3, milliseconds=100)).isoformat(),
                completedAt=(GOOD_TIME + timedelta(seconds=index * 3 + 1)).isoformat())
        self.state.update(phase="COMPLETE", **ctl.gates({r["name"]: r["summary"] for r in self.state["runs"]}))
        ctl.save(self.directory, self.state)

    def tearDown(self):
        self.tmp.cleanup()

    def archive(self, name, side, dataset, dry):
        report, ledger, _, _ = reports(dry)
        report["meta"]["promptVersion"] = self.plan["systems"][side]["promptVersion"]
        for row in ledger:
            row["configuredPromptVersion"] = report["meta"]["promptVersion"]
        output = self.directory / name
        output.mkdir()
        path = output / "eval-synthetic.json"
        ctl.write(path, report)
        (output / "process.log").write_text("synthetic offline evidence", encoding="utf-8")
        (output / "llm-calls").mkdir()
        if ledger:
            (output / "llm-calls/calls.jsonl").write_text("\n".join(json.dumps(r) for r in ledger), encoding="utf-8")
        group = ctl.aggregate_run(report, ledger, dry, self.plan["systems"][side], self.plan["datasets"][dataset])
        return dict(name=name, report=str(path), summary=group, files=ctl.report_files(output))

    def rewrite_archive(self, name, mutate_report, mutate_ledger=lambda _: None):
        entry = next(r for r in [*self.state["preflights"], *self.state["runs"]] if r["name"] == name)
        report = ctl.read(entry["report"])
        mutate_report(report)
        ctl.write(entry["report"], report)
        ledger_path = self.directory / name / "llm-calls/calls.jsonl"
        if ledger_path.exists():
            ledger = [json.loads(line) for line in ledger_path.read_text(encoding="utf-8").splitlines()]
            mutate_ledger(ledger)
            ledger_path.write_text("\n".join(json.dumps(r) for r in ledger), encoding="utf-8")
        entry["files"] = ctl.report_files(self.directory / name)
        ctl.save(self.directory, self.state)

    def test_complete_archive_recomputes_gates_without_live_dependencies(self):
        with patch.object(ctl, "verify_inputs") as verify, patch.object(ctl, "real_key") as key:
            summary = ctl.audit(self.directory, live=False)
            self.assertTrue(summary["complete"])
            self.assertTrue(summary["comparisonGatesPassed"])
            self.assertEqual(len(summary["groups"]), 9)
            self.assertNotIn("HIDDEN", json.dumps(summary))
            self.assertFalse(summary["completionTimestampsAreSendEvidence"])
            verify.assert_not_called()
            key.assert_not_called()

    def test_stored_gate_tamper_rejected(self):
        self.state["formalGate"]["candidateMeanPercent"] = 1
        ctl.save(self.directory, self.state)
        with self.assertRaisesRegex(ctl.Refused, "recomputed"):
            ctl.audit(self.directory, False)

    def test_same_system_first_prompt_drift_rejected(self):
        self.rewrite_archive("C2", lambda r: r["itemEvidence"][0]["calls"][0].update(promptSha256="1" * 64),
                             lambda rows: rows[0].update(promptSha256="1" * 64))
        with self.assertRaisesRegex(ctl.Refused, "first prompt drift"):
            ctl.audit(self.directory, False)

    def test_report_bytes_drift_rejected(self):
        with (self.directory / "U_B/process.log").open("a", encoding="utf-8") as stream:
            stream.write("drift")
        with self.assertRaisesRegex(ctl.Refused, "evidence drift"):
            ctl.audit(self.directory, False)

    def test_cross_system_gold_drift_rejected(self):
        self.rewrite_archive("gold_blind_C", lambda r: r["itemEvidence"][0].update(goldResultSha256="2" * 64))
        with self.assertRaisesRegex(ctl.Refused, "Gold fingerprints"):
            ctl.audit(self.directory, False)

    def test_local_gate_refusal_not_model_error(self):
        output = self.directory / "U_C"
        (output / "process.log").write_text("CALL_FAILED: PEAK_HOURS", encoding="utf-8")
        with self.assertRaisesRegex(ctl.Refused, "not model accuracy"):
            ctl.analyze_output(output, False, self.plan["systems"]["C"], self.plan["datasets"]["blind"])

    def test_full_gold_cannot_be_established_by_truncated_self_match(self):
        output = self.directory / "gold_blind_C"
        (output / "process.log").write_text("WARN 结果集超过 200 行，已截断", encoding="utf-8")
        with self.assertRaisesRegex(ctl.Refused, "truncated"):
            ctl.analyze_output(output, True, self.plan["systems"]["C"], self.plan["datasets"]["blind"])

    def test_schema_count_is_retrieved_context_not_full_database(self):
        report, ledger, system, data = reports(True)
        report["meta"]["schemaTableCount"] = 9
        self.assertEqual(ctl.aggregate_run(report, ledger, True, system, data)["correct"], 100)
        report["meta"]["schemaTableCount"] = 38
        with self.assertRaises(ctl.Refused):
            ctl.aggregate_run(report, ledger, True, system, data)

    def test_registered_report_path_cannot_escape_archive(self):
        self.state["runs"][0]["report"] = self.state["runs"][1]["report"]
        ctl.save(self.directory, self.state)
        with self.assertRaisesRegex(ctl.Refused, "escaped"):
            ctl.audit(self.directory, False)


if __name__ == "__main__":
    unittest.main()
