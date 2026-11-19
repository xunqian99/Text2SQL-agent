# 原始上游响应交付：revision-2局部修复

首次D审查拒绝与旧固定plan/decision继续保留。本修复主要补代理到控制器的原始响应字节接口，不恢复旧结论，不修改旧证据。原20项、chunked 13项均未重跑；根任务报告的旧组合57项中1项chunked超时没有由本专项覆盖或洗掉。根任务随后定位：旧非法请求夹具把合法 `Transfer-Encoding: chunked` 配空body，支持chunked后会正确等待body，而测试客户端先超时。按根任务要求，同批把该夹具改成确定非法的 `Transfer-Encoding: gzip`（仍期望400），不单独重跑；修正是否成立留待最终同版组合回归统一确认。

## 技术与设计导读

### 1. 实现与数据流

例如上游实际返回 `b'{ "usage": { "total_tokens": 22, "total_tokens": 0 } }\n'`。代理照旧读取原始字节、计算SHA256、判定响应完整；严格JSON校验发现重复字段，将 `responseJson=null` 并写 `D_LOCAL_RESPONSE:duplicate JSON object member; usage is unknown`。新增的 `result["responseBodyRaw"]` 把**同一份原始不可变bytes**交给 `after_send(permit, result)`，包含空格、字段顺序、重复字段及末尾换行；状态与原body对Java的透传不变。

控制器须先独占写入 `<token>-upstream.body.bin` 并核对长度/hash，然后从用于JSON账本的result副本移除bytes、换成保存路径和校验信息，再写ledger。代理自己不落盘。本地客户端已断开时after仍恰好调用一次，因此该原始证据仍能由控制器保存。

来源追踪继续依靠permit/token；`upstreamAttempted`只表示transport尝试，不保证供应商收到；`responseComplete`只表示传输完整，不保证语义唯一。空响应和发送前过期均传 `b""`，部分超时传已读到的前缀；这些情况不制造0用量。原始字节可用不等于已经持久化，持久化成功须由根控制器另行证明。

### 2. 核心技术与兼容影响

仅在 `_forward` 的finally阶段生成 `body=bytes(response_body)` 后增加 `responseBodyRaw`，仍在after之前赋值；不改变模型请求、授权门禁、超时、重试、JSON重复字段策略或返回Java的响应。已有hash和字节数与新bytes对应。

这是回调数据类型的兼容变化：包含bytes的result不能直接 `json.dumps(result)`。调用者必须先取出bytes，完成原始文件写入后再编码其余元数据。测试的通用断言已分开检查原始bytes和可JSON编码的元数据，避免旧测试仅因新字段类型报无关错误。未引入依赖、排序或权重算法。

### 3. 数字与验证范围

`first-run.json`：**4/4测试方法通过，0失败、0错误，727.572ms**。实际本机替身上游请求6次，通用Recorder的after调用7次（含1次过期permit的0发送记录）。供应商调用0，外部请求0。

四项覆盖成功与重复JSON的逐字节/hash/长度一致；部分响应超时保留前缀且用量未知；客户端放弃后after仍收到完整bytes且恰好一次；发送前过期、上游断线、无响应超时均保留空bytes与未知用量。subTest不计为独立测试方法分母。

这只是原始字节交付的本机证据，不证明根控制器已经落盘，不证明旧chunked超时已消除，不宣称最终全量通过，更不折算业务准确率或真实Token/成本。

### 4. 设计权衡与面试追问

未选把bytes转成base64再放进账本：那会让原始证据与结构化账目耦合，额外增加约三分之一体积，也掩盖“文件先落盘、账本后引用”的顺序。代理只交bytes，根控制器统一决定证据文件命名、独占写入和失败停止。

未选仅保留hash/解析后的JSON：hash只能校验已有原件，不能恢复原件；普通JSON对象还会丢掉重复字段、格式和原始编码。记录原bytes才能复查解析歧义。

追问：“完整bytes已有，为什么usage还是未知？”答辩要点：物理字节完整与语义唯一是两个不同凭据；重复total_tokens无法唯一解释，不能挑一个值记账。

追问：“写文件失败，还能认为这次模型没有花钱吗？”答辩要点：不能。发送尝试已经发生，持久化失败应暴露、停止后续请求，并保留未知/预留；不能释放预留或伪造0用量。

### 5. 配合事项

用户无需额外操作。根控制器负责先写原始bytes再写账本、检查失败、修订pending与batch-1 revision-2，并统一做最终组合回归。交付时须核对原始响应路径/hash、已知与未知费用、错误层次，不能把证据持久化失败归成SQL错误。
