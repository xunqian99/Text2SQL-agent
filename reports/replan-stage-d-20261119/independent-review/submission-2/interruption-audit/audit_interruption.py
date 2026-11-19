"""Read-only audit of the interrupted DEV_F. Never calls a model or reads keys."""
from __future__ import annotations

from collections import Counter
from datetime import datetime,timedelta
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[5]
OUT=Path(__file__).resolve().parent
sys.dont_write_bytecode=True
sys.path.insert(0,str(ROOT/"scripts"))
import run_replan_stage_d as d

PLAN_SHA="de6c10f1365f53345a37d238efafd1c00afadb506bab75d102b36f050d601016"
B=d.BATCH


def next_peak(at):
    local=at.astimezone(d.BEIJING)
    for offset in range(8):
        date=local.date()+timedelta(days=offset)
        if date.weekday()>=5:continue
        for hour in (9,14):
            start=datetime.combine(date,datetime.min.time(),d.BEIJING)+timedelta(hours=hour)
            if start>local:return start
    raise AssertionError("no next peak")


def main():
    plan=d.read(B/"plan.json");assert d.sha(B/"plan.json")==PLAN_SHA
    d.verify(plan,database=False)
    state=d.read(B/"state.json");ledger=d.read(B/"send-ledger.json")
    interrupted=d.read(B/"DEV_F/interruption.json")
    assert state["terminal"]=="INTERRUPTED_NO_REPLAY" and list(state["rounds"])==["DEV_F"]
    assert state["rounds"]["DEV_F"]==interrupted and interrupted["status"]=="INTERRUPTED"
    assert ledger["planSha256"]==state["planSha256"]==PLAN_SHA
    source_paths=[B/"plan.json",B/"state.json",B/"send-ledger.json",B/"preflight.json",
        B/"candidate-cost-quota.json",B/"DEV_F-controller-outcome.json",B/"DEV_F-controller-output.log"]
    source_paths.extend(p for p in (B/"DEV_F").rglob("*") if p.is_file())
    source_paths.extend(p for p in (B/"send-ledger-events").iterdir() if p.is_file())
    source_paths.extend(B.glob("send-ledger.json.tmp-*"))
    manifest={p.relative_to(ROOT).as_posix():d.sha(p) for p in sorted(source_paths)}
    rows=[d.strict_json(line) for line in (B/"DEV_F/responses.jsonl").read_text(encoding="utf-8-sig").splitlines()]
    expected_ids=[i["id"] for i in d.read(B/"dev-reference.json")["items"]]
    ids=[r["id"] for r in rows]
    assert len(ids)==len(set(ids))==state["requestsStarted"]==interrupted["completedAnswers"]==68
    assert ids==expected_ids[:len(ids)] and len(expected_ids)==100
    assert not (B/"DEV_F/responses.json").exists() and not (B/"DEV_F/score.json").exists() and not (B/"DEV_F/summary.json").exists()
    http_checks=[]
    for row in rows:
        path=ROOT/row["httpEvidence"];meta=d.read(path);raw=path.with_suffix(".body.bin").read_bytes()
        assert meta["responseComplete"] is True and hashlib.sha256(raw).hexdigest()==meta["responseBodySha256"]
        assert len(raw)==meta["responseBytes"] and meta["httpStatus"]==row["httpStatus"]
        assert d.strict_json(raw)==row["body"]
        assert meta["returnedAt"]==row["returnedAt"]
        http_checks.append(dict(id=row["id"],httpStatus=row["httpStatus"],status=row["body"].get("status"),
            budget=row["body"].get("budget"),returnedAt=row["returnedAt"],httpEvidence=row["httpEvidence"]))
    events=B/"send-ledger-events";expected_events=set();details=[]
    known=Decimal(0);token_totals=Counter();late=[];windows=[]
    for index,call in enumerate(ledger["calls"],1):
        token=call["token"];permit=events/(token+"-permit.json");expected_events.add(permit.name)
        initial={k:v for k,v in call.items() if k not in ("usageKnown","costYuan","result","usage")}
        initial.update(usageKnown=False,costYuan=None,result=None)
        assert d.read(permit)==initial
        result=call["result"]
        if result is not None:
            completion=events/(token+"-completion.json");raw_path=events/(token+"-upstream.body.bin")
            expected_events.update((completion.name,raw_path.name))
            assert d.read(completion)==call and result["responseBodyFile"]==raw_path.name
            raw=raw_path.read_bytes();assert len(raw)==result["responseBytes"] and hashlib.sha256(raw).hexdigest()==result["responseBodySha256"]
            if result["responseJson"] is not None:assert d.strict_json(raw)==result["responseJson"]
        else:assert not (events/(token+"-completion.json")).exists() and not (events/(token+"-upstream.body.bin")).exists()
        at=datetime.fromisoformat(call["admittedAt"]);decision=call["offPeak"]
        recorded=d.window(datetime.fromisoformat(decision["checkedAt"]),decision["guardSeconds"])
        assert recorded==decision and decision["allowed"] is True and decision["guardSeconds"]>=120
        next_start=next_peak(at)
        windows.append(dict(index=index,token=token,id=call["questionId"],admittedAtBeijing=at.astimezone(d.BEIJING).isoformat(),
            guardSeconds=decision["guardSeconds"],secondsToNextPeak=(next_start-at.astimezone(d.BEIJING)).total_seconds()))
        if result:
            begin=datetime.fromisoformat(result["startedAtUtc"]);end=datetime.fromisoformat(result["finishedAtUtc"])
            assert d.window(begin,120)["allowed"] and d.window(end,120)["allowed"]
            assert end>=begin
            row=next(r for r in rows if r["id"]==call["questionId"])
            if end>datetime.fromisoformat(row["returnedAt"]):late.append(dict(token=token,id=call["questionId"],usageKnown=call["usageKnown"],costYuan=call["costYuan"],upstreamFinishedAt=result["finishedAtUtc"],httpReturnedAt=row["returnedAt"]))
        if call["usageKnown"]:
            u=call["usage"];assert all(type(u[k]) is int and u[k]>=0 for k in ("prompt_tokens","completion_tokens","total_tokens","prompt_cache_hit_tokens","prompt_cache_miss_tokens"))
            assert u["prompt_tokens"]==u["prompt_cache_hit_tokens"]+u["prompt_cache_miss_tokens"] and u["total_tokens"]==u["prompt_tokens"]+u["completion_tokens"]
            cost=(Decimal(u["prompt_cache_hit_tokens"])*Decimal(".02")+Decimal(u["prompt_cache_miss_tokens"])+Decimal(u["completion_tokens"])*4)/1000000
            assert cost==Decimal(call["costYuan"]);known+=cost
            for k in ("prompt_tokens","completion_tokens","total_tokens","prompt_cache_hit_tokens","prompt_cache_miss_tokens"):token_totals[k]+=u[k]
        else:assert call["costYuan"] is None
        details.append(dict(index=index,token=token,id=call["questionId"],attempt=call["attempt"],
            admittedAt=call["admittedAt"],resultPresent=result is not None,usageKnown=call["usageKnown"],costYuan=call["costYuan"],
            upstreamAttempted=None if result is None else result["upstreamAttempted"],
            httpStatus=None if result is None else result["httpStatus"],responseComplete=None if result is None else result["responseComplete"],
            error=None if result is None else result["error"],requestBodySha256=call["requestBodySha256"]))
    for rejection in ledger["rejections"]:
        path=events/(rejection["eventId"]+"-rejection.json");expected_events.add(path.name);assert d.read(path)==rejection
    assert expected_events=={p.name for p in events.iterdir()}
    assert not ledger["breaches"]
    per_question=[]
    for row in rows:
        calls=[c for c in ledger["calls"] if c["questionId"]==row["id"]]
        budget=row["body"]["budget"]
        assert budget["timeoutMs"]==15000 and budget["maxModelCalls"]==2
        assert len(calls)<=budget["modelCalls"]<=2
        assert [c["attempt"] for c in calls]==list(range(1,len(calls)+1))
        per_question.append(dict(id=row["id"],applicationModelCalls=budget["modelCalls"],applicationUnknownUsageCalls=budget["unknownUsageCalls"],
            permits=len(calls),completedTransportResults=sum(c["result"] is not None for c in calls)))
    quota=d.read(B/"candidate-cost-quota.json")
    assert Decimal(str(quota["knownCostYuan"]))==known and quota["knownCalls"]==67 and len(quota["holds"])==3
    traces=[d.strict_json(line) for p in (B/"DEV_F/runtime-calls").glob("*.jsonl") for line in p.read_text(encoding="utf-8-sig").splitlines()]
    assert len(traces)==67 and all(t["usageKnown"] for t in traces)
    assert sum(Decimal(str(t["costYuan"])) for t in traces)-known<Decimal("0.000000001")
    unknown=[c for c in details if not c["usageKnown"]]
    no_result=[c for c in details if not c["resultPresent"]]
    assert len(ledger["calls"])==69 and len(no_result)==1 and no_result[0]["index"]==69 and no_result[0]["id"]=="T4-027"
    assert len(unknown)==2 and sum(c["resultPresent"] for c in details)==68
    assert len(ledger["rejections"])==2 and "WinError 5" in ledger["rejections"][0]["reason"]
    assert "PRIOR_USAGE_COST_OR_LOCAL_GATE_STOP"==ledger["rejections"][1]["reason"]
    temp_paths=list(B.glob("send-ledger.json.tmp-*"));assert len(temp_paths)==1
    stale=d.read(temp_paths[0]);assert len(stale["calls"])==69 and not stale["rejections"] and stale["calls"]==ledger["calls"]
    replays=[]
    try:d.next_round(state)
    except d.Refused as error:replays.append(str(error))
    else:raise AssertionError("interruption could replay")
    try:d.validate_completed_rounds(state)
    except d.Refused as error:replays.append(str(error))
    else:raise AssertionError("interrupted round validated as completed")
    round_begin=datetime.fromisoformat(interrupted["wholeWindow"]["checkedAt"])
    assert d.window(round_begin,1740)==interrupted["wholeWindow"]
    summary=dict(format="replan-d-interruption-independent-audit-v1",planSha256=PLAN_SHA,
        terminal=state["terminal"],plannedQuestions=100,questionsStarted=68,httpAnswersPreserved=68,unstartedQuestions=32,
        applicationModelCalls=sum(p["applicationModelCalls"] for p in per_question),applicationUnknownUsageCalls=sum(p["applicationUnknownUsageCalls"] for p in per_question),
        commonSendReservations=69,transportAttemptResults=sum(bool((c["result"] or {}).get("upstreamAttempted")) for c in ledger["calls"]),
        providerReceiptNotInferredFromTransport=True,completedProxyResults=68,confirmedHttpResponseHeaders=sum((c["result"] or {}).get("httpStatus") is not None for c in ledger["calls"]),
        completeResponses=sum((c["result"] or {}).get("responseComplete") is True for c in ledger["calls"]),knownUsageCalls=67,
        knownCostYuan=str(known),commonUnknownReservations=2,commonUnknownHeldYuan="0.20",commonConfiguredCommittedYuan=str(known+Decimal(".20")),
        candidateUnknownHolds=3,candidateHeldYuan="0.30",candidateConfiguredCommittedYuan=str(known+Decimal(".30")),
        oneUnknownTransportResponse=True,onePermitBlockedBeforeUpstream=True,lateCompletions=len(late),lateKnownUsageCalls=sum(x["usageKnown"] for x in late),
        lateKnownCostYuan=str(sum((Decimal(x["costYuan"]) for x in late if x["usageKnown"]),Decimal(0))),
        tokensKnownOnly=dict(token_totals),gateRejections=2,breaches=0,allRecordedAdmissionsAndTransportIntervalsOffPeak=True,
        roundStartBeijing=round_begin.astimezone(d.BEIJING).isoformat(),firstTransportStartBeijing=datetime.fromisoformat(ledger["calls"][0]["result"]["startedAtUtc"]).astimezone(d.BEIJING).isoformat(),
        lastCompletedTransportFinishBeijing=max(datetime.fromisoformat(c["result"]["finishedAtUtc"]) for c in ledger["calls"] if c["result"]).astimezone(d.BEIJING).isoformat(),
        finalPermitAdmissionBeijing=datetime.fromisoformat(ledger["calls"][-1]["admittedAt"]).astimezone(d.BEIJING).isoformat(),
        minimumAdmissionSecondsToNextPeak=min(w["secondsToNextPeak"] for w in windows),
        unknownCostNeverFilledWithZero=True,full100AccuracyOrP95Available=False,roundSafetyAcceptedForPromotion=False,
        replayRejections=replays,auditProviderCalls=0,auditProviderTokens=0,auditProviderCostYuan=0)
    d.write(OUT/"accounting-and-interruption.json",summary)
    d.write(OUT/"call-classification.json",dict(calls=details,lateCompletions=late,unknownCalls=unknown,rejections=ledger["rejections"]))
    d.write(OUT/"http-and-budget-reconciliation.json",dict(passed=True,rows=http_checks,perQuestion=per_question))
    d.write(OUT/"recorded-time-windows.json",dict(rule="Frozen officially-rechecked Beijing weekday 09-12 /14-18; no holiday relaxation",roundWindow=interrupted["wholeWindow"],admissions=windows))
    d.write(OUT/"failure-path-evidence.json",dict(token=no_result[0]["token"],question="T4-027",permitIndex=69,
        noCompletionOrRawBody=True,rejections=ledger["rejections"],retainedTemporaryLedgerSha256=d.sha(temp_paths[0]),
        retainedTemporaryLedgerHasPermit69AndNoRejections=True,
        derivation="Frozen SendGate.before writes permit, appends call, then save/replace throws. Exception handler records rejection and re-raises; ModelProxy._forward catches before_send exception and returns before connection creation. This specific error path supports no upstream attempt for permit69; result=null alone would not suffice.",
        retryAttribution="Application log attributes second generation to Text2SqlOrchestrator retry; it does not establish an SDK automatic retry.",
        rootCauseEstablished="Windows os.replace rejected replacing send-ledger.json with WinError 5.",
        specificLockOwnerEstablished=False,rootReadElevenSecondsEarlierDoesNotProveLockOwner=True,
        boundedLocalPersistenceRetryIsPossibleFutureDesign=True,noFrozenCodeOrLedgerChanged=True))
    assert all(d.sha(ROOT/path)==expected for path,expected in manifest.items())
    d.write(OUT/"source-evidence-manifest.json",dict(planSha256=PLAN_SHA,files=manifest,sourceBytesStableDuringAudit=True,auditProviderCalls=0))
    print(json.dumps(summary,ensure_ascii=False,indent=2))


if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
