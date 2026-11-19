import json
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parent
questions=json.loads((ROOT/'basic_time-input.json').read_text(encoding='utf-8-sig'))['items']
results=json.loads((ROOT/'basic_time-query-results.json').read_text(encoding='utf-8'))
by_key={r['key']:r for r in results}
P='src/main/java/com/text2sql/agent/generation/PromptTemplate.java'
M='src/main/resources/schema/metrics.yml'
G='src/main/resources/schema/glossary.yml'
A='src/main/resources/schema/ambiguities.yml'
D='src/main/java/com/text2sql/agent/retrieval/DatabaseSchemaReader.java'
items=[]

def item(id,decision,reason,sources,resolution,extra=(),secondary=()):
    q=next(q for q in questions if q['id']==id)
    checks=[by_key[id+':gold']]+[by_key[k] for k in extra]
    obj=dict(id=id,question=q['question'],decision=decision,reason=reason,
             contractSource=sources,
             checks=[dict(sql=c['sql'],result=c['result'],evidenceKey=c['key']) for c in checks],
             proposedResolution=resolution)
    if secondary: obj['secondaryIssues']=list(secondary)
    items.append(obj)

item('T1-001','KEEP',
 '“一共多少笔订单”以orders的一行一订单为对象，主键order_id保证COUNT(*)不会把同一订单重复算入；现值99,441。未要求有效或已送达，不能因为枚举中存在取消状态就自动删订单；空表COUNT为0，无分母和时间边界。',
 [P+' 规则7、10',M+' valid_order的aliases与entityPolicies末段','实际schema：orders_pkey'],
 '保留。若业务想默认只看有效订单，应明确改题与对外契约，不能静默把全部订单变成有效订单。')
item('T1-003','KEEP',
 '对象是已取消订单，不是退款单；order_status=canceled直接对应订单状态，得到625笔。过滤后仍保持一行一订单，不需要关联退款或明细，也不应叠加排除取消的有效订单条件；无匹配时返回0。',
 [G+' orders.order_status',P+' 规则7、10、15'], '保留已取消订单的明确状态覆盖。')
item('T1-005','KEEP',
 '题目明确按订单明细“条”计数，order_items复合主键标识每条明细，price>1000严格排除等于1000，当前844条。这里不是不同订单数、商品数或结算毛额，单价numeric且NOT NULL；不增加订单状态过滤。',
 [P+' 规则10、16','实际schema：order_items(price numeric NOT NULL；复合主键)'], '保留；价格阈值不改成>=。')
item('T1-007','KEEP',
 '卖家实体由sellers.seller_id主键定义，COUNT(*)给出3,095个，包括尚无商品明细的卖家。直接数实体表恰好保留零销售卖家，不需要从order_items反推“有交易卖家”。',
 [G+' sellers',P+' 规则10','实际schema：sellers_pkey'], '保留实体全集计数。')
item('T1-009','KEEP',
 '当前产品明确规定裸客户数/属性分布默认按客户档案记录，而不是自然人去重。SP有41,746条客户档案、40,302个自然人，Gold选前者符合这个默认；若按行业直觉改成去重人数会改变度量。',
 [M+' entityPolicies.customer_identity_v2(populationCountUnit=ROW_KEY)',G+' customers.customer_state'],
 '按当前产品默认保留；题面可补“客户档案记录”提高跨产品可迁移性。',['basic-identity-nulls'])
item('T1-010','KEEP',
 '问题要的是实际出现的订单状态种数，COUNT(DISTINCT order_status)得到8，不是状态定义字典的潜在取值个数。实际列NOT NULL，不存在NULL是否算一种的分歧；订单全状态都应参加状态种类统计。',
 [P+' 规则7、10','实际schema：orders.order_status NOT NULL'], '保留实际状态去重计数。')
item('T1-012','KEEP',
 'Gold按明细单价排序，投影完整明细身份(order_id,order_item_id)和price，符合“哪些明细”。第10名价格3,999.90，之上9条、该价格仅1条，没有跨LIMIT边界的并列；若未来同价跨界，应允许任取10条或声明稳定次序。',
 [P+' 规则8、10、16','实际schema：order_items复合主键'], '保留当前参考结果；并列规则作为未来稳定性约定，不把现库无并列的题判错。',['T1-012:cutoff'])
item('T1-014','KEEP',
 '一行一条评价，按review_score=1计11,424条；同一订单有多条评价也应逐条计数，因此不对order_id去重。题目是记录数量，没有好评率分母或订单状态要求。',
 [G+' order_reviews',P+' 规则10'], '保留按评价记录计数。')
