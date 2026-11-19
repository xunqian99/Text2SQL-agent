# 审查反馈后的差异复核（revision 1）

**99 PASS / 0 DEFECT / 1 AMBIGUOUS（T5-016待落实作者选择）**。这是一轮审查反馈后修订复核，不是未见盲测或独立泛化评测。providerCalls=0。

100题ID完整且唯一；21题有修改，其中13题改Gold、8题只改问题；79题未改且原PASS结论保持。原主体报告、addendum和最终初审JSON均未覆盖。生产契约及指定源码/DDL的SHA与首轮一致，旧候选6文件在reviewed-draft2中均与初审SHA一致。

## 唯一待收口项：T5-016

题干现已指明按退款金额降序最多20，但Gold仍缺HAVING SUM(ri.refund_value) IS NOT NULL。RS17只读构造一组退款金额全NULL事实，实际仍返回(category,QUAL,1,NULL)。

不能把这个返回直接说成字面违反p21：若它只是普通降序最多20组的列表，保留NULL末尾可成立。未落实的是作者已选择的“已知金额最高”的修订意图。作者需将题面明确为已知退款金额最高，并加上述HAVING；不能只改SQL暗中缩小事实范围。本轮将其记为AMBIGUOUS / selected-intent pending，而非宣称当前真实快照已错。

## 已验证的关键修复

- T5-009：SELECT、GROUP、ORDER一致使用i.warehouse_id；孤儿仓库库存保留编号999，多当前品牌分别进组，缺品牌留NULL，自动字符串修订未破坏关联键。
- T5-001：两个不同等级ID同名按显式名称汇总为300；未知等级/地域金额25留NULL组，没有关联放大或删除会员事实。
- T6-008：零商品分母为NULL；有效商品重复明细+取消订单构造下，分子1，独立全商品分母3，输出33.33%。
- T6-019：先ROUND到两位，再过滤未知均值并按别名排名。第10名10.003与10.004均显示10.00，由州AA优先于ZZ，未按原始精度偷换裁决。
- T5-014：先取最新再验证等级，不回退旧有效快照；同日最大ID和未知日期规则均符合。
- 5道月份题：题面明确仅实际事实月后，1月/3月构造均不补2月；T3-007有订单但无明细的2月按新范围不输出。
- T5-005：所有类型活动均在全集内，重复用券不重复订单/金额；零用券活动保留(0,NULL)，符合题面覆盖及普通降序列表。

## 验证数字与边界

13条修改Gold实际执行且与旧结果双向EXCEPT ALL差异均0。T5-001一次比较因Docker共享内存不足失败，关闭该会话并行重试成功，完整错误留存。24个synthetic案例23个符合所选预期，RS17揭示尚未落实的作者意图。全部数据库执行只读、8秒超时并ROLLBACK。

结果规模：未改查询复用首轮实际输出，13条改Gold使用本轮实际输出，合计808行；单题最大52行，为T2-019。这里没有声称本轮重跑全部100条。41条无LIMIT参考SQL按作者裁决定义完整预期集合；该裁决不豁免生产规则、不改变执行器上限。普通NULL排序无LIMIT且不改变集合时仅作展示lint。

## 逐题差异结果

