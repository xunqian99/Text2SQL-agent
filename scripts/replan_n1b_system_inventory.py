"""Offline inventory for the proposed N1b system-rule candidate.

This script only reads the frozen B0 input inventory.  It does not build a
model client, read credentials, start a service, or change the production
prompt.  It records the amount of system text that a future candidate would
have to remove before a paid DEV100 could even be considered.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


RULE_RE = re.compile(r"^\s*(\d+)\.\s")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def extract_rules(system_text: str) -> list[dict[str, object]]:
    lines = system_text.splitlines()
    start = next((i for i, line in enumerate(lines) if line.strip() == "硬性规则："), None)
    if start is None:
        raise ValueError("frozen system prompt has no 硬性规则 marker")
    rules: list[dict[str, object]] = []
    current: dict[str, object] | None = None
    for index, line in enumerate(lines[start + 1 :], start=start + 1):
        match = RULE_RE.match(line)
        if match:
            if current is not None:
                rules.append(current)
            current = {
                "number": int(match.group(1)),
                "startLine": index + 1,
                "lines": [line],
            }
        elif current is not None:
            current["lines"].append(line)
    if current is not None:
        rules.append(current)
    for rule in rules:
        rule["endLine"] = rule["startLine"] + len(rule["lines"]) - 1
        rule["chars"] = len("\n".join(rule["lines"]))
    return rules


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--baseline",
        type=Path,
        default=Path("reports/replan-stage-a-20261119/input-inventory/capture-original-200.json"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(
            "reports/replan-stage-d-20261119/n1b-system-inventory-20261121/summary.json"
        ),
    )
    args = parser.parse_args()

    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    items = baseline["items"]
    if len(items) != 200:
        raise SystemExit(f"expected frozen B0 200 items, got {len(items)}")
    system = baseline["systemPrompt"]["text"]
    system_chars = len(system)
    total_chars = sum(int(item["promptChars"]) for item in items)
    required_total_reduction = total_chars * 0.20
    required_system_reduction = required_total_reduction / len(items)
    target_system_chars = system_chars - required_system_reduction
    rules = extract_rules(system)
    reasoning_start = system.find("思考与推理步骤")
    hard_rules_start = system.find("硬性规则")
    result = {
        "format": "replan-n1b-system-inventory-v1",
        "status": "OFFLINE_INVENTORY_ONLY",
        "modelCalls": 0,
        "providerCalls": 0,
        "realKeyReads": 0,
        "baseline": {
            "path": str(args.baseline).replace("\\", "/"),
            "sha256": sha256(args.baseline),
            "promptVersion": baseline.get("promptVersion"),
            "items": len(items),
            "totalPromptChars": total_chars,
            "systemCharsPerItem": system_chars,
            "systemLines": len(system.splitlines()),
            "systemPromptSha256": baseline["systemPrompt"]["sha256"],
            "reasoningChars": len(system[reasoning_start:hard_rules_start])
            if reasoning_start >= 0 and hard_rules_start > reasoning_start
            else None,
        },
        "requiredGate": {
            "totalReductionPercent": 20.0,
            "requiredTotalCharsSaved": required_total_reduction,
            "requiredSystemCharsSavedPerItem": required_system_reduction,
            "maxSystemCharsPerItem": target_system_chars,
            "p95MustNotIncrease": True,
        },
        "ruleInventory": {
            "count": len(rules),
            "numbers": [rule["number"] for rule in rules],
            "rules": rules,
            "mappingRequired": "every candidate rule must map to these original line ranges and the 51-obligation review",
        },
        "frozen": [
            "schema and join hints",
            "data profile",
            "metrics, value hints and examples",
            "question text and dataset",
            "retrieval provenance and ranking",
            "model, reasoning, output, timeout, retry and scoring settings",
        ],
        "notAnAdmission": [
            "This inventory does not prove semantic equivalence.",
            "The existing p22 system text is excluded because it belongs to the failed C1 run.",
            "No paid request is authorized by this file.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "status": result["status"],
        "ruleCount": len(rules),
        "systemCharsPerItem": system_chars,
        "maxSystemCharsPerItem": round(target_system_chars, 3),
        "modelCalls": 0,
        "output": str(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
