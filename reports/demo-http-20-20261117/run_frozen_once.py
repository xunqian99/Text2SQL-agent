"""Own one frozen application/caller run; never overwrite responses or replay questions."""
from datetime import datetime, timezone
from pathlib import Path
import json
import os
import re
import socket
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
assert not (HERE / 'requests').exists() and not (HERE / 'execution.json').exists()
assert os.environ.get('AGENT_LLM_API_KEY', '').strip(), 'Existing model key required in environment only'


def save(name, value):
    with (HERE / name).open('x', encoding='utf-8', newline='\n') as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write('\n')


with socket.socket() as sock:
    sock.settimeout(1)
    assert sock.connect_ex(('127.0.0.1', 18087)) != 0, 'Do not connect to another application'
env = {name: value for name, value in os.environ.items()
       if not re.match(r'^(AGENT_|SPRING_|TEXT2SQL_TEST_DB_)', name, re.I)
       and name.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')}
env['AGENT_LLM_API_KEY'] = os.environ['AGENT_LLM_API_KEY']
env['PSModulePath'] = str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/Modules')
args = ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(HERE / 'start_cold_jar.ps1')]
execution = dict(startedAtUtc=datetime.now(timezone.utc).isoformat(), launcherCommand=args,
                 clearedOverrides=True, questionReplay=False, completed=False)
process = None
java_pid = None
try:
    with (HERE / 'application.log').open('xb') as log:
        process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        execution['launcherPid'] = process.pid
        started = time.monotonic()
        while time.monotonic() - started < 60:
            assert process.poll() is None, 'Launcher/application exited; preserve log'
            content = (HERE / 'application.log').read_text(encoding='utf-8', errors='replace')
            pid_match = re.search(r'INFO\s+(\d+)\s+---', content)
            if pid_match:
                java_pid = int(pid_match.group(1))
            if 'Tomcat started on port 18087' in content:
                break
            time.sleep(.25)
        else:
            raise AssertionError('Startup observation timeout; do not start another process')
        assert java_pid
        execution['javaPid'] = java_pid
        caller = [sys.executable, str(ROOT / 'reports/demo-caller-binding-20261117/invoke_probe.py'), 'run']
        with (HERE / 'caller-run.log').open('xb') as output:
            completed = subprocess.run(caller, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
        execution['callerExitCode'] = completed.returncode
        execution['completed'] = completed.returncode == 0
        time.sleep(2)  # Permit already-finished local log writes, never replay a request.
finally:
    if java_pid is not None:
        stopped = subprocess.run(['taskkill', '/PID', str(java_pid), '/T', '/F'], capture_output=True)
        execution['ownedJavaStopExitCode'] = stopped.returncode
    if process is not None:
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            execution['launcherStillRunning'] = True
        execution['launcherExitCode'] = process.poll()
    with socket.socket() as sock:
        sock.settimeout(1)
        execution['portClosed'] = sock.connect_ex(('127.0.0.1', 18087)) != 0
    execution['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
    save('execution.json', execution)
assert execution['portClosed'] and execution['launcherExitCode'] is not None
if execution['completed']:
    with (HERE / 'caller-summary.log').open('xb') as output:
        summary = subprocess.run([sys.executable, str(ROOT / 'reports/demo-caller-binding-20261117/invoke_probe.py'), 'summarize'],
                                 cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW)
    save('summary-execution.json', dict(exitCode=summary.returncode))
    assert summary.returncode == 0, 'Summary failed; preserve all original responses'
print(json.dumps(execution))
