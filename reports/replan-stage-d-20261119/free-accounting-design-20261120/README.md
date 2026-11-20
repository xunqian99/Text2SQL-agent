# Stage D 迟到账务采集：免费设计审查

**审查日期：** 2026-11-20  
**审查类型：** 本机、免费、只读设计审查  
**结论：** 当前代理不能直接进入生产或付费评测；必须先创建新 plan，完成独立工程验收，再由用户单独决定是否重新开放付费范围。

## 结论先行

当前实现把 DNS、TCP connect、TLS、请求写入、响应头读取和响应 body 读取绑定在同一个绝对 deadline 上。这个设计足以保护父请求的 15 秒答案预算，但不能直接改成“父 deadline + 105 秒”：那样会同时放宽连接建立和写请求，破坏“只在同一条已经发出的连接上继续读账务原体”的边界。

可行的最小修订是拆成两个独立时间契约：

```text
answerDeadline = admissionAt + 15s
upstreamDeadline = answerDeadline       # DNS/connect/TLS/每次 write 仍受此截止
postSendAccountingDeadline = min(
    answerDeadline + 105s,
    wholeRoundDeadline,
    officialOffPeakBoundary
)
```

只有完整请求已经写入同一条上游连接后，才允许切换到 `postSendAccountingDeadline` 的**只读收集**状态。迟到 body 可以用于共同账务证据，但必须标记 `late=true`、`accountingOnly=true`、`answerEligible=false`；Java 仍按原 15 秒预算得到原来的超时/504，迟到 SQL 不得回填 HTTP answer、评分、纠错、会话或补题。

这份审查没有修改冻结代理、真实账本或实际批次，没有读取 Key、旧 68 题答案 SQL 或 unseen 题，也没有供应商请求。新增文件只在本目录。

## 已完成的免费基线

运行 `scripts/test_replan_stage_d_proxy.py` 的当前代理本机测试，全部使用本地合成 HTTP stub：

| 指标 | 数值 |
|---|---:|
| 测试 | 37 |
| 通过 | 37 |
| 失败/错误 | 0 / 0 |
| 总耗时 | 6,506.743 ms |
| 本地上游请求 | 25 |
| gate 调用 | 27 |
| after 调用 | 33 |
| 供应商调用 | 0 |
| 外部请求 | 0 |

原始结果保存在 [`proxy-baseline.json`](proxy-baseline.json)。这些数字只证明当前本机代理的既有行为；它们不证明真实供应商会提供完整 usage，也不证明迟到账务已经可以进入生产。

## 请求如何流转

以一条 C1 问题“查询 2018 年的订单数”为例，当前链路是：

1. Java 控制器进入题目 admission，记录 `round/questionId/ordinal`，并把父 deadline 设为 `started + 15s`。
2. `SendGate.before` 校验模型、温度、输出上限、题目绑定和配额预留，持久化 permit 与 PENDING hold 后才允许代理发送。
3. 本地 proxy 读取 permit，建立一次上游连接并发送一次 POST。`_DeadlineSocket` 与 `_DeadlineReader` 都读取同一个 deadline，因此 DNS、connect、TLS、write、header、body 共享 15 秒截止。
4. 上游 body 到达后，proxy 生成 `responseBodyRaw`、长度、SHA-256 和严格 JSON 结果，再同步调用 `after_send`，最后才把 HTTP 结果回给 Java。
5. reregistered controller 复制当前题目的 fence，调用 `quota_monitor().finish_http(...)`；monitor 将 HTTP evidence、共同 quota 和 candidate quota 写入可恢复的账本。

这个链路保留了重要的身份分层：candidate 的 `requestId/callNumber/promptSha256/status` 在 quota holds 中，`pending/bindings/permitFences/unknowns` 在 monitor knowledge 中；共同层的已知费用和 candidate UNKNOWN 不能扁平化成一张“已付款”表。已有规则会永久保留 candidate UNKNOWN，即使共同层后来获得了 KNOWN usage，也不能释放 candidate 的 ¥0.10 hold。

下一版在第 3 步后增加状态转换：

```text
ADMITTED
  -> REQUEST_WIRE_IN_PROGRESS (answer/upstream deadline)
  -> REQUEST_WIRE_COMPLETE (记录 requestWireCompleteAt)
  -> ANSWER_WINDOW
  -> ANSWER_RETURNED 或 ANSWER_TIMED_OUT
  -> ACCOUNTING_READ_ONLY (同一已发连接，至多到 accounting deadline)
  -> ACCOUNTING_COMPLETE / ACCOUNTING_INCOMPLETE
```