item('T1-016','AMBIGUOUS',
 '最大分期数是24，但实际有18笔支付、分属18个订单并列。Gold返回某一order_id+24，24本身正确；题面与当前实体寻优规则都未约定18个身份中选谁，不能把其中一笔变成唯一标准身份。另外分期字段允许NULL，PostgreSQL DESC默认NULLS FIRST，合成反例会选中未知值。',
 [P+' 规则8（实体寻优）','实际schema：order_payments主键(order_id,payment_sequential)，payment_installments可空'],
 '明确任选一个已知最大分期支付并以完整支付主键稳定排序，或只问最大期数并输出24；不将另一个并列身份自动判错。加NULLS LAST或排除未知值须与题面“已知分期”语义一致。',
 ['T1-016:ties','T1-016:null-counterexample'],
 ['现库null_installments=0，NULL排序反例证明的是允许状态下的缺口，不宣称本次结果选到了NULL。','order_id不是全库支付记录的完整主键；当前18笔最大值恰好分属不同订单，暂未造成身份碰撞。'])
item('T1-018','KEEP',
 '运费属于订单明细字段freight_value，问“条”对应明细而非订单。严格>50得到4,605条，等于50不计；字段numeric NOT NULL，直接计数不引入商品或支付连接放大。',
 [G+' order_items.freight_value',P+' 规则10、16'], '保留明细运费阈值计数。')
item('T1-021','KEEP',
 '对象是卖家所在地，不是客户收货州；seller_state=RJ返回171个卖家。COUNT卖家实体表包括没有订单的RJ卖家，无需交易连接，也不涉及地区主体反问。',
 [G+' sellers.seller_state',P+' 规则15'], '保留RJ物理州代码与卖家表。')
item('T1-023','KEEP',
 '“没有填写类目”按当前NULL口径解释为product_category_name IS NULL，得到610个商品；空字符串现库为0。维度默认排除NULL不能覆盖这道明确询问NULL的题，亦无需关联类目翻译表把未翻译值混入。',
 [M+' dimensionPolicies.product_category_nulls_v1',P+' 规则7',G+' products.product_category_name'],
 '保留；如果业务将空白字符串也视为未填写，需明确扩展题意，不能把未翻译类目算作未填写。',['basic-identity-nulls'])
item('T1-025','KEEP',
 'members.status=2由库内列注释和词典共同定义为冻结，当前5,724人。members.customer_unique_id有非空唯一约束，因此数会员行与已知会员身份一一对应；不需要再从customers连接而放大同一个会员。',
 [G+' members.status','实际schema：members.status注释、customer_unique_id UNIQUE NOT NULL'], '保留冻结会员实体计数。')
item('T1-027','KEEP',
 '仓库实体全集由warehouses主键定义，COUNT(*)返回5；题目没有要求存在库存、发过货或容量大于0。直接数仓库可保留零事实仓库，空表也会返回0。',
 [G+' warehouses',P+' 规则10'], '保留仓库实体全集。')
item('T1-029','KEEP',
 '题干明确“已经完成退款”，库内status=4表示已退款，计得4,177笔退款单，身份为refund_id而非order_id。无需因为同一订单可能多张退款单而去重订单，也不能改成只看completed_at，因为该时间与状态存在缺失差异。',
 [G+' refunds.status','实际schema：refunds.status注释',P+' 规则15'], '保留明确的已退款状态口径；时间质量问题另行治理，不能暗改本题对象。',['T2-022:status'])
item('T1-030','KEEP',
 '“因为质量问题发起”对应退款原因QUAL，当前1,925张退款单；“发起”并不要求完成或批准。Gold没有把被申请记录误缩成已退款记录，也没有把退款明细条数当退款单数。',
 [G+' refunds.reason_code','实际schema：refunds.reason_code注释',P+' 规则7、15'], '保留原因筛选，不补完成状态。')
item('T1-032','KEEP',
 'discount_type=fixed明确映射满减，coupons一行一张券，当前150张。题目统计券的定义数量，不是发放/领取/核销次数，不应转去coupon_usages或按coupon_code多次使用加总。',
 [G+' coupons.discount_type','实际schema：coupons主键及coupon_code唯一约束'], '保留优惠券定义记录计数。')
item('T1-034','KEEP',
 '问题要工单分类定义的个数，ticket_categories有6条主键不同的分类。直接数定义表可包含暂时没有工单的分类；按support_tickets.category_id去重反而可能漏掉零工单分类。',
 [G+' ticket_categories',P+' 规则10'], '保留分类定义全集，不改成已使用分类数。')
item('T1-036','KEEP',
 'movement_type=2由列注释定义为出库，当前16,273条流水。这里要记录条数，既不是SUM(quantity)出库件数，也不是去重商品数；退货入库3、盘亏4不能并作普通出库。',
 [G+' inventory_movements.movement_type','实际schema：movement_type枚举注释'], '保留出库流水记录计数。')
item('T1-038','KEEP',
 'regions以region_code主键逐州存储，macro_region=Sudeste得到4个州。题目是纯地区字典问题，没有交易事实地域主体歧义；不需要按客户/卖家来源反问，也不能把geo坐标点当州。',
 [G+' regions',A+' 大区.triggerPatterns（不触发纯字典题）','实际schema：regions_pkey'], '保留地区字典计数。')

item('T2-002','KEEP',
 '“所有订单”明确包含全部状态；运费事实在order_items，每条明细的freight_value汇总为2,251,909.54。订单无明细时没有运费事实，SUM不需要用多表重复铺开；金额列numeric，现有两位小数精度可保持。空表SUM为NULL而非伪造0。',
 [P+' 规则7、12、16',G+' order_items.freight_value'], '保留全部明细运费合计，不叠加有效订单过滤。')
