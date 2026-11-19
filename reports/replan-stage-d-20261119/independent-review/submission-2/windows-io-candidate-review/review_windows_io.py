"""Independent free review of the unintegrated local persistence candidate."""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[5]
OUT=Path(__file__).resolve().parent
sys.dont_write_bytecode=True
sys.path.insert(0,str(ROOT/"scripts"))
import replan_durable_write as candidate
import run_replan_stage_d as frozen


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(name,value):
    with (OUT/name).open("x",encoding="utf-8") as f:json.dump(value,f,ensure_ascii=False,indent=2)


def main():
    files=[ROOT/"scripts/replan_durable_write.py",ROOT/"scripts/test_replan_durable_write.py",ROOT/"scripts/replan_durable_write_integration.patch"]
    identities={p.relative_to(ROOT).as_posix():sha(p) for p in files}
    temp=OUT/"temp";temp.mkdir();tempfile.tempdir=str(temp)
    stream=io.StringIO();suite=unittest.defaultTestLoader.loadTestsFromModule(importlib.import_module("test_replan_durable_write"))
    result=unittest.TextTestRunner(stream=stream,verbosity=2).run(suite)
    with (OUT/"supplied-suite.log").open("xb") as f:f.write(stream.getvalue().encode())
    write("supplied-suite.json",dict(testsRun=result.testsRun,failures=len(result.failures),errors=len(result.errors),skipped=len(result.skipped),passed=result.wasSuccessful(),providerCalls=0))
    checks=[]
    for code in (5,32,33):
        calls=[];clock=[0.]
        def replace(source,destination):
            calls.append((str(source),str(destination)))
            if len(calls)<3:
                error=PermissionError("independent synthetic Windows collision");error.winerror=code;raise error
        outcome=candidate.replace_with_retry(Path("one-temp"),Path("one-destination"),replace=replace,clock=lambda:clock[0],sleep=lambda n:clock.__setitem__(0,clock[0]+n))
        assert len(calls)==3 and len(set(calls))==1
        checks.append(dict(id=f"winerror_{code}_same_paths_retry",passed=True,attempts=outcome["attempts"],elapsedSeconds=outcome["elapsedSeconds"]))
    calls=[];clock=[0.]
    def denied(*paths):
        calls.append(paths);error=PermissionError("persistent synthetic collision");error.winerror=5;raise error
    try:candidate.replace_with_retry(Path("temp"),Path("dest"),max_attempts=2,replace=denied,clock=lambda:clock[0],sleep=lambda n:clock.__setitem__(0,clock[0]+n))
    except PermissionError:assert len(calls)==2
    else:raise AssertionError("max_attempts bypass")
    checks.append(dict(id="attempt_bound_stops_permanent_denial",passed=True,attempts=len(calls)))
    calls=[]
    def unrelated(*paths):calls.append(paths);error=OSError("disk error");error.winerror=112;raise error
    try:candidate.replace_with_retry(Path("temp"),Path("dest"),replace=unrelated)
    except OSError:assert len(calls)==1
    else:raise AssertionError("unrelated error accepted")
    checks.append(dict(id="unrelated_winerror_never_retried",passed=True,attempts=len(calls)))
    # Deliberately expose the advertised limit's exact scope. A synchronous OS
    # call itself cannot be interrupted by the helper's retry deadline.
    clock=[0.]
    def slow_success(*paths):clock[0]+=.6
    slow=candidate.replace_with_retry(Path("temp"),Path("dest"),replace=slow_success,clock=lambda:clock[0],sleep=lambda n:clock.__setitem__(0,clock[0]+n))
    assert slow["elapsedSeconds"]==.6
    checks.append(dict(id="deadline_does_not_cancel_a_blocking_replace",passed=True,elapsedSeconds=slow["elapsedSeconds"],limitation=True))
    native=dict(executed=False)
    if os.name=="nt":
        api=ctypes.WinDLL("kernel32",use_last_error=True)
        api.CreateFileW.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,wintypes.DWORD,ctypes.c_void_p,wintypes.DWORD,wintypes.DWORD,wintypes.HANDLE]
        api.CreateFileW.restype=wintypes.HANDLE
        api.CloseHandle.argtypes=[wintypes.HANDLE];api.CloseHandle.restype=wintypes.BOOL
        case=OUT/"native-permanent-handle";case.mkdir();source=case/"same.tmp";dest=case/"ledger.json"
        old=b'{"calls":0}\n';new=b'{"calls":1}\n';source.write_bytes(new);dest.write_bytes(old)
        handle=api.CreateFileW(str(dest),0x80000000,0x1|0x2,None,3,0,None)
        if handle==wintypes.HANDLE(-1).value:raise ctypes.WinError(ctypes.get_last_error())
        try:
            try:candidate.replace_with_retry(source,dest,max_wait_seconds=.05)
            except OSError as error:native=dict(executed=True,failedClosed=True,winerror=error.winerror)
            else:raise AssertionError("permanently held handle unexpectedly allowed replacement")
            assert source.read_bytes()==new and dest.read_bytes()==old
            native.update(tempOriginalBytesPreserved=True,destinationOriginalBytesPreserved=True)
        finally:api.CloseHandle(handle)
        checks.append(dict(id="native_permanent_handle_retains_temp_and_old_dest",passed=True,**native))
    patch_result=subprocess.run(["git","apply","--check","scripts/replan_durable_write_integration.patch"],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
    helper=ROOT/"scripts/replan_durable_write.py"
    bound=helper in frozen.tool_files()
    assert not bound and patch_result.returncode==0
    write("independent-boundaries.json",dict(checks=checks,passed=len(checks),providerCalls=0))
    write("integration-findings.json",dict(providerCalls=0,patchAppliesWithoutMutation=patch_result.returncode==0,
        helperIncludedInExistingToolFileList=bound,
        patchAddsHelperToFrozenPlanFileList=False,
        fullIntegrationAccepted=False,
        finding="The patch adds a runtime import but does not add replan_durable_write.py to tool_files; a future plan created from that patched controller would not detect helper source drift.",
        localRetryMechanismSupportedByTests=True,
        limits=dict(defaultWaitSeconds=.5,defaultAttempts=12,helperAcceptedMaxWaitSeconds=1,helperAcceptedMaxAttempts=20,blockingOsReplaceIsNotInterruptibleByRetryDeadline=True),
        currentStoppedControllerUnmodified=True,currentPaidStateAndLedgerUnmodified=True))
    assert all(sha(ROOT/p)==expected for p,expected in identities.items())
    write("candidate-source-identity.json",dict(files=identities,sourceBytesStableDuringReview=True,providerCalls=0))


if __name__=="__main__":main()
