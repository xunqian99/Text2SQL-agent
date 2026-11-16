"""Check archived evidence and deliberately corrupt copies, without model calls."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from summarize_holdout import read, summarize


ARCHIVE = Path(__file__).resolve().parents[1] / "reports/holdout-baseline-20261116"


class HoldoutEvidenceAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "run"
        shutil.copytree(ARCHIVE, self.root)

    def rewrite(self, path, change):
        value = read(path)
        change(value)
        path.write_text(json.dumps(value), encoding="utf-8")

    def change_report(self, change):
        report = next((self.root / "holdout").glob("eval-*.json"))
        self.rewrite(report, change)

    def require_rejection(self, message):
        result = summarize(self.root)
        self.assertFalse(result["passedEvidenceAudit"])
        self.assertIn(message, "\n".join(result["errors"]))

    def test_accepts_complete_archive(self):
        result = summarize(self.root)
        self.assertTrue(result["passedEvidenceAudit"], result["errors"])
        self.assertFalse(result["independentBlindTestPassed"])

    def test_rejects_incomplete_run(self):
        self.rewrite(self.root / "manifest.json", lambda r: r.update(state="RUNNING"))
        self.require_rejection("baseline is not complete")

    def test_rejects_manifest_score_change(self):
        self.rewrite(self.root / "manifest.json", lambda r: r["runs"][-1].update(correct=-1))
        self.require_rejection("run manifest count mismatch")

    def test_rejects_gold_result_change(self):
        self.change_report(lambda r: r["itemEvidence"][0].update(goldResultSha256="0" * 64))
        self.require_rejection("goldResultSha256 changed")

    def test_rejects_selected_answer_change(self):
        self.change_report(lambda r: r["itemEvidence"][0].update(finalSql="SELECT 42"))
        self.require_rejection("selected answer differs")

    def test_rejects_unknown_usage(self):
        self.change_report(lambda r: r["itemEvidence"][0]["calls"][0].update(usage=None))
        self.require_rejection("unknown usage")

    def test_rejects_clarification_counted_as_answer(self):
        path = next((self.root / "development").glob("eval-*.json"))
        self.rewrite(path, lambda r: next(i for i in r["itemEvidence"]
                                         if i["status"] == "NEEDS_CLARIFICATION").update(correct=True))
        self.require_rejection("clarification counted as answer")

    def test_rejects_missing_request_cost(self):
        self.change_report(lambda r: r["itemEvidence"][0]["totalUsage"].update(costYuan=0))
        self.require_rejection("request costYuan accounting mismatch")

    def test_rejects_token_component_change(self):
        self.change_report(lambda r: r["itemEvidence"][0]["calls"][0]["usage"].update(totalTokens=-1))
        self.require_rejection("token components mismatch")

    def test_rejects_layer_denominator_change(self):
        self.change_report(lambda r: next(iter(r["byLayer"].values())).update(total=0))
        self.require_rejection("byLayer counts mismatch")

    def test_rejects_configuration_drift(self):
        self.change_report(lambda r: r["meta"]["effectiveConfig"].update(temperature=.5))
        self.require_rejection("model budget changed")

    def test_rejects_omitted_ledger_call(self):
        path = next((self.root / "holdout" / "llm-calls").glob("*.jsonl"))
        lines = path.read_text(encoding="utf-8-sig").splitlines()
        path.write_text("\n".join(lines[:-1]) + "\n", encoding="utf-8")
        self.require_rejection("raw ledger call count mismatch")


if __name__ == "__main__":
    unittest.main()
