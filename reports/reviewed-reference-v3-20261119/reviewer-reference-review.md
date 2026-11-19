# 独立复核：Olist V3 candidate（2026-11-19）

语义结论：PASS 82 / DEFECT 11 / AMBIGUOUS 7，合计100。100条Gold实际执行成功，共808行；执行成功不证明题意、事实全集或边界正确。providerCalls=0。

我以新的独立审查上下文进行复核，未参与候选题目/Gold编写或生产调参；未读历史模型输出、其他审计/lineage报告或历史调参结论。初次原文读取T1–T3时candidate notes随文件呈现，未用于正确性论证；此后只读取id/question/gold_sql。当前契约文件自身的历史说明不可当成独立证据。

审查范围包括全部100题、当前p21提示词、metrics/ambiguities/glossary、AmbiguityDetector、DatabaseSchemaReader及实际DDL/约束；没有按任何模型失分挑选题目。根agent告知规则8在本轮作最终澄清后，我重新读取当前文件并以下列SHA固定本次结论。

全部执行会话使用BEGIN READ ONLY及8秒statement_timeout，最终ROLLBACK；没有写数据库，没有调用供应商模型。S前缀为只读CTE/VALUES synthetic反例，不能称作真实样本错误。完整SQL、stdout/stderr见reviewer-evidence.sql/json；完整100题实际输出见reviewer-gold-dryrun.json。

## 必须先处理的语义问题

### T3-001 — AMBIGUOUS

每个月的订单数量是多少？按月份排序。

“每个月”未明确只出观测月还是连续日历；真实2016-11内部缺月被删。不能从没有记录推断已完整观测为0。

处理建议：明确“实际有订单月份”，或明确MIN/MAX连续月份且缺期NULL/已确认零事实计0，再据此生成日历。

证据：A03_month_coverage；来源：data/benchmarks/olist-v3-candidate/T3_time_window.yaml:1。

### T3-007 — AMBIGUOUS

每个月的运费总额是多少？

未明确月份覆盖全集；INNER明细使2018-10这个有订单但无运费事实的月也不展示，2016-11内部缺期亦无行。

处理建议：明确按订单覆盖月份还是运费事实观测月份；若按订单范围，先建月份日历，再LEFT金额，未知保留NULL。

证据：A03_month_coverage；来源：data/benchmarks/olist-v3-candidate/T3_time_window.yaml:27。

### T3-021 — AMBIGUOUS

每个月的退款单数量是多少？

每个月未指定观测月/日历月，真实2016-12内部缺期未展示。

处理建议：题干明确仅实际有退款申请月份，或明确完整月份及缺期NULL/零规则。

证据：A03_month_coverage；来源：data/benchmarks/olist-v3-candidate/T3_time_window.yaml:98。

### T3-023 — AMBIGUOUS

每个月新增多少张客服工单？

每个月未指定观测月/日历月，真实2016-12内部缺期未展示。

处理建议：题干明确仅实际有工单月份，或明确完整月份及缺期NULL/零规则。

证据：A03_month_coverage；来源：data/benchmarks/olist-v3-candidate/T3_time_window.yaml:108。

### T3-029 — AMBIGUOUS

每个月的佣金收入是多少？

每个月佣金未指定日历覆盖；真实2017-02内部缺期没有输出，不能视为已确认零佣金。

处理建议：明确仅有结算记录的账期月份，或补日历并将缺金额保留NULL。

证据：A03_month_coverage；来源：data/benchmarks/olist-v3-candidate/T3_time_window.yaml:138。

### T4-011 — DEFECT

每个订单状态的平均评价分是多少？

订单状态全集被INNER评价缩成有评价状态；零评价状态本应平均NULL却无行。当前样本每状态恰好都有评价不构成保障。

处理建议：从orders LEFT JOIN order_reviews后按order_status分组，AVG自然忽略未知分；不要WHERE过滤右表。

证据：A04_zero_review_states_and_member_levels, S09_T4-011_zero_review_status；来源：data/benchmarks/olist-v3-candidate/T4_multi_join.yaml:62。

### T4-015 — DEFECT

按订单关联客户档案所在州所属的大区，订单数量分别是多少？

客户州可NULL且没有到regions的FK，INNER地区维表删除未匹配订单，违背当前名称维表保留事实规则。

处理建议：沿订单customer_id找客户后LEFT JOIN regions，把未知映射订单保留NULL大区组；或题干明确只统计可映射大区。

