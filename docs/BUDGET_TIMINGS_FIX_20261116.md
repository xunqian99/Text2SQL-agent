# 阶段3：中断请求分段计时修复与验收

**超时响应将耗时清零的缺陷已修复；这不是一次加速。** 15秒受控模型阻塞经实际编排链路返回时，墙钟15011ms、检索37ms、生成14973ms、其余1ms，不再把模型耗时报告为0。Java全量535/535、失败/错误/跳过0；Python离线审计96/96。外部供应商调用0、实际费用0，不更新模型准确率或生产P95，阶段3仍未通过。

## 1. 原因、改动与请求数据流

前一轮[参考示例策略实验](AVAILABLE_EXAMPLES_RESULTS_20261116.md)有9次MODEL阶段超时，`budget.stageMs.MODEL`实际已记录等待，但响应 `timings.generationMs` 等四段为0。问题不在计时器没有运行，而在 `RequestBudget.stopped()` 新建失败响应时硬填四个0；`AgentResponse.withBudget()`只修总时间，没把分段接回来。若纠错超时后返回成功首答，它还保留首答旧分段，遗漏第二次尝试。

修复前先新增阻塞回归测试，在原业务实现上执行1项，明确失败于检索耗时0<20ms，见[before.log](../reports/budget-timings-fix-20261116/before.log)和去除系统属性的[before.xml](../reports/budget-timings-fix-20261116/before.xml)。这不是通过改标准答案让测试变绿。

具体请求例子：用户问“订单有哪些”→进入有界工作线程→Schema检索→准备生成→模型调用→超过15秒共享预算→等待线程冻结计时与账目→取消工作与传输→返回 `REQUEST_TIMEOUT`。响应带已有表上下文、`budget.stage=MODEL`、`termination=DEADLINE_EXCEEDED`、1次模型调用及1次未知用量，同时检索和模型等待均保留。未取得SQL时没有答案数据库执行，不把它归因为Join或聚合错误。

核心改动只有三处业务类：

- `RequestBudget`：复用已有的单调时钟与互斥阶段账本，快照增加计时版本、其他时间和最后一次阶段停留时间；停止响应使用同一快照。
- `AgentResponse.withBudget()`：预算开启时四段和总时间一起从快照投影；正常、失败、首答回退均一致。预算关闭仍保留原尝试局部计时。
- `Text2SqlOrchestrator`：生成准备显式标记 `GENERATION`，避免准备时间滞留在CACHE/RETRIEVAL；第二次结果检查标记 `RESULT_CHECK`，避免算进数据库执行。

HTTP DTO及评测证据已传递同一个 `Snapshot`，无需另写两套计时；测试验证JSON输出包含新标记。没有新增依赖，没有更改Prompt、模型参数、检索策略、预算数值、调用上限、答案选择或评分集。

## 2. 身份凭据、数学不变量与边界

请求由 `requestId` 标识，模型调用仍用 `callNumber` 与消息指纹追踪。计时口径新增 `budget.timingsVersion=request-budget-exclusive-v1`，不能把缺少版本的历史四段直接当成同口径数据。旧报告、旧分数与旧JSONL保持原样。

设请求开始为s，阶段切换为t1、t2…，停止时刻为f。`System.nanoTime()`提供单调时间；每次切换把上一阶段区间累加进 `stageNanos`，重复进入同名阶段先按纳秒累计。快照加上当前未结束区间，以毫秒向下取整，`finish()`把f冻结。阶段区间互不重叠，嵌套纠错继续用同一预算，不重新起钟。

新响应投影规则如下；每项是阶段集合的和，没有打分、权重或Top-K：

| 响应字段 | 对应的互斥阶段 |
|---|---|
| retrievalMs | RETRIEVAL |
| generationMs | REWRITE＋GENERATION＋MODEL |
| validationMs | VALIDATION＋RESULT_CHECK |
| executionMs | EXPLAIN＋EXECUTION |
| totalMs | 请求开始到冻结时刻的整体时间 |
| budget.otherMs | totalMs减上述四段；含准入、会话、澄清、缓存、工具编排及毫秒舍入 |

因此四段之和≤totalMs，四段＋otherMs=totalMs。详细 `budget.stageMs` 继续保留ADMISSION、TOOL等身份，不把工具等来源混成模型开销；otherMs不是“模型未知耗时”。按阶段名累计后取整产生的误差小于阶段种类数毫秒，不会因重试重复相加父子计时。

`budget.currentStageMs`记录最后一次进入当前阶段的停留时间，`stageMs[stage]`记录该阶段历次停留总和；二者不能相加。`termination!=COMPLETE`表明最后阶段未完成；相减可以估计此前同名阶段已完成的停留，最多有1ms取整差。比如纠错场景MODEL合计506ms，其中最后一次475ms，第一轮MODEL约31ms，不能误称全部506ms都是第二轮。

边界与限制必须区分：

