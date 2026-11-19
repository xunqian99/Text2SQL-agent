"""Human semantic judgments recorded separately from execution success."""
import hashlib
import json
from datetime import datetime, timezone
from reviewer_execute import OUT, ROOT, load_items

RATIONALES = {
'T1':[
'订单主键order_id；审核非空与交承运商为空是独立时间事实，包含全部状态；审核时间/order_id稳定截断30。',
'商品主键product_id；三边>=40的SQL三值逻辑自然排除NULL，另排除未知重量；重量倒序与商品ID保序25。',
'member_id保留会员身份，status=2与积分>=1500匹配题意；积分NULL被排除，同积分按ID40。',
'fixed券与闭区间[100,300]准确；最低金额NULL不匹配；门槛/券ID截断35，不施加未要求的有效期。',
'shipment_id运输记录；status=2、shipped_at非空、delivered_at为空三项独立条件；发货时间/ID稳定40，不借订单时间。',
'refund_id退款申请粒度；status=2且refund_amount>150，直接读取金额不加运费；金额/ID稳定30。',
'工单priority=4与resolved_at为空，不以status替代；created_at非空，时间/工单ID稳定30，允许未知订单号。',
'log_id为事件身份；零结果与非NULL点击同时成立也保留，不纠正原始矛盾；搜索时间/ID双倒序30。',
'库存复合键(warehouse_id,product_id)；两数量已知且quantity<safety_stock；差额与完整键稳定40。',
'history_id调价事件；旧价减新价>=50，先排除任一价NULL；金额差/ID稳定30，不用相对涨幅。',
'评价完整键(review_id,order_id)保留；SQL NULL正文不等于空字符串；创建时间/评价ID/订单号稳定20。',
'支付完整键(order_id,payment_sequential)；credit_card、期数>=8、金额非空；金额及完整键稳定25，全部订单状态。',
'settlement_id结算单；status=2且net_amount>1000；不重复扣佣金；净额/ID稳定30。',
'address_id地址记录；默认布尔、地址州RJ、城市非NULL；不改用客户城市或自然人身份，城市/ID稳定40。',
'referral_id推荐事件；两端身份非NULL、未转化、原始奖励>0、创建时间已知；不把未转化奖励变0，时间/ID稳定20。',
'campaign_id活动身份而非名称；status=3、budget>0保证已知正候选；预算/ID稳定12。',
'brand_id身份；country原值BR精确匹配，名称NULLS LAST及ID稳定25，不翻译国家。',
'地理多坐标只投影DISTINCT已知邮编；SC与两坐标非空，不选择随意坐标或连交易事实；邮编升序40。',
'message_id事件；customer精确值、正文非SQL NULL（空串保留）、时间已知；时间/ID双倒序35。',
'warehouse_id仓库身份；capacity>0排除未知非正容量；region_code即仓库州来源，容量/ID稳定8（实库5仓，不伪造8行）。'],
'T2':[
'orders一行一单；发货非空且签收NULL，再按原状态分组COUNT(*)；不以状态代替时间、不补空组。',
'客户档案COUNT(*)与自然人COUNT(DISTINCT customer_unique_id)分离；同一人跨州各计，州NULL成组；规模倒序/州保序。',
'原类目含NULL成组；商品数含未知重量但AVG仅已知重量，HAVING>=80后类目保序，无补0。',
'支付事实一行一笔，期数=1；支付方式NULL组、金额SUM/AVG忽略NULL并保全未知为NULL；不额外过滤订单。',
'卖家维度COUNT商品行而非订单；SUM(price)保留等额明细、不含运费；未知卖家排除，行数/卖家ID稳定18。',
'member_id计全记录，COUNT(points)只计已知积分，AVG不补0；status含NULL分组（DDL当前非空不影响定义）。',
'keyword非NULL保留空串，result_count=0不包含NULL；每log_id一次，条数/词排序稳定22。',
'shipment_id运输事实COUNT(*)，完整发货/签收双非空FILTER；承运商NULL组保留，不过滤状态或时间先后。',
'status=4退款单COUNT(*)与SUM(refund_amount)；原因NULL成组，金额不加运费、不变订单数。',
'工单按priority含NULL分组；全部工单与resolved_at为空数量分开，不把status作为解决证据。',
'库存主键一仓商品一项；quantity SUM及quantity<safety_stock FILTER，任一NULL不认定低库存；只列库存出现仓。',
'movement_id流水COUNT及原quantity SUM，movement_type含NULL组；不自动把出库变负，也不做时间限制。',
'用券member_id非NULL；COUNT核销与原优惠SUM，HAVING金额>0后排序20；不去重订单或借买家会员身份。',
'settlement_id结算单一单一次；status组COUNT、SUM(net_amount)、AVG(net_amount)；全未知自然NULL，不重扣佣金。',
'先排除任一价NULL再按>,<,=三路CASE；每history_id等权，不按商品去重，无未出现方向补行。',
'奖励CASE按NULL、<=0、<=10、<=20、else无缝分桶，边界不重叠；converted_at COUNT为转化标记，未知奖励SUM仍NULL。',
'评价每物理行按评分统计，复合身份不被review_id单键折叠；正文NULL FILTER保空字符串，评分NULL组保留。',
'budget>0先定义已知正预算集合，再按campaign_type分组COUNT/AVG；不按活动名称去重，无正预算类型不补0。',
'shipping_addresses.state作为自身州；地址主键COUNT、默认TRUE FILTER与不同customer_id分开，不将关联键数称人数。',
'品牌物理行COUNT按原country含NULL分组；同名不同ID分别计，品牌数倒序/国家NULL末稳定。'],
'T3':[
'下单窗口[2017-09-01,2018-03-01)，自然月COUNT订单与COUNT审核时间；全部状态，仅实际月份且升序。',
'2017/2018各1-6月有效订单先按年/月计数，同月内连接取交集，2018减2017；01-06字符串稳定，不补缺月。',
'searched_at左闭右开一周；EXTRACT HOUR跨日期按一天小时汇总，COUNT日志/COUNT点击非NULL，不按会员。',
'completed_at定义Q2归属，status=4；非申请时间、金额退款头字段，不额外连订单；月份升序。',
'截止前按product_id以changed_at DESC/history_id DESC取最后，再筛已知上涨；不倒置过滤，午夜上界排除。',
'snapshot_date<=6月30含当天，member_id非NULL，日期/快照ID取最新后才筛积分闭区间；不回退旧快照。',
'moved_at[3月1,6月1)且类型2，自然月原数量SUM/流水COUNT；不翻转正负，不补缺月。',
'工单创建窗口定义月份；COUNT保留未解决，EPOCH/3600平均仅两时刻已知；未按状态过滤。实库存在逆序时刻，按题面原始差值可为负，不隐式纠正。',
'六月重叠区间须合法start<=end；已知两日期，start<7月1且end>=6月1；包括6月30单日，排除7月1与逆序区间。初版缺合法区间条件，待作者定向修订复验。',
'paid_at为实际打款月，窗口[1月1,9月1)，不以status=3或账期起点替代；结算头净额一单一次。',
'注册Q1左闭右开且末登录>=7月1，NULL日期不匹配；注册/会员ID稳定35，状态不限。',
'推荐创建窗口分母全记录；转化条件created<=converted<=created+7日双含且不限转化月份；NULL及提前转化不入分子。',
'shipping_limit_date[8月20,8月27)作为截止日期来源，按date聚合COUNT商品行和SUM(price)；不误用购买时间或加运费。',
'shipment发货窗口[6月1,8月1)且签收已知，按发货月COUNT/AVG完整EPOCH天；跨月送达保留，无status过滤。',
'先截取五月评价，再每order_id按创建时间/评价ID倒序取最后，最后才筛1分；完整键对并列有确定性，订单号30。',
'有效订单范围[2017-11-01,2018-05-01)先自然月COUNT，再按month-1 month真日历连接；无缺月跳跃，首月无上月不输出。'],
'T4':[
'订单事实沿customer_id精确定位客户州再地区唯一键；两LEFT JOIN保未知大区，有效Q2前段订单COUNT不被自然人多档案扩散。',
'商品行沿seller_id到卖家州/大区；LEFT维度保完整明细，COUNT/SUM(price)，不筛订单状态或取客户区。',
'inventory仓商品quantity SUM沿产品/翻译LEFT JOIN，英文NULL含原始NULL和缺翻译；HAVING库存>0排名15，数量/英文NULL末稳定。',
'member_levels为维度全集，冻结条件放LEFT JOIN ON；COUNT(m.member_id)让零会员等级=0，未知等级不额外成组。',
'已结束活动ID全集，fixed券条件放LEFT ON；COUNT券ID而非*保零券=0，同名活动各组。',
'核销事件通过券/活动唯一键归属，used_at[3月1,6月1)，一核销一次SUM/COUNT；已知合计12排名，不连明细扩优惠。',
'退款明细完整事件身份，原明细以(order_id,order_item_id)二键匹配；类目LEFT保NULL，SUM件数/金额，不按退款头或订单状态筛。',
'shipment事实WHERE发货非空，轨迹EXISTS地点非NULL只作存在标记；不复制多轨迹，仓库/地区LEFT保NULL，未签收仅delivered_at为空。',
'created_at Q1定义工单集，分类LEFT保缺失映射为NULL组；COUNT工单、AVG完整小时不受消息加权或status过滤。',
'紧急工单全集LEFT customer消息且条件放ON；COUNT message_id保零消息工单，消息数/工单ID排序25。',
'品牌全集LEFT当前关联再LEFT存在产品；关联复合主键确保每品牌商品只计一次，无产品品牌=0，多当前品牌分别计。',
'调价history_id与真实商品匹配、原类目已知、变更Q2；COUNT含未知价记录而AVG只含两价已知，每条变动等权金额差。',
'五月全部订单沿事实customer_id取客户州；默认且同州地址用EXISTS避免多地址复制，SQL=不认NULL相同，不按自然人转移地址证据。',
'标签全集LEFT关联及正常会员；关联(member_id,tag_id)主键保每标签每会员一次；无正常会员COUNT主键=0。',
'referrer_member_id推荐人链到会员/等级，均真实匹配；COUNT推荐、COUNT(converted_at)转化，不取被推荐人等级。',
'账期period_start[3月1,6月1)与status=3，结算头SUM(net_amount)/COUNT；卖家LEFT保未知州，不筛paid_at或连明细。',
'财务明细(settlement_id,order_id,order_item_id)通过订单二键匹配，商品类目LEFT；SUM已存gross/commission，不重算、无状态筛选，已知毛额16稳定。',
'七月非NULL点击日志为完整事实，LEFT产品及翻译保缺失为NULL英文组；COUNT log不去重用户/产品，组数15稳定。',
'八月类型3流水JOIN仓库且仓州SP，LEFT商品保未知类目；原quantity SUM/流水COUNT，无订单关系替代。',
'debit_card支付事件JOIN Q1全部订单，LEFT客户按order.customer_id取州；SUM payment_value/支付COUNT，不加运费、不人反连。',
'评价身份(review_id,order_id)加类目DISTINCT消同类商品复制；跨类目分别计；低分<=2 FILTER、未知评分留分母，原NULL类目明确排除。',
'活动ID与其商品复合主键到订单行，活动开始2018年，订单有效且时间>=start并<end+1日；每活动独立统计行与price，不伪称因果归因。'],
'T5':[
'退款头status=4且申请上半年，退款明细同时对refund_id/order_id对齐，再原商品二键；当前品牌可多归属，卖家地区LEFT保NULL，SUM明细事实不改退款头金额。实库头/行订单不一致0。',
'运输shipment_id为事实；订单customer_id→自然人→唯一会员作正常筛选，标签EXISTS防复；仓库维度LEFT与客户州分离，3-5月发货含全部订单状态。',
'核销链活动与核销member_id当前等级为维度，核销及订单都Q2且有效；每usage金额一次，组内COUNT DISTINCT order_id，不替换成买家会员。',
'结算明细到结算头、结算卖家、原明细完整键、产品均内连；大区/翻译LEFT保NULL，Q1账期金额直接财务事实；维度来源明确为结算卖家。',
'工单六月创建，订单/客户真实匹配取订单客户区；消息预先按ticket_id聚合并以COALESCE对无消息补可证明0，工单COUNT不被消息条数加权。',
'八月点击log→会员/等级/真实产品内连，当前品牌LEFT；每日志×当前品牌关联计一次，无品牌NULL组，不按人去重或假设品牌唯一。',
'当前仓商品为事实全集，仓/商品真实匹配；九月类型2流水先按同复合键SUM再LEFT，匹配键标记区分无事实0和有事实全NULL，库存不补0。',
'四月全部订单作分母事实全集，商品/支付/退款三事实各先按订单SUM；通过事实键标记无事实0与存在未知NULL，地区LEFT保全，不被三重一对多放大。',
'推荐两端分别以src/dst身份匹配，等级取推荐人，HIGH_VALUE EXISTS取被推荐人；上半年推荐COUNT、转化时间标记、奖励原SUM含未转化。',
'七月有效订单准确customer_id定位自然人/当前会员等级，商品明细JOIN真实商品，英文翻译LEFT；COUNT行/SUM(price+freight)，不冒充历史等级。'],
'T6':[
'MG属于各订单客户记录州，先限定有效期间订单再按已知自然人COUNT，>=2为分子人群，全部下单人群为分母，100.0及NULLIF保证百分数/空集NULL。',
'有商品明细有效订单集合由订单预聚合INNER定义；分子SUM所有price+freight，分母COUNT一单一行；非人数/行数，保两位、空集NULL。',
'有效Q1订单为全分母，LEFT客户保未知州，会员EXISTS身份标记不删非会员且不限status；计订单比例而非人数，100.0/NULLIF两位。',
'全部Q2订单，真实签收非空为分母；签收<=预计才分子，预计NULL留分母但不算准时；不依赖状态，不用1-迟到率。',
'上半年有效商品行沿卖家州/大区LEFT；整体SUM freight / SUM(price+freight)，非单行比率平均；100.0两位，零/未知分母NULL，DDL两金额非空。',
'商品与核销先各按order_id聚合，再INNER到六月有效订单，限定二事实皆存在；总优惠/总GMV、不乘商品数量、不用SUM DISTINCT，零/未知分母NULL。',
'全历史有效且身份/时间已知首单判定11月cohort，1月回访先自然人去重标记；LEFT保未回访分母，跨customer_id同一人不漏不重，空人群NULL。',
'全历史首有效月后报告期3-5月分类，同首月全单NEW、后月RETURNING、缺身份/首月NULL；商品订单预聚合LEFT保无明细订单及已知0，不偷删金额/订单。',
'Q2创建工单JOIN分类定义，全部工单分母含未解决；完整EPOCH小时<=SLA且已解决为分子，未知SLA不成功。合同未要求非负时长，源数据逆序时间按原公式参与（不是自动纠正）。',
'结算明细按头账期5-7月，卖家州LEFT；SUM已存commission / SUM gross，加权整体比率而非AVG行率，0-1四位，不乘100，零未知分母NULL。',
'九月日志→真实会员/等级定义，COUNT事件全分母，非NULL clicked字段为分子；不JOIN产品改变点击分母，不按人去重，未出现等级不补0。',
'全库MAX moved_at统一锚（不限仓或类型），近90日左开右闭类型2原数量；库存/流水各先按仓SUM，库存仓LEFT全集，90.0比例1位，无出库/零/未知NULL。']}

