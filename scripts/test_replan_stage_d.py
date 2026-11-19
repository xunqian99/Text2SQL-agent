"""Local tests for D state, actual send admission and durable accounting; no keys/network."""
from datetime import datetime,timezone,timedelta
from decimal import Decimal
import json
import io
import hashlib
import urllib.error
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_replan_stage_d as d


def payload(question="原问题",correction=False):
    text="=== 用户问题 ===\n"+question+"\n"
    if correction: text+="\n\n=== 上一次 SQL 与评估反馈 ===\n上一条SQL与错误"
    value=dict(model="deepseek-flash",temperature=0,max_tokens=8192,stream=False,
               messages=[dict(role="system",content="只读"),dict(role="user",content=text)])
    return value,json.dumps(value,ensure_ascii=False).encode()


def result(usage=True,**changes):
    value=dict(upstreamAttempted=True,httpStatus=200,responseComplete=True,
      startedAtUtc="2026-11-21T12:00:00+00:00",finishedAtUtc="2026-11-21T12:00:01+00:00",
      responseJson={"usage":dict(prompt_tokens=10,completion_tokens=20,total_tokens=30,
                      prompt_cache_hit_tokens=3,prompt_cache_miss_tokens=7)} if usage else None)
    value.update(changes);return value


class SendGateTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/"ledger.json"
        d.write(self.path,dict(planSha256="plan",calls=[],rejections=[],breaches=[]))
        self.clock=lambda:datetime(2026,11,21,12,tzinfo=d.BEIJING)
        self.gate=d.SendGate(self.path,"plan","DEV_F",clock=self.clock,monotonic=lambda:100)
        self.gate.begin(dict(id="Q1",question="原问题"),115)
    def tearDown(self): self.temp.cleanup()

    def test_one_initial_then_one_correction_never_three(self):
        first=self.gate.before(*payload());self.gate.after(first,result())
        second=self.gate.before(*payload(correction=True));self.gate.after(second,result())
        with self.assertRaisesRegex(d.Refused,"PER_QUESTION_CALL_LIMIT"): self.gate.before(*payload(correction=True))
        self.assertEqual(len(self.gate.data["calls"]),2)
        self.assertEqual(self.gate.summary()["knownUsageCalls"],2)

    def test_sdk_duplicate_initial_is_not_a_second_paid_attempt(self):
        self.gate.before(*payload())
        with self.assertRaisesRegex(d.Refused,"FIRST_OR_CORRECTION_BINDING"): self.gate.before(*payload())
        self.assertEqual(len(self.gate.data["calls"]),1)

    def test_wrong_question_and_then_valid_call_cannot_continue(self):
        with self.assertRaisesRegex(d.Refused,"QUESTION_BINDING"): self.gate.before(*payload("别的问题"))
        with self.assertRaisesRegex(d.Refused,"PRIOR_"): self.gate.before(*payload())
        self.assertEqual(len(self.gate.data["calls"]),0)

    def test_call_after_deadline_or_end_never_gets_permit(self):
        self.gate.end()
        with self.assertRaisesRegex(d.Refused,"NO_ACTIVE"): self.gate.before(*payload())

    def test_peak_and_wire_delay_reserve_are_checked_before_admission(self):
        self.gate.clock=lambda:datetime(2026,11,19,8,57,50,tzinfo=d.BEIJING)
        # 130 seconds to peak looks >120, but 15-second parent DNS/connect margin is required too.
        with self.assertRaisesRegex(d.Refused,"PEAK_BOUNDARY_RESERVE"): self.gate.before(*payload())
        self.assertEqual(len(self.gate.data["calls"]),0)

    def test_unknown_is_held_across_process_and_version(self):
        permit=self.gate.before(*payload());self.gate.after(permit,result(False))
        fresh=d.SendGate(self.path,"plan","B1",clock=self.clock,monotonic=lambda:100)
        self.assertEqual(fresh.committed(),Decimal(".10"))
        self.assertEqual(fresh.summary()["unknownUsageCalls"],1)
        self.assertEqual(fresh.summary()["unknownHeldYuan"],"0.10")

    def test_full_usage_and_cache_identity_settles_exactly_once(self):
        permit=self.gate.before(*payload());self.gate.after(permit,result())
        self.assertEqual(self.gate.committed(),Decimal("0.00008706"))
        self.assertEqual(self.gate.summary()["cacheHitTokens"],3)
        with self.assertRaisesRegex(d.Refused,"Duplicate"): self.gate.after(permit,result())
        self.assertEqual(self.gate.summary()["knownUsageCalls"],1)

    def test_partial_or_inconsistent_usage_never_becomes_zero_cost(self):
        value=result();value["responseJson"]["usage"]["total_tokens"]=999
        permit=self.gate.before(*payload());self.gate.after(permit,value)
        self.assertEqual(self.gate.committed(),d.RESERVE)
        self.assertFalse(self.gate.data["calls"][0]["usageKnown"])

    def test_input_or_output_estimate_breach_retains_actual_cost_then_stops(self):
        value=result();value["responseJson"]["usage"].update(prompt_tokens=10000,prompt_cache_hit_tokens=0,prompt_cache_miss_tokens=10000,total_tokens=10020)
        permit=self.gate.before(*payload());self.gate.after(permit,value)
        self.assertEqual(self.gate.data["calls"][0]["costYuan"],"0.01008")
        self.assertTrue(self.gate.data["breaches"])
        with self.assertRaisesRegex(d.Refused,"PRIOR_"): self.gate.before(*payload(correction=True))

    def test_global_quota_is_shared_not_reset_for_second_system(self):
        for index in range(10):
            if index: self.gate.begin(dict(id="Q"+str(index+1),question="原问题"),115)
            self.gate.before(*payload());self.gate.end()
        fresh=d.SendGate(self.path,"plan","B1",clock=self.clock,monotonic=lambda:100)
        fresh.begin(dict(id="OTHER",question="原问题"),115)
        with self.assertRaisesRegex(d.Refused,"GLOBAL_COST_RESERVATION"): fresh.before(*payload())
        self.assertEqual(len(fresh.data["calls"]),10)
        self.assertEqual(fresh.committed(),Decimal("1.00"))

    def test_global_call_limit_is_independent_of_cost_quota(self):
        with patch.object(d,"MAX_CALLS",1):
            self.gate.before(*payload());self.gate.end();self.gate.begin(dict(id="Q2",question="原问题"),115)
            with self.assertRaisesRegex(d.Refused,"GLOBAL_CALL_LIMIT"): self.gate.before(*payload())

    def test_event_and_ledger_tampering_cannot_reopen_budget(self):
        self.gate.before(*payload());data=d.read(self.path);data["calls"]=[];d.write(self.path,data,replace=True)
        with self.assertRaisesRegex(d.Refused,"Orphan/missing"): d.SendGate(self.path,"plan","B1")

    def test_completed_permit_cannot_be_rewritten_behind_completion(self):
        permit=self.gate.before(*payload());self.gate.after(permit,result())
        path=self.gate.events/(permit["token"]+"-permit.json")
        value=d.read(path);value["requestBodySha256"]="changed";d.write(path,value,replace=True)
        with self.assertRaisesRegex(d.Refused,"permit admission drift"): d.SendGate(self.path,"plan","B1")

    def test_http_budget_reconciliation_and_late_known_cost(self):
        permit=self.gate.before(*payload());self.gate.after(permit,result())
        row=dict(id="Q1",body=dict(budget=dict(timeoutMs=15000,maxModelCalls=2,modelCalls=1,unknownUsageCalls=1)),
                 returnedAt="2026-11-21T12:00:00.5+00:00")
        reconciled=self.gate.reconcile([row]);totals=self.gate.summary(reconciliation=reconciled)
        self.assertEqual(totals["firstAnswerCalls"],1);self.assertEqual(totals["correctionCalls"],0)
        self.assertEqual(totals["lateKnownUsageCalls"],1)
        self.assertEqual(totals["lateKnownCostYuan"],"0.00008706")
        self.assertEqual(reconciled["questions"][0]["runtimeUnknownUsageCalls"],1)
        row["body"]["budget"]["modelCalls"]=0
        with self.assertRaisesRegex(d.Refused,"budget/send reconciliation"): self.gate.reconcile([row])

    def test_strict_json_rejects_nested_duplicate_and_non_finite(self):
        for raw in ('{"usage":{"tokens":1,"tokens":2}}','{"value":NaN}'):
            with self.subTest(raw=raw),self.assertRaises(ValueError): d.strict_json(raw)

    def test_options_that_change_model_or_charge_are_rejected(self):
        value,raw=payload();value["tools"]=[dict(type="web_search")]
        with self.assertRaisesRegex(d.Refused,"UNREGISTERED_MODEL_OPTION"): self.gate.before(value,json.dumps(value).encode())


