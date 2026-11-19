# 独立复核收尾补充（保留原报告）

最终结论：**PASS 79 / DEFECT 13 / AMBIGUOUS 8**，合计100。新增3题问题；初步报告的82/11/7不再作为最终数字。原reviewer-reference-review.md和reviewer-item-review.json保持原样，最终完整100项及700个逐题核查轴在reviewer-final-item-review.json。

100条Gold在当前真实库执行成功，共808行。13项DEFECT由当前契约与DDL允许的synthetic边界证成，未声称当前真实样本已有这些坏行。8项AMBIGUOUS要求固定题干/口径；真实月缺口和TopN成员差异证明这些问题有实际区分度。providerCalls=0。

## 新增发现

### T5-003 — DEFECT

聚合退款金额可全NULL，Gold只用NULLS LAST仍会在候选不足15组时选入未知金额类目；当前最值候选规则要求仅已知目标属性。

建议：先按英文名称聚合并ROUND金额，再在外层WHERE refund_amt IS NOT NULL后按refund_amt和名称稳定取前15。保留缺翻译NULL名称组；排除的是未知金额，不是未知名称。

证据：A13_nullable_amount_facts, S14_T5-003_unknown_refund_amount_top15。

### T5-010 — DEFECT

commission_amount允许NULL，某卖家佣金全未知时Gold仍会把该卖家放入佣金Top20；NULLS LAST不能替代排除未知最值候选。

建议：先按卖家聚合佣金和英文类目数，外层WHERE commission IS NOT NULL后按最终两位佣金排序取前20；不影响金额已知但类目未知的卖家。

证据：A13_nullable_amount_facts, S15_T5-010_unknown_commission_top20。

### T5-016 — AMBIGUOUS

同时请求退款笔数与退款金额，却只说前20，未说明按哪个主指标排名。真实228组中按笔数与按金额的Top20各有3组只在本方案中，不能把Gold任意选择金额当成唯一标准。

建议：明确“按退款金额降序取前20”以保留Gold，或明确按退款笔数排名。金额排名还应过滤全未知金额候选，不能仅NULLS LAST后LIMIT。

证据：A14_T5-016_top20_ranking_ambiguity。

## 证据与独立性

共29个最终证据案例：14个实际数据/元数据检查，15个只读synthetic构造。新增SQL及原始结果在reviewer-addendum-evidence.sql/json；原25例不覆盖。曾有一例构造SQL因重复WITH出现语法错误，已在形成原报告前修复重跑，该失败不能作为语义证据。

未参与题目、Gold或生产调参，使用fresh reviewer context。未读历史模型输出、其他审计/lineage、历史调参结论。初次T1–T3原文读取附带candidate notes，已在主报告如实披露且未据此论证；不能声称完全没有看到candidate notes。只读当前契约和指定源码/DDL，未追读文件中提及的历史报告。

源文件内容在主报告SHA和最终验证之间保持一致；最终PromptTemplate SHA-256：`db75a631ddcd9084fe4cf3d4087ff35be11f8b89865a5ec31865b6b8999b6714`。全部候选/契约SHA在reviewer-final-item-review.json的metadata.manifest中。

全部SQL均在BEGIN READ ONLY和8秒statement_timeout会话内执行并ROLLBACK。没有修改候选或生产文件，没有Git提交。41条缺LIMIT与普通列表NULL排序作为源形式lint单列，没有混入13项语义缺陷。
