"""Temporary synthetic screen fixtures: never new model measurements or evaluation questions."""
import copy
import hashlib
import json
import unittest

import test_prompt_directive_evidence as directive_fixtures
from test_prompt_directive_evidence import sha
from summarize_available_examples import summarize, audit_pairs, VERSION


class AvailableExamplesEvidenceTest(unittest.TestCase):
    def setUp(self):
        base = self.base = directive_fixtures.PromptDirectiveEvidenceTest('test_consistent_witnesses_pass_but_do_not_accept_stage')
        base.setUp()
        self.addCleanup(base.doCleanups)
        self.root, self.manifest = base.root, base.manifest
        self.manifest.update(format='available-examples-screen-v1', order=['gold', 'overlap', 'available-tables'])
        names = {'gold': 'gold', 'explicit-steps': 'overlap', 'no-explicit-steps': 'available-tables'}
        metric_hash = '1' * 64
        self.pairs = copy.deepcopy(base.pairs)
        self.pairs.update(format='available-examples-pairs-v1', promptVersion=VERSION, systemChars=2795,
                          systemSha256=base.pairs['defaultSystemSha256'], metricDefinitionsSha256=metric_hash,
                          defaultPolicy='overlap-v1', candidatePolicy='available-tables-v1', fullProviderChecks=2,
                          legacyMessagesMatchPreviousWitness=100)
        safe = {'id': 'synthetic-safe', 'questionSha256': sha('safe question'), 'sqlSha256': sha('SELECT * FROM orders'),
                'tables': ['orders'], 'missingTables': []}
        other = {'id': 'synthetic-other', 'questionSha256': sha('other question'), 'sqlSha256': sha('SELECT * FROM products'),
                 'tables': ['products'], 'missingTables': []}
        self.pairs['examplesLibrary'] = [safe, other]
        for w in self.pairs['items']:
            w.update(tables=['orders'], commonContextSha256=w['contextSha256'],
                     defaultUserSha256=sha('default-user:' + w['id']), candidateUserSha256=sha('candidate-user:' + w['id']),
                     defaultExamples=[{**other, 'missingTables': ['products']}], candidateExamples=[safe])
        for run in self.manifest['runs']:
            old, name = run['name'], names[run['name']]
            if old != name:
                (self.root / old).rename(self.root / name)
            strict = name == 'available-tables'
            run.update(name=name, requireAvailableExampleTables=strict)
            run.pop('explicitReasoningEnabled')
            report_path = self.root / name / run['report']
            report = base.base.read(report_path)
            report['meta']['promptVersion'] = VERSION
            report['meta']['effectiveConfig'].update(explicitReasoningEnabled=True, fewshotRequireAvailableTables=strict,
                                                     fewshotSelectionPolicy='available-tables-v1' if strict else 'overlap-v1',
                                                     metricDefinitionsSha256=metric_hash)
            base.base.write(report_path, report)
            run['reportSha256'] = hashlib.sha256(report_path.read_bytes()).hexdigest()
            for ledger in (self.root / name / 'llm-calls').glob('*.jsonl'):
                rows = [json.loads(s) for s in ledger.read_text(encoding='utf-8').splitlines() if s.strip()]
                for row in rows:
                    row.update(configuredPromptVersion=VERSION, configuredMetricDefinitionsSha256=metric_hash,
                               configuredFewshotRequireAvailableTables=strict,
                               configuredFewshotSelectionPolicy='available-tables-v1' if strict else 'overlap-v1')
                ledger.write_text(''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
        self.save_pairs()

    def save_pairs(self):
        p = self.root / 'prompt-pairs.json'
        p.write_text(json.dumps(self.pairs), encoding='utf-8')
        self.manifest['promptPairsSha256'] = hashlib.sha256(p.read_bytes()).hexdigest()
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest), encoding='utf-8')

    def change_candidate(self, mutate):
        run = self.manifest['runs'][-1]
        p = self.root / run['name'] / run['report']
        report = json.loads(p.read_text(encoding='utf-8'))
        mutate(report)
        p.write_text(json.dumps(report), encoding='utf-8')
        run['reportSha256'] = hashlib.sha256(p.read_bytes()).hexdigest()
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest), encoding='utf-8')

    def result(self):
        return summarize(self.root, verify_workspace=False)

    def test_synthetic_consistent_screen_does_not_accept_stage(self):
        r = self.result()
        self.assertTrue(r['passedEvidenceAudit'], r['errors'])
        self.assertFalse(r['stageAccepted'])

    def test_missing_table_example_cannot_be_a_candidate(self):
        self.pairs['items'][0]['candidateExamples'] = self.pairs['items'][0]['defaultExamples']
        self.save_pairs()
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_zero_examples_is_allowed_without_backfilling(self):
        self.pairs['items'][0]['candidateExamples'] = []
        errors = []
        audit_pairs(self.pairs, lambda ok, msg: errors.append(msg) if not ok else None)
        self.assertEqual(errors, [])

    def test_same_id_cannot_hide_changed_example_sql(self):
        self.pairs['items'][0]['candidateExamples'] = [dict(self.pairs['items'][0]['candidateExamples'][0], sqlSha256='0' * 64)]
        self.save_pairs()
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_actual_first_prompt_must_match_candidate_witness(self):
        self.change_candidate(lambda r: next(i for i in r['itemEvidence'] if i['calls'])['calls'][0].update(promptSha256='0' * 64))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_semantic_switch_cannot_change_with_example_policy(self):
        self.change_candidate(lambda r: r['meta']['effectiveConfig'].update(semanticEnabled=False))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_unknown_usage_remains_unknown_in_cost_gate(self):
        r = self.result()
        self.assertTrue(r['groups']['available-tables']['unknownUsage'])
        self.assertIsNone(r['screenGates']['completeCostAtMost030'])
        self.assertFalse(r['candidateForConfirmation'])

    def test_candidate_flag_and_version_must_agree(self):
        self.change_candidate(lambda r: r['meta']['effectiveConfig'].update(fewshotRequireAvailableTables=False))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_gold_cannot_change_even_with_a_new_checksum(self):
        self.change_candidate(lambda r: r['itemEvidence'][0].update(goldSqlSha256='0' * 64))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_frozen_pair_bytes_cannot_change(self):
        p = self.root / 'prompt-pairs.json'
        p.write_text(p.read_text(encoding='utf-8') + '\n', encoding='utf-8')
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_duplicate_pair_is_not_a_hundred_questions(self):
        self.pairs['items'][-1] = self.pairs['items'][0]
        self.save_pairs()
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_raw_ledger_must_identify_selected_policy(self):
        p = next((self.root / 'available-tables' / 'llm-calls').glob('*.jsonl'))
        rows = [json.loads(s) for s in p.read_text(encoding='utf-8').splitlines()]
        rows[0]['configuredFewshotSelectionPolicy'] = 'overlap-v1'
        p.write_text(''.join(json.dumps(r) + '\n' for r in rows), encoding='utf-8')
        self.assertFalse(self.result()['passedEvidenceAudit'])


if __name__ == '__main__':
    unittest.main()