item('T2-004','REFERENCE_DEFECT',
 '题目是平均“每个订单”的明细数，Gold却先从order_items聚合，只保留有明细的订单。现库99,441个订单中775个没有明细，112,650条明细应除以99,441，四舍五入1.13；Gold的有明细订单条件均值为1.14，实际已不同。',
 [P+' 规则9、10（事实全集与零事实）','实际schema：orders主键，order_items外键不保证每单必有明细'],
 '以orders LEFT JOIN明细后按order_id计COUNT(oi.order_item_id)，再取AVG；或者将题面改为“有明细的订单平均多少条”，那样现Gold可保留。',['T2-004:denominator'])
item('T2-006','KEEP',
 '题目明确支付记录数，按payment_type分组COUNT(*)，不按订单去重或把分期数当支付笔数。一次订单可组合支付且支付主键含payment_sequential，现有5种方式含3条not_defined；没有要求名称翻译或排除未知方式。',
 [G+' order_payments',P+' 规则8、10、17','实际schema：order_payments复合主键'], '保留各支付方式的记录计数；未出现的方式是否补0不属于本题明确要求。')
item('T2-008','KEEP',
 '当前customer_identity_v2规定按属性的裸客户数量默认客户记录数，Gold据此按州计数。第5名PR为5,045，只有1州在该计数，前面4州，没有跨Top5边界的并列；自然人去重是另一个指标。',
 [M+' entityPolicies.customer_identity_v2',P+' 规则8、10'], '按现有客户记录默认保留；若问各州客户人数须显式使用customer_unique_id。',['T2-008:cutoff','basic-identity-nulls'])
item('T2-011','KEEP',
 '“商品销售总额”按order_items.price逐明细累加，不含freight_value，也不是settlements.net_amount。seller_id保持实体身份，ROUND两位后按金额取10；第10位135,171.70无边界并列。此普通商品金额题没有命中GMV或有效订单的明确别名，不据valid_order注释扩大筛选范围。',
 [P+' 规则7、9、12、16',A+' 销售额.hints含商品销售额',M+' valid_order与sales_without_freight别名范围'],
 '保留按明细商品金额的Top10；若题意要净成交商品额，另明确订单状态。',['T2-011:cutoff'])
item('T2-013','KEEP',
 '请求列举两个非时间业务子集的计数、没有要求状态分布，Gold一行依次输出delivered=96,478与canceled=625，符合当前条件聚合形状约定。过滤写在各COUNT内部，某组为空仍为0，不会因WHERE只保留另一组而丢计数。',
 [P+' 规则17（非时间子集计数）',G+' orders.order_status'], '保留横向两列计数，不能用有效订单过滤删除取消子集。')
item('T2-015','AMBIGUOUS',
 '按members.level_id分组的平均积分正确保持会员等权，现有4等级全部有会员且points全部非空，Gold当前没有漏已存在会员。但“每个会员等级”是否包含member_levels中零会员等级，当前题面/默认未确定；若需要等级全集，直接分组members会漏掉该等级的NULL均值。',
 [P+' 规则8、9、12',G+' members.level_id及member_levels','实际schema：members到member_levels外键'],
 '明确“按现有会员记录等级分组”可保留Gold；若明确所有等级定义，改从member_levels LEFT JOIN，零会员平均值为NULL而非0。',['T2-015:coverage'])
item('T2-017','KEEP',
 'regions一行一个州，按macro_region计数能直接回答各大区州数，27州共5区；当前4州的两个大区并列只影响未要求的显示顺序，不改变地区与计数结果。纯字典统计不触发交易地域主体歧义。',
 [G+' regions',A+' 大区.triggerPatterns',P+' 规则8、10'], '保留地区字典聚合；若要求固定显示序才补确定次排序。')
item('T2-019','KEEP',
 '每个活动自身只有一条campaigns记录，budget是活动级预算事实，不需要因为中文“总额”就跨活动SUM或关联活动商品反复累加。Gold输出campaign_id与该行budget，保留所有活动包括无商品活动；ID默认展示符合当前契约。',
 [P+' 规则8、9（预算不随子表累加）',G+' campaigns.budget','实际schema：campaigns_pkey'], '保留活动级预算；不添加活动名称或将同名活动合并。')
item('T2-020','KEEP',
 '按优惠券定义的discount_type逐张计数，当前fixed和percent各150张；“每种”明确要求维度行。两组计数同为150并列，题目没有要求次序，因此两行前后交换不构成业务错误，不应从这个并列制造唯一排列。',
 [G+' coupons.discount_type',P+' 规则10、17'], '保留两个类型的券定义数量；若评分依赖行顺序，应独立声明稳定次序而非归因模型。')
