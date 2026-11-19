# B提交2覆盖索引

根目录`coverage-matrix.md`属于被拒绝的提交1；其“生成异常回退2次”不能满足B出口。以下以提交2冻结源码和最终输出为准，其余权限/工具要求保持相同，未删除反例。

|边界|提交2直接证据|结果/判据|
|---|---|---|
|生成异常后，4种目录故障×CALL_FAILED/UNPARSEABLE/OUTPUT_TRUNCATED|最终XML `unavailableCatalogBlocksAllRepairableGenerationFailureRetries[1..4]`，每项3原因|12组合；已发生首答1、额外模型0、SQL/EXPLAIN0；已发生用量保持|
|显式纠错4种目录故障|`explicitCorrectionRejectsUnavailableCatalogBeforeModelCall[1..4]`|模型/SQL/EXPLAIN0，SCHEMA_UNAVAILABLE|
|首答校验失败后目录失效|`catalogFailureBeforeAutomaticCorrectionKeepsFirstUsageAndSkipsSecondCall`|首答用量保持，自动纠错模型0、SQL0；trace记录纠错准入拒绝，不冒充第二模型调用|
|正常目录可恢复生成失败|`availableCatalogStillAllowsRecoverableGenerationRetryWithinExistingLimit`|生成2、SQL1，两次用量相加，原预算不变|
|纠错发送后才发生目录故障|`correctionCatalogFailureIsTerminalRatherThanReturningEarlierSuccess`|发送前检查成功；最终校验失败不执行第二SQL，两次已发生用量保留，不回退首次可疑成功|
|原漏检真表/真列、别名、缓存/固定/纠错、损坏目录与工具粘滞|同类其余38项对应原要求，最终总计47项（新增9个JUnit执行项；参数方法内部还遍历3原因）|全通过，明确对象来源和列归属未改变|
|原独立101探针，实施者原样回放|`implementer-replay-of-independent-probe.json/txt`|101通过、0失败；不是第二次独立审查，也未调独立断言|
|最终完整构建|`full-build-final/execution.json`及99份最终XML|1233通过，失败/错误/跳过0；focused中旧XML不另计新测试|
|受保护目录HTTP及真实只读DB|`catalog-http-evidence/summary.json`|11项，真表真列+3工具通过；4类非法SQL执行0、鉴权、会话、费用保留|
|鉴权/费用/会话/独占计时|`access-http-evidence`、`cost-http-evidence`、`session-http-evidence`、JSON文件`timings-http-evidence`|36/4/3/4项或场景均通过；使用真实保护链路/受控替身，不是准确率或P95|
|首答输入保持|`first-call-invariance.json`及6份消息|候选400次新内存捕获与提交1冻结p21对照400次逐字一致；不是供应商发送|
|Gold可执行性|`gold-execution.json`与3份原始dryrun|原200、V4开发100/正式100全通过；非模型成绩/新评分器验收|
|历史、数据与依赖保全|`integrity-verification.json`、`candidate-identity.json`；根提交1补丁/JAR和独立拒绝原文|37表数据身份不变，79依赖不变，原9项差异保留；授权代码/JAR变化单列|

正式审查2需独立判断本包全部出口，不能只凭实施者101回放或测试总数接受。新危险放行仍须为0；未见题和付费评测没有进入本包。
