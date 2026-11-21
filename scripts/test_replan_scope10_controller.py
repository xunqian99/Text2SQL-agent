"""Free boundary checks for real paid admission; never resolves credentials."""
from __future__ import annotations

import copy
import json
import tempfile
import unittest
from contextlib import nullcontext
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import run_replan_stage_d_scope10 as c
from replan_quota_monitor import QuotaRefused


class ControllerChecks(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="scope10-free-")
        self.addCleanup(temp.cleanup)
        self.batch = Path(temp.name)
        self.patch = patch.object(c.d, "BATCH", self.batch)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.seed = json.loads((c.SCOPE / "staged-candidate-quota.json").read_bytes())
        self.write("candidate-quota-initial-snapshot.json", self.seed)
        self.write("candidate-cost-quota.json", self.seed)

    def write(self, name, value):
        (self.batch / name).write_text(json.dumps(value), encoding="utf-8")

    def test_actual_fractional_quota_uses_exact_decimals(self):
        q = c.candidate_headroom()
        self.assertEqual(q["knownCalls"], 139)
        self.assertEqual(str(q["knownCostYuan"]), "0.29376746")
        self.assertEqual(len(q["holds"]), 7)

    def test_old_unknown_cannot_be_refunded(self):
        q = copy.deepcopy(self.seed)
        q["holds"].pop(next(iter(q["holds"])))
        self.write("candidate-cost-quota.json", q)
        with self.assertRaises(QuotaRefused):
            c.candidate_headroom()

    def test_known_high_water_cannot_regress(self):
        self.write("candidate-cost-quota.json", {**self.seed, "knownCostYuan": 0})
        with self.assertRaises(QuotaRefused):
            c.candidate_headroom()

    def test_no_new_call_if_unknown_holds_consume_remaining_budget(self):
        self.write("candidate-cost-quota.json", {**self.seed, "knownCostYuan": 9.25})
        with self.assertRaisesRegex(c.d.Refused, "no room"):
            c.candidate_headroom()

    def test_generation_failed_cannot_hide_proxy_deadline(self):
        rows = [dict(id="a", body=dict(status="GENERATION_FAILED", budget=dict(termination="COMPLETE")))]
        self.assertEqual(c.deadline_counts(rows, [dict(questionId="a", result=dict(late=True))])["timeoutCount"], 1)

    def test_runtime_and_two_model_deadlines_count_one_question(self):
        rows = [dict(id="a", body=dict(status="REQUEST_TIMEOUT")), dict(id="b", body=dict(status="SUCCESS"))]
        calls = [dict(questionId="a", result=dict(late=True)), dict(questionId="a", result=dict(late=True))]
        self.assertEqual(c.deadline_counts(rows, calls)["timeoutCount"], 1)

    def test_timely_body_and_slow_durable_write_still_count_actual_deadline(self):
        rows = [dict(id="a", body=dict(status="GENERATION_FAILED"))]
        calls = [dict(questionId="a", result=dict(late=False, answerEligible=True))]
        decisions = [dict(questionId="a", answerDeadlineExceeded=True)]
        self.assertEqual(c.deadline_counts(rows, calls, decisions)["timeoutCount"], 1)

    def test_weekend_is_offpeak_but_weekday_peaks_are_rejected(self):
        zone = c.d.BEIJING
        self.assertTrue(c.d.window(datetime(2026, 11, 21, 14, tzinfo=zone), 1740)["allowed"])
        for hour in (9, 11, 14, 17):
            self.assertFalse(c.d.window(datetime(2026, 11, 23, hour, tzinfo=zone), 120)["allowed"])
        self.assertFalse(c.d.window(datetime(2026, 11, 23, 8, 59, tzinfo=zone), 120)["allowed"])

    def test_frozen_java_loads_ten_yuan_without_changing_pricing_address(self):
        command = c.app_command("F", 54321, "http://127.0.0.1:59900", self.batch)
        self.assertIn("--agent.cost-quota.total-yuan=10.00", command)
        self.assertIn("--agent.cost-quota.state-file=" + str(self.batch / "candidate-cost-quota.json"), command)
        with self.assertRaises(c.d.Refused):
            c.app_command("F", 54321, "http://127.0.0.1:59901", self.batch)

    def test_independent_acceptance_is_before_any_key_access(self):
        self.write("plan.json", {})
        self.write("state.json", dict(planSha256=c.sha(self.batch / "plan.json"), requestsStarted=0, rounds={}, terminal=None))
        with patch.object(c, "BATCH", self.batch), patch.object(c, "configure"), \
                patch.object(c.n, "controller_locked", return_value=nullcontext()), \
                patch.object(c, "verify"), patch.object(c.n, "_old_real_key") as key:
            with self.assertRaises(FileNotFoundError):
                c.run_next()
            key.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
