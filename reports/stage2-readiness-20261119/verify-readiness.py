"""Offline verification of actual peak rejection and remaining phase requirements."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[2]
FOLDER=Path(__file__).resolve().parent
read=lambda p:json.loads(Path(p).read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
git=lambda *a:subprocess.check_output(['git',*a],cwd=ROOT,text=True).strip()

def verify(v):
    assert git('rev-parse',v['productionCommit']+':src')==v['sourceGitTree']
    for p,h in v['evidenceSha256'].items():assert sha(ROOT/p)==h,p
    assert sha(ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar')==v['jarSha256']
    with zipfile.ZipFile(FOLDER/'live-rejection-logs.zip') as z:
        peak=z.read('peak-rejection.log').decode('utf-8-sig')
        protocol=z.read('protocol-rejection.log').decode('utf-8-sig')
        assert z.read('peak-rejection.exit').decode('utf-8-sig').strip()=='1'
        assert z.read('protocol-rejection.exit').decode('utf-8-sig').strip()=='1'
    assert '原因=PEAK_HOURS' in peak
    assert 'EvalRunner.runEvaluation(EvalRunner.java:121)' in peak
    assert '2026-11-19T09:54:09.' in peak and '2026-11-19T12:00+08:00' in peak
    assert '开始评估：' not in peak and '进度 ' not in peak
    assert 'Cannot validate argument' in protocol and 'p20-calendar-window-v20' in protocol
    assert not list(FOLDER.rglob('*.jsonl')) and not (FOLDER/'unexpected-report').exists()
    assert not (FOLDER/'unexpected-formal').exists()
    packet=(ROOT/'docs/review/FORMAL_CONTRACT_REVIEW_PACKET_20261119.md').read_text(encoding='utf-8')
    assert len(re.findall(r'^## \d+\. T\d-\d{3}$',packet,re.M))==100
    assert packet.count('**外部裁决：** 未填写。')==v['pendingIndependentDecisions']==100
    old=read(ROOT/'reports/stage2-dimension-display-regression-20261118/summary.json')
    assert old['meanAccuracyPercent']==78 and old['passedEvidenceAudit'] is False and old['errors']
    assert not v['goalAchieved'] and not v['stage2Accepted'] and v['stage3Deferred']
    assert v['providerCalls']==v['providerTokens']==v['providerCostYuan']==0
    assert v['fullJavaReruns']==v['goldReruns']==0
    print('PASS: actual peak and protocol rejection2/2, provider0, decisions pending100, stage2Accepted=false')

if '--assemble' in sys.argv:
    assert not (FOLDER/'verification.json').exists()
    base=read(ROOT/'reports/calendar-window-20261119/verification.json')
    jar=ROOT/base['jar']
    assert sha(jar)==base['jarSha256']
    classes={}
    with zipfile.ZipFile(jar) as z:
        for p in ('evaluation/EvalRunner.class','budget/DeepSeekOffPeakPolicy.class',
                  'generation/LlmSqlGenerator.class','budget/BudgetHttpRequestFactory.class'):
            entry='com/text2sql/agent/'+p
            data=z.read('BOOT-INF/classes/'+entry)
            assert data==(ROOT/'target/classes'/entry).read_bytes()
            classes[entry]=hashlib.sha256(data).hexdigest()
    with zipfile.ZipFile(FOLDER/'live-rejection-logs.zip','x',compression=zipfile.ZIP_DEFLATED) as z:
        for name in ('peak-rejection.log','peak-rejection.exit','protocol-rejection.log','protocol-rejection.exit'):
            z.write(FOLDER/name,name)
    source_paths=[
        'reports/calendar-window-20261119/verification.json',
        'reports/calendar-window-20261119/formal-contract-audit.json',
        'docs/review/FORMAL_CONTRACT_REVIEW_PACKET_20261119.md',
        'docs/STAGE2_REGRESSION_PROTOCOL_20261117.md',
        'reports/customer-identity-http-20261117/summary.json',
        'reports/output-contract-http-20261117/summary.json',
        'reports/stage2-dimension-display-regression-20261118/summary.json',
        'reports/demo-http-20-20261117-v2/summary.json',
        'data/holdout/v1/README.md',
        'reports/stage2-readiness-20261119/live-rejection-logs.zip',
        'reports/stage2-readiness-20261119/verify-readiness.py']
    v=dict(format='stage2-readiness-v1',productionCommit=git('rev-parse','HEAD'),
        sourceGitTree=git('rev-parse','HEAD:src'),jarSha256=sha(jar),packagedClassSha256=classes,
        actualPeakRejected=True,actualPeakRejectionBeijing='2026-11-19T09:54:09+08:00',
        actualUnregisteredProtocolRejected=True,rejectionsPassed=2,rejectionsTotal=2,
        credentialSource='fixed-public-placeholder; no user key used',
        providerCalls=0,providerTokens=0,providerCostYuan=0,
        fullJavaReruns=0,goldReruns=0,reusedJavaPassed=1185,reusedGoldPassed=200,
        pendingIndependentDecisions=100,independentBlindTestAvailable=False,
        latestMeasuredFormalPercent=78,formalMinimumPercent=89,
        originalFormalAuditFailed=True,conditionalContractCompliantCeilingPercent=86,
        goalAchieved=False,stage2Accepted=False,stage3Deferred=True,
        nextDependency='Independent business adjudication and unseen evaluation source; same AI cannot certify itself.',
        evidenceSha256={p:sha(ROOT/p) for p in source_paths})
    verify(v)
    (FOLDER/'verification.json').write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
else:
    verify(read(FOLDER/'verification.json'))
