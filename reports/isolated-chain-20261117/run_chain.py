"""Explicit same-host isolated acceptance; prepare never has a model key.

One paid HTTP question is a separate, frozen, off-peak-only command.
No database or evidence directory is automatically deleted or reused.
"""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COLD = ROOT / 'reports/clean-cache-20261117'
CONTAINER = 'text2sql-clean-chain-20261117'
DB_PORT = 15433
QUESTION = '2018年有多少笔订单？'
GOLD_SQL = "SELECT count(*) FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'"


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def save(name, value):
    with (HERE / name).open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write('\n')


def command(args):
    return subprocess.check_output(args, cwd=ROOT, text=True, encoding='utf-8', errors='replace').strip()


def original_state():
    return dict(containerId=command(['docker', 'inspect', '--format', '{{.Id}}', 'text2sql-postgres']),
                running=command(['docker', 'inspect', '--format', '{{.State.Running}}', 'text2sql-postgres']) == 'true')


def psql(sql, role='text2sql_ro'):
    return command(['docker', 'exec', CONTAINER, 'psql', '-X', '-U', role, '-d', 'olist',
                    '-v', 'ON_ERROR_STOP=1', '-At', '-c', sql])


def inputs():
    cold = read(COLD / 'protocol.json')
    build = read(COLD / 'result.json')
    assert build['mavenExitCode'] == 0 and build['tests'] == 710
    assert sha(build['executableJar']) == build['executableJarSha256']
    source = Path(cold['sourceDirectory'])
    assert not (source / 'src/main/resources/application-local.yml').exists()
    witness = read(COLD / 'dependency-witness.json')
    for path, digest in witness['sourceFilesSha256'].items():
        assert sha(source / path) == digest, 'Exported source changed: ' + path
    for path, identity in cold['sourceObjects'].items():
        assert command(['git', 'rev-parse', 'HEAD:' + path]) == identity
    return cold, build, source