class StageStateTest(unittest.TestCase):
    def test_time_boundaries_weekend_and_conservative_holiday(self):
        cases=[(datetime(2026,11,19,8,57,59,tzinfo=d.BEIJING),120,True),
          (datetime(2026,11,19,8,58,tzinfo=d.BEIJING),120,False),
          (datetime(2026,11,19,9,tzinfo=d.BEIJING),120,False),
          (datetime(2026,11,19,12,tzinfo=d.BEIJING),1740,True),
          (datetime(2026,11,19,13,31,tzinfo=d.BEIJING),1740,False),
          (datetime(2026,11,19,18,tzinfo=d.BEIJING),1740,True),
          (datetime(2026,11,21,10,tzinfo=d.BEIJING),1740,True),
          (datetime(2026,11,23,8,31,tzinfo=d.BEIJING),1740,False)]
        for at,guard,allowed in cases:
            with self.subTest(at=at): self.assertEqual(d.window(at,guard)["allowed"],allowed)

    def test_development_failure_cannot_replay_or_select_c2(self):
        state=dict(rounds={"DEV_F":dict(status="COMPLETED",gate=dict(passed=False))},terminal=None)
        with self.assertRaisesRegex(d.Refused,"Development failed"): d.next_round(state)
        state["rounds"]["DEV_F"]["status"]="INTERRUPTED"
        with self.assertRaisesRegex(d.Refused,"never replayed"): d.next_round(state)

    def test_targets_are_required_before_formal(self):
        state=dict(rounds={"DEV_F":dict(status="COMPLETED",gate=dict(passed=True))},terminal=None)
        self.assertEqual(d.next_round(state),"TARGETS")
        state["rounds"]["TARGETS"]=dict(status="COMPLETED",gate=dict(passed=False))
        with self.assertRaisesRegex(d.Refused,"Original targets failed"): d.next_round(state)

    def test_formal_slow_both_or_unknown_cost_cannot_promote(self):
        summary=dict(total=100,correct=89,wallP95Ms=13000,timeoutCount=4,newDangerousAllowances=0,accounting=dict(unknownUsageCalls=0))
        rounds={n:dict(summary=json.loads(json.dumps(summary))) for n in d.FORMAL}
        self.assertTrue(d.formal_gate(rounds)["passed"])
        for n in d.FORMAL: rounds[n]["summary"]["wallP95Ms"]=14000
        self.assertFalse(d.formal_gate(rounds)["passed"])
        for n in d.FORMAL: rounds[n]["summary"]["wallP95Ms"]=13000
        rounds["B1"]["summary"]["accounting"]["unknownUsageCalls"]=1
        self.assertFalse(d.formal_gate(rounds)["passed"])

    def test_development_full_layer_denominators_and_no_high_score_rerun(self):
        group=dict(total=100,correct=71,timeoutCount=9,byLayer={"multi_join_deep":dict(total=10,correct=4),"business_semantics":dict(total=13,correct=6)})
        self.assertTrue(d.dev_gate(group)["passed"])
        group["byLayer"]["multi_join_deep"]["total"]=9
        self.assertFalse(d.dev_gate(group)["passed"])

    def test_formal_pass_still_does_not_let_root_read_unseen(self):
        summary=dict(total=100,correct=90,wallP95Ms=12000,timeoutCount=0,accounting=dict(unknownUsageCalls=0))
        rounds={"DEV_F":dict(status="COMPLETED",gate=dict(passed=True)),"TARGETS":dict(status="COMPLETED",gate=dict(passed=True)),
                **{n:dict(status="COMPLETED",summary=summary) for n in d.FORMAL}}
        with self.assertRaisesRegex(d.Refused,"INDEPENDENT_UNSEEN"): d.next_round(dict(rounds=rounds,terminal=None))


class HttpEvidenceTest(unittest.TestCase):
    def test_raw_error_response_survives_parse_rejection(self):
        raw=b'{"budget":{"modelCalls":0,"modelCalls":1}}'
        error=urllib.error.HTTPError("http://127.0.0.1",400,"bad",{},io.BytesIO(raw))
        with tempfile.TemporaryDirectory() as temp,patch.object(d.urllib.request,"build_opener") as opener:
            opener.return_value.open.side_effect=error
            evidence=Path(temp)/"answer.json"
            with self.assertRaisesRegex(ValueError,"Duplicate JSON key"):
                d.http_json("http://127.0.0.1",evidence=evidence)
            self.assertEqual(evidence.with_suffix(".body.bin").read_bytes(),raw)
            meta=d.read(evidence)
            self.assertEqual(meta["httpStatus"],400)
            self.assertEqual(meta["responseBodySha256"],hashlib.sha256(raw).hexdigest())
            self.assertTrue(meta["responseComplete"])
            self.assertIn("Duplicate JSON key",meta["error"])


if __name__=="__main__": unittest.main(verbosity=2)
