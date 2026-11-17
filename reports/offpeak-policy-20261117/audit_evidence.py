"""Offline evidence capture/audit. Never creates a model client or reads credentials."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCES = [
    'src/main/java/com/text2sql/agent/budget/DeepSeekOffPeakPolicy.java',
    'src/main/java/com/text2sql/agent/budget/BudgetHttpRequestFactory.java',
    'src/main/java/com/text2sql/agent/generation/LlmSqlGenerator.java',
    'src/main/java/com/text2sql/agent/generation/GenerationException.java',
    'src/main/java/com/text2sql/agent/evaluation/EvalRunner.java',
    'src/main/java/com/text2sql/agent/orchestrator/Text2SqlOrchestrator.java',
    'src/test/java/com/text2sql/agent/acceptance/DemoHttpAcceptanceProbe.java',
    'src/test/java/com/text2sql/agent/budget/DeepSeekOffPeakPolicyTest.java',
    'src/test/java/com/text2sql/agent/generation/DeepSeekOffPeakGenerationTest.java',
    'src/test/java/com/text2sql/agent/orchestrator/OrchestratorSelfCorrectionTest.java',
]


def digest(path, normalized=False):
    data = path.read_bytes()
    return hashlib.sha256(data.replace(b'\r\n', b'\n') if normalized else data).hexdigest()


def totals(folder):
    suites = [ET.parse(path).getroot() for path in sorted(folder.glob('TEST-*.xml'))]
    return dict(suites=len(suites), **{key: sum(int(s.attrib[key]) for s in suites)
                for key in ('tests', 'failures', 'errors', 'skipped')})


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


if '--capture' in sys.argv:
    assert not (HERE / 'manifest.json').exists(), 'Never overwrite evidence'
    destination = HERE / 'surefire'
    destination.mkdir()
    for path in (ROOT / 'target/surefire-reports').glob('TEST-*.xml'):
        shutil.copyfile(path, destination / path.name)
    assert totals(destination) == dict(suites=68, tests=710, failures=0, errors=0, skipped=0)
    payload = dict(
        sourceBaseCommit=git('rev-parse', 'HEAD'),
        originalDatasetTree=git('rev-parse', 'HEAD:data/eval'),
        policySource='https://api-docs.deepseek.com/zh-cn/quick_start/pricing/',
        verifiedDateBeijing='2026-11-17',
        policy='deepseek-off-peak-weekday-conservative-v1',
        command='mvn -q test', mavenExitCode=0,
        totals=totals(destination), addedChecks=40,
        externalProviderCalls=0, externalProviderCostYuan=0,
        initialFailure='8 mock-verification failures: configuration checks are allowed; reserve must never be called. '
                       'The first targeted run and first full run used this same incorrect assertion. '
                       'Final full run follows the correction; no paid replay.',
        sourcesLfSha256={name: digest(ROOT / name, True) for name in SOURCES},
        artifactsSha256={str(path.relative_to(HERE)).replace('\\', '/'): digest(path)
                         for path in sorted(HERE.rglob('*')) if path.is_file()}
    )
    (HERE / 'manifest.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

manifest = json.loads((HERE / 'manifest.json').read_text(encoding='utf-8'))
assert totals(HERE / 'surefire') == manifest['totals']
assert manifest['totals'] == dict(suites=68, tests=710, failures=0, errors=0, skipped=0)
assert manifest['externalProviderCalls'] == 0
for name, expected in manifest['artifactsSha256'].items():
    assert digest(HERE / name) == expected, f'Archived bytes changed: {name}'
for name, expected in manifest['sourcesLfSha256'].items():
    assert digest(ROOT / name, True) == expected, f'Source changed: {name}'
assert git('rev-parse', 'HEAD:data/eval') == manifest['originalDatasetTree']
old = totals(HERE / 'initial-failures')
assert old['tests'] == 8 and old['failures'] == 8
print(json.dumps(dict(audit='PASS', **manifest['totals'], externalProviderCalls=0), ensure_ascii=False))
