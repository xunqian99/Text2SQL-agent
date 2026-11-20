# Stage D revision-6 / paid-scope-10：免费追加登记

**结论：** 用户已明确批准此前 ¥10 完整范围。本目录完成了追加式 `revision-6-paid-scope-10` 的 `prepare/preflight/audit`，但本轮仍未读 Key、未发 provider 请求、未运行 `run-next`。旧 revision-4、旧拒绝证据、旧 ¥1 candidate quota 和上一份 ¥1 reopen 登记全部只读引用。

新登记只把 candidate quota 的 `limitYuan` 从 `1.00` 复制为新目录中的 `10.00` staged 文件；`budgetId`、`pricingIdentity`、`reservationYuan`、`knownCalls=139`、`knownCostYuan=0.293767460` 和 7 个 UNKNOWN hold 全部保持原字节语义。生产旧 quota 没有迁移，`quotaMigrationPerformed=false`。

## 交付物与 SHA

| 文件 | 作用 | SHA-256 |
|---|---|---|
| [`paid_scope10_plan.py`](paid_scope10_plan.py) | 只读锚点、生成新授权/plan、执行免费 preflight/audit | `b2befe7fd0e718c9715116669e71a6cdca336ef2950ec667153065afa4cb1d98` |
| [`test_paid_scope10_plan.py`](test_paid_scope10_plan.py) | 19 个本地测试（含 4 个旧原件字节篡改拒绝、4 个 binding 字段篡改拒绝） | `fd0f90122e4dd0f4cf401191b86f729e375ede5667c35e436daaebdd4d0aea6b` |
| [`paid-scope10-authorization.json`](paid-scope10-authorization.json) | 用户 ¥10 范围授权 | `bcf686fefa4468633a87de1d3b4bbcefa87c04a10889f8643149ed97733bcdd0` |
| [`base-snapshot.json`](base-snapshot.json) | 旧原件/旧 reopen/source SHA 快照 | `cb6aece13b01e8098c8f4491ea34d18cf594b2d4827d4cb170827487f212d27c` |
| [`staged-candidate-quota.json`](staged-candidate-quota.json) | 新目录中的 ¥10 staged quota 副本 | `57931f5a2a6df10e654ff3322791e8eebc7675dbb10abf1c6f7305a019de41bf` |
| [`plan.json`](plan.json) | revision-6 paid-scope-10 plan | `a0f9e86a32b78addf68204be76115953cf082b61791ec74a4339ac65fbab3595` |
| [`authorization-binding.json`](authorization-binding.json) | 绑定用户授权、staged quota、plan 和旧 reopen | `2bf2ed7d8a1cf7e79fb6960fab5bd8fe2413cc2dc7701632966a0a3e5db5325b` |
| [`prepare-result.json`](prepare-result.json) | prepare 数字结果 | `b28e8400edbb51987c814f143bf9b1f75c6c6b3ebdd0795af44ca241a2efebcb` |
| [`preflight.json`](preflight.json) | 免费范围/峰时/配额预检查 | `5ba76d2bd8bd15fac2744e01a549f29cfa62b3766ccd60a1af9bc5658d4c76b` |
| [`audit.json`](audit.json) | 追加登记独立审计 | `3b52f510bc63796b13926747552fe3a9cfbf50b1d1a93c6ded261d16beb6e0ac` |

旧闭合 registration 的 plan SHA 是 `da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe`；上一份 ¥1 reopen plan SHA 是 `ff0f9c1d271972b4d8ab594760dbe4d8d957f2b4cf15c3c4f3136c4267f5a8c1`。新 plan 通过 binding 同时引用两者，避免把旧的“冻结 caps”授权字段误当成当前 ¥10 授权。

## 授权范围

新授权的决定为 `APPROVED_PAID_SCOPE10_WITH_FROZEN_ORIGINAL_GATES`。边界固定如下：

