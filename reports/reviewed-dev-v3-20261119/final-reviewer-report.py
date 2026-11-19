from pathlib import Path
from collections import Counter
import hashlib,json

OUT=Path(r'C:\Users\xunqian\Desktop\agent\reports\reviewed-dev-v3-20261119')
cases=json.loads((OUT/'final-reviewer-final-cases.json').read_text(encoding='utf-8'))
manifest=json.loads((OUT/'final-reviewer-final-manifest.json').read_text(encoding='utf-8'))
initial={r['id']:r for r in map(json.loads,(OUT/'final-reviewer-batch-output.jsonl').read_text(encoding='utf-8').splitlines())}
delta={r['id']:r for r in map(json.loads,(OUT/'final-reviewer-delta-output.jsonl').read_text(encoding='utf-8').splitlines())}
execution=json.loads((OUT/'final-reviewer-execution-summary.json').read_text())
delta_summary=json.loads((OUT/'final-reviewer-delta-summary.json').read_text(encoding='utf-8'))

reasons={
'T1-002':'订单主键一行一订单；按下单时间取2018年度半开窗口，无状态额外过滤。',
'T1-004':'明确自然人单位；按customer_unique_id去重，不把订单级customer_id误当自然人。',
'T1-006':'以products实体记录为全集COUNT(*)，无关联放大。',
'T1-008':'明确城市名称值、同名跨州合并；COUNT(DISTINCT customer_city)对应该单位。',
'T1-011':'产品类目按原始非NULL值去重，符合product_category_nulls_v1。',
'T1-013':'明细完整身份为(order_id,order_item_id)；最高运费严格5行，同值按完整身份升序。修订已用6条等运费反例验证。',
'T1-015':'计信用卡支付记录；按物理payment_type=credit_card筛选，不把支付数当订单数。',
'T1-017':'在商品粒度按克重>5000严格筛选，NULL不满足数值条件。',
'T1-019':'按签收时间缺失判定未有送达记录；不扩张成已证实仍在运输的业务结论。',
'T1-020':'非NULL且BTRIM后非空的标题才计入；有正文但无标题不计。',
'T1-022':'直接在明细事实按price<10计记录；不误用商品表或结算金额。',
'T1-024':'通过等级主键关联等级名称黄金会员后计会员；不会因名称不是唯一键重复同一等级行。',
'T1-026':'积分已知候选中按积分降序、会员完整主键升序，严格最多10人，仅投影所需两列。',
'T1-028':'容量已知候选中取最大，完整仓库主键裁决并列，仅输出ID与容量。',
'T1-031':'优惠券定义记录全集计数，不把核销记录当成券数量。',
'T1-033':'紧急优先级物理值4，工单主键粒度计数。',
'T1-035':'同一仓库商品库存记录的quantity与safety_stock比较，不跨记录聚合后比较。',
'T1-037':'按搜索词原值去重，NULL不作为一个已知关键词。',
'T1-039':'按活动已结束状态3筛选，不以当前系统时间推断。',
'T1-040':'价格历史中的product_id去重，计发生过历史调价记录的商品数。',
'T2-001':'全明细price合计；price为非NULL两位NUMERIC，合计不含运费。',
'T2-003':'明细等权AVG(price)，金额两位，不先按订单聚合改变权重。',
'T2-005':'全部订单按状态原值分组计订单，降序仅作用于数量；完整集合8组无LIMIT截断。',
'T2-007':'全部评价记录按分数原值分组；复合评价身份不在连接中被重复。',
'T2-009':'类目默认排NULL，按商品数降序及类目原值升序严格Top10，未额外输出名称。',
'T2-010':'按卖家事实seller_state分组计卖家，不改走客户州。',
'T2-012':'按卖家ID合计明细运费，金额两位后排序，同额卖家ID裁决，严格最多10。',
'T2-014':'点名两个非时间子集的记录数；条件计数同一行两列、顺序与题面一致，空集合仍各为0。',
'T2-016':'按会员状态原值计会员，保留实际出现状态。',
'T2-018':'活动类型分组计活动，没有附加活动名称或状态限制。',
'T2-021':'退款单按原因代码原值计记录，不去重成订单。',
'T2-023':'已退款以refunds.status=4筛选；合计退款单金额，不合并等额真实退款。',
'T2-025':'所有库存记录quantity合计，未关联仓库维表缩小事实全集。',
'T2-027':'库存变动按movement_type分组计流水，而非库存余额。',
'T2-029':'发货单事实按status分组计发货记录，而非订单或物流轨迹。',
'T2-030':'题面明确仅统计工单实际出现category_id，不补零工单分类；输出ID符合默认投影。',
'T2-032':'结算单按status原值计记录，不把结算明细当单数。',
'T2-034':'合计结算应计佣金commission，未自行只取已打款；不把它解释为现金已到账收入。',
'T2-036':'类目默认排NULL，商品COUNT>1500，输出类目与计数，不附加商品维度。',
'T2-038':'请求仅价格极值，可用MAX/MIN两列；不是寻找具体商品实体。',
'T3-002':'明确两年份各一行，整数年份与订单数纵表，未改成两列。',
'T3-004':'2017年11月下单时间半开区间，不漏末日非零时刻。',
'T3-006':'修订题面明确只列有商品明细月份且不补缺月；按下单月合计price，全部状态，不误套连续日历。',
'T3-008':'明确有商品明细订单等权；每月总price/去重订单数等价于先逐单合计后平均，price非NULL。',
'T3-011':'先建立MIN/MAX之间自然月日历，缺月保留NULL再LAG；缺月或缺基期、零分母返回NULL，仅输出月份和四位比率。',
'T3-013':'MAX下单日期定锚，含锚点日90自然日为[D-89,D+1)，当前得到9440笔，连续90×24小时9529笔属不同问题。',
'T3-015':'按日内小时计订单，指标降序、小时升序NULL最后稳定取1，未把小时当每天时间序列。',
'T3-017':'用时间差EPOCH/86400得到小数天，AVG忽略不可计算时长，两位数；PG16实际执行成功。',
'T3-019':'真实签收晚于预计时间才计超时；未知任一端点不确认超时，不推测状态。',
'T3-020':'registered late_delivery_rate：分母签收时间非NULL，分子确认晚到，四位小数比率；不额外排取消状态。',
'T3-022':'以退款申请时间requested_at分月合计退款单金额，不无故连接订单或只取已完成。',
'T3-024':'以工单resolved_at-created_at计算实际小时均值，不用SLA标准代替耗时。',
'T3-026':'按评价自身创建月份统计评价等权均分，两位，不改用订单月。',
'T3-028':'修订题面明确最大最近登录日期锚点和30自然日；DATE型>D-30等价>=D-29，无超出MAX的日期，当前4321人。',
'T4-001':'自然人跨客户档案汇总订单，customer_id连接但customer_unique_id分组；数量与自然人ID稳定Top10。',
'T4-003':'按商品原始非NULL类目统计不同订单，同单同类目多明细只计一次，跨类目分别计入。',
'T4-005':'卖家维度直接来源明细；按订单主键去重，数量与seller_id稳定Top10。',
'T4-007':'从全部products做反连接，只计不存在价格历史的商品，不重复有多条历史者。',
'T4-009':'每支付记录等权均值，支付方式原值含NULL组；金额两位及NULL最后排序。',
'T4-010':'修订题面明确仅有明细状态、不补无明细状态；全部状态明细price按状态求和，不擅自有效筛选。',
'T4-012':'每条评价等权均分，但Top10按评价记录数；隐藏计数作为排名字段、不额外投影，完整州值裁决。',
'T4-014':'先去重(order_id,payment_type)，再关联评价，防同单同方式多支付重复加权，保留每条评价身份。',
'T4-016':'客户地域沿订单对应customer_id定位，不借自然人ID扩张州归属；LEFT地域保持未知事实，合计全状态price。',
'T4-018':'修订题面明确当前会员等级ID、全状态明细商品金额且不含运费或实付；自然人会员唯一关联，金额无放大。',
'T4-021':'题面指定仅有使用记录的活动；通过唯一券主键及活动主键关联，usage记录计数不要求补零活动。',
'T4-023':'券主键关联使用记录，按折扣类型合计每条真实优惠金额，不用SUM(DISTINCT)。',
'T4-025':'修订为全部仓库LEFT地域；未知大区保留NULL组，原仓库容量只计一次，反例已验证容量100不丢。',
'T4-027':'以inventory为全集及仓库ID维度，每条仓库商品记录等权AVG，SUM与AVG分别输出，不关联维表筛事实。',
'T4-029':'全部工单LEFT订单客户，未知州NULL组；按订单customer_id定位，COUNT(*)保留孤立工单。',
'T4-030':'明确已知总额候选；先按seller_id求和并排除全未知总额，再以两位展示金额及ID稳定Top10。',
'T4-032':'退款身份去重；refund_id+order_id验证退款归属，order_id+order_item_id完整明细关联，非NULL类目每退款单计一次。',
'T4-034':'全部orders为分母、EXISTS任意退款为分子，避免多退款单放大订单；四位小数及NULLIF。',
'T4-036':'明确有商品明细自然人、每州内独立去重，sum price/人数且全状态，不假定同人唯一州。',
'T4-038':'按当前会员等级ID统计CHURN_RISK标签去重会员；多个同代码标签也不会重复会员。',
'T4-041':'brands为全集LEFT当前映射、产品和明细；按ID分组，商品数去重、金额逐明细，零销售NULL不补0，输出60品牌。',
'T4-043':'逐发货单先COUNT(tracking_id)含零轨迹，再按状态等权平均；619张零轨迹单未被删掉。',
'T4-045':'非NULL类目全产品数用于Top10，重量AVG只忽略未知重量；不将未知重量筛选扩散成商品数过滤。',
'T5-002':'明确当前等级ID、全状态price、不含运费；customer_unique_id唯一会员关联，不附加等级名或有效筛选。',
'T5-004':'shipments为全集LEFT仓库，以区域编码输出；发货记录数与不同order_id数各用自己的事实单位。',
'T5-006':'仅分类名称物流延迟作必要过滤；工单会员ID决定等级、订单客户档案决定州，两维NULL保留，完整身份Top20。',
'T5-008':'referrals为全集LEFT两端会员，分别获得推荐人与受邀人当前等级；记录数及奖励金额不因缺会员减少。',
'T5-011':'修订明确只统计当前已关联品牌的明细；不将33,737条无映射明细悄悄当作覆盖全站，品牌ID/金额稳定Top10。',
'T5-013':'题面定义关联商品历史非因果归因且允许跨活动重复归属；活动商品复合主键保证各活动内不重复商品关联。',
'T5-015':'修订以全部轨迹为全集，COUNT DISTINCT轨迹自带shipment_id身份；LEFT多跳维表保留缺订单或缺发货记录，反例两ID保留。',
'T5-017':'全部工单LEFT消息；COUNT DISTINCT工单与COUNT消息主键分别度量，零消息工单和未知维度保留，无会员资格过滤。',
'T5-019':'调价记录等权AVG(delta)，商品去重后另计关联不同订单；两指标分别聚合后合并，防历史×明细放大。',
'T5-020':'全部inventory_movements为全集LEFT仓库地域，不要求inventory存在；大区与类型完整分组身份，记录与数量各自求聚合。',
'T6-001':'两项具名金额按题面顺序同一行；2018有效订单的含运费GMV及price，符合已注册业务口径。',
'T6-003':'有效订单先按自然人聚合，再计算>=2用户占全部有效下单用户，百分数两位、空分母NULL。',
'T6-005':'orders有效集合LEFT会员，保留非会员分母；分子分母都按order_id去重，当前会员身份、百分数两位。',
'T6-007':'有效订单为完整分母，EXISTS退款计不同有效退款订单，百分数两位，零分母NULL。',
'T6-009':'修订明确评价记录等权，沿订单客户档案定位州；有效订单范围，百分数两位后排名、州原值裁决，展示精度反例通过。',
'T6-010':'全部搜索记录为分母，clicked_product_id非NULL为分子；CTR不是下单转化，百分数两位及零分母NULL。',
'T6-012':'有效下单自然人逐月去重，先做2018年12月日历；没有事实月份NULL，不把缺覆盖伪造为0。',
'T6-014':'members为全集，每会员先聚合订单数和GMV，再按等级求人数/均值；1106名无有效订单会员保留，订单0与GMV NULL分别处理。',
'T6-016':'修订题面明确仅销售额已知品牌Top10；当前映射商品全集为动销率分母、有效订单动销为分子，金额两位/百分数两位、ID裁决。',
'T6-018':'先按有效下单自然人聚合并按当前会员身份分组；显式两组骨架保留空组NULL比例，不被INNER会员筛掉非会员。',
'T6-021':'已知GMV自然人按两位展示值及ID排ROW_NUMBER，取CEIL(n*0.2)严格人数；分子GMV/全GMV为四位小数。',
'T6-023':'全部仓库保留，全部流水MAX定锚、90自然日只取type2；records标记区分无出库0与数量全未知NULL，无参照NULL、零分母NULL，五种状态反例验证。',
'T6-025':'自然人全部历史首次有效下单月定义新老客；报告事实截2018后按新/老分别汇总GMV，最后保留12月日历，缺金额NULL。',
}
assert set(reasons)=={x['id'] for x in cases},(len(reasons),len(cases))

