import json
import shutil
import tempfile
import unittest
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from unittest import mock

import execution_adapter as p


class ExecutionAdapterTests(unittest.TestCase):
    def test_scope_reverification_binds_plan_ledger_and_unknowns(self):
        context = p.verify_scope()
        self.assertEqual(context["hashes"]["plan"], p.SCOPE_PLAN_SHA256)
        self.assertEqual(context["hashes"]["closedLedger"], p.CLOSED_LEDGER_SHA256)
        self.assertEqual(context["oldUnknownCount"], 7)
        self.assertEqual(context["closedQuota"]["knownCalls"], 139)

    def test_prepare_manifest_is_disabled_and_side_effect_free(self):
        manifest = p.read_json(p.HERE / "adapter-manifest.json")
        self.assertFalse(manifest["executionEnabled"])
        self.assertTrue(manifest["realQuotaMigrationProofRequired"])
        self.assertEqual(manifest["providerCalls"], 0)
        self.assertEqual(manifest["keyReads"], 0)
        self.assertFalse(manifest["oldLedgerModified"])
        self.assertFalse(manifest["oldEvidenceModified"])

    def test_preflight_passes_all_free_checks(self):
        result = p.read_json(p.HERE / "preflight.json")
        self.assertTrue(result["passed"])
        self.assertTrue(all(result["checks"].values()))
        self.assertEqual(result["providerCalls"], 0)
        self.assertEqual(result["keyReads"], 0)
        self.assertFalse(result["executionEnabled"])

    def test_peak_window_rejected(self):
        result = p.off_peak_window(datetime(2026, 11, 20, 2, tzinfo=timezone.utc))
        self.assertFalse(result["allowed"])
        self.assertEqual(result["reason"], "PEAK_HOURS")

    def test_peak_boundary_guard_rejected(self):
        result = p.off_peak_window(datetime(2026, 11, 20, 0, 59, tzinfo=timezone.utc))
        self.assertFalse(result["allowed"])
        self.assertEqual(result["reason"], "PEAK_BOUNDARY_RESERVE")

    def test_weekend_and_after_peak_are_allowed(self):
        self.assertTrue(p.off_peak_window(datetime(2026, 11, 20, 4, tzinfo=timezone.utc))["allowed"])
        self.assertTrue(p.off_peak_window(datetime(2026, 11, 21, 2, tzinfo=timezone.utc))["allowed"])

    def test_reservation_keeps_old_unknown_commitment_and_stays_under_ten(self):
        context = p.verify_scope()
        self.assertEqual(context["currentCommitmentYuan"], "0.993767460")
        self.assertEqual(context["nextReservationCommitmentYuan"], "1.093767460")
        self.assertLessEqual(Decimal(context["nextReservationCommitmentYuan"]), Decimal("10.00"))
        self.assertEqual(context["oldUnknownCount"], 7)

    def test_dry_run_has_one_post_per_attempt_and_two_call_cap(self):
        result = p.read_json(p.HERE / "dry-run.json")
        self.assertTrue(result["passed"])
        self.assertEqual(result["maxModelCallsPerQuestion"], 2)
        self.assertLessEqual(len(result["attempts"]), 2)
        self.assertTrue(all(attempt["postCount"] == 1 for attempt in result["attempts"]))
        self.assertEqual(result["maxPostCountPerAttempt"], 1)
        self.assertEqual(result["providerCalls"], 0)

    def test_dry_run_keeps_old_unknown_permanent(self):
        result = p.read_json(p.HERE / "dry-run.json")
        self.assertEqual(result["oldUnknownHolds"], 7)
        self.assertEqual(result["oldUnknownReleased"], 0)
        self.assertFalse(result["oldUnknownRefundAllowed"])
        self.assertEqual(result["candidateHoldDigest"], p.verify_scope()["holdDigest"])

    def test_dry_run_marks_105_second_accounting_only_flags(self):
        accounting = p.read_json(p.HERE / "dry-run.json")["accounting"]
        self.assertEqual(accounting["sameSentConnectionReadOnlyExtraSeconds"], 105)
        self.assertEqual(accounting["answerDeadlineMs"], 15000)
        self.assertEqual(accounting["accountingDeadlineMs"], 120000)
        self.assertTrue(accounting["late"])
        self.assertTrue(accounting["accountingOnly"])
        self.assertFalse(accounting["answerEligible"])
        self.assertFalse(accounting["lateSqlBackfill"])
        self.assertIsNone(accounting["callerSql"])

    def test_execute_refuses_before_real_migration_proof(self):
        result = p.execute(at=datetime(2026, 11, 20, 4, tzinfo=timezone.utc), persist=False)
        self.assertFalse(result["passed"])
        self.assertTrue(result["refused"])
        self.assertEqual(result["refusalReason"], "REAL_QUOTA_MIGRATION_PROOF_MISSING")
        self.assertFalse(result["checks"]["realQuotaMigrationProof"])
        self.assertEqual(result["providerCalls"], 0)
        self.assertEqual(result["keyReads"], 0)

    def test_execute_refuses_peak_before_any_side_effect(self):
        result = p.execute(at=datetime(2026, 11, 20, 2, tzinfo=timezone.utc), persist=False)
        self.assertFalse(result["passed"])
        self.assertEqual(result["refusalReason"], "OFF_PEAK_REQUIRED")
        self.assertFalse(result["checks"]["offPeakWindow"])
        self.assertEqual(result["providerCalls"], 0)
        self.assertEqual(result["keyReads"], 0)

    def test_execute_rejects_mismatched_real_migration_proof(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for name in ("adapter-manifest.json", "preflight.json", "dry-run.json"):
                shutil.copy2(p.HERE / name, output / name)
            proof = {
                "format": p.PROOF_FORMAT,
                "productionApplied": True,
                "sourceQuotaSha256": "0" * 64,
                "targetStagedQuotaSha256": p.SCOPE_STAGED_QUOTA_SHA256,
                "budgetId": p.verify_scope()["closedQuota"]["budgetId"],
                "pricingIdentity": p.verify_scope()["closedQuota"]["pricingIdentity"],
                "targetLimitYuan": "10.00",
                "unknownHoldDigest": p.verify_scope()["holdDigest"],
                "oldUnknownReleased": 0,
            }
            (output / "real-quota-migration-proof.json").write_text(
                json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            with mock.patch.object(p, "HERE", output):
                result = p.execute(output=output, at=datetime(2026, 11, 20, 4, tzinfo=timezone.utc), persist=False)
            self.assertFalse(result["passed"])
            self.assertEqual(result["refusalReason"], "REAL_QUOTA_MIGRATION_PROOF_MISMATCH")
            self.assertEqual(result["providerCalls"], 0)
            self.assertEqual(result["keyReads"], 0)

    def test_execute_requires_old_unknown_hold_identity(self):
        result = p.read_json(p.HERE / "execute-result.json")
        self.assertEqual(result["oldUnknownReleased"], 0)
        self.assertFalse(result["checks"]["realQuotaMigrationProof"])
        self.assertFalse(result["checks"]["executionEnabled"])

    def _scope_copy(self, temporary):
        scope = Path(temporary) / "scope"
        shutil.copytree(p.SCOPE_DIR, scope)
        return scope

    def _assert_scope_tamper_refused(self, file_name, mutate):
        with tempfile.TemporaryDirectory() as temporary:
            scope = self._scope_copy(temporary)
            path = scope / file_name
            value = json.loads(path.read_text(encoding="utf-8"))
            mutate(value)
            path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with mock.patch.object(p, "SCOPE_DIR", scope):
                with self.assertRaises(p.AdapterRefused):
                    p.verify_scope()

    def test_tampered_authorization_is_refused_before_execute(self):
        self._assert_scope_tamper_refused("paid-scope10-authorization.json",
                                          lambda value: value.update({"executionEnabledInThisTask": True}))

    def test_tampered_plan_is_refused_before_execute(self):
        self._assert_scope_tamper_refused("plan.json",
                                          lambda value: value["scope"].update({"cumulativeQuestionLimit": 1047}))

    def test_tampered_staged_quota_is_refused_before_execute(self):
        self._assert_scope_tamper_refused("staged-candidate-quota.json",
                                          lambda value: value.update({"limitYuan": 11.0}))

    def test_tampered_binding_is_refused_before_execute(self):
        self._assert_scope_tamper_refused("authorization-binding.json",
                                          lambda value: value.update({"executionEnabled": True}))

    def test_tampered_old_ledger_is_refused_before_execute(self):
        with tempfile.TemporaryDirectory() as temporary:
            closed = Path(temporary) / "closed"
            closed.mkdir()
            for name in ("plan.json", "state.json", "send-ledger.json", "candidate-cost-quota.json"):
                shutil.copy2(p.CLOSED_DIR / name, closed / name)
            ledger_path = closed / "send-ledger.json"
            ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
            ledger["calls"].pop()
            ledger_path.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with mock.patch.object(p, "CLOSED_DIR", closed):
                with self.assertRaises(p.AdapterRefused):
                    p.verify_scope()

    def test_no_key_or_provider_artifact_in_adapter_directory(self):
        names = {path.name.lower() for path in p.HERE.iterdir() if path.is_file()}
        self.assertFalse(any("key" in name or "provider" in name for name in names))
        self.assertNotIn("real-quota-migration-proof.json", names)

    def test_execute_result_records_zero_external_activity(self):
        result = p.read_json(p.HERE / "execute-result.json")
        self.assertEqual(result["providerCalls"], 0)
        self.assertEqual(result["keyReads"], 0)
        self.assertEqual(result["externalRequests"], 0)
        self.assertFalse(result["oldLedgerModified"])
        self.assertFalse(result["oldEvidenceModified"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
