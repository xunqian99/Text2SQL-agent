"""Directed free tests; networking, subprocesses and real credentials are forbidden."""
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import unittest
from contextlib import contextmanager
from datetime import datetime, timezone
from decimal import Decimal
from unittest.mock import patch

import run_replan_stage_d_reregistered as n

EVIDENCE = n.CANDIDATE / "directed-tests-1"


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def authorized_fixture(plan):
    # Synthetic only; never write this fixture to the actual registration.
    message = "SYNTHETIC TEST FIXTURE; no real human paid permission is granted"
    return dict(format="replan-d-direct-user-paid-authorization-v1", approved=True,
        authority="DIRECT_HUMAN_USER", scope="ONE_ADDITIONAL_FULL_DEV100_FIXED_C1_EXISTING_GATES",
        planSha256=n.sha(plan), approvedAt="2026-01-01T00:00:00+00:00", directUserMessage=message,
        directUserMessageSha256=hashlib.sha256(message.encode()).hexdigest(),
        legacyManifestSha256=n.PINNED["paid-interruption-manifest.json"],
        limits=dict(additionalDevelopmentQuestions=100, currentRouteRequests=902,
            cumulativeRequests=970, cumulativeModelCalls=1940, totalYuan="1.00", reservationYuan="0.10"),
        acknowledgments=dict(oldBatchInterruptedNoReplay=True, legacyRequests=68,
            legacyApplicationModelCalls=70, knownCostYuan="0.16312188", commonUnknownHeldYuan="0.20",
            candidateUnknownHeldYuan="0.30", unknownReservationsNeverReleased=True,
            twoQuotaLayersNeverSummedAsBill=True, unseenStillLocked=True,
            engineeringAcceptanceAloneIsNotPaidAuthorization=True))


