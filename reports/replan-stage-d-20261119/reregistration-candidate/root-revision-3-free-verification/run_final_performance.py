"""Same-source synthetic scale measurement; no model/key/database calls."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "scripts"))
import benchmark_replan_quota_monitor as b


if __name__ == "__main__":
    b.OUTPUT = Path(__file__).resolve().parent / "final-performance-1"
    b.OUTPUT.mkdir(parents=True, exist_ok=False)
    source = b.n.sha(Path(b.q.__file__))
    results = []
    for questions in (102, 902):
        b.n.d.BATCH = b.OUTPUT / str(questions)
        results.append(b.build(questions))
    assert b.n.sha(Path(b.q.__file__)) == source, "Source changed during scale measurement"
    b.plain(b.OUTPUT / "verification.json", dict(results=results, sourceSha256=source,
            supplierCalls=0, keyReads=0, noP95OrAccuracyClaim=True,
            firstMeasurement="New monitor without parsed cache; storage was written during construction, not controlled OS cold cache"))
    print(json.dumps(dict(passed=True, sourceSha256=source, results=results, supplierCalls=0)))