item('T2-022','KEEP',
 '裸“退款总金额”没有说已退成功，Prompt规则7禁止自行补状态，Gold对全部退款单refund_amount求和649,715.62符合记录总额解释。已退款status=4只合计458,711.50，二者不同；不能凭“退款”一词把后者强制为唯一答案。',
 [P+' 规则7、12',G+' refunds.refund_amount与status'],
 '按当前不自动加状态的契约保留；若业务要现金已退金额，应把成功状态或完成时间口径写进题目。',['T2-022:status'])
item('T2-024','AMBIGUOUS',
 'inventory一行一个仓库×商品，COUNT(*)确实是库存记录条数，当前5仓均有记录，因此Gold包含全部现有仓库。题目“每个仓库”未明确仓库全集还是有库存记录仓库；无记录仓库合法存在且合成例中仓库2应为0，Gold会完全没有该行。',
 [P+' 规则9、10',G+' inventory粒度及warehouses','实际schema：inventory复合主键'],
 '明确“所有仓库含无库存记录”时从warehouses LEFT JOIN，计COUNT(i.product_id)；若仅已有库存仓库，保留Gold。不把现库全覆盖当成永久约束。',['T2-024-026:warehouse-coverage','T2-024-026:zero-counterexample'])
item('T2-026','AMBIGUOUS',
 'SUM(quantity)按仓库累计库存件数，没有把库存记录数量当库存量，现库5仓的quantity均已填写。歧义在零库存记录仓库是否要列出：Gold从inventory分组会缺整行；另外“无记录”可按0件库存解释，但有记录且quantity全NULL意味着未知，不应一律COALESCE成0。',
 [P+' 规则7、9',G+' inventory.quantity及warehouses','实际schema：inventory.quantity可空'],
 '明确仓库全集及无记录为0后用仓库侧LEFT JOIN，并区分无记录与未知quantity；若只要求库存事实分布，保留Gold。',['T2-024-026:warehouse-coverage','T2-024-026:zero-counterexample'])
item('T2-028','KEEP',
 '一行shipments是一张发货单，carrier是承运商维度，COUNT(*)得到各承运商承担的发货单数。订单可能拆成多张发货单，因此不应去重order_id；“承担”没有要求签收状态或存在轨迹，直接数发货事实保留未完成发货单。',
 [G+' shipments.carrier',P+' 规则7、10'], '保留按发货单记录统计，不关联一对多物流轨迹。')
item('T2-031','KEEP',
 '工单按support_tickets.priority原始值分组，1至4分别为低/中/高/紧急；Gold数工单记录并按优先级升序，当前1,353/2,581/2,643/1,357。题目没有要求优先级中文名称，默认保留有业务含义的状态值，也不该只统计已解决工单。',
 [G+' support_tickets.priority',P+' 规则7、8、10'], '保留优先级编码与工单数量两列。')
item('T2-033','AMBIGUOUS',
 'Gold合计所有结算净额4,821,914.63；“支付给”可理解为已实际打款，也可口语指应结净额。若按status=3已打款则3,905,258.13，按paid_at非空则3,840,854.48，且状态/时间有1,796条不一致。当前词典net_amount同时别名应结/实结，无法唯一决定哪一套凭据优先。',
 [G+' settlements.net_amount、status、paid_at',P+' 规则7、12','实际schema：settlements.status注释'],
 '先把题目确定为“全部结算单应结净额”或“已打款净额”，后者再明确以status=3还是paid_at非空为准。不能把任一现库数字当不言自明的唯一Gold。',['T2-033:status','session-and-paid-bases'])
item('T2-035','KEEP',
 '每条search_logs代表一次搜索，按keyword聚合COUNT(*)是搜索次数，不是独立搜索会员数或result_count结果数量。第10关键词774次，之上9个且该计数唯一，当前没有Top10边界并列；keyword现库无NULL。',
 [G+' search_logs',P+' 规则10'], '保留按搜索日志次数排名；不把无点击搜索删掉。',['T2-035:cutoff','basic-identity-nulls'])
item('T2-037','COINCIDENTAL_EQUIVALENCE',
 '题目对象是订单，Gold却数customers记录。现库没有无订单客户关联键，也没有某customer_id关联多订单，所以各州恰好相等，SP等5州阈值结果通过；但orders.customer_id只有外键、没有唯一约束，不能由此推出客户档案恒等订单。只读反例1个SP客户关联5,001订单时，真实应入选而Gold只数1。',
 [P+' 规则10（计数身份）',M+' customer_identity_v2','实际schema：orders_customer_id_fkey并非UNIQUE'],
 '从orders关联对应customers记录属性后按订单计数，HAVING COUNT(*)>5000；当前一对一等价仅是快照事实，不是正确性的证明。',['T2-037:state-comparison','T2-037:multiplicity','T2-037:counterexample'])
item('T2-039','COINCIDENTAL_EQUIVALENCE',
 '当前好评>=4、分母所有评价，原始小数比率四位输出0.7707，既未乘100也未按订单去重，符合未注册普通比例默认。缺陷在空评价集：COUNT(*)为0，Gold确定除零报错；同一只读空集加NULLIF后返回NULL。现库非空掩盖了这个当前契约明确要求的边界。',
 [P+' 规则10、12（普通比例四位及分母零NULLIF）',G+' order_reviews.review_score'],
 '保持当前分子/分母与单位，仅把分母改NULLIF(COUNT(*),0)。不把未评分评价悄悄排出“所有评价”分母。',['T2-039:empty-counterexample','T2-039:empty-safe-control','basic-identity-nulls'])
