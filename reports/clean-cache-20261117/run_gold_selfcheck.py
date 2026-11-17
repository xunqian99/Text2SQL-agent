"""Run unchanged Gold SQL through the cold-built executable jar, with no model key."""
from pathlib import Path
import json
import os
import re
import shutil
import subprocess
import time

HERE = Path(__file__).resolve().parent
protocol = json.loads((HERE / 'protocol.json').read_text(encoding='utf-8-sig'))
build = json.loads((HERE / 'result.json').read_text(encoding='utf-8-sig'))
assert build['mavenExitCode'] == 0
source = Path(protocol['sourceDirectory'])
out = source.parent / 'gold-output'
calls = source.parent / 'gold-call-logs'
assert not out.exists() and not (HERE / 'gold-result.json').exists(), 'Never overwrite Gold evidence'
env = {key: value for key, value in os.environ.items()
       if not re.match(r'^(AGENT_|SPRING_|TEXT2SQL_TEST_DB_)', key, re.I)
       and key.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')}
args = ['java', '-jar', build['executableJar'], '--spring.profiles.active=demo',
        '--server.address=127.0.0.1', '--server.port=0', '--agent.llm.api-key=',
        '--agent.llm.base-url=http://127.0.0.1:1', '--agent.eval.enabled=true',
        '--agent.eval.dry-run=true', '--agent.eval.split=all', '--agent.eval.dir=data/eval',
        '--agent.eval.limit=0', '--agent.eval.repeat=1', '--agent.eval.exit-after-run=true',
        '--agent.eval.out-dir=' + str(out), '--agent.observability.dir=' + str(calls)]
started = time.monotonic()
with (HERE / 'gold-selfcheck.log').open('xb') as log:
    completed = subprocess.run(args, cwd=source, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=120,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
result = dict(command=args, exitCode=completed.returncode, wallMs=round((time.monotonic() - started) * 1000),
              modelCalls=0, scope='Gold execution self-check, not Text2SQL accuracy')
reports = sorted(out.glob('eval-*.json'))
assert len(reports) == 1, 'Expected one untouched Gold report'
archive = HERE / 'gold'
archive.mkdir()
shutil.copyfile(reports[0], archive / reports[0].name)
report = json.loads(reports[0].read_text(encoding='utf-8'))
result['report'] = str((archive / reports[0].name).relative_to(HERE)).replace('\\', '/')
result['overall'] = report['overall']
result['datasetSha256'] = report['meta']['datasetSha256']
result['dryRun'] = report['meta']['dryRun']
result['modelLogRecords'] = sum(len([line for line in path.read_text(encoding='utf-8').splitlines() if line.strip()])
                                for path in calls.glob('*.jsonl'))
result['passed'] = (completed.returncode == 0 and report['meta']['dryRun']
                    and report['overall']['total'] == 200 and report['overall']['correct'] == 200
                    and not report['failures'] and result['modelLogRecords'] == 0)
with (HERE / 'gold-result.json').open('x', encoding='utf-8') as handle:
    json.dump(result, handle, ensure_ascii=False, indent=2)
    handle.write('\n')
assert result['passed'], 'Gold self-check failed; preserve report'
print(json.dumps(dict(gold='200/200', exitCode=completed.returncode, wallMs=result['wallMs'], modelCalls=0)))
