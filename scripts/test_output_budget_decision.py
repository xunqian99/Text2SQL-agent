"""Ensure screening cannot promote fast but damaged, truncated, or unpriced candidates."""
from copy import deepcopy
import unittest
from summarize_output_budget import decision


class OutputBudgetDecisionTest(unittest.TestCase):
    def setUp(self):
        self.base = dict(correct=70, wallP95Ms=10000, truncatedRequests=[], unknownUsage=[],
                         costYuan=.2, boundedReturnViolations=[], cancellationRejected=0)
        self.candidate = deepcopy(self.base)
        self.candidate['wallP95Ms'] = 8000

    def test_promotes_only_for_confirmation_not_stage_acceptance(self):
        result = decision(self.base, self.candidate)
        self.assertTrue(result['candidateForConfirmation'])
        self.assertFalse(result['stageAccepted'])
        self.assertFalse(result['candidateP95Below8Seconds'])

    def test_rejects_accuracy_loss_over_two(self):
        self.candidate['correct'] = 67
        self.assertFalse(decision(self.base, self.candidate)['candidateForConfirmation'])

    def test_rejects_small_latency_gain(self):
        self.candidate['wallP95Ms'] = 9001
        self.assertFalse(decision(self.base, self.candidate)['candidateForConfirmation'])

    def test_rejects_extra_truncation_hidden_by_retries(self):
        self.candidate['truncatedRequests'] = ['q1', 'q2']
        self.assertFalse(decision(self.base, self.candidate)['candidateForConfirmation'])

    def test_unknown_cost_is_unconfirmed_even_if_known_cost_is_cheap(self):
        self.candidate['unknownUsage'] = ['q1:1']
        result = decision(self.base, self.candidate)
        self.assertIsNone(result['screenGates']['completeCostAtMost030'])
        self.assertFalse(result['candidateForConfirmation'])

    def test_rejects_resource_guard_failures(self):
        for field, value in (('boundedReturnViolations', ['q1']), ('cancellationRejected', 1)):
            candidate = {**self.candidate, field: value}
            self.assertFalse(decision(self.base, candidate)['candidateForConfirmation'])


if __name__ == '__main__':
    unittest.main()
