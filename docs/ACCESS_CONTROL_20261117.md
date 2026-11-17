# 阶段4：单用户认证与接口限流

**后续状态**：[持久化费用预留](COST_QUOTA_20261117.md)已完成，当前protected默认启用费用门禁，`costQuotaEnforced=true`。以下605/605、36/36与“费用尚未实现”保留认证首次交付时点；其冻结证据未被修改。后续Java625/625、费用HTTP 4/4组、认证回归36/36均另行归档。受控费用模式不允许BYOK，使用已有全局模型凭据。金额门禁不是供应商账单硬封顶。

## 范围与当前结论

新增`protected`运行模式，在`local,demo`之后加载。它为全部页面与接口增加单用户HTTP Basic认证，并对API应用共享速率桶和并发闸门。问数、会话列表/关闭/删除，以及原有商品/订单/系统接口均经过同一入口。普通demo/baseline行为保持原样；未启动protected不等于已经获得保护。

这不是多租户、不是登录用户目录、没有RLS，没有部署到公网。本轮不修改模型、Prompt、检索打分、评测题目/Gold、请求时间/次数预算。**费用配额尚未实现**，`/api/access/status`明确返回`costQuotaEnforced=false`；接口次数与并发上限不能代替金额上限。阶段3P95继续暂缓，阶段4整体未通过。

| 验收 | 结果 | 范围 |
|---|---|---|
| Java全量 | 605/605，61个测试套件，失败/错误/跳过0 | 新增39项；包含原53条SQL对抗、真实PostgreSQL检查 |
| 真实HTTP | 36/36 | 两个完整Spring Boot上下文，生产过滤器/控制器/会话/数据库；模型为替身 |
| 外部供应商调用/费用 | 0 / ¥0 | 合成模型调用2次，不能当作新准确率或P95证据 |
| 服务清理 | 两端口监听0，Java进程退出 | 不停止原数据库 |

真实服务记录覆盖14个未认证入口、BYOK不能代替登录、错误/畸形凭据、跨站拒绝、健康/静态页认证、正常问数54,011、未授权关闭/删除无副作用、授权会话生命周期、参数错误后许可释放、并发拒绝/释放恢复、速率耗尽与健康可用。合成模型Token为本地固定值，非真实供应商用量。证据在`reports/access-control-20261117/`和`reports/access-http-20261117/`，离线复核执行`python reports/access-control-20261117/audit_evidence.py`。

## 运行与验收路径

应用访问口令使用独立变量`AGENT_ACCESS_TOKEN`，不要拿供应商模型Key替代。口令要求32至256个无空格可打印ASCII字符。受保护模式口令缺失/不合法直接启动失败，异常不含被拒绝的值。下面安全输入并保存为用户环境变量，不把实际口令填入提示文字：

```powershell
$accessSecure = Read-Host '输入独立的应用访问口令（32至256个字符）' -AsSecureString
$accessPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($accessSecure)
try {
    $accessPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($accessPointer)
    [Environment]::SetEnvironmentVariable('AGENT_ACCESS_TOKEN', $accessPlain, 'User')
    $env:AGENT_ACCESS_TOKEN = $accessPlain
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($accessPointer)
    Remove-Variable accessSecure, accessPointer, accessPlain -ErrorAction SilentlyContinue
}
powershell -ExecutionPolicy Bypass -File scripts/start_local.ps1 -Mode protected
```

启动脚本从当前进程或User环境读取口令，通过子进程环境传递，不放入Maven命令行或日志。`-Preview`显示`local,demo,protected`、固定本机地址和`agent.access.enabled=true`，不显示口令。启动脚本继续要求已存在的个人local配置，模型凭据沿用`AGENT_LLM_API_KEY`。

浏览器打开`http://127.0.0.1:8080/`，HTTP401的Basic挑战由浏览器提供登录框。用户名固定`demo`，密码为独立应用访问口令。静态页面也认证，后续同源fetch复用浏览器凭据，无需将口令写入页面或localStorage。供应商BYOK输入框仍只用于供应商调用，不能充当应用登录。浏览器凭据缓存与退出行为由浏览器管理；本轮不新增自定义登录/退出页面，也未以自动浏览器验证其登录框交互。

PowerShell命令行检查（密码从环境读取，不作为URL或命令行参数）：