证据：A01_schema_constraints, A11_region_and_seller_coverage, A12_real_schema_nullable, S11_T4-015_unmapped_region；来源：data/benchmarks/olist-v3-candidate/T4_multi_join.yaml:83。

### T4-017 — AMBIGUOUS

每个会员等级对应的订单数量是多少？

每个会员等级未明确是全定义等级、现有会员等级还是仅有订单等级。Gold仅最后一种，零订单等级不会返回0。

处理建议：明确“现有订单关联会员中实际出现的等级编号”；若要求全部等级，从member_levels/指定实体全集LEFT订单并COUNT订单PK。

证据：A04_zero_review_states_and_member_levels, S10_T4-017_zero_order_level；来源：data/benchmarks/olist-v3-candidate/T4_multi_join.yaml:100。

### T4-031 — DEFECT

每个州的卖家结算净额合计是多少？

settlements.seller_id没有FK，INNER sellers会删除未匹配州维度的结算金额，Gold把事实完整性寄托在当前样本。

处理建议：settlements LEFT JOIN sellers保留未知州组；若业务排除无卖家结算，必须在题干/契约写清。

证据：A01_schema_constraints, A05_unmatched_keys, S13_T4-031_unmatched_seller；来源：data/benchmarks/olist-v3-candidate/T4_multi_join.yaml:176。

### T5-001 — DEFECT

按订单关联客户档案所在州所属宏观区域，各会员等级（按等级名称汇总）的商品销售额分别是多少？按销售额从高到低排序。

显式要求等级名称汇总，members.level_id可NULL；INNER member_levels删掉这类会员商品金额，违反当前名称关联保留NULL名称事实规则；地区INNER同类风险。

处理建议：在会员事实范围内LEFT JOIN member_levels和regions，名称未匹配留NULL组；同名汇总继续按题干执行。

证据：A12_real_schema_nullable, A11_region_and_seller_coverage, S12_T5-001_unknown_level_name；来源：data/benchmarks/olist-v3-candidate/T5_multi_join_deep.yaml:1。

### T5-005 — AMBIGUOUS

按活动编号统计每个优惠券活动带来的有效订单数和商品销售额，按商品销售额降序取前10；没有有效用券订单的活动也保留。

“优惠券活动”可指campaign_type=COUPON，也可指所有活动通过券归因。真实四种类型各13个且全有券；Gold按全部52活动，Top10有7个非COUPON，两个解释实际不同。

处理建议：改为“所有活动通过优惠券带来的…”以保留Gold；或明确“campaign_type为COUPON”，并在活动全集过滤后仍保留零订单活动。

证据：A07_campaign_population, A10_campaign_top10_types；来源：data/benchmarks/olist-v3-candidate/T5_multi_join_deep.yaml:49。

### T5-009 — DEFECT

库存低于安全库存时，按仓库编号、仓库州代码及当前品牌编号统计仓库×商品库存行数，缺当前品牌归入NULL品牌组；有多个当前品牌的商品分别计入各品牌组。按行数降序取最多20组，并列时按这三个分组字段升序、NULL排最后。

inventory没有到warehouses的FK，INNER仓库维表会丢真实库存行；同时输出w.warehouse_id替代事实i.warehouse_id。

处理建议：用inventory i LEFT JOIN warehouses w；SELECT/GROUP BY i.warehouse_id,w.region_code,pbm.brand_id；未知仓库州留NULL。

证据：A01_schema_constraints, A05_unmatched_keys, S06_T5-009_orphan_warehouse；来源：data/benchmarks/olist-v3-candidate/T5_multi_join_deep.yaml:81。

### T5-014 — DEFECT

将每个会员最近一次日期的快照等级与当前等级比较，快照同日多条时取快照编号最大一条，日期未知不参与最新判断。按当前等级名称和快照等级名称组合汇总不一致会员数；任一等级未知或无快照不判为已确认不一致。

任一等级未知不判不一致，但snapshot.level_id没有FK；非NULL失配等级仍经<>被计数并显示NULL名称。

处理建议：取最新快照后，要求当前和快照等级各自能匹配member_levels主键，再比较ID；或明确未知仅指SQL NULL，但须在契约披露不存在等级ID的处理。

证据：A01_schema_constraints, A05_unmatched_keys, S07_T5-014_unknown_snapshot_level；来源：data/benchmarks/olist-v3-candidate/T5_multi_join_deep.yaml:134。