`ANSWER_TIMED_OUT` 只影响 Java 的答复资格；它不能撤销已经发送的请求，也不能把迟到 body 当成新的答案。`ACCOUNTING_READ_ONLY` 禁止 DNS、重连、重发、provider usage 查询和任何会产生第二笔模型调用的动作。

## 当前缺口与影响

| 位置 | 已观察缺口 | 影响 | 必须的修订 |
|---|---|---|---|
| `scripts/replan_stage_d_proxy.py` | `_DeadlineSocket.sendall`、`_DeadlineReader.readinto` 共用 `deadline` | 直接加 105 秒会放宽 connect/TLS/write；不加则迟到 body 永远收不到 | 将 `upstreamDeadline` 与 `postSendAccountingDeadline` 分离，并只在 `requestWireComplete` 后切换读取状态 |
| proxy handler | `after_send` 在回包前同步执行 | after 或磁盘写入慢会侵占 Java 15 秒答案预算 | 回答路径与 accounting collector 解耦；collector 有并发上限和整轮截止，失败只影响账务，不回填答案 |
| proxy handler | 上游 body 没有单体 2 MiB、活动总量 32 MiB 和 collector ≤16 的硬上限 | 滴流/大 body 可以耗尽线程、内存或磁盘 | 在读取前后都计数；超限保留已收 prefix、置 `responseComplete=false`、usage UNKNOWN 并停止连接 |
| `run_replan_stage_d.py` | `gate.begin(item, started + 15)` 的 permit 只保存 `upstreamDeadlineMonotonic` | 没有可审计的答案截止、账务截止和整轮截止 | permit 固定保存两个/三个绝对 deadline、`roundDeadline`、`requestWireCompleteAt` |
| old controller `after` | 主要按 token 查找 call，未逐字段核对 permit 与 durable call | token 被复用或字段被篡改时，可能把结果绑定到错误调用 | 对 plan/round/ordinal/question/attempt/questionSha/requestBodySha/requestBytes/requestId/callNumber/promptSha 全字段比较 |
| `run_replan_stage_d.py::reconcile` | 用 `finishedAtUtc > returnedAt` 推断 late | 只能事后猜测，不能表达“答案截止后、账务截止前完成”或答案资格 | 由 transport 直接记录 `late/accountingOnly/answerEligible` 和两个完成时间；reconcile 只核对证据，不重新推断身份 |
| `run_replan_stage_d_reregistered.py` | HTTP evidence stem/questionId 未显式与 fence 全字段绑定；request budget 的 `requestId` 未显式绑定 permit | 下一题/同 token 数据可能被错误接收 | evidence 的 stem、`questionId`、budget.requestId 必须等于当前 fence/hold；不等则 fail-closed |
| `replan_quota_monitor.py::_match_pending` | 只按 `callNumber + promptSha256 + PENDING` 找 candidate hold | 同一题/同 attempt 的 requestId 变化可能被误认成同一调用 | 候选匹配必须同时核对 `requestId`、questionId、round、attempt、request body SHA 和 fence |
| `replan_quota_monitor.py::finish_http` | `response=None` 时跳过原 HTTP evidence/body/hash 校验 | HTTP failure 证据可缺失或错配，却仍可能完成账务状态转换 | 无论 response 是否存在，都要求 evidence path、stem、meta SHA、body SHA/bytes、complete 标记与 failure 原因一致 |
| monitor/after 失败路径 | `responseBodyRaw` 主要在 after 链路中传递；早期失败没有独立 raw 失败证据 | callback 或 monitor 失败时可能只剩内存片段 | 先 create-only spool 原体和失败停止证据，再调用 monitor；任何失败不得覆盖原件 |
| round/drain | round deadline 只在题目开始检查；`drain(120)` 没有明确绑定整轮绝对截止 | 迟到 collector 可能拖过整轮，形成隐性额外窗口 | collector close、drain、进程退出全部受同一个 `wholeRoundDeadline`，到点停止并留下 UNKNOWN |

源码审查时记录的 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `scripts/replan_stage_d_proxy.py` | `e3aa8b4c04d15f990a5a15a160e874d3a9edd4a74c45f90e1661172debf731f1` |
| `scripts/run_replan_stage_d.py` | `079ec048dc29348cc032e05b592dfbf2540f3b3480fe078c461a8a10e344e4b8` |
| `scripts/run_replan_stage_d_reregistered.py` | `8df42436b1caf81c719f24d88c1615b34d1697f29de83e247a662c50b9f70fb7` |
| `scripts/replan_quota_monitor.py` | `35b86ea00752c4e4a986480c8be266e87345c0617bd041214cbf17f53e7cb9db` |
| `docs/REPLAN_STAGE_D_ACCOUNTING_CONTINUATION_PROPOSAL_20261120.md` | `330d47016efc7fc072660a3c7537199c2130d9d1636aa34fc80acad5cd3fabc0` |

