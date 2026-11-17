"""Run existing synthetic protected HTTP probes from the frozen cold build, once."""
from pathlib import Path
import csv
import hashlib
import io
import json
import os
import re
import socket
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COLD = ROOT / 'reports/clean-cache-20261117'
CHAIN = ROOT / 'reports/isolated-chain-20261117'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(name, value):
    with (HERE / name).open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write('\n')


def inspect(fmt, name):
    return subprocess.check_output(['docker', 'inspect', '--format', fmt, name], text=True).strip()


def alive(pid):
    text = subprocess.check_output(['tasklist', '/FI', f'PID eq {pid}', '/FO', 'CSV', '/NH'], text=True)
    return any(len(row) > 1 and row[1] == str(pid) for row in csv.reader(io.StringIO(text)))


assert not (HERE / 'protocol.json').exists(), 'Never overwrite an acceptance attempt'
cold = read(COLD / 'protocol.json')
build = read(COLD / 'result.json')
assert build['mavenExitCode'] == 0 and sha(build['executableJar']) == build['executableJarSha256']
source = Path(cold['sourceDirectory'])
assert not (source / 'src/main/resources/application-local.yml').exists()
witness = read(COLD / 'dependency-witness.json')
for path, identity in witness['sourceFilesSha256'].items():
    assert sha(source / path) == identity, path
for path, identity in cold['sourceObjects'].items():
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD:' + path], cwd=ROOT, text=True).strip() == identity

suite = next((COLD / 'surefire').glob('TEST-*.xml'))
properties = {p.attrib['name']: p.attrib.get('value', '') for p in ET.parse(suite).getroot().findall('properties/property')}
# Surefire's trailing empty path would add the current directory. Exclude it explicitly.
classpath = [str(Path(path)) for path in properties['java.class.path'].split(';') if path]
cache = Path(cold['repositoryDirectory'])
known = {str((cache / item['path']).resolve()): item['sha256'] for item in witness['cacheArtifacts']}
dependency_hashes = {}
for path in classpath:
    target = Path(path)
    assert target.exists() and target.resolve().is_relative_to(source.parent.resolve())
    if target.is_file():
        assert sha(target) == known[str(target.resolve())]
        dependency_hashes[path] = sha(target)
classes = source / 'target/classes'
with zipfile.ZipFile(build['executableJar']) as archive:
    packaged = [name for name in archive.namelist() if name.startswith('BOOT-INF/classes/') and not name.endswith('/')]
    for name in packaged:
        assert archive.read(name) == (classes / name.removeprefix('BOOT-INF/classes/')).read_bytes(), name
test_classes = {str(path.relative_to(source / 'target/test-classes')).replace('\\', '/'): sha(path)
                for path in (source / 'target/test-classes').rglob('*') if path.is_file()}

chain = read(CHAIN / 'protocol.json')
binding = read(CHAIN / 'database-binding.json')
container = chain['container']
assert container == 'text2sql-clean-chain-20261117'
assert inspect('{{.Id}}', container) == binding['containerId']
assert inspect('{{.State.Running}}', container) == 'false'
original = dict(containerId=inspect('{{.Id}}', 'text2sql-postgres'), running=inspect('{{.State.Running}}', 'text2sql-postgres') == 'true')
assert original == chain['originalContainer']
protocol = dict(sourceCommit=cold['sourceCommit'], sourceObjects=cold['sourceObjects'], sourceDirectory=str(source),
                jarSha256=build['executableJarSha256'], packagedEntriesMatched=len(packaged),
                classpath=classpath, dependencyHashes=dependency_hashes, testClassHashes=test_classes,
                databaseContainer=container, databaseContainerId=binding['containerId'], databasePort=15433,
                originalContainer=original, runnerSha256=sha(__file__),
                scope='Cold-build classpath matches executable production entries; existing synthetic ChatModel probes, real protected HTTP/PostgreSQL. Not packaged-jar launch or provider accuracy.')
save('protocol.json', protocol)
env = {name: value for name, value in os.environ.items()
       if not re.match(r'^(AGENT_|SPRING_|TEXT2SQL_TEST_DB_)', name, re.I)
       and name.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')}
env.update(SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:15433/olist?ApplicationName=protected-current-20261117',
           SPRING_DATASOURCE_USERNAME='text2sql_ro', SPRING_DATASOURCE_PASSWORD='text2sql_ro')
attempts = []
try:
    subprocess.run(['docker', 'start', container], check=True, capture_output=True)
    for _ in range(60):
        ready = subprocess.run(['docker', 'exec', container, 'pg_isready', '-U', 'text2sql', '-d', 'olist'], capture_output=True)
        if ready.returncode == 0:
            break
        time.sleep(.25)
    assert ready.returncode == 0
    for phase, main in [('access', 'com.text2sql.agent.access.AccessHttpAcceptanceProbe'),
                        ('cost', 'com.text2sql.agent.cost.CostHttpAcceptanceProbe')]:
        args = ['java', '-cp', ';'.join(classpath), main, str(HERE / phase)]
        started = time.monotonic()
        with (HERE / (phase + '.log')).open('xb') as log:
            process = subprocess.Popen(args, cwd=source, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
            attempt = dict(phase=phase, pid=process.pid, command=args)
            attempts.append(attempt)
            try:
                code = process.wait(timeout=150)
            except subprocess.TimeoutExpired:
                attempt['observationTimedOut'] = True
                raise  # Do not restart or kill children merely because observation expired.
            attempt.update(exitCode=code, wallMs=round((time.monotonic() - started) * 1000))
        save(phase + '-attempt.json', attempt)
        assert code == 0, 'Probe failed; preserve log and responses: ' + phase
    access, cost = read(HERE / 'access/summary.json'), read(HERE / 'cost/summary.json')
    assert access['passed'] and access['checks'] == 36 and cost['passed'] and cost['scenarios'] == 4
    assert access['externalProviderCalls'] == cost['externalProviderCalls'] == 0
    assert access['syntheticModelCalls'] == 2 and cost['syntheticModelCalls'] == 3
    ports = access['ports'] + cost['ports']
    pids = [attempt['pid'] for attempt in attempts] + cost['pids']
    processes = [dict(pid=pid, alive=alive(pid)) for pid in pids]
    assert not any(item['alive'] for item in processes)
    port_states = []
    for port in ports:
        with socket.socket() as sock:
            sock.settimeout(1)
            port_states.append(dict(port=port, closed=sock.connect_ex(('127.0.0.1', port)) != 0))
    assert all(item['closed'] for item in port_states)
    save('result.json', dict(passed=True, accessChecks=36, costScenarios=4, syntheticModelCalls=5,
         externalProviderCalls=0, externalProviderCostYuan=0, processes=processes, ports=port_states,
         javaTestsRerun=False, scope=protocol['scope']))
finally:
    save('attempts.json', attempts)
    # An observation timeout is not termination: do not stop a database under live probes.
    live = [attempt['pid'] for attempt in attempts if alive(attempt['pid'])]
    if not live:
        assert inspect('{{.Id}}', container) == binding['containerId']
        subprocess.run(['docker', 'stop', container], check=True, capture_output=True)
    after = dict(containerId=inspect('{{.Id}}', 'text2sql-postgres'), running=inspect('{{.State.Running}}', 'text2sql-postgres') == 'true')
    assert after == original
    save('shutdown.json', dict(liveParentPids=live, isolatedContainerRunning=inspect('{{.State.Running}}', container) == 'true',
         isolatedVolumePreserved=True, originalContainer=after))
print('Current protected HTTP: access36/36, cost4/4 groups; synthetic calls5, provider calls0, all processes/ports closed.')
