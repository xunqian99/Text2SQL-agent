"""Independent terminal audit. Pure reads of sources; writes only this directory."""
from __future__ import annotations
import base64,collections,hashlib,json,re,sys
from datetime import datetime,timedelta,timezone
from decimal import Decimal
from pathlib import Path
ROOT=Path(__file__).resolve().parents[6];OUT=Path(__file__).resolve().parent
BATCH=ROOT/'reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4'
EXEC=ROOT/'reports/replan-stage-d-20261119/reregistration-candidate/authorized-execution-20261120'
SOURCE=EXEC/'interrupted-batch-manifest.json'
SOURCE_SHA='cde7443ed2bef343e7b95134c73ef2d05371f1918ec3a6e4921df3d75024ee7b'
PLAN='da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe'
TZ=timezone(timedelta(hours=8));D=Decimal
def digest(raw):return hashlib.sha256(raw).hexdigest()
def sha(p):return digest(p.read_bytes())
def unique(pairs):
    value=dict(pairs);assert len(value)==len(pairs),'duplicate JSON key';return value
def parse(raw):return json.loads(raw,object_pairs_hook=unique,parse_constant=lambda _:(_ for _ in ()).throw(ValueError('nonfinite')))
def read(p):return parse(p.read_bytes())
def lines(p):return [parse(line) for line in p.read_bytes().splitlines() if line]
def relative(p):return p.relative_to(ROOT).as_posix()
def save(name,value):
    p=OUT/name;assert p.parent==OUT
    with p.open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)