candidate_manifest={k:v for k,v in manifest.items() if k.startswith('data/benchmarks/')}
contract_manifest={k:v for k,v in manifest.items() if k!='gold_sql_sha256_by_id' and not k.startswith('data/benchmarks/')}
def digest(x):
    return hashlib.sha256(json.dumps(x,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()

acceptance=[]
for c in cases:
    result=delta.get(c['id'],initial[c['id']])
    evidence_file='final-reviewer-delta-output.jsonl' if c['id'] in delta else 'final-reviewer-batch-output.jsonl'
    acceptance.append({'id':c['id'],'status':'PASS','reason':reasons[c['id']],
      'question':c['question'],'sourceFile':c['source_file'],
      'goldSQLSHA256':manifest['gold_sql_sha256_by_id'][c['id']],
      'observedResultRows':len(result['rows']),'observedResultSHA256':digest(result['rows']),
      'evidence':{'file':evidence_file,'recordId':c['id'],'kind':result['kind']}})

resolved=[
 {'ids':['T6-016'],'issue':'原题面要求无销售/零商品NULL，但Gold只保留已知销售品牌。','resolution':'作者明确新题为仅销售额已知品牌Top10，未将未知品牌偷换成0。','verification':'静态重读最终题面；Gold未改且在全100批次执行。'},
 {'ids':['T3-028'],'issue':'相对30天未在题面声明数据最大日期锚点。','resolution':'作者补最大最近登录日期及30自然日；DATE型Gold>D-30保持。','verification':'R04：MAX锚4321人、current_date锚0人，最终题面消除差异。'},
 {'ids':['T1-013','T2-009','T2-012','T3-015','T4-005','T4-030','T6-009'],'issue':'TopN未用完整身份裁决同展示值，且T4-030可能让NULL总额排最前。','resolution':'作者补完整二级身份排序；T4-030只留已知金额候选；T6-009题面明确每条评价等权。','verification':'当前7题均无跨截断边界并列，T1-013内部375.28有2行并列；F01/F06/F07覆盖身份、NULL、展示精度。'},
 {'ids':['T4-025'],'issue':'INNER地域可能丢失未知大区仓库容量。','resolution':'作者以仓库为全集LEFT地域并明确NULL组。','verification':'旧R11中1仓库0组；最终F02保留NULL大区容量100。'},
 {'ids':['T5-015'],'issue':'多跳INNER丢失缺订单或缺发货记录的轨迹身份。','resolution':'作者以全部轨迹为全集LEFT多跳，计st.shipment_id而非匹配成功实体。','verification':'旧R12中1发货单0组；最终F03保留2个轨迹发货ID，重复轨迹不放大。'},
 {'ids':['T5-011'],'issue':'题面全部明细与只统计当前品牌映射的INNER候选范围不清。','resolution':'作者明确只统计当前已关联品牌的明细。','verification':'R03证实无当前品牌映射明细33,737条、金额4,074,263.37；最终题面明确排除其范围。'},
 {'ids':['T6-023'],'issue':'COALESCE(SUM(quantity),0)把有出库但全部数量未知误作无出库0。','resolution':'作者增加records来源标记，仅无出库记录时补0；全未知数量仍NULL。','verification':'旧R13中记录1/SUM NULL被补0；最终F04、F05分别验证有未知、无记录、已知、无参考日。'},
 {'ids':['T4-018'],'issue':'消费总额未定义price、含运费GMV或实付。','resolution':'作者明确当前等级ID、全状态、不含运费商品金额，不是支付金额。','verification':'R08等级1分别为7,498,209.33/8,734,257.03/8,823,223.01；最终题面唯一指向第一项。'},
 {'ids':['T4-010','T3-006'],'issue':'订单状态/月份的维度全集不明确。','resolution':'作者分别明确有商品明细状态、有商品明细月份，不补无明细状态或缺月。','verification':'T2-005为8状态，T4-010为7状态，created5单无明细；T3-006为24明细月份，与连续日历题T3-011区分。'},
]

metadata={
 'reviewer':'independent_final_reviewer_20261119',
 'scope':'olist-v3-dev-candidate six YAML files, 100 questions; final acceptance after author revisions',
 'date':'2026-11-19','timezone':'Asia/Shanghai',
 'finalCounts':{'total':100,'PASS':100,'DEFECT':0,'AMBIGUOUS':0},
 'finalCandidateManifest':candidate_manifest,
 'finalCandidateManifestSHA256':digest(candidate_manifest),
 'productionContractAndDDLManifest':contract_manifest,
 'productionContractAndDDLManifestSHA256':digest(contract_manifest),
 'actualDDLReadOnlyCatalogSHA256':digest(initial['R01-actual-ddl']['rows']),
 'productionContractUnchangedDuringReview':True,
 'independence':{'priorModelOutputsRead':False,'otherReviewerConclusionsRead':False,'blindOrUnseenMaterialsRead':False,'keysRead':False,'providerCalls':0,'goldOrProductionFilesEditedByReviewer':False,'gitCommitsByReviewer':0},
 'execution':{'fullGoldBatch':execution,'finalDeltaBatch':delta_summary,
   'transactionsReadOnly':True,'statementTimeout':'8s','databaseRole':'text2sql_ro','postgresVersion':'16.15',
   'finalGoldCoverage':{'unchangedGoldFromFullBatch':90,'changedGoldReexecuted':10,'total':100},
   'fullBatchRepeated':False},
 'limitations':['接受的是本次明确题面和固定契约下的SQL语义，不是模型正确率或泛化收益。','作者对题面范围做了明确选择；不能称为最终用户已确认的业务默认。','未将完整Gold结果缺LIMIT判作违规；生产运行行数上限属于另一约束。','结构性反例用CTE/VALUES临时遮蔽真实表，不写库、不改变原数据。'],
}
report={'metadata':metadata,'resolvedObservations':resolved,'items':acceptance,'unresolvedIssues':[]}
(OUT/'final-reviewer-acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')

md=['# 独立最终复核：开发参考100题','',
 '最终接受：**100 PASS、0 DEFECT、0 AMBIGUOUS**。这是作者收到问题反馈后修订版本的接受结论，不是宣称第一稿零缺陷。',
 '',
 '全100条Gold与15条定向只读查询同批执行成功，9.879秒；之后只补执行10条变更Gold及7个最小反例，1.274秒，全部通过。累计不重复全跑，供应商调用0次。',
 '',
 '未读历史模型输出、其他审查结论、blind/unseen内容或Key；未改生产、题库或原数据；未提交Git。生产契约与源DDL在复核期间SHA未变。',
 '',
 '完整逐题接受、证据和SHA见 `final-reviewer-acceptance.json`；源SQL和原始结果按完整批次与修订批次分别保存。',
 '',
 '## 主要反馈及最终处理','']
for r in resolved:
    md.append('- '+','.join(r['ids'])+'：'+r['issue']+' '+r['resolution']+' '+r['verification'])
md+=['','## 每题接受记录','','| ID | 结论 | 结果行数 | 理由 |','|---|---|---:|---|']
md += ['| '+x['id']+' | PASS | '+str(x['observedResultRows'])+' | '+x['reason'].replace('|','/')+' |' for x in acceptance]
md+=['','## 最终输入SHA-256','','| 文件 | SHA-256 |','|---|---|']
md+=['| '+k+' | '+v+' |' for k,v in {**candidate_manifest,**contract_manifest}.items()]
md+=['','候选六YAML manifest SHA-256：`'+metadata['finalCandidateManifestSHA256']+'`。',
 '生产契约与源DDL manifest SHA-256：`'+metadata['productionContractAndDDLManifestSHA256']+'`。',
 '实际数据库表列/约束只读快照 SHA-256：`'+metadata['actualDDLReadOnlyCatalogSHA256']+'`。',
 '',
 '本次数字只用于说明输入语义、事实覆盖与边界验证，不代表模型提升；跨版本题面和Gold已经变化，后续跨版本分差不可直接归因于模型。']
(OUT/'final-reviewer-report.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
assert len(acceptance)==100 and Counter(x['status'] for x in acceptance)=={'PASS':100}
print(json.dumps({'finalCounts':metadata['finalCounts'],'finalCandidateManifest':candidate_manifest,'productionContractAndDDLManifest':contract_manifest,'actualDDLReadOnlyCatalogSHA256':metadata['actualDDLReadOnlyCatalogSHA256']},ensure_ascii=False,indent=2))
