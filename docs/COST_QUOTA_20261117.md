# 阶段4：持久化费用预留与调用门禁

## 当前结论

protected模式新增持久化的配置估价配额：默认总额1元，每次模型调用先预留0.10元；启动重启不清零，未知用量不退还预留。所有生成、改写、纠错、工具消息调用走同一账本。普通demo/baseline默认不开启配额。

**这不是供应商账单硬封顶。** 预留金额不是每次调用费用的数学上界；若实际配置估价超过预留，记录实际金额并锁住后续调用，不能撤回已经发生的费用。供应商价格、未返回用量、外部账户上的其他调用和本机管理员操作均超出这份账本的保证范围。阶段3 P95继续暂缓，阶段4整体尚未通过。

| 验收 | 结果 | 证据范围 |
|---|---|---|
| Java全量回归 | 625/625，63个套件，失败/错误/跳过0 | 新增20项，含原53条SQL对抗与真实PostgreSQL测试 |
| 费用HTTP验收 | 4/4组 | 3个独立Java进程，真实protected入口/控制器/会话/PostgreSQL；仅模型及其用量为替身 |
| 认证HTTP回归 | 36/36 | 配额显式关闭以隔离原认证、跨站、速率/并发行为；费用组合行为由上一行单独验证 |
| 并发预留 | 16个同时申请者仅2个获准 | 总额0.20元、每次0.10元，不产生超额预留 |
| 供应商调用与账单 | 0次 / ¥0 | 费用HTTP使用3次合成模型调用，认证回归2次；不是生产延迟/准确率实测 |
| 退出检查 | 5个监听端口均0，4个Java进程均退出 | 保留原数据库运行 |

费用证据：`reports/cost-http-20261117-v2/`；回归及校验：`reports/cost-quota-20261117/`；认证回归：`reports/access-http-cost-regression-20261117/`。首次同进程热重启探针失败单列归档，不算作通过；最终验收用独立JVM模拟服务退出与重新启动。

## 可验收路径

