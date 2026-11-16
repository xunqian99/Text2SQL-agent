"""Synthetic low fixtures test protocol identity; they are not model measurements."""
import hashlib
import json
from pathlib import Path
import unittest
import test_generation_mode_evidence as fixtures
from summarize_generation_mode import summarize


class LowReasoningEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.base = fixtures.GenerationModeEvidenceTest('test_accepts_consistent_protocol_without_accepting_stage')
        self.base.setUp()
        self.addCleanup(self.base.doCleanups)
        self.root = self.base.root
        self.manifest = self.base.read(self.root / 'manifest.json')
        self.manifest.update(format='low-reasoning-screen-v1', candidateReasoningEffort='low',
                             order=['gold', 'provider-default', 'reasoning-low'])
        (self.root / 'reasoning-none').rename(self.root / 'reasoning-low')
        self.run = self.manifest['runs'][-1]
        self.run.update(name='reasoning-low', configuredReasoningEffort='low')
        self.change_candidate(lambda r:r['meta']['effectiveConfig'].update(configuredReasoningEffort='low'))
        for ledger in (self.root / 'reasoning-low' / 'llm-calls').glob('*.jsonl'):
            rows = [json.loads(line) for line in ledger.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
            ledger.write_text(''.join(json.dumps({**r, 'configuredReasoningEffort':'low'})+'\n' for r in rows), encoding='utf-8')

    def save_manifest(self):
        self.base.write(self.root / 'manifest.json', self.manifest)

    def change_candidate(self, mutate):
        path = self.root / 'reasoning-low' / self.run['report']
        report = self.base.read(path)
        mutate(report)
        self.base.write(path, report)
        self.run['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.save_manifest()

    def result(self):
        return summarize(self.root, verify_workspace=False)

    def test_audits_low_without_claiming_stage_acceptance(self):
        result = self.result()
        self.assertTrue(result['passedEvidenceAudit'], result['errors'])
        self.assertEqual('low', result['candidateReasoningEffort'])
        self.assertFalse(result['stageAccepted'])

    def test_none_result_cannot_be_relabelled_low(self):
        self.change_candidate(lambda r:r['meta']['effectiveConfig'].update(configuredReasoningEffort='none'))
        result = self.result()
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('configured mode changed', '\n'.join(result['errors']))

    def test_legacy_protocol_cannot_silently_change_its_candidate(self):
        self.manifest['format'] = 'generation-mode-screen-v1'
        self.save_manifest()
        result = self.result()
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('protocol/candidate mode differs', '\n'.join(result['errors']))

    def test_low_protocol_requires_explicit_candidate_provenance(self):
        self.manifest.pop('candidateReasoningEffort')
        self.save_manifest()
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_historical_none_archive_remains_auditable(self):
        result = summarize(Path('reports/generation-mode-screen-20261116'), verify_workspace=False)
        self.assertTrue(result['passedEvidenceAudit'], result['errors'])
        self.assertEqual('none', result['candidateReasoningEffort'])


if __name__ == '__main__':
    unittest.main()
