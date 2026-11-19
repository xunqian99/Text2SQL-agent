# 新 D 注册 revision-2 独立工程审查

结论：**拒绝工程准入（accepted=false）**。117 项测试全部通过，不能掩盖两个已经复现的 P1 阻断。

- 固定 plan SHA256：`91c67f5a2484fd6fd0e63a1f86c09ebb8e20737f5a7a3d4c4850b9b63c9c17b4`
- 审查对象：`batch-1/reregistered-dev-1/revision-2`，C1 与原有指标门槛未调参。
- 本次仅在本审查目录写合成夹具、复现实验与结论；未改驱动、冻结计划、真实账本、旧决策或付费输出。
- 未读取真实模型凭证、未执行实际 `run-next`、未创建真实授权、未提交模型问题、未调用供应商；未读取 unseen/blind/holdout 或旧 U_B/U_C 逐题结果。

## 阻断 1：候选配额可以回退到旧种子，丢失新支出或新 UNKNOWN

位置：`scripts/run_replan_stage_d_reregistered.py:183–195`。

`check_candidate_quota()`只把当前候选账本与旧批次账本比较：预算身份一致、knownCalls 不低于 67、knownCost 不低于 0.16312188、旧 3 笔 UNKNOWN 仍在、承诺金额不超过 1 元。它没有绑定新批次最近已验证的候选快照，也没有保护新产生的 UNKNOWN。运行时 `candidate-cost-quota.json`不属于计划不可变文件或完成轮次证据；开始请求后，恢复旧种子仍能通过该校验。

独立反例见 `quota-rollback-counterexample.json`，所有写入都在 `quota-rollback-counterexample/`：

| 情况 | candidate 承诺金额 | common 承诺金额 | 再保留 0.10 元 |
| --- | ---: | ---: | --- |
| 历史基线 | 0.46312188 | 0.36312188 | 两层都可继续 |
| 新增 0.50 元已知费用 | 0.96312188 | 0.86312188 | candidate 必须拒绝，common 仍可继续 |
| candidate 单独恢复旧种子 | 0.46312188 | 0.86312188 | 当前检查接受，原本更严格的 candidate 被重新打开 |

同一实验还证明：保留原有三笔 UNKNOWN，但删除一笔新 UNKNOWN，校验仍接受。两层数字不是相加后的真实账单，common 的正确性也不能替代 candidate 的独立约束；历史两层相差的一笔 0.10 元保留额，正是这个漏洞有实际影响的原因。

需要补的防线是新候选账本的单调检查与可核验生命周期：knownCalls/knownCost 不能回退；新 UNKNOWN 不能凭文件替换消失；允许正常 PENDING 结算时，必须对应明确的 usage/结算证据。不能只删除启动 SHA 检查来修第二项，否则第一项更容易触发。本审查未实施修复。

## 阻断 2：正常 JAR 启动会因条目顺序变化，在第一题前被误拒

位置：`scripts/run_replan_stage_d_reregistered.py:305–307`；调用顺序在冻结驱动 `scripts/run_replan_stage_d.py:726、731、735`；候选 Java 持久化行为可见 `CostQuota.java:75、80、124`。

驱动先 verify，再写入 `DEV_F=RUNNING` 并启动应用。Java `CostQuota`构造器加载旧 holds、调用 `Map.copyOf(holds)`并立即 persist；新的 JVM 可以改变 3 个 holds 的迭代顺序。随后循环第一题前再次 verify，此时 requestsStarted 仍为 0、common calls 仍为空，代码仍要求运行时文件 SHA 与初始种子完全相同。仅对象字段顺序变化，也会被当成实质回退。

独立复现使用实际冻结 C1 JAR，仅启动并读取健康状态，无 `/api/ask`：

- 启动前完整 `verify(plan,database=False)`通过。
- 应用达到 ready；配额语义完全相同，67 knownCalls、0.16312188 已知费用与 3 UNKNOWN 不变。
- 第一次启动即观察到 holds 次序 `2a735… / 36c9… / 6644…`变成 `6644… / 36c9… / 2a735…`。
- SHA 从 `d4fc0f0a78e0eb5f989d5e8ae7e14822997c9637a5d96ed3372224260198b312`变成 `9fec85ada762563e0438d25740d925960a1830241efacadab623adfd763378d6`。
- 第一题前 verify 拒绝：`Before first paid start, inherited candidate runtime quota must equal its exact seed`。
- 提交问题 0、模型调用 0、供应商调用 0、真实 key reader 0；真实运行目录和授权路径未变。

完整证据：`ready-only-first-start.json`与 `ready-only-first-start/start-1/`下的 before/after 原始字节、application.log、health.json。实验只复现就绪后校验拒绝；未调用生产 run-next。根据冻结驱动的异常处理，该拒绝会把一轮置为不可重放的 `INTERRUPTED_NO_REPLAY`，因此不能用一次付费授权去赌 JVM 的迭代顺序。