### T6-006 — DEFECT

已送达订单的准时率是多少？（实际送达日期不晚于预计送达日期）

注册指标规定零分母NULL，Gold直接除COUNT(*)；空已签收订单集报除零。

处理建议：分母改NULLIF(COUNT(*),0)，保留原已签收分母及预计日期未知处理。

证据：A02_nulls_and_denominators, S01_T6-006_empty_delivered；来源：data/benchmarks/olist-v3-candidate/T6_business_semantics.yaml:28。

### T6-008 — DEFECT

商品动销率是多少？（有销量的商品数 / 全部商品数）

独立全商品分母正确，但漏NULLIF；空products合法且会0/0报错。

处理建议：用NULLIF(a.n,0)，不得把未知动销率COALESCE为0。

证据：A02_nulls_and_denominators, S02_T6-008_empty_products；来源：data/benchmarks/olist-v3-candidate/T6_business_semantics.yaml:48。

### T6-011 — DEFECT

按订单关联客户档案所在州所属宏观区域，运费占含运费GMV的百分比是多少？仅统计有效订单。

Gold金额分母漏NULLIF，合法零价格零运费组报除零；地区INNER也可能删事实。

处理建议：分母NULLIF(SUM(price+freight_value),0)；LEFT地区映射保留未知大区，状态与百分数单位不变。

证据：A09_zero_gmv_region, S03_T6-011_zero_region_gmv, S11_T4-015_unmapped_region；来源：data/benchmarks/olist-v3-candidate/T6_business_semantics.yaml:58。

### T6-017 — DEFECT

推荐活动的转化率和人均奖励是多少？

转化率和人均奖励两个分母都漏NULLIF；空推荐或有推荐无转化均会报错。

处理建议：两个分母分别加NULLIF；未转化集合的平均成功奖励返回NULL，不能补0。

证据：A02_nulls_and_denominators, S04_T6-017_empty_referrals, S05_T6-017_no_conversion；来源：data/benchmarks/olist-v3-candidate/T6_business_semantics.yaml:101。

### T6-019 — DEFECT

各州的平均配送时长（下单到签收）是多少天？列出配送最慢的 10 个州。

下单时间允许NULL，某州所有时长不可计算时AVG=NULL；Gold仍可能把该州选进最慢Top10，违反未知最值候选规则。

处理建议：先按州聚合最终ROUND均值，外层WHERE avg_days IS NOT NULL，再按该均值和州代码稳定Top10；不把未知当0。

证据：A08_slowest_unknown_candidates, A12_real_schema_nullable, S08_T6-019_unknown_average_enters_top10；来源：data/benchmarks/olist-v3-candidate/T6_business_semantics.yaml:117。

## 不能混淆的边界

1. 当前真实样本的零分母、未映射大区、未匹配卖家、未知会员等级、孤儿库存均未触发上述大部分缺陷；它们是DDL允许且当前契约要求兜底的边界，不应被包装成当前100条执行失败。
2. 真实月缺口确实存在：orders 2016-11；refunds和support_tickets 2016-12；settlements 2017-02。源事件MIN/MAX只确定边界，不证明中间被完整观测。题干须明确只看观测月、连续缺期NULL，或确认零事实可补0。
3. 客户档案与自然人不是一个对象：实际2997个自然人有多档案，39个自然人跨州。关联事实属性沿customer_id；跨档案人级行为按customer_unique_id。547个订单有多评价，订单计数去重order_id，评价记录保留(review_id,order_id)。
4. 另有41条多行Gold缺LIMIT，字面违反当前规则3；这属于参考SQL源形式问题，不自动证明结果值错误。补限制必须不截断用户要求的全集。普通列表NULL排序也逐题单列lint，不与语义11项混计。
5. PASS只表示在明确题意/契约下未发现语义缺陷；不表示允许忽略source-form lint，也不是对未来数据全覆盖的形式化证明。

## 全部100题逐题结论

