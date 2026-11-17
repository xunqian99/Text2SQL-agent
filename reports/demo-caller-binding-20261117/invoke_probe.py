"""Launch the unchanged 20-case caller from verified cold-build artifacts, never workspace target."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEMO = ROOT / 'reports/demo-http-20-20261117'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verified_command(mode):
    binding = read(HERE / 'binding.json')
    assert sha(__file__) == binding['wrapperSha256'], 'Frozen wrapper changed'
    assert sha(DEMO / 'protocol.json') == binding['protocolSha256']
    protected_path = ROOT / 'reports/protected-current-20261117/protocol.json'
    assert sha(protected_path) == binding['classpathProtocolSha256']
    plan = read(protected_path)
    runtime = read(DEMO / 'artifact-plan.json')
    assert sha(runtime['executableJar']) == binding['jarSha256'] == runtime['executableJarSha256']
    source = Path(plan['sourceDirectory'])
    assert len(plan['classpath']) == 116 and all(plan['classpath'])
    assert not (source / 'target/classes/application-local.yml').exists()
    assert not (source / 'target/test-classes/application-local.yml').exists()
    for path, digest in plan['dependencyHashes'].items():
        assert sha(path) == digest, 'Dependency changed: ' + str(Path(path).name)
    for path, digest in plan['testClassHashes'].items():
        assert sha(source / 'target/test-classes' / path) == digest, 'Test caller classes changed'
    with zipfile.ZipFile(runtime['executableJar']) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                assert archive.read(name) == (source / 'target/classes' / name.removeprefix('BOOT-INF/classes/')).read_bytes()
    for path, identity in plan['sourceObjects'].items():
        assert subprocess.check_output(['git', 'rev-parse', 'HEAD:' + path], cwd=ROOT, text=True).strip() == identity
    assert not subprocess.check_output(['git', 'status', '--porcelain', '--', 'src', 'pom.xml', 'data/eval', 'scripts/start_local.ps1'], cwd=ROOT, text=True).strip()
    assert sha(ROOT / 'src/main/resources/application-local.yml') == read(DEMO / 'protocol.json')['localConfigSha256']
    args = ['java', '-cp', ';'.join(plan['classpath']),
            'com.text2sql.agent.acceptance.DemoHttpAcceptanceProbe', mode, str(DEMO)]
    if mode == 'run':
        args += ['http://127.0.0.1:18087']
    return args


if __name__ == '__main__':
    assert len(sys.argv) == 2 and sys.argv[1] in ('preview', 'run', 'summarize'), 'Use preview, run or summarize'
    mode = sys.argv[1]
    args = verified_command('run' if mode == 'preview' else mode)
    if mode == 'preview':
        print(json.dumps(dict(artifactVerification='PASS', callerSource='retained cold build',
              classpathEntries=116, workspaceTargetUsed=False, applicationStarted=False,
              httpRequests=0, providerCalls=0), ensure_ascii=False))
    else:
        # Caller only sends HTTP to the local app; it never needs the supplier key or Spring/JVM overrides.
        env = {name: value for name, value in os.environ.items()
               if not re.match(r'^(AGENT_|SPRING_|TEXT2SQL_TEST_DB_)', name, re.I)
               and name.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')}
        completed = subprocess.run(args, cwd=ROOT, env=env, stdout=sys.stdout, stderr=sys.stderr,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        sys.exit(completed.returncode)
