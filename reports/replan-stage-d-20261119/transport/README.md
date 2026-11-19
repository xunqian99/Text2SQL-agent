# D评测本机转发器：免费本机验证

范围仅为新D评测的发送层。没有修改生产Java、候选、JAR、旧控制器、旧协议或旧U_C；没有读取盲题/未见Gold、U_B/U_C逐题结果；没有调用供应商。

## 固定接口与接入责任

```python
from replan_stage_d_proxy import ModelProxy

with ModelProxy(key=in_memory_key,
                before_send=controller_before_send,
                after_send=controller_after_send) as proxy:
    # 将该本机地址配置给待测Java进程；真实key不交给Java。
    java_base_url = proxy.base_url  # http://127.0.0.1:<临时端口>
    # Java发送 POST /v1/chat/completions。
    # 待Java请求全部结束：
    proxy.drain(timeout_seconds=120)
    if proxy.errors:
        raise RuntimeError("transport callback/shutdown evidence failed")
```

`before_send(payload: dict, raw_body: bytes) -> permit: dict` 由主控制器负责：校验题目身份、模型参数、官方空闲时段且距离高峰不少于120秒、请求/模型调用/费用额度，并在返回前持久化预留。异常返回本机503，错误消息以 `D_LOCAL_GATE:` 开头，上游尝试为0。该回调必须是有界本机操作。

permit必需字段为唯一非空字符串 `token` 与有限数值 `upstreamDeadlineMonotonic`；后者是当前问数请求15秒预算的**绝对**截止时刻，不能在纠错时重新给15秒。转发器检查已用token、截止时间和关闭状态。无许可、过期、复用许可均不能开始第二次上游发送。permit的其他元数据原样交回主控制器。

`after_send(permit, result: dict)` 对每个已返回的permit恰好调用一次，包括无效/过期、关闭取消、网络异常和客户端断开。before回调自己抛异常时没有permit，不调用after，由主控制器记拒绝。after失败会在 `proxy.errors` 留下脱敏错误，原始上游完整响应仍返回Java；主控制器必须在drain后检查errors并停止后续批次，不能把它当成功记账。

result字段：

| 字段 | 语义 |
|---|---|
| startedAtUtc / finishedAtUtc | 获取permit后的发送处理起止时间，UTC ISO8601 |
| httpStatus | 上游响应头已完整取得时的状态；未知为null |
| responseJson | 已取得的响应字节能解JSON则解析，否则null；不制造usage |
| responseBodySha256 / responseBytes | 实际已取得的原始响应字节哈希/长度，可包含超时前的部分响应 |
| error | 脱敏后的本机失败原因；完整4xx响应本身不是传输异常 |
| upstreamAttempted | 已进入transport.request尝试；DNS/连接失败也可为true，**不证明供应商已收到HTTP** |
| responseComplete | 完整响应体已读完；false不能冒充成功返回 |

## 技术与设计导读

### 1. 核心机制与数据流

例如Java发送 `{"model":"已登记模型","messages":[{"role":"user","content":"查询订单数"}],"stream":false}`。本机服务先检查路径、POST、单一Content-Length、1MiB限制、JSON对象和非流式。主控制器核对该问题及额度，持久化预留，给出 `token=问数请求ID/第1次调用` 和剩余父请求的绝对deadline。转发器不重写原始请求body，仅在内存添加真实Authorization，向登记上游尝试一次。

完整响应如 `{"usage":{"prompt_tokens":17,"completion_tokens":5,"total_tokens":22},...}` 会先连同token、状态、字节哈希、时间交给after记账，再将**原始body字节与状态**返回Java。即使Java已断开，after仍保存已收到的usage。若发送后断网、超时且没有usage，`responseJson=null`，保持用量未知，不写成0。仅收到部分但仍可解析的JSON时保留它，同时标记 `responseComplete=false`，不把残缺响应伪装成成功。

来源凭据是原样返回的permit/token及 `upstreamAttempted`、`responseComplete` 两个独立标记。不能把“取得许可”“开始尝试”“供应商收到”“已知用量”压成一个成功/失败布尔值。本组件没有打分、排序、TopK或权重，不存在用伴生数据挤掉主目标的逻辑。

