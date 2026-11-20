# Revision-6c ¥10 quota activation：append-only 免费激活包

这个目录完成的是 quota activation 的免费工作包。程序从 revision-4 的 `candidate-cost-quota.json` 读取原始字节，在新目录生成只改变 `limitYuan` 的 ¥10 staged runtime、程序生成的 migration proof 和 `executionEnabled=true` 的新 manifest。旧 state、旧 ledger、旧 quota、旧拒绝证据、旧 driver 都只读引用，整个流程不读取 Key、不发 provider 请求。`revision6c` 是在旧激活包之外追加的修复目录，旧目录保持原始字节不变。

`executionEnabled=true` 只代表新目录的 staged runtime 已完成身份绑定，不能代表生产 runner 已加载，也不能授权真实请求。manifest 明确记录 `providerCalls=0`、`keyReads=0`、`productionRunnerModified=false`；`paid_lock_audit` 独立审查通过前不得启动真实评测。

## 交付物与 SHA

| 文件 | 作用 | SHA-256 |
|---|---|---|
| [`activation_workflow.py`](activation_workflow.py) | 读取旧 quota、生成 target/proof/manifest、执行 append-only 门禁 | `406dd9d53578ec0fdf8439bd4444249c765d3f9a98afbb1bc53b573f6d9ef240` |
| [`test_activation_workflow.py`](test_activation_workflow.py) | 30 个测试，覆盖旧文件/授权/证明篡改、同步时间篡改、off-peak 语义、重复激活和峰时 guard | `a783bb5bc46d4a6dff736ddfde514b81e5eae9103881d340110594acb0d4ce0b` |
| [`dry-run.json`](dry-run.json) | 内存计算结果与迁移 transaction identity | `82799cca326bd628c4a2c6cf906d9a397061caa6fbec4fab5ef0e32b67a59af0` |
| [`activate-preflight.json`](activate-preflight.json) | 峰时、reserve、旧账本和空目录门禁 | `d31ca9b2d74cc32d26d7fa86dc17976e57cab3bad3e70ee9848886b4a7d2d40` |
| [`staged-runtime-quota.json`](staged-runtime-quota.json) | 新目录中的 ¥10 runtime quota | `57931f5a2a6df10e654ff3322791e8eebc7675dbb10abf1c6f7305a019de41bf` |
| [`migration-proof.json`](migration-proof.json) | 程序生成的 source/target/scope/hold/transaction 证明 | `2f8ac38e6574c8204085ab47a3f6a7c8e3bcad3ebb68d5cde816741891193fee` |
| [`execution-manifest.json`](execution-manifest.json) | 新目录激活 manifest，`executionEnabled=true` | `6d6e4db64190b2c949b5bb26838bfe375eba05b91d42519a2bd7eee7f4eb23c7` |
| [`activate-result.json`](activate-result.json) | activate-commit 数字结果 | `37d142bf212b71ed574e165db4d22a6f50b66ec3801bee70f39ce58b686b57c0` |

固定绑定如下：revision-6 plan `a0f9e86a32b78addf68204be76115953cf082b61791ec74a4339ac65fbab3595`，用户授权 `bcf686fefa4468633a87de1d3b4bbcefa87c04a10889f8643149ed97733bcdd0`，authorization binding `2bf2ed7d8a1cf7e79fb6960fab5bd8fe2413cc2dc7701632966a0a3e5db5325b`，revision-4 source quota `f9ed89b775ff2c57e4394c1bf39d55533752f0d19e3c0bae13286dc4aceccc86`。

## 三个入口

1. `python activation_workflow.py dry-run` 只读取旧 quota 和已批准 scope，在内存中生成 target bytes、target SHA、hold digest 和 migration transaction ID，然后写一份报告；不会写 target 或 proof。
2. `python activation_workflow.py activate-preflight` 重新读取所有锚点，验证旧 76 次中断身份、76 条 ledger call、139 个 known call、7 个 UNKNOWN、¥10/¥0.10 staged 目标和空的 commit 槽位。固定北京时间样例拒绝工作日 10:00 与 08:59 跨入 09:00 的 guard，允许 12:00 后和周末。
3. `python activation_workflow.py activate-commit` 再做一次 source/scope/ledger/quota/off-peak 检查，在新目录依次写入 target quota、migration proof、execution manifest 和 commit result。任一生成文件已存在都会返回 `DUPLICATE_ACTIVATION`；峰时先于写入返回 `OFF_PEAK_REQUIRED`。

## Proof 为什么不能手写

`activate-commit` 没有 proof 输入参数，proof 只能由当前程序根据实际 source bytes 和 target bytes生成。proof 绑定：

