# Stage D 追加式 reopen authorization/plan：免费登记与审计

**日期：** 2026-11-20  
**范围：** 只做 `prepare`、`preflight`、`audit`；不读 Key、不发供应商请求、不运行 `run-next`。  
**结论：** 新授权已经被结构化登记，但原 ¥1 配额和旧 UNKNOWN 承诺仍然生效；下一次 ¥0.10 预留会越过原上限，所以当前合法发送结果是拒绝。旧 revision-4 的永久拒绝锁保持不动。

## 已交付的文件与 SHA

本目录是独立追加登记，不是旧 `batch-1/reregistered-dev-1/revision-4` 的替换目录。

| 文件 | 作用 | SHA-256 |
|---|---|---|
| [`reopen_plan.py`](reopen_plan.py) | 只读基线、生成新授权/plan、运行免费 preflight/audit | `b7c04aed8dc94e2ac6b5b1a7e18a72604ed69a6bcebf340213eace599902d02b` |
| [`test_reopen_plan.py`](test_reopen_plan.py) | 9 个本地单元测试 | `41319246f000c0eec70b9ba85ddb1e24d6de2423342d2edcae317cd706223f71` |
| [`reopen-authorization.json`](reopen-authorization.json) | 用户新范围的结构化授权 | `137c067f4ee166a2d28846a6d066dcb2fa0f6ef53060ad3c6994e3336f2f06eb` |
| [`base-snapshot.json`](base-snapshot.json) | 旧关闭登记的只读哈希与计数快照 | `8990237b80f49b468ee95bdcc8e99167a0a25f8a5325e169fe6f7fbb8eadb383` |
| [`plan.json`](plan.json) | 新 `revision-5-reopen` 追加式 plan | `ff0f9c1d271972b4d8ab594760dbe4d8d957f2b4cf15c3c4f3136c4267f5a8c1` |
| [`authorization-binding.json`](authorization-binding.json) | 将新授权 SHA、plan SHA 和旧 plan SHA 绑定 | `c278df2811ce499d35d60a4d3f58185f665385a713d6409d70efcba4866da158` |
| [`prepare-result.json`](prepare-result.json) | prepare 数字结果 | `350336e153251a30f18e401e8a060da9b9e4acdc338c427819746cb4b57f12e18` |
| [`preflight.json`](preflight.json) | 免费 preflight 与峰时边界结果 | `b29664907e618ee6b057c20458519376da70200958a5b7525e55bf1185293090` |
| [`audit.json`](audit.json) | 独立只读审计结果 | `534930660c7d9fa8272d32fd70a70d1a3d229251ef64219e9d76543dd92e333f` |

旧 revision-4 的基线身份是：plan `da6c10f1365f53345a37d238efafd1c00afadb506bab75d102b36f050d601016`，旧 scope decision `4e5b824568e7f2a87070b4390a24f6fa5f713c27afb17b68ee374adc8e509f87`，旧 proposal `0c72656803e63dce968d8d94edf3eb5ed191274bdfc304c6004fee2018422c0c`。新 plan 通过 `authorization-binding.json` 引用这些身份，未覆盖旧文件。

## 这次授权到底允许什么

登记的授权文本来自父任务转达的用户范围声明，其 SHA 为 `9e877529917ce2d909eba2adad190c53acf78252ee2c4c949027e69b5c3c4966`。授权含义是“未来可以在冻结边界内重新进行付费评测”，同时明确禁止提额、重置账本、改旧拒绝证据、绕过北京时间峰时和在本任务中运行 `run-next`。

因此 `reopen-authorization.json` 将 `futureScopeApproved` 设为 `true`，但把 `executionEnabledInThisTask`、`keyReadAllowedInThisTask`、`providerCallsAllowedInThisTask` 和 `runNextInvoked` 都固定为 `false`。新 `plan.json` 的 `executionState` 是 `PREPARE_PREFLIGHT_AUDIT_ONLY`，还要求未来必须由独立最终审查和新的执行适配器接管。旧 driver 本身继续永久拒绝 revision-4，不能通过把旧 scope JSON 改成批准来绕过它。

## 具体数据流

以未来想发送一道 C1 题为例，本次登记的数据流只走到发送前：

1. `verify_base()` 读取旧 revision-4 的 `plan/state/send-ledger/candidate-cost-quota`，以及旧 decision/proposal 和源码哈希。它只读计数、状态、额度身份和原件 SHA，不读取旧答案 SQL、不加载 JAR、不连接数据库。
2. `prepare` 把用户授权文本写成 `reopen-authorization.json`，把旧 registration 的 SHA、当前永久拒绝 driver SHA、原 gates 和原 limits 写进新 `plan.json`，再用 `authorization-binding.json` 绑定 `authorizationSha256 + planSha256 + basePlanSha256`。
3. `preflight` 复核绑定和旧原件哈希，按当前 controller 的时间规则测试北京时间工作日 09:00—12:00、14:00—18:00 及 guard overlap。峰时和峰前边界都拒绝；周末和 12:00 后的固定样例允许通过时间门，但仍不能越过配额门。
4. 配额门读取旧 candidate quota 的 139 个已知调用、`¥0.293767460` 已知成本和 7 个永久 UNKNOWN hold。现有占用为 `0.293767460 + 7 × 0.10 = 0.993767460`，下一次预留后为 `1.093767460`，所以 preflight 在发送前拒绝。
5. `audit` 再次比较旧 state、ledger、candidate quota、declined decision 和源码 SHA，确认旧账本没有重置、新 UNKNOWN 没有释放、没有 Key reader、没有 provider call、没有 `run-next`。