初始种子逐字节检查只适用于尚无轮次的首次启动前。应用启动后应使用语义身份、保留额与新的单调检查；仍须保留确定性拒绝回退的能力。此处没有评分或 SQL 质量问题，错误发生在控制器生命周期层。

## 已验证内容与数字

- 单次独立组合测试：5 个最终模块，共 **117/117**，0 failure、0 error、0 skip，耗时 **10.226 秒**。两个证据模块先重定向到本目录的绝对路径，所有合成授权均留在本审查目录。
- 原有 117 项测试与 preflight **不覆盖**“新增费用/UNKNOWN 后恢复旧种子”及“真实 JAR ready 后、第一题前再核 exact seed”两条边界；117/117 不能作为这两条防线成立的证明。两个新增独立实验正是补这两项缺口，结果均为阻断成立。
- 新计划绑定 **374** 个文件：**50** 个 Git 原始字节文件全部匹配，**324** 个 runtime 文件逐项 SHA 匹配。
- preflight 的 **44** 个证据文件：工作区 SHA 与 Git 原始字节均匹配；旧付费 manifest 的 **401** 个文件核验通过。
- 数据库身份重新只读校验通过；真实新 state 仍 requestsStarted=0、rounds 空、terminal=null，common calls 空，candidate 仍为原始种子。
- 原始 C1 免费预检已真正加载历史候选配额，保留了预算/pricing 身份、历史费用和 3 UNKNOWN；其本地合成 usage 不计入真实账单。
- 无人类授权时，新入口与读 key 前屏障均拒绝；真实 key reader 和继承付费路线调用数均为 0，夹具 state/ledger/目录字节变化数为 0。
- 固定代理端口的独占、关闭、TIME_WAIT/跨进程重开、无随机端口回退、冻结 handler 继承等测试均在 117 项中通过。固定端口解决 pricing 身份漂移，不能解决上面两个候选账本边界。
- 写文件 helper 与 schema 均已进入计划；旧驱动和旧代理原始文件未修改。Python helper 的重试是同一临时文件对同一目标的有限重试，不能对同步 OS 调用给出硬墙钟上界，这个历史限制不变。

保留历史事实：旧 68 个 HTTP 问题、70 次应用 modelCalls、69 个 common permits、68 次 transport attempts、67 次已知 usage、已知费用 **0.16312188 元**。common UNKNOWN 为 **2 笔 / 0.20 元**，candidate UNKNOWN 为 **3 笔 / 0.30 元**；承诺金额分别为 **0.36312188 / 0.46312188 元**。这两层不能相加成账单。旧批次保持 `INTERRUPTED_NO_REPLAY`；没有完整 DEV100 准确率或完整 P95，也不能伪造。

## 技术与设计导读

具体链路示例：新一轮 DEV_F 启动，控制器先核计划/旧账本/授权/预检，候选 JAR 加载同一个 budgetId 与 pricingIdentity，开始题目前再 verify，然后 common permit 与 candidate lease 分别保留 0.10 元，真正发送后按 usage 结算。此次两个缺陷分别落在候选层状态防回退、以及启动后的语义状态与字节身份混用；均发生在 SQL 生成质量之外。

核心身份必须分开：`legacy`仅标记旧批次历史；当前 common ledger 的 calls/events 记录新发送凭据；candidate 的 `knownCalls`、`knownCostYuan`与 `holds`记录另一层资金承诺。旧 holds 的 UUID、requestId、callNumber、promptSha256、status 是独立身份，不能扁平化成一个数量就算保留。新旧承诺额公式同为 `knownCost + 0.10 × outstanding holds`，但两层 UNKNOWN 数量不同，数值不能互相代替，也不能相加当供应商账单。

设计权衡：只核旧基线容易实现，却缺少新活动的单调性；每次核原始文件 SHA 又把 JSON 无序对象的正常重写误判为漂移。合理边界是不可变初始种子与有证据的动态状态分开保护，同时核回退与正常结算，不能任选一边放弃另一边。

面试追问：

1. “两个预算层都有限额，为什么单层回退仍是漏洞？”——用 0.96312188 对 0.86312188 的下一笔保留额反例作答，指出两层 UNKNOWN 不同与 configured-price 仅是本地准入而非供应商硬封顶。
2. “为什么文件 SHA 通过不等于状态正确，SHA 变化也不一定是错误？”——不可变计划适合字节身份；JSON 动态账本要结合语义身份、单调状态、UNKNOWN 生命周期和结算证据。说明这次真实 JVM 就绪后只重排 holds 的证据。

当前不需要用户启动任何外部服务。修复须另一个免费固定版本后再审；工程接受也不能替代直接人类付费授权。应主动核对的三件事仍是数据经过哪两层准入、每次 usage/耗时/费用来自哪里、错误究竟落在控制器/账本还是 SQL 链路。

## 决策范围

本拒绝仅针对上述 SHA 的新注册工程准入；不改历史 revision-2 的接受结论、不恢复旧批次、不宣称模型准确率、不放开 unseen、不推进后续阶段。所有真实付费前提继续锁定。独立证据及检查工具由本目录 manifest 封存，决策绑定同一 plan SHA。
