"""Synthetic edge cases for diagnostic bounds; these are not model evaluation questions."""
import unittest

from analyze_latency_routing_feasibility import diagnose, percentile95
from summarize_holdout import is_dev


def observations(latencies, easy=0):
    ids = [f'T1-{n:03d}' for n in range(1, 999) if is_dev(f'T1-{n:03d}')][:len(latencies)]
    return [{'id': key, 'wallLatencyMs': ms, 'difficulty': 'single_table_basic' if i < easy else 'multi_join',
             'correct': False, 'status': 'SUCCESS', 'budget': {'stageMs': {'MODEL': ms}, 'unknownUsageCalls': 0}}
            for i, (key, ms) in enumerate(zip(ids, latencies))]


class LatencyRoutingFeasibilityTest(unittest.TestCase):
    def test_exactly_five_slow_requests_can_coexist_with_p95_below_eight_seconds(self):
        r = diagnose(observations([7999] * 95 + [10000] * 5))
        self.assertEqual(r['observedP95Ms'], 7999)
        self.assertEqual(r['minimumHardSlowRequestsThatMustAlsoImprove'], 0)
        self.assertTrue(r['optimisticPassesP95'])

    def test_sixth_slow_request_at_exactly_eight_seconds_fails_strict_gate(self):
        r = diagnose(observations([7999] * 94 + [8000] * 6))
        self.assertEqual(r['observedP95Ms'], 8000)
        self.assertFalse(r['optimisticPassesP95'])
        self.assertEqual(r['minimumHardSlowRequestsThatMustAlsoImprove'], 1)

    def test_optimizing_easy_requests_does_not_hide_harder_tail(self):
        r = diagnose(observations([5000] * 54 + [1000] * 34 + [10000] * 12, easy=54))
        self.assertEqual(r['optimisticP95Ms'], 10000)
        self.assertEqual(r['remainingHardSlowCount'], 12)
        self.assertEqual(r['minimumHardSlowRequestsThatMustAlsoImprove'], 7)

    def test_easy_tail_is_optimistically_removed_without_changing_denominator(self):
        r = diagnose(observations([12000] * 10 + [1000] * 90, easy=10))
        self.assertEqual(r['observations'], 100)
        self.assertEqual(r['optimisticP95Ms'], 1000)
        self.assertEqual(r['minimumHardSlowRequestsThatMustAlsoImprove'], 0)

    def test_nearest_rank_ceil_is_used_for_twenty_one_samples(self):
        self.assertEqual(percentile95([1] * 19 + [8000, 9000]), 8000)

    def test_unknown_usage_remains_counted_even_when_latency_is_known(self):
        items = observations([1000] * 94 + [15000] * 6)
        items[-1]['budget']['unknownUsageCalls'] = 1
        self.assertEqual(diagnose(items)['slowRequestUnknownUsageCalls'], 1)

    def test_eval_questions_are_rejected_instead_of_being_used_for_tuning(self):
        items = observations([1000])
        items[0]['id'] = next(f'T1-{n:03d}' for n in range(1, 100) if not is_dev(f'T1-{n:03d}'))
        with self.assertRaisesRegex(ValueError, 'development'):
            diagnose(items)

    def test_duplicate_observations_are_rejected(self):
        items = observations([1000, 1000])
        items[1]['id'] = items[0]['id']
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            diagnose(items)

    def test_empty_observations_are_rejected(self):
        with self.assertRaises(ValueError):
            diagnose([])

    def test_invalid_latencies_are_rejected_instead_of_changing_quantile(self):
        for value in [-1, 1.5, None, True]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                diagnose(observations([1000, value]))


if __name__ == '__main__':
    unittest.main()