def build():
    items=load_items()
    assert [len(RATIONALES['T'+str(n)]) for n in range(1,7)]==[20,20,16,22,10,12]
    initial=json.loads((OUT/'initial-execution-results.json').read_text(encoding='utf-8'))
    execution={x['id']:x for x in initial['results']}
    if (OUT/'revision-execution-results.json').exists():
        execution.update({x['id']:x for x in json.loads((OUT/'revision-execution-results.json').read_text(encoding='utf-8'))['results']})
    if (OUT/'maxrows200-revision-execution-results.json').exists():
        execution.update({x['id']:x for x in json.loads((OUT/'maxrows200-revision-execution-results.json').read_text(encoding='utf-8'))['results']})
    boundary=json.loads((OUT/'boundary-witness-results.json').read_text(encoding='utf-8'))
    witnesses={}
    for row in boundary['results']:
        witnesses.setdefault(row['itemId'],[]).append(row)
    records=[]
    for item in items:
        layer=item['id'].split('-')[1]
        seq=int(item['id'].split('-')[2])
        e=execution[item['id']]
        source_match=e['sqlSha256']==hashlib.sha256(item['gold_sql'].encode()).hexdigest()
        if not source_match:
            raise RuntimeError('Unexecuted final SQL '+item['id'])
        ws=witnesses.get(item['id'],[])
        status='PASS' if all(x['status']=='PASS' for x in ws) and e['ok'] and e['resultRowCount']<=200 else 'DEFECT'
        rationale=RATIONALES[layer][seq-1]
        if item['id']=='B2-T3-009' and status=='PASS':
            rationale=rationale.replace('初版缺合法区间条件，待作者定向修订复验。','初版缺合法区间条件；作者按独立反馈定向修订后，实库及逆序/单日/上界反例复验通过，反馈后修订接受。')
        if item['id']=='B2-T5-007' and status=='PASS':
            rationale+=' 原207行超过冻结候选默认200行执行上限；作者按新增运行约束明确仅从库存合计已知组中选库存最高150组，同值按大区/英文类目NULL最后稳定排序。定向实库及合成边界复验后接受；未使用候选输出修题。'
        records.append({'id':item['id'],'difficulty':item['difficulty'],'status':status,'question':item['question'],
             'goldSqlSha256':e['sqlSha256'],'sourceFile':item['sourceFile'],'sourceSha256':item['sourceSha256'],
             'semanticRationale':rationale,'checks':['question-Gold correspondence','physical identity and complete key','NULL and empty semantics',
             'complete facts and one-to-many cardinality','time window and attribute source','sort/tie/limit contract','grain and answerability'],
             'execution':{k:e[k] for k in ('ok','resultRowCount','wallMs','resultSha256')},
             'boundaryWitnesses':[{'witness':x['witness'],'status':x['status'],'expected':x['expected'],'actual':x['actual']} for x in ws]})
    summary={'format':'unseen-v2-independent-semantic-review-v1','at':datetime.now(timezone.utc).isoformat(),
             'itemCount':len(records),'counts':{s:sum(x['status']==s for x in records) for s in ('PASS','DEFECT','AMBIGUOUS')},
             'initialCounts':{'PASS':99,'DEFECT':1,'AMBIGUOUS':0},'reviewer':'/root/review_unseen_v2',
             'productionSourcesRead':False,'historicalOutputsRead':False,'oldQuestionsManuallyRead':False,
             'providerCalls':0,'databaseWrites':0,'sharedFilesystemProcessIndependenceOnly':True,
             'reviewMode':'independent initial review; targeted author revision after feedback, accepted only after recheck',
             'records':records}
    (OUT/'per-item-review.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    md=['# 未见题v2独立语义审查','',f"最终逐题统计：{summary['counts']}。初审99 PASS、1 DEFECT、0 AMBIGUOUS。",'',
        '本审查没有读取生产Prompt/实现、旧题内容、历史模型输出或旧审计。作者与审查者分工独立，但共享文件系统只有流程隔离。',
        '只读实库执行、语义人工对照、合成CTE边界见证分别记录；执行成功不单独构成PASS。',
        '最终版本属于“反馈后修订接受”，不宣称完全盲审。候选已在出题前冻结，反馈没有进入生产调参。','',
        '## 已知数据与边界','',
        '实库存在2997个多档案自然人、39个跨州多档案自然人、789个跨订单复用评价ID、2961个多支付订单、9803个多商品订单和97583个多轨迹运输记录。需要完整身份和预聚合/EXISTS。',
        '实库有2556条解决时间早于创建时间的工单，其中548条位于第二季度。相关题按已明确的原始差值/<=SLA公式计算，未擅自改写成非负时长；不能把这些数包装成无质量问题的真实SLA运营结论。',
        '退款头/行订单不一致和当前品牌多归属在实库均为0，但多品牌题面显式说明非独占，退款明细原订单匹配使用完整二键。',
        '最终采用冻结候选默认200行上限，原207行项已改为题面显式Top150并稳定裁决，不允许驱动层静默截断。其余无LIMIT维度全集在本快照均≤200，未来数据库增长时必须重验。',
        '旧题只允许程序精确重合检查；0精确重合不证明0语义重合。没有做旧题人工语义对照。','']
    for record in records:
        md.extend([f"## {record['id']} — {record['status']}",'',record['semanticRationale'],'',
                   f"证据：只读执行 {record['execution']['resultRowCount']} 行；结果 SHA-256 `{record['execution']['resultSha256']}`。",
                   f"Gold SHA-256 `{record['goldSqlSha256']}`。",''])
        if record['boundaryWitnesses']:
            md.extend(['边界见证：'+', '.join(x['witness']+'='+x['status'] for x in record['boundaryWitnesses']),''])
    (OUT/'REVIEW.md').write_text('\n'.join(md)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'count':len(records),'counts':summary['counts']},ensure_ascii=False))

if __name__=='__main__':build()