class DirectedChecks(unittest.TestCase):
    def setUp(self):
        self.directory = EVIDENCE / self._testMethodName
        self.directory.mkdir(parents=True)
        self.scope = patch.object(n.d, "BATCH", self.directory)
        self.scope.start()
        self.addCleanup(self.scope.stop)
        # No test may read the environment/registry key, reach a socket, or spawn a JAR.
        for target in ((n, "_old_real_key"), (socket.socket, "connect"), (subprocess, "run"), (subprocess, "Popen")):
            guard = patch.object(*target, side_effect=AssertionError("Real key/network/process forbidden in directed tests"))
            guard.start(); self.addCleanup(guard.stop)

    def plan(self):
        path = self.directory / "plan.json"
        dump(path, {"synthetic": True, "scope": self._testMethodName})
        return path

    def auth(self, edit=None):
        auth = authorized_fixture(self.plan())
        if edit: edit(auth)
        dump(self.directory / n.AUTH_NAME, auth)
        return auth

    def empty_ledger(self, suffix="send-ledger.json"):
        path = self.directory / suffix
        dump(path, dict(planSha256="fixture-plan", legacyManifestSha256=n.PINNED["paid-interruption-manifest.json"],
                        calls=[], rejections=[], breaches=[]))
        return path

    def gate(self, **kwargs):
        return n.SendGate(self.empty_ledger(), "fixture-plan", "DEV_F",
            clock=lambda: datetime(2026, 11, 21, 12, tzinfo=n.d.BEIJING), monotonic=lambda: 10., **kwargs)

    def payload(self, correction=False):
        text = "=== 用户问题 ===\n同一题号的新完整开发轮\n"
        if correction: text += "\n\n=== 上一次 SQL 与评估反馈 ===\nfixed"
        payload = dict(model="deepseek-flash", temperature=0, max_tokens=8192,
                       messages=[dict(role="system", content="synthetic"), dict(role="user", content=text)])
        return payload, json.dumps(payload, ensure_ascii=False).encode()

    def begin(self, gate):
        gate.begin(dict(id="T1-002", question="同一题号的新完整开发轮"), 25.)

    def test_legacy_full_manifest_and_independent_accounting_match(self):
        facts = n.legacy_facts()
        self.assertEqual(facts["applicationModelCalls"], 70)
        self.assertEqual(facts["commonSendReservations"], 69)
        self.assertEqual(facts["transportAttempts"], 68)
        self.assertEqual(facts["commonCommittedYuan"], "0.36312188")
        self.assertEqual(facts["candidateCommittedYuan"], "0.46312188")

    def test_old_controller_source_is_byte_unchanged(self):
        self.assertEqual(n.sha(n.ROOT / "scripts/run_replan_stage_d.py"), n.OLD_CONTROLLER_SHA)

    def test_missing_authorization_refuses_before_old_run_and_key(self):
        state = self.directory / "state.json"; dump(state, dict(requestsStarted=0, rounds={}))
        before = n.sha(state)
        with patch.object(n.d, "run_next", side_effect=AssertionError("Inherited paid path was reached")):
            with self.assertRaises(n.Refused): n.run_next()
        self.assertEqual(n.sha(state), before)
        self.assertFalse((self.directory / "controller.lock").exists())

    def test_engineering_acceptance_does_not_authorize_payment(self):
        dump(self.directory / "decision.json", dict(accepted=True, planSha256="fixture"))
        with self.assertRaises(n.Refused): n.run_next()

    def test_wrong_plan_authorization_is_rejected(self):
        self.auth(lambda a: a.update(planSha256="0" * 64))
        with self.assertRaises(n.Refused): n.guarded_real_key()

    def test_agent_review_authority_is_rejected(self):
        self.auth(lambda a: a.update(authority="INDEPENDENT_REVIEWER"))
        with self.assertRaises(n.Refused): n.require_human_authorization()

    def test_message_hash_must_match(self):
        self.auth(lambda a: a.update(directUserMessageSha256="0" * 64))
        with self.assertRaises(n.Refused): n.require_human_authorization()

    def test_missing_unknown_acknowledgment_is_rejected(self):
        self.auth(lambda a: a["acknowledgments"].pop("candidateUnknownHeldYuan"))
        with self.assertRaises(n.Refused): n.require_human_authorization()

    def test_raised_money_authorization_is_rejected(self):
        self.auth(lambda a: a["limits"].update(totalYuan="2.00"))
        with self.assertRaises(n.Refused): n.require_human_authorization()

    def test_future_authorization_is_rejected(self):
        self.auth(lambda a: a.update(approvedAt="2099-01-01T00:00:00+00:00"))
        with self.assertRaises(n.Refused): n.require_human_authorization()

    def test_authorization_read_alone_does_not_write_or_read_key(self):
        self.auth()
        before = {p.name: n.sha(p) for p in self.directory.iterdir()}
        seal = n.require_human_authorization()
        self.assertEqual(seal["planSha256"], n.sha(self.directory / "plan.json"))
        self.assertEqual(before, {p.name: n.sha(p) for p in self.directory.iterdir()})

    def test_valid_synthetic_auth_is_sealed_before_fake_key(self):
        self.auth()
        def fake_key():
            self.assertTrue((self.directory / "human-authorization-consumed.json").is_file())
            return "synthetic-not-a-provider-credential"
        with patch.object(n, "_old_real_key", side_effect=fake_key):
            self.assertEqual(n.guarded_real_key(), "synthetic-not-a-provider-credential")

    def test_consumed_authorization_cannot_change(self):
        self.auth()
        dump(self.directory / "human-authorization-consumed.json", dict(planSha256="wrong"))
        with self.assertRaises(n.Refused): n.require_human_authorization()

    def test_new_current_ledger_does_not_inherit_old_same_id_attempt(self):
        gate = self.gate(); self.begin(gate)
        permit = gate.before(*self.payload())
        self.assertEqual(permit["attempt"], 1)
        self.assertEqual(len(gate.data["calls"]), 1)
        self.assertEqual(gate.committed(), Decimal("0.46312188"))

    def test_common_legacy_reservation_blocks_extra_budget(self):
        gate = self.gate(); self.begin(gate)
        with patch.object(n, "LEGACY", {**n.LEGACY}):
            gate.legacy["commonCommittedYuan"] = "0.91"
            with self.assertRaisesRegex(n.Refused, "GLOBAL_COST_RESERVATION"):
                gate.before(*self.payload())
        self.assertEqual(gate.data["calls"], [])

    def test_current_permit_limit_deducts_seventy_application_calls(self):
        self.assertEqual(n.CURRENT_CALLS, 1870)
        self.assertEqual(n.d.MAX_CALLS + n.LEGACY["applicationModelCalls"], 1940)
        self.assertEqual(n.d.MAX_REQUESTS + n.LEGACY["requestsStarted"], 970)
        self.assertLess(970, 1002); self.assertLess(1940, 2004)

    def test_durable_replace_keeps_one_temp_bytes_and_one_permit(self):
        gate = self.gate(); self.begin(gate)
        observed = []
        real_replace = n.replace_with_retry
        def same_bytes_helper(source, dest):
            original = source.read_bytes()
            def transient(first, second):
                observed.append((str(first), str(second), first.read_bytes()))
                if len(observed) == 1:
                    error = PermissionError("synthetic Windows sharing conflict"); error.winerror = 32
                    raise error
                n.os.replace(first, second)
            result = real_replace(source, dest, replace=transient)
            self.assertEqual(original, dest.read_bytes())
            return result
        with patch.object(n, "replace_with_retry", side_effect=same_bytes_helper):
            permit = gate.before(*self.payload())
        self.assertEqual(len(observed), 2)
        self.assertEqual(observed[0], observed[1])
        self.assertEqual(len(list(gate.events.glob("*-permit.json"))), 1)
        self.assertEqual(gate.data["calls"][0]["token"], permit["token"])
        self.assertEqual(gate.data["calls"][0]["upstreamDeadlineMonotonic"], 25.)

    def test_replace_failure_never_returns_permit_or_releases_hold(self):
        gate = self.gate(); self.begin(gate)
        with patch.object(n, "replace_with_retry", side_effect=PermissionError("persistent fixture failure")):
            with self.assertRaises(PermissionError): gate.before(*self.payload())
        self.assertEqual(len(gate.data["calls"]), 1)
        self.assertFalse(gate.data["calls"][0]["usageKnown"])
        self.assertEqual(gate.committed(), Decimal("0.46312188"))
        self.assertTrue(list(self.directory.glob("send-ledger.json.tmp-*")))
        self.assertEqual(len(list(gate.events.glob("*-permit.json"))), 1)

    def test_write_calls_helper_defaults_only(self):
        path = self.directory / "state.json"; dump(path, dict(old=True))
        with patch.object(n, "replace_with_retry") as helper:
            n.write(path, dict(new=True), replace=True)
        self.assertEqual(helper.call_count, 1)
        self.assertEqual(helper.call_args.kwargs, {})

    def test_durable_write_never_overwrites_create_new_evidence(self):
        path = self.directory / "immutable.json"; dump(path, dict(retain=True))
        before = n.sha(path)
        with self.assertRaises(n.Refused): n.write(path, dict(retain=False))
        self.assertEqual(before, n.sha(path))

    def test_write_to_old_batch_is_rejected_before_io(self):
        with self.assertRaises(n.Refused): n.write(n.OLD_BATCH / "must-not-exist.json", dict(x=1))

    def test_summary_keeps_legacy_current_and_cumulative_separate(self):
        gate = self.gate(); self.begin(gate); gate.before(*self.payload())
        summary = gate.summary()
        self.assertEqual(summary["sendReservations"], 1)
        self.assertEqual(summary["transportAttempts"], 0)
        self.assertEqual(summary["legacyAccounting"]["transportAttempts"], 68)
        self.assertEqual(summary["cumulativeCommonAccounting"]["sendReservations"], 70)
        self.assertEqual(summary["cumulativeCommonAccounting"]["transportAttempts"], 68)
        self.assertEqual(Decimal(summary["cumulativeCommonAccounting"]["unknownHeldYuan"]), Decimal(".30"))
        self.assertEqual(Decimal(summary["cumulativeCommonAccounting"]["knownCostYuan"]), Decimal(".16312188"))
        self.assertTrue(summary["candidateQuotaIsSeparateNotSummedAsBill"])

    def test_exact_candidate_quota_copy_preserves_budget_and_all_holds(self):
        source = n.OLD_BATCH / "candidate-cost-quota.json"
        n.write_bytes(self.directory / "candidate-cost-quota.json", source.read_bytes())
        self.assertEqual(n.sha(source), n.sha(self.directory / "candidate-cost-quota.json"))
        self.assertEqual(len(n.check_candidate_quota()["holds"]), 3)

    def test_candidate_unknown_cannot_be_removed(self):
        quota = n.d.read(n.OLD_BATCH / "candidate-cost-quota.json"); quota["holds"].pop(next(iter(quota["holds"])))
        dump(self.directory / "candidate-cost-quota.json", quota)
        with self.assertRaises(n.Refused): n.check_candidate_quota()

    def test_candidate_budget_id_cannot_be_reset(self):
        quota = n.d.read(n.OLD_BATCH / "candidate-cost-quota.json"); quota["budgetId"] = "fresh-reset"
        dump(self.directory / "candidate-cost-quota.json", quota)
        with self.assertRaises(n.Refused): n.check_candidate_quota()

    def test_candidate_layer_exhaustion_is_not_bypassed_by_common_budget(self):
        quota = n.d.read(n.OLD_BATCH / "candidate-cost-quota.json"); quota["knownCostYuan"] = 0.8
        dump(self.directory / "candidate-cost-quota.json", quota)
        with self.assertRaises(n.Refused): n.check_candidate_quota()

    def test_prepare_checks_old_terminal_before_creating_new_directory(self):
        self.directory.rmdir()
        with patch.object(n, "legacy_facts", side_effect=n.Refused("not independently audited")):
            with self.assertRaises(n.Refused): n.prepare()
        self.assertFalse(self.directory.exists())

    def test_prepare_refuses_existing_directory(self):
        with patch.object(n, "legacy_facts", side_effect=AssertionError("must stop before further reads")):
            with self.assertRaises(n.Refused): n.prepare()

    def test_prepare_free_integration_copies_legacy_quota_and_binds_helper(self):
        self.directory.rmdir()
        facts = n.legacy_facts()
        def compile_fixture():
            runtime = self.directory / "tool-runtime"; runtime.mkdir()
            n.write_bytes(runtime / "synthetic.class", b"not executable; no JAR process in directed checks")
            return "synthetic-only-classpath"
        with patch.object(n.d, "compile_bridge", side_effect=compile_fixture), \
             patch.object(n.d, "database_snapshot", return_value=n.old_database()), \
             patch.object(n, "legacy_facts", return_value=facts):
            n.prepare()
        plan = n.d.read(self.directory / "plan.json")
        self.assertEqual(plan["legacy"], facts)
        self.assertIn("scripts/replan_durable_write.py", plan["files"])
        self.assertIn("scripts/run_replan_stage_d_reregistered.py", plan["files"])
        self.assertIn(n.AUTH_SCHEMA.relative_to(n.ROOT).as_posix(), plan["files"])
        self.assertEqual(n.sha(self.directory / "candidate-cost-quota.json"), facts["candidateQuotaSha256"])
        self.assertEqual(n.d.read(self.directory / "send-ledger.json")["calls"], [])
        self.assertFalse((self.directory / n.AUTH_NAME).exists())
        for dataset in ("dev", "formal", "targets"):
            self.assertEqual(n.sha(self.directory / (dataset + "-reference.json")), n.sha(n.OLD_BATCH / (dataset + "-reference.json")))

    def test_preflight_free_delegation_never_requires_human_auth_or_key(self):
        self.plan()
        dump(self.directory / "state.json", dict(requestsStarted=0))
        self.empty_ledger()
        def local_fixture(plan, output):
            output.mkdir()
            n.write(output / "result.json", dict(externalProviderCallsAdded=0, syntheticFixture=True))
            return dict(passed=True, planSha256=n.sha(self.directory / "plan.json"), externalProviderCallsAdded=0)
        with patch.object(n.d, "verify"), patch.object(n.d, "offline_preflight", side_effect=local_fixture):
            n.d.preflight()
        self.assertTrue((self.directory / "preflight.json").exists())
        self.assertFalse((self.directory / n.AUTH_NAME).exists())

    def test_offline_candidate_command_loads_exact_legacy_seed_only(self):
        seed = n.OLD_BATCH / "candidate-cost-quota.json"
        n.write_bytes(self.directory / "candidate-quota-initial-snapshot.json", seed.read_bytes())
        n.write_bytes(self.directory / "candidate-cost-quota.json", seed.read_bytes())
        out = self.directory / "offline-F"; out.mkdir()
        before = n.sha(self.directory / "candidate-cost-quota.json")
        with patch.object(n, "_old_app_command", return_value=["synthetic-command"]):
            command = n.app_command("F", 12345, "http://127.0.0.1:1", out, offline=True)
        self.assertEqual(command, ["synthetic-command"])
        self.assertEqual(n.sha(out / "offline-cost-quota.json"), n.sha(seed))
        self.assertEqual(n.sha(self.directory / "candidate-cost-quota.json"), before)

    def test_offline_application_checks_inherited_identity_after_fake_process(self):
        seed = n.OLD_BATCH / "candidate-cost-quota.json"
        n.write_bytes(self.directory / "candidate-quota-initial-snapshot.json", seed.read_bytes())
        out = self.directory / "offline-F"; out.mkdir()
        n.write_bytes(out / "offline-cost-quota.json", seed.read_bytes())
        @contextmanager
        def local_context(*args, **kwargs):
            yield ("http://127.0.0.1:1", "synthetic-only")
        with patch.object(n, "_old_application", side_effect=local_context):
            with n.application("F", None, out, offline=True): pass
        self.assertEqual(n.d.read(out / "inherited-quota-after-load.json")["legacyUnknownHoldsPreserved"], 3)

    def test_offline_synthetic_usage_is_not_summed_into_real_history(self):
        gate = n.SendGate(self.empty_ledger(), "fixture-plan", "OFFLINE_F",
            clock=lambda: datetime(2026, 11, 21, 12, tzinfo=n.d.BEIJING), monotonic=lambda: 10.)
        self.begin(gate); gate.before(*self.payload())
        summary = gate.summary()
        self.assertEqual(summary["addedExternalProviderCalls"], 0)
        self.assertEqual(summary["addedRealProviderCostYuan"], "0")
        self.assertNotIn("cumulativeCommonAccounting", summary)
        self.assertEqual(summary["legacyAccounting"]["knownCostYuan"], "0.16312188")

    def test_old_partial_answers_cannot_enter_new_state_or_target_source(self):
        source = (n.ROOT / "scripts/run_replan_stage_d.py").read_text(encoding="utf-8")
        self.assertIn('dev_score=read(BATCH/"DEV_F/score.json")', source)
        self.assertIn('if len(responses)!=(2 if name=="TARGETS" else 100)', source)
        self.assertNotEqual(n.BATCH, n.OLD_BATCH)
        self.assertEqual(n.d.BATCH, self.directory)