## 最小字段契约

### Permit/fence

新 permit 必须至少包含以下字段，且 permit、durable call、private fence、HTTP evidence 四处逐字段一致：

```json
{
  "planSha256": "...",
  "round": "B1",
  "ordinal": 0,
  "questionId": "...",
  "questionSha256": "...",
  "attempt": 1,
  "requestBodySha256": "...",
  "requestBytes": 12345,
  "requestId": "quota-hold-request-id",
  "callNumber": 1,
  "promptSha256": "...",
  "candidateHoldId": "...",
  "token": "one-use-capability",
  "answerDeadlineMonotonic": 0.0,
  "upstreamDeadlineMonotonic": 0.0,
  "postSendAccountingDeadlineMonotonic": 0.0,
  "wholeRoundDeadlineMonotonic": 0.0
}
```

其中 `token` 只是一次性 capability，不能成为唯一身份依据。AFTER 必须用 token 找到原 fence 后，再比较上面所有身份和时间字段；找不到、重复、跨题或字段被改写都必须停止。

### Transport/evidence

每个发送最多一条 POST、最多一次 BEFORE 和一次 AFTER。必须持久化：

```text
requestWireStartedAt
requestWireCompleteAt
answerDeadline
answerReturnedAt
accountingDeadline
responseHeaderAt
responseCompleteAt
late
accountingOnly
answerEligible
responseComplete
responseBytes
responseBodySha256
rawBodyFile
```

`responseComplete=true` 只能在同一条连接上读到完整、未超限、通过 framing 的 body 后设置。usage 只有在完整原体、严格 JSON 无重复键、五个非负整数和以下等式都成立时才是 KNOWN：

```text
prompt_tokens + completion_tokens = total_tokens
prompt_cache_hit_tokens + prompt_cache_miss_tokens = prompt_tokens
```

缺字段、重复 JSON key、截断、矛盾 usage、SHA/长度不一致或超限都只能得到 UNKNOWN；禁止补写 0。

### Collector/resource

建议的免费实现上限：

* collector 并发最多 16；
* 单条 upstream body 最多 2 MiB；
* 活动 body 总量最多 32 MiB；
* `postSendAccountingDeadline = min(answerDeadline + 105s, wholeRoundDeadline, offPeakBoundary)`；
* collector、drain、关闭和退出共享 `wholeRoundDeadline`，不把 120 秒 drain 当作额外预算；
* 只读 continuation 不得 DNS、重连、重发、请求余额或 provider usage。

这里的数学防挤压不是排序权重，而是三道硬边界：`min` 保证局部窗口不能越过整轮/空闲边界；单体和总量上限保证一个滴流响应不能挤占其他 collector；candidate UNKNOWN 永久占用 ¥0.10，common late KNOWN 只提升共同层高水位，不能替 candidate 退款。两层账务分别报告，不能相加伪装成供应商已知账单。

## 下一轮免费实现的测试矩阵

以下是必须在**新 plan、新独立输出目录**中执行的验收矩阵。当前 37/37 只是旧代理基线，不能替代这些新测试。

