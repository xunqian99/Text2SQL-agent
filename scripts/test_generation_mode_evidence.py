"""Synthetic protocol fixtures made from old raw evidence; never represent new model runs."""
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from summarize_generation_mode import summarize


class GenerationModeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'synthetic'
        shutil.copytree('reports/output-budget-screen-20261116', self.root, ignore=shutil.ignore_patterns('*.log'))
        self.manifest = self.read(self.root / 'manifest.json')
        self.manifest['format'] = 'generation-mode-screen-v1'
        names = {'gold': 'gold', 'baseline-8192': 'provider-default', 'candidate-2048': 'reasoning-none'}
        self.manifest['order'] = list(names.values())
        for run in self.manifest['runs']:
            old = run['name']
            name = run['name'] = names[old]
            if name != old:
                (self.root / old).rename(self.root / name)
            mode = 'none' if name == 'reasoning-none' else 'provider-default'
            run.pop('maxTokens')
            run['configuredReasoningEffort'] = mode
            path = self.root / name / run['report']
            report = self.read(path)
            report['meta']['effectiveConfig'].update(maxTokens=8192, configuredReasoningEffort=mode)
            self.write(path, report)
            run['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            for ledger in (self.root / name / 'llm-calls').glob('*.jsonl'):
                rows = [json.loads(line) for line in ledger.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
                ledger.write_text(''.join(json.dumps({**r, 'configuredReasoningEffort': mode}) + '\n' for r in rows), encoding='utf-8')
        self.write(self.root / 'manifest.json', self.manifest)

    @staticmethod
    def read(path):
        return json.loads(path.read_text(encoding='utf-8-sig'))

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value), encoding='utf-8')

    def change_candidate(self, mutation):
        run = self.manifest['runs'][-1]
        path = self.root / 'reasoning-none' / run['report']
        report = self.read(path)
        mutation(report)
        self.write(path, report)
        run['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write(self.root / 'manifest.json', self.manifest)

    def result(self):
        return summarize(self.root, verify_workspace=False)

    def test_accepts_consistent_protocol_without_accepting_stage(self):
        result = self.result()
        self.assertTrue(result['passedEvidenceAudit'], result['errors'])
        self.assertFalse(result['stageAccepted'])
        self.assertFalse(result['candidateForConfirmation'])

    def test_rejects_output_cap_changing_alongside_mode(self):
        self.change_candidate(lambda r: r['meta']['effectiveConfig'].update(maxTokens=2048))
        result = self.result()
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('output cap changed', '\n'.join(result['errors']))

    def test_rejects_mode_only_claim_when_mode_was_omitted(self):
        self.change_candidate(lambda r: r['meta']['effectiveConfig'].update(configuredReasoningEffort='provider-default'))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_rejects_raw_mode_provenance_missing(self):
        ledger = next((self.root / 'reasoning-none' / 'llm-calls').glob('*.jsonl'))
        rows = [json.loads(line) for line in ledger.read_text(encoding='utf-8').splitlines()]
        rows[0].pop('configuredReasoningEffort')
        ledger.write_text(''.join(json.dumps(r) + '\n' for r in rows), encoding='utf-8')
        result = self.result()
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('raw ledger mode differs', '\n'.join(result['errors']))

    def test_rejects_different_first_prompt(self):
        def mutate(report):
            item = next(i for i in report['itemEvidence'] if i['calls'])
            item['calls'][0]['promptSha256'] = '0' * 64
        self.change_candidate(mutate)
        result = self.result()
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('first prompt differs', '\n'.join(result['errors']))

    def test_rejects_question_change_despite_valid_report_checksum(self):
        self.change_candidate(lambda r: r['itemEvidence'][0].update(questionSha256='0' * 64))
        self.assertFalse(self.result()['passedEvidenceAudit'])


if __name__ == '__main__':
    unittest.main()
