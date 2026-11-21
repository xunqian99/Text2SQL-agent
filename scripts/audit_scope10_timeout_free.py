"""Recheck the frozen scope10 timeout evidence without starting the application.

This audit is deliberately read-only: it consumes archived JSON evidence, never
loads a key, opens a socket, or mutates the paid batch.  It is a diagnostic
artifact, not a model-accuracy or replay authorization tool.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


EXPECTED_TIMEOUT_IDS = {
    "T2-012",
    "T2-036",
    "T4-036",
    "T4-041",
    "T5-019",
    "T6-014",
    "T6-016",
    "T6-021",
    "T6-023",
    "T6-025",
}


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def audit(batch: Path) -> dict:
    state_path = batch / "state.json"
    diagnosis_path = batch / "DEV_F" / "timeout-diagnosis.json"
    input_path = batch / "DEV_F" / "input-size-diagnosis.json"
    proxy_path = batch / "DEV_F" / "proxy-answer-decisions.json"
    responses_path = batch / "DEV_F" / "responses.json"
    for path in (state_path, diagnosis_path, input_path, proxy_path, responses_path):
        require(path.is_file() and not path.is_symlink(), f"missing evidence: {path}")

    state = read(state_path)
    diagnosis = read(diagnosis_path)
    input_sizes = read(input_path)
    proxy = read(proxy_path)
    responses = read(responses_path)["items"]
    response_by_id = {item["id"]: item for item in responses}
    decision_by_id = {item["questionId"]: item for item in proxy}

    require(state["terminal"] == "DEVELOPMENT_FAILED_NO_C2", "batch is not the frozen stop state")
    require(state["requestsStarted"] == 100, "the frozen batch denominator changed")
    require(len(proxy) == 100 and len(response_by_id) == 100, "DEV_F evidence is incomplete")

    diagnosed_ids = {item["id"] for item in diagnosis["lateQuestions"]}
    observed_ids = {item["questionId"] for item in proxy if item["answerDeadlineExceeded"]}
    require(diagnosed_ids == EXPECTED_TIMEOUT_IDS, "timeout diagnosis IDs drifted")
    require(observed_ids == EXPECTED_TIMEOUT_IDS, "proxy timeout IDs drifted")
    require(diagnosis["runtimeTimeoutCount"] == 0, "runtime timeout count changed")
    require(diagnosis["proxyDeadlineCount"] == 10, "proxy deadline count changed")

    for question_id in EXPECTED_TIMEOUT_IDS:
        decision = decision_by_id[question_id]
        response = response_by_id[question_id]
        body = response["body"]
        budget = body["budget"]
        require(decision["selectedHttpStatus"] == 504, f"{question_id}: proxy status drifted")
        require(decision["bodyTimely"] is False, f"{question_id}: body was marked timely")
        require(decision["durableSettlementSucceeded"] is False,
                f"{question_id}: timeout decision settlement unexpectedly changed")
        require(decision["durableBeforeAnswer"] is True,
                f"{question_id}: answer was emitted before the durable decision boundary")
        require(response["httpStatus"] == 502 and body["status"] == "GENERATION_FAILED",
                f"{question_id}: Java response classification drifted")
        require(budget["termination"] == "COMPLETE", f"{question_id}: Java termination changed")
        require(0 < budget["elapsedMs"] < 15000, f"{question_id}: Java deadline evidence changed")
        require(budget["stageMs"]["MODEL"] >= 14900, f"{question_id}: model timing evidence changed")

    late_wall = [response_by_id[item]["wallLatencyMs"] for item in EXPECTED_TIMEOUT_IDS]
    round_state = state["rounds"]["DEV_F"]
    summary = round_state["summary"]
    require(summary["timeoutCount"] == 10 and round_state["gate"]["checks"]["timeouts"] is False,
            "development gate evidence drifted")
    require(summary["wallP95Ms"] > 15000, "P95 no longer reflects the failed deadline gate")

    result = {
        "format": "scope10-timeout-free-audit-v1",
        "batch": batch.as_posix(),
        "planSha256": state["planSha256"],
        "apiCalls": 0,
        "realKeyReads": 0,
        "mutatedPaidBatch": False,
        "replayAuthorized": False,
        "thresholdChangeAuthorized": False,
        "terminal": state["terminal"],
        "dev": {
            "questions": state["requestsStarted"],
            "correct": summary["correct"],
            "timeouts": summary["timeoutCount"],
            "wallP95Ms": summary["wallP95Ms"],
            "wallMeanMs": summary["wallMeanMs"],
            "timeoutIds": sorted(EXPECTED_TIMEOUT_IDS),
        },
        "deadlineEvidence": {
            "runtimeTimeouts": diagnosis["runtimeTimeoutCount"],
            "proxyDeadlineTimeouts": diagnosis["proxyDeadlineCount"],
            "javaElapsedMs": {
                "min": min(response_by_id[item]["body"]["budget"]["elapsedMs"] for item in EXPECTED_TIMEOUT_IDS),
                "max": max(response_by_id[item]["body"]["budget"]["elapsedMs"] for item in EXPECTED_TIMEOUT_IDS),
            },
            "javaModelStageMs": {
                "min": min(response_by_id[item]["body"]["budget"]["stageMs"]["MODEL"] for item in EXPECTED_TIMEOUT_IDS),
                "max": max(response_by_id[item]["body"]["budget"]["stageMs"]["MODEL"] for item in EXPECTED_TIMEOUT_IDS),
            },
            "proxyWallMs": {
                "min": min(late_wall),
                "max": max(late_wall),
            },
            "timeoutDecisionSettlementSucceeded": False,
            "timeoutDecisionDurableBeforeAnswer": True,
        },
        "inputSizeCorrelationOnly": {
            "lateMeanPromptChars": input_sizes["late"]["promptChars"]["mean"],
            "normalMeanPromptChars": input_sizes["normal"]["promptChars"]["mean"],
            "lateMeanInputEstimate": input_sizes["late"]["inputEstimate"]["mean"],
            "normalMeanInputEstimate": input_sizes["normal"]["inputEstimate"]["mean"],
            "causalConclusion": False,
        },
        "accounting": {
            "currentKnownCostYuan": summary["accounting"]["knownCostYuan"],
            "currentKnownUsageCalls": summary["accounting"]["knownUsageCalls"],
            "currentUnknownUsageCalls": summary["accounting"]["unknownUsageCalls"],
            "lateKnownUsageCalls": summary["accounting"]["lateKnownUsageCalls"],
            "lateKnownCostYuan": summary["accounting"]["lateKnownCostYuan"],
            "cumulativeKnownCostYuan": summary["accounting"]["cumulativeCommonAccounting"]["knownCostYuan"],
            "cumulativeUnknownHeldYuan": summary["accounting"]["cumulativeCommonAccounting"]["unknownHeldYuan"],
            "unknownFilledWithZero": summary["accounting"]["noUnknownFilledWithZero"] is False,
        },
        "evidenceSha256": {
            path.name: sha256(path)
            for path in (state_path, diagnosis_path, input_path, proxy_path, responses_path)
        },
        "finding": (
            "All ten failures are proxy answer-deadline overruns: Java completed its "
            "15-second budget just before the proxy wall-clock deadline. Input size is "
            "correlated in the archived sample but is not a causal proof. No timing bug "
            "or replay authorization was established."
        ),
    }
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.batch.resolve())
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": True, "out": args.out.as_posix(), "apiCalls": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
