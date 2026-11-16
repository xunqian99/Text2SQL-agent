"""Temporary synthetic witnesses test rejection paths; these are NOT new model results."""
import hashlib
import json
import unittest

import test_generation_mode_evidence as fixtures
from summarize_prompt_directive import summarize, DEFAULT_VERSION, CANDIDATE_VERSION
from summarize_holdout import ids_hash


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


class PromptDirectiveEvidenceTest(unittest.TestCase):
    def setUp(self):
        base = self.base = fixtures.GenerationModeEvidenceTest('test_accepts_consistent_protocol_without_accepting_stage')
        base.setUp()
        self.addCleanup(base.doCleanups)
        self.root = base.root
        self.manifest = base.read(self.root / 'manifest.json')
        self.manifest.update(format='prompt-directive-screen-v1', configuredReasoningEffort='provider-default',
                             order=['gold', 'explicit-steps', 'no-explicit-steps'])
        names = {'gold': 'gold', 'provider-default': 'explicit-steps', 'reasoning-none': 'no-explicit-steps'}
        self.reports = {}
        for run in self.manifest['runs']:
            old, name = run['name'], names[run['name']]
            if old != name:
                (self.root / old).rename(self.root / name)
            run.update(name=name, explicitReasoningEnabled=name != 'no-explicit-steps')
            run.pop('configuredReasoningEffort')
            report = self.reports[name] = base.read(self.root / name / run['report'])
            report['meta']['promptVersion'] = CANDIDATE_VERSION if name == 'no-explicit-steps' else DEFAULT_VERSION
            report['meta']['effectiveConfig'].update(explicitReasoningEnabled=name != 'no-explicit-steps',
                                                    configuredReasoningEffort='provider-default')
        originals = {i['id']: i for i in self.reports['explicit-steps']['itemEvidence']}
        candidates = {i['id']: i for i in self.reports['no-explicit-steps']['itemEvidence']}
        witnesses = []
        candidate_first_by_request = {}
        for ident, item in originals.items():
            candidate = candidates[ident]
            original_hash = item['calls'][0]['promptSha256'] if item['calls'] else sha('synthetic-default:' + ident)
            chars = next((i['calls'][0]['usage']['promptChars'] for i in (item, candidate)
                          if i['calls'] and i['calls'][0]['usage']), 5000)
            candidate_hash = sha('synthetic-candidate:' + ident)
            witnesses.append({'id': ident, 'questionSha256': item['questionSha256'],
                              'commonUserSha256': sha('synthetic-user:' + ident), 'commonUserChars': chars - 2795,
                              'contextSha256': sha('synthetic-context:' + ident),
                              'defaultPromptSha256': original_hash, 'candidatePromptSha256': candidate_hash,
                              'defaultPromptChars': chars, 'candidatePromptChars': chars - 259})
            if candidate['calls']:
                first = candidate['calls'][0]
                first['promptSha256'] = candidate_hash
                if first['usage']:
                    first['usage']['promptChars'] = chars - 259
                candidate_first_by_request[candidate['budget']['requestId']] = (candidate_hash, chars - 259)
        self.pairs = {'format': 'prompt-directive-pairs-v1', 'codeCommit': self.manifest['codeCommit'],
                      'split': 'dev', 'modelCalls': 0, 'sameProcessComparisons': 300, 'sameProcessMismatches': [],
                      'promptHashVersion': 'text-tool-messages-v1',
                      'defaultPromptVersion': DEFAULT_VERSION, 'candidatePromptVersion': CANDIDATE_VERSION,
                      'defaultSystemChars': 2795, 'candidateSystemChars': 2536, 'removedChars': 259,
                      'defaultSystemSha256': 'f50464555aceac53dc657613a5887c1a72128ab18ef66a3d41c5413fb2419f0e',
                      'candidateSystemSha256': sha('SYNTHETIC system'), 'items': witnesses,
                      'datasetSha256': self.reports['explicit-steps']['meta']['datasetSha256'], 'sampleIdsSha256': ids_hash(originals)}
        for run in self.manifest['runs']:
            name = run['name']
            path = self.root / name / run['report']
            base.write(path, self.reports[name])
            run['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            for ledger in (self.root / name / 'llm-calls').glob('*.jsonl'):
                rows = [json.loads(line) for line in ledger.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
                for row in rows:
                    row.update(configuredReasoningEffort='provider-default', configuredPromptVersion=self.reports[name]['meta']['promptVersion'])
                    if name == 'no-explicit-steps' and row.get('callNumber') == 1:
                        row['promptSha256'], row['promptChars'] = candidate_first_by_request[row['requestId']]
                ledger.write_text(''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
        self.save_pairs()

    def save_pairs(self):
        path = self.root / 'prompt-pairs.json'
        self.base.write(path, self.pairs)
        self.manifest['promptPairsSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.base.write(self.root / 'manifest.json', self.manifest)

    def change_candidate(self, mutate):
        run = self.manifest['runs'][-1]
        path = self.root / run['name'] / run['report']
        report = self.base.read(path)
        mutate(report)
        self.base.write(path, report)
        run['reportSha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.base.write(self.root / 'manifest.json', self.manifest)

    def result(self):
        return summarize(self.root, verify_workspace=False)

    def test_consistent_witnesses_pass_but_do_not_accept_stage(self):
        result = self.result()
        self.assertTrue(result['passedEvidenceAudit'], result['errors'])
        self.assertFalse(result['stageAccepted'])

    def test_different_provider_mode_is_not_a_single_prompt_factor(self):
        self.change_candidate(lambda r: r['meta']['effectiveConfig'].update(configuredReasoningEffort='none'))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_candidate_cannot_send_default_first_prompt(self):
        def mutate(report):
            first = next(i for i in report['itemEvidence'] if i['calls'])
            witness = next(w for w in self.pairs['items'] if w['id'] == first['id'])
            first['calls'][0]['promptSha256'] = witness['defaultPromptSha256']
        self.change_candidate(mutate)
        result = self.result()
        self.assertFalse(result['passedEvidenceAudit'])
        self.assertIn('first prompt differs from paired witness', '\n'.join(result['errors']))

    def test_default_version_label_cannot_hide_candidate(self):
        self.change_candidate(lambda r: r['meta'].update(promptVersion=DEFAULT_VERSION))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_changed_gold_is_rejected_even_with_new_report_checksum(self):
        self.change_candidate(lambda r: r['itemEvidence'][0].update(goldSqlSha256='0' * 64))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_unknown_usage_does_not_become_free(self):
        result = self.result()
        self.assertTrue(result['groups']['no-explicit-steps']['unknownUsage'])
        self.assertIsNone(result['screenGates']['completeCostAtMost030'])
        self.assertFalse(result['candidateForConfirmation'])

    def test_pair_bytes_cannot_change_after_capture(self):
        path = self.root / 'prompt-pairs.json'
        path.write_text(path.read_text(encoding='utf-8') + '\n', encoding='utf-8')
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_duplicate_pairs_are_not_100_independent_items(self):
        self.pairs['items'][-1] = self.pairs['items'][0]
        self.save_pairs()
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_semantic_switch_cannot_change_together_with_prompt(self):
        self.change_candidate(lambda r: r['meta']['effectiveConfig'].update(semanticEnabled=False))
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_raw_prompt_version_provenance_is_required(self):
        ledger = next((self.root / 'no-explicit-steps' / 'llm-calls').glob('*.jsonl'))
        rows = [json.loads(line) for line in ledger.read_text(encoding='utf-8').splitlines()]
        rows[0].pop('configuredPromptVersion')
        ledger.write_text(''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_equal_system_lengths_are_not_this_ablation(self):
        self.pairs['candidateSystemChars'] = 2795
        self.save_pairs()
        self.assertFalse(self.result()['passedEvidenceAudit'])

    def test_first_call_length_must_match_witness(self):
        def mutate(report):
            first = next(i['calls'][0] for i in report['itemEvidence'] if i['calls'] and i['calls'][0]['usage'])
            first['usage']['promptChars'] += 1
        self.change_candidate(mutate)
        self.assertFalse(self.result()['passedEvidenceAudit'])


if __name__ == '__main__':
    unittest.main()
