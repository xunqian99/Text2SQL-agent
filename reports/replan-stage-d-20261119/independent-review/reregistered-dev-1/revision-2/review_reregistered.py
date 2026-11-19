"""Independent complete review. No actual authorization, credentials or vendor calls."""
from __future__ import annotations

import copy
from decimal import Decimal
import hashlib
import importlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[5]
OUT=Path(__file__).resolve().parent
sys.dont_write_bytecode=True
sys.path.insert(0,str(ROOT/"scripts"))
import run_replan_stage_d_reregistered as n

PLAN_SHA="91c67f5a2484fd6fd0e63a1f86c09ebb8e20737f5a7a3d4c4850b9b63c9c17b4"


def write(name,value):
    import json
    path=OUT/name;path.parent.mkdir(parents=True,exist_ok=True)
    with path.open("x",encoding="utf-8") as stream:json.dump(value,stream,ensure_ascii=False,indent=2)


def git_bytes(mapping):
    paths=list(mapping)
    result=subprocess.run(["git","cat-file","--batch"],cwd=ROOT,
        input="".join("HEAD:"+p+"\n" for p in paths).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
    offset=0
    for path in paths:
        end=result.stdout.index(b"\n",offset);fields=result.stdout[offset:end].split();offset=end+1
        assert len(fields)==3 and fields[1]==b"blob",(path,fields)
        size=int(fields[2]);body=result.stdout[offset:offset+size];offset+=size+1
        assert hashlib.sha256(body).hexdigest()==mapping[path],path
    return len(paths)


def identities():
    plan_path=n.BATCH/"plan.json";assert n.sha(plan_path)==PLAN_SHA
    plan=n.d.read(plan_path)
    n.verify(plan)
    direct={p:s for p,s in plan["files"].items() if "/tool-runtime/" not in p}
    direct_count=git_bytes(direct)
    preflight=n.d.read(n.BATCH/"preflight.json")
    assert preflight["passed"] is True and preflight["planSha256"]==PLAN_SHA
    for p,s in preflight["evidence"].items():assert n.sha(ROOT/p)==s,p
    preflight_count=git_bytes(preflight["evidence"])
    legacy=n.legacy_facts()
    state=n.d.read(n.BATCH/"state.json");ledger=n.d.read(n.BATCH/"send-ledger.json")
    assert state["requestsStarted"]==0 and state["rounds"]=={} and state["terminal"] is None and not ledger["calls"]
    assert not (n.BATCH/n.AUTH_NAME).exists()
    assert n.sha(n.BATCH/"candidate-cost-quota.json")==n.sha(n.BATCH/"candidate-quota-initial-snapshot.json")==n.sha(n.OLD_BATCH/"candidate-cost-quota.json")
    assert len(plan["files"])==374 and direct_count==50 and preflight_count==44
    f_after=n.d.read(n.BATCH/"offline-preflight/F/inherited-quota-after-load.json")
    assert f_after["passed"] is True and f_after["applicationReachedReady"] and f_after["applicationBodyCompleted"] and f_after["legacyUnknownHoldsPreserved"]==3
    write("identity-verification.json",dict(planSha256=PLAN_SHA,boundFiles=len(plan["files"]),
        gitOriginalBytesMatched=direct_count,runtimeFiles=324,preflightFilesAndGitBytes=preflight_count,
        databaseIdentityRechecked=True,legacy=legacy,old401FilesVerified=True,
        newPaidStateSha256=n.sha(n.BATCH/"state.json"),newLedgerSha256=n.sha(n.BATCH/"send-ledger.json"),
        realHumanAuthorizationAbsent=True,exactCandidateSeedPreserved=True,realC1PreflightLoadedSeed=True,providerCalls=0))


def suite():
    temp=OUT/"unit-temp";temp.mkdir();tempfile.tempdir=str(temp)
    names=("test_replan_stage_d","test_replan_stage_d_proxy","test_replan_durable_write", "test_replan_stage_d_reregistered","test_replan_stable_proxy")
    modules=[importlib.import_module(name) for name in names]
    for module in modules:
        if module.__name__=="test_replan_stage_d_reregistered":module.EVIDENCE=(OUT/"directed-test-fixtures").resolve()
        if module.__name__=="test_replan_stable_proxy":module.EVIDENCE=(OUT/"stable-test-fixtures").resolve()
    tests=unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromModule(module) for module in modules)
    stream=io.StringIO();result=unittest.TextTestRunner(stream=stream,verbosity=2).run(tests)
    with (OUT/"combined-suite.log").open("xb") as f:f.write(stream.getvalue().encode())
    write("combined-suite.json",dict(testsRun=result.testsRun,failures=len(result.failures),errors=len(result.errors),
        skipped=len(result.skipped),passed=result.wasSuccessful(),bothEvidenceModulesRedirectedToOwnAbsolutePaths=True,
        scope="Single independent run of all five final test modules; synthetic authorization is under review fixtures only",providerCalls=0))