| 项目 | 新范围 |
|---|---:|
| 配置累计上限（含旧账） | ¥10.00 |
| 每次预留 | ¥0.10 |
| 最后一次完整 DEV100 | 100 题，最多 200 次模型调用 |
| 新路线最大请求 | 902 问 |
| 新路线最大应用调用 | 1,804 次 |
| 累计请求上限 | 1,046 问 |
| 累计应用调用上限 | 1,950 次 |
| 已继承请求/应用调用 | 144 / 146 |
| 已继承 common permit/transport attempt | 145 / 144 |
| 已知 candidate 调用 | 139 |
| candidate UNKNOWN | 7，永久保留 |
| 父请求预算 | 15,000 ms |
| 每题最多模型调用 | 2 |
| SDK 发送次数 | 1 |
| 整轮 guard | 1,740 s |
| 发送 guard | 120 s |

“最后一次 DEV100”是唯一新增开发轮。开发失败、未知用量门槛失败或中断就停止；不增加第二个开发轮、不转 C2、不做付费单题探针。开发通过后才按原顺序进入目标题和正式六轮；正式全部通过后才允许未见两侧各 100 题。阶段 3 的模型/路由/思考专项仍不在本授权内。

## 请求如何流转

以未来一题的发送为例，数据只在本次登记中走到发送前：

1. `verify_anchors()` 读取旧 revision-4 的 plan/state/ledger/candidate quota、旧 decision/proposal、上一份 ¥1 reopen plan/auth/audit 和当前源码 SHA。它不读取旧 SQL、Gold、JAR、数据库或 Key。
2. `prepare` 写入用户授权、旧原件快照和一份 candidate quota staged copy。staged copy 只有 `limitYuan` 改为 10.0；所有 hold 身份和 pricing/budget 身份原样保留。
3. `plan.json` 写入新累计数字、最后一次 DEV100 200 调用边界、原候选/正式门槛、账务 continuation 参数和北京时间峰时规则。`authorization-binding.json` 绑定 `authorizationSha256 + stagedQuotaSha256 + planSha256 + priorReopenPlanSha256`；`verify_output` 还逐项锁死 `executionEnabled=false`、`appendOnly=true`、`baseSnapshotSha256` 和 `priorReopenPlanSha256`。
4. `preflight` 复核 scope 字段逐项一致、staged quota 只有一处差异，并用固定北京时间样例检查峰时与 guard overlap。它不创建真实 permit。
5. `audit` 再次对旧原件哈希、旧 76 中断身份、7 个 UNKNOWN、旧拒绝决策和永久拒绝 driver 做只读校验。

身份分层保持清晰：旧 revision-4 是 `INTERRUPTED_NO_REPLAY` 的历史事实；上一份 ¥1 reopen 是被新授权 supersede 的范围登记；本次 authorization 用 `authorizationId + statementSha256` 标识；新 staged quota 用原 `budgetId/pricingIdentity/hold` 身份，唯一变更是配置上限。common 的 6 个 UNKNOWN 与 candidate 的 7 个 UNKNOWN 仍分层报告，不能互相退款，也不能合并成“已知账单”。

## 原门槛与账务 continuation

新 plan 绑定原 C1-only 路线、round 顺序、model `deepseek-flash`、temperature 0、输出 8192、无工具/思考漂移、每题最多 2 次、SDK 一次发送、Java 15 秒、整轮 1,740 秒、发送 guard 120 秒、payload 65,536 bytes，以及所有原 scorer、Gold、unseen 和安全门槛。

账务 continuation 的边界也固定：DNS/connect/TLS/每次写仍受父 15 秒；只有同一已完整发出的连接可以额外只读 105 秒；读取截止取 `min(parent+105s, wholeRoundDeadline, offPeakBoundary)`。collector 最多 16 个，单 body 最多 2 MiB，活动 body 总量最多 32 MiB；不得重连、重发、查询 provider usage。迟到 body 可做共同账务证据，但 `late=true/accountingOnly=true/answerEligible=false`，不能回填 SQL、HTTP answer、评分、纠错或会话。