| ID | 结论 | 核查重点（完整七轴记录在JSON） |
|---|---|---|
| T1-001 | PASS | 订单记录；PK orders.order_id；COUNT(*)正确，不自行排除状态；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-003 | PASS | canceled状态订单；PK order_id；按物理枚举过滤；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-005 | PASS | price>1000的订单明细；完整PK(order_id,order_item_id)，不是订单数；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-007 | PASS | 卖家实体；PK seller_id；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-009 | PASS | SP州客户档案记录；PK customer_id；裸客户数量按当前customer_identity_v2数记录，未要求自然人；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-010 | PASS | 订单状态不同非NULL值数；COUNT(DISTINCT order_status)；该列DDL NOT NULL；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-012 | PASS | 价格最高的明细；完整PK(order_id,order_item_id)；无JOIN；直接使用源表记录；price为NOT NULL，因此不存在未知价格进入Top10；候选不足不补 |
| T1-014 | PASS | review_score=1的评价记录；完整PK(review_id,order_id)，不是不同订单；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-016 | PASS | 支付寻优；完整PK(order_id,payment_sequential)；无JOIN；直接使用源表记录；显式只考虑已知分期数；全部未知返回0行 |
| T1-018 | PASS | freight_value>50的明细；完整PK(order_id,order_item_id)；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-021 | PASS | RJ州卖家实体；PK seller_id；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-023 | PASS | product_category_name IS NULL的商品；PK product_id；明确NULL过滤覆盖默认分类政策；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-025 | PASS | status=2冻结会员实体；PK member_id且customer_unique_id有UNIQUE NOT NULL，一行一人；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-027 | PASS | 仓库实体；PK warehouse_id；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-029 | PASS | status=4已退款退款单；PK refund_id，不要求其明细存在；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-030 | PASS | reason_code=QUAL的退款单；PK refund_id，不擅自加已退款过滤；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-032 | PASS | discount_type=fixed的券实体；PK coupon_id，不是核销次数；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-034 | PASS | 工单分类实体；PK category_id，不是工单记录或分类名称去重；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-036 | PASS | movement_type=2的库存变动记录；PK movement_id，不是quantity合计；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T1-038 | PASS | macro_region=Sudeste的地区字典州记录；PK region_code，一州一行；无JOIN；直接使用源表记录；COUNT空集为0；其他字段NULL不被额外排除 |
| T2-002 | PASS | 所有明细上的运费金额事实；完整PK(order_id,order_item_id)；无JOIN；直接使用源表记录；freight_value为NUMERIC(12,2) NOT NULL；空集SUM为NULL |
| T2-004 | PASS | 全部订单各自明细条数，然后订单等权平均；orders LEFT JOIN order_items为1:N；每订单先聚合，COUNT右侧NOT NULL PK分量；无明细订单cnt=0；完全无订单时AVG=NULL |
| T2-006 | PASS | 支付记录按payment_type分布；完整PK(order_id,payment_sequential)；无JOIN；直接使用源表记录；NULL payment_type作为分组保留；空表无分组行 |
| T2-008 | PASS | 客户档案记录按州计数；PK customer_id；非自然人去重；无JOIN；直接使用源表记录；州NULL作为候选组保留，身份裁决NULLS LAST |
| T2-011 | PASS | 按seller_id汇总order_items.price商品金额；明细PK完整；无JOIN；直接使用源表记录；price NOT NULL；每个出现卖家至少一条明细，故SUM已知 |
| T2-013 | PASS | 订单记录中delivered和canceled两个子集；无JOIN；两个FILTER各自计数；空orders仍一行(0,0)，某一组为空亦保留0 |
| T2-015 | PASS | 现有members实际出现level_id分组；不是全部等级字典；无JOIN；按members.level_id直接分组；AVG忽略NULL points；全未知均值NULL；NULL level_id组保留 |
| T2-017 | PASS | regions州记录按macro_region分组；PK region_code；无JOIN；直接使用源表记录；NULL大区作为组；空表无组 |
| T2-019 | PASS | 活动实体自身预算；PK campaign_id；预算不是子事实可重复加总值；无JOIN；直接使用源表记录；NULL budget保留，普通列表排序默认NULLS LAST的字面要求Gold未显写，实际budget无NULL |
| T2-020 | PASS | 优惠券实体按discount_type分组；PK coupon_id；无JOIN；直接使用源表记录；NULL discount_type组保留；空表无组 |
| T2-022 | PASS | 退款单refund_amount金额；PK refund_id；无JOIN；直接使用源表记录；SUM忽略NULL金额；空/全未知为NULL；不私加已退款状态 |
| T2-024 | PASS | 库存表仓库×商品记录；完整PK(warehouse_id,product_id)；无JOIN；只按inventory实际仓库编号分组；题干明确有库存记录；不要求零库存记录仓库补行 |
| T2-026 | PASS | 库存quantity合计；完整PK(warehouse_id,product_id)；无JOIN；在inventory实际仓库组内求和；NULL quantity不当0；全未知为NULL；题干限定已有库存记录 |
| T2-028 | PASS | 发货单记录按carrier分组；PK shipment_id；无JOIN；直接使用源表记录；NULL carrier组保留；未额外加发货状态过滤 |
| T2-031 | PASS | 工单记录按priority分组；PK ticket_id；无JOIN；直接使用源表记录；NULL priority组保留；不扩展未出现的枚举组 |
| T2-033 | PASS | 所有settlements.net_amount金额；PK settlement_id；无JOIN；直接使用源表记录；不按paid_at/status过滤；SUM空/全NULL为NULL |
| T2-035 | PASS | 搜索日志记录按keyword分组；PK log_id；无JOIN；直接使用源表记录；NULL keyword组保留，身份裁决时NULLS LAST |
| T2-037 | PASS | 按事实关联客户档案州统计订单；PK order_id；orders→customers为N:1，实际FK且目标PK唯一；州NULL组不会主动被排除；COUNT仍为订单记录数 |
| T2-039 | PASS | 评价记录好评>=4比例；完整PK(review_id,order_id)；无JOIN；每条评价等权；NULL分数不确认好评但仍在分母；空集NULLIF返回NULL |
| T2-040 | PASS | 支付金额payment_value按payment_type求和；完整支付PK；无JOIN；直接使用源表记录；NULL金额不当0；NULL支付方式保留组；全未知金额NULL |
| T3-001 | AMBIGUOUS | “每个月”未明确只出观测月还是连续日历；真实2016-11内部缺月被删。不能从没有记录推断已完整观测为0。 |
| T3-003 | PASS | orders记录按含年份季度计数；PK order_id；无JOIN；直接使用源表记录；无额外状态/非空过滤；未来完全无事实季度覆盖未明 |
| T3-005 | PASS | 按订单下单自然周汇总后取订单最多周；无JOIN；直接使用源表记录；当前下单时间非NULL；未知时间组未来应明确是否属于可确认周 |
| T3-007 | AMBIGUOUS | 未明确月份覆盖全集；INNER明细使2018-10这个有订单但无运费事实的月也不展示，2016-11内部缺期亦无行。 |
| T3-009 | PASS | 2018年实际有订单月份的订单记录；无JOIN；直接使用源表记录；无事实月份依显式范围不出行；非空时间由窗口自然限制 |
| T3-010 | PASS | orders按月计数；一行一月后LAG；monthly/calendar/filled为月键唯一的1:1左连接；题干明确无订单月计0、首期变化NULL；空源calendar无行 |
| T3-012 | PASS | 2018与2017同月均有订单的月份之计数差；先按(year,month)聚合唯一，再同月配对1:1；题干显式仅两边都有数据；无基期月份不展示 |
| T3-014 | PASS | 90自然日窗口明细price销售额，按周；orders→order_items为1:N；订单全表MAX单行anchor交叉连接；MAX未知时窗口无匹配；有订单无明细的周没有金额数据，不制造0 |
| T3-016 | PASS | 按DOW计订单最多星期；PK order_id；无JOIN；直接使用源表记录；当前下单时间已知；没有额外状态过滤 |
| T3-018 | PASS | 订单从下单到签收时长，按事实客户州分组；orders→customers FK N:1，不放大订单；无签收不参与均值；下单未知时AVG忽略该时长；当前所有州有可计时事实 |
| T3-021 | AMBIGUOUS | 每个月未指定观测月/日历月，真实2016-12内部缺期未展示。 |
| T3-023 | AMBIGUOUS | 每个月未指定观测月/日历月，真实2016-12内部缺期未展示。 |
| T3-025 | PASS | 2018上半年订单按所关联客户档案州计数；orders→customers FK N:1，COUNT(*)不重复；州NULL组可保留；无订单的州是否补0不是题干显式要求 |
| T3-027 | PASS | members注册记录按register_date月；PK member_id且人身份唯一；无JOIN；直接使用源表记录；WHERE register_date IS NOT NULL意在只展示可归月注册；当前无NULL；不构造未知月份 |
| T3-029 | AMBIGUOUS | 每个月佣金未指定日历覆盖；真实2017-02内部缺期没有输出，不能视为已确认零佣金。 |
| T3-030 | PASS | 有效orders记录季度计数；PK order_id；无JOIN；直接使用源表记录；明确有效订单排除canceled/unavailable；未顺带过滤送达时间 |
| T4-002 | PASS | 订单明细price按订单关联客户州求和；明细→orders PK→customers PK为N:1:1；不因自然人多档案改路；customers州NULL作为组保留；price非NULL |
| T4-004 | PASS | 明细price按英文类目名称显式合并；明细→products PK，翻译表原类目PK唯一LEFT JOIN，不放大金额；原始类目已知但缺翻译保留NULL英文名称组 |
| T4-006 | PASS | 明细price按该事实seller_id关联卖家州汇总；order_items.seller_id有FK，sellers PK N:1不放大；seller_state NULL组保留；金额非NULL |
| T4-008 | PASS | 没有任何user_tag_map关联的members实体；PK member_id；LEFT反连接；有任何匹配右键则排除，不会把一人多标签计多次；空标签表时全部会员被计；孤儿tag_map不凭空生成会员 |
| T4-011 | DEFECT | 订单状态全集被INNER评价缩成有评价状态；零评价状态本应平均NULL却无行。当前样本每状态恰好都有评价不构成保障。 |
| T4-013 | PASS | 至少一条1/2分评价的不同orders，按事实客户州计数；order_reviews→orders→customers N:1:1；COUNT(DISTINCT order_id)抵消同订单多评价；低分NULL不满足；客户州NULL可保留；不加无关有效状态过滤 |
| T4-015 | DEFECT | 客户州可NULL且没有到regions的FK，INNER地区维表删除未匹配订单，违背当前名称维表保留事实规则。 |
| T4-017 | AMBIGUOUS | 每个会员等级未明确是全定义等级、现有会员等级还是仅有订单等级。Gold仅最后一种，零订单等级不会返回0。 |
| T4-019 | PASS | 各user_tags实体覆盖真实members；PK tag_id、member_id；tags LEFT map LEFT members，COUNT DISTINCT member_id，不把孤儿map算会员；明确零会员标签保留，COUNT(NULL)=0；不要求有订单 |
| T4-020 | PASS | 有HIGH_VALUE标签的会员所下不同orders；map→tags、members→customers自然人扩展→orders；自然人扩展是需要跨客户档案找订单；只有能确认目标标签会员的订单进入范围；空集0 |
| T4-022 | PASS | coupon_usages.discount_amount核销事实金额；PK usage_id；无JOIN；直接使用源表记录；无JOIN不放大，SUM忽略未知，空/全未知为NULL；未提有效订单不添加过滤 |
| T4-024 | PASS | shipments发货单数，按显式仓库名称合并；warehouses FULL JOIN shipments，warehouse_id目标PK唯一；COUNT shipment_id而非COUNT(*)；零发货COUNT=0；未分配发货归NULL名称组；FULL保护两侧 |
| T4-026 | PASS | shipments运输记录，按carrier平均发货至送达；无需orders或tracking；单表一行shipment_id；缺任一时间不计均值；NULL carrier组不额外过滤 |
| T4-028 | PASS | 按工单分类编号平均实际处理时长；PK ticket_id；categories FULL JOIN tickets N:1；COALESCE分类编号保留两侧；无工单/无可计时分类平均NULL；NULL分类与未匹配也保留 |
| T4-031 | DEFECT | settlements.seller_id没有FK，INNER sellers会删除未匹配州维度的结算金额，Gold把事实完整性寄托在当前样本。 |
| T4-033 | PASS | refunds.refund_amount按reason_code汇总；PK refund_id；无JOIN；直接使用源表记录；NULL原因组保留；NULL金额不计SUM；未擅加状态筛选 |
| T4-035 | PASS | 自然人customer_unique_id的客单价；非customer_id档案；先order_items按order_id聚合一行，再orders LEFT金额；orders→customers有FK；题干明确金额全未知客户不展示；其余SUM忽略缺明细但订单数保留；自然人DDL NOT NULL |
| T4-037 | PASS | 商品销量为order_items明细行数；PK(product_id)展示图片属性；items→products有FK N:1；按product_id和照片数分组，不按图片数误合商品；照片数NULL不删商品；只统计有销量商品符合Top销量候选 |
| T4-039 | PASS | 有点击标记搜索记录/全部搜索记录；PK log_id；无JOIN；clicked_product_id非NULL表示点击事实，不要求维表匹配；空表NULLIF返回NULL；clicked_product_id NULL留分母 |
| T4-040 | PASS | 从未在inventory出现的products实体；PK product_id；LEFT inventory反连接，库存可一商品多仓，不会对未匹配多计；空inventory时全部商品计入；quantity=0仍是进过库存表，不错误排除 |
| T4-042 | PASS | 至少有一次new_price>old_price历史的不同product_id；单表筛历史记录后COUNT DISTINCT product_id，非history_id记录数；NULL新/旧价不能确认上涨；无满足事实计0 |
| T4-044 | PASS | 大区客户档案记录数与大区内自然人订单均值，两个身份分别处理；customers LEFT regions PK LEFT orders；COUNT DISTINCT customer_id抵消一档多订单；每订单自身客户属性决定归属；零订单人仍分母；未映射大区保留NULL；分母0用NULLIF；NULL自然人受DDL排除 |
| T5-001 | DEFECT | 显式要求等级名称汇总，members.level_id可NULL；INNER member_levels删掉这类会员商品金额，违反当前名称关联保留NULL名称事实规则；地区INNER同类风险。 |
| T5-003 | PASS | refund_items.refund_value事实，英文类目名称显式汇总；退款PK→明细1:N；明细以(order_id,order_item_id)完整键找商品；产品→翻译LEFT唯一；原类目NULL依维度政策不分类；缺翻译保留NULL英文组；SUM空未知不当0 |
| T5-005 | AMBIGUOUS | “优惠券活动”可指campaign_type=COUPON，也可指所有活动通过券归因。真实四种类型各13个且全有券；Gold按全部52活动，Top10有7个非COUPON，两个解释实际不同。 |
| T5-007 | PASS | 有商品点击的search_logs记录按(keyword,英文类目名)分组；logs→products PK N:1，翻译按原类目PK LEFT，不乘行；不额外关联会员等级；原始类目NULL明确不分类；缺翻译保留NULL英文名组 |
| T5-009 | DEFECT | inventory没有到warehouses的FK，INNER仓库维表会丢真实库存行；同时输出w.warehouse_id替代事实i.warehouse_id。 |
| T5-010 | PASS | 结算明细commission_amount金额及不同英文类目数；完整PK(settlement_id,order_id,order_item_id)；detail→settlements PK；LEFT完整order_item键→products→translation PK，保留未知分类的佣金；缺订单明细/商品/翻译仍保留佣金；未知类目不伪装成一个已知类目 |
| T5-012 | PASS | 标签实体覆盖成员数与成员订单明细price均值；tags LEFT map LEFT members LEFT自然人对应客户 LEFT orders LEFT items；完整member_id去重；零会员标签也保留0,NULL；有无订单不影响会员数，AVG无价格=NULL |
| T5-014 | DEFECT | 任一等级未知不判不一致，但snapshot.level_id没有FK；非NULL失配等级仍经<>被计数并显示NULL名称。 |
| T5-016 | PASS | 退款单数与退款明细金额，按英文类目和退款原因汇总；refund→details1:N，完整(order_id,order_item_id)关联，不放大单项金额；退款数DISTINCT refund_id；默认原类目NULL不分类；缺翻译NULL英文名组保留；不删未知原因 |
| T5-018 | PASS | 默认地址州×该customer_id档案登记州组合的不同订单；addresses→customers→orders，以customer_id而非自然人反向取全部地址；COUNT DISTINCT订单消除同州多默认地址；is_default NULL不确认默认；地址州/登记州NULL组仍可保留；无默认地址订单不在指定范围 |
| T6-002 | PASS | 有效订单数、下单自然人数、客单价三个不同度量；items先order_id聚合，orders LEFT金额；客户PK FK，不放大订单；空有效订单返回(0,0,NULL)，零金额分母NULLIF；自然人身份DDL非NULL |
| T6-004 | PASS | 自然人首个有效订单形成同期群；每人一行；首次订单按自然人聚合；回访先DISTINCT再LEFT cohort，避免多次回访加权；未回访留分母；COUNT(*)每输出cohort组必大于0，虽未NULLIF但无0分母风险；无cohort无行 |
| T6-006 | DEFECT | 注册指标规定零分母NULL，Gold直接除COUNT(*)；空已签收订单集报除零。 |
| T6-008 | DEFECT | 独立全商品分母正确，但漏NULLIF；空products合法且会0/0报错。 |
| T6-011 | DEFECT | Gold金额分母漏NULLIF，合法零价格零运费组报除零；地区INNER也可能删事实。 |
| T6-013 | PASS | 用券且有商品明细的有效订单GMV、优惠金额、折扣率；券按订单汇总、商品按订单汇总；再1:1订单关联，避免多券×明细乘法；空集合金额SUM=NULL、比例NULLIF；不能用SUM DISTINCT等额金额抵消放大 |
| T6-015 | PASS | 全部support_tickets SLA达成，按事实category_id；tickets LEFT categories PK，保留未知分类工单进对应分母；未知SLA/起点/终点无法确认达成但仍分母；NULLIF防0 |
| T6-017 | DEFECT | 转化率和人均奖励两个分母都漏NULLIF；空推荐或有推荐无转化均会报错。 |
| T6-019 | DEFECT | 下单时间允许NULL，某州所有时长不可计算时AVG=NULL；Gold仍可能把该州选进最慢Top10，违反未知最值候选规则。 |
| T6-020 | PASS | 平台加权佣金率和每seller_id佣金率分布；detail→settlements PK N:1，先每卖家聚合gross/commission；无跨卖家名称合并；未知卖家率不补0；MIN/AVG/MAX忽略NULL；空集平台NULL |
| T6-022 | PASS | 2017/2018有效订单含运费GMV各月及同比；每(year,month)先聚合，月份1..12日历LEFT两年1:1；缺金额不填0；无同期/同期0增长率NULL；完整12月输出 |
| T6-024 | PASS | 退款原因-订单先去重，再保留订单所有评价记录；评价完整PK(review_id,order_id)；reason_orders DISTINCT(reason_code,order_id)防一因多退款重复；JOINreviews保留每条评价，不按review_id单分量去重；未知评价分不入低分分子但仍分母；NULL原因组保留；NULLIF零分母 |

