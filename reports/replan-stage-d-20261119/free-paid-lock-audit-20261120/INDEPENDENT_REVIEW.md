# 免费付费锁独立复核（2026-11-20）

## 结论先行

父任务提交 `e1739186` 的当前拒绝路径是有效的：`run_next()` 先调用 `require_human_authorization()`；该函数先读取当前 scope decision，再读取 `human-paid-authorization.json`；`guarded_real_key()` 又在 `_old_real_key()` 前调用它。当前决定是 `DECLINED_NO_ADDITIONAL_PAID_EVALUATION`，因此在真实 Key 读取前抛出 `Refused`。独立复核的替身 Key reader 调用次数为 **0**，供应商调用为 **0**，实际批次关键文件哈希前后不变。

这不是“整个付费入口已经永久不可绕过”的结论。当前补丁把一个可修改 JSON 文件的两个字段当作重新开放凭据，没有校验该决定的 SHA、提案 SHA、来源 plan SHA 或独立重新开放身份。隔离探针把 scope 文件复制到临时目录并只改成 `APPROVED_NEW_SCOPE`/`true`，再把 Key reader 替换成哨兵；`guarded_real_key()` 触达哨兵 **1** 次，实际批次仍未变化。这暴露的是设计边界，不是对当前拒绝文件的改写。

## 审计边界与证据

本复核只读取当前 scope decision、revision-4 的 plan/state/candidate quota 和授权元数据，以及 controller 源码。没有启动 `run-next`，没有启动应用，没有打开供应商连接，没有读取真实 Key，没有打开旧 68 题答案 SQL 或 unseen。没有写 revision-4 的 state、ledger、quota、responses 或授权文件；新增文件只在本复核目录。

当前现场数字如下：

- scope decision SHA-256：`4e5b824568e7f2a87070b4390a24f6fa5f713c27afb17b68ee374adc8e509f87`；决定为 `DECLINED_NO_ADDITIONAL_PAID_EVALUATION`，`newPaidAuthorizationGranted=false`。
- revision-4 state 为 `requestsStarted=76`、`roundStatus=INTERRUPTED`、`terminal=INTERRUPTED_NO_REPLAY`。
- candidate quota 为 139 个已知调用、已知成本 `¥0.29376746`、7 个 UNKNOWN、单次预留 `¥0.10`。下一次预留后的配置占用为 `¥1.093767460`，超过冻结上限 `¥1.00`。
- 旧授权和 consumed seal 仍存在，这是历史证据；它们没有被删除或重写。revision-4 plan 绑定的旧 driver SHA 为 `bda005...d1d4e`，当前加入 scope lock 后的 driver SHA 为 `8df424...f70b7`，所以旧 plan 仍然绑定前锁源码，旧 plan 不能合法接受这次源变更。

`result.json` 是父任务的拒绝复核；`independent-run-result-2.json` 是同一检查的独立重跑；`residual-probe.json` 是上述临时 scope 文件探针；`independent-review.json` 汇总本结论与哈希。

## 真实拒绝路径的数据流

若用户请求后续付费轮，入口先进入 `run_next()`，再进入 `require_human_authorization()`。第一个门读取 scope decision；当前值不是 `APPROVED_NEW_SCOPE` 且布尔值为 false，于是直接拒绝，后续授权 JSON、consumed seal、`_old_real_key()`、配额 admit、模型 HTTP、Java 应用均不执行。即使只看当前实际状态，`INTERRUPTED_NO_REPLAY` 也不允许恢复，配额下一次 ¥0.10 预留也会超过 ¥1.00。

身份没有被扁平化：scope decision 记录用户范围；authorization 记录 direct-user、plan SHA 和历史 UNKNOWN 承诺；consumed seal 记录授权文件 SHA；state 记录 terminal/题数；candidate quota 记录 known 高水位和 UNKNOWN holds。当前 guard 只新增了第一层语义检查，没有把 scope decision 的身份绑定进 plan 或 authorization。

## 关键风险与 fail-closed 方案

当前风险不是“用户的 DECLINED 文件会误放行”，这个路径已经被挡住；风险是未来有人把同一路径的 JSON 改成批准字段，或直接调用公开的 `guarded_real_key()`，而 guard 没有验证该批准来自哪一份提案、哪一个 plan、哪一次独立复核。`run_next()` 内层旧 runner 的 plan 校验不能保护直接 helper 调用。

建议在下一份新 plan 中采用以下边界：

1. 把当前拒绝 decision 的 SHA 与 `proposalSha256`、`sourcePlanSha256` 固定写入新 guard 的 source/plan manifest；当前 revision-4 不回写，因为它是已封存实际批次。
2. 当前 revision-4 的 guard 只接受精确的拒绝决策并永久拒绝旧 plan；未来重新开放必须创建新 registration、新 plan、新 direct-user authorization 和新的独立工程接受，不能把 scope 文件改写后继续用 revision-4。
3. 对未来批准，要求批准文件同时绑定新 plan SHA、提案 SHA、批准消息哈希、时间和独立 decision SHA；缺任何一个字段立即拒绝。批准状态不能只靠两个可编辑字段。
4. 在真实 `application(..., offline=False)`、`http_json()` 和 real `SendGate.before()` 入口继续保留 scope capability 校验，或者把这些函数收为只能由 `run_next()` 调用的私有接口。这样直接 helper 调用也不能越过 scope 门。
5. 新增负向测试：临时修改 scope 文件的 decision、布尔值、proposal SHA、source plan SHA 各一次，替身 Key reader 都必须为 **0**；并核对实际 batch 的 state/quota/ledger 哈希无变化。

这里的数学防线是配额保留而非权重排序：7 个 UNKNOWN 每项按 `¥0.10` 保留，`¥0.29376746 + 7×¥0.10 = ¥0.99376746`；再预留一次就到 `¥1.09376746`，不能靠清零、重启或把 common/candidate 两层相加为“已付费”来放行。边界漏洞在 scope 身份校验层，不在这条配额算术上。

## 量化结果与面试答辩

本次免费审计得到：Key reader **0**、供应商调用 **0**、run-next 调用 **0**、应用启动 **0**、实际批次修改 **0**。当前拒绝路径的保护效果是“在 Key 前拒绝”；不能把它表述成准确率、P95 或供应商成本改善。

面试追问“为什么保留旧授权文件？”应回答：它是历史审计证据，删除会破坏不可变身份；新的用户 scope 决定必须作为更早的范围门。追问“为什么当前 guard 还不算永久安全？”应回答：两个可编辑字段不是不可伪造的批准身份；必须绑定新 plan、提案、独立决策和消息哈希，并把真实 HTTP/helper 入口也置于同一 capability 门下。

## 用户配合

当前无需 Key、Docker、应用启动或任何新批准。只有用户以后明确重新开放付费范围，才允许创建新的 plan、独立复核和授权；本次 `DECLINED` 不得被改写成旧 revision-4 的继续执行许可。
