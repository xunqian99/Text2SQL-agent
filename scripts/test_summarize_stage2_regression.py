"""Candidate resource registration must not disable configuration drift checks. No model calls."""
import unittest

from summarize_stage2_regression import audit_baseline_config


class CandidateMetricResourceTest(unittest.TestCase):
    baseline = dict(metricDefinitionsSha256='old-resource', maxTokens=8192, requestTimeoutMs=15000,
                    clarificationEnabled=True)

    def errors(self, config, candidate_sha):
        errors = []
        audit_baseline_config(config, self.baseline, candidate_sha,
                              lambda ok, message: None if ok else errors.append(message))
        return errors

    def test_original_protocol_still_requires_exact_baseline_resource(self):
        self.assertEqual([], self.errors(dict(self.baseline), None))
        self.assertTrue(self.errors(dict(self.baseline, metricDefinitionsSha256='new-resource'), None))

    def test_registered_candidate_resource_can_differ_from_historical_baseline(self):
        self.assertEqual([], self.errors(dict(self.baseline, metricDefinitionsSha256='new-resource'), 'new-resource'))

    def test_wrong_runtime_resource_is_rejected_even_if_it_matches_baseline(self):
        self.assertTrue(self.errors(dict(self.baseline), 'new-resource'))

    def test_empty_frozen_resource_is_rejected(self):
        self.assertTrue(self.errors(dict(self.baseline, metricDefinitionsSha256=''), ''))

    def test_other_runtime_config_drift_is_still_rejected(self):
        for key, value in (('maxTokens', 2048), ('requestTimeoutMs', 30000), ('clarificationEnabled', False)):
            with self.subTest(key=key):
                self.assertTrue(self.errors(dict(self.baseline, metricDefinitionsSha256='new-resource', **{key:value}), 'new-resource'))

    def test_missing_runtime_resource_is_rejected(self):
        config = dict(self.baseline)
        del config['metricDefinitionsSha256']
        self.assertTrue(self.errors(config, 'new-resource'))


if __name__ == '__main__':
    unittest.main()
