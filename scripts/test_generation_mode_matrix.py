"""Synthetic old on-budget records exercise the new mode matrix without paid calls."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from summarize_generation_mode_matrix import decision, summarize, ORDER


class MatrixDecisionTest(unittest.TestCase):
    def setUp(self):
        self.base = dict(correct=92, wallP95Ms=10000, costYuan=.2, unknownUsage=[],
                         boundedReturnViolations=[], cancellationRejected=0, truncatedRequests=[])
        self.default = [deepcopy(self.base) for _ in range(3)]
        self.none = [{**deepcopy(self.base), 'correct': 90, 'wallP95Ms': 7999} for _ in range(3)]

    def test_exact_accuracy_floor_and_strict_latency_threshold(self):
        self.assertTrue(decision(self.default, self.none)['stageAccepted'])
        self.none[0]['wallP95Ms'] = 8002
        self.assertFalse(decision(self.default, self.none)['stageAccepted'])

    def test_cannot_ratchet_baseline_down(self):
        for r in self.default: r['correct'] = 85
        for r in self.none: r['correct'] = 89
        result = decision(self.default, self.none)
        self.assertTrue(result['gates']['accuracyLossAtMost2ppContemporaneous'])
        self.assertFalse(result['gates']['accuracyLossAtMost2ppFrozen92'])
        self.assertFalse(result['stageAccepted'])

    def test_contemporaneous_loss_is_also_required(self):
        for r in self.default: r['correct'] = 93
        self.assertFalse(decision(self.default, self.none)['stageAccepted'])

    def test_one_run_cost_cannot_hide_in_average(self):
        self.none[0]['costYuan'] = .301
        self.none[1]['costYuan'] = .1
        self.assertFalse(decision(self.default, self.none)['stageAccepted'])

    def test_unknown_cost_stays_unconfirmed(self):
        self.none[0]['unknownUsage'] = ['q1:1']
        result = decision(self.default, self.none)
        self.assertIsNone(result['gates']['eachRunCostAtMost030'])
        self.assertFalse(result['stageAccepted'])

    def test_resource_and_truncation_failures_reject(self):
        for field, value in [('boundedReturnViolations', ['q1']), ('cancellationRejected', 1),
                             ('truncatedRequests', ['q1', 'q2'])]:
            none = deepcopy(self.none)
            none[0][field] = value
            self.assertFalse(decision(self.default, none)['stageAccepted'])


class MatrixEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = Path('reports/request-budget-matrix-20261116')
        self.manifest = self.read(self.source / 'manifest.json')
        old_runs = {r['name']: r for r in self.manifest['runs']}
        self.manifest.update(format='generation-mode-matrix-v1', order=ORDER, runs=[],
                             distinctRealQuestions=100, plannedRealRequests=600, referenceAccuracyPercent=92)
        for name in ORDER:
            old_name = 'gold' if name == 'gold' else name[:2] + '-on'
            run = deepcopy(old_runs[old_name])
            shutil.copytree(self.source / old_name, self.root / name, ignore=shutil.ignore_patterns('*.log'))
            mode = 'none' if name.endswith('-none') else 'provider-default'
            run.update(name=name, configuredReasoningEffort=mode)
            run.pop('enabled')
            path = self.root / name / run['report']
            report = self.read(path)
            report['meta']['effectiveConfig']['configuredReasoningEffort'] = mode
            self.write(path, report)
            run['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            self.manifest['runs'].append(run)
            for ledger in (self.root / name / 'llm-calls').glob('*.jsonl'):
                rows = [json.loads(s) for s in ledger.read_text(encoding='utf-8-sig').splitlines() if s.strip()]
                ledger.write_text(''.join(json.dumps({**r, 'configuredReasoningEffort':mode})+'\n' for r in rows), encoding='utf-8')
        self.write(self.root / 'manifest.json', self.manifest)

    @staticmethod
    def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))

    @staticmethod
    def write(path, value): path.write_text(json.dumps(value), encoding='utf-8')

    def result(self): return summarize(self.root, verify_workspace=False)

    def change(self, mutation):
        run = self.manifest['runs'][-1]
        path = self.root / 'r3-none' / run['report']
        report = self.read(path)
        mutation(report)
        self.write(path, report)
        run['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write(self.root / 'manifest.json', self.manifest)

    def test_honest_failed_matrix_is_auditable(self):
        result = self.result()
        self.assertTrue(result['passedEvidenceAudit'], result['errors'])
        self.assertFalse(result['stageAccepted'])

    def test_does_not_accept_an_extra_factor(self):
        self.change(lambda r:r['meta']['effectiveConfig'].update(maxTokens=2048))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_does_not_accept_wrong_configured_mode(self):
        self.change(lambda r:r['meta']['effectiveConfig'].update(configuredReasoningEffort='provider-default'))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_does_not_accept_prompt_drift(self):
        def mutate(r):
            item=next(i for i in r['itemEvidence'] if i['calls'])
            item['calls'][0]['promptSha256']='0'*64
        self.change(mutate)
        result = self.result()
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('first prompt differs', '\n'.join(result['errors']))

    def test_does_not_accept_a_lowered_frozen_reference(self):
        self.manifest['referenceAccuracyPercent'] = 90
        self.write(self.root/'manifest.json',self.manifest)
        self.assertFalse(self.result()['passedEvidenceAudit'])


if __name__ == '__main__': unittest.main()