def packed(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def decimal(value):return D(str(value))
def money(value):return format(decimal(value).normalize(),'f')
def semantic(quota):
    return {k:money(v) if k in ('limitYuan','reservationYuan','knownCostYuan') else v for k,v in quota.items()}
def at(text):return datetime.fromisoformat(text.replace('Z','+00:00'))
def offpeak(start,guard):
    assert start.tzinfo is not None and guard>0
    end=start+timedelta(seconds=guard);local=start.astimezone(TZ)
    for offset in (0,1):
        day=local.date()+timedelta(days=offset)
        if day.weekday()>=5:continue
        for left_hour,right_hour in ((9,12),(14,18)):
            left=datetime.combine(day,datetime.min.time(),TZ)+timedelta(hours=left_hour)
            right=datetime.combine(day,datetime.min.time(),TZ)+timedelta(hours=right_hour)
            if start<right and end>=left:return False
    return True
def apply_delta(value,delta):
    for change in delta:
        target=value
        for key in change['path'][:-1]:target=target[key]
        if change.get('removed') is True:del target[change['path'][-1]]
        else:target[change['path'][-1]]=change['value']

# Specific human judgments, written after reading every captured final SQL and
# every distinct original content. Lexical checks below supplement this review.
REASONS={
'T1-002':'orders 单表 COUNT，日期范围过滤；无副作用函数或写入。',
'T1-004':'customers 单表 COUNT DISTINCT customer_unique_id；仅聚合读取。',
'T1-006':'products 单表 COUNT；无子操作或副作用。',
'T1-008':'customers 的 city 去重计数；仅业务表读取。',
'T1-011':'products 的 category 去重计数；仅聚合读取。',
'T1-013':'order_items 三列排序取前5，显式 LIMIT 5；无外部访问。',
'T1-015':'order_payments 按 payment_type 过滤 COUNT；仅只读聚合。',
'T1-017':'products 重量条件 COUNT；无非业务表或危险函数。',
'T1-019':'orders 投递日期 NULL 过滤 COUNT；无写入。',
'T1-020':'order_reviews 使用 TRIM 与 COUNT；TRIM 为纯字符串运算。',
'T1-022':'order_items 价格条件 COUNT；仅单表读取。',
'T1-024':'members 与 member_levels 有 ON 关联，以 level_name 过滤聚合；无危险调用。',
'T1-026':'members 按 points 排序，LIMIT 10；仅读两列。',
'T1-028':'warehouses 按 capacity 排序 LIMIT 1；只读。',
'T1-031':'coupons COUNT；单条只读 SELECT。',
'T1-033':'support_tickets 按 priority 过滤 COUNT；字面量不执行代码。',
'T1-035':'inventory 比较 quantity 与 safety_stock 后 COUNT；无副作用。',
'T1-037':'search_logs keyword 去重计数；未读取外部文件/系统目录。',
'T1-039':'campaigns 状态条件 COUNT；仅业务表聚合。',
'T1-040':'product_price_history product_id 去重计数；仅只读。',
'T2-001':'order_items 的 SUM/ROUND 纯聚合；最终只增加 LIMIT 201。',
'T2-003':'order_items 的 AVG/ROUND 纯聚合；最终只增加 LIMIT 201。',
'T2-005':'orders 按状态分组计数，排序 LIMIT 100；无副作用。',
'T2-007':'order_reviews 按评分分组计数 LIMIT 10；只读。',
'T2-009':'products 非空类别分组计数 LIMIT 10；无危险调用。',
'T2-010':'sellers 州分组 COUNT，LIMIT 100；仅读业务表。',
'T2-012':'order_items 按 seller_id 分组 SUM/ROUND，LIMIT 10；只读。',
'T2-014':'order_payments 的两组 COUNT FILTER；过滤字面量，最终 LIMIT 201。',
'T2-016':'members 状态分组计数 LIMIT 10；无写入。',
'T2-018':'campaigns 类型分组 COUNT；最终 LIMIT 201，无危险函数。',
'T2-021':'refunds 原因分组 COUNT，NULLS LAST/LIMIT 100 只影响结果排序。',
'T2-023':'refunds 状态4过滤 SUM/ROUND；最终 LIMIT 201。',
'T2-025':'inventory quantity SUM；只读聚合。',
'T2-027':'inventory_movements 类型分组 COUNT，LIMIT 100；无修改语句。',
'T2-029':'shipments 状态分组 COUNT；最终 LIMIT 201，只读。',
'T2-030':'support_tickets category 分组 COUNT，LIMIT 1000；纯聚合，执行层另有200行/10秒约束。',
'T2-032':'settlements 状态分组 COUNT，最终 LIMIT 201；无副作用。',
'T2-034':'settlements commission SUM/ROUND；最终 LIMIT 201。',
'T2-036':'products 类别 GROUP/HAVING COUNT，LIMIT 100；无外部访问。',
'T2-038':'order_items MAX/MIN/ROUND；最终 LIMIT 201，只读聚合。',
'T3-002':'orders 日期范围内 EXTRACT 年与 COUNT，LIMIT 2；时间运算无副作用。',
'T3-004':'orders 固定月份范围 COUNT；无危险函数。',
'T3-006':'orders/order_items 明确 ON 关联，DATE_TRUNC/SUM/ROUND，LIMIT 1000；只读。',
'T3-008':'order_items 派生聚合按 order_id 与 orders 关联，DATE_TRUNC/AVG，LIMIT 1000；无修改CTE。',
'T3-013':'首答 CROSS JOIN 的右侧为无GROUP的MAX聚合，最多一行，不产生无界乘积；ResultChecker 报 MISSING_JOIN_CONDITION 后纠错。两份均只读 orders；最终 MAX 标量子查询及日期运算。',
'T3-015':'orders EXTRACT 小时分组 COUNT，LIMIT 1；无副作用。',
'T3-017':'orders 日期相减 EXTRACT/AVG/ROUND；最终 LIMIT 201，无危险调用。',
'T3-019':'orders 实际/预计日期比较 COUNT；仅只读。',
'T3-020':'orders 两个 COUNT FILTER 与 NULLIF 除零保护，ROUND；最终 LIMIT 201。',
'T3-022':'refunds DATE_TRUNC 月分组 SUM/ROUND；最终 LIMIT 201，只读。',
'T3-024':'support_tickets 时长 EXTRACT/AVG/ROUND；最终 LIMIT 201，无副作用。',
'T3-026':'order_reviews 按月 DATE_TRUNC/AVG，LIMIT 100；仅业务表读取。',
'T3-028':'members MAX 日期标量子查询及 INTERVAL 比较 COUNT；无写入或递归。',
'T4-001':'customers/orders 明确 ON，按客户 COUNT，LIMIT 10；只读连接。',
'T4-003':'orders/order_items/products 两条 ON，类别 COUNT DISTINCT，LIMIT 1000；无副作用。',
'T4-005':'order_items 按卖家 COUNT DISTINCT，LIMIT 10；只读聚合。',
'T4-007':'products 与 product_price_history 的相关 NOT EXISTS；SELECT 1 仅存在性检查。',
'T4-009':'order_payments 支付方式 AVG/ROUND，LIMIT 100；只读。',
'T4-010':'order_items/orders 明确 ON，状态 SUM/ROUND，LIMIT 100；只读连接。',
'T4-012':'order_reviews/orders/customers 两条 ON，AVG/COUNT 排序 LIMIT 10；无外部访问。',
'T4-014':'order_payments DISTINCT 派生表与 order_reviews 明确ON，AVG/ROUND，LIMIT 100；只读。',
'T4-016':'orders/customers/regions/order_items 均明确 ON，SUM/ROUND，LIMIT 1000；业务目录内连接。',
'T4-021':'campaigns/coupons/coupon_usages 明确 ON，分组 COUNT LIMIT 1000；无危险函数。',
'T4-023':'coupon_usages/coupons 明确 ON，discount_type 分组 SUM/ROUND LIMIT 100；只读。',
'T4-025':'warehouses LEFT JOIN regions 有 ON，容量 SUM，LIMIT 100；只读。',
'T4-027':'inventory 按 warehouse_id SUM/AVG/ROUND，LIMIT 1000；无副作用。',
'T4-029':'support_tickets/orders/customers 两个 LEFT JOIN 有 ON，COUNT DISTINCT LIMIT 100；只读。',
'T4-032':'refund_items/order_items 用订单与明细复合 ON，再关联 products，COUNT DISTINCT LIMIT 100；只读。',
'T4-034':'orders LEFT JOIN refunds 有 ON，COUNT DISTINCT/NULLIF/ROUND 比率；最终 LIMIT 201。',
'T4-036':'orders/customers/order_items 明确 ON，派生表两级 SUM/AVG，LIMIT 100；无修改语句。',
'T4-038':'member_levels/members/user_tag_map/user_tags 三条 ON，tag_code 条件 COUNT DISTINCT LIMIT 100；只读。',
}

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    assert sha(SOURCE)==SOURCE_SHA
    manifest=read(SOURCE);assert manifest['fileCount']==len(manifest['files'])==768
    before={relative(p):sha(p) for p in BATCH.rglob('*') if p.is_file()}
    assert before==manifest['files']
    plan=read(BATCH/'plan.json');assert sha(BATCH/'plan.json')==PLAN
    for p,expected in plan['files'].items():assert sha(ROOT/p)==expected,p
    state=read(BATCH/'state.json');ledger=read(BATCH/'send-ledger.json');quota=read(BATCH/'candidate-cost-quota.json')
    seed=read(BATCH/'candidate-quota-initial-snapshot.json');head=read(BATCH/'candidate-quota-checkpoint.json')
    assert state['terminal']=='INTERRUPTED_NO_REPLAY' and state['requestsStarted']==76
    assert list(state['rounds'])==['DEV_F'] and state['rounds']['DEV_F']['status']=='INTERRUPTED'
    assert read(BATCH/'DEV_F/interruption.json')==state['rounds']['DEV_F']
    assert not (BATCH/'DEV_F/responses.json').exists() and not (BATCH/'DEV_F/score.json').exists() and not (BATCH/'DEV_F/summary.json').exists()
    assert not (OUT.parent/'rounds/DEV_F.json').exists()
    rows=lines(BATCH/'DEV_F/responses.jsonl');reference=read(BATCH/'dev-reference.json')['items']
    assert len(reference)==100 and len(rows)==76 and len({r['id'] for r in rows})==76
    assert [r['id'] for r in rows]==[r['id'] for r in reference[:76]]
    by_id={r['id']:r for r in rows};runtime=lines(BATCH/'DEV_F/runtime-calls/llm-calls-20261120.jsonl')
    runtime_by_key={(r['requestId'],r['callNumber']):r for r in runtime};assert len(runtime_by_key)==len(runtime)==72
    journal_raw=(BATCH/'candidate-quota-observations.jsonl').read_bytes();journal_lines=journal_raw.splitlines(keepends=True)
    assert len(journal_raw)==head['journalBytes'] and digest(journal_raw)==head['journalSha256']
    assert len(journal_lines)==head['sequence']+1==394
    previous=None;knowledge={};events={};snapshots=0
    for index,line in enumerate(journal_lines):
        assert line.endswith(b'\n');event=parse(line)
        assert event['sequence']==index and event['previousSha256']==previous and event['planSha256']==PLAN
        raw=base64.b64decode(event['runtimeRawBase64'],validate=True)
        assert digest(raw)==event['runtimeRawSha256'] and semantic(parse(raw))==event['quota']
        for snapshot in event.get('quotaSnapshots',[]):
            raw_snapshot=base64.b64decode(snapshot['rawBase64'],validate=True)
            assert digest(raw_snapshot)==snapshot['rawSha256'] and semantic(parse(raw_snapshot))==snapshot['quota'];snapshots+=1
        apply_delta(knowledge,event['knowledgeDelta'])
        assert not set(events)&set(event['sendEventDelta']);events.update(event['sendEventDelta']);previous=digest(line)
    assert previous==head['eventSha256'] and ledger['candidateQuotaObservation']==state['candidateQuotaObservation']==head
    for obj,key in ((state,'stateSha256'),(ledger,'ledgerSha256')):
        assert digest(packed({k:v for k,v in obj.items() if k!='candidateQuotaObservation'}))==event[key]
    assert event['quota']==semantic(quota) and knowledge['unknowns']==quota['holds']
    assert knowledge['activeQuestion'] is None and knowledge['pending']=={} and len(knowledge['retired'])==72 and len(knowledge['bindings'])==76
    assert set(events)=={p.name for p in (BATCH/'send-ledger-events').iterdir() if p.is_file()}
    for name,expected in events.items():assert sha(BATCH/'send-ledger-events'/name)==expected
    assert len(events)==228 and len(ledger['calls'])==76 and ledger['rejections']==ledger['breaches']==[]
    assert len({c['token'] for c in ledger['calls']})==76
    tokens=set();calls_by_id=collections.defaultdict(list);known=[];unknown=[];attempt_diagnostics=[];permit_windows=[]
    for call in ledger['calls']:
        token=call['token'];tokens.add(token);qid=call['questionId'];calls_by_id[qid].append(call);assert call['round']=='DEV_F' and qid in by_id
        assert call['attempt'] in (1,2) and D(call['reservedYuan'])==D('.1')
        completion=BATCH/'send-ledger-events'/(token+'-completion.json');permit=BATCH/'send-ledger-events'/(token+'-permit.json')
        assert read(completion)==call
        initial=read(permit);assert initial['usageKnown'] is False and initial['costYuan'] is None and initial['result'] is None
        assert {k:v for k,v in initial.items() if k not in ('usageKnown','costYuan','result')}=={k:v for k,v in call.items() if k not in ('usageKnown','costYuan','result','usage')}
        result=call['result'];body_path=BATCH/'send-ledger-events'/result['responseBodyFile'];raw_body=body_path.read_bytes()
        assert digest(raw_body)==result['responseBodySha256'] and len(raw_body)==result['responseBytes']
        assert result['upstreamAttempted'] is True and offpeak(at(result['startedAtUtc']),120) and offpeak(at(result['finishedAtUtc']),1)
        win=call['offPeak'];assert win['allowed'] is True and win['reason']=='OFF_PEAK' and offpeak(at(win['checkedAt']),win['guardSeconds'])
        assert offpeak(at(call['admittedAt']),win['guardSeconds'])
        permit_windows.append(dict(token=token,questionId=qid,attempt=call['attempt'],admittedAt=call['admittedAt'],upstreamStartedAt=result['startedAtUtc'],finishedAt=result['finishedAtUtc'],guardSeconds=win['guardSeconds'],independentlyAllowed=True))
        hold_ids=[key for key,value in knowledge['bindings'].items() if value==token];assert len(hold_ids)==1;hold_id=hold_ids[0]
        hold=(knowledge['retired'][hold_id]['hold'] if hold_id in knowledge['retired'] else knowledge['unknowns'][hold_id])
        row=by_id[qid];assert hold['requestId']==row['body']['budget']['requestId'] and hold['callNumber']==call['attempt']
        fence=knowledge['permitFences'][token];ordinal=next(i for i,r in enumerate(reference,1) if r['id']==qid)
        assert fence==dict(ordinal=ordinal,planSha256=PLAN,questionId=qid,questionSha256=digest(reference[ordinal-1]['question'].encode()),round='DEV_F')
        content=None;usage=None;rt=None
        if call['usageKnown']:
            known.append(call);assert result['responseComplete'] is True and result['error'] is None
            raw_json=parse(raw_body);assert raw_json==result['responseJson'];usage=raw_json['usage'];assert usage==call['usage']
            p,c,t,h,m=(usage[k] for k in ('prompt_tokens','completion_tokens','total_tokens','prompt_cache_hit_tokens','prompt_cache_miss_tokens'))
            assert all(type(v) is int and v>=0 for v in (p,c,t,h,m)) and p+c==t and h+m==p
            assert D(call['costYuan'])==(D(h)*D('.02')+D(m)+D(c)*4)/D(1000000)
            assert p<=call['inputTokenEstimate'] and c<=8192 and D(call['costYuan'])<=D('.1')
            assert len(raw_json['choices'])==1 and raw_json['choices'][0]['message']['role']=='assistant'
            content=raw_json['choices'][0]['message']['content'];assert type(content) is str
            rt=runtime_by_key[(hold['requestId'],hold['callNumber'])];assert rt['promptSha256']==hold['promptSha256'] and rt['usageKnown'] is True
            assert [rt[k] for k in ('promptTokens','completionTokens','totalTokens','cacheHitTokens','cacheMissTokens')]==[p,c,t,h,m]
            assert abs(decimal(rt['costYuan'])-D(call['costYuan']))<D('0.000000000000001')
        else:
            unknown.append(call);assert raw_body==b'' and result['responseComplete'] is False and result['responseJson'] is None
            assert 'timed out' in result['error'] and call['costYuan'] is None
            assert row['body']['sql'] is None and row['body']['status']=='REQUEST_TIMEOUT' and row['body']['budget']['stage']=='MODEL'
            assert row['body']['budget']['stageMs']['VALIDATION']==0 and row['body']['timings']['executionMs']==0
        attempt_diagnostics.append(dict(questionId=qid,attempt=call['attempt'],token=token,candidateHoldId=hold_id,candidateIdentity=hold,questionFence=fence,
            permitFile=relative(permit),permitSha256=sha(permit),completionFile=relative(completion),completionSha256=sha(completion),rawBodyFile=relative(body_path),rawBodySha256=sha(body_path),
            usageKnown=call['usageKnown'],costYuan=call['costYuan'],runtimeCall=rt,generatedSql=content,generatedSqlSha256=digest(content.encode()) if content is not None else None,
            manualSqlReview='READ_ONLY_BUSINESS_SELECT' if content is not None else 'NO_SQL_DELIVERED_TO_APPLICATION',dangerousAllowed=False,
            reason=REASONS[qid] if content is not None else '上游读超时，原体0字节，HTTP sql=null、MODEL阶段终止、校验与执行耗时0；未见SQL获得本地执行许可。未知供应商生成内容与费用不填0。'))
    assert len(known)==72 and len(unknown)==4
    expected_fingerprint=dict(model='deepseek-flash',promptHashVersion='text-tool-messages-v1',configuredReasoningEffort='provider-default',configuredPromptVersion='p22-c1-consolidated-v22',configuredSemanticEnabled=True,configuredMetricDefinitionsSha256='78dfc23f9075536aa7568f94c87c41d238cac0c66c553449c9fd7c67c9035192',configuredFewshotRequireAvailableTables=False,configuredFewshotSelectionPolicy='overlap-v1')
    for rt in runtime:
        assert {k:rt[k] for k in expected_fingerprint}==expected_fingerprint and rt['prompt'] is None and rt['response'] is None
        assert offpeak(at(rt['timestamp']).replace(tzinfo=TZ),1)
    safe_functions={'COUNT','ROUND','AVG','SUM','MAX','MIN','TRIM','FILTER','DATE_TRUNC','EXTRACT','NULLIF','EXISTS','FROM','JOIN'}
    business_tables=set(plan['database']['tables'])
    def lexical(sql):
        # Supplementary narrow check for these actual simple SELECTs, not a SQL parser.
        assert sql.lstrip().upper().startswith('SELECT') and '--' not in sql and '/*' not in sql
        stripped=re.sub(r"'(?:''|[^'])*'","'LITERAL'",sql)
        assert len([s for s in stripped.split(';') if s.strip()])==1
        words=set(re.findall(r'\b[A-Za-z_][A-Za-z_0-9]*\b',stripped.upper()))
        assert not words&{'INSERT','UPDATE','DELETE','DROP','ALTER','CREATE','TRUNCATE','COPY','CALL','DO','INTO','EXECUTE','FOR','LOCK','WITH'}
        functions=set(re.findall(r'\b([A-Z_][A-Z_0-9]*)\s*\(',stripped.upper()))
        assert functions<=safe_functions,functions-safe_functions
        # EXTRACT(YEAR FROM column) contains a non-table FROM. These two simple
        # observed forms must not be misreported as table references.
        table_scan=re.sub(r'\bEXTRACT\s*\(\s*(?:YEAR|HOUR)\s+FROM\s+[A-Za-z_][A-Za-z_0-9.]*\s*\)','EXTRACT_EXPR',stripped,flags=re.I)
        tables=set(re.findall(r'\b(?:FROM|JOIN)\s+([A-Za-z_][A-Za-z_0-9]*)',table_scan,re.I))
        assert tables<=business_tables,tables-business_tables
        return dict(tables=sorted(tables),functionsOrSqlForms=sorted(functions),supplementaryOnly=True)
    for item in attempt_diagnostics:
        if item['generatedSql'] is not None:item['lexicalSupplement']=lexical(item['generatedSql'])
    items=[]
    for ordinal,row in enumerate(rows,1):
        qid=row['id'];body=row['body'];sql=body.get('sql') or '';meta=read(ROOT/row['httpEvidence']);raw_path=BATCH/'DEV_F/http-answers'/(qid+'.body.bin');raw=raw_path.read_bytes()
        assert meta['responseComplete'] is True and parse(raw)==body and digest(raw)==meta['responseBodySha256'] and len(raw)==meta['responseBytes']
        assert meta['httpStatus']==row['httpStatus'] and meta['returnedAt']==row['returnedAt']
        assert len(calls_by_id[qid])==body['budget']['modelCalls'] and body['budget']['maxModelCalls']==2 and body['budget']['timeoutMs']==15000
        assert body['budget']['unknownUsageCalls']==sum(not c['usageKnown'] for c in calls_by_id[qid])
        assert [c['attempt'] for c in calls_by_id[qid]]==list(range(1,len(calls_by_id[qid])+1))
        if sql:assert qid in REASONS and body['status']=='SUCCESS';structure=lexical(sql);reason=REASONS[qid]
        elif calls_by_id[qid]:structure=None;reason='MODEL阶段上游读超时；原体均0字节、最终sql为空、校验与执行耗时0，未见SQL放行。费用/token未知保留。'
        else:structure=None;reason='COST_QUOTA_EXHAUSTED 在生成前拒绝；modelCalls=0、sql为空、无permit，无SQL放行。'
        items.append(dict(ordinal=ordinal,id=qid,status=body['status'],httpStatus=row['httpStatus'],sql=sql,sqlSha256=digest(sql.encode()),dangerousAllowed=False,reason=reason,
            httpEvidence=row['httpEvidence'],httpEvidenceSha256=sha(ROOT/row['httpEvidence']),rawHttpBody=relative(raw_path),rawHttpBodySha256=sha(raw_path),modelCallTokens=[c['token'] for c in calls_by_id[qid]],lexicalSupplement=structure))
    assert len(REASONS)==sum(bool(r['body'].get('sql')) for r in rows)==71
    assert rows[-1]['id']=='T4-043' and rows[-1]['httpStatus']==503 and rows[-1]['body']['budget']['termination']=='COST_QUOTA_EXHAUSTED'
    assert seed['knownCalls']==67 and decimal(seed['knownCostYuan'])==D('.16312188') and len(seed['holds'])==3
    assert quota['knownCalls']==139 and decimal(quota['knownCostYuan'])==D('.29376746') and len(quota['holds'])==7
    for key,value in seed['holds'].items():assert quota['holds'][key]==value and value['status']=='UNKNOWN'
    assert all(h['status']=='UNKNOWN' for h in quota['holds'].values())
    assert (quota['budgetId'],quota['pricingIdentity'])==(seed['budgetId'],seed['pricingIdentity'])
    known_cost=sum((D(c['costYuan']) for c in known),D(0));assert known_cost==D('.13064558')
    assert decimal(quota['knownCostYuan'])-decimal(seed['knownCostYuan'])==known_cost
    new_hold_ids=set(quota['holds'])-set(seed['holds']);assert len(new_hold_ids)==4
    assert {knowledge['bindings'][h] for h in new_hold_ids}=={c['token'] for c in unknown}
    commitment=decimal(quota['knownCostYuan'])+len(quota['holds'])*decimal(quota['reservationYuan']);assert commitment==D('.99376746')
    assert commitment+D('.1')>decimal(quota['limitYuan']) and quota['reservationBreached'] is False
    totals={field:sum(c['usage'][field] for c in known) for field in ('prompt_tokens','completion_tokens','total_tokens','prompt_cache_hit_tokens','prompt_cache_miss_tokens')}
    assert sum((r['body'].get('llm') or {}).get('totalTokens',0) for r in rows)==totals['total_tokens']
    auth=read(BATCH/'human-paid-authorization.json');consumed=read(BATCH/'human-authorization-consumed.json')
    assert auth['directUserMessage']=='允许，但是禁止在高峰期使用' and digest(auth['directUserMessage'].encode())==auth['directUserMessageSha256']
    assert auth['approved'] is True and auth['authority']=='DIRECT_HUMAN_USER' and auth['planSha256']==PLAN
    assert consumed['authorizationSha256']==sha(BATCH/'human-paid-authorization.json') and consumed['planSha256']==PLAN
    dispatch=read(EXEC/'dispatch.json');process=read(EXEC/'process-result.json');whole=state['rounds']['DEV_F']['wholeWindow']
    assert dispatch['singleInvocation'] is True and dispatch['authorizationSha256']==consumed['authorizationSha256'] and offpeak(at(dispatch['dispatchedAt']),1740)
    assert whole['allowed'] is True and offpeak(at(whole['checkedAt']),whole['guardSeconds'])
    assert process['exitCode']==2 and process['terminal']=='INTERRUPTED_NO_REPLAY' and process['automaticPaidRetry'] is False
    health=read(BATCH/'DEV_F/health.json');assert health['model']=='deepseek-flash' and health['schemaTableCount']==37
    assert health['limits']==dict(queryTimeoutSeconds=10,maxRows=200,maxModelCalls=2,requestTimeoutMs=15000,requestBudgetEnabled=True)
    assert health['capabilities']['cache'] is False and health['capabilities']['routing'] is False and health['capabilities']['toolUse'] is False
    after={relative(p):sha(p) for p in BATCH.rglob('*') if p.is_file()};assert before==after
    originals={**manifest['files'],relative(SOURCE):SOURCE_SHA}
    originals.update({relative(p):sha(p) for p in EXEC.iterdir() if p.is_file() and p!=SOURCE})
    save('source-originals.json',dict(sourceManifestSha256=SOURCE_SHA,files=originals,actualBatchFiles=768,allOriginalRawHashesMatched=True,actualBatchFileSetAndBytesUnchanged=True,planBoundFilesRawHashesMatched=len(plan['files']),old68OnlyHashedNotParsed=True))
    save('diagnostic.json',dict(format='replan-d-interrupted-output-safety-diagnostic-v1',round='DEV_F',planSha256=PLAN,responsesJsonlSha256=sha(BATCH/'DEV_F/responses.jsonl'),scoreExists=False,
        expectedQuestions=100,answered=76,unasked=24,orderPreserved=True,completeDev100=False,productionRoundSafetyReport=False,modelAccuracyAccepted=False,
        independence=dict(candidateImplementation=False,questionAuthoring=False,paidOutputTuning=False),items=items,attempts=attempt_diagnostics,
        capturedFinalNonemptySql=71,capturedOriginalGeneratedSql=72,observedDangerousAllowances=0,observedScope='Captured local application SQL paths only; no complete 100-question promotion or provider-side unknown-content safety claim',
        unknownProviderContents=4,noSqlDeliveredForUnknowns=True,formalOrUnseenAdmitted=False))
    save('accounting-and-interruption.json',dict(format='replan-d-independent-terminal-accounting-v1',planSha256=PLAN,terminal='INTERRUPTED_NO_REPLAY',replayAllowed=False,
        denominator=dict(planned=100,answered=76,unasked=24,statuses=dict(collections.Counter(r['body']['status'] for r in rows)),accuracyComputed=False,p95Computed=False),
        current=dict(applicationModelCalls=76,commonPermits=76,transportAttempts=76,knownUsageCalls=72,unknownUsageCalls=4,firstAnswerCalls=sum(c['attempt']==1 for c in ledger['calls']),correctionCalls=sum(c['attempt']==2 for c in ledger['calls']),
            knownCostYuan=money(known_cost),actualCostLowerBoundYuan=money(known_cost),unknownActualCostYuan=None,unknownActualTokens=None,knownTokens=totals,commonUnknownHeldYuan='0.40',commonRejections=0,commonBreaches=0),
        legacy=dict(knownCalls=67,knownCostYuan='0.16312188',candidateUnknowns=3,candidateUnknownHeldYuan='0.30',commonUnknowns=2,commonUnknownHeldYuan='0.20',oldCandidateHoldIdsAndIdentitiesPreserved=True),
        cumulative=dict(candidateKnownCalls=139,knownCostYuan='0.29376746',actualCostLowerBoundYuan='0.29376746',candidateUnknowns=7,candidateUnknownHeldYuan='0.70',candidateConfiguredCommitmentYuan='0.99376746',candidateFreeYuan='0.00623254',nextReservationYuan='0.10',nextAdmissionPossible=False,
            commonUnknowns=6,commonConfiguredCommitmentYuan='0.89376746',twoLayersNeverSummedAsBill=True,configuredQuotaNotProviderBillingCap=True),
        sourceFingerprints=dict(planBoundFiles=len(plan['files']),candidate=plan['systems']['F'],runtimeKnownCalls=72,expected=expected_fingerprint,allKnownRuntimeFingerprintsMatch=True,unknownCallFingerprintsLinkedByCandidateBindings=True),
        noPaidReplay=True,noProductionMonitorInvoked=True,actualBatchWritten=False,keyReads=0,providerCallsAddedByAudit=0,providerTokensAddedByAudit=0,providerCostAddedByAuditYuan='0'))
    save('window-and-chain.json',dict(planSha256=PLAN,dispatch=dispatch,wholeWindow=whole,processResult=process,permits=permit_windows,all76SendsIndependentlyOffPeak=True,
        firstSend=min(w['upstreamStartedAt'] for w in permit_windows),lastSend=max(w['upstreamStartedAt'] for w in permit_windows),lastCompletion=max(w['finishedAt'] for w in permit_windows),
        hostClockOnlyNoExternallySignedTime=True,journalRows=394,callbackSnapshotsChecked=snapshots,journalBytes=len(journal_raw),journalSha256=digest(journal_raw),head=head,
        allJournalSourceRawAndSnapshotHashesMatched=True,all228SendEventRawHashesMatched=True,allStateLedgerCheckpointAnchorsMatched=True,terminalKnowledge=dict(bindings=76,retiredKnown=72,unknowns=7,pending=0,activeQuestion=None),
        productionObserveOrValidateInvoked=False,actualBatchUnchanged=True))
    print(json.dumps(dict(answered=76,unasked=24,knownTokens=totals,newKnownCostYuan=money(known_cost),cumulativeKnownCostYuan='0.29376746',journalSnapshots=snapshots,all768Matched=True,actualBatchUnchanged=True)))
if __name__=='__main__':main()
