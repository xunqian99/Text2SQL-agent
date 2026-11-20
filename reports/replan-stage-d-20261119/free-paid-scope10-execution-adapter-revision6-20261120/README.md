# Revision-6 ¥10 execution adapter：免费安全空跑

这个目录是 revision-6 / paid-scope-10 登记的独立适配器。它只读取已提交的 revision-6 元数据和 revision-4 历史原件，提供 `prepare`、`preflight`、`dry-run`、`execute` 四个入口。旧 driver、旧账本、旧拒绝证据和生产 quota 都没有修改。

本工作包没有迁移真实 quota，也没有读取 Key、连接 provider 或导入生产 runner。`execute` 即使在北京时间空闲时段也会因为缺少真实 quota 迁移证明和执行开关而 fail closed；这不是付费评测通过证据。

## 交付物与 SHA

| 文件 | 作用 | SHA-256 |
|---|---|---|
| [`execution_adapter.py`](execution_adapter.py) | 独立 scope 复核、时间/配额门禁、离线 dry-run 和拒绝式 execute | `6ca87a80315ecf931f5fb2c4d00097b51ffbffa2dc6f8b60d3e0248d87fc27c1` |
| [`test_execution_adapter.py`](test_execution_adapter.py) | 21 个本地测试，覆盖篡改拒绝、峰时边界、配额、调用和账务标记 | `52ffea09314f4254d70a8222249301d8a9b27ed2c3dd739fa1c693bc96ae1ec3` |
| [`adapter-manifest.json`](adapter-manifest.json) | 适配器身份与执行关闭声明 | `83cbb5e6eb6b28269c2be57c9a5ed1291c3f4b2a344a0eb60be7287ce2415281` |
| [`prepare-result.json`](prepare-result.json) | prepare 数字结果 | `e9bdd99841c0b1e45bf4732d7bd8011a9cab3b5693f3c15c4375e53fe8ef05b0` |
| [`preflight.json`](preflight.json) | 免费门禁结果 | `ddaafab89b611171b674a8870468ec710a0c376bdf057c0a14f56787c6814b17` |
| [`dry-run.json`](dry-run.json) | 一题两次最大调用的离线计划与账务标记 | `fde4320513ae0fb6989db4ae2c0a203f81a6494853552443277af8a0d4ee1989` |
| [`execute-result.json`](execute-result.json) | 默认 execute 的拒绝证据 | `d65e82e9eba9311bd80cca4acf9c74c250934c756ef261d809f5bafd00469289` |

适配器绑定的 revision-6 plan SHA 是 `a0f9e86a32b78addf68204be76115953cf082b61791ec74a4339ac65fbab3595`，用户授权 SHA 是 `bcf686fefa4468633a87de1d3b4bbcefa87c04a10889f8643149ed97733bcdd0`，binding SHA 是 `2bf2ed7d8a1cf7e79fb6960fab5bd8fe2413cc2dc7701632966a0a3e5db5325b`，revision-4 ledger SHA 是 `a5097cec99f0a8c9b2f2b9596a46a682dde6a0dd8bbf4ad6bdad4af602d3ca23`。

## 四个入口

1. `python execution_adapter.py prepare` 读取并锁定 scope、授权 binding、revision-4 state/ledger/quota 和 staged quota，写入 manifest。manifest 明确 `executionEnabled=false`、需要真实迁移证明、provider calls=0、Key reads=0。
2. `python execution_adapter.py preflight` 重新检查旧 76 次中断身份、旧 ledger 76 条 call、139 个已知调用、7 个 candidate UNKNOWN、staged ¥10/¥0.10 和下一次 ¥0.10 reserve。它用固定北京时间样例拒绝 10:00 和跨入 09:00 的 08:59，允许 12:00 后与周末。
3. `python execution_adapter.py dry-run` 只生成一题的离线发送计划：每次尝试最多一个 POST，同一题最多两次模型调用，实际 provider calls=0。它同时生成迟到响应的 accounting-only 标记。
4. `python execution_adapter.py execute` 再次加载全部 scope 锚点，验证授权、plan SHA、旧账本继承、off-peak、reserve、7 个 UNKNOWN hold 身份和真实迁移证明。当前缺少 `real-quota-migration-proof.json`，并且 manifest 执行开关关闭，因此默认返回 `REAL_QUOTA_MIGRATION_PROOF_MISSING`，不会读 Key 或发请求。

