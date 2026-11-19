"""Seal B0 only after independent ACCEPT_B0_ONLY; never grants paid admission."""
from pathlib import Path
import hashlib, json, shutil, subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = Path(__file__).resolve().parent
OUT = BASE/'attempt-5'
REVIEW = BASE/'independent-review/submission-5'
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as f:
        json.dump(value, f, ensure_ascii=False, indent=2); f.write('\n')

decision = json.loads((REVIEW/'decision.json').read_text(encoding='utf-8-sig'))
assert decision['decision'] == 'ACCEPT_B0_ONLY', 'Independent B exit not accepted'
identity = json.loads((OUT/'candidate-identity.json').read_text(encoding='utf-8'))
manifest = json.loads((OUT/'submission-5-manifest.json').read_text(encoding='utf-8'))
checked = {}
for group, files in [('submission', manifest['files']), ('source', identity['sourceFiles']), ('compiled', identity['compiledClasses'])]:
    for name, wanted in files.items():
        assert sha(ROOT/name) == wanted, (group, name)
    checked[group] = len(files)
jar = ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar'
assert sha(jar) == identity['jarSha256'] == sha(identity['savedCandidateJar'])
saved = ROOT/'.private/recovery/replan-b0-20261119/text2sql-agent-B0.jar'
assert not saved.exists(); shutil.copyfile(jar, saved)
# Keep compiler output available for future offline paired checks even after Maven clean.
compiled_root = ROOT/'.private/recovery/replan-b0-20261119/accepted-compiled-B0'
assert not compiled_root.exists()
for name, wanted in identity['compiledClasses'].items():
    dest = compiled_root/Path(name).relative_to('target'); dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT/name, dest); assert sha(dest) == wanted
write(BASE/'b0-identity.json', dict(status='ACCEPTED_LOCAL_B0_ONLY', selectedSubmission=5,
    candidateSourceCommit='37017182d67a1fba7d16b399cc616565a784aac0', jarSha256=sha(saved),
    savedJar=str(saved), compiledSnapshot=str(compiled_root), verifiedCounts=checked,
    independentDecision='reports/replan-stage-b-20261119/independent-review/submission-5/decision.json',
    independentDecisionSha256=sha(REVIEW/'decision.json'), candidateIdentitySha256=sha(OUT/'candidate-identity.json'),
    firstCallCaptureFiles={p.name:sha(p) for p in OUT.glob('messages-B-*.json')},
    database=identity['database'], dependencyCount=identity['dependencyCount'],
    allowedNextWork='C1 free local preparation from this B0',
    paidEvaluationAdmitted=False, stage2Accepted=False, stage3Resumed=False,
    modelAccuracyImprovementProven=False, p95ImprovementProven=False,
    providerCalls=0, providerTokens=0, providerCostYuan=0))
checkpoint = json.loads((BASE/'checkpoint.json').read_text(encoding='utf-8'))
write(OUT/'pre-acceptance-checkpoint.json', checkpoint)
checkpoint.update(status='ACCEPTED_LOCAL_B0_ONLY', stageBExitAccepted=True, b0Established=True,
    selectedSubmission=5, candidateSourceCommit='37017182d67a1fba7d16b399cc616565a784aac0',
    formalSubmissionsUsed=5, independentDecision='reports/replan-stage-b-20261119/independent-review/submission-5/decision.json',
    mayAdvanceC=True, mayAdvanceD=False, pendingReview=None,
    paidEvaluationAdmitted=False, overallGoalCompleted=False,
    b0Identity='reports/replan-stage-b-20261119/b0-identity.json')
(BASE/'checkpoint.json').write_text(json.dumps(checkpoint, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
print(json.dumps(dict(status='ACCEPTED_LOCAL_B0_ONLY', checked=checked, jarSha256=sha(saved), providerCalls=0)))