## 最终读取文件与SHA-256

| 文件 | SHA-256 |
|---|---|
| data/benchmarks/olist-v3-candidate/T1_single_table_basic.yaml | `33b76dc96570c1feb33efdd77745dbc791bedc5e2641900fa479a7e59488fc7e` |
| data/benchmarks/olist-v3-candidate/T2_single_table_agg.yaml | `06cfb00244a0b8e0c07fc0f0226f01d26b21a20c8d9cb2f826ebca4a0e590ae2` |
| data/benchmarks/olist-v3-candidate/T3_time_window.yaml | `7b29da23534e128e29305ac39656631323ff86a54531c083c04ab4bb3a19d0e8` |
| data/benchmarks/olist-v3-candidate/T4_multi_join.yaml | `0cd391b5ed90539bb91e92366204b6d74a598c2130245f4f505dcd5b06b22a12` |
| data/benchmarks/olist-v3-candidate/T5_multi_join_deep.yaml | `9fa729235268a5d66ec4bed22014ef0377093642998cb24d0f1f1a0790bf5667` |
| data/benchmarks/olist-v3-candidate/T6_business_semantics.yaml | `072ff9e7533514889792ca62374ba7b8f02f9330c742f4538eed58b674e78a51` |
| data/schema/01_olist_schema.sql | `3c7a256d13378e81b8c3c001e1ced117f2a00ec758634d5a2c103a3e82000105` |
| data/schema/03_extend_schema.sql | `ca9910e2a3a2996350e3dd9c8305e416d0e2efbe8edac4804b9216269f9652f0` |
| src/main/java/com/text2sql/agent/clarification/AmbiguityDetector.java | `155f6f879d696083955e4ddf231c52e713695c6361a37b1224ac9e12fcbc8f18` |
| src/main/java/com/text2sql/agent/generation/PromptTemplate.java | `db75a631ddcd9084fe4cf3d4087ff35be11f8b89865a5ec31865b6b8999b6714` |
| src/main/java/com/text2sql/agent/retrieval/DatabaseSchemaReader.java | `c264eb79e4c73fa1d8302f355195a02958c9d2e3e15dc09c082506e2da917f6d` |
| src/main/resources/schema/ambiguities.yml | `493dccf3090c7f7ea544162a351e8c8d1e7ab085886ec16aa12797ae89ce3e3a` |
| src/main/resources/schema/glossary.yml | `b4bc655947ed1f4eb2e243bbdaf3800bdd7919ee4121fc9a7276b647947b99ac` |
| src/main/resources/schema/metrics.yml | `78dfc23f9075536aa7568f94c87c41d238cac0c66c553449c9fd7c67c9035192` |

未修改候选题目、Gold或生产契约。未Git提交；本任务只交付审查报告。
