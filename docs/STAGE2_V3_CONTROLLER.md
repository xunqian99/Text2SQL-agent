# V3固定批次控制器

入口为`scripts/run_stage2_v3_comparison.py`，只有`run-next`可能解析真实密钥并启动付费Java进程。`prepare`、`preflight`和`audit`不调用模型；Gold预检使用显式假密钥。控制器只散列YAML原始字节，不解析或打印未见题面、Gold或逐题失败。它会在程序内部读取Java报告以审计，但对外始终只输出汇总。

所有命令用已安装Python运行；Windows示例解释器为`C:/Users/xunqian/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`。所有路径必须不含空白和引号，沿用现有评测入口的限制。

## 使用顺序

1. 完成代码、脚本及协议提交；开发、正式、未见三个100题数据集必须有最终独立审查冻结章。未见作者自己的manifest不能替代独立审查，`independentReview`必须为`true`。候选与基线JAR必须已经构建并通过各自检查，控制器不替你重建JAR。
2. 使用一个从未存在的协议目录执行`prepare`。它冻结双方运行HEAD、已登记生产源码、完整src与配置、JAR、控制器依赖、协议、数据及冻结章、37张业务表的数据库快照。已有目录拒绝覆盖。
3. 执行`preflight DIRECTORY`，在双方JAR上分别执行开发/正式/未见Gold预检，共6次100题；必须全部100/100、零模型调用、没有结果截断、双方输入及Gold结果指纹相同，才进入`READY`。生产上限仍200行；不能让Gold也截断后自匹配冒充完整通过。
4. 每次执行`run-next DIRECTORY`只启动下一轮，顺序为`DEV_C,B1,C1,C2,B2,B3,C3,U_B,U_C`。开发未达任一阈值即`HALTED`；开发通过后，正式结论不通过仍完成已登记的未见两轮。任何证据漂移、运行中断、Java本地时段拒绝都会停止。
5. 执行`audit DIRECTORY`复查实时源码/配置/数据库及全部归档，或`scripts/summarize_stage2_v3.py DIRECTORY`输出相同汇总。`--evidence-only`只读离线归档，不访问当前数据库，也不表示当前环境仍与冻结时相同。汇总不会回写、替换旧报告。

准备命令模板如下；`NEW_PROTOCOL_DIRECTORY`必须是一个新路径，三个数据目录与冻结章需使用最终审查后的实际路径。

```powershell
& $python scripts/run_stage2_v3_comparison.py prepare `
  --directory NEW_PROTOCOL_DIRECTORY `
  --dev-dir FROZEN_DEV_DIRECTORY --dev-seal FROZEN_DEV_SEAL `
  --formal-dir data/benchmarks/olist-v3-candidate `
  --formal-seal data/benchmarks/olist-v3-candidate/reference-seal.json `
  --blind-dir FROZEN_BLIND_DIRECTORY --blind-seal FROZEN_BLIND_SEAL

& $python scripts/run_stage2_v3_comparison.py preflight NEW_PROTOCOL_DIRECTORY
& $python scripts/run_stage2_v3_comparison.py run-next NEW_PROTOCOL_DIRECTORY
& $python scripts/summarize_stage2_v3.py NEW_PROTOCOL_DIRECTORY --evidence-only
```

默认候选仓库是当前项目，默认基线是`C:/Users/xunqian/.codex/worktrees/stage2-v3-baseline/agent`；可用`--candidate-repo`和`--baseline-repo`显式指定。默认JAR是各自`target/text2sql-agent-0.1.0-SNAPSHOT.jar`，允许显式指定已冻结JAR路径。

## 恢复与边界

北京时间工作日09:00—12:00、14:00—18:00拒绝付费。外层还要求下一高峰前至少1740秒余量；不足时返回`WAITING`并保留下一轮`PENDING`，不睡眠、不拿密钥、不启动Java。稍后仍执行原目录的`run-next`。缺密钥同样保留`READY/PENDING`。

启动前先持久化`STARTED/RUNNING`；进程退出后才审计并归档为`COMPLETE`。崩溃、超时或中断的已开始轮次不可静默补跑。锁文件残留表示运行结果不确定，应保留现场；控制器不自动删除锁，也不自动补题。`state.json`是状态登记，`plan.json`及每轮原始报告/JSONL/进程日志是证据；不要手工修改它们。

数据库采用只读`REPEATABLE READ`快照，排除5张内部运行表，固定37张业务表；字段类型、注释、约束与每表行数、按稳定JSONB字节生成的SHA-256行多重集一并散列。数据库值不会导出，只返回散列与计数。Java显式固定`localhost:5432/olist`和`text2sql_ro`，与Docker快照目标对应；密码仍从冻结的本地配置或环境读取，控制器不打印。

费用重用已有请求预算审计器：正常已知用量、晚到已知用量和仍未知用量分开。`costYuan`只计报告内已知费用，`fullKnownConfiguredCostYuan`另纳入已归档晚到用量；存在`unresolvedUsageCalls`时仍只是费用下界。JSONL完成时间不被当作HTTP发送时间；实际发送时段由已审查的Java入口及发送前门禁保障。

控制器检测意外漂移，不提供防恶意篡改的数字签名，也不把共享文件系统的协作独立性宣称为操作系统隔离。独立审查仍是流程凭据；冻结二进制的完整来源还需结合构建记录和既有独立安全审查判断。它不会用新的脚本门槛替代协议登记的业务验收。

## 技术与设计导读

请求例子是“继续下一轮”：控制器读取计划与状态，按固定顺序找到尚未开始的轮次，核对资源指纹与37表快照，检查空闲窗口至少1740秒，才解析真实Key；先将STARTED持久化，再启动指定JAR。100题结束后重算账目与分母，开发门槛失败即停止，成功才允许正式/未见后续轮。来源通过side、dataset、run name及SHA关联，不能把基线与候选、预检与付费、已知与未知用量混到一个数里。

核心是Python状态机、排他锁、SHA-256、PostgreSQL只读一致快照和已有Java发送前门禁，没有新增业务依赖。40项离线检查通过；真实只读37表快照一次13.713秒。这些数字不代表模型准确率或请求P95。报告的schemaTableCount是本轮最大检索上下文表数，数据库总表数另由快照固定，二者不可混淆。

历史基线报告没有explicitReasoningEnabled、fewshotRequireAvailableTables、metricDefinitionsSha256三个字段。仅对已绑定源码及JAR的历史版本允许缺报，并在汇总中列出：p9固定含四步指令，旧示例选择没有可用表过滤，指标定义由源码与JAR资源SHA核对。候选必须完整上报；没有把推断值伪写进历史原报告，也没有为了补观测改造基线生产链路。

没有选择“只在启动时看时间”，因为请求排队和重试会跨窗；没有选择崩溃后自动重放，因为供应商是否已经收到请求可能未知，重放会重复计费和挑样本。这里没有新的检索打分、TopK权重或模型路由。最坏边界是进程崩溃留下STARTED和锁，保留现场并停止，不能静默补题。

面试问“你能保证账单硬封顶吗？”——不能；已知用量按冻结价格计算，超时未知用量保留为未知，发送限制是准入规则而非供应商结算承诺。问“怎么证明报告可比？”——同题/同Gold/同数据库、公共参数相同、双方各自源码/JAR固定，正常失败仍进100题分母，资源漂移则整轮证据不成立。

无需用户额外操作；应核对每轮属于哪个版本、费用下界与未知次数、失败属于准入/运行证据还是模型生成层。