完整账务仍是硬门槛：响应原体完整、无重复 JSON key、usage 五整数非负且满足 token 等式，才是 KNOWN；限时后仍 UNKNOWN 就不能宣称正式费用完整。candidate 已观察 UNKNOWN 永久保留 ¥0.10，即使共同层之后拿到迟到 KNOWN，也不能释放 candidate hold。

## 北京时间空闲时段

登记的时间规则是 `Asia/Shanghai` 工作日 09:00—12:00、14:00—18:00 拒绝；发送 guard 只要跨入峰段也拒绝。免费 preflight 固定验证：

| 样例 | 结果 |
|---|---|
| 周五 10:00 北京时间 | `PEAK_HOURS`，拒绝 |
| 周五 08:59 北京时间，guard 120 s 跨入 09:00 | `PEAK_BOUNDARY_RESERVE`，拒绝 |
| 周五 12:00 北京时间 | `OFF_PEAK`，时间门通过 |
| 周六 10:00 北京时间 | `OFF_PEAK`，时间门通过 |

时间门通过不代表本轮已执行；本次 `executionState=PREPARE_PREFLIGHT_AUDIT_ONLY`，配额副本和授权都未加载进生产 runner。

## 数字验收

执行 `python -m unittest -q test_paid_scope10_plan.py`：**19/19 通过，0 失败，0 错误；PowerShell wall time 564.969 ms**（unittest 内部 203.213 ms）。新增 4 个 binding 字段篡改测试，执行标志、append-only 标志、base snapshot SHA 和上一份 reopen SHA 任一被改写都会在读取 Key/授权执行前拒绝。

`preflight.json` 的 **13/13 checks** 为 true，`audit.json` 的 **19/19 checks** 为 true。关键数字：

| 指标 | 数值 |
|---|---:|
| provider calls | 0 |
| Key reads | 0 |
| run-next invoked | false |
| actual batch modified | false |
| quota migration performed | false |
| old UNKNOWN released | 0 |
| old revision-4 requests/ledger calls | 76 / 76 |
| old candidate known calls / UNKNOWN | 139 / 7 |
| staged limit/reservation | ¥10.00 / ¥0.10 |
| current candidate commitment | ¥0.993767460 |
| next reservation commitment | ¥1.093767460 |
| next reservation under staged ¥10 | true |
| last DEV100 max calls | 200 |
| new route max calls | 1,804 |
| cumulative max calls | 1,950 |

这些数字证明范围登记、身份绑定和免费拒绝边界一致，不证明业务准确率、P95、真实供应商 usage 或付费评测已经完成。

## 设计权衡与面试追问

**为什么 staged quota 可以写 ¥10，却不能直接改旧 quota？**
旧 quota 是 revision-4 的不可变证据，里面的 7 个 UNKNOWN 和 budget/pricing 身份必须保持原状。新目录 staged copy 只改变 `limitYuan`，由程序逐字段比较其余内容；生产加载、旧 ledger 写入和真实迁移留到免费 Java/monitor/独立审查之后。

**为什么 previous ¥1 authorization 仍保留 `limitIncreaseAllowed=false`？**
它是历史范围身份，不能被回写成 ¥10。当前用户新授权以新的 `authorizationId`、statement SHA 和 binding 覆盖未来范围；旧文件保持原字节，审计能区分“旧决定没有被篡改”和“用户后来新开了精确 ¥10 范围”。

**为什么累计上限 1,046/1,950 与旧 plan 的 970/1,940 不同？**
这是本次明确批准的范围变化：旧 144 问/146 调用作为历史起点，新增路线最多 902 问/1,804 调用，合计 1,046/1,950。它不重置旧计数，也不把历史 UNKNOWN 当 0；每次发送仍受 ¥10 预留和所有原门槛约束。

## 用户配合

本轮不需要用户提供 Key、启动 Docker 或运行服务。要真正开始未来付费评测，仍需完成新 continuation 的真实 Java/wire/monitor/resource/window 免费验收和独立工程接受，再将最终 source/plan SHA 与本授权绑定；在此之前本目录的 staged quota 不会被生产 runner 读取。
