"""Archive completed local evidence and prepare an unsigned review packet."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
FOLDER=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'scripts'))
from eval_lib import load_eval_items
read=lambda p:json.loads(Path(p).read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
git=lambda *args:subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()
commit=git('rev-parse','HEAD')
assert commit.startswith('46efb8e')
assert not git('diff',commit,'--','src','pom.xml')
audit=read(FOLDER/'formal-contract-audit.json')
queries={i['name']:i['sql'] for i in read(FOLDER/'formal-contract-query-results.json')['queries']}
questions={q['id']:q for q in load_eval_items()}
packet=['# 原正式100题契约独立复核包（待外部填写）',
    '',f'候选生产提交：`{commit}`。编制日期：2026-11-19。',
    '', '**状态：PENDING。复核人：未填写。独立性说明：未填写。签字日期：未填写。**',
    '', '本包由实现AI整理，所有裁决留空。下面是已见原题参考审核，不是未见盲测；不得将AI自审当作独立审核。',
    '', '请未参与原题/Gold编写和本轮调参的人员逐题确认：统计对象/身份、事实全集、维度显示、时间参照与边界、NULL/零事实、单位、并列与输出形状。',
    '', '每题填写：保持原参考 / 接受修订并写理由 / 需要业务补充。SQL可运行不代表业务正确；诊断SQL只是候选，不能直接签收。',
    '', '禁止改原v2文件和历史分数。若形成新协议，另存版本、冻结差异及门槛后再测；不能跨参考版本宣称模型提升。',
    '', '| 汇总 | 值 |','|---|---:|','| 原eval题数 | 100 |','| AI标出的确定冲突 | 14 |',
    '| 外部已签收条数 | 0 |','| 新供应商调用 | 0 |','',
    '独立未见盲测另需外部人员持有尚未向实现方公开的题目和Gold。本包不满足该要求。','']
for index,item in enumerate(audit['items'],1):
    identity=item['id'];q=questions[identity]
    packet += [f'## {index}. {identity}', '', q['question'], '', '**原Gold（保持原样）**', '',
        '```sql',q['gold_sql'].strip(),'```','', '**AI审查提示（需独立核实）**','']
    packet += [('- '+issue['detail']) for issue in item['issues']] or ['未标出已证实冲突；仍须审阅，不等于自动认可。']
    if identity+'-contract' in queries:
        packet += ['','**诊断候选SQL（未签收；不进入原评分）**','','```sql',queries[identity+'-contract'],'```']
    if identity in {x['id'] for x in audit['items'] if 'single_turn_clarification_conflict' in x['confirmedConflictKinds']}:
        current=next(x for x in read(FOLDER/'clarifications.json')['items'] if x['id']==identity)
        packet += ['','**当前生产需要补充的信息**','']
        packet += ['- '+d['question'] for d in current['decisions'] if d['needsClarification']]
    packet += ['','**外部裁决：** 未填写。','**理由/证据/明确业务口径：** 未填写。','**复核人/日期：** 未填写。','']
packet_path=ROOT/'docs/review/FORMAL_CONTRACT_REVIEW_PACKET_20261119.md'
packet_path.parent.mkdir(exist_ok=True)
packet_path.write_text('\n'.join(packet),encoding='utf-8',newline='\n')

totals=Counter()
xmls=sorted((ROOT/'target/surefire-reports').glob('TEST-*.xml'))
for p in xmls:
    suite=ET.parse(p).getroot()
    for k in ('tests','failures','errors','skipped'):totals[k]+=int(suite.get(k,0))
assert dict(totals)==dict(tests=1185,failures=0,errors=0,skipped=0)
for name in ('maven','package','gold'):assert (FOLDER/(name+'.exit')).read_text(encoding='utf-8-sig').strip()=='0'
with zipfile.ZipFile(FOLDER/'engineering-logs.zip','x',compression=zipfile.ZIP_DEFLATED) as z:
    for p in xmls:z.write(p,'surefire/'+p.name)
    for p in sorted(FOLDER.glob('*.log')):z.write(p,p.name)
    for p in sorted(FOLDER.glob('*.exit')):z.write(p,p.name)

gold_path=next((FOLDER/'gold').glob('eval-*.json'));gold=read(gold_path)
assert gold['overall']['correct']==gold['overall']['total']==200
assert gold['meta']['gitCommit']==commit and not any(i['calls'] for i in gold['itemEvidence'])
impact={}
stable_keys=['ddlSha256','metricsSha256','valueHintsSha256','examplesSha256','tables','retrievalTables',
    'scores','expandedTables','evidenceOrder','columns']
for split in ('dev','eval'):
    before=read(ROOT/f'reports/clarification-candidate-dev-screen-20261119/prompt-capture-{split}.json')
    after=read(FOLDER/f'prompt-{split}.json')
    assert len(before['items'])==len(after['items'])==100
    assert after['sameProcessComparisons']==300 and after['sameProcessMismatches']==[]
    for a,b in zip(before['items'],after['items']):
        assert a['id']==b['id'] and a['promptSha256']!=b['promptSha256']
        assert all(a[k]==b[k] for k in stable_keys)
    impact[split]=dict(items=100,changedPrompts=100,stableContextItems=100,comparisons=300,mismatches=0,
        oldSystemChars=4409,newSystemChars=4740,oldProfileChars=187,newProfileChars=380,
        originalCaptureSha256=sha(ROOT/f'reports/clarification-candidate-dev-screen-20261119/prompt-capture-{split}.json'))
v=read(ROOT/'reports/extremum-projection-20261119/verification.json')
for p,h in v['originalDataSha256'].items():assert sha(ROOT/p)==h,p
jar=ROOT/'target/text2sql-agent-0.1.0-SNAPSHOT.jar'
with zipfile.ZipFile(jar) as z:
    for name in ('generation/PromptTemplate.class','retrieval/DatabaseSchemaReader.class'):
        p='com/text2sql/agent/'+name
        assert z.read('BOOT-INF/classes/'+p)==(ROOT/'target/classes'/p).read_bytes()
verification=dict(format='calendar-window-verification-v1',productionCommit=commit,
    sourceGitTree=git('rev-parse',commit+':src'),java=dict(totals),fullJavaRuns=1,targetedReruns=0,
    promptVersion='p20-calendar-window-v20',boundaryPassed=14,boundaryTotal=14,
    databaseDiagnostics=3,formalContractReviewItems=100,formalReadOnlyQueries=44,
    confirmedDistinctConflicts=14,conditionalContractCompliantScoreCeilingPercent=86,formalGatePercent=89,
    goldReport=str(gold_path.relative_to(FOLDER)).replace('\\','/'),goldCorrect=200,goldTotal=200,
    promptImpact=impact,externalProviderCalls=0,providerTokens=0,providerCostYuan=0,
    latestMeasuredDevCorrect=68,latestMeasuredFormalPercent=78,stageAccepted=False,stage3Deferred=True,
    independentReview=False,newPaidAccuracyMeasured=False,
    jar=str(jar.relative_to(ROOT)).replace('\\','/'),jarSha256=sha(jar),
    originalDataSha256=v['originalDataSha256'],reviewPacket=str(packet_path.relative_to(ROOT)).replace('\\','/'),
    reviewPacketSha256=sha(packet_path),artifactSha256={})
for p in sorted(FOLDER.rglob('*')):
    if p.is_file() and p.suffix in ('.py','.json','.sql','.zip') and p.name!='verification.json':
        verification['artifactSha256'][str(p.relative_to(FOLDER)).replace('\\','/')]=sha(p)
with (FOLDER/'verification.json').open('x',encoding='utf-8',newline='\n') as f:
    json.dump(verification,f,ensure_ascii=False,indent=2);f.write('\n')
print('ARCHIVED: Java1185, Gold200, boundary14, formal review100; provider0; review unsigned.')