item('T2-040','KEEP',
 '“每个支付方式收上来的钱”对应每条支付记录的payment_value汇总，方式维度不变，不是商品金额、佣金或整单含运费金额。当前credit_card为12,542,084.19，not_defined为0.00；numeric求和两位，组合支付各归其方式，不再关联订单明细而放大。',
 [G+' order_payments.payment_value',P+' 规则9、12'], '保留支付事实的方式合计，不额外限定有效订单或成功支付状态。')

item('T3-001','KEEP',
 '按下单时间DATE_TRUNC(month)保留年+月并升序，不把2017与2018同月份混合，统计订单记录而非商品条数。当前返回实际有订单的25个月，2016-11没有记录；未给连续日历覆盖默认，所以不把缺少0月份本身判为确定错误。',
 [P+' 规则7、13、17',G+' orders.order_purchase_timestamp'],
 '保留已有订单月份分布；若产品要连续月份含0，需补明确日历范围与覆盖约定。',['T3:time-coverage'],
 ['与T3-010不同：本题没有“上个月”计算，缺月只影响展示覆盖，不会把另一个月当作上月。'])
item('T3-003','KEEP',
 'DATE_TRUNC(quarter,下单时间)按年区分季度，输出季度起始时间戳及订单数并升序，正合当前季度默认。不能只EXTRACT(quarter)把不同年Q1合并，也没有有效订单限定。',
 [P+' 规则7、13、17'], '保留季度起始时间戳维度及订单数量。',['T3:time-coverage'])
item('T3-005','KEEP',
 '按PostgreSQL周一为起点的DATE_TRUNC(week)分组，当前最大周唯一：2017-11-20起始、3,008订单。week_start转DATE仍保留实际周身份，未改成字符串或丢失年份；当前没有冠军并列，单行LIMIT1能回答题面。',
 [P+' 规则8、13',G+' orders.order_purchase_timestamp'], '保留周起始日与数量；若未来出现并列第一，需明确选一周还是全列。',['T3-005:ties'])
item('T3-007','KEEP',
 '运费来自order_items，月份归订单下单事件，JOIN orders只补业务时间且订单主键唯一，不会放大明细运费。Gold是“存在运费明细的月份”分布；实际2018-10虽有4个订单但无明细，因此没有该月运费行，属于未约定的零事实月份覆盖，不能单凭此宣布求和错误。',
 [P+' 规则9、12、13、16',G+' order_items.freight_value与orders.order_purchase_timestamp'],
 '保留现有运费事实聚合；若明确要每个有订单月份含零运费，需以订单月份日历保留该月并区分无明细与未知运费。',['T3-007:zero-months'],
 ['目前题面没有要求完整月份/含0，因此该覆盖差异保留为说明，不计确定缺陷。'])
item('T3-009','AMBIGUOUS',
 '2018年范围采用>=2018-01-01且<2019-01-01，完整包含年末时刻，时间过滤正确。Gold仅输出1至10月，11、12月无订单被省略；“2018年每个月”也能合理要求全年12行并将无事实月填0。当前契约规定分组格式，却没有确定完整年日历覆盖。',
 [P+' 规则13、14、17',D+' 历史快照说明'],
 '明确“有订单的月份”可保留Gold；若为完整2018年月报，生成12个月并LEFT JOIN订单计数。零事实不自动证明数据源已完整观测到年末。',['T3-009-012:year-months','T3:time-coverage'])
item('T3-010','REFERENCE_DEFECT',
 'LAG按结果的上一行移动，不识别自然月；源数据2016-11无订单，Gold把2016-12的1笔减2016-10的324笔，得到-323，这不是相比上个月。11月在同一历史区间内为0，12月自然月变化应为+1；不能只加NULLIF或排序修复这个月身份错位。',
 [P+' 规则13、17（时间维度保持实际月份）','题面“上个月”的相邻自然月语义'],
 '先构造min至max的连续月份序列，LEFT JOIN订单月计数并对无订单月计0，再LAG；或按m-interval 1 month精确连接，显式定义缺失月的0/未知策略。首月缺历史基期应保持NULL。',['T3-010:gaps'])
item('T3-012','AMBIGUOUS',
 'Gold按相同月份号对齐2018与2017，输出绝对订单增量，1月6,469笔，这是一种合理“增长多少”解释；当前GMV同比增长率指标不适用于订单量，不能强制乘100。问题在2018“每个月”是否全年覆盖及无当年月份：Gold只保留双方有记录的1至10月，11/12月整行消失，题面未授权只取交集。',
 [M+' yoy_growth.requiredContextAny与notes（只定义GMV率）',P+' 规则8、12、13、17'],
 '明确绝对增加笔数、只比较有数据的同月，可保留Gold；若全年月度比较，用12个月日历对齐，并区分0订单与快照未覆盖。百分比必须另说增长率及基期零处理。',['T3-009-012:year-months'])
