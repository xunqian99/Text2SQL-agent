# 永久 scope lock 独立复核（2026-11-20）

## 结论

最新锁已达到本次审计目标：revision-4 的付费 scope 绑定了精确的 decision、proposal 和 source plan SHA；任一身份或四类关键字段被篡改时，`require_human_authorization()` 都在授权文件和 Key reader 之前拒绝。独立重跑结果为四类探针全部 **credential reader=0、provider=0、batch writes=0**。

本复核没有执行 `run-next`，没有启动应用，没有连接供应商，没有读取真实 Key，没有打开旧 68 题答案 SQL 或 unseen，也没有修改共享 revision-4 批次。

## 复核对象与固定身份

被复核源码 SHA-256 为 `132627384d885e1255a00607474dbc632080b7d1ebf2c64c513a614a7674669e`。源码中的三个常量与当前文件实际字节逐一相等：

- decision：`4e5b824568e7f2a87070b4390a24f6fa5f713c27afb17b68ee374adc8e509f87`
- proposal：`0c72656803e63dce968d8d94edf3eb5ed191274bdfc304c6004fee2018422c0c`
- revision-4 source plan：`da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe`

决定文件字段为 `DECLINED_NO_ADDITIONAL_PAID_EVALUATION` 和 `newPaidAuthorizationGranted=false`，且其中的 `proposalSha256` 与固定 proposal SHA 一致。

## 具体数据流

任何正常付费请求都要经过 `run_next()`，其第一步调用 `require_human_authorization()`。该函数依次检查 scope decision 存在且不是 symlink、decision SHA、proposal 存在且 SHA、revision-4 plan 存在且 SHA，然后读取决定字段并验证 proposal 绑定。即使这些检查全部通过，函数最后仍无条件抛出 `Refused("Revision-4 paid scope is permanently closed by direct-user decision")`。

因此调用链在 authorization JSON、consumed seal、`guarded_real_key()` 的 `_old_real_key()`、配额 admit、HTTP 和 Java 应用之前终止。历史 `human-paid-authorization.json` 和 consumed seal 保留在磁盘，只作为证据，不再成为可消费凭据。

来源凭据没有被扁平化：decision 表示用户范围，proposal 表示被拒绝的提案，source plan 表示具体 revision-4 身份，state 表示中断 terminal，quota 表示 known 高水位与 UNKNOWN holds。三份 SHA 是 scope capability 的身份锚，不是金额或模型输出的替代物。

## 四类篡改探针

每个探针都在临时目录生成副本，替身 Key reader 只用于检测是否越过拒绝边界：

| 探针 | 触发的拒绝 | credential reader | provider | batch writes |
|---|---|---:|---:|---:|
| `decision` 改为批准 | decision identity changed | 0 | 0 | 0 |
| `newPaidAuthorizationGranted=true` | decision identity changed | 0 | 0 | 0 |
| `proposalSha256` 改写 | decision identity changed | 0 | 0 | 0 |
| source plan 增加字节 | source plan identity changed | 0 | 0 | 0 |

独立重跑产物为 `independent-fixed-recheck.json`；父任务同版结果为 `result-fixed-4.json`。两份结果都记录了相同的四类零调用结论。

## terminal 与配额交叉检查

当前 revision-4 仍是 `requestsStarted=76`、`roundStatus=INTERRUPTED`、`terminal=INTERRUPTED_NO_REPLAY`。candidate quota 有 139 个 known calls、已知成本 `¥0.29376746`、7 个 UNKNOWN、单次预留 `¥0.10`。下一次预留后的配置占用为 `¥1.093767460`，超过冻结上限 `¥1.00`。这些是独立的第二道保底；本次复核没有通过重启、清零或恢复中断批次来验证它们。

## 设计效果、权衡与边界

这次修改解决的是“旧授权仍在磁盘，后续范围拒绝却没有撤销能力”的入口错误。效果可以量化为：decision/proposal/source plan 三个身份匹配为 **3/3**；四类篡改探针 **4/4** 在 Key 前拒绝；真实 Key、供应商调用、批次写入均为 **0**。

选择固定 SHA，而不是删除旧授权文件，是因为删除会破坏历史审计证据；选择永久关闭，而不是把额度改成零，是因为额度账和供应商账仍需保持原始身份。未来若用户重新开放付费范围，必须创建新 registration、新 plan、新 direct-user authorization 和新的独立工程审查，不能修改或复用 revision-4。

当前保证覆盖 `run_next()` 和 `guarded_real_key()` 的付费入口。`application()`、`http_json()` 等函数仍是模块内 helper；正常路径无法先获得 Key 因为永久 lock 已经拒绝，但若未来把这些 helper 暴露为公共入口，应继续传递 scope capability 或将其收为只能由 `run_next()` 调用的私有接口。

面试追问“为什么四类篡改都不能到 Key？”应回答：拒绝依赖精确字节身份，任何决定、提案或 plan 变化先在 SHA 层停止，且函数在成功校验后仍无条件拒绝；历史授权不会被重新消费。追问“为什么没有跑模型验证？”应回答：用户已拒绝新增付费评测，本次只验证本地安全边界，provider/key/API 必须保持零调用。

## 用户配合

当前无需提供 Key、启动应用或重新批准付费。若未来重新开放，只能走新 plan、新独立审查、新授权流程；不能在现有 revision-4 上改写 scope JSON 继续执行。