- source quota 原始字节 SHA 与 target staged runtime 字节 SHA；
- revision-6 plan、authorization、binding SHA；
- 7 个 UNKNOWN hold 的 canonical digest、预算/定价 identity 和 `oldUnknownReleased=0`；
- 由 source/target/scope/hold/generator SHA 推导的 migration transaction ID；
- 固定 activation time anchor `2026-11-20T15:00:00+00:00`，并纳入 transaction identity；
- 当前 `activation_workflow.py` 的 program SHA 和固定 `generatedBy=activation_workflow.py`。

`verify_activation()` 会重新计算所有这些字段，并确认 target 只把 `limitYuan` 从 `1.0` 变为 `10.0`。它还强制 proof 的 source/target 路径、`1.00 → 10.00` 金额、`reservationYuan=0.10` 和 `productionApplied=false`；manifest 必须保持 `STAGED_RUNTIME_READY_NO_PROVIDER_EXECUTION`、原 UNKNOWN digest、`runtimeQuotaAppliedOnlyInNewDirectory=true` 与 `productionRunnerModified=false`。manifest 的 off-peak 记录必须是 `allowed=true`、`reason=OFF_PEAK`、`guardSeconds=120`。proof、manifest 与 off-peak 检查时间必须等于固定 activation anchor，防止同步重写三份时间绕过 staged-only 语义。同步重写 proof、manifest 和 off-peak 的 tamper 测试也会在新目录执行前失败。已有 target/proof/manifest 后再次激活也不会覆盖文件或生成第二笔 transaction。

## 一个 quota 请求如何流转

以未来第一笔 ¥0.10 reserve 为例：程序先读取 revision-4 quota 的原始字节，确认 `knownCostYuan=0.293767460`、7 个 UNKNOWN hold 和旧 `budgetId/pricingIdentity`；当前承诺是 `0.293767460 + 7×0.10 = ¥0.993767460`，新 reserve 后是 `¥1.093767460`，低于 ¥10 target。target runtime 保留所有 hold 和身份字段，只有 `limitYuan` 改为 `10.0`。

随后 proof 把 source/target bytes SHA、scope SHA、hold digest 和 transaction ID 封存；manifest 再绑定 proof SHA，并将 `executionEnabled=true` 写在新目录。旧 quota、ledger 和 state 不变，UNKNOWN 仍是 7 个，释放数为 0。provider 访问、Key 读取和生产 quota 加载仍是 0。

## 数字验收

执行 `python -m unittest -q test_activation_workflow.py`：**30/30 通过，0 失败，0 错误；PowerShell wall time 887.617 ms**（unittest 内部 555 ms）。

| 指标 | 数值 |
|---|---:|
| provider calls | 0 |
| Key reads | 0 |
| old files modified | false |
| production runner modified | false |
| source quota limit | ¥1.00 |
| target staged limit / reservation | ¥10.00 / ¥0.10 |
| old known calls / UNKNOWN holds | 139 / 7 |
| old UNKNOWN released | 0 |
| current / next reservation commitment | ¥0.993767460 / ¥1.093767460 |
| activation time anchor | `2026-11-20T15:00:00+00:00` |
| activation transaction | `quota-migration-6e3480113e8a5348ade8d2cd37b2f276` |
| execution manifest | `executionEnabled=true`, staged-only |

这些数字证明的是新目录的 append-only 身份迁移和拒绝边界，不证明 Java runtime 已加载、真实 provider usage、业务准确率或 P95。`paid_lock_audit` 独立审查通过前，任何真实调用都必须继续拒绝。

## 设计权衡与面试追问

直接修改 revision-4 quota 会破坏旧账务证据和 7 个 UNKNOWN 的身份，所以采用 source bytes → target bytes → proof → manifest 的单向链。代价是生成了一个新目录和一笔明确的 transaction；收益是旧账本可按原 SHA 重放审计，重复激活也无法覆盖。

把 `executionEnabled=true` 放入新 manifest，而不是继续伪装成“未激活”，是为了让后续 runtime 有明确的配置入口；manifest 同时写死 `providerCalls=0`、`keyReads=0`、`productionRunnerModified=false`，避免把配置激活误报为付费执行。独立审查仍是下一道硬门槛。

面试官常追问两个问题：为什么 proof 要同时绑定 source bytes 和 target bytes？因为只绑定金额字段会允许换 quota 文件或保留错误 hold，字节 SHA 能把实际迁移对象钉死；为什么 transaction ID 还要绑定 generator program SHA？因为同一 source/target 在不同生成规则下可能得到不同 manifest，程序 SHA 让生成算法本身进入审计身份。

本轮不需要用户提供 Key、启动 Docker 或运行服务。下一步由 `paid_lock_audit` 独审本目录；在审查完成前不允许把新 manifest 接入真实 provider runner。
