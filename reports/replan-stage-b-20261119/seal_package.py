"""Seal locally verified B evidence, not paid admission. Never reads unseen datasets."""
import difflib
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'scripts'))
import run_stage2_v3_comparison as frozen

def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,d):
    with Path(p).open('x',encoding='utf-8',newline='\n') as f: json.dump(d,f,ensure_ascii=False,indent=2); f.write('\n')
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT)

before=json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
old=json.loads((ROOT/'reports/replan-stage-a-20261119/source-baseline.json').read_text(encoding='utf-8'))
current={p.relative_to(ROOT).as_posix():sha(p) for base in ('src/main','src/test')
         for p in (ROOT/base).rglob('*') if p.is_file() and p.name!='application-local.yml'}
changed={p for p,v in current.items() if before['files'].get(p)!=v}
removed=set(before['files'])-set(current)
if removed: raise ValueError('Unexpected source deletion')
expected=changed|{'target/text2sql-agent-0.1.0-SNAPSHOT.jar'}
drift={p for p,v in old['protectedFiles'].items() if not (ROOT/p).is_file() or sha(ROOT/p)!=v}
if drift-expected: raise ValueError('Unexpected protected drift '+str(drift-expected))
preserved=[]
for record in old['initialTrackedDifferences']:
    p=ROOT/record['path']
    check=p.exists()==record['exists'] and (not p.exists() or sha(p)==record['sha256'])
    if not check: raise ValueError('Preexisting work changed '+str(p))
    preserved.append(dict(path=record['path'],preserved=True))
db=frozen.database_snapshot()
if db!=old['database']: raise ValueError('Business database identity changed')
if sha(ROOT/'pom.xml')!=before['pomSha256']: raise ValueError('Dependencies changed')
write(OUT/'integrity-verification.json',dict(protectedFiles=len(old['protectedFiles']),
    intendedProtectedChanges=sorted(drift),unchangedProtectedFiles=len(old['protectedFiles'])-len(drift),
    unexpectedDrift=[],preexistingTrackedChanges=preserved,database=db,databaseUnchanged=True,providerCalls=0))

patch=[]
for p in sorted(changed|{'scripts/replan_stage_b.py'}):
    tracked=subprocess.run(['git','cat-file','-e','HEAD:'+p],cwd=ROOT,capture_output=True).returncode==0
    old_text=git('show','HEAD:'+p).decode('utf-8').replace('\r\n','\n') if tracked else ''
    new_text=(ROOT/p).read_text(encoding='utf-8')
    patch.extend(difflib.unified_diff(old_text.splitlines(keepends=True),new_text.splitlines(keepends=True),
        fromfile='a/'+p if tracked else '/dev/null',tofile='b/'+p))
(OUT/'candidate.patch').open('x',encoding='utf-8',newline='\n').write(''.join(patch))
jar=ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar'
saved=ROOT/'.private/recovery/replan-b0-20261119/text2sql-agent-B-candidate.jar'
saved.parent.mkdir(parents=True,exist_ok=True)
if saved.exists(): raise ValueError('Never overwrite candidate JAR')
shutil.copyfile(jar,saved)
with zipfile.ZipFile(jar) as z:
    dependencies={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')}
with zipfile.ZipFile(ROOT/'.private/recovery/p21-ea5a8a4/text2sql-agent-p21.jar') as z:
    prior={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')}
if dependencies!=prior: raise ValueError('Bundled dependencies drifted')
write(OUT/'candidate-identity.json',dict(format='replan-stage-b-candidate-v1',baseCommit=before['head'],
    changedSources=sorted(changed),sourceFiles=current,pomSha256=sha(ROOT/'pom.xml'),
    candidateJarSha256=sha(jar),savedCandidateJar=str(saved),savedJarSha256=sha(saved),
    dependencies=dependencies,dependenciesUnchanged=True,
    compiledClasses={str(p.relative_to(ROOT)):sha(p) for folder in ('target/classes','target/test-classes')
                     for p in (ROOT/folder).rglob('*') if p.is_file()},
    stageAccepted=False,paidEvaluationAdmitted=False,providerCalls=0))
trees=[ET.parse(p).getroot() for p in (OUT/'full-build-final/surefire').glob('TEST-*.xml')]
counts={k:sum(int(t.get(k,0)) for t in trees) for k in ('tests','failures','errors','skipped')}
if counts!=dict(tests=1224,failures=0,errors=0,skipped=0): raise ValueError('Unexpected test result')
timings=json.loads((OUT/'timings-http-evidence').read_text(encoding='utf-8'))
summary=dict(finalMaven=counts,testSuites=len(trees),catalogBoundaryTests=38,
    protectedCatalogHttp=11,accessHttpChecks=36,costHttpScenarios=4,sessionHttpScenarios=3,
    timingScenarios=len(timings['scenarios']),firstCallExact=400,
    gold=json.loads((OUT/'gold-execution.json').read_text(encoding='utf-8')),
    providerCalls=0,providerTokens=0,providerCostYuan=0,
    status='READY_FOR_INDEPENDENT_REVIEW',notModelAccuracyOrP95Evidence=True)
write(OUT/'verification-summary.json',summary)
paths=[p for p in OUT.rglob('*') if p.is_file() and 'independent-review' not in p.parts]
paths += [ROOT/p for p in sorted(changed|{'scripts/replan_stage_b.py','docs/REPLAN_STAGE_B_20261119.md','.gitattributes'})]
write(OUT/'submission-1-manifest.json',dict(submission=1,files={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(paths)},providerCalls=0))
print(json.dumps(summary,ensure_ascii=False))