def rollback():
    path=OUT/"quota-rollback-counterexample";path.mkdir()
    original=n.d.read(n.OLD_BATCH/"candidate-cost-quota.json")
    file=path/"candidate-cost-quota.json"
    state=path/"state.json";ledger=path/"send-ledger.json"
    # Synthetic post-start state, never the real candidate runtime file.
    write("quota-rollback-counterexample/state.json",dict(requestsStarted=100,rounds={"DEV_F":dict(status="COMPLETED")}))
    write("quota-rollback-counterexample/send-ledger.json",dict(calls=[dict(synthetic=True)]))
    after=copy.deepcopy(original)
    after["knownCalls"]+=100
    after["knownCostYuan"]=float(Decimal(str(original["knownCostYuan"]))+Decimal(".50"))
    write("quota-rollback-counterexample/after-new-known-cost.json",after)
    file.write_bytes((path/"after-new-known-cost.json").read_bytes())
    with patch.object(n.d,"BATCH",path):
        n.check_candidate_quota()
        before_rollback_accepted=True
        before_commit=Decimal(str(after["knownCostYuan"]))+Decimal(".10")*len(after["holds"])
        assert before_commit==Decimal(".96312188")
        file.write_bytes((n.OLD_BATCH/"candidate-cost-quota.json").read_bytes())
        restored=n.check_candidate_quota()
        after_rollback_accepted=True
        restored_commit=Decimal(str(restored["knownCostYuan"]))+Decimal(".10")*len(restored["holds"])
    # A new UNKNOWN, unlike the original three, is also removable unnoticed.
    with_hold=copy.deepcopy(after)
    with_hold["knownCostYuan"]=original["knownCostYuan"]
    hold=copy.deepcopy(next(iter(original["holds"].values())))
    hold.update(requestId="synthetic-new-request",callNumber=1)
    with_hold["holds"]["synthetic-new-unknown-hold"]=hold
    write("quota-rollback-counterexample/with-new-unknown-hold.json",with_hold)
    file.write_bytes((path/"with-new-unknown-hold.json").read_bytes())
    with patch.object(n.d,"BATCH",path):
        n.check_candidate_quota()
        file.write_bytes((n.OLD_BATCH/"candidate-cost-quota.json").read_bytes())
        without=n.check_candidate_quota()
        removed_hold_accepted="synthetic-new-unknown-hold" not in without["holds"]
    common_commit=Decimal(n.LEGACY["commonCommittedYuan"])+Decimal(".50")
    assert before_commit+Decimal(".10")>n.d.LIMIT and restored_commit+Decimal(".10")<=n.d.LIMIT
    assert common_commit+Decimal(".10")<=n.d.LIMIT
    write("quota-rollback-counterexample.json",dict(planSha256=PLAN_SHA,
        syntheticOnly=True,actualPaidOrCandidateFilesChanged=False,
        beforeRollbackCheckAccepted=before_rollback_accepted,beforeRollbackCandidateCommittedYuan=str(before_commit),
        beforeRollbackNextReserveWouldExceedCandidateLimit=True,
        restoredSeedCheckAccepted=after_rollback_accepted,restoredCandidateCommittedYuan=str(restored_commit),
        afterRollbackNextReserveWouldPass=True,newUnknownHoldCanBeRemovedWithoutRejection=removed_hold_accepted,
        sharedCommonCommittedYuan=str(common_commit),sharedNextReserveStillWithinLimit=True,
        whyCommonLayerDoesNotRepairOtherLayer="Candidate layer would stop at 0.96312188 but common layer at 0.86312188 still admits 0.10; seed rollback reopens the stricter candidate layer.",
        cause="check_candidate_quota compares only the immutable old baseline, not the last verified new candidate snapshot, and new runtime quota is excluded from completed-round evidence.",providerCalls=0))


def missing_authorization():
    assert not (n.BATCH/n.AUTH_NAME).exists()
    target=OUT/"missing-human-auth-fixture";target.mkdir()
    write("missing-human-auth-fixture/state.json",dict(requestsStarted=0,rounds={},terminal=None))
    write("missing-human-auth-fixture/send-ledger.json",dict(calls=[],rejections=[],breaches=[]))
    before={p.name:n.sha(p) for p in target.iterdir()}
    with patch.object(n.d,"BATCH",target),patch.object(n,"_old_real_key",side_effect=AssertionError("real key forbidden")) as key,\
         patch.object(n.d,"run_next",side_effect=AssertionError("inherited paid route forbidden")) as inherited:
        try:n.run_next()
        except n.Refused as error:first_reason=str(error)
        else:raise AssertionError("missing human authorization admitted")
        try:n.guarded_real_key()
        except n.Refused as error:second_reason=str(error)
        else:raise AssertionError("guarded key admitted without human authorization")
    after={p.name:n.sha(p) for p in target.iterdir()}
    assert before==after and key.call_count==inherited.call_count==0
    write("missing-human-auth-boundary.json",dict(planSha256=PLAN_SHA,realAuthorizationPathRemainsAbsent=True,
        syntheticEntryAndKeyBarrierRejected=True,realKeyReaderCalls=0,inheritedPaidRouteCalls=0,
        stateLedgerAndDirectoryByteDelta=0,reasons=[first_reason,second_reason],providerCalls=0))