| ID | 前次 | 本轮 | 改动 | 本轮依据 |
|---|---|---|---|---|
| T1-001 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-003 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-005 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-007 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-009 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-010 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-012 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-014 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-016 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-018 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-021 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-023 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-025 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-027 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-029 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-030 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-032 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-034 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-036 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T1-038 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-002 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-004 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-006 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-008 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-011 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-013 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-015 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-017 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-019 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-020 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-022 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-024 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-026 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-028 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-031 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-033 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-035 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-037 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-039 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T2-040 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-001 | AMBIGUOUS | PASS | question | 题干已限定实际有订单月，Gold只返回观测月份；不要求补2016-11。RS18确认内部缺月不补行，月升序保持。 |
| T3-003 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-005 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-007 | AMBIGUOUS | PASS | question | 题干已限定实际有关联订单明细的下单月，因此有订单无明细月份不展示符合范围。RS19确认只出有明细月份，不把缺金额伪造0。 |
| T3-009 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-010 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-012 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-014 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-016 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-018 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-021 | AMBIGUOUS | PASS | question | 题干已限定实际有退款申请月份，与requested_at分组一致。RS20确认内部无事实月不补行。 |
| T3-023 | AMBIGUOUS | PASS | question | 题干已限定实际新建工单月份，与created_at分组一致。RS21确认内部无事实月不补行。 |
| T3-025 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-027 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T3-029 | AMBIGUOUS | PASS | question | 题干已限定实际有结算单的账期起始月份，与period_start分组一致。RS22确认缺期不补0金额。 |
| T3-030 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-002 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-004 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-006 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-008 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-011 | DEFECT | PASS | question,gold_sql | 从实际orders状态全集LEFT评价；无评价状态保留NULL均分。RS01通过；主指标NULLS LAST不再先排未知。评价AVG仍按完整记录等权。 |
| T4-013 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-015 | DEFECT | PASS | gold_sql | LEFT regions保留未映射大区订单；订单仍沿customer_id唯一客户档案取州，不改走自然人其他档案。RS02保留NULL大区计数。 |
| T4-017 | AMBIGUOUS | PASS | question | 题干明确只取现有订单关联会员中出现的等级编号。RS23中会员存在但无订单时0行符合范围，不再要求补0等级。 |
| T4-019 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-020 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-022 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-024 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-026 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-028 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-031 | DEFECT | PASS | gold_sql | settlements LEFT sellers保留无匹配卖家的结算金额；RS03返回NULL州100金额，无事实损失。 |
| T4-033 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-035 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-037 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-039 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-040 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-042 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T4-044 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T5-001 | DEFECT | PASS | gold_sql | LEFT等级/地区保留会员商品金额；members自然人UNIQUE及商品订单外键使关联不放大。RS04中不同level_id同名按题干明确合并300，未知等级和地域金额25留NULL组。 |
| T5-003 | DEFECT | PASS | question,gold_sql | 题干明确已知金额最高最多15组；HAVING SUM(refund_value) IS NOT NULL排除全未知金额候选，仍保留缺翻译NULL名称。RS05通过。 |
| T5-005 | AMBIGUOUS | PASS | question | 题干明确所有活动通过券归因，不再只指COUPON类型；活动全集LEFT保留零用券活动，普通降序最多N列表可保留NULL金额末尾。RS24同时核查非COUPON、零活动、多核销去重与金额不倍增。 |
| T5-007 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T5-009 | DEFECT | PASS | gold_sql | SELECT/GROUP/ORDER统一i.warehouse_id，LEFT仓库不删除孤儿库存；当前品牌LEFT为1:N由题干授权分组分别计入。RS06确认3组身份、数量、NULL归属均正确；ASC默认NULLS LAST符合三个裁决键要求。 |
| T5-010 | DEFECT | PASS | question,gold_sql | 题干明确已知佣金最高最多20；HAVING SUM(commission_amount) IS NOT NULL先排未知卖家，LEFT商品/翻译仍保留佣金事实。RS07通过。 |
| T5-012 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T5-014 | DEFECT | PASS | gold_sql | 先按每会员日期DESC和snapshot_id DESC取最新，再检查两个等级能在维表匹配，不把最新未知等级回退成旧快照。RS08同时验证同日最大ID和未知日期忽略，结果仅1位确认不一致会员。 |
| T5-016 | AMBIGUOUS | AMBIGUOUS | question,gold_sql | 排名主指标已明确为退款金额，但上轮反馈中的“已知金额最高”修订意图未落实；Gold仍返回全未知金额组。当前题面“按金额降序最多20”也可作为保留NULL末尾的普通列表，不能把这个synthetic结果直接称为字面违反p21。须明确选择普通列表，或显式已知金额最高并加HAVING；作者已选择后一种，待下一修订核验。 |
| T5-018 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T6-002 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T6-004 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T6-006 | DEFECT | PASS | gold_sql | COUNT分母加NULLIF，已送达且预计时间未知仍留分母；RS09空已签收集合返回NULL，比例单位仍为百分数两位。 |
| T6-008 | DEFECT | PASS | gold_sql | 只给独立products全集分母加NULLIF，未改变有效订单商品分子。RS10空全集NULL；RS11含重复有效明细及取消订单时分子1/全部商品3=33.33%，不是1/2或100%。 |
| T6-011 | DEFECT | PASS | gold_sql | LEFT地区与金额分母NULLIF同时落实；RS12显示未知地域10%保留、已知地域零GMV返回NULL，单位与有效状态范围不变。 |
| T6-013 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T6-015 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T6-017 | DEFECT | PASS | gold_sql | 转化率和奖励两个分母分别NULLIF；RS13空推荐(NULL,NULL)，RS14有推荐零转化(0,NULL)，没有把成功奖励未知补0。 |
| T6-019 | DEFECT | PASS | gold_sql | 先按州ROUND平均天数为最终展示别名avg_days，再外层排NULL并排序。RS15全未知州0行；RS16第10名原始10.003和10.004同展示10.00，由州AA裁决胜ZZ，证明未错误按未舍入值排序。 |
| T6-020 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T6-022 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |
| T6-024 | PASS | PASS | 未改 | 源问题/Gold未变；保持首轮PASS和七轴分析。 |

## 文件与来源

reviewer-revision-items.json包含全部100项、前后状态、原七轴和修订核查、候选/契约SHA、旧候选保留核对。reviewer-revision-evidence.json/sql保存完整实际及synthetic SQL、输出、失败与重试。

PromptTemplate最终SHA-256：`db75a631ddcd9084fe4cf3d4087ff35be11f8b89865a5ec31865b6b8999b6714`。没有修改候选题目、生产源码或数据库；没有Git提交。
