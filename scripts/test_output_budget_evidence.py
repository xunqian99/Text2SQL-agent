"""Check the frozen screen and reject config, input, or truncation evidence tampering."""
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from summarize_output_budget import summarize

ARCHIVE = Path('reports/output-budget-screen-20261116')


class OutputBudgetEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'screen'
        shutil.copytree(ARCHIVE, self.root, ignore=shutil.ignore_patterns('*.log'))

    def change_report(self, change):
        manifest_path = self.root / 'manifest.json'
        manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
        entry = next(r for r in manifest['runs'] if r['name'] == 'candidate-2048')
        path = self.root / 'candidate-2048' / entry['report']
        report = json.loads(path.read_text(encoding='utf-8'))
        change(report)
        path.write_text(json.dumps(report), encoding='utf-8')
        entry['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest), encoding='utf-8')

    def test_accepts_honest_failed_screen_without_promoting_candidate(self):
        result = summarize(self.root, verify_workspace=False)
        self.assertTrue(result['passedEvidenceAudit'], result['errors'])
        self.assertFalse(result['candidateForConfirmation'])
        self.assertEqual(6, len(result['groups']['candidate-2048']['truncatedRequests']))

    def test_rejects_more_than_token_budget_changing(self):
        self.change_report(lambda r: r['meta']['effectiveConfig'].update(clarificationEnabled=False))
        result = summarize(self.root, verify_workspace=False)
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('more than maxTokens changed', '\n'.join(result['errors']))

    def test_rejects_input_hash_change_even_with_refreshed_report_checksum(self):
        self.change_report(lambda r: r['itemEvidence'][0].update(questionSha256='0' * 64))
        result = summarize(self.root, verify_workspace=False)
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('questionSha256 changed', '\n'.join(result['errors']))

    def test_rejects_erasing_truncation_call_while_attempt_preserves_it(self):
        def change(report):
            call = next(c for i in report['itemEvidence'] for c in i['calls'] if c['status'] == 'OUTPUT_TRUNCATED')
            call['status'] = 'OK'
        self.change_report(change)
        result = summarize(self.root, verify_workspace=False)
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('truncation call/attempt evidence differs', '\n'.join(result['errors']))


if __name__ == '__main__':
    unittest.main()
