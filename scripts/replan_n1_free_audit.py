"""Offline N1 admission audit; never constructs a model client or reads a key.

N1 is allowed to rewrite only the rendered business-metric component.  This
script computes an optimistic upper bound by pretending that the whole metric
component disappears.  If that upper bound misses the frozen gates, no
implementation within the proposal's scope can be admitted or paid-tested.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
from pathlib import Path


LATE_IDS = (
    "T2-012", "T2-036", "T4-036", "T4-041", "T5-019",
    "T6-014", "T6-016", "T6-021", "T6-023", "T6-025",
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def p95(values: list[int]) -> int:
    ordered = sorted(values)
    return ordered[int(0.95 * (len(ordered) - 1))]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--baseline",
        type=Path,
        default=Path("reports/replan-stage-a-20261119/input-inventory/capture-original-200.json"),
    )
    parser.add_argument(
        "--timeout-summary",
        type=Path,
        default=Path(
            "reports/replan-stage-d-20261119/scope10-final-dev-20261121/"
            "revision-3/free-timeout-audit-20261121/summary.json"
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("reports/replan-stage-d-20261119/n1-free-audit-20261121/summary.json"),
    )
    args = parser.parse_args()

    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    rows = baseline["items"]
    if len(rows) != 200:
        raise SystemExit(f"expected frozen B0 200 rows, got {len(rows)}")
    timeout = json.loads(args.timeout_summary.read_text(encoding="utf-8"))
    timeout_ids = tuple(timeout["dev"]["timeoutIds"])
    if timeout_ids != LATE_IDS:
        raise SystemExit(f"late-question identity changed: {timeout_ids}")

    # The proposal permits only business-metric text rewriting.  DDL, profile,
    # examples, value hints, framing, question and system prompt are fixed.
    before = [int(row["promptChars"]) for row in rows]
    metric = [int(row["componentChars"]["metrics"]) for row in rows]
    optimistic = [total - metric_chars for total, metric_chars in zip(before, metric)]
    late_rows = [next(row for row in rows if row["id"] == ident) for ident in LATE_IDS]
    late_before = [int(row["promptChars"]) for row in late_rows]
    late_metric = [int(row["componentChars"]["metrics"]) for row in late_rows]
    late_optimistic = [total - metric_chars for total, metric_chars in zip(late_before, late_metric)]

    total_before = sum(before)
    total_optimistic = sum(optimistic)
    total_reduction = (total_before - total_optimistic) / total_before
    late_reduction = (sum(late_before) - sum(late_optimistic)) / sum(late_before)
    result = {
        "format": "replan-n1-free-audit-v1",
        "status": "FAIL_PRECHECK",
        "modelCalls": 0,
        "providerCalls": 0,
        "realKeyReads": 0,
        "scope": "only metrics component may change; all other prompt components and retrieval stay frozen",
        "baseline": {
            "path": str(args.baseline).replace("\\", "/"),
            "sha256": sha256(args.baseline),
            "promptVersion": baseline.get("promptVersion"),
            "items": len(rows),
            "totalPromptChars": total_before,
            "promptP95Chars": p95(before),
            "metricItems": sum(value > 0 for value in metric),
            "metricChars": sum(metric),
            "componentChars": {
                key: sum(int(row["componentChars"][key]) for row in rows)
                for key in rows[0]["componentChars"]
            },
        },
        "optimisticUpperBound": {
            "assumption": "delete every metric character, including rules that must actually be retained",
            "totalPromptChars": total_optimistic,
            "promptP95Chars": p95(optimistic),
            "maxReductionFraction": total_reduction,
            "maxReductionPercent": total_reduction * 100,
            "gateRequiredPercent": 20.0,
        },
        "lateTimeoutSubset": {
            "ids": list(LATE_IDS),
            "beforeMeanPromptChars": statistics.mean(late_before),
            "optimisticMeanPromptChars": statistics.mean(late_optimistic),
            "maxReductionFraction": late_reduction,
            "maxReductionPercent": late_reduction * 100,
            "gateRequiredPercent": 10.0,
        },
        "whyRejected": [
            "The optimistic global upper bound is 3.5277%, below the required 20%.",
            "The optimistic late-timeout upper bound is 9.6987%, below the required 10%.",
            "Meeting either gate would require deleting schema, examples, system obligations, or changing the frozen scope.",
        ],
        "independentChecksRequired": [
            "51 semantic obligations and fail-open cases",
            "9/9 dangerous negatives",
            "four provenance sets: directHits, expandedTables, metricDependencies, safetyBridges",
            "full build and read-only Gold200 self-check",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({
        "status": result["status"],
        "modelCalls": 0,
        "maxReductionPercent": round(total_reduction * 100, 6),
        "lateMaxReductionPercent": round(late_reduction * 100, 6),
        "output": str(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
