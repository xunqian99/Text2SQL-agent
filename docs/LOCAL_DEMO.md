# 本机受控演示与验收步骤

适用范围：Windows PowerShell、Java21、Maven3.9、Docker Desktop、单用户PostgreSQL问数。本说明是运行路径，**不是全部步骤已经在干净环境通过的声明**。P95优化暂缓，阶段3原目标与阶段4欠项见[执行决定](STAGE3_DEFERRAL_20261116.md)。

## 1. 数据库准备

先准备`data/raw/olist/`内的Olist原始CSV（不随仓库分发），然后在项目根目录执行：

```powershell
docker compose -f docker/docker-compose.yml up -d
```

**仅全新演示数据库**执行下面的初始化。`rebuild_db.ps1`含重建表操作，不要在已有数据的库上为验证文档而执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/rebuild_db.ps1
```

初始化依次执行原始表、CSV、扩展表、种子数据、只读角色、持久化表与专用角色。应用业务连接必须使用`text2sql_ro`；管理账号`text2sql`只用于建库导数。`text2sql_rw`只允许操作专用持久化表，不能授予业务表全部权限。旧`scripts/deploy.sh`当前存在过宽授权和公网暴露问题，不用于本机或远程验收。

现有Compose映射的数据库端口是全部网卡，本次新增应用启动入口只约束HTTP监听。不要据此宣称整台机器已经完成网络隔离；需要靠本机网络策略限制数据库访问，远程部署尚未验收。

## 2. 配置凭据

本地配置不存在时复制模板，已有文件不要覆盖：

```powershell
if (-not (Test-Path src/main/resources/application-local.yml)) {
    Copy-Item src/main/resources/application-local.example.yml src/main/resources/application-local.yml
}
```

模型Key使用用户环境变量`AGENT_LLM_API_KEY`。已永久配置的Key由启动脚本读取，无需再输入；不把Key作为`Read-Host`提示文字，也不放进启动参数。模板中的单价是配置估价参数，演示前核对供应商账号实际时段和价格；本说明不承诺其为当前账单价。数据库自定义密码分别由`AGENT_DB_PASSWORD`与`AGENT_DB_PERSISTENCE_PASSWORD`提供。

## 3. 区分运行入口

| 用途 | 命令 | 行为与边界 |
|---|---|---|
| 完整本机演示 | `powershell -File scripts/start_local.ps1 -Mode demo` | 检索、Join规划、语义、示例、澄清、纠错、结果检查、多轮开启；查询缓存/持久化/工具循环/升级路由关闭 |
| 全Schema参照 | `powershell -File scripts/start_local.ps1 -Mode baseline` | 关闭上述增强，保留AST、EXPLAIN和只读执行；真实问数仍需模型，不是离线推理 |
| 离线Gold自检 | `powershell -File scripts/run_eval.ps1 -Split all -DryRun` | 不调用模型，执行原200题Gold；不能当作模型准确率 |
| 真实评测 | `powershell -File scripts/run_eval.ps1 -Split eval` | 使用原评测脚本的显式配置，默认关闭会话/查询缓存/纠错/结果检查；与demo分开记账 |

演示与基线均先载入`local`再载入对应profile；启动参数固定本机地址、端口和关闭自动评测。模型端点、型号、Token与单价继承个人local配置，命令行覆盖仍具有更高优先级。Profile名称不是完整配置凭据，必须核对健康响应的实际开关。`-Preview`只打印非敏感启动参数，`-Port 18084`可避开已有服务。

连续20条验收使用[冻结HTTP协议](DEMO_HTTP_PROTOCOL_20261116.md)。`-CallLogDir reports/<独立运行目录>/llm-calls`可为本轮单独记录调用账目；不要与其他进程共写同一目录。

演示与基线默认总预算15秒、最多2次模型调用、重试至少预留2秒，数据库查询10秒、200行。预算也包含多轮改写调用；不能假设最多2次都留给生成和纠错。线程调度、取消与收尾会产生少量墙钟越界，超时仍可能产生供应商费用，不能称绝对硬实时或硬金额配额。

## 4. 启动与健康检查

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start_local.ps1 -Mode demo
```

