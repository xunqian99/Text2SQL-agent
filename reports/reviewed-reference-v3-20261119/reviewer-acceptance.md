# 最终参考题库修订复核验收

**100 PASS / 0 DEFECT / 0 AMBIGUOUS；无待定项。** 这是审查反馈后的修订复核，不是未见盲测，不代表生产准确率通过。providerCalls=0。

最终相对draft3只改T5-016，其他99题逐字比对问题/Gold一致。最终这一题真实执行20行，与draft3双向EXCEPT ALL差异均0；两个只读synthetic补验均通过：全未知退款金额不返回；金额已知但缺英文翻译的NULL名称组保留；同一退款两条明细的金额相加，退款笔数仍只计1。

此前99/0/1审查记录保留。原revision报告写入时候选发生切换，权威当轮冻结记录见reviewer-revision-frozen-items.json，版本勘正见reviewer-revision-snapshot-note.md；本文件不覆盖旧结论。

完整验证链：首轮全部100 Gold实际执行；revision集中复验13条改Gold及24个synthetic场景；本次只补T5-016一条实际查询和2个synthetic，没有重跑全100。未改与改后真实结果合并仍808行，单题最大52行（T2-019）。41条无LIMIT参考SQL按裁决用于完整预期集合，不豁免生产规则，也不改执行器上限。

所有本次SQL只读且8秒超时并ROLLBACK；未调用模型，未修改候选或生产文件，未Git提交。全部100项最终状态和逐项证据引用在reviewer-acceptance.json。

## 最终6个候选文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| data/benchmarks/olist-v3-candidate/T1_single_table_basic.yaml | `33b76dc96570c1feb33efdd77745dbc791bedc5e2641900fa479a7e59488fc7e` |
| data/benchmarks/olist-v3-candidate/T2_single_table_agg.yaml | `06cfb00244a0b8e0c07fc0f0226f01d26b21a20c8d9cb2f826ebca4a0e590ae2` |
| data/benchmarks/olist-v3-candidate/T3_time_window.yaml | `fda5b14beecd51aafa5b4818de1b2c538acf544d79777428f0a290d5d749269b` |
| data/benchmarks/olist-v3-candidate/T4_multi_join.yaml | `c811af116e0af60acaf8f74f6b3ca0988b9cb02df2e9d4b8bfba6d45d03f8aaf` |
| data/benchmarks/olist-v3-candidate/T5_multi_join_deep.yaml | `7b0e5063e15857d3a5f61bd923c23ee7dbb874a09e2c52a5331bc52163593c31` |
| data/benchmarks/olist-v3-candidate/T6_business_semantics.yaml | `313b43a6ce6ab1cf7d4ec146bb1301ff0d5c6e2ecada7462a28d0a8006d1c48b` |

## 契约及指定源码/DDL SHA-256（与初审一致）

| 文件 | SHA-256 |
|---|---|
| data/schema/01_olist_schema.sql | `3c7a256d13378e81b8c3c001e1ced117f2a00ec758634d5a2c103a3e82000105` |
| data/schema/03_extend_schema.sql | `ca9910e2a3a2996350e3dd9c8305e416d0e2efbe8edac4804b9216269f9652f0` |
| src/main/java/com/text2sql/agent/clarification/AmbiguityDetector.java | `155f6f879d696083955e4ddf231c52e713695c6361a37b1224ac9e12fcbc8f18` |
| src/main/java/com/text2sql/agent/generation/PromptTemplate.java | `db75a631ddcd9084fe4cf3d4087ff35be11f8b89865a5ec31865b6b8999b6714` |
| src/main/java/com/text2sql/agent/retrieval/DatabaseSchemaReader.java | `c264eb79e4c73fa1d8302f355195a02958c9d2e3e15dc09c082506e2da917f6d` |
| src/main/resources/schema/ambiguities.yml | `493dccf3090c7f7ea544162a351e8c8d1e7ab085886ec16aa12797ae89ce3e3a` |
| src/main/resources/schema/glossary.yml | `b4bc655947ed1f4eb2e243bbdaf3800bdd7919ee4121fc9a7276b647947b99ac` |
| src/main/resources/schema/metrics.yml | `78dfc23f9075536aa7568f94c87c41d238cac0c66c553449c9fd7c67c9035192` |

不能把这些数字写成“生产100%准确率”：这里检查的是100道参考题的题意、Gold和已选口径是否一致及边界是否被落实；没有调用生产模型生成答案，也没有评价模型泛化。