先按[入口保护](ACCESS_CONTROL_20261117.md)永久配置独立的`AGENT_ACCESS_TOKEN`，模型仍使用已有`AGENT_LLM_API_KEY`。本轮不需要重新设置模型Key。启动：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start_local.ps1 -Mode protected
```

protected继承local中的模型、端点和单价，要求输入/输出单价为有限正数、缓存命中单价非负，并要求请求预算开启。总额与预留均为正数，预留不超过总额，小数最多8位，总额最多10,000元。不合规直接拒绝启动，不通过零单价伪造费用控制。

默认账本是项目根目录下`.private/runtime/cost-quota.json`，已被Git忽略。可用`AGENT_COST_STATE_FILE`指定路径；环境变量在应用启动时读取。不要为恢复调用而删除账本或换新路径，这等于人工新开预算，不是重启恢复。此版本没有自动每日充值、自动释放未知用量或网页清零入口。

在另一个PowerShell终端检查（口令只进请求Header）：

```powershell
$accessToken = [Environment]::GetEnvironmentVariable('AGENT_ACCESS_TOKEN', 'User')
if ([string]::IsNullOrWhiteSpace($accessToken)) { throw '请先配置应用访问口令' }
$quotaHeaders = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('demo:' + $accessToken)) }
Invoke-RestMethod http://127.0.0.1:8080/api/access/status -Headers $quotaHeaders
Invoke-RestMethod http://127.0.0.1:8080/api/cost/status -Headers $quotaHeaders
$quotaBody = @{ question = '2018年有多少笔订单？'; sessionId = 'cost-check' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/ask -Method Post -Headers $quotaHeaders `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($quotaBody))
Invoke-RestMethod http://127.0.0.1:8080/api/cost/status -Headers $quotaHeaders
Remove-Variable accessToken, quotaHeaders, quotaBody
```

这条手动问数会真正调用供应商；先核对单价和账户额度。检查`costQuotaEnforced=true`；费用状态中`enabled=true`、`ledgerAvailable=true`，再看`canAdmitCall`。启用并不等于余额充足。`/api/cost/status`也需要认证且计入API速率桶；精确GET健康接口仍可在速率饱和时诊断，但健康200不承诺配额可用。

合成验收可复跑到**新目录**，只需要现有PostgreSQL，不调用付费模型：

```powershell
mvn test
mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/demo-http-classpath.txt'
$costCp = 'target/test-classes;target/classes;' + (Get-Content target/demo-http-classpath.txt -Raw).Trim()
java -cp $costCp com.text2sql.agent.cost.CostHttpAcceptanceProbe reports/cost-http-new
python reports/cost-quota-20261117/audit_evidence.py
```

探针启动3个独立Java进程，分别验证初始调用、同账本重启拒绝、纠错首答回退；各进程只绑定本机临时端口、使用独立随机应用口令、供应商端点指向本机不可用端口。最后一条复核冻结归档，并非重新付费测准确率。

## 技术与设计导读

### 1. 具体请求的数据流

以“2018年有多少笔订单？”为例：先经过认证与速率/并发入口，再进入请求预算、检索和上下文组装。`LlmSqlGenerator.invoke`在`model.call`之前向`CostQuota`申请预留；账本落盘成功后才登记请求模型调用并发起传输。SQL生成完成后**先按返回用量结算，再提取SQL**，因此空输出、SQL错误或最终未采用的纠错也不能免单。后续AST、EXPLAIN、只读执行与会话收尾不改。

HTTP合成验收采用总额0.12元、每次预留0.10元，并故意使用每千Token输入1元/输出2元的合成单价：

1. 第一次返回10输入Token、5输出Token，估价`(10×1+5×2)/1000=0.02`元；释放未用的0.08元，查询结果为54,011。
2. 第二次仍返回可执行SQL，但没有有效usage；保留完整0.10元预留并标记UNKNOWN。此时占用`0.02+0.10=0.12`元。
3. 第三次请求返回503、`COST_QUOTA_EXHAUSTED`，请求模型调用数0、未知用量0；会话仍记录一次RESOURCE_LIMITED，不能把没发出的调用算成付费失败。
4. 退出进程后用同账本重启，预算ID、已知费用和未知预留不变，仍然拒绝新的调用。

另一个总额0.10元的账本验证：首答结算0.02元，纠错再申请0.10元被拒绝。返回HTTP200、保留成功首答54,011，并在budget中公开终止原因；模型调用仅1次、未知0、会话仅1轮。

### 2. 核心组件、身份与数学约束

- `CostQuotaProperties`绑定独立配置；`CostQuotaConfiguration`创建并在服务关闭时释放账本。
- `CostQuota`使用BigDecimal结算、同步临界区预留、FileChannel强制落盘与原子替换、OS文件锁拒绝同文件双实例。没有引入新依赖。
- `LlmSqlGenerator.invoke`统一四种调用入口，传入requestId、下一次callNumber、Prompt SHA-256。每次预留另有UUID，防止不同调用混账；账本保存budgetId、价格/模型/端点指纹及PENDING/UNKNOWN状态，不保存Key或Prompt正文。
- `/api/cost/status`只输出金额、计数、预算ID及可用性；不返回本机账本路径、逐调用身份或凭据。原查询响应的`budget.requestId`可与私有账本关联排障。

设总额为L、固定每次预留R、已知估价K、未结算预留数N：

```text
committed = K + N × R
可申请新调用：账本健康、未触发超预留锁定，且 K + N × R + R <= L
已知结算费用 = [(输入Token - 缓存命中Token) × 输入价
                 + 缓存命中Token × 缓存价 + 输出Token × 输出价] / 1000
```

PENDING与UNKNOWN都计入N；UNKNOWN不能因名称不同而获得更轻权重。预留、检查、状态变更和持久化在同一个同步区，16路并发也不能同时看见同一份空余额。请求次数预算拒绝发生在传输之前时才允许`cancelUnsent`退还；传输异常并不证明供应商没有执行，不能退还。

四条调用共用同一价格身份。受控费用模式拒绝BYOK及切换到另一模型，防止拿全局模型单价给未知凭据/型号计费；在网络发送前返回明确资源受限原因。普通demo仍可按原逻辑使用BYOK。现有页面输入框不会因此获得受控模式下的供应商切换能力。

没有修改表召回、directHits/expandedTables标记、Top-K权重或SQL业务口径。

### 3. 效果与边界防线

补上了“请求次数有限，但累计模型费用仍可不断增长”的缺口。已知调用按配置金额结算，未知调用按整笔预留占用；重启不抹掉欠账，拒绝调用不会增加模型调用数。新增20个测试覆盖并发、重启、磁盘失败、缓存价、超预留、四种模型入口、空输出已付费、超时晚到结算及凭据错误不回显。没有产生新的模型准确率、生产P95或供应商账单结论。

必须掌握这些边界：

- **超时和晚到**：HTTP已经返回超时，供应商仍可能计费；预留继续占用。当前进程收到晚到有效usage后可以结算、释放差额，但已经返回的HTTP未知计数不会被悄悄改写。取消中断标记在持久化前临时清理、之后恢复，避免已到达结果因旧中断而漏记。
- **重启后的未完成调用**：PENDING恢复为UNKNOWN继续计费占用，不自动重发；旧实例的Lease不能修改重新打开的账本。本轮没有接入供应商账单对账，因此永久未知不会自动释放。
- **单次超预留**：测试中预留0.10元、返回估价2.01元，即使超过总额1元也必须如实记录，同时持久化`reservationBreached=true`拒绝后续调用；不能截断账目来伪造未超限。已经在途的其他调用仍须结算。未知调用也可能比预留更贵，因此配额不保证真实账单上界。
- **磁盘故障**：先落盘后发送；落盘失败停止新调用。结算落盘失败会锁住当前实例，重启沿用旧的保守预留记录。坏JSON、金额/价格指纹不符、过大账本、重复文件锁一律拒绝启动，绝不悄悄初始化为零。
- **信任与持久性**：这是受信任本机磁盘上的单实例账本，非防篡改财务系统。FileChannel.force和原子替换不是断电/硬盘损坏的完整证明；没有父目录fsync或异地事务副本。更换/删除文件、使用另一路径或其他机器会建立另一份额度；不承诺分布式合计封顶，也不防有本机管理权限的人改账。
- **配置估价与真实费用**：输入/输出价格错误、供应商变化、其他账户请求都无法由本地usage解决。金额是按配置计算，不是从供应商账户读取。原20条HTTP中的未知费用与85%业务正确率保留，不被本轮成功覆盖。

### 4. 设计取舍与面试追问

没有用“请求成功后再加总”的方案：并发和超时都能在记账前把费用打出去。没有用“超时就退款”的方案：本地断开连接不等于供应商未执行。也没有把0.10元宣称为严格上界，输入大小、Token计数和供应商计费没有这样的证明。

此处选择本机文件账本，是因为项目当前目标是单进程受控演示，可独立于业务库写权限持久化，故障时保守拒绝。替代方案是事务数据库/Redis预留，适合多实例集中额度，但需要额外存储权限、故障恢复与对账协议；本阶段不扩成分布式计费平台。文件方案仍有同步IO开销，不宣称性能提升。

**问：为什么总额度1元还可能记出2.01元？** 因为1元限制的是发送前基于预留的准入，0.10元不是调用最高账单。必须承认上界假设不足，记录超额并锁住后续调用；若产品必须绝对封顶，要结合可证明的Token上界、可靠价格/计费规则及供应商账户限额，不能靠事后本地计数承诺。

**问：重启会不会把超时账目清掉？** 不会。发送前持久化PENDING，重启转换UNKNOWN并仍占整笔预留；模型/价格身份不符拒绝打开。真正的对账应由明确的供应商账单证据驱动，此版本没有自动退款入口。

### 5. 用户配合与剩余事项

本轮验收已由AI完成，不需要用户手动补跑测试。要体验protected模式，仅需确保独立应用口令已设置、已有模型Key有效并核对实际单价；不要在聊天中粘贴凭据。

请能自行解释三件事：一个问数在第几步预留与结算、已知费用和未知预留为什么不同、拒绝来自认证/速率/调用预算/金额账本中的哪一层。后续仍需处理NULL输出语义与澄清误报，保留独立盲测、完整新主机复现及远程部署门禁；本轮不恢复P95优化。
