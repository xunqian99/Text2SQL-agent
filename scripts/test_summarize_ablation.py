"""Exercise evidence-audit rejection paths with synthetic, non-model records."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from summarize_ablation import summarize


class EvidenceAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        manifest = {"state": "COMPLETE", "rounds": 3, "gitCommit": "test", "runs": []}
        usage = dict(promptTokens=10, completionTokens=20, totalTokens=30, cacheHitTokens=0,
                     cacheMissTokens=10, costYuan=.001)
        for round_number in range(1, 4):
            for group in ("baseline", "correction", "checker", "no-clarification"):
                directory = self.root / f"{round_number}-{group}"
                (directory / "llm-calls").mkdir(parents=True)
                meta = dict(gitCommit="test", dryRun=False, split="eval", itemCount=100,
                            productionSelfCorrectionEnabled=group in ("correction", "checker"),
                            productionSelfCorrectionMaxAttempts=2 if group in ("correction", "checker") else 1,
                            selfCorrectionEnabled=False,
                            effectiveConfig=dict(clarificationEnabled=group != "no-clarification",
                                                 resultCheckerEnabled=group == "checker"))
                items, ledger = [], []
                for index in range(100):
                    digest = hashlib.sha256(str(index).encode()).hexdigest()
                    call = dict(number=1, status="OK", promptSha256=digest, usage=usage.copy())
                    attempt = dict(number=1, stage="INITIAL_SQL", status="SUCCESS", sql="SELECT 1",
                                   resultSha256=digest, correct=True)
                    items.append(dict(id=f"T-{index:03}", questionSha256=digest, goldSqlSha256=digest,
                                      goldResultSha256=digest, status="SUCCESS", finalSql="SELECT 1", correct=True,
                                      totalUsage=usage.copy(), calls=[call], productionAttempts=[attempt],
                                      selectedAttempt=1, retryTrigger="NONE", wallLatencyMs=100, timings=None))
                    ledger.append(dict(promptSha256=digest, **usage))
                report = dict(meta=meta, overall=dict(total=100, correct=100, totalCostYuan=.1,
                                                     avgPromptTokens=10, p50LatencyMs=100, p95LatencyMs=100,
                                                     avgLatencyMs=100), itemEvidence=items, byLayer={}, byTableCount={})
                (directory / "report.json").write_text(json.dumps(report), encoding="utf-8")
                (directory / "llm-calls" / "calls.jsonl").write_text(
                    "\n".join(json.dumps(entry) for entry in ledger), encoding="utf-8")
                manifest["runs"].append(dict(round=round_number, group=group, state="COMPLETE",
                                             directory=str(directory), report="report.json"))
        (self.root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def change(self, mutate):
        path = self.root / "1-correction" / "report.json"
        report = json.loads(path.read_text(encoding="utf-8"))
        mutate(report)
        path.write_text(json.dumps(report), encoding="utf-8")
        result = summarize(self.root)
        self.assertFalse(result["passedEvidenceAudit"])
        return "\n".join(result["errors"])

    def test_accepts_complete_consistent_records(self):
        self.assertTrue(summarize(self.root)["passedEvidenceAudit"])

    def test_rejects_prompt_drift(self):
        errors = self.change(lambda r: r["itemEvidence"][0]["calls"][0].update(promptSha256="a" * 64))
        self.assertIn("first-prompt drift", errors)

    def test_rejects_misreported_selected_sql(self):
        errors = self.change(lambda r: r["itemEvidence"][0]["productionAttempts"][0].update(sql="SELECT 2"))
        self.assertIn("selected answer mismatch", errors)

    def test_rejects_missing_request_cost(self):
        errors = self.change(lambda r: r["itemEvidence"][0]["totalUsage"].update(costYuan=0))
        self.assertIn("costYuan ledger mismatch", errors)

    def test_rejects_changed_database_reference_result(self):
        errors = self.change(lambda r: r["itemEvidence"][0].update(goldResultSha256="b" * 64))
        self.assertIn("benchmark/database result fingerprint changed", errors)


if __name__ == "__main__":
    unittest.main()
