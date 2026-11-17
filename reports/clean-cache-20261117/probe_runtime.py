"""Exercise the newly built jar without any model credentials or external model endpoint."""
from pathlib import Path
import hashlib
import json
import os
import re
import socket
import subprocess
import time
import urllib.error
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
protocol = json.loads((HERE / 'protocol.json').read_text(encoding='utf-8-sig'))
build = json.loads((HERE / 'result.json').read_text(encoding='utf-8-sig'))
assert build['mavenExitCode'] == 0 and build['tests'] == 710
jar = Path(build['executableJar'])
assert hashlib.sha256(jar.read_bytes()).hexdigest() == build['executableJarSha256']
source = Path(protocol['sourceDirectory'])
assert not (source / 'src/main/resources/application-local.yml').exists()
assert not (HERE / 'runtime-result.json').exists(), 'Do not overwrite a runtime attempt'


def write_new(name, value):
    with (HERE / name).open('x', encoding='utf-8') as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write('\n')


def exchange(port, path, body=None):
    request = urllib.request.Request('http://127.0.0.1:' + str(port) + path,
                                     data=None if body is None else json.dumps(body).encode('utf-8'),
                                     headers={} if body is None else {'Content-Type': 'application/json'})
    started = time.monotonic()
    try:
        response = urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode('utf-8')
        return dict(httpStatus=response.code, body=json.loads(raw), wallMs=round((time.monotonic() - started) * 1000))


env = {name: value for name, value in os.environ.items()
       if not re.match(r'^(AGENT_|SPRING_|TEXT2SQL_TEST_DB_)', name, re.I)
       and name.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')}
call_dir = source.parent / 'runtime-call-logs'
args = ['java', '-jar', str(jar), '--spring.profiles.active=baseline', '--server.address=127.0.0.1',
        '--server.port=0', '--agent.eval.enabled=false', '--agent.llm.api-key=',
        '--agent.llm.base-url=http://127.0.0.1:1', '--agent.observability.dir=' + str(call_dir)]
with zipfile.ZipFile(jar) as archive:
    manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8')
    assert 'JarLauncher' in manifest and 'Start-Class:' in manifest
    assert 'BOOT-INF/classes/application.yml' in archive.namelist()
    assert 'BOOT-INF/classes/application-local.yml' not in archive.namelist()
    (HERE / 'jar-manifest.txt').write_text(manifest, encoding='utf-8')

started = time.monotonic()
result = dict(command=args, apiKeyConfigured=False, endpoint='loopback-closed-port', modelCalls=0,
              scope='cold-build executable jar, same-host database, no-key runtime', checks={})
process = None
port = None
try:
    with (HERE / 'runtime-startup.log').open('xb') as log:
        process = subprocess.Popen(args, cwd=source, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        result['pid'] = process.pid
        while time.monotonic() - started < 60:
            if process.poll() is not None:
                raise AssertionError('Application exited before startup: ' + str(process.returncode))
            text = (HERE / 'runtime-startup.log').read_text(encoding='utf-8', errors='replace')
            match = re.search(r'Tomcat started on port (\d+)', text)
            if match:
                port = int(match.group(1))
                break
            time.sleep(.25)
        assert port, 'Application startup timed out'
        result['port'] = port
        result['startupMs'] = round((time.monotonic() - started) * 1000)
        listeners = []
        for line in subprocess.check_output(['netstat', '-ano', '-p', 'tcp'], text=True, errors='replace').splitlines():
            parts = line.split()
            if len(parts) == 5 and parts[0] == 'TCP' and parts[-1] == str(process.pid) and parts[3] == 'LISTENING':
                listeners.append(dict(localAddress=parts[1], state=parts[3], pid=process.pid))
        write_new('runtime-listeners.json', listeners)
        health = exchange(port, '/api/health')
        write_new('health-no-key.json', health)
        h = health['body']
        checks = result['checks']
        checks['loopbackOnly'] = len(listeners) == 1 and listeners[0]['localAddress'] == '127.0.0.1:' + str(port)
        checks['healthHttp200'] = health['httpStatus'] == 200
        checks['baselineMode'] = h['mode'] == 'baseline'
        checks['schema37'] = h['schemaTableCount'] == 37 and h['schemaAvailable']
        checks['noKeyNotReady'] = not h['llmConfigured'] and not h['ready']
        checks['noPaidHealthClaim'] = not h['providerVerified'] and not h['databaseLiveVerified']
        ask = exchange(port, '/api/ask', {'question': '2018年有多少笔订单？'})
        write_new('ask-no-key.json', ask)
        checks['ask503NotConfigured'] = ask['httpStatus'] == 503 and ask['body']['status'] == 'NOT_CONFIGURED'
        records = sum(len([line for line in path.read_text(encoding='utf-8').splitlines() if line.strip()])
                      for path in call_dir.glob('*.jsonl'))
        checks['noModelLogRecords'] = records == 0
        result['modelLogRecords'] = records
        assert all(checks.values()), 'Runtime contract failed: ' + str(checks)
        result['passed'] = True
except Exception as error:
    result['passed'] = False
    result['error'] = type(error).__name__ + ': ' + str(error)
    raise
finally:
    if process is not None:
        if process.poll() is None:
            process.terminate()
        process.wait(timeout=15)
        result['processExited'] = process.poll() is not None
        result['processExitCodeAfterTermination'] = process.returncode
    if port:
        with socket.socket() as sock:
            sock.settimeout(1)
            result['portClosed'] = sock.connect_ex(('127.0.0.1', port)) != 0
    result['wallMs'] = round((time.monotonic() - started) * 1000)
    write_new('runtime-result.json', result)
print(json.dumps(dict(passed=result['passed'], checks=len(result['checks']), modelCalls=0,
                      startupMs=result['startupMs'], processExited=result['processExited'], portClosed=result['portClosed'])))
