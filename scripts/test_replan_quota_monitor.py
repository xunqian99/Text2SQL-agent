"""Synthetic quota lifecycle/rollback checks; no supplier, key or Java calls."""
from __future__ import annotations

import base64
import copy
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import unittest
from contextlib import contextmanager
from datetime import datetime
from decimal import Decimal
from unittest.mock import patch

import run_replan_stage_d_reregistered as n
import replan_quota_monitor as q

EVIDENCE = n.CANDIDATE / "quota-monitor-checks-5"


def bytes_for(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def replace_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".synthetic-java-tmp")
    temp.write_bytes(bytes_for(value)); os.replace(temp, path)


class MonitorChecks(unittest.TestCase):
    def setUp(self):
        self.directory = EVIDENCE / self._testMethodName
        self.directory.mkdir(parents=True)
        self.scope = patch.object(n.d, "BATCH", self.directory)
        self.scope.start(); self.addCleanup(self.scope.stop)
        for target in ((n, "_old_real_key"), (socket.socket, "connect"), (subprocess, "Popen")):
            guard = patch.object(*target, side_effect=AssertionError("Real network/key/process forbidden"))
            guard.start(); self.addCleanup(guard.stop)
        self.seed = dict(version=1, budgetId="synthetic-inherited-budget", pricingIdentity="synthetic-frozen-pricing",
                         limitYuan=1, reservationYuan=.1, knownCalls=67, knownCostYuan=.16312188,
                         reservationBreached=False, holds={f"legacy-{i}": dict(requestId=f"legacy-request-{i}",
                         callNumber=1, promptSha256=str(i) * 64, status="UNKNOWN") for i in range(1, 4)})
        replace_json(self.directory / "plan.json", dict(synthetic=True, test=self._testMethodName))
        self.plan_sha = n.sha(self.directory / "plan.json")
        replace_json(self.directory / "candidate-cost-quota.json", self.seed)
        replace_json(self.directory / "candidate-quota-initial-snapshot.json", self.seed)
        replace_json(self.directory / "candidate-quota-genesis-policy.json", {**q.POLICY,
                     "seedSha256": n.sha(self.directory / "candidate-quota-initial-snapshot.json")})
        replace_json(self.directory / "state.json", dict(planSha256=self.plan_sha, requestsStarted=0, rounds={}, terminal=None))
        replace_json(self.directory / "send-ledger.json", dict(planSha256=self.plan_sha,
                     legacyManifestSha256=n.PINNED["paid-interruption-manifest.json"], calls=[], rejections=[], breaches=[]))
        self.monitor = q.QuotaMonitor(self.directory, self.plan_sha, n.raw_write)
        self.monitor.initialize()

    def runtime(self):
        return json.loads((self.directory / "candidate-cost-quota.json").read_bytes())

    def java(self, value):
        replace_json(self.directory / "candidate-cost-quota.json", value)

    def start(self, side="F"):
        state = q.read(self.monitor.state)
        state["rounds"] = {"DEV_F": dict(status="RUNNING", side=side)}
        self.monitor.persist(self.monitor.state, state)
        self.monitor.observe("APPLICATION_ENTER", update=lambda k, quota: k.update(runtimeStarted=True, activeSide=side))
        self.monitor.observe("QUESTION_BEGIN", update=lambda k, quota: k.update(activeQuestion="T1-002"))

    def pending(self, identity="new-hold", *, payload=None):
        value = self.runtime()
        hold = dict(requestId="new-request", callNumber=1, promptSha256=q.prompt_sha(payload) if payload else "a" * 64, status="PENDING")
        value["holds"][identity] = hold; self.java(value)
        self.monitor.observe("SYNTHETIC_PENDING")
        return identity

    def checkpoint(self):
        return self.monitor._load()[1]

    def returned(self, response, *, question="T1-002"):
        path = self.directory / "http-answers" / (question + ".json")
        path.parent.mkdir(exist_ok=True)
        raw = bytes_for(response)
        path.with_suffix(".body.bin").write_bytes(raw)
        replace_json(path, dict(responseComplete=True, responseBodySha256=q.digest(raw), responseBytes=len(raw)))
        return dict(httpEvidence=path.relative_to(self.directory).as_posix(), httpEvidenceSha256=n.sha(path),
                    responseBodySha256=q.digest(raw), questionId=question)

    def test_new_known_cost_cannot_roll_back_to_seed(self):
        self.start(); value = self.runtime(); value.update(knownCalls=68, knownCostYuan=.17312188)
        self.java(value); self.monitor.observe("NEW_KNOWN")
        self.java(self.seed)
        with self.assertRaisesRegex(q.QuotaRefused, "Latest known"): self.monitor.observe("ROLLBACK")

    def test_new_known_call_count_cannot_roll_back_with_unchanged_cost(self):
        self.start(); value = self.runtime(); value["knownCalls"] += 1
        self.java(value); self.monitor.observe("ZERO_PRICE_KNOWN")
        self.java(self.seed)
        with self.assertRaisesRegex(q.QuotaRefused, "Latest known"): self.monitor.observe("ROLLBACK")

    def test_zero_cost_usage_count_increase_is_accepted(self):
        self.start(); value = self.runtime(); value["knownCalls"] += 1
        self.java(value); result = self.monitor.observe("ZERO_PRICE_KNOWN")
        self.assertEqual(result["knownCalls"], 68)
        self.assertEqual(result["knownCostYuan"], "0.16312188")

    def test_new_unknown_is_sticky_across_new_monitor_instance(self):
        self.start(); value = self.runtime(); value["holds"]["new-unknown"] = dict(requestId="new-request", callNumber=1,
            promptSha256="a" * 64, status="UNKNOWN")
        self.java(value); self.monitor.observe("NEW_UNKNOWN"); self.java(self.seed)
        second = q.QuotaMonitor(self.directory, self.plan_sha, n.raw_write)
        with self.assertRaisesRegex(q.QuotaRefused, "Observed UNKNOWN"): second.observe("RESTART")

    def test_observed_unknown_content_cannot_change(self):
        self.start(); value = self.runtime(); value["holds"]["new-unknown"] = dict(requestId="new-request", callNumber=1,
            promptSha256="a" * 64, status="UNKNOWN")
        self.java(value); self.monitor.observe("NEW_UNKNOWN")
        value["holds"]["new-unknown"]["requestId"] = "replacement"
        self.java(value)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("ALTERED_UNKNOWN")

    def test_json_reorder_and_equivalent_decimal_are_accepted_after_running(self):
        self.start(); value = self.runtime()
        value["holds"] = dict(reversed(list(value["holds"].items())))
        value["knownCostYuan"] = "0.163121880000"; value["reservationYuan"] = "0.1000"; value["limitYuan"] = "1.00"
        self.java(value); self.monitor.observe("READY_ONLY_REORDER")
        self.assertEqual(self.checkpoint()["quota"]["knownCostYuan"], "0.16312188")

    def test_exact_seed_required_only_in_truly_pristine_state(self):
        value = self.runtime(); value["holds"] = dict(reversed(list(value["holds"].items())))
        self.java(value)
        with self.assertRaisesRegex(q.QuotaRefused, "exact seed"): self.monitor.observe("BEFORE_START")

    def test_running_ready_with_zero_questions_does_not_require_raw_seed(self):
        self.start(); value = self.runtime(); value["holds"] = dict(reversed(list(value["holds"].items())))
        self.java(value); self.monitor.observe("APPLICATION_READY")
        self.assertEqual(q.read(self.monitor.state)["requestsStarted"], 0)
        self.assertEqual(q.read(self.monitor.ledger)["calls"], [])

    def test_pending_becomes_unknown_with_identity_preserved_on_reopen(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"][identity]["status"] = "UNKNOWN"
        self.java(value)
        second = q.QuotaMonitor(self.directory, self.plan_sha, n.raw_write); second.observe("RESTART_READY")
        self.assertEqual(self.checkpoint()["knowledge"]["unknowns"][identity], value["holds"][identity])
        value["holds"].pop(identity); self.java(value)
        with self.assertRaises(q.QuotaRefused): second.observe("ILLEGAL_REFUND")

    def test_pending_disappearance_without_evidence_is_rejected(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"].pop(identity); self.java(value)
        with self.assertRaisesRegex(q.QuotaRefused, "per-identity"): self.monitor.observe("MISSING_PENDING")

    def test_pending_identity_mutation_is_rejected(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"][identity]["promptSha256"] = "b" * 64
        self.java(value)
        with self.assertRaisesRegex(q.QuotaRefused, "identity changed"): self.monitor.observe("MUTATED_PENDING")

    def test_cancel_unsent_requires_archived_http_and_budget_identity(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"].pop(identity); self.java(value)
        response = dict(status="REQUEST_TIMEOUT", budget=dict(requestId="new-request", modelCalls=0, termination="DEADLINE_EXCEEDED"))
        evidence = self.returned(response)
        self.monitor.observe("HTTP_RETURN", response=response, evidence=evidence)
        self.assertEqual(self.checkpoint()["knowledge"]["retired"][identity]["outcome"], "UNSENT")

    def test_unsent_transient_dictionary_without_saved_http_is_rejected(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"].pop(identity); self.java(value)
        response = dict(status="REQUEST_TIMEOUT", budget=dict(requestId="new-request", modelCalls=0, termination="DEADLINE_EXCEEDED"))
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("HTTP_RETURN", response=response)

    def test_offpeak_cancel_requires_complete_reason_format(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"].pop(identity); self.java(value)
        response = dict(status="GENERATION_FAILED", message="DeepSeek评测仅允许空闲时段调用；本次未发送。原因=PEAK_HOURS，最早接续=2026-11-19T18:00+08:00[Asia/Shanghai]",
                        budget=dict(requestId="new-request", modelCalls=1, termination="COMPLETE"))
        self.monitor.observe("HTTP_RETURN", response=response, evidence=self.returned(response))
        self.assertEqual(self.checkpoint()["knowledge"]["retired"][identity]["outcome"], "UNSENT")

    def test_fake_offpeak_substring_cannot_refund(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"].pop(identity); self.java(value)
        response = dict(status="SUCCESS", message="fake DeepSeek评测仅允许空闲时段调用；本次未发送。原因=whatever",
                        budget=dict(requestId="new-request", modelCalls=1, termination="COMPLETE"))
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("HTTP_RETURN", response=response, evidence=self.returned(response))

    def test_unknown_cannot_use_unsent_refund_path(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"][identity]["status"] = "UNKNOWN"
        self.java(value); self.monitor.observe("UNKNOWN"); value["holds"].pop(identity); self.java(value)
        response = dict(status="REQUEST_TIMEOUT", budget=dict(requestId="new-request", modelCalls=0, termination="DEADLINE_EXCEEDED"))
        with self.assertRaisesRegex(q.QuotaRefused, "Observed UNKNOWN"):
            self.monitor.observe("HTTP_RETURN", response=response, evidence=self.returned(response))

    def test_http_unsent_question_mismatch_is_rejected(self):
        self.start(); identity = self.pending(); value = self.runtime(); value["holds"].pop(identity); self.java(value)
        response = dict(status="REQUEST_TIMEOUT", budget=dict(requestId="new-request", modelCalls=0, termination="DEADLINE_EXCEEDED"))
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("HTTP_RETURN", response=response, evidence=self.returned(response, question="OTHER"))

    def test_missing_checkpoint_cannot_reinitialize(self):
        self.monitor.head.unlink()
        second = q.QuotaMonitor(self.directory, self.plan_sha, n.raw_write)
        with self.assertRaises(q.QuotaRefused): second.observe("RESTART")
        with self.assertRaises(q.QuotaRefused): second.initialize()

    def test_old_checkpoint_cannot_hide_new_observation(self):
        old = self.monitor.head.read_bytes(); self.monitor.observe("NEXT")
        self.monitor.head.write_bytes(old)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("RESTART")

    def test_truncated_chain_tail_is_rejected_by_state_and_checkpoint(self):
        self.monitor.observe("NEXT")
        self.monitor.journal.write_bytes(self.monitor.journal.read_bytes().rsplit(b"\n", 2)[0] + b"\n")
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("RESTART")

    def test_checkpoint_and_tail_rollback_is_detected_by_controller_anchors(self):
        old = self.monitor.head.read_bytes(); old_journal = self.monitor.journal.read_bytes(); self.monitor.observe("NEXT")
        self.monitor.journal.write_bytes(old_journal); self.monitor.head.write_bytes(old)
        with self.assertRaisesRegex(q.QuotaRefused, "anchor mismatch"): self.monitor.observe("RESTART")

    def test_missing_state_anchor_is_rejected(self):
        state = q.read(self.monitor.state); state.pop(q.ANCHOR); replace_json(self.monitor.state, state)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("RESTART")

    def test_missing_ledger_anchor_is_rejected(self):
        value = q.read(self.monitor.ledger); value.pop(q.ANCHOR); replace_json(self.monitor.ledger, value)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("RESTART")

    def test_outside_transaction_state_mutation_is_rejected(self):
        state = q.read(self.monitor.state); state["requestsStarted"] = 5; replace_json(self.monitor.state, state)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("RESTART")

    def test_orphan_send_event_is_rejected(self):
        directory = self.directory / "send-ledger-events"; directory.mkdir(); replace_json(directory / "orphan.json", {})
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("RESTART")

    def test_partial_anchor_commit_is_fail_closed_on_restart(self):
        original = self.monitor.writer
        def fail_head(path, value, **kwargs):
            if path == self.monitor.head: raise PermissionError("synthetic checkpoint failure")
            return original(path, value, **kwargs)
        self.monitor.writer = fail_head
        with self.assertRaises(PermissionError): self.monitor.observe("PARTIAL")
        with self.assertRaises(q.QuotaRefused): q.QuotaMonitor(self.directory, self.plan_sha, n.raw_write).observe("RESTART")

    def test_every_source_hash_is_same_original_quota_bytes(self):
        self.start(); value = self.runtime(); value["knownCostYuan"] = "0.16312188000"; self.java(value)
        original = (self.directory / "candidate-cost-quota.json").read_bytes()
        self.monitor.observe("SOURCE")
        event = self.checkpoint()
        self.assertEqual(base64.b64decode(event["runtimeRawBase64"]), original)
        self.assertEqual(event["runtimeRawSha256"], q.digest(original))
        self.assertEqual(event["quota"], q.quota_semantic(q.parse(original, exact=True)))

    def test_runtime_change_after_capture_does_not_corrupt_source_identity(self):
        self.start(); first = self.runtime(); first["knownCalls"] = 68; self.java(first)
        original_append = self.monitor._append
        def changing_append(*args, **kwargs):
            second = self.runtime(); second["knownCalls"] = 69; self.java(second)
            return original_append(*args, **kwargs)
        with patch.object(self.monitor, "_append", side_effect=changing_append): self.monitor.observe("RACE")
        event = self.checkpoint(); self.assertEqual(event["quota"]["knownCalls"], 68)
        self.assertEqual(q.parse(base64.b64decode(event["runtimeRawBase64"]), exact=True)["knownCalls"], 68)
        self.monitor.observe("NEXT"); self.assertEqual(self.checkpoint()["quota"]["knownCalls"], 69)

    def payload(self):
        payload = dict(model="deepseek-flash", temperature=0, max_tokens=8192,
                       messages=[dict(role="system", content="synthetic"), dict(role="user", content="=== 用户问题 ===\n测试\n")])
        return payload, bytes_for(payload)

    def real_gate(self, *, side="F"):
        # Use the production factory/lock/write hooks against this complete base.
        key = (self.directory.resolve(), self.plan_sha)
        production = n.quota_monitor()
        self.monitor = production
        self.start(side)
        gate = n.SendGate(self.directory / "send-ledger.json", self.plan_sha, "DEV_F",
                          clock=lambda: datetime(2026, 11, 21, 12, tzinfo=n.d.BEIJING), monotonic=lambda: 10.)
        gate.begin(dict(id="T1-002", question="测试"), 25.)
        return gate

    def completed(self, gate, permit, cost="0.000005", *, valid=True):
        usage = dict(prompt_tokens=1, completion_tokens=1, total_tokens=2, prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=1)
        body = bytes_for(dict(usage=usage)) if valid else b"{}"
        gate.after(permit, dict(responseBodyRaw=body, responseBodySha256=q.digest(body), responseBytes=len(body),
                   responseComplete=True, upstreamAttempted=True, responseJson=json.loads(body)))

    def test_real_f_before_matches_prompt_hash_and_persists_binding(self):
        gate = self.real_gate(); payload, raw = self.payload(); identity = self.pending(payload=payload)
        permit = gate.before(payload, raw)
        self.assertEqual(self.checkpoint()["knowledge"]["bindings"][identity], permit["token"])
        self.assertEqual(q.read(gate.events / (permit["token"] + "-permit.json"))["upstreamDeadlineMonotonic"], 25.)

    def test_real_f_without_matching_pending_has_zero_permits(self):
        gate = self.real_gate()
        with self.assertRaises(q.QuotaRefused): gate.before(*self.payload())
        self.assertEqual(gate.data["calls"], [])

    def test_real_b_observes_shared_quota_without_assuming_pending_support(self):
        gate = self.real_gate(side="B"); permit = gate.before(*self.payload())
        self.assertEqual(self.checkpoint()["evidence"]["token"], permit["token"])
        self.assertEqual(self.checkpoint()["evidence"]["holdId"], None)

    def test_pending_complete_releases_reservation_only_with_exact_settlement(self):
        gate = self.real_gate(); payload, raw = self.payload(); identity = self.pending(payload=payload)
        permit = gate.before(payload, raw); self.completed(gate, permit)
        self.assertIn(identity, self.checkpoint()["knowledge"]["pending"])
        value = self.runtime(); value["holds"].pop(identity); value["knownCalls"] += 1; value["knownCostYuan"] = "0.16312688"; self.java(value)
        self.monitor.observe("AFTER_JAVA_COMPLETE")
        latest = self.checkpoint(); self.assertEqual(latest["knowledge"]["retired"][identity]["outcome"], "KNOWN")
        commitment = Decimal(latest["quota"]["knownCostYuan"]) + Decimal(".1") * len(latest["quota"]["holds"])
        self.assertLess(commitment, Decimal(".56312188"))

    def test_pending_completed_without_known_increment_cannot_disappear(self):
        gate = self.real_gate(); payload, raw = self.payload(); identity = self.pending(payload=payload)
        permit = gate.before(payload, raw); self.completed(gate, permit)
        value = self.runtime(); value["holds"].pop(identity); self.java(value)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("FORGED_COMPLETE")

    def test_unknown_completion_cannot_refund_pending(self):
        gate = self.real_gate(); payload, raw = self.payload(); identity = self.pending(payload=payload)
        permit = gate.before(payload, raw); self.completed(gate, permit, valid=False)
        value = self.runtime(); value["holds"].pop(identity); value["knownCalls"] += 1; self.java(value)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("FORGED_COMPLETE")

    def test_before_persistence_failure_returns_no_permit(self):
        gate = self.real_gate(); payload, raw = self.payload(); identity = self.pending(payload=payload)
        def fail(path, value, **kwargs): raise PermissionError("synthetic no disk")
        self.monitor.writer = fail
        with self.assertRaises(PermissionError): gate.before(payload, raw)
        self.assertEqual(gate.data["calls"], [])
        self.assertEqual(self.runtime()["holds"][identity]["status"], "PENDING")

    def test_after_persistence_failure_keeps_pre_send_unknown_commitment(self):
        gate = self.real_gate(); payload, raw = self.payload(); identity = self.pending(payload=payload)
        permit = gate.before(payload, raw)
        checkpoint = self.monitor.head.read_bytes()
        def fail(*args, **kwargs): raise PermissionError("synthetic after-send disk failure")
        self.monitor.appender = fail
        with self.assertRaises(PermissionError): self.completed(gate, permit)
        self.assertEqual(self.monitor.head.read_bytes(), checkpoint)
        durable = q.read(self.monitor.ledger)["calls"][0]
        self.assertFalse(durable["usageKnown"]); self.assertEqual(durable["result"], None)
        self.assertEqual(self.runtime()["holds"][identity]["status"], "PENDING")
        with self.assertRaises(q.QuotaRefused): q.QuotaMonitor(self.directory, self.plan_sha, n.raw_write).observe("RESTART")

    def test_real_b_and_f_application_ready_exit_hooks(self):
        @contextmanager
        def fake(side, proxy, out, **kwargs):
            value = self.runtime(); value["holds"] = dict(reversed(list(value["holds"].items()))); self.java(value)
            yield ("http://127.0.0.1:1", "synthetic")
        for side in ("B", "F"):
            out = self.directory / side; out.mkdir()
            with patch.object(n, "_old_application", side_effect=fake):
                with n.application(side, None, out): pass
        contexts = [q.parse(line)["context"] for line in self.monitor.journal.read_bytes().splitlines()]
        self.assertEqual(contexts.count("APPLICATION_ENTER"), 2)
        self.assertEqual(contexts.count("APPLICATION_READY"), 2)
        self.assertEqual(contexts.count("APPLICATION_EXIT"), 2)

    def test_external_controller_owner_blocks_standalone_observation(self):
        (self.directory / "controller.lock").write_bytes(b"different-process-owner")
        with self.assertRaises(n.Refused): n.quota_monitor().observe("AUDIT")
        self.assertEqual((self.directory / "controller.lock").read_bytes(), b"different-process-owner")

    def test_missing_entire_monitor_never_uses_seed_fallback(self):
        # An initialized current state cannot be legitimized by missing history.
        self.monitor.head.unlink()
        with self.assertRaises(q.QuotaRefused): n.check_candidate_quota()

    def test_rejected_quota_stops_persistently_even_after_runtime_restore(self):
        self.start(); value = self.runtime(); value["knownCostYuan"] = ".8"; value["knownCalls"] = 68
        value["holds"]["new-unknown"] = dict(requestId="new", callNumber=1, promptSha256="a" * 64, status="UNKNOWN")
        self.java(value)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("OVER_BUDGET")
        observed = self.checkpoint()
        self.assertEqual(observed["knowledge"]["knownCostYuan"], "0.8")
        self.assertIn("new-unknown", observed["knowledge"]["unknowns"])
        self.assertIn("stopReason", observed["knowledge"])
        self.java(self.seed)
        with self.assertRaisesRegex(q.QuotaRefused, "Persisted quota"):
            q.QuotaMonitor(self.directory, self.plan_sha, n.raw_write).observe("ROLLBACK_AFTER_REFUSAL")

    def test_journal_prefix_rewrite_is_detected_despite_decoded_cache(self):
        self.monitor.observe("NEXT")
        raw = self.monitor.journal.read_bytes()
        self.monitor.journal.write_bytes(raw.replace(b"GENESIS", b"GEXESIS", 1))
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("TAMPER")

    def test_partial_append_cannot_be_repaired_or_ignored(self):
        with self.monitor.journal.open("ab") as stream: stream.write(b'{"partial":')
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("PARTIAL_TAIL")

    def quota_stop(self):
        self.start()
        value = self.runtime(); value["knownCostYuan"] = ".8"; self.java(value)
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("OVER_BUDGET")
        state = q.read(self.monitor.state)
        state["terminal"] = "INTERRUPTED_NO_REPLAY"
        state["rounds"]["DEV_F"].update(status="INTERRUPTED", reason="QuotaRefused: synthetic quota stop",
            finishedAt="2026-11-21T12:00:01+08:00", completedAnswers=0)
        return state

    def test_quota_stop_allows_only_same_round_irreversible_interruption(self):
        state = self.quota_stop(); knowledge = copy.deepcopy(self.checkpoint()["knowledge"])
        self.java(self.seed)  # Replaced runtime cannot affect the sealed liability.
        self.monitor.persist(self.monitor.state, state)
        latest = self.checkpoint()
        self.assertEqual(latest["context"], "INTERRUPTED_AFTER_QUOTA_STOP")
        self.assertEqual(latest["knowledge"], knowledge)
        self.assertEqual(latest["quota"]["knownCostYuan"], "0.8")
        self.assertEqual(q.read(self.monitor.state)["terminal"], "INTERRUPTED_NO_REPLAY")
        with self.assertRaises(q.QuotaRefused): self.monitor.observe("NO_CONTINUATION")

    def test_quota_stop_interruption_cannot_increase_request_counter(self):
        state = self.quota_stop(); state["requestsStarted"] += 1
        with self.assertRaises(q.QuotaRefused): self.monitor.persist(self.monitor.state, state)
        self.assertIsNone(q.read(self.monitor.state)["terminal"])

    def test_quota_stop_cannot_modify_ledger_or_resume_round(self):
        state = self.quota_stop()
        with self.assertRaises(q.QuotaRefused): self.monitor.persist(self.monitor.ledger, q.read(self.monitor.ledger))
        state["terminal"] = None; state["rounds"]["DEV_F"]["status"] = "RUNNING"
        with self.assertRaises(q.QuotaRefused): self.monitor.persist(self.monitor.state, state)

    def test_quota_stop_interruption_cannot_add_round_or_mark_completed(self):
        state = self.quota_stop(); state["rounds"]["TARGETS"] = dict(status="RUNNING")
        with self.assertRaises(q.QuotaRefused): self.monitor.persist(self.monitor.state, state)
        state["rounds"].pop("TARGETS"); state["rounds"]["DEV_F"]["status"] = "COMPLETED"
        with self.assertRaises(q.QuotaRefused): self.monitor.persist(self.monitor.state, state)

    def test_quota_stop_interruption_cannot_clear_stop_or_change_state_identity(self):
        state = self.quota_stop(); state["stopReason"] = None
        with self.assertRaises(q.QuotaRefused): self.monitor.persist(self.monitor.state, state)
        state.pop("stopReason"); state["planSha256"] = "replacement-plan"
        with self.assertRaises(q.QuotaRefused): self.monitor.persist(self.monitor.state, state)
        self.assertIn("stopReason", self.checkpoint()["knowledge"])

    def test_interruption_after_quota_stop_persistence_failure_keeps_original_stop(self):
        state = self.quota_stop(); original = self.monitor.writer
        def fail(path, value, **kwargs):
            if path == self.monitor.state: raise PermissionError("synthetic terminal persistence unavailable")
            return original(path, value, **kwargs)
        self.monitor.writer = fail
        with self.assertRaises(PermissionError): self.monitor.persist(self.monitor.state, state)
        with self.assertRaises(q.QuotaRefused): q.QuotaMonitor(self.directory, self.plan_sha, n.raw_write).observe("NO_REBUILD")


if __name__ == "__main__":
    EVIDENCE.mkdir(parents=True, exist_ok=False)
    result = unittest.TestResult()
    unittest.defaultTestLoader.loadTestsFromTestCase(MonitorChecks).run(result)
    report = dict(testsRun=result.testsRun, passed=result.testsRun-len(result.errors)-len(result.failures),
                  failures=len(result.failures), errors=len(result.errors),
                  failureDetails=[dict(test=str(t), trace=s) for t, s in result.errors + result.failures],
                  realSupplierCalls=0, realKeyReads=0, javaProcesses=0,
                  syntheticOnly=True, sourceSha256=n.sha(Path(q.__file__)))
    replace_json(EVIDENCE / "verification.json", report)
    print(json.dumps({k: report[k] for k in ("testsRun", "passed", "failures", "errors")}))
    if not result.wasSuccessful(): sys.exit(1)
