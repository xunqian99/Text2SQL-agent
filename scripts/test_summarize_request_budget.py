"""Exercise stop/late accounting and reject corrupted evidence without paid calls."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from summarize_request_budget import audit_run, summarize, ORDER
from summarize_holdout import dataset_hash, ids_hash, is_dev, read


class RequestBudgetAuditTest(unittest.TestCase):
    def setUp(self):
        self.usage = dict(model='fixture', promptTokens=10, completionTokens=5, totalTokens=15,
                          cacheHitTokens=0, cacheMissTokens=10, costYuan=.00003)
        self.items, self.ledger, self.errors = [], [], []
        for n in range(100):
            call = dict(number=1, status='OK', promptSha256='a' * 64, usage=deepcopy(self.usage))
            attempt = dict(number=1, stage='INITIAL_SQL', sql='SELECT 1', status='SUCCESS', correct=True)
            self.items.append(dict(id=f'q{n}', correct=True, status='SUCCESS', finalSql='SELECT 1',
                                   selectedAttempt=1, wallLatencyMs=50, timings=dict(totalMs=50),
                                   totalUsage=deepcopy(self.usage), calls=[call], productionAttempts=[attempt],
                                   retryTrigger='NONE', budget=dict(requestId=f'req{n}', timeoutMs=15000,
                                       maxModelCalls=2, modelCalls=1, unknownUsageCalls=0,
                                       elapsedMs=50, termination='COMPLETE', stage='EXECUTION', cancellationRejected=0)))
            self.ledger.append(dict(requestId=f'req{n}', callNumber=1, usageKnown=True, status='OK',
                                    promptSha256='a' * 64, **self.usage))
        self.report = dict(itemEvidence=self.items, overall=dict(total=100, correct=100, sqlValid=100,
                            totalCostYuan=.003, avgPromptTokens=10),
                           meta=dict(effectiveConfig=dict(inputPricePer1k=.001, cacheHitPricePer1k=.00002, outputPricePer1k=.004)),
                           byLayer={'T1': dict(total=100, correct=100)}, byTableCount={'1': dict(total=100, correct=100)})

    def run_audit(self):
        return audit_run(self.report, self.ledger, True,
                         lambda condition, message: None if condition else self.errors.append(message))

    def stop(self):
        item = self.items[0]
        item['calls'].append(dict(number=2, status='IN_FLIGHT', promptSha256='b' * 64, usage=None))
        item['productionAttempts'].append(dict(number=2, stage='REQUEST_STOP', status='REQUEST_TIMEOUT', sql='SELECT 1', correct=False))
        item.update(wallLatencyMs=15001, timings=dict(totalMs=15000), retryTrigger='SUSPICIOUS_RESULT')
        item['budget'].update(modelCalls=2, unknownUsageCalls=1, elapsedMs=15000, termination='DEADLINE_EXCEEDED')

    def test_accepts_known_complete_accounting(self):
        summary = self.run_audit()
        self.assertEqual([], self.errors)
        self.assertEqual(100, summary['calls'])

    def test_request_stop_is_not_an_extra_call_and_unknown_is_not_free(self):
        self.stop()
        summary = self.run_audit()
        self.assertEqual([], self.errors)
        self.assertEqual(101, summary['calls'])
        self.assertEqual(['q0:2'], summary['unknownUsage'])
        self.assertEqual([{'id': 'q0', 'correct': True}], summary['successfulFallbacks'])

    def test_late_cost_is_reconciled_without_counting_it_in_original_response(self):
        self.stop()
        self.ledger.append(dict(requestId='req0', callNumber=2, usageKnown=True, status='LATE_OK',
                                promptSha256='b' * 64, **self.usage))
        summary = self.run_audit()
        self.assertEqual([], self.errors)
        self.assertAlmostEqual(.003, summary['costYuan'])
        self.assertAlmostEqual(.00003, summary['lateKnownCostYuan'])
        self.assertEqual(['q0:2'], summary['unknownUsage'])

    def test_rejects_cost_erasure(self):
        self.items[0]['totalUsage']['costYuan'] = 0
        self.run_audit()
        self.assertIn('q0: request costYuan ledger mismatch', self.errors)

    def test_rejects_selected_answer_replacement(self):
        self.items[0]['finalSql'] = 'SELECT 42'
        self.run_audit()
        self.assertIn('q0: selected sql differs', self.errors)

    def test_rejects_duplicate_raw_identity(self):
        self.ledger.append(deepcopy(self.ledger[0]))
        self.run_audit()
        self.assertIn('duplicate raw request/call identity', self.errors)

    def test_rejects_checkpoint_timing_without_correction_wait(self):
        self.stop()
        self.items[0]['timings']['totalMs'] = 50
        self.run_audit()
        self.assertIn('q0: total timing excludes budget wait', self.errors)

    def test_reports_deadline_and_cleanup_gate_failures(self):
        self.stop()
        self.items[0]['wallLatencyMs'] = 17000
        self.items[0]['budget']['cancellationRejected'] = 1
        summary = self.run_audit()
        self.assertEqual(['q0'], summary['boundedReturnViolations'])
        self.assertEqual(1, summary['cancellationRejected'])


class RequestBudgetMatrixAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        frozen = self.root / 'frozen.txt'
        frozen.write_text('frozen', encoding='utf-8')
        gold = read(next(Path('reports/holdout-baseline-20261116/reference-gold').glob('eval-*.json')))
        gold['meta'].update(gitCommit='fixture', datasetSha256=dataset_hash('data/eval'))
        gold_items = {i['id']: i for i in gold['itemEvidence']}
        identities = sorted(i for i in gold_items if not is_dev(i))
        self.manifest = dict(state='COMPLETE', codeCommit='fixture', order=ORDER, runs=[],
                             protectedSha256={str(frozen): hashlib.sha256(frozen.read_bytes()).hexdigest()})
        for name in ORDER:
            directory = self.root / name
            directory.mkdir()
            if name == 'gold':
                report = deepcopy(gold)
            else:
                fixture = RequestBudgetAuditTest()
                fixture.setUp()
                report = fixture.report
                enabled = name.endswith('-on')
                for item, ident in zip(report['itemEvidence'], identities):
                    item.update(id=ident, **{k: gold_items[ident][k] for k in ('questionSha256', 'goldSqlSha256', 'goldResultSha256')})
                    if not enabled:
                        item['budget'] = None
                report['meta'] = deepcopy(gold['meta'])
                report['meta'].update(dryRun=False, split='eval', itemCount=100, sampleIdsSha256=ids_hash(identities),
                                      limit=0, layers=[], productionSelfCorrectionEnabled=True,
                                      productionSelfCorrectionMaxAttempts=2, selfCorrectionEnabled=False,
                                      model='deepseek-flash', retrievalEnabled=True, retrievalTopK=8, joinHintsEnabled=True)
                config = dict(requestBudgetEnabled=enabled, requestTimeoutMs=15000, requestMaxModelCalls=2,
                              requestMinRetryRemainingMs=2000, sdkMaxAttempts=1, maxTokens=8192, temperature=0,
                              fewshotMaxExamples=2, inputPricePer1k=.001, cacheHitPricePer1k=.00002, outputPricePer1k=.004)
                config.update({k: True for k in ('clarificationEnabled', 'resultCheckerEnabled', 'columnPruningEnabled',
                                                 'joinPlanEnabled', 'semanticEnabled', 'fewshotEnabled')})
                config.update({k: False for k in ('cacheEnabled', 'routingEnabled', 'toolUseEnabled', 'conversationEnabled')})
                report['meta']['effectiveConfig'] = config
                ledger_dir = directory / 'llm-calls'
                ledger_dir.mkdir()
                (ledger_dir / 'calls.jsonl').write_text('\n'.join(json.dumps(r) for r in fixture.ledger), encoding='utf-8')
            path = directory / 'eval-fixture.json'
            path.write_text(json.dumps(report), encoding='utf-8')
            self.manifest['runs'].append(dict(name=name, state='COMPLETE', report=path.name,
                                              reportSha256=hashlib.sha256(path.read_bytes()).hexdigest(), enabled=name.endswith('-on'),
                                              total=report['overall']['total'], correct=report['overall']['correct']))
        self.save_manifest()

    def save_manifest(self):
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest), encoding='utf-8')

    def test_accepts_complete_synthetic_matrix(self):
        result = summarize(self.root)
        self.assertTrue(result['passedEvidenceAudit'], result['errors'])
        self.assertTrue(result['stageAccepted'])

    def test_rejects_source_byte_mutation(self):
        (self.root / 'frozen.txt').write_text('changed', encoding='utf-8')
        result = summarize(self.root)
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('protected file bytes changed', '\n'.join(result['errors']))

    def test_evidence_only_explicitly_does_not_verify_current_private_workspace(self):
        (self.root / 'frozen.txt').unlink()
        result = summarize(self.root, verify_workspace=False)
        self.assertTrue(result['passedEvidenceAudit'], result['errors'])
        self.assertFalse(result['verifiedCurrentWorkspaceBytes'])

    def test_rejects_modified_report_bytes(self):
        path = self.root / 'r1-on/eval-fixture.json'
        path.write_text(path.read_text(encoding='utf-8') + '\n', encoding='utf-8')
        self.assertIn('r1-on: report bytes changed', summarize(self.root)['errors'])

    def test_rejects_consistent_but_different_first_prompt(self):
        path = self.root / 'r1-on/eval-fixture.json'
        report = read(path)
        report['itemEvidence'][0]['calls'][0]['promptSha256'] = 'c' * 64
        path.write_text(json.dumps(report), encoding='utf-8')
        next(r for r in self.manifest['runs'] if r['name'] == 'r1-on')['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        ledger_path = self.root / 'r1-on/llm-calls/calls.jsonl'
        rows = [json.loads(line) for line in ledger_path.read_text(encoding='utf-8').splitlines()]
        rows[0]['promptSha256'] = 'c' * 64
        ledger_path.write_text('\n'.join(json.dumps(r) for r in rows), encoding='utf-8')
        self.save_manifest()
        self.assertIn('first prompt differs', '\n'.join(summarize(self.root)['errors']))


if __name__ == '__main__':
    unittest.main()