另开终端：

```powershell
$health = Invoke-RestMethod http://127.0.0.1:8080/api/health
$health | ConvertTo-Json -Depth 6
```

核对`mode=demo`、`capabilities`及`limits`。`ready=true`仅指全局模型客户端已配置且Schema快照非空；`providerVerified=false`、`databaseLiveVerified=false`说明这不是付费供应商探针，也不是数据库实时心跳。Schema来自可缓存目录。无全局Key时`ready=false`但进程可正常运行；过去某个BYOK请求留下的客户端不能把全局状态变成就绪。Schema读取失败或为空返回503、`SCHEMA_UNAVAILABLE`，不回显异常细节、JDBC地址或凭据。

当前HTTP只监听`127.0.0.1`，不支持其他机器直连。**没有单用户访问认证、限流或总费用配额**；同机进程仍能访问，代理/隧道也可能重新暴露服务，不应开启公网转发。`X-Api-Key`和请求中的`apiKey`是供应商BYOK凭据，不是应用登录令牌。

## 5. 自检与一条真实问数

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_eval.ps1 -Split all -DryRun
```

应保存200/200报告，不调用模型。该脚本的服务端口默认为18080，与演示8080分开；退出后不应残留评测服务。

```powershell
$body = @{ question = '2018年有多少笔订单？'; sessionId = 'local-demo-check' } | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/ask `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$result | ConvertTo-Json -Depth 8
```

应核对`status`、SQL、输出列与行数、四段`timings`、`budget`及`llm`。返回SUCCESS只表示链路成功，业务正确性须与明确口径的只读SQL对照。调用日志默认不记Prompt/输出内容；用`reports/llm-calls`逐调用记录核算改写、未采用纠错和超时调用，未知usage单列，不能只算最终答案。

具体链路：问题进入`AskController`→`Text2SqlOrchestrator`处理会话与预算→Schema检索/语义→模型生成→AST/EXPLAIN→只读执行→结果检查→响应。`HealthController`读取`AgentProperties`展示实际配置，完全不触发这条付费问数链路。

## 6. 剩余验收证据

- 20条冻结代表题连续HTTP执行：接口成功与业务正确分别计数；保存平均/P95、全部调用账目、会话隔离与未知用量。
- 新隔离环境完成CSV初始化、启动、Gold200/200与一条真实问数；当前库跑通不能替代干净环境复现。
- 数据库只读角色无法修改业务数据，现有SQL对抗测试通过。
- 远程模式仍须补单用户鉴权、所有会话接口保护、限流、费用上限与权限收敛；当前不宣称通过这些门禁。
- 阶段3原P95、独立盲测及完整结果语义契约仍为欠项。

## 7. 技术与设计导读

核心组件是Spring profile配置覆盖、`AgentProperties`绑定、`HealthController`白名单响应和PowerShell启动器。有效配置来自绑定后的属性，不按profile名称猜开关；就绪来源使用全局客户端标记，避免把其他请求的BYOK缓存冒充服务配置。健康检查不参与表打分、Top-K或数据裁剪，不改变原检索来源追踪和权重。

选择独立profile而不是复制完整local配置，避免散落Key与个人端点；保留命令行覆盖让诊断可控，但要求核对有效配置。没有引入新的依赖或安全框架。本轮不承诺准确率、P95或模型费用改善。

面试追问：**健康200是否证明模型能用？** 不证明，配置检查不验证Key有效期、额度和供应商响应，一条真实问数才覆盖完整链路。**监听本机是否等于鉴权？** 不等于，它限制直连网络面，同机进程和转发仍可访问；远程模式必须另验认证与配额。