### 2. 核心技术与组件

仅使用Python标准库，无新增依赖。`HTTPServer`处理本机端口，每个已接纳连接登记handler；`http.client`直连上游，不读取HTTP_PROXY/HTTPS_PROXY/ALL_PROXY，不用SDK，不自动重试。`_DeadlineReader`在每次底层recv前设置“deadline−现在”，覆盖响应头和分段body，避免慢速逐字节发送不断续期。TLS使用默认的证书和主机名校验。

默认上游仅允许字节精确的 `https://api.deepseek.com/v1/chat/completions`。测试必须显式 `allow_loopback_test=True`，才可使用IP字面量回环HTTP地址及同一固定路径。没有任意URL转发口。密钥只保存在内存中构造Authorization，不输出请求头、不落盘；错误字符串会替换该key。

### 3. 效果与数字

| 证据 | 分母与结果 | 总耗时 | 真实供应商/外部请求 |
|---|---|---|---|
| first-run.json | 首版18/18测试通过，0失败、0错误 | 3896.789ms | 0 / 0 |
| final-run.json | 增加关闭中DNS恢复与建连前耗尽两项，最终20/20通过，0失败、0错误 | 4108.183ms | 0 / 0 |

最终测试实际接收了12次**本机HTTP替身请求**。`counters.gateCalls/afterCalls`只计通用Recorder的调用；自定义拒绝/故障回调不计入该计数，不能拿它们冒充全体问数或模型调用分母。工程测试20项不折算为业务正确率、真实Token/费用、真实模型P95或D阶段通过。

覆盖了：高峰门禁拒绝0发送、原body/usage一次透传、客户端放弃后仍留已知usage、超时和断线未知用量、429无重试、路径/方法/流式/JSON/超大body拒绝、endpoint约束、permit过期/无效/复用、回调错误可见与key脱敏、慢响应头与慢body绝对截止、残缺body证据、系统代理忽略、drain超时、关闭后回调/DNS恢复仍不能发送、退出join并关闭监听端口。

### 4. 设计权衡与追问

未选逐个改造新旧Java SDK的方案：它不能自然证明p9与候选共用同一个物理发送门禁，而且容易留下SDK内部重试。独立本机发送层能统一看到首答/纠错的每次真实尝试，但Java仍负责业务执行，主控制器仍负责完整额度与时段决策。

未选每次socket读固定15秒：多次读可能累计远超父请求预算。这里始终使用同一个绝对deadline；超时、4xx、网络失败都不重试。DNS只选第一个解析地址、一次TCP连接，没有地址回退，以减少隐式尝试，代价是网络可用性更保守。

面试追问一：“客户端超时，为什么不能释放全部预留并算0费用？”回答要点：客户端收不到不等于供应商没收到；用permit追踪尝试，用usage判断已知部分，没有可靠usage的请求保留未知并由控制器继续预留，不能拿费用下界宣称通过。

面试追问二：“drain后关闭还能偷偷发送吗？”回答要点：关闭先设置独立标记，门禁返回后、DNS返回后、连接前、每次写出前都检查；所有已创建handler都有独立线程登记并在退出join。晚返回的回调/解析只能留取消证据，不能恢复发送。

边界必须公开：Python标准库不能强行中断系统DNS解析，也不能强杀永久阻塞的用户回调。解析若吃完deadline，返回后禁止TCP连接；关闭标记也禁止解析恢复后发送。退出等待120秒，失败时关闭可见套接字并再join最多5秒，仍不能收尾则抛异常并在errors留证，不能声称正常退出。主控制器的before/after必须有界且不做网络；root已确认此约束。HTTP字节一旦开始发送，后续超时不承诺供应商未收到。

### 5. 仍需主控制器接入与用户核对

本交付无需用户启动外部服务。主控制器须接入两个回调、配置Java本机base URL、传入父请求deadline，并在run结束drain及核errors。真实评测仍须单独满足冻结候选、官方时段、价格/预留和批准的D协议；本次本机测试没有触发它们。

交付时核对三个问题：问题经过哪个token发送；已知Token/耗时/费用与未知项分别是多少；错误发生在本机门禁、网络/供应商还是后续业务校验。不要把transport错误混成SQL错误。
