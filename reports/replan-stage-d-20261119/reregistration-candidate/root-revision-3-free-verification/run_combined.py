"""Final free suite; fresh evidence paths, no actual paid authorization."""
from __future__ import annotations

import hashlib
import importlib
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "scripts"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    run = OUT / sys.argv[1]
    run.mkdir()
    (run / "temp").mkdir()
    tempfile.tempdir = str(run / "temp")
    names = ("test_replan_stage_d", "test_replan_stage_d_proxy", "test_replan_durable_write",
             "test_replan_stage_d_reregistered", "test_replan_stable_proxy", "test_replan_quota_monitor")
    modules = [importlib.import_module(name) for name in names]
    for module in modules:
        if hasattr(module, "EVIDENCE"):
            module.EVIDENCE = (run / (module.__name__ + "-evidence")).resolve()
    sources = {path.relative_to(ROOT).as_posix(): sha(path) for path in [
        *[ROOT / "scripts" / (name + ".py") for name in names],
        ROOT / "scripts/run_replan_stage_d.py", ROOT / "scripts/replan_stage_d_proxy.py",
        ROOT / "scripts/run_replan_stage_d_reregistered.py", ROOT / "scripts/replan_quota_monitor.py",
        ROOT / "scripts/replan_durable_write.py", ROOT / "scripts/replan_stable_proxy.py"]}
    suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromModule(module) for module in modules)
    stream = io.StringIO()
    started = time.monotonic()
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    elapsed = time.monotonic() - started
    with (run / "combined-suite.log").open("xb") as file:
        file.write(stream.getvalue().encode("utf-8"))
    assert all(sha(ROOT / path) == expected for path, expected in sources.items()), "Source changed during final suite"
    verification = dict(testsRun=result.testsRun, failures=len(result.failures), errors=len(result.errors),
                        skipped=len(result.skipped), passed=result.wasSuccessful(), elapsedSeconds=elapsed,
                        sources=sources, failureDetails=[name.id() for name, _ in result.failures],
                        errorDetails=[name.id() for name, _ in result.errors],
                        actualPaidAuthorizationCreated=False, providerCalls=0, providerCostYuan="0",
                        scope="One complete same-source free suite; real network tests use local synthetic upstream only")
    with (run / "verification.json").open("x", encoding="utf-8", newline="\n") as file:
        json.dump(verification, file, ensure_ascii=False, indent=2)
        file.write("\n")
    print(json.dumps({key: value for key, value in verification.items() if key != "sources"}, ensure_ascii=False))
    sys.exit(0 if result.wasSuccessful() else 1)
