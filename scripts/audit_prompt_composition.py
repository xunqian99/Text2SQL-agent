"""Audit archived prompt composition against actual first-call evidence; no model calls."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def audit(root, report_dir, evidence_only=False):
    summary = read(report_dir / "summary.json")
    require(summary["format"] == "prompt-composition-summary-v1", "Unknown summary format")
    require(summary["unit"] == "java-utf16-code-units", "Unexpected length unit")
    require(summary["modelCalls"] == summary["modelCostYuan"] == 0, "Unexpected model usage")
    source = root / "src/test/java/com/text2sql/agent/retrieval/PromptDeterminismProbe.java"
    if not evidence_only:
        require(digest(source) == summary["probeSourceSha256"], "Probe source changed")
    checked = {}
    ids_by_split = []
    for split in ("dev", "eval"):
        report = read(report_dir / f"{split}.json")
        claim = summary["splits"][split]
        reference_path = root / claim["referenceReport"]
        require(digest(reference_path) == claim["referenceSha256"], f"{split}: reference changed")
        reference = read(reference_path)
        require(report["split"] == reference["meta"]["split"] == split, "Split mismatch")
        require(report["componentMeasurementsVersion"] == "prompt-components-v1", "Unknown components")
        require(report["componentLengthUnit"] == summary["unit"], "Unit mismatch")
        for key in ("datasetSha256", "sampleIdsSha256"):
            require(report[key] == reference["meta"][key], f"{split}: {key} mismatch")
        items = {item["id"]: item for item in report["items"]}
        real = {item["id"]: item for item in reference["itemEvidence"]}
        require(len(items) == len(report["items"]) == report["itemCount"] == claim["items"] == 100,
                f"{split}: duplicate or missing probe items")
        require(len(real) == len(reference["itemEvidence"]) == 100 and items.keys() == real.keys(),
                f"{split}: real ID set mismatch")
        ids_by_split.append(set(items))
        totals = {key: 0 for key in claim["componentTotalChars"]}
        matched, missing = 0, []
        for item_id, item in items.items():
            components = item["componentChars"]
            require(components.keys() == totals.keys(), f"{item_id}: component set changed")
            require(all(isinstance(n, int) and n >= 0 for n in components.values()), f"{item_id}: bad lengths")
            require(sum(components.values()) == item["promptChars"], f"{item_id}: lengths do not sum")
            require(item["systemReasoningDirectiveChars"] == claim["directiveCharsPerPrompt"] == 259,
                    f"{item_id}: directive length changed")
            require(components["system"] == 2795, f"{item_id}: system length changed")
            for key, value in components.items():
                totals[key] += value
            calls = real[item_id]["calls"]
            if not calls:
                require(real[item_id]["status"] == "NEEDS_CLARIFICATION",
                        f"{item_id}: missing prompt is not a clarification")
                missing.append(item_id)
            else:
                first = calls[0]
                require(first["number"] == 1 and first["promptSha256"] == item["promptSha256"],
                        f"{item_id}: real first prompt drift")
                if first.get("usage"):
                    require(first["usage"]["promptChars"] == item["promptChars"], f"{item_id}: real length mismatch")
                matched += 1
        require(totals == claim["componentTotalChars"], f"{split}: component totals differ")
        total = sum(totals.values())
        for key, value in totals.items():
            require(math.isclose(100 * value / total, claim["componentPercent"][key]), f"{split}: percent differs")
        lengths = [item["promptChars"] for item in items.values()]
        require(statistics.mean(lengths) == claim["meanPromptChars"] and
                statistics.median(lengths) == claim["medianPromptChars"] and
                max(lengths) == claim["maxPromptChars"], f"{split}: length stats differ")
        require(math.isclose(100 * 259 * len(items) / total, claim["directivePercentOfTotal"]), "Directive share differs")
        require(matched == claim["matchedRealFirstPrompts"] and sorted(missing) == sorted(claim["noRealFirstPrompt"]),
                f"{split}: real prompt coverage differs")
        require(not claim["realPromptDrift"] and not claim["mismatches"] and not report["sameProcessMismatches"], "Drift recorded")
        require(report["sameProcessComparisons"] == claim["comparisons"] == 300, "Comparison count differs")
        require(report["modelCallCount"] == report["estimatedModelCostYuan"] == 0, "Probe used model")
        for key in ("captureAvgMs", "captureP95Ms"):
            require(report[key] == claim[key], f"{split}: {key} differs")
        if "timeoutQuestion" in claim:
            require(items[claim["timeoutQuestion"]["id"]] == claim["timeoutQuestion"], "Timeout example differs")
        checked[split] = {"items": len(items), "componentsSumChecked": len(items),
                          "realFirstPromptsMatched": matched, "clarificationsWithoutPrompt": sorted(missing),
                          "sameProcessComparisons": report["sameProcessComparisons"]}
    require(not (ids_by_split[0] & ids_by_split[1]), "Dev/eval overlap")
    require(summary["passed"] is True, "Summary not passed")
    manifest_path = report_dir / "artifacts.sha256.json"
    if manifest_path.exists():
        for name, sha in read(manifest_path).items():
            require(digest(report_dir / name) == sha, f"Archive changed: {name}")
    return {"passed": True, "sourceChecked": not evidence_only, "splits": checked,
            "uniqueItems": len(ids_by_split[0] | ids_by_split[1]), "modelCalls": 0}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", default="reports/prompt-composition-20261116")
    parser.add_argument("--evidence-only", action="store_true", help="Skip current probe source check for historical archives")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    print(json.dumps(audit(repo, repo / args.report_dir, args.evidence_only), ensure_ascii=False, indent=2))
