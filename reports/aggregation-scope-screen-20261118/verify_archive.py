"""Read-only verification of a rejected development candidate; never sends requests."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

folder=Path(__file__).resolve().parent; root=folder.parents[1]
os.chdir(root); sys.path.insert(0,str(folder))
seal=json.loads((folder/'evidence-manifest.json').read_text(encoding='utf-8-sig'))
for item in seal['artifacts']:
    assert hashlib.sha256((root/item['path']).read_bytes()).hexdigest()==item['sha256'], item['path']
spec=importlib.util.spec_from_file_location('scope_archive_auditor',folder/'audit_evidence.py')
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
actual=module.summarize(folder)
expected=json.loads((folder/'summary.json').read_text(encoding='utf-8-sig'))
assert actual==expected, 'Recomputed summary differs'
assert actual['passedEvidenceAudit'] and not actual['candidateForConfirmation'] and not actual['stageAccepted']
assert [actual['groups'][arm]['correct'] for arm in ('p15','p16')]==[69,68]
manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8-sig'))
source='src/main/java/com/text2sql/agent/generation/PromptTemplate.java'
assert (folder/'p15-PromptTemplate.java').read_bytes()==subprocess.check_output(['git','show',manifest['baselineSourceCommit']+':'+source])
assert hashlib.sha256((folder/'p16-PromptTemplate.java').read_bytes()).hexdigest()==manifest['protectedSha256'][source]
assert json.loads((folder/'credential-scan.json').read_text(encoding='utf-8-sig'))['knownCredentialMatches']==0
shutdown=json.loads((folder/'shutdown.json').read_text(encoding='utf-8-sig'))
assert shutdown['listenerCount']==shutdown['matchingJavaProcessCount']==0
rollback=json.loads((folder/'rollback-verification.json').read_text(encoding='utf-8-sig'))
assert rollback['defaultPromptVersion']=='p15-entity-coverage-v15'
assert rollback['java']==dict(tests=861,failures=0,errors=0,skipped=0,suites=80)
print(json.dumps(dict(archiveAudit='PASS',scores=[69,68],candidateForConfirmation=False,stageAccepted=False,
                      defaultRestoredTo='p15',unresolvedUsageCalls=sum(g['unresolvedUsageCalls'] for g in actual['groups'].values()),
                      knownCostLowerBoundYuan=sum(g['fullKnownCostYuan'] for g in actual['groups'].values()))))