def environment(paid):
    env = {name: value for name, value in os.environ.items()
           if not re.match(r'^(AGENT_|SPRING_|TEXT2SQL_TEST_DB_)', name, re.I)
           and name.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')}
    env.update(SPRING_DATASOURCE_URL=f'jdbc:postgresql://127.0.0.1:{DB_PORT}/olist?ApplicationName=isolated-chain-20261117',
               SPRING_DATASOURCE_USERNAME='text2sql_ro', SPRING_DATASOURCE_PASSWORD='text2sql_ro')
    if paid:
        key = os.environ.get('AGENT_LLM_API_KEY', '')
        assert key.strip(), 'Provide the existing key only through the child process environment'
        env['AGENT_LLM_API_KEY'] = key
    return env


def args_for(plan, phase):
    args = ['java', '-jar', plan['jar'], '--spring.profiles.active=local,demo',
            '--spring.config.additional-location=' + Path(plan['configDirectory']).as_uri() + '/',
            '--server.address=127.0.0.1', '--server.port=0', '--agent.eval.enabled=false',
            '--agent.observability.dir=' + str(HERE / (phase + '-calls'))]
    if phase != 'paid':
        args += ['--agent.llm.api-key=', '--agent.llm.base-url=http://127.0.0.1:1']
    return args


def exchange(port, path, body=None, method=None):
    req = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                                 data=None if body is None else json.dumps(body).encode('utf-8'),
                                 headers={} if body is None else {'Content-Type': 'application/json'}, method=method)
    started = time.monotonic()
    try:
        response = urllib.request.urlopen(req, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode('utf-8')
        return dict(httpStatus=response.code, body=json.loads(raw) if raw else None,
                    wallMs=round((time.monotonic() - started) * 1000))


def off_peak_check():
    local = datetime.now(timezone(timedelta(hours=8)))
    start = local.hour * 3600 + local.minute * 60 + local.second
    rejected = local.weekday() < 5 and any(start < end and start + 120 >= begin
                                          for begin, end in ((9 * 3600, 12 * 3600), (14 * 3600, 18 * 3600)))
    save('paid-window-check.json', dict(checkedAt=local.isoformat(), allowed=not rejected,
                                      guardSeconds=120, weekdayHolidayPolicy='conservative',
                                      providerTransportPolicy='DeepSeekOffPeakPolicy in frozen jar'))
    assert not rejected, 'Peak/guard interval: do not start the paid acceptance'


def runtime(plan, paid=False):
    phase = 'paid' if paid else 'no-key'
    if paid:
        assert read(HERE / 'offline-result.json')['passed']
        off_peak_check()
    assert sha(plan['jar']) == plan['jarSha256']
    assert sha(Path(plan['configDirectory']) / 'application-local.yml') == plan['configSha256']
    assert psql(GOLD_SQL).strip() == plan['expectedValue']
    args = args_for(plan, phase)
    result = dict(startedAt=now(), command=args, apiKeyConfigured=paid, passed=False)
    process = None
    port = None
    try:
        with (HERE / (phase + '-startup.log')).open('xb') as log:
            process = subprocess.Popen(args, cwd=plan['sourceDirectory'], env=environment(paid),
                                       stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
            result['pid'] = process.pid
            started = time.monotonic()
            while time.monotonic() - started < 60:
                assert process.poll() is None, 'Application exited before startup'
                content = (HERE / (phase + '-startup.log')).read_text(encoding='utf-8', errors='replace')
                match = re.search(r'Tomcat started on port (\d+)', content)
                if match:
                    port = int(match.group(1))
                    break
                time.sleep(.25)
            assert port, 'Application startup timeout; preserve logs'
            result.update(port=port, startupMs=round((time.monotonic() - started) * 1000))
            listeners = [line.split() for line in command(['netstat', '-ano', '-p', 'tcp']).splitlines()
                         if len(line.split()) == 5 and line.split()[-1] == str(process.pid)
                         and line.split()[3] == 'LISTENING']
            save(phase + '-listeners.json', listeners)
            assert len(listeners) == 1 and listeners[0][1] == f'127.0.0.1:{port}'
            health = exchange(port, '/api/health')
            save(phase + '-health.json', health)
            h = health['body']
            assert health['httpStatus'] == 200 and h['mode'] == 'demo' and h['schemaTableCount'] == 37
            assert h['llmConfigured'] == paid and h['ready'] == paid and not h['providerVerified']
            connections = json.loads(psql("SELECT coalesce(json_agg(json_build_object('role',usename,'database',datname,'application',application_name)),'[]') FROM pg_stat_activity WHERE application_name='isolated-chain-20261117'", 'text2sql'))
            save(phase + '-connections.json', connections)
            assert connections and all(c['role'] == 'text2sql_ro' and c['database'] == 'olist' for c in connections)
            response = exchange(port, '/api/ask', dict(question=QUESTION, sessionId='isolated-chain-2018'))
            save(phase + '-ask.json', response)
            if paid:
                body = response['body']
                assert response['httpStatus'] == 200 and body['status'] == 'SUCCESS'
                assert body['rows'] == [[plan['expectedValue']]]
                close = exchange(port, '/api/session/isolated-chain-2018/close', {}, 'POST')
                save('paid-session-close.json', close)
                assert close['httpStatus'] == 200 and close['body']['turnCount'] == 1
                removed = exchange(port, '/api/session/isolated-chain-2018', method='DELETE')
                save('paid-session-delete.json', removed)
                assert removed['httpStatus'] == 204
            else:
                assert response['httpStatus'] == 503 and response['body']['status'] == 'NOT_CONFIGURED'
            result['passed'] = True
    finally:
        if process is not None:
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=20)
            result['processExited'] = process.poll() is not None
        if port:
            with socket.socket() as sock:
                sock.settimeout(1)
                result['portClosed'] = sock.connect_ex(('127.0.0.1', port)) != 0
        result['finishedAt'] = now()
        save(phase + '-runtime-result.json', result)
    assert result['processExited'] and result['portClosed']
    return result


def prepare():
    assert not (HERE / 'protocol.json').exists(), 'Never overwrite an acceptance attempt'
    cold, build, source = inputs()
    original = original_state()
    assert original['running']
    save('original-before.json', original)
    init_env = environment(False)
    # Python preserves PowerShell 7 module paths when launching Windows PowerShell 5.1.
    # Restrict only this child to its builtin modules; do not mutate user/global settings.
    init_env['PSModulePath'] = str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/Modules')
    with (HERE / 'database-init.log').open('xb') as log:
        result = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                                 str(ROOT / 'scripts/prepare_clean_demo.ps1'), '-RunId', 'chain-20261117',
                                 '-DatabasePort', str(DB_PORT), '-OutputDir', 'reports/isolated-chain-20261117/database'],
                                cwd=ROOT, env=init_env, stdout=log, stderr=subprocess.STDOUT,
                                creationflags=subprocess.CREATE_NO_WINDOW, timeout=180)
    assert result.returncode == 0, 'New database initialization failed; preserve all evidence'
    config_dir = source.parent / 'isolated-chain-config'
    config_dir.mkdir(exist_ok=False)
    template = source / 'src/main/resources/application-local.example.yml'
    shutil.copyfile(template, config_dir / 'application-local.yml')
    shutil.copyfile(template, HERE / 'public-config-template.yml')
    expected = psql(GOLD_SQL).strip()
    assert expected == '54011'
    save('database-binding.json', dict(containerId=command(['docker', 'inspect', '--format', '{{.Id}}', CONTAINER]),
                                      ports=json.loads(command(['docker', 'inspect', '--format', '{{json .HostConfig.PortBindings}}', CONTAINER])),
                                      mounts=json.loads(command(['docker', 'inspect', '--format', '{{json .Mounts}}', CONTAINER]))))
    plan = dict(format='same-host-isolated-chain-v1', frozenAt=now(), sourceCommit=cold['sourceCommit'],
                sourceDirectory=str(source), sourceArchiveSha256=cold['sourceArchiveSha256'],
                sourceObjects=cold['sourceObjects'], jar=build['executableJar'], jarSha256=build['executableJarSha256'],
                configDirectory=str(config_dir), configSha256=sha(template), personalConfigLoaded=False,
                initializationModulePath='child-only Windows PowerShell builtin modules',
                container=CONTAINER, databasePort=DB_PORT, question=QUESTION, goldSql=GOLD_SQL,
                expectedValue=expected, paidQuestionCount=1, totalBudgetMs=15000, maxModelCalls=2,
                runnerSha256=sha(__file__), originalContainer=original,
                scope='Existing host/JDK/Maven/Docker image; retained isolated cold source/cache/jar plus newly created database and public template. Not a new OS.')
    save('protocol.json', plan)
    (HERE / 'protocol.sha256').write_text(sha(HERE / 'protocol.json') + '\n', encoding='ascii')
    runtime(plan)
    args = [arg for arg in args_for(plan, 'gold') if arg != '--agent.eval.enabled=false'] + ['--agent.eval.enabled=true', '--agent.eval.dry-run=true',
            '--agent.eval.split=all', '--agent.eval.dir=data/eval', '--agent.eval.limit=0',
            '--agent.eval.repeat=1', '--agent.eval.exit-after-run=true', '--agent.eval.out-dir=' + str(HERE / 'gold')]
    with (HERE / 'gold.log').open('xb') as log:
        started = time.monotonic()
        completed = subprocess.run(args, cwd=source, env=environment(False), stdout=log, stderr=subprocess.STDOUT,
                                   timeout=120, creationflags=subprocess.CREATE_NO_WINDOW)
    files = list((HERE / 'gold').glob('eval-*.json'))
    assert completed.returncode == 0 and len(files) == 1, 'Gold self-check failed; preserve evidence'
    gold = read(files[0])
    assert gold['meta']['dryRun'] and gold['overall']['total'] == gold['overall']['correct'] == 200 and not gold['failures']
    assert gold['meta']['datasetSha256'] == read(COLD / 'gold-result.json')['datasetSha256']
    assert not list((HERE / 'no-key-calls').glob('*.jsonl')) and not list((HERE / 'gold-calls').glob('*.jsonl'))
    assert original_state() == original
    save('offline-result.json', dict(passed=True, databaseInitExitCode=result.returncode,
         goldExitCode=completed.returncode, goldTotal=200, goldCorrect=200,
         goldProcessWallMs=round((time.monotonic() - started) * 1000),
         noKeyRuntimePassed=True, personalConfigLoaded=False, originalContainerUnchanged=True,
         externalModelCalls=0, externalModelCostYuan=0, paidRunStarted=False))
    print('Isolated chain offline checks passed; Gold200/200, no-key HTTP verified, provider calls=0. Database retained for one off-peak paid question.')


def paid():
    plan = read(HERE / 'protocol.json')
    assert sha(HERE / 'protocol.json') == (HERE / 'protocol.sha256').read_text().strip()
    assert sha(__file__) == plan['runnerSha256'], 'Frozen runner changed'
    inputs()
    assert command(['docker', 'inspect', '--format', '{{.Id}}', CONTAINER]) == read(HERE / 'database-binding.json')['containerId']
    assert command(['docker', 'inspect', '--format', '{{.State.Running}}', CONTAINER]) == 'true'
    runtime(plan, paid=True)
    assert original_state() == plan['originalContainer']
    print('Paid HTTP question finished; inspect raw response, complete call ledger and shutdown before declaring acceptance.')


if __name__ == '__main__':
    assert len(sys.argv) == 2 and sys.argv[1] in ('prepare', 'paid'), 'Use prepare or paid explicitly'
    prepare() if sys.argv[1] == 'prepare' else paid()
