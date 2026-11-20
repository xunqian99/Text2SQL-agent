import json
import shutil
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

import reopen_plan as rp


class ReopenPlanTests(unittest.TestCase):
    def test_base_registration_is_the_closed_revision4(self):
        base = rp.verify_base()
        self.assertEqual(base["closedPlanSha256"], rp.CLOSED_PLAN_SHA256)
        self.assertEqual(base["state"]["requestsStarted"], 76)
        self.assertEqual(base["ledger"]["calls"], 76)
        self.assertTrue(base["currentPermanentRejectLockVerified"])

    def test_original_limits_and_counters_are_preserved(self):
        base = rp.verify_base()
        self.assertEqual(rp.EXPECTED_LIMITS["totalYuan"], "1.00")
        self.assertEqual(rp.EXPECTED_LIMITS["cumulativeRequests"], 970)
        self.assertEqual(rp.EXPECTED_LIMITS["cumulativeCalls"], 1940)
        self.assertEqual(base["candidateQuota"]["unknownHolds"], 7)
        self.assertEqual(base["candidateQuota"]["configuredCommittedYuan"], "0.993767460")
        self.assertEqual(base["candidateQuota"]["nextReservationCommitmentYuan"], "1.093767460")
        self.assertTrue(base["candidateQuota"]["nextReservationWouldExceedLimit"])

    def test_peak_and_boundary_windows_are_rejected(self):
        peak = rp.off_peak_window(datetime(2026, 11, 20, 2, tzinfo=timezone.utc), 120)
        boundary = rp.off_peak_window(datetime(2026, 11, 20, 0, 59, tzinfo=timezone.utc), 120)
        self.assertEqual(peak["reason"], "PEAK_HOURS")
        self.assertEqual(boundary["reason"], "PEAK_BOUNDARY_RESERVE")
        self.assertFalse(peak["allowed"])
        self.assertFalse(boundary["allowed"])

    def test_offpeak_after_peak_and_weekend_are_allowed(self):
        after_peak = rp.off_peak_window(datetime(2026, 11, 20, 4, tzinfo=timezone.utc), 120)
        weekend = rp.off_peak_window(datetime(2026, 11, 21, 2, tzinfo=timezone.utc), 120)
        self.assertTrue(after_peak["allowed"])
        self.assertTrue(weekend["allowed"])

    def test_generated_plan_is_bound_to_user_authorization_and_base(self):
        base, authorization, plan, binding = rp._load_output(rp.HERE)
        self.assertEqual(plan["authorizationId"], authorization["authorizationId"])
        self.assertEqual(binding["authorizationSha256"], rp.sha(rp.HERE / "reopen-authorization.json"))
        self.assertEqual(binding["planSha256"], rp.sha(rp.HERE / "plan.json"))
        self.assertEqual(plan["basePlanSha256"], base["closedPlanSha256"])
        self.assertFalse(plan["runNext"]["actionExposed"])
        self.assertFalse(plan["runNext"]["invokedDuringPreparation"])

    def test_preflight_and_audit_are_free_and_execution_disabled(self):
        preflight = rp.read_json(rp.HERE / "preflight.json")
        audit = rp.read_json(rp.HERE / "audit.json")
        self.assertTrue(preflight["passed"])
        self.assertTrue(audit["passed"])
        self.assertEqual(preflight["providerCalls"], 0)
        self.assertEqual(preflight["keyReads"], 0)
        self.assertFalse(preflight["executionEnabled"])
        self.assertEqual(audit["providerCalls"], 0)
        self.assertEqual(audit["keyReads"], 0)
        self.assertFalse(audit["runNextInvoked"])
        self.assertFalse(audit["actualBatchModified"])

    def test_tampering_authorization_is_rejected_in_isolated_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for name in ("base-snapshot.json", "reopen-authorization.json", "plan.json", "authorization-binding.json"):
                shutil.copy2(rp.HERE / name, output / name)
            auth_path = output / "reopen-authorization.json"
            auth = json.loads(auth_path.read_text(encoding="utf-8"))
            auth["paidEvaluationScope"]["limitIncreaseAllowed"] = True
            auth_path.write_text(json.dumps(auth, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with self.assertRaises(rp.Refused):
                rp.verify_output(output)

    def test_old_decision_and_current_lock_are_not_rewritten(self):
        base = rp.verify_base()
        self.assertEqual(base["closedDecisionSha256"], rp.CLOSED_DECISION_SHA256)
        self.assertEqual(base["closedProposalSha256"], rp.CLOSED_PROPOSAL_SHA256)
        self.assertEqual(base["closedPlanDriverSha256"], rp.CLOSED_PLAN_DRIVER_SHA256)
        self.assertEqual(base["currentLockedDriverSha256"], rp.CURRENT_DRIVER_SHA256)
        self.assertTrue(rp.current_driver_lock_verified())

    def test_output_contains_no_key_or_provider_artifacts(self):
        names = {path.name for path in rp.HERE.iterdir() if path.is_file()}
        self.assertFalse(any("key" in name.lower() for name in names))
        self.assertFalse(any("provider" in name.lower() for name in names))
        self.assertNotIn("human-paid-authorization.json", names)


if __name__ == "__main__":
    unittest.main(verbosity=2)
