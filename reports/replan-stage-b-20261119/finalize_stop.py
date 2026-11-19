"""Seal the B stop and restored A source. No model, no sealed dataset reads."""
from pathlib import Path
import hashlib,json,subprocess,sys,xml.etree.ElementTree as ET,zipfile

ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'scripts'))
import run_stage2_v3_comparison as frozen

def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,d):
    with Path(p).open('x',encoding='utf-8',newline='\n') as f: json.dump(d,f,ensure_ascii=False,indent=2); f.write('\n')

before=json.loads((OUT/'source-before.json').read_text(encoding='utf-8'))
prior=json.loads((ROOT/'reports/replan-stage-a-20261119/source-baseline.json').read_text(encoding='utf-8'))
restoration=json.loads((OUT/'restoration-plan.json').read_text(encoding='utf-8'))
review=json.loads((OUT/'independent-review/decision.json').read_text(encoding='utf-8'))
assert review['formalSubmissionsUsed']==2 and review['decision']=='REJECTED_STOP_CURRENT_B_ROUND'
files={p.relative_to(ROOT).as_posix():sha(p) for folder in ('src/main','src/test')
       for p in (ROOT/folder).rglob('*') if p.is_file() and p.name!='application-local.yml'}
assert files==before['files'], 'Restored source differs from A'
assert sha(ROOT/'pom.xml')==before['pomSha256']
preserved=[]
for record in prior['initialTrackedDifferences']:
    p=ROOT/record['path']; same=p.exists()==record['exists'] and (not p.exists() or sha(p)==record['sha256'])
    assert same,record['path']; preserved.append(dict(path=record['path'],preserved=True))
db=frozen.database_snapshot(); assert db==prior['database']
jar=ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar'
with zipfile.ZipFile(jar) as z:
    deps={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')}
    classes={p.relative_to(ROOT/'target/classes').as_posix():sha(p) for p in (ROOT/'target/classes').rglob('*') if p.is_file()}
    jar_classes={n[len('BOOT-INF/classes/'):]:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('BOOT-INF/classes/') and not n.endswith('/')}
    assert jar_classes==classes,'Packaged classes do not match rebuilt classes'
with zipfile.ZipFile(ROOT/'.private/recovery/p21-ea5a8a4/text2sql-agent-p21.jar') as z:
    expected={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')}
assert deps==expected
candidate=json.loads((OUT/'attempt-2/candidate-identity.json').read_text(encoding='utf-8'))
saved=ROOT/'.private/recovery/replan-b0-20261119/rejected-compiled-2'
checks={p:sha(saved/p.replace('\\','/').removeprefix('target/'))==value for p,value in candidate['compiledClasses'].items()}
assert all(checks.values())
active=[]; stale=[]
for p in (OUT/'restored-baseline-build/surefire').glob('TEST-*.xml'):
    tree=ET.parse(p).getroot(); name=tree.get('name')
    if (ROOT/'target/test-classes'/Path(name.replace('.','/')+'.class')).is_file(): active.append((p,tree))
    else: stale.append(name)
counts={key:sum(int(t.get(key,0)) for _,t in active) for key in ('tests','failures','errors','skipped')}
assert counts==dict(tests=1186,failures=0,errors=0,skipped=0)
http=json.loads((OUT/'restored-baseline-http-evidence/summary.json').read_text(encoding='utf-8'))
assert http['passed'] and http['checks']==36 and http['externalProviderCalls']==0
protected_drift=[p for p,v in prior['protectedFiles'].items() if not (ROOT/p).is_file() or sha(ROOT/p)!=v]
assert set(protected_drift)=={'target/text2sql-agent-0.1.0-SNAPSHOT.jar'}
write(OUT/'restored-baseline-identity.json',dict(sourceEqualsAcceptedA=True,sourceBaselineCommit=before['head'],
    sourceChecks=len(files),sourceFiles=files,pomSha256=sha(ROOT/'pom.xml'),rebuiltJarSha256=sha(jar),
    rebuiltJarDifferentBytesFromHistoricalP21=True,rebuiltJarClassesMatchCompilerOutput=True,
    dependencyCount=len(deps),dependenciesUnchanged=True,dependencies=deps,database=db,
    databaseUnchanged=True,preexistingTrackedChanges=preserved,
    originalProtectedFiles=len(prior['protectedFiles']),unchangedProtectedFiles=len(prior['protectedFiles'])-1,
    authorizedArtifactChangeOnly=protected_drift,
    archivedRejectedCompiledChecks=len(checks),archivedRejectedCompiledPassed=sum(checks.values()),
    testSuites=len(active),tests=counts,staleXmlExcludedFromCurrentBuildCounts=stale,
    staleXmlNote='Raw report directory retained historical candidate XML; missing active class excludes its 47 cases. No old case counted as a restored-baseline run.',
    protectedHttpChecks=36,providerCalls=0,providerTokens=0,providerCostYuan=0))
write(OUT/'checkpoint.json',dict(format='replan-stage-b-stop-v1',stageAExitAccepted=True,stageBExitAccepted=False,
    b0Established=False,currentRoundStopped=True,stopReason='B independent review allowance exhausted at 2/2 with unresolved B-02',
    formalSubmissionsUsed=2,formalSubmissionsMaximum=2,thirdSubmissionAllowed=False,
    mayAdvanceC=False,mayAdvanceD=False,paidEvaluationAdmitted=False,overallGoalCompleted=False,
    productionAndTestsRestoredTo=before['head'],restoredSourceChecks=len(files),
    rejectedCandidateCommit=restoration['rejectedCandidateCommit'],
    independentDecision='reports/replan-stage-b-20261119/independent-review/decision.json',
    independentSubmission1=dict(passed=93,total=101,failed=8),independentSubmission2=dict(passed=126,total=134,failed=8),
    restoredBaselineTests=counts,restoredProtectedHttpChecks=36,
    originalUcResumed=False,sealedQuestionsRead=False,stage3PerformanceExperimentsResumed=False,
    providerCalls=0,providerTokens=0,providerCostYuan=0))
print(json.dumps(dict(status='STOPPED_RESTORED_A',sourceChecks=len(files),tests=counts,http=36,providerCalls=0)))
