import json
import shutil
import tempfile
import unittest
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from unittest import mock

import activation_workflow as p


class ActivationWorkflowTests(unittest.TestCase):
    def test_inputs_bind_scope_old_ledger_and_transaction(self):
        context = p.verify_inputs()
        self.assertEqual(context["hashes"]["closedQuota"], p.CLOSED_QUOTA_SHA256)
        self.assertEqual(context["hashes"]["closedLedger"], p.CLOSED_LEDGER_SHA256)
        self.assertEqual(context["oldUnknownCount"], 7)
        self.assertEqual(context["targetSha256"], p.SCOPE_STAGED_QUOTA_SHA256)
        self.assertTrue(context["transactionId"].startswith(p.TRANSACTION_PREFIX))

    def test_dry_run_is_metadata_only_and_reserves_under_ten(self):
        result = p.read_json(p.HERE / "dry-run.json")
        self.assertTrue(result["passed"])
        self.assertEqual(result["sourceLimitYuan"], "1.00")
        self.assertEqual(result["targetLimitYuan"], "10.00")
        self.assertEqual(result["currentCommitmentYuan"], "0.993767460")
        self.assertEqual(result["nextReservationCommitmentYuan"], "1.093767460")
        self.assertLessEqual(Decimal(result["nextReservationCommitmentYuan"]), Decimal("10.00"))
        self.assertEqual(result["providerCalls"], 0)
        self.assertEqual(result["keyReads"], 0)
        self.assertFalse(result["oldFilesModified"])

    def test_preflight_peak_and_commit_slot_checks_pass(self):
        result = p.read_json(p.HERE / "activate-preflight.json")
        self.assertTrue(result["passed"])
        self.assertTrue(all(result["checks"].values()))
        self.assertTrue(result["checks"]["peakRejected"])
        self.assertTrue(result["checks"]["boundaryRejected"])
        self.assertTrue(result["checks"]["commitSlotFresh"])
        self.assertFalse(result["executionEnabled"])

    def test_peak_and_boundary_guard_are_fail_closed(self):
        peak = p.off_peak_window(datetime(2026, 11, 20, 2, tzinfo=timezone.utc))
        boundary = p.off_peak_window(datetime(2026, 11, 20, 0, 59, tzinfo=timezone.utc))
        self.assertFalse(peak["allowed"])
        self.assertEqual(peak["reason"], "PEAK_HOURS")
        self.assertFalse(boundary["allowed"])
        self.assertEqual(boundary["reason"], "PEAK_BOUNDARY_RESERVE")

    def test_commit_generates_program_bound_proof_and_enables_only_new_manifest(self):
        manifest = p.read_json(p.HERE / "execution-manifest.json")
        proof = p.read_json(p.HERE / "migration-proof.json")
        result = p.read_json(p.HERE / "activate-result.json")
        self.assertTrue(manifest["executionEnabled"])
        self.assertTrue(result["executionEnabled"])
        self.assertEqual(proof["generatedBy"], "activation_workflow.py")
        self.assertEqual(proof["generatorProgramSha256"], p.sha(p.HERE / "activation_workflow.py"))
        self.assertEqual(proof["sourceBytesSha256"], p.CLOSED_QUOTA_SHA256)
        self.assertEqual(proof["targetBytesSha256"], p.sha(p.HERE / "staged-runtime-quota.json"))
        self.assertEqual(manifest["migrationProofSha256"], p.sha(p.HERE / "migration-proof.json"))
        self.assertEqual(manifest["providerCalls"], 0)
        self.assertEqual(manifest["keyReads"], 0)

    def test_target_runtime_changes_only_limit(self):
        old = p.read_json(p.OLD_QUOTA)
        target = p.read_json(p.HERE / "staged-runtime-quota.json")
        compare = dict(target)
        compare["limitYuan"] = old["limitYuan"]
        self.assertEqual(compare, old)
        self.assertEqual(target["limitYuan"], 10.0)
        self.assertEqual(target["reservationYuan"], old["reservationYuan"])

    def test_unknown_hold_digest_and_release_count_are_permanent(self):
        context = p.verify_inputs()
        proof = p.read_json(p.HERE / "migration-proof.json")
        manifest = p.read_json(p.HERE / "execution-manifest.json")
        self.assertEqual(proof["unknownHoldDigest"], context["unknownDigest"])
        self.assertEqual(proof["unknownHoldCount"], 7)
        self.assertEqual(proof["oldUnknownReleased"], 0)
        self.assertEqual(manifest["oldUnknownReleased"], 0)

    def test_activation_verification_rechecks_all_generated_artifacts(self):
        verified = p.verify_activation()
        self.assertTrue(verified["executionEnabled"])
        self.assertEqual(verified["oldUnknownReleased"], 0)
        self.assertEqual(verified["migrationTransactionId"], p.verify_inputs()["transactionId"])

    def test_duplicate_activation_is_refused(self):
        with self.assertRaises(p.ActivationRefused) as error:
            p.activate_commit(at=datetime(2026, 11, 20, 4, tzinfo=timezone.utc))
        self.assertEqual(str(error.exception), "DUPLICATE_ACTIVATION")

    def test_commit_peak_is_refused_before_duplicate_slot(self):
        with self.assertRaises(p.ActivationRefused) as error:
            p.activate_commit(at=datetime(2026, 11, 20, 2, tzinfo=timezone.utc))
        self.assertEqual(str(error.exception), "OFF_PEAK_REQUIRED")

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
                with self.assertRaises(p.ActivationRefused):
                    p.verify_inputs()

    def test_scope_plan_tamper_is_refused(self):
        self._assert_scope_tamper_refused("plan.json",
                                          lambda value: value["scope"].update({"cumulativeQuestionLimit": 1047}))

    def test_scope_authorization_tamper_is_refused(self):
        self._assert_scope_tamper_refused("paid-scope10-authorization.json",
                                          lambda value: value.update({"executionEnabledInThisTask": True}))

    def test_scope_binding_tamper_is_refused(self):
        self._assert_scope_tamper_refused("authorization-binding.json",
                                          lambda value: value.update({"executionEnabled": True}))

    def test_old_quota_bytes_tamper_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            old = Path(temporary) / "candidate-cost-quota.json"
            shutil.copy2(p.OLD_QUOTA, old)
            value = json.loads(old.read_text(encoding="utf-8"))
            value["knownCalls"] += 1
            old.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with mock.patch.object(p, "OLD_QUOTA", old):
                with self.assertRaises(p.ActivationRefused):
                    p.verify_inputs()

    def test_old_ledger_bytes_tamper_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            closed = Path(temporary) / "closed"
            closed.mkdir()
            for name in ("plan.json", "state.json", "send-ledger.json", "candidate-cost-quota.json"):
                shutil.copy2(p.CLOSED_DIR / name, closed / name)
            ledger = closed / "send-ledger.json"
            value = json.loads(ledger.read_text(encoding="utf-8"))
            value["calls"].pop()
            ledger.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with mock.patch.object(p, "CLOSED_DIR", closed), mock.patch.object(p, "OLD_QUOTA", closed / "candidate-cost-quota.json"):
                with self.assertRaises(p.ActivationRefused):
                    p.verify_inputs()

    def _assert_proof_tamper_refused(self, field, value):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for name in ("staged-runtime-quota.json", "migration-proof.json", "execution-manifest.json"):
                shutil.copy2(p.HERE / name, output / name)
            proof_path = output / "migration-proof.json"
            proof = json.loads(proof_path.read_text(encoding="utf-8"))
            proof[field] = value
            proof_path.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with self.assertRaises(p.ActivationRefused):
                p.verify_activation(output)

    def test_proof_source_sha_tamper_is_refused(self):
        self._assert_proof_tamper_refused("sourceBytesSha256", "0" * 64)

    def test_proof_target_sha_tamper_is_refused(self):
        self._assert_proof_tamper_refused("targetBytesSha256", "0" * 64)

    def test_proof_generator_sha_tamper_is_refused(self):
        self._assert_proof_tamper_refused("generatorProgramSha256", "0" * 64)

    def test_proof_transaction_tamper_is_refused(self):
        self._assert_proof_tamper_refused("migrationTransactionId", "quota-migration-forged")

    def test_handwritten_generated_by_marker_is_refused(self):
        self._assert_proof_tamper_refused("generatedBy", "manual-proof.json")

    def test_manifest_execution_flag_tamper_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for name in ("staged-runtime-quota.json", "migration-proof.json", "execution-manifest.json"):
                shutil.copy2(p.HERE / name, output / name)
            manifest_path = output / "execution-manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["executionEnabled"] = False
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            with self.assertRaises(p.ActivationRefused):
                p.verify_activation(output)

    def test_old_files_are_byte_identical_after_activation(self):
        paths = [p.CLOSED_DIR / name for name in ("plan.json", "state.json", "send-ledger.json", "candidate-cost-quota.json")]
        before = {path.name: p.sha(path) for path in paths}
        p.verify_activation()
        after = {path.name: p.sha(path) for path in paths}
        self.assertEqual(before, after)

    def test_activation_has_no_key_or_provider_artifact(self):
        names = {path.name.lower() for path in p.HERE.iterdir() if path.is_file()}
        self.assertFalse(any("key" in name or "provider" in name for name in names))
        manifest = p.read_json(p.HERE / "execution-manifest.json")
        self.assertEqual(manifest["providerCalls"], 0)
        self.assertEqual(manifest["keyReads"], 0)
        self.assertFalse(manifest["productionRunnerModified"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