def ready_only():
    """Actual frozen C1 JAR, isolated quota, health only, no model question."""
    path=OUT/"ready-only-first-start";path.mkdir()
    temp=path/"process-temp";temp.mkdir()
    seed=n.OLD_BATCH/"candidate-cost-quota.json"
    plan=n.d.read(n.BATCH/"plan.json")
    # Build a nonsecret allowlist directly: never enumerate or resolve key env/registry.
    process_env={"SYSTEMROOT":r"C:\Windows","WINDIR":r"C:\Windows",
        "COMSPEC":r"C:\Windows\System32\cmd.exe","PATHEXT":".COM;.EXE;.BAT;.CMD",
        "PATH":str(n.d.JAVA.parent)+r";C:\Windows\System32",
        "TEMP":str(temp),"TMP":str(temp),"USERPROFILE":r"C:\Users\xunqian",
        "APPDATA":r"C:\Users\xunqian\AppData\Roaming",
        "LOCALAPPDATA":r"C:\Users\xunqian\AppData\Local"}
    attempts=[]
    for i in range(1,4):
        target=path/("start-"+str(i));target.mkdir()
        (target/"DEV_F").mkdir()
        (target/"candidate-cost-quota.json").write_bytes(seed.read_bytes())
        (target/"candidate-quota-initial-snapshot.json").write_bytes(seed.read_bytes())
        write("ready-only-first-start/start-"+str(i)+"/state.json",dict(requestsStarted=0,
            rounds={"DEV_F":dict(status="RUNNING")},terminal=None))
        write("ready-only-first-start/start-"+str(i)+"/send-ledger.json",dict(calls=[],rejections=[],breaches=[]))
        with patch.object(n.d,"BATCH",target),patch.object(n.d,"clean_env",return_value=process_env),\
             patch.object(n,"_old_real_key",side_effect=AssertionError("real key forbidden")) as keys,\
             patch.object(n,"guarded_real_key",side_effect=AssertionError("guarded key forbidden")):
            n.verify(plan,database=False)
            before=(target/"candidate-cost-quota.json").read_bytes()
            with n.application("F",SimpleNamespace(base_url=n.STABLE_PROXY_URL),target/"DEV_F"):
                after=(target/"candidate-cost-quota.json").read_bytes()
                semantic_same=n.d.strict_json(before)==n.d.strict_json(after)
                try:n.verify(plan,database=False)
                except n.Refused as error:refusal=str(error)
                else:refusal=None
                assert keys.call_count==0
            assert n.d.read(target/"state.json")["requestsStarted"]==0
            assert n.d.read(target/"send-ledger.json")["calls"]==[]
            (target/"before-load.bin").write_bytes(before)
            (target/"after-load.bin").write_bytes(after)
            attempt=dict(start=i,applicationReachedReady=True,beforeSha256=hashlib.sha256(before).hexdigest(),
                afterSha256=hashlib.sha256(after).hexdigest(),bytesChanged=before!=after,
                semanticQuotaUnchanged=semantic_same,verifyRefusal=refusal,
                asksSubmitted=0,modelCalls=0,providerCalls=0,realKeyReaderCalls=keys.call_count,
                beforeHoldOrder=list(n.d.strict_json(before)["holds"]),afterHoldOrder=list(n.d.strict_json(after)["holds"]))
            attempts.append(attempt)
            if before!=after:
                assert semantic_same and refusal=="Before first paid start, inherited candidate runtime quota must equal its exact seed"
                break
    write("ready-only-first-start.json",dict(planSha256=PLAN_SHA,syntheticOwnBatchOnly=True,
        actualCandidateRuntimeTouched=False,realAuthorizationAbsent=not (n.BATCH/n.AUTH_NAME).exists(),
        sameFrozenC1Jar=True,paidRunNextNeverCalled=True,questionsSubmitted=0,providerCalls=0,
        initialVerifyPassedAllAttempts=True,attempts=attempts,
        firstQuestionVerifyFalseRejectConfirmed=any(a["semanticQuotaUnchanged"] and a["verifyRefusal"] for a in attempts)))


if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    {"identities":identities,"suite":suite,"rollback":rollback,"auth":missing_authorization,"ready":ready_only}[sys.argv[1]]()
