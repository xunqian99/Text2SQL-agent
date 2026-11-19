"""Seal submission 5 after its single clean final build; public evidence and read-only DB only."""
from pathlib import Path
import difflib, hashlib, json, shutil, subprocess, sys, xml.etree.ElementTree as ET, zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = Path(__file__).resolve().parent
OUT = BASE / 'attempt-5'
sys.path.insert(0, str(ROOT / 'scripts'))
import run_stage2_v3_comparison as frozen

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(path, data):
    with Path(path).open('x', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, ensure_ascii=False, indent=2); f.write('\n')
def git(*args): return subprocess.check_output(['git', *args], cwd=ROOT)

before = json.loads((BASE / 'source-before.json').read_text(encoding='utf-8'))
prior = json.loads((ROOT / 'reports/replan-stage-a-20261119/source-baseline.json').read_text(encoding='utf-8'))
current = {p.relative_to(ROOT).as_posix(): sha(p) for folder in ('src/main', 'src/test')
           for p in (ROOT/folder).rglob('*') if p.is_file() and p.name != 'application-local.yml'}
changed = {p for p, value in current.items() if before['files'].get(p) != value}
assert not set(before['files']) - set(current), 'Unexpected source deletion'
assert sha(ROOT/'pom.xml') == before['pomSha256'], 'Dependencies changed'
preserved = []
for record in prior['initialTrackedDifferences']:
    p = ROOT / record['path']
    assert p.exists() == record['exists'] and (not p.exists() or sha(p) == record['sha256']), record['path']
    preserved.append(dict(path=record['path'], preserved=True))
drift = {p for p, value in prior['protectedFiles'].items() if not (ROOT/p).is_file() or sha(ROOT/p) != value}
amended_plan = 'docs/REVISED_IMPROVEMENT_PLAN_20261119.md'
assert sha(ROOT/amended_plan) == hashlib.sha256(git('show', '93c7d3b:'+amended_plan)).hexdigest(), 'Plan differs from explicit authorization commit'
assert not drift - changed - {'target/text2sql-agent-0.1.0-SNAPSHOT.jar', amended_plan}, sorted(drift-changed)
database = frozen.database_snapshot()
assert database == prior['database'], 'Business data changed'
jar = ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar'
with zipfile.ZipFile(jar) as z:
    dependencies = {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')}
    packaged = {n[len('BOOT-INF/classes/'):]: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('BOOT-INF/classes/') and not n.endswith('/')}
compiled = {p.relative_to(ROOT/'target/classes').as_posix(): sha(p) for p in (ROOT/'target/classes').rglob('*') if p.is_file()}
assert compiled == packaged, 'Packaged classes differ from current compiler output'
with zipfile.ZipFile(ROOT/'.private/recovery/p21-ea5a8a4/text2sql-agent-p21.jar') as z:
    expected = {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')}
assert dependencies == expected
saved = ROOT/'.private/recovery/replan-b0-20261119/text2sql-agent-B-candidate-5.jar'
assert not saved.exists(); shutil.copyfile(jar, saved)
write(OUT/'candidate-identity.json', dict(submission=5, baseCommit='54d227dc5943081a0c66786e7117909c21094b8e',
    authorizationCommit='93c7d3b', sourceFiles=current, changedSources=sorted(changed), pomSha256=sha(ROOT/'pom.xml'),
    jarSha256=sha(jar), savedCandidateJar=str(saved), savedJarSha256=sha(saved),
    dependencies=dependencies, dependencyCount=len(dependencies), dependenciesUnchanged=True,
    packagedClassesMatch=True, compiledClasses={p.relative_to(ROOT).as_posix(): sha(p)
        for folder in ('target/classes', 'target/test-classes') for p in (ROOT/folder).rglob('*') if p.is_file()},
    database=database, preexistingTrackedChanges=preserved, unexpectedProtectedDrift=[],
    protectedFileCount=len(prior['protectedFiles']), authorizedProtectedChanges=sorted(drift),
    providerCalls=0, stageBAccepted=False, paidEvaluationAdmitted=False))

patch = []
for name in sorted(changed | {'scripts/replan_stage_b.py'}):
    old = before['files'].get(name)
    original = git('show', '54d227d:'+name).decode('utf-8').replace('\r\n', '\n') if old else ''
    new = (ROOT/name).read_text(encoding='utf-8')
    patch.extend(difflib.unified_diff(original.splitlines(keepends=True), new.splitlines(keepends=True),
                 fromfile='a/'+name if old else '/dev/null', tofile='b/'+name))
(OUT/'candidate.patch').open('x', encoding='utf-8', newline='\n').write(''.join(patch))
trees = [ET.parse(p).getroot() for p in (OUT/'full-build-final/surefire').glob('TEST-*.xml')]
counts = {k: sum(int(t.get(k, 0)) for t in trees) for k in ('tests', 'failures', 'errors', 'skipped')}
assert counts == dict(tests=1479, failures=0, errors=0, skipped=0), counts
for t in trees:
    assert (ROOT/'target/test-classes'/Path(t.get('name').replace('.', '/')+'.class')).is_file(), 'Stale XML'
capture = json.loads((OUT/'first-call-invariance.json').read_text(encoding='utf-8'))
assert capture['exactMessagesAndRetrievalEqual'] and sum(c['total'] for c in capture['comparisons']) == 400
gold = json.loads((OUT/'gold-execution.json').read_text(encoding='utf-8'))
assert sum(g['correct'] for g in gold['runs']) == 400
for name in ('catalog-http', 'access-http', 'cost-http', 'session-http', 'timings-http'):
    assert json.loads((OUT/name/'execution.json').read_text(encoding='utf-8'))['exitCode'] == 0
summary = dict(status='READY_FOR_INDEPENDENT_REVIEW', submission=5, finalMaven=counts,
    testSuites=len(trees), catalogSendBoundaryTests=246, catalogValidationTests=47,
    exactFirstCalls=400, goldExecutable=400, protectedHttpProbeGroups=5,
    providerCalls=0, providerTokens=0, providerCostYuan=0,
    notModelAccuracyOrP95Evidence=True, paidEvaluationAdmitted=False)
write(OUT/'verification-summary.json', summary)
paths = [BASE/'seal_attempt5.py'] + [p for p in OUT.rglob('*') if p.is_file()]
paths += [ROOT/p for p in sorted(changed | {'scripts/replan_stage_b.py'})]
paths += [ROOT/p for p in (amended_plan, 'docs/REPLAN_STAGE_B_CONTINUATION_AUTHORIZATION_20261119.md', 'docs/REPLAN_STAGE_B_CONTINUATION_PROPOSAL_20261119.md')]
write(OUT/'submission-5-manifest.json', dict(submission=5, files={p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(paths)}, providerCalls=0))
print(json.dumps(summary))
