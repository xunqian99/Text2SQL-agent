"""Read the isolated synthetic Java fixture; do not run Java or controllers."""
from __future__ import annotations

import ast
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
DRAFT = ROOT / 'reports/replan-stage-d-20261119/post-interruption-feasibility-20261120'
PROBE = DRAFT / 'real-java-probe'
EVIDENCE = PROBE / 'check-4'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_bytes())


def main():
    result = read(EVIDENCE / 'result.json')
    assert sha(EVIDENCE / 'result.json') == 'bcaf5ac72c102f7e6c2b84ecf0e11f9c5938eee45c986e680f98af8148e4c47c'
    assert result['passed'] and len(result['checks']) == 16 and all(result['checks'].values())
    launcher = PROBE / 'probe.py'
    dependency = DRAFT / 'loopback_accounting_probe.py'
    ast.parse(launcher.read_text(encoding='utf-8'))
    assert launcher.read_bytes() == (EVIDENCE / 'launcher-source.py.txt').read_bytes()
    assert dependency.read_bytes() == (EVIDENCE / 'imported-loopback-probe-source.py.txt').read_bytes()
    assert result['sourceSha256'] == sha(launcher)
    assert result['probeSourceSha256'] == sha(dependency)
    budget = result['javaBudget']
    answer_path = EVIDENCE / 'http-answer.json'
    answer_record = read(answer_path)
    answer = json.loads((EVIDENCE / 'http-answer.body.bin').read_bytes())
    assert answer_record['responseSha256'] == sha(EVIDENCE / 'http-answer.body.bin')
    assert answer_record['body'] == answer and answer['budget'] == budget
    assert result['httpStatus'] == answer_record['httpStatus'] == 504
    assert answer['status'] == 'REQUEST_TIMEOUT' and not answer.get('sql')
    assert budget['timeoutMs'] == 15000 and budget['modelCalls'] == budget['unknownUsageCalls'] == 1
    assert budget['elapsedMs'] == 15001 and budget['stage'] == 'MODEL'
    assert budget['termination'] == 'DEADLINE_EXCEEDED'
    assert result['loopbackPosts'] == result['callbacks'] == 1
    assert 15000 <= result['wallMs'] <= 16000 and result['accountingGraceSeconds'] == 1
    initial = read(EVIDENCE / 'quota-initial.json')
    pending = read(EVIDENCE / 'quota-before-send.json')
    returned = read(EVIDENCE / 'quota-at-http-return.json')
    final = read(EVIDENCE / 'quota-after-drain.json')
    assert read(EVIDENCE / 'synthetic-cost-quota.json') == final
    identity = result['candidateUnknownHoldId']
    before_hold = pending['holds'][identity]
    after_hold = final['holds'][identity]
    assert before_hold['status'] == 'PENDING' and after_hold['status'] == 'UNKNOWN'
    assert {k: v for k, v in before_hold.items() if k != 'status'} == {k: v for k, v in after_hold.items() if k != 'status'}
    assert after_hold == returned['holds'][identity] == result['candidateUnknownHold']
    assert after_hold['requestId'] == budget['requestId'] and after_hold['callNumber'] == 1
    assert final['knownCalls'] == final['knownCostYuan'] == 0 and len(final['holds']) == 1
    assert not initial['holds'] and initial['knownCalls'] == initial['knownCostYuan'] == 0
    assert all(initial[k] == pending[k] == final[k] for k in ('version', 'budgetId', 'pricingIdentity', 'limitYuan', 'reservationYuan'))
    permit = read(EVIDENCE / 'synthetic-permit.json')
    completion = read(EVIDENCE / 'synthetic-completion.json')
    raw = EVIDENCE / 'synthetic-upstream.body.bin'
    assert completion['token'] == permit['token']
    assert completion['responseBodySha256'] == sha(raw) and completion['responseBytes'] == raw.stat().st_size
    assert completion['responseJson'] == read(raw) and completion['responseComplete']
    assert completion['responseJson']['usage'] == result['syntheticUsage']
    usage = result['syntheticUsage']
    assert usage['prompt_tokens'] == usage['prompt_cache_hit_tokens'] + usage['prompt_cache_miss_tokens'] == 10
    assert usage['completion_tokens'] == 5 and usage['total_tokens'] == 15
    assert all(result[k] is False for k in ('productionMonitorIntegrated', 'productionLedgerUsed', 'tlsOrPeakBoundaryTested', 'resourceExhaustionTested', 'productionAdmission'))
    assert result['supplierRequests'] == result['realCredentialsRead'] == 0
    command = read(EVIDENCE / 'command.json')
    assert command['allCredentialStringsSynthetic'] and '--spring.config.location=classpath:/application.yml' in command['arguments']
    assert '--agent.llm.api-key=synthetic-local-only' in command['arguments']
    assert any(arg.startswith('--agent.llm.base-url=http://127.0.0.1:') for arg in command['arguments'])
    assert read(EVIDENCE / 'environment-policy.json')['credentialsRead'] == 0
    paths = [path for path in EVIDENCE.iterdir() if path.is_file() and path.suffix != '.log']
    sources = {path.relative_to(ROOT).as_posix(): sha(path) for path in (*paths, launcher, dependency)}
    output = dict(
        format='independent-real-java-synthetic-evidence-review-v1', sourceHashes=sources,
        suppliedScenarioCount=1, suppliedAssertions=16, suppliedAssertionsTrue=16,
        independentJavaOrNetworkReruns=0,
        sourceOriginalCopiesMatchCurrent=True, syntheticRawResponseAndHttpHashesMatch=True,
        pendingToUnknownIdentityPreserved=True, knownCallsAndKnownCostRemainZero=True,
        observedHttpStatus=504, observedHttpWallMs=result['wallMs'], observedBudgetElapsedMs=15001,
        observedModelStageMs=budget['stageMs']['MODEL'], observedModelCalls=1,
        observedPromptTokens=10, observedCompletionTokens=5, usageIsSyntheticOnly=True,
        noLateSqlInOriginalHttp=True,
        lateAfterHttpAssertion='Producer monotonic timestamps checked in its 16 assertions; no independent rerun',
        actual768ArtifactUnchangedAssertion='Producer reported; this independent review did not reread actual batch',
        frozenJarSha256=result['frozenJarSha256'],
        productionMonitorTested=False, sendBlockingDeadlineTested=False,
        tlsAndPeakTested=False, resourceLimitsTested=False, actual105SecondGraceTested=False,
        productionAccepted=False, paidAuthorized=False, canExecute=False,
        actualBatchReadOrWrittenByThisReview=False, old68AnswersOrSqlParsed=False,
        unseenRead=False, supplierCallsAdded=0, realKeyReadsAdded=0)
    with (OUT / 'real-java-evidence-review.json').open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(output, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps(dict(suppliedScenarioCount=1, suppliedAssertions=16, sourceCopiesMatch=True,
                          originalHoldPreserved=True, independentReruns=0, productionAccepted=False)))


if __name__ == '__main__':
    main()
