"""Audit the preregistered dev/heldout baseline. Standard library, no model calls or Gold edits."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def dataset_hash(directory):
    digest = hashlib.sha256()
    for path in sorted(p for p in Path(directory).iterdir() if re.fullmatch(r"T\d+_.*\.ya?ml", p.name)):
        name, content = path.name.encode(), path.read_bytes()
        digest.update(struct.pack(">i", len(name)) + name + struct.pack(">i", len(content)) + content)
    return digest.hexdigest()


def ids_hash(ids):
    digest = hashlib.sha256()
    for identity in sorted(ids):
        value = identity.encode()
        digest.update(struct.pack(">i", len(value)) + value)
    return digest.hexdigest()


def is_dev(identity):
    value = 0
    encoded = identity.encode("utf-16-be")
    for i in range(0, len(encoded), 2):
        value = (31 * value + int.from_bytes(encoded[i:i + 2], "big")) & 0xffffffff
    return value % 2 == 0


def summarize(root, suite_manifest="data/holdout/v1/manifest.json"):
    root, suite_manifest = Path(root), Path(suite_manifest)
    manifest, seal = read(root / "manifest.json"), read(suite_manifest)
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    require(manifest["state"] == "COMPLETE", "baseline is not complete")
    require(manifest["declaredIndependentReview"] is False and seal["independentReview"] is False,
            "this preregistration requires unreviewed-candidate provenance")
    seal_hash = hashlib.sha256(suite_manifest.read_bytes()).hexdigest()
    require(manifest["suiteManifestSha256"] == seal_hash, "suite manifest changed")
    require(manifest["suiteId"] == seal["suiteId"], "suite identity changed")
    require(dataset_hash(suite_manifest.parent) == seal["datasetSha256"], "frozen YAML changed")
    require(dataset_hash(seal["referenceDir"]) == seal["referenceDatasetSha256"], "reference YAML changed")
    expected_names = ["reference-gold", "holdout-gold", "development", "holdout"]
    require([run["name"] for run in manifest["runs"]] == expected_names, "wrong/missing/duplicate run order")
    reports = {}
    for run in manifest["runs"]:
        require(run["state"] == "COMPLETE", f'{run["name"]}: incomplete')
        report_files = list((root / run["name"]).glob("eval-*.json"))
        require(len(report_files) == 1, f'{run["name"]}: missing/duplicate report')
        reports[run["name"]] = read(root / run["name"] / run["report"])
        require(reports[run["name"]]["meta"]["gitCommit"] == manifest["codeCommit"], f'{run["name"]}: code changed')
        require(run["total"] == reports[run["name"]]["overall"]["total"]
                and run["correct"] == reports[run["name"]]["overall"]["correct"],
                f'{run["name"]}: run manifest count mismatch')
    if set(reports) != set(expected_names):
        return {"passedEvidenceAudit": False, "errors": errors}
    gold_items = {}
    for name, count, fingerprint in (("reference-gold", 200, seal["referenceDatasetSha256"]),
                                     ("holdout-gold", 100, seal["datasetSha256"])):
        report = reports[name]
        items = report["itemEvidence"]
        require(report["meta"]["dryRun"] and report["meta"]["split"] == "all", f"{name}: not full Gold self-check")
        require(report["overall"]["total"] == report["overall"]["correct"] == len(items) == count,
                f"{name}: incomplete/broken Gold")
        require(report["meta"]["datasetSha256"] == fingerprint, f"{name}: input fingerprint changed")
        require(len({i["id"] for i in items}) == count and all(i["correct"] for i in items), f"{name}: Gold items changed")
        require(not any(i["calls"] for i in items), f"{name}: Gold called model")
        gold_items[name] = {i["id"]: i for i in items}
    require(ids_hash(gold_items["holdout-gold"]) == seal["sampleIdsSha256"], "holdout sample IDs changed")
    require(reports["holdout-gold"]["meta"]["effectiveConfig"]["evaluationSuite"]["manifestSha256"] == seal_hash,
            "holdout Gold bypassed suite guard")
    summaries = {}
    common_config = None
    for name, gold_name in (("development", "reference-gold"), ("holdout", "holdout-gold")):
        report = reports[name]
        meta, overall, items = report["meta"], report["overall"], report["itemEvidence"]
        require(not meta["dryRun"] and overall["total"] == meta["itemCount"] == len(items) == 100, f"{name}: denominator changed")
        expected_ids = set(gold_items[gold_name])
        if name == "development":
            expected_ids = {identity for identity in expected_ids if is_dev(identity)}
        require({i["id"] for i in items} == expected_ids and len(expected_ids) == len(items), f"{name}: wrong/duplicate sample set")
        require(meta["sampleIdsSha256"] == ids_hash(expected_ids), f"{name}: sample fingerprint mismatch")
        require(meta["datasetSha256"] == reports[gold_name]["meta"]["datasetSha256"], f"{name}: dataset differs from preflight")
        require(meta["split"] == ("dev" if name == "development" else "all") and meta["limit"] == 0 and not meta["layers"], f"{name}: filtered run")
        config = meta["effectiveConfig"]
        require(meta["productionSelfCorrectionEnabled"] and meta["productionSelfCorrectionMaxAttempts"] == 2,
                f"{name}: wrong production retry budget")
        require(not meta["selfCorrectionEnabled"], f"{name}: evaluator correction enabled")
        for flag in ("clarificationEnabled", "resultCheckerEnabled", "columnPruningEnabled", "joinPlanEnabled", "semanticEnabled", "fewshotEnabled"):
            require(config[flag] is True, f"{name}: {flag} disabled")
        for flag in ("cacheEnabled", "routingEnabled", "toolUseEnabled", "conversationEnabled"):
            require(config[flag] is False, f"{name}: {flag} enabled")
        require(config["maxTokens"] == 8192 and config["temperature"] == 0 and config["fewshotMaxExamples"] == 2, f"{name}: model budget changed")
        require(meta["retrievalTopK"] == 8 and meta["retrievalEnabled"] and meta["joinHintsEnabled"], f"{name}: retrieval changed")
        if name == "holdout":
            identity = config["evaluationSuite"]
            require(identity["manifestSha256"] == seal_hash and identity["exactOverlapCheckPassed"]
                    and identity["declaredIndependentReview"] is False, "holdout: suite guard evidence missing")
        comparable = {k: v for k, v in config.items() if k != "evaluationSuite"}
        for field in ("model", "baseUrl", "promptVersion", "includeDataProfile", "includeForeignKeys", "limitMode", "maxRows"):
            comparable[field] = meta[field]
        if common_config is None:
            common_config = comparable
        require(comparable == common_config, f"{name}: production configuration differs")
        calls, recovered, regressed, retries, unknown, clarifications = [], [], [], [], [], []
        for item in items:
            identity = item["id"]
            gold = gold_items[gold_name][identity]
            for field in ("questionSha256", "goldSqlSha256", "goldResultSha256"):
                require(item[field] == gold[field], f"{name}/{identity}: {field} changed")
            actual_calls, attempts = item["calls"], item["productionAttempts"]
            require(len(actual_calls) <= 2 and len(attempts) <= 2, f"{name}/{identity}: budget exceeded")
            require([c["number"] for c in actual_calls] == list(range(1, len(actual_calls) + 1)), f"{name}/{identity}: call numbering changed")
            require([a["number"] for a in attempts] == list(range(1, len(attempts) + 1)), f"{name}/{identity}: attempt numbering changed")
            if item["status"] == "NEEDS_CLARIFICATION":
                clarifications.append(identity)
                require(not actual_calls and not attempts and not item["correct"], f"{name}/{identity}: clarification counted as answer")
            else:
                require(len(actual_calls) == len(attempts) and len(attempts) >= 1, f"{name}/{identity}: missing production evidence")
                selected = item["selectedAttempt"]
                require(1 <= selected <= len(attempts), f"{name}/{identity}: invalid selected attempt")
                if 1 <= selected <= len(attempts):
                    chosen = attempts[selected - 1]
                    for left, right in (("sql", "finalSql"), ("status", "status"), ("correct", "correct")):
                        require(chosen[left] == item[right], f"{name}/{identity}: selected answer differs")
                if len(attempts) == 2:
                    retries.append({"id": identity, "trigger": item["retryTrigger"]})
                    require(item["retryTrigger"] != "NONE", f"{name}/{identity}: retry trigger absent")
                    if not attempts[0]["correct"] and item["correct"]:
                        recovered.append(identity)
                    if attempts[0]["correct"] and not item["correct"]:
                        regressed.append(identity)
            for call in actual_calls:
                require(bool(re.fullmatch(r"[0-9a-f]{64}", call["promptSha256"] or "")), f"{name}/{identity}: missing input hash")
                if call["usage"] is None:
                    unknown.append(f'{identity}:{call["number"]}')
                else:
                    usage = call["usage"]
                    require(usage["totalTokens"] == usage["promptTokens"] + usage["completionTokens"],
                            f"{name}/{identity}: token components mismatch")
                    require(usage["cacheHitTokens"] + usage["cacheMissTokens"] == usage["promptTokens"],
                            f"{name}/{identity}: cache token components mismatch")
                    expected_cost = (usage["cacheHitTokens"] * config["cacheHitPricePer1k"]
                                     + usage["cacheMissTokens"] * config["inputPricePer1k"]
                                     + usage["completionTokens"] * config["outputPricePer1k"]) / 1000
                    require(abs(usage["costYuan"] - expected_cost) <= .00000051,
                            f"{name}/{identity}: configured price mismatch")
            if actual_calls and not any(c["usage"] is None for c in actual_calls):
                for field in ("promptTokens", "completionTokens", "totalTokens", "cacheHitTokens", "cacheMissTokens", "costYuan"):
                    require(abs(sum(c["usage"][field] for c in actual_calls) - item["totalUsage"][field]) <= .00000051,
                            f"{name}/{identity}: request {field} accounting mismatch")
            calls.extend(actual_calls)
        require(not unknown, f"{name}: unknown usage {unknown}")
        ledger_paths = list((root / name / "llm-calls").glob("*.jsonl"))
        ledger = [json.loads(line) for path in sorted(ledger_paths) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        known = [c for c in calls if c["usage"] is not None]
        require(len(ledger) == len(calls), f"{name}: raw ledger call count mismatch")
        for raw, call in zip(ledger, calls):
            require(raw.get("prompt") is None and raw.get("response") is None, f"{name}: raw ledger stores content")
            require(raw["promptSha256"] == call["promptSha256"] and raw["status"] == call["status"], f"{name}: raw call identity mismatch")
            if call["usage"] is not None:
                for field in ("promptTokens", "completionTokens", "totalTokens", "cacheHitTokens", "cacheMissTokens", "costYuan"):
                    require(abs(raw[field] - call["usage"][field]) <= .00000051, f"{name}: ledger {field} mismatch")
        totals = {field: sum(c["usage"][field] for c in known) for field in
                  ("promptTokens", "completionTokens", "totalTokens", "cacheHitTokens", "cacheMissTokens", "costYuan")}
        require(sum(i["correct"] for i in items) == overall["correct"], f"{name}: correctness total mismatch")
        require(sum(i["status"] == "SUCCESS" for i in items) == overall["sqlValid"], f"{name}: SQL success total mismatch")
        require(abs(totals["costYuan"] - overall["totalCostYuan"]) <= .00000051, f"{name}: report cost mismatch")
        require(abs(totals["promptTokens"] / 100 - overall["avgPromptTokens"]) <= .0051, f"{name}: report tokens mismatch")
        for grouping in ("byLayer", "byTableCount"):
            require(sum(g["total"] for g in report[grouping].values()) == 100
                    and sum(g["correct"] for g in report[grouping].values()) == overall["correct"],
                    f"{name}: {grouping} counts mismatch")
        weak_ids = {"T2-H016", "T4-H012", "T1-H017", "T1-H019"} if name == "holdout" else set()
        weak = [i for i in items if i["id"] in weak_ids]
        other = [i for i in items if i["id"] not in weak_ids]
        wall = sorted(i["wallLatencyMs"] for i in items)
        summaries[name] = {"total": 100, "correct": overall["correct"], "sqlValid": overall["sqlValid"],
                           "wallP50Ms": wall[49], "wallP95Ms": wall[94], "calls": len(calls),
                           "wallAvgMs": sum(wall) / 100, "wallMaxMs": wall[-1],
                           "maxRequestCostYuan": max((i["totalUsage"]["costYuan"] for i in items if i["totalUsage"]), default=0),
                           "generationTotalMs": sum((i["timings"] or {}).get("generationMs", 0) for i in items),
                           "wallTotalMs": sum(wall),
                           "unknownUsage": unknown, **totals, "clarifications": clarifications,
                           "retries": retries, "recovered": recovered, "regressed": regressed,
                           "weakGold": {"total": len(weak), "correct": sum(i["correct"] for i in weak), "ids": sorted(weak_ids)},
                           "otherGold": {"total": len(other), "correct": sum(i["correct"] for i in other)},
                           "byLayer": report["byLayer"], "byTableCount": report["byTableCount"],
                           "retrieval": report["retrieval"], "failures": [i["id"] for i in items if not i["correct"]],
                           "slowest": sorted(({'id':i['id'],'wallLatencyMs':i['wallLatencyMs'],'timings':i['timings']} for i in items),
                                             key=lambda i: i['wallLatencyMs'],reverse=True)[:10]}
    return {"format": "heldout-baseline-summary-v1", "passedEvidenceAudit": not errors, "errors": errors,
            "codeCommit": manifest["codeCommit"], "suiteManifestSha256": seal_hash,
            "independentBlindTestPassed": False, "groups": summaries, "productionConfig": common_config,
            "limits": ["One independent run per set; no stable-mean or mechanism-causality claim.",
                       "AI-authored and self-reviewed; exact-overlap checks do not prove semantic independence.",
                       "Weak-Gold subgroup is secondary; all 100 remain in the main denominator.",
                       "Prices are configured estimates; application calls are not supplier HTTP or billing counts."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory")
    parser.add_argument("--suite-manifest", default="data/holdout/v1/manifest.json")
    args = parser.parse_args()
    result = summarize(args.directory, args.suite_manifest)
    (Path(args.directory) / "summary.json").write_bytes((json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode())
    print(json.dumps({"passedEvidenceAudit": result["passedEvidenceAudit"], "errors": result["errors"],
                      "groups": {name: {key: group[key] for key in ("correct", "wallP95Ms", "calls", "costYuan")}
                                 for name, group in result.get("groups", {}).items()}}, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["passedEvidenceAudit"] else 1)