| 类别 | 场景 | 必须观察的结果 |
|---|---|---|
| Deadline | 15 秒内完整 200 | Java 收到原答案；`answerEligible=true`；一次 POST/一次 AFTER；usage 正常结算 |
| Deadline | DNS 阻塞 | 严格在 answer/upstream deadline 失败；0 次 POST；不进入 accounting continuation |
| Deadline | TCP connect 阻塞 | 同上；accounting +105 秒不能放宽 connect |
| Deadline | TLS 阻塞 | 同上；不能用 read-only 窗口掩盖握手超时 |
| Deadline | 请求 write 阻塞/部分写 | 父截止停止；不重连、不重发；usage UNKNOWN |
| Deadline | header/body 在父截止后、accounting 截止前完成 | Java 已 504；同一连接完整 raw body 可入共同账务；`late=true/accountingOnly=true/answerEligible=false`；SQL 不回填 |
| Deadline | accounting deadline 后才完成 | 保留已收 prefix；`responseComplete=false`；usage UNKNOWN；collector 在整轮截止前退出 |
| Deadline | round/off-peak 边界更早 | 使用三者最早值；不因单题窗口继续拖延整轮 |
| Transport | client disconnect | collector 仍最多一次 AFTER；已知 usage 可保留；不把断开当作 0 usage |
| Transport | chunked/trickle/incomplete | framing、长度和绝对时间都校验；不截短冒充完整 |
| Transport | duplicate JSON / contradictory usage | 保留原体；usage UNKNOWN；记录 breach；不重试 |
| Resource | 单体 body >2 MiB | 停止读取，保留 prefix，UNKNOWN |
| Resource | 活动 body >32 MiB | 新 collector/读取被拒绝；已有证据不被覆盖 |
| Resource | collector >16 并发 | 新发送前拒绝；不创建供应商连接 |
| Evidence | raw spool 失败 | create-only failure evidence；禁止继续结算或重写原件 |
| Evidence | after/monitor 早期异常 | Java answer 资格不被提升；raw body 和失败原因仍可审计 |
| Evidence | `response=None` | 仍校验 evidence path、stem、meta SHA、body SHA/bytes、complete/failure 语义 |
| Identity | token 复用/重复 AFTER | fail-closed；最多一次 POST/一次 callback |
| Identity | wrong plan/round/ordinal/question/attempt | fail-closed；不能借下一题 fence |
| Identity | question SHA/request body SHA/requestId/callNumber/prompt SHA 改写 | fail-closed；candidate hold 不释放 |
| Identity | HTTP evidence stem 与 fence 不同 | fail-closed；不得依赖 `finishedAtUtc > returnedAt` 事后猜测 |
| Identity | budget.requestId 与 permit/hold 不同 | fail-closed；共同层和 candidate 层都不结算 |
| Cross-question | 下一题开始后迟到 AFTER | 仍使用原题 `permitFences[token]`；下一题 admission 不被污染 |
| Quota | candidate UNKNOWN，common late KNOWN | common 只增加已知高水位；candidate UNKNOWN 永久保留 ¥0.10 |
| Quota | candidate/common 两层均 UNKNOWN | 两层分别保留 hold；不能相加、清零或重启恢复 |
| Lifecycle | 进程终止/重启/锚或 ledger 落盘失败 | 原件、锚、failure evidence 保留；无法证明完整性则停止 |
| Java integration | 冻结 C1 单题，父 15 秒后迟到 SQL | HTTP answer/session/score 不变；迟到 SQL 回填数必须为 **0** |
| Round integration | 同版组合回归 | 发送、账务、整轮截止和退出均通过；未完成证据仍 UNKNOWN |

每一批免费验收必须输出数字：通过/失败/错误数、供应商调用数与 Key 读取数（均应为 0）、每场景 POST/AFTER 最大次数、late SQL backfill（应为 0）、缺失 raw evidence（应为 0）、body 峰值、collector 峰值、AFTER p50/p95 延迟，以及仍为 UNKNOWN 的 hold 数。没有这些数字，不得把“设计可行”写成“工程接受”。

## 设计权衡与面试答辩

**为什么不把所有 deadline 直接加 105 秒？**  
因为连接和写请求也会被放宽，代理可能在 Java 已超时后才首次发出供应商请求；这会让“一题一次发送、父 15 秒预算”失效。拆分后，只有已完成 wire send 的连接有只读 continuation。

**为什么不重连或重新查询 provider usage？**  
重连无法证明取得的 body 属于原请求，查询余额也没有逐笔 request identity；两者会把账务证据从原 wire 事实变成猜测，并可能产生第二次收费。原连接读取失败就保留 prefix/UNKNOWN，不能用新请求补救。

**为什么 common KNOWN 不能释放 candidate UNKNOWN？**  
两层 hold 的身份和承诺不同。common 的迟到 body 只能证明共同层请求的 usage；它没有证明 candidate 的同一请求已经被供应商完整结算。保守保留 candidate ¥0.10 才能阻止已观察 UNKNOWN 被隐式退款或挤出预算。

**为什么 token 不能单独作为 fence？**  
token 只是一次性能力，可能被错误复用或跨题传递。plan、题号、attempt、request body SHA、requestId、prompt SHA 和 round/ordinal 才构成完整 provenance；任何一项不一致都应拒绝。

## 需要的下一步与用户配合

当前不需要 Key、Docker、供应商账号或付费批准。下一步只能是：

1. 在独立目录实现上述 deadline、collector、evidence 和 fence 修订；不修改已冻结实际批次。
2. 运行完整免费测试矩阵，生成全数字结果和源文件 SHA。
3. 由未参与实现的人做独立工程审查；失败时停在免费阶段。
4. 固定新 plan SHA、proposal SHA、代码 SHA 和独立判决后，再由用户单独批准是否重新开放金额/次数/付费调用。

任何预算、累计次数、模型、题面、Gold、评分、峰时规则或 UNKNOWN 处理的变化，都必须另建提案，不能借这份设计报告放行。现有冻结批次的旧答案、状态、账本和授权文件继续保持原样。

