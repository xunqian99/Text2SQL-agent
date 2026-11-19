# 独立契约复核：原正式T5/T6题

复核人：`/root/review_deep_business`。新上下文、未参与题目/Gold编写或Prompt/规则调参、未读既有审计与其他审阅者结论，外部模型调用 **0次**。只读产品六个指定源文件及实际schema；只读SQL全部使用`text2sql_ro`、`BEGIN READ ONLY`、8秒statement timeout。未改生产代码、原始评测/holdout、Gold、评分器或门槛，未提交Git。

完整逐题理由、契约来源、已执行SQL与实际结果在`deep_business-review.json`；全部22题均有独立数据库核查。当前统计：{"CONTRACT_CONFLICT": 6, "COINCIDENTAL_EQUIVALENCE": 5, "AMBIGUOUS": 1, "REFERENCE_DEFECT": 4, "KEEP": 6}。分类以主问题为准，其他缺陷列为secondaryIssues，不能把分类数直接当模型错误数。

## 已确定的参考答案缺陷（4题）

- **T5-010**：分类查找删佣金事实。总佣金535788.39被算成527124.40；缺8663.99，其中原始类目NULL 8092.23、非NULL缺英文翻译571.76。前20中一个佣金值少261.92，纠正后有一个新卖家进入前20。类目数与佣金的事实全集必须分开。
- **T6-002**：商品明细内连接删8个有效订单、7名买家；98207/94990被算成98199/94983，AOV应160.23而Gold160.24。
- **T6-013**：1175个多商品用券订单放大优惠。417833.13被算成473711.60，折扣率21.64%被算成24.54%；当前无一单多券，GMV1930583.35才暂未重复。
- **T6-022**：Gold只有1至9月。2017年10—12月764756.03、1172191.68、861526.77被删除，共2798474.48。完整月份候选输出12行，缺2018金额及同比保持NULL，不填0造-100%。具体逐月对照见JSON的T6-022 checks与candidateResult。

## 当前契约冲突与待定业务

T5-001、T6-011未明确宏观地域主体，当前规则要求澄清，Gold固定客户州。客户大区和卖家大区实际会改变42190条明细、6842445.94 GMV的归属；两题只给待确认候选题干，未强定地域答案。T5-005活动、T5-012标签、T6-015工单分类的名称输出与当前“未明说名称则输出标识”默认不同；名称均没有唯一约束。T5-007多加会员连接并删5条非NULL类目缺译点击，虽缺译组低于Top20阈值，仍与保留事实的当前契约冲突。

**T5-009不能冻结为唯一Top20**：19组高于5，另19组同为5，Gold只任取并列中的1组。另359个缺当前品牌库存行被删，保留未知品牌会有5组进入Top20。商品行数/库存件数、当前/历史品牌、未知品牌及并列裁决均需明确后再定SQL。没有凭当前数据无多当前品牌就宣称其受唯一约束。

## 现库巧合与边界

T5-014全部38471条快照恰好每会员1条，Gold未筛最近却与latest相同；只读合成反例已证明旧快照可造成误报。T5-003/016本快照非NULL退款类目均有翻译，不保证以后仍完整；84条原始类目NULL按当前默认排除，不能与缺翻译混为一谈。T6-024目前一订单一退款，直接关联评价才未重复加权，合成反例展示均分2.33/低分66.67%对正确3.00/50.00%。T6-020当前2176卖家毛额均正，零分母补0的错误尚未触发；合成例的平均率0.0500对0.1000。合成证据均明确标记，**不是生产实测记录**。

SLA另有**2556条负处理时长**，当前产品公式和Gold都会把它们计为成功。这是共用定义与数据质量的待决事项，没有偷加非负过滤。T6-017当前奖励约定会将未转化记录奖励21582.52也计入总奖励，得到20.01；若只算转化奖励则6.72。Gold符合现注册口径，业务名称是否应叫“人均”需要另审，不当作模型算错。

KEEP的6题为T5-018、T6-004/006/008/017/019；它们也逐项记录身份、独立分母、NULL、时间与TopK边界。几个Gold省略NULLIF，当前快照分母正不报错；这项结论不等于它们已满足任意空库的产品安全策略。

## 证据文件

- `deep_business-schema.sql` / `deep_business-schema-results.jsonl`：实际列、可空性、PK/FK/UNIQUE/索引。
- `deep_business-checks.sql` / `deep_business-checks-results.jsonl`：22题逐题生产快照核查。
- `deep_business-topk.sql` / `deep_business-topk-results.jsonl`：8道有LIMIT题的截断边界。
- `deep_business-followup.sql` / `deep_business-followup-results.jsonl`：实际影响及明确标记的只读合成反例。
- `deep_business-integrity.sql` / `deep_business-integrity-results.jsonl`：未声明外键的8类关联完整性，当前孤儿均0，仅代表当前快照。
- `deep_business-candidate-results.sql` / `deep_business-candidate-results.jsonl`：8道已冻结诊断候选SQL及实际执行结果，均未写回基准。
- `deep_business-build-report.py`：报告组装与候选只读查询脚本。只读取本审阅者证据及指定输入/源文件。