数据身份没有被扁平化：旧 plan/decision/proposal 是关闭登记身份；新用户授权由 `authorizationId + statementSha256` 标识；新 plan 由自身 SHA 标识；candidate UNKNOWN 由旧 quota 的 `unknownHoldIdentitySha256` 追踪。共同层的迟到 usage 若未来被观测，也没有权限替 candidate UNKNOWN 退款。

## 绑定的原限额、计数与门槛

`plan.json` 对旧 plan 的 `limits` 做逐字段相等绑定：

| 项目 | 固定值 |
|---|---:|
| 配置累计限额 | ¥1.00 |
| 单次预留 | ¥0.10 |
| 当前 plan 请求上限 | 902 |
| 当前 plan 模型调用上限 | 1,870 |
| 累计请求上限 | 970 |
| 累计应用调用上限 | 1,940 |
| 原已计入请求/应用调用 | 68 / 70 |
| 当前现场已开始请求/ledger calls | 76 / 76 |
| 已见累计请求/应用调用 | 144 / 146 |
| 选定新路线最大调用 | 1,804 |
| 每题模型调用 | 最多 2 |
| Java 请求预算 | 15,000 ms |
| SDK 发送次数 | 1 |
| 整轮 guard | 1,740 s |
| 发送 guard | 120 s |
| 输出上限 | 8,192 tokens |
| payload 上限 | 65,536 bytes |

原业务路线也被固定：C1 only、C2 不准入；round 顺序、正式六轮、unseen locked、原 scorer、candidate UNKNOWN 永久保留、common/candidate 两层不能相加。当前剩余槽位只是算术结果，不能覆盖配额拒绝：当前 plan request slots 为 `902 - 76 = 826`，current plan call slots 为 `1,870 - 76 = 1,794`，但第一道配额预留已经不能通过。

## 北京时间峰时防线

原 `window()` 规则被原样登记为：时区 `Asia/Shanghai`，工作日峰时 `09:00-12:00` 与 `14:00-18:00`；发送 guard 只要与峰时有重叠就拒绝。免费 preflight 的固定结果如下：

| 固定时刻 | 结果 |
|---|---|
| 周五 10:00 北京时间 | `PEAK_HOURS`，拒绝 |
| 周五 08:59 北京时间，guard 120 s 跨入 09:00 | `PEAK_BOUNDARY_RESERVE`，拒绝 |
| 周五 12:00 北京时间 | `OFF_PEAK`，时间门允许 |
| 周六 10:00 北京时间 | `OFF_PEAK`，时间门允许 |

“时间门允许”不等于“可以发送”：当前 ¥1 配额已经使下一次发送在 quota admission 阶段拒绝；未来每一题仍必须同时满足时间、账本、题目、独立审查和其他原 gate。

## 验证数字

执行 `python -m unittest -q test_reopen_plan.py`：**9/9 通过，0 失败，0 错误；PowerShell wall time 354.446 ms**（unittest 内部耗时 42 ms）。

`preflight.json` 的 **8/8 checks** 全部为 true，`audit.json` 的 **15/15 checks** 全部为 true。共同数字为：

| 指标 | 数值 |
|---|---:|
| 新目录写入 | 1 个独立目录 |
| 供应商调用 | 0 |
| Key 读取 | 0 |
| `run-next` 调用 | 0 |
| 实际批次修改 | false |
| quota migration | false |
| 旧 UNKNOWN 释放 | 0 |
| 旧 state/ledger/quota/decision 改写 | false |
| 原永久拒绝锁 | 已验证存在 |
| 下一次预留 | `¥1.093767460`，超过 ¥1.00 |

这些数字只证明免费登记的身份和拒绝边界，不能证明业务准确率、P95、供应商 usage 或付费评测完成。

## 设计权衡与面试追问

**为什么新建追加 registration，而不是改旧 decision 或 revision-4 plan？**  
旧 revision-4 的 `require_human_authorization()` 已把 decline decision、proposal SHA 和 source plan SHA 固定到代码中，并且末尾永久抛拒绝。改旧 JSON 会破坏不可变审计身份；追加 plan 可以证明用户新授权属于哪次未来范围，同时保留旧批次的停止事实。

**为什么新授权已经批准未来范围，当前仍然拒绝发送？**  
授权是范围身份，quota admission 是每次发送的资源门。7 个 UNKNOWN hold 仍是 candidate 的真实保守承诺；`¥0.293767460 + ¥0.70` 已接近 ¥1，不能因用户重新授权就清零或迁移预算。要发送必须先通过原 quota，否则直接拒绝。

**为什么 plan 需要绑定旧 driver 的永久拒绝 SHA 和当前 driver SHA？**  
旧 plan 当时绑定的 driver SHA 是 `bda005...`，当前源码已包含永久拒绝锁，SHA 为 `132627...`。同时记录两者可以证明旧 plan 没被悄悄重写、当前锁也没有被绕过；未来若需要执行，必须另做适配器和独立 review，不能直接调用旧 `run-next`。

## 后续需要用户配合的事项

本轮无需用户再提供 Key、启动 Docker 或确认数据库口径；也没有执行任何 provider 请求。若未来要真正发送，必须先让新的执行适配器完成免费真实 Java/wire/monitor/resource/window 验收，绑定最终 source/plan SHA，并由独立审查确认。届时仍必须使用原 ¥1 限额和原 UNKNOWN，发送前检查北京时间空闲窗口；若配额仍无法通过，系统必须继续拒绝。