if __name__ == "__main__":
    EVIDENCE.mkdir(parents=True, exist_ok=False)
    protected = {str(n.ROOT / "scripts/run_replan_stage_d.py"): n.OLD_CONTROLLER_SHA}
    manifest = n.d.read(n.BASE / "paid-interruption-manifest.json")
    protected.update({str(n.ROOT / p): value for p, value in manifest["files"].items()})
    result = unittest.TestResult()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(DirectedChecks)
    suite.run(result)
    after = {p: n.sha(p) for p in protected}
    exact = after == protected
    report = dict(format="replan-d-reregistration-directed-free-checks-v1", testsRun=result.testsRun,
        passed=result.testsRun-len(result.failures)-len(result.errors), failures=len(result.failures), errors=len(result.errors),
        failureDetails=[dict(test=str(t), trace=s) for t, s in result.failures+result.errors],
        oldFrozenFilesVerified=len(protected), oldFrozenBytesUnchanged=exact,
        addedExternalProviderCalls=0, addedProviderTokens=0, addedProviderCostYuan="0",
        actualHttpRequests=0, realCredentialReads=0, childProcesses=0,
        syntheticAuthorizationExistsOnlyInTestSubdirectories=True,
        actualRegistrationAuthorizationExists=(n.BATCH / n.AUTH_NAME).exists(),
        legacyAccounting=n.LEGACY, twoQuotaLayersAreNotSummedAsBill=True,
        sourceSha256=n.sha(n.ROOT / "scripts/run_replan_stage_d_reregistered.py"))
    dump(EVIDENCE / "verification.json", report)
    print(json.dumps({k: report[k] for k in ("testsRun", "passed", "failures", "errors", "oldFrozenFilesVerified", "oldFrozenBytesUnchanged")}))
    if not result.wasSuccessful() or not exact: sys.exit(1)