item('T3-014','CONTRACT_CONFLICT',
 '源事件是订单下单时间，先全表MAX得到2018-10-17 17:30:18作为共同锚点是正确的；但Gold用(A-90天,A]滚动窗口，题面没有要求滚动90×24小时。当前规则14默认包含参照日的90个自然日，即[2018-07-20 00:00,2018-10-18 00:00)。两者现库差89个订单/98条明细，商品额1,273,118.74与1,262,279.86，相差10,838.88。',
 [P+' 规则14（自然日与滚动时长）',D+' readDataProfile事件源全表MAX',A+' 销售额.hints含商品销售额'],
 '若保持题面，Gold改自然日窗口；若保持Gold，题面明确“以最后下单时刻回溯90×24小时，左开右闭”。这是默认契约冲突，不宣称滚动90天在业务上天然错误。',['T3-014:window-difference'],
 ['商品销售额已明确不含运费；不应再触发销售额含运费反问。','DATE_TRUNC周维度可能包含窗口首尾的部分周；题面没有要求完整自然周，不应把这些周扩大成整周筛选。'])
item('T3-016','KEEP',
 'Gold以DOW整数输出星期维度，0为周日、1为周一，当前唯一冠军1=周一、16,196笔，符合当前默认编号。虽然口语问星期几可用中文，产品已经明确默认DOW；不能把中文或ISO7编号当唯一合理展示。',
 [P+' 规则13（DOW默认）'], '保留DOW与订单数；若只要星期名称或只要一列，需明确覆盖默认结果形状。',['T3-016:ties'])
item('T3-018','KEEP',
 '当前delivery_days定义为下单到签收，不是发货到签收；Gold按订单关联键读取该订单客户州，EPOCH/86400保留小数天，再按订单等权求均值两位。实际96,476笔签收时间已知，缺起点/负时长均0；PostgreSQL16中EXTRACT返回numeric，Gold无显式cast仍可运行，不应照Prompt浮点概述误判语法错误。',
 [M+' delivery_days',M+' customer_identity_v2交易属性来源',P+' 规则12、16','实际schema：orders时间为timestamp without time zone'],
 '保留现Gold；不能改走客户自然人的全部档案取州，也不应额外限定order_status=delivered来替代真实签收时间。',['T3-018:quality'],
 ['每州均值只针对可计算配送事实，零已签收订单的州是否列NULL未被明确要求；无需把未签收者补0参与平均。'])
item('T3-021','KEEP',
 '退款单自身事件为requested_at，Gold按申请月计退款记录，既不按订单购买月也不按completed_at，从而保留已申请但未完成退款。当前退款时间全已知；未规定连续月份日历，不补无申请月份。',
 [G+' refunds.requested_at(退款时间/申请月份)',P+' 规则7、13'], '保留退款申请事件月计数；若要完成退款月数，必须明确完成事件。',['T3:time-coverage'])
item('T3-023','KEEP',
 '“新增客服工单”明确对应support_tickets.created_at，按自身创建月COUNT工单，不需要借用orders下单时间或消息创建时间。实际创建时间无NULL；只要新建工单就计，不要求关联订单或已解决，避免INNER JOIN订单漏无订单工单。',
 [G+' support_tickets.created_at',P+' 规则7、13'], '保留工单自身创建月计数。',['T3:time-coverage'])
item('T3-025','KEEP',
 '2018上半年用[2018-01-01,2018-07-01)包括整个6月30日，按orders一行一订单计数。州沿o.customer_id定位对应客户档案，FK指向唯一客户键不放大订单；不能通过customer_unique_id连同人其他州档案，也未授权排除取消单。',
 [P+' 规则7、10、14',M+' customer_identity_v2交易属性来源','实际schema：orders到customers外键'], '保留购买时间半年度边界与交易所属州。')
item('T3-027','KEEP',
 '会员注册事实与时间是members.register_date，Gold按注册月计会员实体，未把订单新客月当会员注册月。该列date经PostgreSQL DATE_TRUNC会返回带时区月起始时间（当前Asia/Shanghai），不同于orders的无时区时间戳；当前契约允许DATE_TRUNC自身类型，未要求二者强行同型。无注册日期无法归入月份，现库也没有该NULL。',
 [G+' members.register_date',P+' 规则13','实际schema：members.register_date date'],
 '保留本表注册日期分组；对外统一月份类型应作为单独契约决策，不能假称所有DATE_TRUNC输出都是无时区时间戳。',['T3:time-coverage','session-and-paid-bases'])
item('T3-029','KEEP',
 '当前词典把佣金月份映射settlements.period_start，Gold按账期开始月累计结算单commission，既不关联结算明细重复加总，也不使用orders下单月。这里按当前账期应计解释“收入”，未明确现金到账，因此不私加status=3或paid_at条件；日期列DATE_TRUNC为带时区月起始。',
 [G+' settlements.period_start及commission',P+' 规则7、9、12、13'],
 '按当前账期默认保留；若业务改为到账佣金收入，要明确paid_at事件与状态/时间冲突处理。',['T3:time-coverage','session-and-paid-bases'])
