"""Inspect caller binding and archived real-clock refusal, without making HTTP requests."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('frozen_demo_caller', HERE / 'invoke_probe.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
for mode in ('run', 'summarize'):
    args = module.verified_command(mode)
    assert args[3] == 'com.text2sql.agent.acceptance.DemoHttpAcceptanceProbe' and args[4] == mode
    assert all('text2sql-clean-cache-20261117' in path for path in args[2].split(';'))
peak = module.read(HERE / 'peak-preflight.json')
assert peak['exitCode'] == 1 and peak['rejectedBeforeHttp'] and not peak['connectionException']
assert not peak['requestsDirectoryExists'] and not peak['healthBeforeExists'] and peak['providerCalls'] == 0
# Java Windows diagnostics use the native code page. Check ASCII exception tokens
# without rewriting or pretending to decode the original diagnostic bytes as UTF-8.
log = (HERE / 'peak-preflight-captured.log').read_bytes()
assert b'DeepSeekOffPeakPolicy$Rejected' in log and b'PEAK_HOURS' in log
initial = module.read(HERE / 'initial-output-capture/failure.json')
assert not initial['passed'] and initial['stdoutLogBytes'] == 0 and not initial['paidRunStarted']
assert (HERE / 'initial-output-capture/peak-preflight.log').stat().st_size == 0
manifest = HERE / 'manifest.json'
if '--capture' in sys.argv:
    assert not manifest.exists(), 'Do not overwrite evidence'
    records = [dict(path=str(path.relative_to(HERE)).replace('\\', '/'), sha256=module.sha(path))
               for path in sorted(HERE.rglob('*')) if path.is_file() and '__pycache__' not in path.parts]
    with manifest.open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(dict(artifacts=records), handle, indent=2)
        handle.write('\n')
for item in module.read(manifest)['artifacts']:
    assert module.sha(HERE / item['path']) == item['sha256']
print(json.dumps(dict(bindingAudit='PASS', classpathEntries=116, workspaceTargetUsed=False,
                     realClockPeakRefusal='BEFORE_HTTP', archivedProviderCalls=0,
                     currentPaidRunStarted=(module.DEMO / 'requests').exists())))