- 模型调用前在生成准备阶段超时：generationMs仍有已消耗时间，但stage为GENERATION、模型调用数0、未知用量0。耗时不是收费凭证。
- 模型调用超时：保留模型等待和未知用量，不能估造Token或按0元记账。`generationMs`包含准备、网络、服务端等待及返回处理，不能称为供应商内部纯推理时间。
- SQL执行或EXPLAIN超时：归入执行分段，已知模型费用照常保留；底层取消机制没有被替换。
- 纠错超时且首答成功：仍返回首答结果，但分段覆盖两次尝试，第二次未知用量仍保留。不能从选中哪份答案决定哪些等待需要付账。
- 调用者中断、不响应中断的工作线程或迟到结果：冻结后的快照不再增长，迟到写入不能覆盖响应。后台清理耗时不属于冻结前请求时间，清理拒绝另报。
- 预算关闭时仍为原尝试局部计时；与新版本不能混合分析。旧报告里的MODEL=0也不应回写“修正”为新分数。

## 3. 实际验收数字

[受控探针](../reports/budget-timings-fix-20261116/acceptance.json)使用真实 `Text2SqlOrchestrator`、`LlmSqlGenerator`、Prompt及预算执行器；Schema、模型传输、校验结果与SQL结果使用明确标记的合成夹具。**这是工程计时验收，不是业务题或模型评测。** 共4个场景、5次模拟传输，外部供应商请求0。

| 场景 | 外部墙钟ms | 检索ms | 生成ms | 校验ms | 执行ms | 其他ms | 请求总计ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| 正常返回 | 140 | 47 | 55 | 0 | 18 | 11 | 131 |
| 执行超时，500ms预算 | 505 | 39 | 32 | 0 | 433 | 0 | 504 |
| 纠错超时，600ms预算 | 608 | 79 | 510 | 1 | 15 | 3 | 608 |
| 模型阻塞，15000ms预算 | 15011 | 37 | 14973 | 0 | 0 | 1 | 15011 |

外部墙钟包围整个Java入口；预算计时在执行器创建预算时开始，到返回前冻结，初始化与响应映射开销可产生毫秒差。15秒预算存在调度容差，本次实际多11ms，仍在既有16秒工程门槛内，不承诺实时操作系统级别零超调。

正常及SQL超时场景分别1次模拟调用、未知0；纠错2次模拟调用、未知1，返回首答SUCCESS；15秒模型超时1次模拟调用、未知1、返回REQUEST_TIMEOUT。未知都是合成夹具身份，不能与上一轮真实供应商9次未知混算成本。先前真实未知费用没有因本修复得到补齐。

Java535项中新增9项 `BudgetTimingsTest`：模型超时、正常互斥投影/HTTP序列化、纠错回退、调用者中断、迟到工作冻结、嵌套纠错、预算关闭、检索超时、生成准备超时。既有3项测试强化断言：实际编排SQL超时保留检索、真实PostgreSQL `pg_sleep`取消后执行计时、本地慢响应HTTP取消后生成计时。55个测试套件，535/535、失败/错误/跳过均0，真实数据库测试没有跳过。

Python96/96为现有离线审计工具检查，不计入535项Java，也不计入评测题库。原基准仍200道，另100道AI候选题未变；本轮没有新模型准确率。

## 4. 可验收路径与影响范围

```powershell
# 专项；数据库集成测试需本地PostgreSQL可用
mvn -q '-Dtest=BudgetTimingsTest,BudgetOrchestratorTest,BudgetTransportTest,BudgetDatabaseTest,RequestBudgetTest' test

# 全量
mvn -q test
python -m unittest discover -s scripts -p 'test_*.py'

# 四场景探针会等待一次15秒，不调用外部模型；输出必须是新文件
mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/budget-timings-classpath.txt'
$timingsCp = 'target/test-classes;target/classes;' + (Get-Content target/budget-timings-classpath.txt -Raw).Trim()
java -cp $timingsCp com.text2sql.agent.budget.BudgetTimingsAcceptanceProbe target/budget-timings-new.json
```

预算开启的HTTP响应与新评测报告获得同一口径；四个字段名保持，`budget`新增3个字段。按旧口径分析的消费者需先读timingsVersion，尤其validationMs现在包含结果检查、executionMs包含EXPLAIN。没有新模型表现或阶段3通过的结论；P95仍需后续真实三轮达到原门槛。

实现检查通过后先提交代码与证据；再在该提交上运行原200题Gold自检及100组提示词捕获，验证业务执行和提示词没有顺带变化，补充记录单独归档。这里不提前声称后续检查已完成。

## 5. 设计取舍与面试答辩

**为什么不直接把totalMs全填进generationMs？** 数据库、检索或工具也会超时，这会制造新错账。现有互斥阶段账本已经有单调时间和来源，复用它比新增一套重叠的尝试计时更容易证明不重复。

**为什么不只修超时分支？** 那会让正常请求沿用一种口径、失败请求用另一种，首答回退仍漏掉纠错时间。本次预算开启路径统一投影并标记版本，预算关闭路径保持原样。代价是不能把新旧分段直接拼成趋势图。

**面试追问1：“返回首答，为什么生成时间包含第二次调用？”** 答案选择与资源账目是两件事。用户为整次请求等待，第二次已发送的调用也可能收费，分段必须覆盖整个请求；首答的局部计时不能代替请求时钟。

**面试追问2：“有15秒计时是不是就能算Token费用？”** 不能。墙钟是客户端单调时间，Token必须来自供应商返回或迟到账单；没有usage就标未知。阶段身份用于定位等待，不能替代收费凭据。

用户无需新增配置。需要掌握：Schema→生成→校验→执行如何切换阶段；已知/未知用量怎样区分；MODEL超时为什么不能写成SQL语义错误。本轮把定位数据修准，没有把性能问题解决。
