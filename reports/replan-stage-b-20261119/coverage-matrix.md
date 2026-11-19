# B覆盖矩阵（冻结证据索引）

这是提交1已冻结测试及产物的索引，不新增实验，不改候选。组件行均指最终`full-build-final/surefire/TEST-com.text2sql.agent.orchestrator.CatalogValidationBoundaryTest.xml`；真实HTTP行指`catalog-http-evidence/summary.json`。

|要求/分支|直接证据|判据与范围|
|---|---|---|
|模型SQL最终执行：未检索真表、剪枝真列、别名JOIN|`omittedTableAndPrunedColumnPassWithoutExpandingGenerationContext`|真SQL执行1，模型生成只获原对象，纠错0|
|固定SQL与dry-run|`everyExecutionEntryUsesFullCatalog[1/2]`|统一完整目录；检索统计不变|
|显式纠错|`everyExecutionEntryUsesFullCatalog[3]`|纠错仍用原生成上下文，执行用完整目录|
|缓存命中|`everyExecutionEntryUsesFullCatalog[4]`|模型0；重新校验与执行1|
|缓存SQL非法后的重新生成|`rejectedCacheFallsBackThroughSameFullCatalogBoundary`|非法缓存不执行；重新生成仅走同一完整边界|
|生成异常后的回退|`failedGenerationFallbackStillCannotExecuteWithoutCatalog`|生成替身2次，目录故障后纠错0、SQL0|
|首答目录空/null/异常/未实现|`unavailableCatalogCannotTriggerCorrectionOrCacheFallback[1..4]`|首答已经发生1次替身生成，额外纠错0、SQL0；不泄漏异常详情|
|上述4种目录故障，缓存命中|同上4项中第二分支|模型0、SQL0，不重新生成|
|上述4种目录故障，固定SQL|同上4项中第三分支|SQL0|
|纠错时目录失效与首次结果回退|`correctionCatalogFailureIsTerminalRatherThanReturningEarlierSuccess`|第二次故障被选定；第一次执行已发生1次，之后执行0；两次用量相加|
|损坏目录：tables=null、null表、空表名、空列集、null列、空列名|`malformedCatalogIsAnEnvironmentFailure[1..6]`|编排与SAMPLE均归类目录故障；SQL0|
|SAMPLE/CHECK/EXPLAIN：真表真列不在生成子集|`toolsUseCompleteColumnsAndOmittedTables[1..3]`|每工具仅取同一完整目录1次；通过|
|五种DB工具空/null/异常目录|`allDatabaseToolsFailClosed[1..3]`|15种组合均为本地故障信号，SQL/计划分析0|
|真实Spring工具回调与循环终止|`realToolCallbackStopsLoopBeforeOtherToolsOrFinalizationAndKeepsUsage`|模型工具轮1；目录1次；同轮第二工具、收口、生成重试和SQL均0|
|虚构表与限定列|`fullCatalogDoesNotAuthorizeUnsafeObjects[1..2]`|最终SQL、SAMPLE、EXPLAIN均拒绝，业务执行与计划分析0|
|五个内部表|同上`[3..7]`|五类内部表均不授权；三个入口各拒绝|
|archive/public显式schema、pg_catalog、information_schema|同上`[8..11]`|不去掉schema强行匹配；原保守规则保持|
|写语句和危险函数|同上`[12..13]`|原AST护栏继续拒绝|
|真实只读DB：真表漏检与列剪枝|HTTP `omitted-table-pruned-column`|37表目录与1表生成上下文，HTTP200、5行，真实只读执行|
|真实只读DB：三个工具|HTTP `real-db-SAMPLE_QUERY/CHECK_JOIN/EXPLAIN_QUERY`|实际SQL或EXPLAIN通过|
|真实HTTP：4类非法SQL|HTTP四个`reject-*`|虚构限定列、内部表、跨schema、虚构表，HTTP422且业务执行0|
|HTTP鉴权先行|HTTP `unauthenticated`；`access-http-evidence/summary.json`|目录专项401、模型0；原36项鉴权/来源/限流等通过|
|HTTP目录故障、会话endSession|HTTP `unavailable-terminal-with-session-close`|422+SCHEMA_UNAVAILABLE、业务执行0；已付替身生成1、会话关闭|
|费用预留|HTTP `cost-reservations-settled`；`cost-http-evidence/summary.json`|目录专项6次已知替身用量结清，pending/unknown0；原费用4场景通过；本地模拟账额不是供应商费用|
|超时、迟到结果与会话收尾|`session-http-evidence/summary.json`；最终`BudgetDatabaseTest`等XML|真实DB取消/预算与3会话场景；无模型准确率或线上延迟结论|
|独占计时与已付用量|JSON文件`timings-http-evidence`|4受控场景，分段及回退账目守恒|
|首答完整消息与原检索统计逐字保持|`first-call-invariance.json`及6份`messages-*.json`|400对请求一致，800次内存模型替身；外部发送0|
|Gold链路|`gold-execution.json`及原始3份dryrun报告|原200+V4开发100+正式100均可执行；旧评分器不代表V4模型正式评分|
|未改变依赖、排名、提示词与数据|`candidate-identity.json`；`integrity-verification.json`|79个打包依赖不变；授权源码变更单列；330原保护文件与37表数据身份不变|

列校验的已知能力边界（裸列、CTE/跨作用域不可判定别名）及进程内目录快照策略详见技术导读；这些项目不写成静态列验证100%覆盖。任何新增危险放行均须由独立审查拒绝，不能以本矩阵正例数量抵消。
