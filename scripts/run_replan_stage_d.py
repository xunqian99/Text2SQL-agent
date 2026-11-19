"""One new D batch. Only run-next may resolve a real key; no old batch is resumed.

Both frozen JARs answer through HTTP. Actual rows are scored by the frozen V4
Java scorer. A shared local proxy accounts every upstream attempt before send.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT=Path(__file__).resolve().parent.parent
BASE=ROOT/"reports/replan-stage-d-20261119"
BATCH=BASE/"batch-1"
PROTOCOL=ROOT/"docs/REPLAN_STAGE_D_PROTOCOL_20261119.md"
JAVA=Path(r"C:\Users\xunqian\.jdks\corretto-21.0.11\bin\java.exe")
JAVAC=JAVA.with_name("javac.exe")
BEIJING=timezone(timedelta(hours=8))
ORDER=("DEV_F","TARGETS","B1","F1","F2","B2","B3","F3","U_B","U_F")
FORMAL=("B1","F1","F2","B2","B3","F3")
ROUND_GUARD=1740
SEND_GUARD=120
LIMIT=Decimal("1.00")
RESERVE=Decimal("0.10")
MAX_REQUESTS=902
MAX_CALLS=1804
MAX_PAYLOAD=65536
INTERNAL={"schema_chunks","semantic_cache","llm_call_log","conversation_session","conversation_turn"}
SYSTEMS={
 "B":dict(sourceCommit="3c0bda099c08183ff3e3b4607bd0a071158fc630",
          jar=".private/recovery/p9-3c0bda0/text2sql-agent-p9.jar",
          sha256="a89dbd431263f0c9356510d71a5dd90fdcd6e0b3ab806ce9e3fdd16cf1121a8b"),
 "F":dict(sourceCommit="e114648a963bf109a38ad232628f1df70915a6d4",
          jar=".private/recovery/replan-c1-20261119/text2sql-agent-C1-submission-1.jar",
          sha256="51874452c5c0acad14a733fc6d0fbec06c81273aaabf7b16eabc3b4b4653c2c2")}


class Refused(RuntimeError): pass


def now(): return datetime.now(timezone.utc)
def stamp(): return now().isoformat()
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def digest(value): return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(",",":")).encode()).hexdigest()
def strict_json(raw):
    def pairs(items):
        result={}
        for key,value in items:
            if key in result: raise ValueError("Duplicate JSON key: "+key)
            result[key]=value
        return result
    def constant(value): raise ValueError("Non-finite JSON constant: "+value)
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=constant)


def read(path): return strict_json(Path(path).read_text(encoding="utf-8-sig"))


def write_bytes(path,data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    with path.open("xb") as f: f.write(data);f.flush();os.fsync(f.fileno())


def write(path,value,*,replace=False):
    path=Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists() and not replace: raise Refused("Never overwrite evidence: "+path.name)
    data=(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+"\n").encode()
    if replace:
        temp=path.with_name(path.name+".tmp-"+uuid.uuid4().hex)
        with temp.open("xb") as f: f.write(data); f.flush(); os.fsync(f.fileno())
        os.replace(temp,path)
    else:
        with path.open("xb") as f: f.write(data); f.flush(); os.fsync(f.fileno())


def clean_env():
    allowed={"PATH","SYSTEMROOT","WINDIR","COMSPEC","PATHEXT","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA","LANG"}
    return {k:v for k,v in os.environ.items() if k.upper() in allowed}


def window(at,guard):
    if at.tzinfo is None or guard<=0 or guard>86400: raise Refused("Invalid clock/guard")
    local=at.astimezone(BEIJING); end=at+timedelta(seconds=guard)
    for offset in range(2):
        day=local.date()+timedelta(days=offset)
        if day.weekday()>=5: continue
        for start_h,end_h in ((9,12),(14,18)):
            left=datetime.combine(day,datetime.min.time(),BEIJING)+timedelta(hours=start_h)
            right=left+timedelta(hours=end_h-start_h)
            if at<right and end>=left:
                return dict(allowed=False,reason="PEAK_HOURS" if at>=left else "PEAK_BOUNDARY_RESERVE",
                            checkedAt=at.isoformat(),nextAllowedAt=right.isoformat(),guardSeconds=guard)
    return dict(allowed=True,reason="OFF_PEAK",checkedAt=at.isoformat(),guardSeconds=guard)


def require_window(guard):
    result=window(now(),guard)
    if not result["allowed"]: raise Refused(result["reason"]+"; next="+result["nextAllowedAt"])
    return result


@contextmanager
def locked():
    lock=BATCH/"controller.lock"
    try: fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
    except FileExistsError: raise Refused("Controller already running or crashed; preserve lock, never silently resume")
    try:
        os.write(fd,str(os.getpid()).encode()); os.close(fd); yield
    finally: lock.unlink(missing_ok=True)


def database_snapshot():
    command=["docker","exec","-i","text2sql-postgres","psql","-X","-qAt","-v","ON_ERROR_STOP=1","-U","text2sql_ro","-d","olist"]
    def sql(value,timeout):
        return subprocess.run(command,input=value.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                              cwd=ROOT,env=clean_env(),timeout=timeout,check=True).stdout.decode()
    tables=[t for t in sql("BEGIN READ ONLY; SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename; COMMIT;",30).splitlines() if t not in INTERNAL]
    if len(tables)!=37 or any(not re.fullmatch(r"[a-z_][a-z0-9_]*",t) for t in tables): raise Refused("Business catalog drift")
    names=",".join("'"+t+"'" for t in tables)
    commands=["BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;","SET LOCAL statement_timeout='8s';",
      "SELECT jsonb_build_object('schema',jsonb_agg(x ORDER BY table_name,ordinal_position)) FROM (SELECT table_name,column_name,ordinal_position,data_type,udt_name,is_nullable,column_default,character_maximum_length,numeric_precision,numeric_scale,obj_description(format('%I.%I',table_schema,table_name)::regclass,'pg_class') AS table_comment,col_description(format('%I.%I',table_schema,table_name)::regclass,ordinal_position) AS column_comment FROM information_schema.columns WHERE table_schema='public' AND table_name IN ("+names+")) x;",
      "SELECT jsonb_build_object('constraints',jsonb_agg(x ORDER BY table_name,definition)) FROM (SELECT c.relname AS table_name,pg_get_constraintdef(k.oid) AS definition FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ("+names+")) x;"]
    for table in tables:
        commands.append("SELECT jsonb_build_object('table','"+table+"','rows',COUNT(*),'sha256',encode(sha256(convert_to(COALESCE(string_agg(h,'' ORDER BY h COLLATE \"C\"),''),'UTF8')),'hex')) FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') AS h FROM public.\""+table+"\" t) rows;")
    commands.append("COMMIT;")
    return dict(algorithm="sha256-canonical-jsonb-row-multiset-C-order-v1",tables=tables,
                sha256=digest([json.loads(line) for line in sql("\n".join(commands),180).splitlines() if line.strip()]))


def dataset_files():
    paths=[]
    for folder in ("olist-v4-development-20261119","olist-v4-formal-20261119"):
        paths.extend(p for p in (ROOT/"data/benchmarks"/folder).iterdir() if p.is_file())
    return paths


def tool_files():
    return [Path(__file__),ROOT/"scripts/replan_stage_d_proxy.py",ROOT/"scripts/replan/StageDScoringBridge.java",PROTOCOL,
            ROOT/"reports/replan-stage-a-20261119/ReferenceContractScorer.java",
            ROOT/"reports/replan-stage-a-20261119/output-column-schema-attempt2.json",
            ROOT/"reports/replan-stage-a-20261119/original-target-preregistration.json",
            ROOT/"src/main/java/com/text2sql/agent/execution/ResultNormalizer.java",
            ROOT/"reports/replan-stage-b-20261119/b0-identity.json",
            ROOT/"reports/replan-stage-c-20261119/c1-submission-1/candidate-identity.json",
            ROOT/"reports/replan-stage-c-20261119/checkpoint.json",
            ROOT/"reports/replan-stage-c-20261119/official-pricing-check.json",
            ROOT/"reports/replan-stage-c-20261119/independent-review/c1-submission-1/decision.json"]


def compile_bridge():
    tool=BATCH/"tool-runtime"; tool.mkdir()
    with zipfile.ZipFile(ROOT/SYSTEMS["F"]["jar"]) as z:
        for name in z.namelist():
            if not (name.startswith("BOOT-INF/classes/") or name.startswith("BOOT-INF/lib/")) or name.endswith("/"): continue
            parts=Path(name).parts
            if ".." in parts: raise Refused("Unsafe JAR entry")
            target=tool/Path(*parts[1:]); target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(z.read(name))
    classes=tool/"helper-classes"; classes.mkdir()
    cp=str(tool/"classes")+os.pathsep+str(tool/"lib/*")
    subprocess.run([str(JAVAC),"-encoding","UTF-8","-cp",cp,"-d",str(classes),
        str(ROOT/"reports/replan-stage-a-20261119/ReferenceContractScorer.java"),
        str(ROOT/"scripts/replan/StageDScoringBridge.java")],cwd=ROOT,env=clean_env(),check=True,timeout=60)
    return str(classes)+os.pathsep+cp


def bridge(cp,*args):
    subprocess.run([str(JAVA),"-Duser.timezone=Asia/Shanghai","-cp",cp,"StageDScoringBridge",*map(str,args)],
                   cwd=ROOT,env=clean_env(),check=True,timeout=180)


def prepare():
    if BATCH.exists(): raise Refused("One batch only; existing directory is never reset")
    for side in SYSTEMS.values():
        if sha(ROOT/side["jar"])!=side["sha256"]: raise Refused("Frozen JAR drift")
    decision=read(ROOT/"reports/replan-stage-c-20261119/independent-review/c1-submission-1/decision.json")
    if decision.get("decision")!="ACCEPT_LOCAL_C1": raise Refused("C1 not locally accepted")
    BATCH.mkdir(parents=True)
    cp=compile_bridge()
    for name in ("dev","formal","targets"): bridge(cp,"prepare",name,BATCH/(name+"-reference.json"))
    database=database_snapshot()
    b0=read(ROOT/"reports/replan-stage-b-20261119/b0-identity.json")
    if database!=b0["database"]: raise Refused("Data differs from accepted B0")
    files=tool_files()+dataset_files()+[BATCH/(n+"-reference.json") for n in ("dev","formal","targets")]
    files.extend(p for p in (BATCH/"tool-runtime").rglob("*") if p.is_file())
    plan=dict(format="replan-d-selected-c1-v1",createdAt=stamp(),systems=SYSTEMS,order=list(ORDER),
        selectedCandidate="C1 only; C2 not admitted",limits=dict(requests=MAX_REQUESTS,calls=MAX_CALLS,globalPlanRequests=1002,globalPlanCalls=2004,
          callsPerRequest=2,requestMs=15000,sdkAttempts=1,roundGuardSeconds=ROUND_GUARD,sendGuardSeconds=SEND_GUARD,
          totalYuan=str(LIMIT),reservationYuan=str(RESERVE),maxPayloadBytes=MAX_PAYLOAD,maxCompletionTokens=8192,inputFramingEstimate=1024),
        commonScorer="frozen ReferenceContractScorer from accepted A; actual HTTP rows, never SQL replay",
        classpath=cp,database=database,files={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(files)},
        unseen="LOCKED; independent custodian may prepare only after all formal gates pass",
        providerCallsBeforePaid=0)
    write(BATCH/"plan.json",plan)
    write(BATCH/"state.json",dict(format=plan["format"],planSha256=sha(BATCH/"plan.json"),
        requestsStarted=0,rounds={},terminal=None))
    write(BATCH/"send-ledger.json",dict(planSha256=sha(BATCH/"plan.json"),calls=[],rejections=[],breaches=[]))
    print("D prepared; paid admission remains false",flush=True)


def verify(plan,*,database=True):
    for name,expected in plan["files"].items():
        if not (ROOT/name).is_file() or sha(ROOT/name)!=expected: raise Refused("Evidence/code drift: "+name)
    for side in plan["systems"].values():
        if sha(ROOT/side["jar"])!=side["sha256"]: raise Refused("Runtime JAR drift")
    if database and database_snapshot()!=plan["database"]: raise Refused("Database identity drift")


def percentile(values): return sorted(values)[math.ceil(.95*len(values))-1]


def dev_gate(group):
    layers=group["byLayer"]
    deep=layers.get("multi_join_deep",{}); business=layers.get("business_semantics",{})
    checks=dict(denominator=group["total"]==100,correct=group["correct"]>=71,
        deep=deep.get("total")==10 and deep.get("correct",0)>=4,
        business=business.get("total")==13 and business.get("correct",0)>=6,
        timeouts=group["timeoutCount"]<=9,dangerous=group.get("newDangerousAllowances",0)==0)
    return dict(passed=all(checks.values()),checks=checks)


def formal_gate(rounds):
    b=[rounds[n]["summary"] for n in ("B1","B2","B3")]
    f=[rounds[n]["summary"] for n in ("F1","F2","F3")]
    b_correct=sum(g["correct"] for g in b); f_correct=sum(g["correct"] for g in f)
    b_p95=sum(g["wallP95Ms"] for g in b)/3; f_p95=sum(g["wallP95Ms"] for g in f)/3
    b_timeout=sum(g["timeoutCount"] for g in b); f_timeout=sum(g["timeoutCount"] for g in f)
    checks=dict(denominators=all(g["total"]==100 for g in b+f),accuracy=f_correct>=267,
      relativeAccuracy=f_correct>=b_correct-6,p95Relative=f_p95<=b_p95,p95Absolute=f_p95<=13642,
      timeoutRelative=f_timeout<=b_timeout,timeoutAbsolute=f_timeout<=14,
      dangerous=all(g.get("newDangerousAllowances",0)==0 for g in b+f),
      completeAccounting=all(g["accounting"]["unknownUsageCalls"]==0 for g in b+f))
    return dict(passed=all(checks.values()),checks=checks,baselineCorrect=b_correct,candidateCorrect=f_correct,
      baselineP95MeanMs=b_p95,candidateP95MeanMs=f_p95,baselineTimeouts=b_timeout,candidateTimeouts=f_timeout)


def next_round(state):
    if state.get("terminal"): raise Refused("Batch stopped: "+state["terminal"])
    rounds=state["rounds"]
    if any(r["status"]!="COMPLETED" for r in rounds.values()): raise Refused("Interrupted/running round is never replayed")
    if "DEV_F" not in rounds: return "DEV_F"
    if not rounds["DEV_F"]["gate"]["passed"]: raise Refused("Development failed; selected route stops")
    if "TARGETS" not in rounds: return "TARGETS"
    if not rounds["TARGETS"]["gate"]["passed"]: raise Refused("Original targets failed; no formal")
    for name in FORMAL:
        if name not in rounds: return name
    if not formal_gate(rounds)["passed"]: raise Refused("Formal gates failed; no unseen")
    raise Refused("FORMAL_PASSED_REQUIRES_INDEPENDENT_UNSEEN_CUSTODIAN; root cannot prepare/read unseen")


class SendGate:
    def __init__(self,path,plan_sha,round_name,*,clock=now,monotonic=time.monotonic):
        self.path=Path(path); self.data=read(path); self.plan_sha=plan_sha; self.round=round_name
        if self.data["planSha256"]!=plan_sha: raise Refused("Send ledger identity mismatch")
        self.clock=clock; self.monotonic=monotonic; self.lock=threading.RLock(); self.current=None
        self.events=self.path.with_name(self.path.stem+"-events"); self.events.mkdir(exist_ok=True)
        self.verify_events()

    def verify_events(self):
        expected=set()
        for call in self.data["calls"]:
            permit=self.events/(call["token"]+"-permit.json"); expected.add(permit.name)
            admitted={k:v for k,v in call.items() if k not in ("usageKnown","costYuan","result","usage")}
            admitted.update(usageKnown=False,costYuan=None,result=None)
            if not permit.is_file() or read(permit)!=admitted: raise Refused("Send permit admission drift")
            final=self.events/(call["token"]+"-completion.json")
            if call.get("result") is not None: expected.add(final.name)
            source=final if call.get("result") is not None else permit
            if not source.is_file() or read(source)!=call: raise Refused("Send ledger/event identity mismatch")
        for rejection in self.data["rejections"]:
            name=rejection["eventId"]+"-rejection.json"; expected.add(name)
            if not (self.events/name).is_file() or read(self.events/name)!=rejection: raise Refused("Rejection event drift")
        if {p.name for p in self.events.iterdir()}!=expected: raise Refused("Orphan/missing send event; no restart replay")

    def save(self): write(self.path,self.data,replace=True)
    def begin(self,item,deadline):
        with self.lock:
            if self.current is not None: raise Refused("Only one active HTTP question")
            self.current=dict(id=item["id"],question=item["question"].strip(),deadline=deadline)
    def end(self):
        with self.lock: self.current=None
    def committed(self):
        return sum(Decimal(c["costYuan"]) if c.get("usageKnown") else RESERVE for c in self.data["calls"])
    def before(self,payload,raw):
        with self.lock:
            try:
                if self.data["breaches"] or self.data["rejections"]: raise Refused("PRIOR_USAGE_COST_OR_LOCAL_GATE_STOP")
                current=self.current
                if current is None or self.monotonic()>=current["deadline"]: raise Refused("NO_ACTIVE_REQUEST_OR_DEADLINE")
                # Any DNS/connect delay is bounded by the remaining parent deadline.
                # Reserve that delay too, so the eventual wire attempt still has 120s.
                decision=window(self.clock(),SEND_GUARD+math.ceil(current["deadline"]-self.monotonic()))
                if not decision["allowed"]: raise Refused(decision["reason"])
                if set(payload)-{"model","messages","temperature","max_tokens","max_completion_tokens","stream","n"}: raise Refused("UNREGISTERED_MODEL_OPTION")
                if payload.get("model")!="deepseek-flash" or payload.get("temperature")!=0: raise Refused("MODEL_OR_TEMPERATURE_DRIFT")
                if payload.get("max_tokens",payload.get("max_completion_tokens"))!=8192: raise Refused("OUTPUT_LIMIT_DRIFT")
                if "max_tokens" in payload and "max_completion_tokens" in payload and payload["max_tokens"]!=payload["max_completion_tokens"]: raise Refused("CONFLICTING_OUTPUT_LIMITS")
                if payload.get("n",1)!=1: raise Refused("ONE_COMPLETION_ONLY")
                if any(k in payload for k in ("reasoning_effort","thinking","tools","tool_choice","web_search","response_format")): raise Refused("MODE_OR_TOOL_DRIFT")
                if payload.get("stream",False) is not False or len(raw)>MAX_PAYLOAD: raise Refused("PAYLOAD_BOUND")
                messages=payload.get("messages")
                if not isinstance(messages,list) or [m.get("role") for m in messages]!=["system","user"]: raise Refused("MESSAGE_SHAPE")
                if any(not isinstance(m.get("content"),str) for m in messages): raise Refused("TEXT_MESSAGES_ONLY")
                user=messages[1]["content"]; marker="=== 用户问题 ===\n"
                tail=user.partition(marker)[2]; expected=current["question"]+"\n"
                if not tail.startswith(expected): raise Refused("QUESTION_BINDING")
                suffix=tail[len(expected):]
                calls=[c for c in self.data["calls"] if c["round"]==self.round and c["questionId"]==current["id"]]
                if len(calls)>=2: raise Refused("PER_QUESTION_CALL_LIMIT")
                if (not calls and suffix) or (calls and not suffix.startswith("\n\n=== 上一次 SQL 与评估反馈 ===\n")): raise Refused("FIRST_OR_CORRECTION_BINDING")
                if len(self.data["calls"])>=MAX_CALLS: raise Refused("GLOBAL_CALL_LIMIT")
                estimate=Decimal(len(raw)+1024)/Decimal(1000000)+Decimal(8192)*Decimal(4)/Decimal(1000000)
                if estimate>RESERVE or self.committed()+RESERVE>LIMIT: raise Refused("GLOBAL_COST_RESERVATION")
                token=uuid.uuid4().hex
                permit=dict(token=token,round=self.round,questionId=current["id"],attempt=len(calls)+1,
                  upstreamDeadlineMonotonic=current["deadline"],admittedAt=self.clock().isoformat(),offPeak=decision,
                  requestBodySha256=hashlib.sha256(raw).hexdigest(),requestBytes=len(raw),inputTokenEstimate=len(raw)+1024,
                  promptChars=sum(len(m["content"].encode("utf-16-le"))//2 for m in messages),reservedYuan=str(RESERVE))
                call={**permit,"usageKnown":False,"costYuan":None,"result":None}
                write(self.events/(token+"-permit.json"),call)
                self.data["calls"].append(call)
                self.save()  # Durable before upstream; failed persistence means no send.
                return permit
            except Exception as error:
                rejection=dict(eventId=uuid.uuid4().hex,round=self.round,questionId=(self.current or {}).get("id"),at=self.clock().isoformat(),reason=str(error))
                write(self.events/(rejection["eventId"]+"-rejection.json"),rejection)
                self.data["rejections"].append(rejection)
                self.save(); raise

    def after(self,permit,result):
        with self.lock:
            matches=[c for c in self.data["calls"] if c["token"]==permit["token"]]
            if len(matches)!=1 or matches[0]["result"] is not None: raise Refused("Duplicate/missing completion identity")
            call=matches[0]; call["result"]=result
            usage=(result.get("responseJson") or {}).get("usage")
            fields=("prompt_tokens","completion_tokens","total_tokens","prompt_cache_hit_tokens","prompt_cache_miss_tokens")
            valid=result.get("responseComplete") is True and isinstance(usage,dict) and all(type(usage.get(k)) is int and usage[k]>=0 for k in fields)
            if valid:
                p,c,t,h,m=(usage[k] for k in fields)
                valid=p>0 and t>0 and p+c==t and h+m==p
            if valid:
                cost=(Decimal(h)*Decimal(".02")+Decimal(m)+Decimal(c)*Decimal(4))/Decimal(1000000)
                call.update(usageKnown=True,costYuan=str(cost),usage=usage)
                if p>call["inputTokenEstimate"] or c>8192 or cost>RESERVE:
                    self.data["breaches"].append(dict(token=call["token"],reason="CONFIGURED_ESTIMATE_OR_RESERVATION_EXCEEDED"))
                if self.committed()>LIMIT: self.data["breaches"].append(dict(token=call["token"],reason="GLOBAL_COST_EXCEEDED"))
                if any(usage.get("server_tool_use",{}).values()):
                    self.data["breaches"].append(dict(token=call["token"],reason="UNREGISTERED_SERVER_TOOL_USE"))
            write(self.events/(call["token"]+"-completion.json"),call)
            self.save()  # Unknown/incomplete usage keeps the full reservation.

    def reconcile(self,rows):
        ids=[r["id"] for r in rows]
        if len(ids)!=len(set(ids)): raise Refused("Duplicate HTTP question identity")
        calls=[c for c in self.data["calls"] if c["round"]==self.round]
        if any(c["questionId"] not in ids for c in calls): raise Refused("Send has no returned HTTP question")
        questions=[]
        for row in rows:
            budget=row["body"].get("budget") or {}
            count=budget.get("modelCalls")
            local=[c for c in calls if c["questionId"]==row["id"]]
            if (budget.get("timeoutMs")!=15000 or budget.get("maxModelCalls")!=2
                or type(count) is not int or not 0<=count<=2 or len(local)>count
                or [c["attempt"] for c in local]!=list(range(1,len(local)+1))):
                raise Refused("HTTP budget/send reconciliation failed: "+row["id"])
            returned=datetime.fromisoformat(row["returnedAt"])
            late=[c for c in local if (c.get("result") or {}).get("finishedAtUtc")
                  and datetime.fromisoformat(c["result"]["finishedAtUtc"])>returned]
            known=[c for c in local if c["usageKnown"]]
            questions.append(dict(id=row["id"],runtimeModelCalls=count,sendReservations=len(local),
                firstAnswerCalls=sum(c["attempt"]==1 for c in local),correctionCalls=sum(c["attempt"]==2 for c in local),
                runtimeUnknownUsageCalls=budget.get("unknownUsageCalls"),proxyKnownUsageCalls=len(known),
                proxyUnknownUsageCalls=len(local)-len(known),lateCompletions=len(late),
                lateKnownUsageCalls=sum(c["usageKnown"] for c in late),
                lateKnownCostYuan=str(sum((Decimal(c["costYuan"]) for c in late if c["usageKnown"]),Decimal(0))),
                tokens=[c["token"] for c in local],httpEvidence=row.get("httpEvidence")))
        return dict(passed=True,questions=questions,scope="send reservations <= runtime modelCalls <= 2; no assumption that reserved means received by provider")

    def summary(self,round_name=None,reconciliation=None):
        calls=[c for c in self.data["calls"] if round_name is None or c["round"]==round_name]
        known=[c for c in calls if c["usageKnown"]]
        questions=(reconciliation or {}).get("questions",[])
        return dict(sendReservations=len(calls),transportAttempts=sum(bool((c.get("result") or {}).get("upstreamAttempted")) for c in calls),
           firstAnswerCalls=sum(c["attempt"]==1 for c in calls),correctionCalls=sum(c["attempt"]==2 for c in calls),
           lateCompletions=sum(q["lateCompletions"] for q in questions),
           lateKnownUsageCalls=sum(q["lateKnownUsageCalls"] for q in questions),
           lateKnownCostYuan=str(sum((Decimal(q["lateKnownCostYuan"]) for q in questions),Decimal(0))),
           confirmedResponses=sum((c.get("result") or {}).get("httpStatus") is not None for c in calls),
           knownUsageCalls=len(known),unknownUsageCalls=len(calls)-len(known),
           knownCostYuan=str(sum(Decimal(c["costYuan"]) for c in known)),unknownHeldYuan=str(RESERVE*(len(calls)-len(known))),
           inputTokens=sum(c["usage"]["prompt_tokens"] for c in known),outputTokens=sum(c["usage"]["completion_tokens"] for c in known),
           cacheHitTokens=sum(c["usage"]["prompt_cache_hit_tokens"] for c in known),cacheMissTokens=sum(c["usage"]["prompt_cache_miss_tokens"] for c in known),
           noUnknownFilledWithZero=True,configuredQuotaNotProviderBillingCap=True)


def free_port():
    with socket.socket() as sock: sock.bind(("127.0.0.1",0)); return sock.getsockname()[1]


def app_command(side,port,base_url,out,*,offline=False):
    values={"spring.config.location":"classpath:/application.yml","spring.profiles.active":"",
      "server.address":"127.0.0.1","server.port":port,"agent.eval.enabled":"false",
      "spring.datasource.url":"jdbc:postgresql://localhost:5432/olist","spring.datasource.username":"text2sql_ro",
      "spring.datasource.password":"text2sql_ro","spring.datasource.hikari.read-only":"true",
      "spring.ai.model.chat":"none","spring.ai.model.embedding":"none","agent.persistence.enabled":"false",
      "agent.llm.base-url":base_url,"agent.llm.completions-path":"/v1/chat/completions","agent.llm.api-key":"local-evaluation-proxy-sentinel",
      "agent.llm.model":"deepseek-flash","agent.llm.max-tokens":8192,"agent.llm.temperature":0,
      "agent.llm.reasoning-effort":"provider-default",
      "agent.llm.input-price-per-1k":"0.001","agent.llm.output-price-per-1k":"0.004","agent.llm.cache-hit-price-per-1k":"0.00002",
      "agent.request-budget.enabled":"true","agent.request-budget.timeout-ms":15000,"agent.request-budget.max-model-calls":2,
      "agent.request-budget.min-retry-remaining-ms":2000,"agent.retrieval.enabled":"true","agent.retrieval.top-k":8,
      "agent.retrieval.column-pruning-enabled":"true","agent.retrieval.join-hints-enabled":"true","agent.retrieval.join-plan-enabled":"true",
      "agent.retrieval.join-list-enabled":"false","agent.retrieval.relation-decay":"0.4","agent.retrieval.bridge-repair-enabled":"true",
      "agent.retrieval.bridge-repair-max-tables":2,"agent.semantic.enabled":"true","agent.fewshot.enabled":"true",
      "agent.fewshot.max-examples":2,"agent.fewshot.require-available-tables":"false","agent.clarification.enabled":"true",
      "agent.self-correction.enabled":"true","agent.self-correction.max-attempts":2,"agent.result-checker.enabled":"true",
      "agent.prompt.explicit-reasoning-enabled":"true","agent.prompt.include-data-profile":"true","agent.prompt.include-foreign-keys":"true",
      "agent.guard.limit-mode":"APPEND","agent.db.max-rows":200,"agent.db.query-timeout-seconds":10,
      "agent.cache.enabled":"false","agent.routing.enabled":"false","agent.tool-use.enabled":"false","agent.conversation.enabled":"false",
      "agent.observability.enabled":"true","agent.observability.include-content":"false","agent.observability.dir":str(out/"runtime-calls"),
      "agent.access.enabled":"true","agent.access.token":"${AGENT_ACCESS_TOKEN}",
      "agent.cost-quota.enabled":"true","agent.cost-quota.total-yuan":"1.00","agent.cost-quota.reservation-yuan":"0.10",
      "agent.cost-quota.state-file":str(out/"offline-cost-quota.json" if offline else BATCH/"candidate-cost-quota.json")}
    return [str(JAVA),"-Duser.timezone=Asia/Shanghai","-jar",str(ROOT/SYSTEMS[side]["jar"]),*["--"+k+"="+str(v) for k,v in values.items()]]


def http_json(url,*,data=None,token=None,timeout=20,evidence=None):
    headers={"Content-Type":"application/json"}
    if token: headers["Authorization"]="Bearer "+token
    request=urllib.request.Request(url,data=None if data is None else json.dumps(data,ensure_ascii=False).encode(),headers=headers)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    status=None;raw=b"";meta=dict(startedAt=stamp(),responseComplete=False)
    try:
        try: response=opener.open(request,timeout=timeout)
        except urllib.error.HTTPError as error: response=error
        with response:
            status=response.code
            raw=response.read(16*1024*1024+1)
            meta.update(httpStatus=status,responseComplete=True,returnedAt=stamp(),responseBytes=len(raw),
                        responseBodySha256=hashlib.sha256(raw).hexdigest())
        if evidence: write_bytes(Path(evidence).with_suffix(".body.bin"),raw)
        if len(raw)>16*1024*1024:
            meta["responseComplete"]=False
            raise Refused("HTTP response size bound")
        body=strict_json(raw)
        if not isinstance(body,dict): raise Refused("HTTP answer is not a JSON object")
        return status,body
    except BaseException as error:
        if isinstance(getattr(error,"partial",None),bytes): raw=error.partial
        meta.update(error=type(error).__name__+": "+str(error),returnedAt=stamp())
        raise
    finally:
        if evidence:
            raw_path=Path(evidence).with_suffix(".body.bin")
            if not raw_path.exists(): write_bytes(raw_path,raw)
            meta.update(responseBytes=len(raw),responseBodySha256=hashlib.sha256(raw).hexdigest())
            write(evidence,meta)


@contextmanager
def application(side,proxy,out,*,offline=False):
    port=free_port(); token=secrets.token_urlsafe(32); env=clean_env();env["AGENT_ACCESS_TOKEN"]=token
    with (out/"application.log").open("xb") as log:
        process=subprocess.Popen(app_command(side,port,proxy.base_url,out,offline=offline),cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                if process.poll() is not None: raise Refused("Application exited before readiness")
                try:
                    code,body=http_json(f"http://127.0.0.1:{port}/api/health",timeout=1)
                    if code==200 and body.get("llmConfigured"):
                        write(out/"health.json",body); break
                except (OSError,ValueError): pass
                time.sleep(.2)
            else: raise Refused("Application startup timeout")
            yield f"http://127.0.0.1:{port}",token
        finally:
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=15)


def real_key():
    key=os.environ.get("AGENT_LLM_API_KEY","").strip()
    if not key and os.name=="nt":
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER,"Environment") as handle: key=winreg.QueryValueEx(handle,"AGENT_LLM_API_KEY")[0].strip()
        except FileNotFoundError: pass
    if not key: raise Refused("No provider key; no paid batch started")
    return key


def validate_completed_rounds(state):
    rounds=state["rounds"]
    keys=list(rounds)
    if keys!=list(ORDER[:len(keys)]): raise Refused("Round order/state was changed")
    existing={p.name for p in BATCH.iterdir() if p.is_dir() and p.name in ORDER}
    if existing!=set(keys): raise Refused("Round directory/state mismatch; no silent reset")
    questions=0
    for name,record in rounds.items():
        if record["status"]!="COMPLETED": raise Refused("Unfinished round never resumes")
        saved=read(BATCH/name/"summary.json")
        if saved!={k:v for k,v in record.items() if k!="summarySha256"}: raise Refused("Round summary/state drift")
        if sha(BATCH/name/"summary.json")!=record["summarySha256"]: raise Refused("Round summary bytes drift")
        for path,value in record["evidence"].items():
            if not (ROOT/path).is_file() or sha(ROOT/path)!=value: raise Refused("Prior paid evidence drift")
        responses=read(BATCH/name/"responses.json")["items"]
        if len(responses)!=(2 if name=="TARGETS" else 100): raise Refused("Prior denominator drift")
        questions+=len(responses)
    if state["requestsStarted"]!=questions: raise Refused("Question counter differs from immutable completed answers")


def offline_preflight(plan,output):
    """Two actual HTTP requests per JAR plus 98 explicitly synthetic score rows.

    No real key is resolved; local-only proxy endpoint and synthetic usage.
    """
    from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
    from replan_stage_d_proxy import ModelProxy
    output=Path(output); output.mkdir(parents=True,exist_ok=False)
    prepared=read(BATCH/"dev-reference.json")
    targets=read(ROOT/"reports/replan-stage-a-20261119/original-target-preregistration.json")["targets"]
    t3=next(t for t in targets if t["id"]=="T3-002")
    chosen=[prepared["items"][0],next(i for i in prepared["items"] if i["id"]=="T3-002")]
    if chosen[0]["id"]!="T1-002": raise Refused("Offline smoke fixture identity changed")
    current={"question":None}; received=[]
    class Stub(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            body=json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            received.append(dict(model=body.get("model"),bytes=int(self.headers["Content-Length"])))
            sql=t3["originalGoldSql"] if current["question"]==t3["originalQuestion"] else "SELECT COUNT(*) FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'"
            response={"id":"offline-fixture","object":"chat.completion","created":1,"model":"deepseek-flash",
              "choices":[{"index":0,"message":{"role":"assistant","content":sql},"finish_reason":"stop"}],
              "usage":{"prompt_tokens":10,"completion_tokens":10,"total_tokens":20,"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":10}}
            raw=json.dumps(response).encode();self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(raw)));self.end_headers();self.wfile.write(raw)
    server=ThreadingHTTPServer(("127.0.0.1",0),Stub);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    results=[]
    try:
        for side in ("B","F"):
            out=output/side;out.mkdir()
            ledger=out/"synthetic-ledger.json";write(ledger,dict(planSha256=sha(BATCH/"plan.json"),calls=[],rejections=[],breaches=[]))
            gate=SendGate(ledger,sha(BATCH/"plan.json"),"OFFLINE_"+side,clock=lambda:datetime(2026,11,21,12,tzinfo=BEIJING))
            rows={}
            with ModelProxy("synthetic-local-key",gate.before,gate.after,
                   upstream_url=f"http://127.0.0.1:{server.server_port}/v1/chat/completions",allow_loopback_test=True) as proxy:
                with application(side,proxy,out,offline=True) as (url,token):
                    for item in chosen:
                        current["question"]=item["question"]; started=time.monotonic();gate.begin(item,started+15)
                        evidence=out/"http-answers"/(item["id"]+".json")
                        try: code,body=http_json(url+"/api/ask",data={"question":item["question"]},token=token,timeout=20,evidence=evidence)
                        finally: gate.end()
                        rows[item["id"]]=dict(id=item["id"],httpStatus=code,body=body,wallLatencyMs=(time.monotonic()-started)*1000,
                            returnedAt=read(evidence)["returnedAt"],httpEvidence=evidence.relative_to(ROOT).as_posix())
                        write(out/"http-answers"/(item["id"]+"-parsed.json"),rows[item["id"]])
                        if code!=200 or body.get("status")!="SUCCESS": raise Refused("Offline actual HTTP smoke failed: "+side+" "+item["id"])
                        budget=body.get("budget") or {}
                        if budget.get("timeoutMs")!=15000 or budget.get("maxModelCalls")!=2 or budget.get("modelCalls",3)>2:
                            raise Refused("Offline runtime budget fields/config missing: "+side)
                proxy.drain(timeout_seconds=30)
                if proxy.errors or gate.data["rejections"]: raise Refused("Offline proxy/gate failed")
            reconciliation=gate.reconcile(list(rows.values()))
            write(out/"http-reconciliation.json",reconciliation)
            mixed=[]
            for item in prepared["items"]:
                mixed.append(rows.get(item["id"],dict(id=item["id"],httpStatus=200,body={"status":"SUCCESS",**item["gold"]})))
            write(out/"responses.json",dict(scope="2 actual local HTTP answers and 98 synthetic Gold rows; not model accuracy",items=mixed))
            bridge(plan["classpath"],"score",BATCH/"dev-reference.json",out/"responses.json",out/"score.json")
            score=read(out/"score.json")
            if score["total"]!=100 or score["correct"]!=100: raise Refused("Common score integration failed")
            results.append(dict(side=side,actualHttp=2,syntheticRows=98,scored=100,correct=100,proxySyntheticUsage=gate.summary(reconciliation=reconciliation)))
        result=dict(passed=True,planSha256=sha(BATCH/"plan.json"),systems=results,localModelRequests=len(received),
          externalProviderCalls=0,providerTokens=0,providerCostYuan=0,notModelAccuracy=True)
        write(output/"result.json",result)
        return result
    finally:
        server.shutdown();server.server_close();thread.join(timeout=5)


def preflight():
    plan=read(BATCH/"plan.json");verify(plan)
    if read(BATCH/"state.json")["requestsStarted"] or read(BATCH/"send-ledger.json")["calls"]: raise Refused("Preflight cannot touch a started paid batch")
    result=offline_preflight(plan,BATCH/"offline-preflight")
    verify(plan)
    result["evidence"]={p.relative_to(ROOT).as_posix():sha(p) for p in (BATCH/"offline-preflight").rglob("*") if p.is_file()}
    write(BATCH/"preflight.json",result)
    print("D offline preflight passed; independent paid admission still required",flush=True)


def run_next():
    from replan_stage_d_proxy import ModelProxy
    with locked():
        plan=read(BATCH/"plan.json"); state=read(BATCH/"state.json"); plan_sha=sha(BATCH/"plan.json")
        if state["planSha256"]!=plan_sha: raise Refused("State/plan identity")
        verify(plan)
        validate_completed_rounds(state)
        admission=read(BASE/"independent-review/decision.json")
        if admission.get("accepted") is not True or admission.get("planSha256")!=plan_sha: raise Refused("No matching independent D admission")
        preflight=read(BATCH/"preflight.json")
        if preflight.get("passed") is not True or preflight.get("planSha256")!=plan_sha: raise Refused("Preflight missing")
        for path,value in preflight["evidence"].items():
            if sha(ROOT/path)!=value: raise Refused("Preflight evidence drift")
        name=next_round(state); whole_window=require_window(ROUND_GUARD)
        side="B" if name.startswith("B") else "F"
        dataset="dev" if name=="DEV_F" else "targets" if name=="TARGETS" else "formal"
        prepared=read(BATCH/(dataset+"-reference.json")); items=prepared["items"]
        if state["requestsStarted"]+len(items)>MAX_REQUESTS: raise Refused("Global question limit")
        gate=SendGate(BATCH/"send-ledger.json",plan_sha,name)
        if gate.data["breaches"] or gate.data["rejections"] or gate.committed()+RESERVE>LIMIT: raise Refused("Existing send/cost stop")
        key=real_key()  # Last prerequisite, after identities, state, independent admission, and clock.
        out=BATCH/name; out.mkdir()
        state["rounds"][name]=dict(status="RUNNING",startedAt=stamp(),side=side,dataset=dataset,wholeWindow=whole_window)
        write(BATCH/"state.json",state,replace=True)
        responses=[]; began=time.monotonic()
        try:
            with ModelProxy(key,gate.before,gate.after) as proxy:
                with application(side,proxy,out) as (url,access_token):
                    last_start=0.
                    with (out/"responses.jsonl").open("xb") as journal:
                        for item in items:
                            verify(plan,database=False)
                            if time.monotonic()-began>1620: raise Refused("Round elapsed bound; preserve interruption")
                            if gate.data["breaches"] or gate.data["rejections"] or proxy.errors: raise Refused("Transport/usage/local gate stop")
                            while time.monotonic()-last_start<2.1: time.sleep(.05)
                            require_window(SEND_GUARD)
                            state["requestsStarted"]+=1; write(BATCH/"state.json",state,replace=True)
                            started=time.monotonic(); last_start=started; gate.begin(item,started+15)
                            evidence=out/"http-answers"/(item["id"]+".json")
                            try: status,body=http_json(url+"/api/ask",data={"question":item["question"]},token=access_token,timeout=20,evidence=evidence)
                            finally: gate.end()
                            wall=(time.monotonic()-started)*1000
                            row=dict(id=item["id"],httpStatus=status,body=body,wallLatencyMs=wall,
                                returnedAt=read(evidence)["returnedAt"],httpEvidence=evidence.relative_to(ROOT).as_posix())
                            responses.append(row);journal.write((json.dumps(row,ensure_ascii=False)+"\n").encode());journal.flush();os.fsync(journal.fileno())
                            budget=body.get("budget")
                            if not isinstance(budget,dict) or budget.get("timeoutMs")!=15000 or budget.get("maxModelCalls")!=2 or budget.get("modelCalls",3)>2:
                                raise Refused("Runtime request budget drift")
                            if status in (401,403,429) or body.get("status")=="RESOURCE_LIMITED": raise Refused("Protected runtime local admission stop")
                            if len(responses)%10==0: print(name,"HTTP answers",len(responses),"/",len(items),flush=True)
                proxy.drain(timeout_seconds=120)
                if proxy.errors: raise Refused("Proxy callback/transport lifecycle error")
            if gate.data["rejections"] or gate.data["breaches"]: raise Refused("Send/usage stop")
            verify(plan)
            write(out/"responses.json",dict(items=responses))
            bridge(plan["classpath"],"score",BATCH/(dataset+"-reference.json"),out/"responses.json",out/"score.json")
            score=read(out/"score.json")
            reconciliation=gate.reconcile(responses);write(out/"http-reconciliation.json",reconciliation)
            costs=gate.summary(name,reconciliation)
            summary={k:score[k] for k in ("total","correct","byLayer")}
            summary.update(wallP95Ms=percentile([r["wallLatencyMs"] for r in responses]),
                wallMeanMs=sum(r["wallLatencyMs"] for r in responses)/len(responses),
                timeoutCount=sum(r["body"].get("status")=="REQUEST_TIMEOUT" or (r["body"].get("budget") or {}).get("termination")=="DEADLINE_EXCEEDED" for r in responses),
                accounting=costs,newDangerousAllowances=0,newDangerousAllowancesScope="independent C/D tested boundaries; not proof of all possible SQL")
            result=dict(status="COMPLETED",side=side,dataset=dataset,summary=summary,finishedAt=stamp())
            if name=="DEV_F":
                result["gate"]=dev_gate(summary)
                if not result["gate"]["passed"]: state["terminal"]="DEVELOPMENT_FAILED_NO_C2"
            elif name=="TARGETS":
                dev_score=read(BATCH/"DEV_F/score.json")
                t3=[r for r in dev_score["items"] if r["id"]=="T3-002"]
                if len(t3)!=1 or "originalTargetCorrect" not in t3[0]: raise Refused("T3 original response identity missing")
                hits=sum(bool(r["correct"]) for r in score["items"])+int(t3[0]["originalTargetCorrect"])
                result["gate"]=dict(passed=hits>=2,correct=hits,total=3,T3Reused=True,extraQuestions=2)
                if hits<2: state["terminal"]="ORIGINAL_TARGETS_FAILED"
            state["rounds"][name]=result
            if name=="F3":
                state["formalGate"]=formal_gate(state["rounds"])
                if not state["formalGate"]["passed"]: state["terminal"]="FORMAL_FAILED_NO_UNSEEN"
            call_tokens={c["token"] for c in gate.data["calls"] if c["round"]==name}
            events=[p for p in gate.events.iterdir() if any(p.name.startswith(token+"-") for token in call_tokens)]
            result["evidence"]={p.relative_to(ROOT).as_posix():sha(p) for p in [*out.rglob("*"),*events] if p.is_file()}
            write(out/"summary.json",result)
            state["rounds"][name]={**result,"summarySha256":sha(out/"summary.json")}
            write(BATCH/"state.json",state,replace=True)
            print(json.dumps(result,ensure_ascii=False),flush=True)
        except BaseException as error:
            state["rounds"][name].update(status="INTERRUPTED",reason=type(error).__name__+": "+str(error),finishedAt=stamp(),completedAnswers=len(responses))
            state["terminal"]="INTERRUPTED_NO_REPLAY"
            write(out/"interruption.json",state["rounds"][name]);write(BATCH/"state.json",state,replace=True)
            raise


def main():
    parser=argparse.ArgumentParser();parser.add_argument("action",choices=("prepare","preflight","run-next","audit"));args=parser.parse_args()
    if args.action=="prepare": prepare()
    elif args.action=="preflight": preflight()
    elif args.action=="run-next": run_next()
    else:
        plan=read(BATCH/"plan.json");verify(plan);print(json.dumps(read(BATCH/"state.json"),ensure_ascii=False,indent=2))


if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8",errors="backslashreplace")
    try: main()
    except Refused as error: print("REFUSED:",str(error),file=sys.stderr);sys.exit(2)