```powershell
$accessBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('demo:' + $env:AGENT_ACCESS_TOKEN))
$accessHeaders = @{ Authorization = 'Basic ' + $accessBasic }
Invoke-RestMethod http://127.0.0.1:8080/api/health -Headers $accessHeaders
Invoke-RestMethod http://127.0.0.1:8080/api/access/status -Headers $accessHeaders
$questionBody = @{ question = '2018年有多少笔订单？'; sessionId = 'protected-check' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/ask -Method Post -Headers $accessHeaders `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($questionBody))
Remove-Variable accessBasic, accessHeaders
```

同一组访问Header也用于会话列表、关闭与删除。没有Header、错误/畸形Header返回401；跨站请求返回403；API速率/并发超限返回429和`Retry-After`，不进入控制器。错误体只含固定状态与提示，不回显口令、请求Header或数据库连接。

手动真实服务补验可重跑到新目录（需要PostgreSQL，模型不联网）：

```powershell
mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/demo-http-classpath.txt'
$accessCp = 'target/test-classes;target/classes;' + (Get-Content target/demo-http-classpath.txt -Raw).Trim()
java -cp $accessCp com.text2sql.agent.access.AccessHttpAcceptanceProbe reports/access-http-new
```

## 技术与设计导读

### 1. 数据如何流动

例如请求`POST /api/ask`询问2018年订单数：先进入注册到`/*`的`SingleUserAccessFilter`→检查唯一Authorization Header中的Basic凭据→检查跨站标记/非安全方法的Origin→从`ApiAdmissionGate`取得速率与并发许可→原AskController/编排/模型/只读执行链路→在finally关闭许可。未认证在第一步返回401，不创建会话、不消耗已认证桶、不调用模型。会话DELETE同样走这条入口，因此无法只用sessionId删除记录。

### 2. 组件、身份和数学不变量

- `AccessProperties`独立绑定开关、口令及限额；运行时比较的是`SHA-256(demo:口令)`，提供值同样先哈希，通过`MessageDigest.isEqual`比较固定长度摘要。解码Basic临时字节数组用完清零。配置对象仍持有环境绑定字符串，这不是密码保险箱，不能宣称已从所有进程内存擦除。
- 身份是**一个共享应用用户**，没有IP→用户映射，也不读取X-Forwarded-For作为额度身份。不同IP/伪造转发头不能获得新桶。认证口令与供应商Key来源分开，禁止把X-Api-Key当登录凭据。
- 桶容量B=10，补充率R=30次/分钟。每次请求使用单调时钟计算`tokens=min(B,tokens+elapsedNs/60e9*R)`；有至少1个令牌才扣1。空闲不会积攒超过B，突发额度和持续速率都受约束。等待建议为`ceil((1-tokens)*60/R)`秒，最小1秒。
- 并发默认C=2，与取令牌操作同锁检查：`active<C && tokens>=1`才发放许可；并发拒绝不再扣令牌。`Permit.close`同锁且幂等，异常退出也释放一次。并发闸门与原16工作线程池不同：它限制当前HTTP处理，不把超时后尚未退出的模型线程伪装成仍存活HTTP请求。晚到工作线程仍受原工作池上限和预算约束，金额预留需后续配额单独处理。
- 所有API路径共用桶，包括状态查询；唯一例外是精确`GET /api/health`：仍需认证，但不消耗API桶/并发许可，以便饱和时排障。静态资源经过认证但不计入API桶，不是网络级抗DoS措施。
- 本轮无表打分、Top-K或权重改动，原directHits/expandedTables来源和扩展衰减保持不变。

### 3. 业务收益与已知边界

把“本机地址限制”补成明确的认证门禁；会话维护、其他业务接口和未知路径也拒绝未认证访问。禁止跨站fetch；非安全方法如POST/DELETE若有Origin，必须与实际请求scheme/host/port精确同源，重复Origin、null、路径/查询/用户信息均拒绝。CLI没有Origin时依靠显式Basic认证；不信任代理转发头改写来源。没有给跨站请求开放CORS。

Basic的Base64是编码而非加密；目前仅绑定本机。远程演示仍需TLS、代理边界、费用配额和部署验收，不能打开公网HTTP。认证没有密钥轮换会话、双因素或第三方账户体系；口令改变需重启当前进程。流量桶和额度仅单进程，重启恢复满桶；多实例不能共享限额。新功能不授予数据库写权限，原商品/订单写接口仍由数据库最小权限拒绝，认证成功不意味着这些业务写功能已获验收。

### 4. 设计取舍与面试追问

没有只保护ask：这会留下读取/删除会话与系统接口的旁路。选择入口过滤器覆盖全部页面与接口，而不是逐控制器复制认证判断；没有新增依赖、用户表或多租户配置。没有按IP分桶，单用户系统不该把可变网络地址冒充业务身份。没有自制页面会话与cookie登录，以免为一次受控展示引入额外登录存储；本机共享凭据的适用范围已限定，多用户/远程认证应采用成熟安全框架单独设计。

**问：为什么有15秒预算还要并发限制？** 时间预算控制每个请求的存活时间，不能限制同一时间涌入的请求数。HTTP闸门限制当前处理数，工作池限制尚未退出的执行任务；费用上限还需要逐模型调用预留，三者不能互相冒充。

**问：为什么认证前不消耗这个桶？** 单个共享已认证桶若被任意未登录请求扣光，会让外部请求轻易阻止合法展示。未登录只做固定大小凭据检查并拒绝；网络攻击与暴力请求吞吐应由部署入口承担，当前没有宣称实现网络防火墙。

### 5. 用户需要掌握与剩余工作

需自行配置独立`AGENT_ACCESS_TOKEN`后才能使用protected模式，原demo运行不受影响。核对三个点：请求在哪一层返回401/429、应用口令与供应商Key为何不同、被拒绝请求为什么不会产生模型费用。此处只证明入口保护，模型改写/纠错/超时调用的费用配额尚待实现。