item('T3-030','KEEP',
 '题干明确“有效订单”，命中valid_order，Gold用NOT IN(canceled,unavailable)过滤，然后按下单季度起始时间戳分组升序。orders.order_status非空，NOT IN不存在NULL状态三值逻辑漏算；没有关联明细，零明细但有效的订单仍会计入。',
 [M+' valid_order',P+' 规则7、10、13、17','实际schema：orders.order_status NOT NULL'], '保留有效订单过滤及季度时间维度。')

assert len(items)==56
assert {i['id'] for i in items}=={q['id'] for q in questions}
for obj in items:
    candidate_key=obj['id']+':proposed-gold'
    if candidate_key in by_key:
        evidence=by_key[candidate_key]
        obj['proposedGoldSql']=evidence['sql']+';'
        obj['proposedGoldSqlStatus']='只读诊断候选，已在现库执行；未改原始Gold。'
        obj['checks'].append(dict(sql=evidence['sql'],result=evidence['result'],evidenceKey=candidate_key))

questions_to_clarify={
 'T1-016':('仅考虑分期数已知的支付，找出分期数最多的一笔；并列时按order_id、payment_sequential升序取第一笔，输出这两个支付标识及分期期数。',
 ['稳定选择一笔已知最大值支付，输出完整主键与期数。','只问最大分期期数，不要求是哪笔支付。','列出全部并列最大分期支付。']),
 'T2-015':('按现有会员记录的会员等级编号分组，每个等级的平均已知积分是多少？',
 ['只按会员记录里实际出现的等级分组，保留现Gold。','列出member_levels所有等级，零会员等级平均积分为NULL。']),
 'T2-024':('有库存记录的每个仓库，各有多少条仓库×商品库存记录？',
 ['仅列已有库存记录仓库，保留现Gold。','列出所有仓库，零库存记录仓库计0。']),
 'T2-026':('有库存记录的每个仓库，其库存数量quantity合计是多少？',
 ['仅列已有库存事实仓库，SUM按已知quantity计算，保留现Gold。','列出所有仓库，无记录为0；有记录但quantity全未知仍为NULL。']),
 'T2-033':('全部卖家结算单的应结净额net_amount合计是多少？',
 ['全部结算单应结净额，保留现Gold。','只统计status=3的已打款结算单净额。','只统计paid_at非空的结算单净额。']),
 'T3-009':('2018年实际有订单的各个月份，订单量是多少？按月份排序。',
 ['只输出有订单月份，保留现Gold。','完整输出2018年12个月，空月份按已观测零订单补0。','仅输出快照实际覆盖期内的连续月份；不将快照后月份当已观测0。']),
 'T3-012':('2018年有订单且2017年同月也有订单的各个月份，订单量相比2017年同月增加多少笔？只输出月份编号和增加笔数。',
 ['双方有数据月份的绝对增加笔数，保留现Gold。','全年12个月的绝对增加笔数，另定快照未覆盖月份是0还是未知。','按同比增长率输出，另明确百分比/小数单位和基期零值处理。'])
}
for obj in items:
    if obj['id'] in questions_to_clarify:
        q,options=questions_to_clarify[obj['id']]
        obj['proposedQuestion']=q
        obj['pendingChoices']=options
        obj['proposedQuestionStatus']='消歧候选，不替业务确认，不改原题。'
