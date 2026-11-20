"""Fresh independent tests of permanent failure, snapshots and delayed checking."""
from __future__ import annotations
import base64,copy,io,json,sys,unittest
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[5];OUT=Path(__file__).resolve().parent
sys.dont_write_bytecode=True;sys.path.insert(0,str(ROOT/'scripts'))
import test_replan_quota_monitor as supplied
import run_replan_stage_d_reregistered as n
import replan_quota_monitor as q
supplied.EVIDENCE=(OUT/'independent-boundaries').resolve()

class Independent(supplied.MonitorChecks):
    def assert_permanent(self,gate,payload,raw):
        with self.assertRaises(q.QuotaRefused):gate.before(payload,raw)
        fresh=q.QuotaMonitor(self.directory,self.plan_sha,n.raw_write)
        with self.assertRaisesRegex(q.QuotaRefused,'Permanent quota persistence stop'):fresh.observe('NEW_PROCESS_MODEL')
        self.assertEqual(q.read(self.monitor.ledger)['calls'],[])

    def independent_first_a_truncated_then_restore(self):
        gate=self.real_gate();payload,raw=self.payload();self.java_pending(payload)
        path=self.monitor.runtime;original=path.read_bytes();bad=b'{"knownCalls":68,';path.write_bytes(bad)
        with self.assertRaises(ValueError):gate.before(payload,raw)
        self.assertTrue(self.monitor.poisoned)
        record=q.read(self.monitor.failure)
        self.assertEqual(record['quotaSnapshots'],[])
        self.assertEqual(base64.b64decode(record['invalidRuntimeRawBase64']),bad)
        path.write_bytes(original);self.assert_permanent(gate,payload,raw)

    def independent_first_a_missing_then_restore(self):
        gate=self.real_gate();payload,raw=self.payload();self.java_pending(payload)
        path=self.monitor.runtime;original=path.read_bytes();path.unlink()
        with self.assertRaises(FileNotFoundError):gate.before(payload,raw)
        self.assertTrue(self.monitor.poisoned);self.assertTrue(self.monitor.failure.is_file())
        self.assertNotIn('invalidRuntimeRawBase64',q.read(self.monitor.failure))
        path.write_bytes(original);self.assert_permanent(gate,payload,raw)

    def independent_after_first_a_invalid_keeps_original_unknown(self):
        gate=self.real_gate();payload,raw=self.payload();self.java_pending(payload);permit=gate.before(payload,raw)
        checkpoint=self.monitor.head.read_bytes();original=self.monitor.runtime.read_bytes();self.monitor.runtime.write_bytes(b'[]')
        with self.assertRaises(q.QuotaRefused):self.completed(gate,permit)
        self.assertEqual(self.monitor.head.read_bytes(),checkpoint)
        durable=q.read(self.monitor.ledger)['calls'][0]
        self.assertFalse(durable['usageKnown']);self.assertIsNone(durable['result'])
        self.assertFalse((gate.events/(permit['token']+'-completion.json')).exists())
        self.monitor.runtime.write_bytes(original)
        with self.assertRaises(q.QuotaRefused):q.QuotaMonitor(self.directory,self.plan_sha,n.raw_write).observe('RESTART')

    def independent_a_liability_survives_b_invalid(self):
        gate=self.real_gate();payload,raw=self.payload();self.java_pending(payload)
        value=self.runtime();value.update(knownCalls=68,knownCostYuan='.17312188')
        value['holds']['only-in-a']=dict(requestId='only-in-a-request',callNumber=1,promptSha256='f'*64,status='UNKNOWN');self.java(value)
        original=self.monitor._snapshot;first=True
        def snapshot():
            nonlocal first
            result=original()
            if first:first=False;self.monitor.runtime.write_bytes(b'{"invalid":')
            return result
        with patch.object(self.monitor,'_snapshot',side_effect=snapshot):
            with self.assertRaises(ValueError):gate.before(payload,raw)
        latest=self.checkpoint();knowledge=latest['knowledge']
        self.assertEqual(knowledge['knownCalls'],68);self.assertEqual(knowledge['knownCostYuan'],'0.17312188')
        self.assertIn('only-in-a',knowledge['unknowns']);self.assertIn('stopReason',knowledge)
        self.assertNotIn('new-hold',knowledge['bindings'])
        self.java(self.seed)
        with self.assertRaises(q.QuotaRefused):self.monitor.observe('RESTORE')

    def independent_a_pending_becomes_unknown_in_b(self):
        gate=self.real_gate();payload,raw=self.payload();identity=self.java_pending(payload)
        original=self.monitor._snapshot;first=True
        def snapshot():
            nonlocal first
            result=original()
            if first:
                first=False;value=self.runtime();value['holds'][identity]['status']='UNKNOWN';self.java(value)
            return result
        with patch.object(self.monitor,'_snapshot',side_effect=snapshot):
            with self.assertRaisesRegex(q.QuotaRefused,'PENDING|Candidate send'):gate.before(payload,raw)
        knowledge=self.checkpoint()['knowledge'];self.assertIn(identity,knowledge['unknowns'])
        self.assertIn('stopReason',knowledge);self.assertNotIn(identity,knowledge['bindings'])

    def independent_full_tamper_blocks_next_question_before_permit(self):
        gate=self.real_gate();payload,raw=self.payload();self.java_pending(payload);permit=gate.before(payload,raw);self.completed(gate,permit)
        old=gate.events/(permit['token']+'-upstream.body.bin');old.write_bytes(old.read_bytes()+b'changed')
        # Fast remains based on anchored ledger and detects historical content at FULL.
        latest=self.monitor._load(fast=True)[1]
        self.assertEqual(latest['knowledge']['knownCalls'],67)
        with self.assertRaisesRegex(q.QuotaRefused,'lost/changed'):self.monitor.observe('NEXT_QUESTION_FULL')
        self.assertEqual(len(q.read(self.monitor.ledger)['calls']),1)

    def independent_rejected_before_does_not_append_more_rejections(self):
        gate=self.real_gate();payload,raw=self.payload();self.java_pending(payload)
        payload['temperature']=1
        with self.assertRaises(n.Refused):gate.before(payload,supplied.bytes_for(payload))
        first=self.monitor.journal.read_bytes();stopped=q.read(self.monitor.ledger)
        self.assertEqual(len(stopped['rejections']),1);self.assertEqual(stopped['calls'],[])
        payload['temperature']=0
        for _ in range(2):
            with self.assertRaises(q.QuotaRefused):gate.before(payload,raw)
        self.assertEqual(self.monitor.journal.read_bytes(),first)
        self.assertEqual(q.read(self.monitor.ledger)['rejections'],stopped['rejections'])

if __name__=='__main__':
    names=[name for name in Independent.__dict__ if name.startswith('independent_')]
    tests=unittest.TestSuite(Independent(name) for name in names)
    output=io.StringIO();result=unittest.TextTestRunner(stream=output,verbosity=2).run(tests)
    (OUT/'independent-boundaries.log').write_bytes(output.getvalue().encode())
    with (OUT/'independent-boundaries.json').open('x',encoding='utf-8') as f:json.dump(dict(testsRun=result.testsRun,failures=len(result.failures),errors=len(result.errors),passed=result.wasSuccessful(),names=names,providerCalls=0,actualBatchTouched=False),f,ensure_ascii=False,indent=2)
    sys.exit(0 if result.wasSuccessful() else 1)