全部候选只是可复核材料。需要业务确认的地方保留问题；没有人工审核签字、没有模型成绩、没有付费评测，不能据此宣称新准确率。

| ID | 主判断 | 首要理由 |
|---|---|---|
| T5-001 | CONTRACT_CONFLICT | 商品销售额已有明确不含运费提示，Gold按商品明细price汇总且会员通过customer_unique_id关联正确；会员customer_unique_id有UNIQUE，未发生明细放大。 |
| T5-003 | COINCIDENTAL_EQUIVALENCE | 退款金额使用refund_items.refund_value，且订单明细按(order_id,order_item_id)完整键关联，粒度正确，不存在退款头金额重复累加。 |
| T5-005 | CONTRACT_CONFLICT | 当前产品默认每个活动以campaign_id区分并输出标识，原题没有要求活动名称，而Gold仅按campaign_name分组和输出。 |
| T5-007 | CONTRACT_CONFLICT | 统计对象是有点击的搜索记录，会员及会员等级不是本题指标或维度，Gold多加两个INNER JOIN会额外要求点击者是可匹配等级的会员。 |
| T5-009 | AMBIGUOUS | 题干没有规定Top20遇到并列如何处理。 |
| T5-010 | REFERENCE_DEFECT | 佣金合计的事实全集是卖家结算明细，不应被英文类目展示的可用性缩小。 |
| T5-012 | CONTRACT_CONFLICT | Gold的会员分母与价格均值链路基本正确：从标签会员出发LEFT JOIN订单/商品，保留没有商品价格的会员，AVG只统计存在的商品明细且不把缺失补0。 |
| T5-014 | COINCIDENTAL_EQUIVALENCE | 题干明确最近一次会员快照，Gold却读取所有member_snapshot，未取MAX(snapshot_date)或按会员排序取最新。 |
| T5-016 | COINCIDENTAL_EQUIVALENCE | 按类目与原因统计时，COUNT DISTINCT refund_id正确防止一张退款单同类多明细重复计笔数，SUM refund_value仍逐退款明细累加；跨类目的一张退款可进入多个组，不能再把各类目退款数相加当平台去重退款数。 |
| T5-018 | KEEP | Gold沿shipping_addresses.customer_id直接关联该客户档案及订单，符合客户属性必须沿事实关联键读取的当前身份政策，没有绕customer_unique_id去匹配另一州的档案。 |
| T6-002 | REFERENCE_DEFECT | Gold把有效订单数、下单人数与客单价统一绑在INNER JOIN order_items后的集合，悄悄排除了无商品明细的有效订单。 |
| T6-004 | KEEP | Gold先在完整有效订单历史按customer_unique_id取首单，再选2017上半年cohort，没有先截报告期而误认新客；下半年回访先DISTINCT自然人，使多笔回访不放大分子。 |
| T6-006 | KEEP | Gold用实际签收时间非NULL作为已送达全集，再以实际不晚于预计计准时，与注册on_time_rate一致。 |
| T6-008 | KEEP | Gold将有效订单中出现的不同product_id与独立全量products分母分别聚合，正确保留未动销商品作为分母，没有用库存或销售连接后的商品数。 |
| T6-011 | CONTRACT_CONFLICT | 含运费GMV、有效订单、运费分子和百分比两位均与freight_ratio一致，但原题宏观区域未指定地域主体。 |
| T6-013 | REFERENCE_DEFECT | Gold直接连接用券记录与商品明细，把每笔订单的discount_amount重复到每个商品上。 |
| T6-015 | CONTRACT_CONFLICT | Gold全工单分母、未解决不计分子、小时EPOCH换算和百分比两位符合sla_rate；但原题没要求类目名称，当前维度默认应使用support_tickets.category_id，Gold输出category_name并仅按名称分组。 |
| T6-017 | KEEP | Gold按推荐记录计算已转化/全部推荐，奖励总额除以已转化推荐数，符合当前conversion_rate与reward_per_converted，而不是自然人去重或每全部推荐均摊。 |
| T6-019 | KEEP | Gold严格使用下单到客户签收的时间差，EPOCH/86400.0保留小数天，AVG后两位，符合delivery_days而非承运商发货到送达口径。 |
| T6-020 | COINCIDENTAL_EQUIVALENCE | 平台总佣金/总毛额与卖家分别聚合后MIN/AVG/MAX的结构正确，平均卖家率是各卖家等权、整体率是按毛额加权，DECIMAL_RATIO四位也符合注册单位。 |
| T6-022 | REFERENCE_DEFECT | 问题同时要求2017与2018各月GMV，再算2018同月同比；Gold却以2018为驱动INNER JOIN2017，只输出两年交集1至9月，删除2017年10/11/12月764756.03、1172191.68、861526.77，合计2798474.48。 |
| T6-024 | COINCIDENTAL_EQUIVALENCE | 当前低分率度量以评价记录为单位，原Gold将退款与评价直接按order_id连接；order_reviews完整身份是(review_id,order_id)，同订单多评价本应各保留。 |