## 一次请求如何流转

以 dry-run 中的 `dry-q-001` 为例：输入只是一道待评估问题的身份，不含真实 SQL、Key 或 provider 地址。`verify_scope()` 先用固定 SHA 读取 revision-6 plan/auth/binding/base/staged quota，再用 revision-4 的 state、ledger、candidate quota 复核 `76 / INTERRUPTED_NO_REPLAY`、76 条历史 call、139 个 known call 和 7 个 UNKNOWN。配额计算为 `0.293767460 + 7×0.10 = ¥0.993767460`，新 reserve 后为 `¥1.093767460`，仍低于 staged ¥10；旧 hold 只读保留，释放数为 0。

dry-run 生成两条尝试，每条 `postCount=1`，总调用数 2，达到每题上限后停止。响应被设为晚于 15 秒 answer deadline、早于 15+105 秒 accounting deadline，因此输出 `late=true`、`accountingOnly=true`、`answerEligible=false`、`lateSqlBackfill=false`、`callerStatus=504`；这个响应只证明账务 continuation 标记，没有产生 SQL 或 provider 请求。

execute 又做同样的身份检查，并检查北京时间发送 guard。空闲时段也必须有真实 quota 迁移证明，证明中要绑定旧 quota SHA、staged quota SHA、budget/pricing identity、¥10 上限和 7 个 UNKNOWN hold digest。当前证明缺失，入口在任何 Key/provider 动作前拒绝。

## 数字验收

执行 `python -m unittest -q test_execution_adapter.py`：**21/21 通过，0 失败，0 错误；PowerShell wall time 570.602 ms**（unittest 内部 211 ms）。新增错误迁移证明拒绝探针，证明字段任一不匹配也不会进入执行。

| 指标 | 数值 |
|---|---:|
| provider calls | 0 |
| Key reads | 0 |
| external requests | 0 |
| old ledger modified | false |
| old evidence modified | false |
| old candidate UNKNOWN | 7，释放 0 |
| current commitment | ¥0.993767460 |
| next ¥0.10 reserve commitment | ¥1.093767460 |
| staged limit / reservation | ¥10.00 / ¥0.10 |
| max model calls per question | 2 |
| POST per attempt | 1 |
| same sent connection accounting continuation | 105 s |
| default execute | refused: `REAL_QUOTA_MIGRATION_PROOF_MISSING` |

这些数字证明的是免费适配器的身份追踪、配额预留和拒绝边界，不证明 Java wire、真实 quota 迁移、供应商 usage、业务准确率或 P95。`paid_lock_audit` 独立审查通过前禁止真实调用。

## 设计权衡与边界

把适配器放在新目录，代价是它不会自动接入生产 runner；收益是旧 driver、旧账本和旧拒绝证据保持不可变，任何 scope 漂移都会在入口前被 SHA 拒绝。staged quota 只有 `limitYuan` 从 1 改为 10，7 个 UNKNOWN hold 的身份 digest、budgetId、pricingIdentity 和 reservation 不变；因此 ¥10 不能被误解成释放历史未知费用。

没有让 `execute` 在缺少迁移证明时“先模拟成功”，因为那会把配置副本冒充生产账务。dry-run 专门承载单次 POST、两次调用上限和 105 秒 accounting flags；execute 只承载最终门禁，避免测试输出伪造付费结果。

面试追问通常集中在两点：为什么 105 秒只能读同一条已完整发送的连接？回答是连接建立、DNS、TLS、写入仍受 15 秒 answer deadline，延长窗口只收账务证据，迟到 SQL 永远不能回填答案；为什么旧 UNKNOWN 不随 ¥10 迁移释放？回答是 UNKNOWN 是已发生但未结算的身份承诺，必须保留 hold，只有绑定原始 quota 和真实 settle 证据的后续审计才能改变状态。

本轮不需要用户提供 Key、启动 Docker 或运行服务。下一步必须先完成 wire/monitor/resource/window 的免费验收和 `paid_lock_audit` 独立审查，再由人工提供真实 quota 迁移证明；在此之前 execute 会持续拒绝。
