import json
import shutil
import tempfile
import unittest
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from unittest import mock

import paid_scope10_plan as p


class PaidScope10PlanTests(unittest.TestCase):
    def test_old_76_interruption_and_quota_identity_are_bound(self):
        base = p.verify_anchors()
        self.assertEqual(base["oldState"]["requestsStarted"], 76)
        self.assertEqual(base["oldState"]["terminal"], "INTERRUPTED_NO_REPLAY")
        self.assertEqual(base["oldLedger"]["calls"], 76)
        self.assertEqual(base["oldCandidateQuota"]["knownCalls"], 139)
        self.assertEqual(base["oldCandidateQuota"]["unknownHolds"], 7)

    def test_scope10_numbers_are_exact(self):
        self.assertEqual(p.SCOPE10["limitYuan"], "10.00")
        self.assertEqual(p.SCOPE10["reservationYuan"], "0.10")
        self.assertEqual(p.SCOPE10["lastDevelopmentQuestions"], 100)
        self.assertEqual(p.SCOPE10["lastDevelopmentMaxApplicationCalls"], 200)
        self.assertEqual(p.SCOPE10["newRouteQuestions"], 902)
        self.assertEqual(p.SCOPE10["newRouteApplicationCalls"], 1804)
        self.assertEqual(p.SCOPE10["cumulativeQuestionLimit"], 1046)
        self.assertEqual(p.SCOPE10["cumulativeApplicationCallLimit"], 1950)

    def test_staged_quota_changes_only_limit(self):
        old = p.read_json(p.CLOSED / "candidate-cost-quota.json")
        staged = p.read_json(p.HERE / "staged-candidate-quota.json")
        compare = dict(staged)
        compare["limitYuan"] = old["limitYuan"]
        self.assertEqual(compare, old)
        self.assertEqual(staged["limitYuan"], 10.0)
        self.assertEqual(staged["reservationYuan"], old["reservationYuan"])

    def test_plan_authorization_binding_and_prior_scope_are_separate(self):
        base, authorization, staged, plan, binding = p._load(p.HERE)
        self.assertEqual(plan["scope"], p.SCOPE10)
        self.assertEqual(authorization["scope"], p.SCOPE10)
        self.assertEqual(plan["authorizationId"], binding["authorizationId"])
        self.assertEqual(binding["authorizationSha256"], p.sha(p.HERE / "paid-scope10-authorization.json"))
        prior = p.read_json(p.PRIOR / "reopen-authorization.json")
        self.assertFalse(prior["paidEvaluationScope"]["limitIncreaseAllowed"])
        self.assertEqual(plan["priorReopenPlanSha256"], p.PRIOR_PLAN_SHA256)

    def test_preflight_numbers_and_execution_flags_are_frozen(self):
        preflight = p.read_json(p.HERE / "preflight.json")
        self.assertTrue(preflight["passed"])
        self.assertTrue(all(preflight["checks"].values()))
        self.assertEqual(preflight["providerCalls"], 0)
        self.assertEqual(preflight["keyReads"], 0)
        self.assertFalse(preflight["runNextInvoked"])
        self.assertTrue(preflight["nextReservationAllowedByStagedLimit"])
        self.assertEqual(preflight["cumulativeQuestionLimit"], 1046)
        self.assertEqual(preflight["cumulativeApplicationCallLimit"], 1950)

    def test_peak_and_boundary_window_are_rejected(self):
        peak = p.off_peak_window(datetime(2026, 11, 20, 2, tzinfo=timezone.utc), 120)
        boundary = p.off_peak_window(datetime(2026, 11, 20, 0, 59, tzinfo=timezone.utc), 120)
        self.assertFalse(peak["allowed"])
        self.assertEqual(peak["reason"], "PEAK_HOURS")
        self.assertFalse(boundary["allowed"])
        self.assertEqual(boundary["reason"], "PEAK_BOUNDARY_RESERVE")

    def test_offpeak_samples_and_original_gate_values(self):
        self.assertTrue(p.off_peak_window(datetime(2026, 11, 20, 4, tzinfo=timezone.utc), 120)["allowed"])
        self.assertTrue(p.off_peak_window(datetime(2026, 11, 21, 2, tzinfo=timezone.utc), 120)["allowed"])
        plan = p.read_json(p.HERE / "plan.json")
        self.assertEqual(plan["gates"]["parentRequestTimeoutMs"], 15000)
        self.assertEqual(plan["gates"]["maxModelCallsPerQuestion"], 2)
        self.assertEqual(plan["accountingContinuation"]["sameSentConnectionReadOnlyExtraSeconds"], 105)
        self.assertEqual(plan["accountingContinuation"]["wholeRoundDeadlineSeconds"], 1740)

    def test_audit_confirms_no_provider_key_or_old_batch_mutation(self):
        audit = p.read_json(p.HERE / "audit.json")
        self.assertTrue(audit["passed"])
        self.assertTrue(all(audit["checks"].values()))
        self.assertEqual(audit["providerCalls"], 0)
        self.assertEqual(audit["keyReads"], 0)
        self.assertFalse(audit["runNextInvoked"])
        self.assertFalse(audit["actualBatchModified"])
        self.assertFalse(audit["quotaMigrationPerformed"])
        self.assertEqual(audit["oldUnknownReleased"], 0)

    def test_tampering_staged_limit_is_rejected_in_isolated_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for name in ("base-snapshot.json", "paid-scope10-authorization.json", "staged-candidate-quota.json",
                         "plan.json", "authorization-binding.json"):
                shutil.copy2(p.HERE / name, output / name)
            quota_path = output / "staged-candidate-quota.json"
            quota = json.loads(quota_path.read_text(encoding="utf-8"))
            quota["limitYuan"] = 11.0
            quota_path.write_text(json.dumps(quota, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with self.assertRaises(p.Refused):
                p.verify_output(output)

    def _assert_binding_tamper_refused(self, field, value):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for name in ("base-snapshot.json", "paid-scope10-authorization.json", "staged-candidate-quota.json",
                         "plan.json", "authorization-binding.json"):
                shutil.copy2(p.HERE / name, output / name)
            binding_path = output / "authorization-binding.json"
            binding = json.loads(binding_path.read_text(encoding="utf-8"))
            binding[field] = value
            binding_path.write_text(json.dumps(binding, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with self.assertRaises(p.Refused):
                p.verify_output(output)

    def test_binding_execution_enabled_tamper_refused(self):
        self._assert_binding_tamper_refused("executionEnabled", True)

    def test_binding_append_only_tamper_refused(self):
        self._assert_binding_tamper_refused("appendOnly", False)

    def test_binding_base_snapshot_hash_tamper_refused(self):
        self._assert_binding_tamper_refused("baseSnapshotSha256", "0" * 64)

    def test_binding_prior_reopen_hash_tamper_refused(self):
        self._assert_binding_tamper_refused("priorReopenPlanSha256", "0" * 64)

    def _assert_closed_byte_tamper_refused(self, file_name):
        with tempfile.TemporaryDirectory() as temporary:
            closed = Path(temporary)
            for name in ("plan.json", "state.json", "send-ledger.json", "candidate-cost-quota.json"):
                shutil.copy2(p.CLOSED / name, closed / name)
            path = closed / file_name
            path.write_bytes(path.read_bytes() + b"\n")
            with mock.patch.object(p, "CLOSED", closed):
                with self.assertRaises(p.Refused):
                    p.verify_anchors()

    def test_closed_plan_byte_tamper_refused_before_authorization(self):
        self._assert_closed_byte_tamper_refused("plan.json")

    def test_closed_state_byte_tamper_refused_before_authorization(self):
        self._assert_closed_byte_tamper_refused("state.json")

    def test_closed_ledger_byte_tamper_refused_before_authorization(self):
        self._assert_closed_byte_tamper_refused("send-ledger.json")

    def test_closed_quota_byte_tamper_refused_before_authorization(self):
        self._assert_closed_byte_tamper_refused("candidate-cost-quota.json")

    def test_current_permanent_lock_and_source_hashes_are_bound(self):
        base = p.verify_anchors()
        self.assertTrue(base["currentPermanentRejectLockVerified"])
        self.assertEqual(base["hashes"]["currentDriver"], p.CURRENT_DRIVER_SHA256)
        self.assertEqual(base["hashes"]["oldController"], p.OLD_CONTROLLER_SHA256)
        self.assertEqual(base["hashes"]["quotaMonitor"], p.QUOTA_MONITOR_SHA256)

    def test_new_directory_has_no_key_or_provider_artifact(self):
        names = {path.name.lower() for path in p.HERE.iterdir() if path.is_file()}
        self.assertFalse(any("key" in name or "provider" in name for name in names))
        self.assertNotIn("human-paid-authorization.json", names)


if __name__ == "__main__":
    unittest.main(verbosity=2)
