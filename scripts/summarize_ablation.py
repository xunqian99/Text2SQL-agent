"""Audit real production ablations; stdlib only. No model calls or benchmark edits."""
import argparse
import json
import math
from pathlib import Path
from statistics import mean, stdev


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def stats(values):
    return {"values": values, "mean": mean(values), "min": min(values), "max": max(values),
            "sampleStdDev": stdev(values) if len(values) > 1 else 0}


def summarize(root):
    root = Path(root)
    manifest = read(root / "manifest.json")
    errors, runs, item_sets = [], [], {}

    def require(condition, message):
        if not condition:
            errors.append(message)

    require(manifest["state"] == "COMPLETE", "matrix is not complete")
    require(manifest["rounds"] >= 3, "fewer than three independent rounds")
    reference_meta, reference_items, hashes = None, None, {}
    ignored_meta = {"timestamp", "worktreeState", "productionSelfCorrectionEnabled",
                    "productionSelfCorrectionMaxAttempts", "effectiveConfig"}
    for run in manifest["runs"]:
        name = f'{run["round"]}:{run["group"]}'
        require(run["state"] == "COMPLETE", f"{name}: run is incomplete")
        if "report" not in run:
            continue
        directory = Path(run["directory"])
        if not directory.is_absolute() and not directory.exists():
            directory = root / directory.name
        report = read(directory / run["report"])
        meta, overall = report["meta"], report["overall"]
        require(meta["gitCommit"] == manifest["gitCommit"], f"{name}: commit changed")
        require(not meta["dryRun"] and meta["split"] == "eval", f"{name}: not a real eval")
        require(overall["total"] == meta["itemCount"] == 100, f"{name}: wrong denominator")
        config = meta["effectiveConfig"]
        correction = run["group"] in ("correction", "checker")
        require(meta["productionSelfCorrectionEnabled"] == correction, f"{name}: wrong correction flag")
        require(meta["productionSelfCorrectionMaxAttempts"] == (2 if correction else 1), f"{name}: wrong attempt budget")
        require(config["clarificationEnabled"] == (run["group"] != "no-clarification"), f"{name}: wrong clarification flag")
        require(config["resultCheckerEnabled"] == (run["group"] == "checker"), f"{name}: wrong checker flag")
        require(not meta["selfCorrectionEnabled"], f"{name}: evaluator retry leaked into production comparison")
        fixed = {k: v for k, v in meta.items() if k not in ignored_meta}
        fixed["effectiveConfig"] = {k: v for k, v in config.items()
                                    if k not in ("clarificationEnabled", "resultCheckerEnabled")}
        if reference_meta is None:
            reference_meta = fixed
        require(fixed == reference_meta, f"{name}: nonexperimental metadata differs")
        items = report["itemEvidence"]
        by_id = {item["id"]: item for item in items}
        item_sets[(run["round"], run["group"])] = by_id
        require(len(items) == len(by_id) == 100, f"{name}: missing/duplicate item evidence")
        inputs = {k: tuple(v[f] for f in ("questionSha256", "goldSqlSha256", "goldResultSha256"))
                  for k, v in by_id.items()}
        if reference_items is None:
            reference_items = inputs
        require(inputs == reference_items, f"{name}: benchmark/database result fingerprint changed")
        require(sum(i["correct"] for i in items) == overall["correct"], f"{name}: correctness totals disagree")
        flattened_calls = []
        recovered, regressed, retried, clarifications = [], [], [], []
        first_correct = 0
        for item in items:
            label = f'{name}/{item["id"]}'
            calls, attempts = item["calls"], item["productionAttempts"]
            require(len(calls) <= (2 if correction else 1), f"{label}: call budget exceeded")
            if item["status"] == "NEEDS_CLARIFICATION":
                clarifications.append(item["id"])
                require(not calls and not attempts and item["selectedAttempt"] == 0, f"{label}: clarification called model")
            else:
                require(len(attempts) == len(calls) and bool(attempts), f"{label}: missing attempts/calls")
            for call in calls:
                require(len(call.get("promptSha256") or "") == 64, f"{label}: missing prompt hash")
                require(call["usage"] is not None, f"{label}: usage unknown; cannot certify total cost")
            if calls:
                current_hash = calls[0]["promptSha256"]
                hashes.setdefault(item["id"], set()).add(current_hash)
            known = [c for c in calls if c["usage"] is not None]
            flattened_calls.extend(known)
            total = item["totalUsage"]
            for field in ("promptTokens", "completionTokens", "totalTokens", "cacheHitTokens", "cacheMissTokens", "costYuan"):
                recorded = total[field] if total else 0
                require(math.isclose(recorded, sum(c["usage"][field] for c in known), abs_tol=1e-9),
                        f"{label}: {field} ledger mismatch")
            if attempts:
                first_correct += attempts[0]["correct"]
                selected = item["selectedAttempt"]
                require(1 <= selected <= len(attempts), f"{label}: invalid selection")
                if 1 <= selected <= len(attempts):
                    chosen = attempts[selected - 1]
                    require((chosen["sql"], chosen["status"], chosen["correct"]) ==
                            (item["finalSql"], item["status"], item["correct"]), f"{label}: selected answer mismatch")
                if len(attempts) > 1:
                    retried.append(item["id"])
                    require(item["retryTrigger"] != "NONE", f"{label}: missing retry reason")
                if not attempts[0]["correct"] and item["correct"]:
                    recovered.append(item["id"])
                if attempts[0]["correct"] and not item["correct"]:
                    regressed.append(item["id"])
        ledger = []
        for path in sorted((directory / "llm-calls").glob("*.jsonl")):
            ledger.extend(json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip())
        require(len(ledger) == len(flattened_calls), f"{name}: file ledger call count differs")
        require([e["promptSha256"] for e in ledger] == [c["promptSha256"] for c in flattened_calls],
                f"{name}: file ledger call order/hash differs")
        for field in ("promptTokens", "completionTokens", "totalTokens", "cacheHitTokens", "cacheMissTokens", "costYuan"):
            require(math.isclose(sum(e[field] for e in ledger), sum(c["usage"][field] for c in flattened_calls), abs_tol=1e-8),
                    f"{name}: file ledger {field} differs")
        totals = {f: sum(c["usage"][f] for c in flattened_calls) for f in
                  ("promptTokens", "completionTokens", "cacheHitTokens", "cacheMissTokens", "costYuan")}
        require(abs(totals["costYuan"] - overall["totalCostYuan"]) <= 0.00000051, f"{name}: report cost mismatch")
        require(math.isclose(totals["promptTokens"] / 100, overall["avgPromptTokens"], abs_tol=.0051), f"{name}: report token mismatch")
        runs.append({"round": run["round"], "group": run["group"], "report": str(directory / run["report"]),
                     "correct": overall["correct"], "firstCorrect": first_correct,
                     "p50LatencyMs": overall["p50LatencyMs"], "p95LatencyMs": overall["p95LatencyMs"],
                     "wallP95Ms": sorted(i["wallLatencyMs"] for i in items)[94],
                     "avgLatencyMs": overall["avgLatencyMs"], "calls": len(flattened_calls), **totals,
                     "retried": retried, "recovered": recovered, "regressed": regressed,
                     "clarifications": clarifications, "failures": sorted(i["id"] for i in items if not i["correct"]),
                     "byLayer": report["byLayer"], "byTableCount": report["byTableCount"],
                     "slowest": sorted(({"id": i["id"], "latencyMs": i["wallLatencyMs"],
                                         "calls": len(i["calls"]), "timings": i["timings"]} for i in items),
                                       key=lambda i: i["latencyMs"], reverse=True)[:10]})
    drifting = sorted(k for k, v in hashes.items() if len(v) != 1)
    require(not drifting, f"first-prompt drift: {drifting}")
    groups = {}
    for name in ("baseline", "correction", "checker", "no-clarification"):
        selected = [r for r in runs if r["group"] == name]
        require(sorted(r["round"] for r in selected) == list(range(1, manifest["rounds"] + 1)), f"{name}: incomplete rounds")
        if not selected:
            continue
        groups[name] = {f: stats([r[f] for r in selected]) for f in
                        ("correct", "p50LatencyMs", "p95LatencyMs", "wallP95Ms", "costYuan", "calls")}
        groups[name]["failureUnion"] = sorted(set().union(*(set(r["failures"]) for r in selected)))
        groups[name]["recoveredCount"] = sum(len(r["recovered"]) for r in selected)
        groups[name]["regressedCount"] = sum(len(r["regressed"]) for r in selected)
    comparisons = []
    for round_number in range(1, manifest["rounds"] + 1):
        for before, after in (("baseline", "correction"), ("correction", "checker"), ("baseline", "no-clarification")):
            left, right = item_sets.get((round_number, before)), item_sets.get((round_number, after))
            if left is None or right is None:
                continue
            common = sorted(left.keys() & right.keys())
            first_same, first_different = [], []
            for item_id in common:
                a, b = left[item_id]["productionAttempts"], right[item_id]["productionAttempts"]
                if a and b:
                    identical = all(a[0][f] == b[0][f] for f in ("status", "sql", "resultSha256", "correct"))
                    (first_same if identical else first_different).append(item_id)
            comparisons.append({"round": round_number, "before": before, "after": after,
                                "improved": [k for k in common if not left[k]["correct"] and right[k]["correct"]],
                                "regressed": [k for k in common if left[k]["correct"] and not right[k]["correct"]],
                                "identicalFirstAnswerCount": len(first_same), "differentFirstAnswerIds": first_different})
    return {"format": "production-ablation-summary-v1", "passedEvidenceAudit": not errors,
            "errors": errors, "gitCommit": manifest["gitCommit"], "fixedMetadata": reference_meta,
            "firstPromptDriftIds": drifting, "groups": groups, "runs": runs, "comparisons": comparisons,
            "limits": ["Three repeats estimate variation; they do not prove mechanism causality from score subtraction.",
                       "Only within-request first/final transitions establish actual retry recovery/regression.",
                       "The existing eval split has been inspected and tuned against; it is not a blind test.",
                       "Costs use configured rates, not verified supplier billing."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory")
    args = parser.parse_args()
    result = summarize(args.directory)
    output = Path(args.directory) / "summary.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passedEvidenceAudit": result["passedEvidenceAudit"], "errors": result["errors"],
                      "groups": result["groups"]}, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["passedEvidenceAudit"] else 1)