counts=dict(Counter(i['decision'] for i in items))
missing_limit=[q['id'] for q in questions if 'LIMIT' not in q['gold_sql'].upper() and ('GROUP BY' in q['gold_sql'].upper() or q['id']=='T2-019')]
review={
 'reviewer':'/root/review_basic_time',
 'provenance':dict(freshContext=True,participatedInAuthoringOrTuning=False,readPriorAudit=False,externalModelCalls=0),
 'scope':dict(input='reports/independent-contract-review-20261119/basic_time-input.json',questionCount=56,tiers=['T1','T2','T3'],productionEdits=False,gitCommit=False),
 'method':dict(allowedCurrentContractSources=[P,D,M,A,G],readOnlyDatabase='text2sql-postgres/olist via text2sql_ro',transaction='BEGIN READ ONLY; SET LOCAL statement_timeout=8s; ... ROLLBACK',checksExecuted=len(results),goldExecutionSuccesses=56,syntheticCases='Only CTE/VALUES/generate_series in read-only SELECT, no data writes',evidenceFile='basic_time-query-results.json'),
 'decisionDefinitions':{
  'KEEP':'未发现足以否定Gold的具体问题；合理的当前默认可保留。附注不表示所有潜在库状态均已证明。',
  'REFERENCE_DEFECT':'题意关键事实对象/时间关系确定，且现库已出现可证错误。',
  'CONTRACT_CONFLICT':'Gold体现一种合理解释，但与允许读取的当前产品明确默认不同。',
  'AMBIGUOUS':'题面/当前默认未唯一约定关键覆盖、凭据或并列身份，不能单方面宣判Gold错误。',
  'COINCIDENTAL_EQUIVALENCE':'Gold当前数值通过，但独立合法反例证明关键语义或空集行为不等价；不据快照相同泛化。'},
 'summary':counts,
 'crossCuttingNotes':[
  'valid_order是按别名匹配注入的指标；不能仅凭其notes中广义默认一句，就给所有裸记录计数强加有效订单过滤。Prompt规则7同时明确禁止无依据事实过滤。',
  '当前实体身份契约明确裸客户数为客户记录数；自然人与订单关联键仍需区分。此默认自身可以被业务质疑，但本审查不悄悄改默认。',
  '月份是否补零没有全局明确默认。一般观测事实的按月分组未被强判错；明确全年每月题列为歧义；涉及上个月运算则必须保持真正相邻月份。',
  'Prompt规则3要求多行带LIMIT，而若干Gold未带。这里只将其记录为语法/生成约定差异，不把语义完整的聚合结果一概判错；不能随意用小LIMIT截断完整分组。',
  '当前PG16的EXTRACT(EPOCH...)为numeric；date参数DATE_TRUNC为timestamptz，timestamp参数为timestamp。实际类型检查优先于提示文字中的概述。'],
 'multiRowGoldWithoutLimit':missing_limit,
 'items':items
}
(ROOT/'basic_time-review.json').write_text(json.dumps(review,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
lines=[
 '# 独立契约复核：T1/T2/T3 原正式题',
 '',
 '审核者：`/root/review_basic_time`。独立新上下文，未参与出题、Gold编写、Prompt或规则调参；未读历史模型输出、历史审计和其他复核结论。外部模型调用 **0**。',
 '',
 '范围为输入包全部 **56题**。仅读取允许的当前Prompt、SchemaReader、metrics/ambiguities/glossary和实际schema。使用只读账号、每条8秒超时、只读事务；56条Gold全部执行成功。没有修改生产代码、原评测数据、评分器、门槛，也没有Git提交。',
 '',
 '| 主分类 | 题数 |',
 '|---|---:|',
 *[f'| {k} | {counts.get(k,0)} |' for k in ['KEEP','REFERENCE_DEFECT','CONTRACT_CONFLICT','AMBIGUOUS','COINCIDENTAL_EQUIVALENCE']],
 '',
 '确定发现：',
 '',
 '- **T2-004**：775笔零明细订单被分母遗漏。112,650条明细 / 99,441笔订单 = **1.13**，Gold为仅有明细订单均值 **1.14**。',
 '- **T3-010**：缺2016-11时，LAG把10月324笔当成12月1笔的上月，Gold **-323**；按11月零订单应为 **+1**。',
 '- **T3-014**：滚动90天与当前自然日默认冲突。锚点2018-10-17 17:30:18，当前默认起点2018-07-20 00:00。89订单、98明细受影响，商品额 **1,273,118.74 vs 1,262,279.86**，差 **10,838.88**。滚动计算本身不是天然错误，需改题或改Gold达成一致。',
 '- **T2-037**：现库客户关联键与订单恰好1:1，但数据库没有该唯一约束。1客户关联5,001订单的只读反例证明，数customers不能普遍回答订单数阈值。',
 '- **T2-039**：当前0.7707的比例单位正确；但空评价集明确除零，加NULLIF才得到契约要求的NULL。',
 '',
 '保留的不确定性：',
 '',
 '- T1-016最大24期有18笔并列，Gold选出的身份不唯一；可空分期值还存在DESC默认NULLS FIRST的边界。',
 '- T2-015/T2-024/T2-026需明确等级/仓库全集还是仅有事实对象；现库全覆盖不能证明未来全覆盖。',
 '- T2-033全部结算净额、已打款状态净额、打款时间非空净额分别为4,821,914.63、3,905,258.13、3,840,854.48；状态与时间1,796条不一致，不能猜测谁是唯一打款凭据。',
 '- T3-009/T3-012需明确全年12个月覆盖还是有事实月份/双方交集；订单绝对增长量与GMV同比增长率不可混用。',
 '',
 '未把所有可疑点判错：裸客户数遵守当前客户档案默认；裸退款总金额不擅自补成功状态；普通按月事实分布没有被强制补日历；本库PG16支持Gold配送时长里的numeric ROUND。多行Gold缺LIMIT作为跨题生成约定差异单独记录，没有把所有聚合题一概否决。',
 '',
 '逐题理由、契约出处、执行SQL、真实返回及建议均见 `basic_time-review.json`。原始独立证据见 `basic_time-query-results.json`；可复跑脚本为 `basic_time-checks.py`。所有合成反例仅存在于只读CTE，没有写入数据库。',
 '',
 '这些数字只证明参考语义/当前快照差异，不是模型准确率、供应商实测或人工审核结论。'
]
(ROOT/'basic_time-review.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps({'items':len(items),'counts':counts,'executedChecks':len(results),'missingLimitGoldCount':len(missing_limit)},ensure_ascii=False))
