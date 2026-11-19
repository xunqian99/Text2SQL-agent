"""Independent revision-2 review. No keys, vendor endpoints or unseen datasets."""
from __future__ import annotations

import copy
import hashlib
import importlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT/"scripts"))
import run_replan_stage_d as d

PLAN_SHA = "de6c10f1365f53345a37d238efafd1c00afadb506bab75d102b36f050d601016"


def save(path, value):
    d.write(OUT/path, value)


def identities():
    assert d.sha(d.BATCH/"plan.json") == PLAN_SHA
    plan = d.read(d.BATCH/"plan.json")
    assert plan["revision"] == 2 and plan["samePaidBatch"] == "batch-1"
    assert plan["supersedesRejectedPlanSha256"] == d.INITIAL_PLAN_SHA
    d.verify(plan)
    git_matches = 0
    for relative, expected in plan["files"].items():
        if "/tool-runtime/" in relative: continue
        result = subprocess.run(["git", "show", "HEAD:"+relative], cwd=ROOT,
                env=d.clean_env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        assert hashlib.sha256(result.stdout).hexdigest() == expected, relative
        git_matches += 1
    preflight = d.read(d.BATCH/"preflight.json")
    assert preflight["passed"] is True and preflight["planSha256"] == PLAN_SHA
    for relative, expected in preflight["evidence"].items():
        assert d.sha(ROOT/relative) == expected, relative
        result = subprocess.run(["git", "show", "HEAD:"+relative], cwd=ROOT,
                env=d.clean_env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        assert hashlib.sha256(result.stdout).hexdigest() == expected, relative
    states = []
    for batch in (d.INITIAL_BATCH, d.BATCH):
        state = d.read(batch/"state.json"); ledger = d.read(batch/"send-ledger.json")
        assert state["requestsStarted"] == 0 and not state["rounds"] and state["terminal"] is None
        assert not ledger["calls"] and not ledger["rejections"] and not ledger["breaches"]
        states.append(dict(path=batch.relative_to(ROOT).as_posix(), stateSha256=d.sha(batch/"state.json"), ledgerSha256=d.sha(batch/"send-ledger.json")))
    assert d.read(d.BASE/"independent-review/decision.json")["accepted"] is False
    save("identity-verification.json", dict(planSha256=PLAN_SHA, boundFiles=len(plan["files"]),
        gitRawByteMatches=git_matches, runtimeFiles=len(plan["files"])-git_matches,
        preflightEvidenceAndGitBytesVerified=len(preflight["evidence"]),
        databaseIdentityRechecked=True, originalRejectionUnchanged=True, states=states, providerCalls=0))


def runtime_derivation():
    plan=d.read(d.BATCH/"plan.json");runtime=d.BATCH/"tool-runtime"
    compared=0
    with zipfile.ZipFile(ROOT/d.SYSTEMS["F"]["jar"]) as jar:
        for relative,expected in plan["files"].items():
            path=ROOT/relative
            if not path.is_relative_to(runtime):continue
            part=path.relative_to(runtime).as_posix()
            if part.startswith("classes/") or part.startswith("lib/"):
                assert hashlib.sha256(jar.read("BOOT-INF/"+part)).hexdigest()==expected,part
                compared+=1
    compiled=OUT/"recompiled-helper";compiled.mkdir()
    cp=str(runtime/"classes")+d.os.pathsep+str(runtime/"lib/*")
    result=subprocess.run([str(d.JAVAC),"-encoding","UTF-8","-cp",cp,"-d",str(compiled),
        str(ROOT/"reports/replan-stage-a-20261119/ReferenceContractScorer.java"),
        str(ROOT/"scripts/replan/StageDScoringBridge.java")],cwd=ROOT,env=d.clean_env(),
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=60)
    d.write_bytes(OUT/"helper-recompile.stdout.bin",result.stdout)
    d.write_bytes(OUT/"helper-recompile.stderr.bin",result.stderr)
    helpers=[]
    for path in (runtime/"helper-classes").rglob("*"):
        if not path.is_file():continue
        local=compiled/path.relative_to(runtime/"helper-classes")
        assert d.sha(local)==d.sha(path)
        helpers.append(path.relative_to(runtime/"helper-classes").as_posix())
    save("runtime-derivation.json",dict(planSha256=PLAN_SHA,jarEntriesMatched=compared,
        independentlyRecompiledHelperClasses=helpers,totalMatched=compared+len(helpers),providerCalls=0))


def supplied_suite():
    temp_root = OUT/"unit-temp"; temp_root.mkdir()
    tempfile.tempdir = str(temp_root)
    loader = unittest.TestLoader()
    suite = unittest.TestSuite(loader.loadTestsFromModule(importlib.import_module(name))
        for name in ("test_replan_stage_d", "test_replan_stage_d_proxy"))
    stream = io.StringIO()
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    d.write_bytes(OUT/"supplied-suite.log", stream.getvalue().encode())
    save("supplied-suite.json", dict(testsRun=result.testsRun, failures=len(result.failures),
        errors=len(result.errors), skipped=len(result.skipped), passed=result.wasSuccessful(),
        scope="All supplied controller/proxy tests on the fixed revision-2 source", providerCalls=0))


def deep_cases():
    cases = []
    def check(name, condition, **detail):
        cases.append(dict(id=name, passed=bool(condition), **detail))
        assert condition, name
    base = OUT/"independent-cases"; base.mkdir()
    def make(name):
        root = base/name; batch = root/"batch"; review = root/"review"
        root.mkdir()
        rows = [dict(id=f"Q{i:03}",body=dict(sql=f"SELECT {i}",status="SUCCESS")) for i in range(100)]
        score = dict(total=100,correct=90,items=[dict(id=r["id"],correct=i<90) for i,r in enumerate(rows)])
        d.write(batch/"DEV_F/responses.json",dict(items=rows));d.write(batch/"DEV_F/score.json",score)
        d.write(review/"diagnostic.json",dict(scope="independent complete 100-row synthetic audit"))
        summary=dict(total=100,correct=90,timeoutCount=0,newDangerousAllowances=None,
            byLayer={"multi_join_deep":dict(total=10,correct=8),"business_semantics":dict(total=13,correct=10)})
        state=dict(planSha256="synthetic-plan",requestsStarted=100,terminal=None,
                   rounds={"DEV_F":dict(status="COMPLETED",summary=summary,gate=d.dev_gate(summary))})
        report=dict(format="replan-d-round-safety-v1",round="DEV_F",planSha256="synthetic-plan",accepted=True,
            responsesSha256=d.sha(batch/"DEV_F/responses.json"),scoreSha256=d.sha(batch/"DEV_F/score.json"),
            independence=dict(candidateImplementation=False,questionAuthoring=False,paidOutputTuning=False),
            newDangerousAllowances=0,
            items=[dict(id=r["id"],sqlSha256=hashlib.sha256(r["body"]["sql"].encode()).hexdigest(),dangerousAllowed=False,reason="independently checked synthetic read-only expression") for r in rows],
            evidence={"review/diagnostic.json":d.sha(review/"diagnostic.json")})
        return root,batch,review,state,report
    def attempt(name, mutate=None, expected=None, after=None):
        root,batch,review,state,report=make(name)
        if mutate: mutate(report,root,batch,review,state)
        if report is not None:d.write(review/"rounds/DEV_F.json",report)
        with patch.object(d,"ROOT",root),patch.object(d,"BATCH",batch),patch.object(d,"REVIEW_ROOT",review):
            try:
                view=d.audited_rounds(state)
            except d.Refused as error:
                check(name,expected is not None and expected in str(error),reason=str(error))
                return
            if expected is not None:check(name,False,reason="unexpected acceptance")
            check(name,view["rounds"]["DEV_F"]["independentSafetyReviewVerified"] is True
                and view["rounds"]["DEV_F"]["gate"]["passed"] is True
                and state["rounds"]["DEV_F"]["summary"]["newDangerousAllowances"] is None,
                nextRound=d.next_round(view))
            if after: after(root,batch,review,state,report,view)
    attempt("complete_100_review_accepts_without_rewriting_summary")
    for field in ("planSha256","responsesSha256","scoreSha256"):
        attempt("wrong_"+field,lambda r,*_,f=field:r.update({f:"wrong"}),"identity/denominator")
    attempt("missing_last_review_id",lambda r,*_:r["items"].pop(),"identity/denominator")
    attempt("swapped_review_ids",lambda r,*_:r["items"].reverse(),"identity/denominator")
    attempt("wrong_sql_hash",lambda r,*_:r["items"][42].update(sqlSha256="wrong"),"per-answer evidence")
    attempt("integer_dangerous_flag",lambda r,*_:r["items"][42].update(dangerousAllowed=0),"per-answer evidence")
    attempt("empty_reason",lambda r,*_:r["items"][42].update(reason="  "),"per-answer evidence")
    attempt("boolean_total",lambda r,*_:r.update(newDangerousAllowances=False),"count mismatch")
    attempt("count_mismatch",lambda r,*_:r.update(newDangerousAllowances=1),"count mismatch")
    attempt("false_independence_claim",lambda r,*_:r["independence"].update(paidOutputTuning=True),"identity/denominator")
    attempt("missing_diagnostic",lambda r,*_:r.update(evidence={}),"diagnostic evidence missing")
    attempt("wrong_diagnostic_hash",lambda r,*_:r.update(evidence={"review/diagnostic.json":"wrong"}),"diagnostic evidence drift")
    def outside(r,root,*_):
        d.write(root/"outside.json",dict(outside=True));r["evidence"]={"outside.json":d.sha(root/"outside.json")}
    attempt("diagnostic_outside_review_root",outside,"diagnostic evidence drift")
    def replaced(root,batch,review,state,report,view):
        report["replacement"]=True;d.write(review/"rounds/DEV_F.json",report,replace=True)
        try:d.audited_rounds(state)
        except d.Refused as error:check("consumed_accepted_report_cannot_change","Previously consumed" in str(error))
        else:check("consumed_accepted_report_cannot_change",False)
    attempt("accept_then_replace",after=replaced)
    root,batch,review,state,report=make("reject_then_accept")
    report.update(accepted=False,newDangerousAllowances=1)
    report["items"][42]["dangerousAllowed"]=True
    d.write(review/"rounds/DEV_F.json",report)
    with patch.object(d,"ROOT",root),patch.object(d,"BATCH",batch),patch.object(d,"REVIEW_ROOT",review):
        try:d.audited_rounds(state)
        except d.Refused as error:check("valid_rejection_is_consumed_and_blocks","rejected actual outputs" in str(error) and (batch/"safety-review-events/DEV_F.json").is_file())
        else:check("valid_rejection_is_consumed_and_blocks",False)
        report.update(accepted=True,newDangerousAllowances=0);report["items"][42]["dangerousAllowed"]=False
        d.write(review/"rounds/DEV_F.json",report,replace=True)
        try:d.audited_rounds(state)
        except d.Refused as error:check("consumed_rejection_cannot_become_acceptance","Previously consumed" in str(error))
        else:check("consumed_rejection_cannot_become_acceptance",False)
    root,batch,review,state,report=make("missing_report")
    with patch.object(d,"ROOT",root),patch.object(d,"BATCH",batch),patch.object(d,"REVIEW_ROOT",review):
        try:d.audited_rounds(state)
        except d.Refused as error:check("missing_report_blocks","Pending independent" in str(error))
        else:check("missing_report_blocks",False)
        try:d.next_round(state)
        except d.Refused as error:check("raw_pending_state_cannot_advance","await bound independent" in str(error))
        else:check("raw_pending_state_cannot_advance",False)
    group=state["rounds"]["DEV_F"]["summary"]
    check("missing_or_null_dangerous_is_not_zero",not d.dev_gate(group)["passed"])
    formal={n:dict(summary=dict(total=100,correct=90,wallP95Ms=13000,timeoutCount=0,accounting=dict(unknownUsageCalls=0),newDangerousAllowances=None)) for n in d.FORMAL}
    check("formal_missing_safety_is_pending",d.formal_metric_gate(formal)["passed"] and not d.formal_gate(formal)["passed"])
    save("independent-review-boundaries.json",dict(planSha256=PLAN_SHA,passed=len(cases),checks=cases,providerCalls=0))


def integrations():
    source = OUT.parent/"review_submission_1.py"
    spec=importlib.util.spec_from_file_location("prior_independent_fixture",source)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    module.OUT=OUT;module.EXPECTED_PLAN=PLAN_SHA
    module.real_jar_corrections(d.read(d.BATCH/"plan.json"))
    # The prior duplicate-body fixture intentionally asserted absence. Exercise
    # this revision directly with the original duplicate bytes in a gate result.
    raw=b'{ "usage": { "total_tokens": 10, "total_tokens": 0 } }\n'
    out=OUT/"raw-byte-cases";out.mkdir()
    rows=[]
    for name,body in (("duplicate",raw),("partial",b'{ "usage":'),("empty",b"")):
        path=out/(name+"-ledger.json");d.write(path,dict(planSha256=PLAN_SHA,calls=[],rejections=[],breaches=[]))
        gate=d.SendGate(path,PLAN_SHA,"INDEPENDENT_RAW_"+name,clock=lambda:datetime(2026,11,21,12,tzinfo=d.BEIJING))
        gate.begin(dict(id="Q1",question="原问题"),d.time.monotonic()+15)
        payload=dict(model="deepseek-flash",temperature=0,max_tokens=8192,messages=[dict(role="system",content="只读"),dict(role="user",content="=== 用户问题 ===\n原问题\n")])
        permit=gate.before(payload,json.dumps(payload,ensure_ascii=False).encode())
        gate.after(permit,dict(upstreamAttempted=True,responseComplete=name=="duplicate",httpStatus=200,
            responseJson=None,responseBodyRaw=body,responseBodySha256=hashlib.sha256(body).hexdigest(),responseBytes=len(body),
            error="D_LOCAL_RESPONSE:duplicate JSON object member" if name=="duplicate" else "TimeoutError"))
        call=gate.data["calls"][0];saved=gate.events/call["result"]["responseBodyFile"]
        assert saved.read_bytes()==body
        fresh=d.SendGate(path,PLAN_SHA,"INDEPENDENT_RESTART")
        assert fresh.committed()==d.RESERVE
        saved.write_bytes(body+b"tampered")
        try:d.SendGate(path,PLAN_SHA,"INDEPENDENT_RESTART")
        except d.Refused as error:assert "Original upstream response evidence drift" in str(error)
        else:raise AssertionError("raw bytes tampering accepted")
        rows.append(dict(id=name,exactBytesPersisted=True,restartHeldYuan=str(fresh.committed()),tamperingRejected=True))
    save("raw-byte-boundaries.json",dict(planSha256=PLAN_SHA,checks=rows,passed=len(rows),providerCalls=0))


def prekey_order():
    checks=[]
    for case in ("empty_valid","dev_review_missing","dev_review_valid"):
        root=OUT/"prekey-cases"/case;batch=root/"batch";reviews=root/"review"
        batch.mkdir(parents=True)
        plan=dict(format="independent-prekey-fixture",files={},systems={})
        d.write(batch/"plan.json",plan);plan_sha=d.sha(batch/"plan.json")
        state=dict(planSha256=plan_sha,requestsStarted=0,rounds={},terminal=None)
        if case!="empty_valid":
            rows=[dict(id=f"Q{i:03}",body=dict(sql=f"SELECT {i}")) for i in range(100)]
            d.write(batch/"DEV_F/responses.json",dict(items=rows));d.write(batch/"DEV_F/score.json",dict(total=100,correct=90))
            group=dict(total=100,correct=90,timeoutCount=0,newDangerousAllowances=None,
                byLayer={"multi_join_deep":dict(total=10,correct=8),"business_semantics":dict(total=13,correct=10)})
            record=dict(status="COMPLETED",summary=group,gate=d.dev_gate(group),evidence={
                "batch/DEV_F/responses.json":d.sha(batch/"DEV_F/responses.json"),
                "batch/DEV_F/score.json":d.sha(batch/"DEV_F/score.json")})
            d.write(batch/"DEV_F/summary.json",record)
            state["rounds"]["DEV_F"]={**record,"summarySha256":d.sha(batch/"DEV_F/summary.json")}
            state["requestsStarted"]=100
            if case=="dev_review_valid":
                d.write(reviews/"diagnostic.json",dict(independent=True))
                d.write(reviews/"rounds/DEV_F.json",dict(format="replan-d-round-safety-v1",round="DEV_F",planSha256=plan_sha,
                    accepted=True,responsesSha256=d.sha(batch/"DEV_F/responses.json"),scoreSha256=d.sha(batch/"DEV_F/score.json"),
                    independence=dict(candidateImplementation=False,questionAuthoring=False,paidOutputTuning=False),newDangerousAllowances=0,
                    evidence={"review/diagnostic.json":d.sha(reviews/"diagnostic.json")},
                    items=[dict(id=r["id"],sqlSha256=hashlib.sha256(r["body"]["sql"].encode()).hexdigest(),dangerousAllowed=False,reason="independent synthetic SQL review") for r in rows]))
        d.write(batch/"state.json",state)
        d.write(batch/"send-ledger.json",dict(planSha256=plan_sha,calls=[],rejections=[],breaches=[]))
        d.write(batch/"preflight.json",dict(passed=True,planSha256=plan_sha,evidence={}))
        d.write(reviews/"decision.json",dict(accepted=True,planSha256=plan_sha))
        for key in ("dev","targets"):d.write(batch/(key+"-reference.json"),dict(items=[dict(id="never-sent")]))
        key_reads=[]
        def key_sentinel():key_reads.append(True);raise d.Refused("INDEPENDENT_KEY_SENTINEL_NO_KEY_READ")
        with patch.object(d,"ROOT",root),patch.object(d,"BATCH",batch),patch.object(d,"REVIEW_ROOT",reviews),\
             patch.object(d,"verify",lambda *a,**k:None),patch.object(d,"require_window",lambda *a,**k:dict(allowed=True)),\
             patch.object(d,"real_key",key_sentinel):
            try:d.run_next()
            except d.Refused as error:reason=str(error)
            else:raise AssertionError("prekey fixture unexpectedly ran")
        expected_reads=0 if case=="dev_review_missing" else 1
        assert len(key_reads)==expected_reads
        assert not (batch/"controller.lock").exists()
        assert d.read(batch/"state.json")==state and not d.read(batch/"send-ledger.json")["calls"]
        checks.append(dict(id=case,passed=True,keyFunctionInvocations=len(key_reads),reason=reason,
            fixtureIdentityAndClockChecksMocked=True,actualKeyOrSendOccurred=False))
    save("prekey-prerequisite-order.json",dict(planSha256=PLAN_SHA,checks=checks,passed=len(checks),providerCalls=0))


if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    {"identities":identities,"runtime":runtime_derivation,"units":supplied_suite,"boundaries":deep_cases,"integrations":integrations,"prekey":prekey_order}[sys.argv[1]]()
