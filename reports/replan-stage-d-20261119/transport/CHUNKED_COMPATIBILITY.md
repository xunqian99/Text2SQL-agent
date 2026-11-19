# Chunked与重复JSON字段的兼容修复

本修复由根任务的首次真实JAR本机接入发现：原p9使用 `Transfer-Encoding: chunked`，旧代理只接纳Content-Length，导致本机400、before/after/上游均为0。原首次接入证据由根任务保留，本工作未读取或修改rehearsal目录。原18项与20项测试证据字节未修改。

## 技术与设计导读

### 1. 实现与具体数据流

例如p9发送 `Transfer-Encoding: chunked`，线上的JSON被拆成 `ff8\r\n<4088字节>\r\n` 等块，最后 `0\r\n\r\n`。代理逐块验证大小行及CRLF，只移除HTTP传输分帧；UTF-8字符即使跨块也先按字节拼接，最终JSON字节的空格、顺序、缩进均保留。完整解码后才能调用 `before_send(payload, raw_body)`，统一核问数、模型、时段、调用/费用预留。转发上游时重新使用Content-Length分帧，JSON body字节不变。

permit/token仍负责来源追踪，`upstreamAttempted`与`responseComplete`仍分开记录。非法/不完整分帧或请求JSON重复字段在before之前拒绝，未取得permit，不发生after或上游尝试。合法解码后若主控制器拒绝，依然返回503/D_LOCAL_GATE且上游0；解码本身不产生费用许可。

请求采用递归 `object_pairs_hook` 校验；例如 `{"model":"a","model":"b"}`、消息数组内重复content，或者 `"model"` 与转义形式 `"\u006dodel"`，均返回400，不能让两个解析器各取不同值影响模型参数、stream或计价。

响应同样严格解析。例如 `{"usage":{"total_tokens":10,"total_tokens":0}}`，after收到 `responseJson=null` 和 `D_LOCAL_RESPONSE:duplicate JSON object member; usage is unknown`。完整上游原始body/status仍透传，SHA256、字节数和 `responseComplete=true` 保留；这只说明字节完整，不代表JSON语义可信。根控制器必须把用量保留为未知，并按错误停止后续请求，不能默认last-wins后记0费用。正常响应usage原样保留。

### 2. 技术抓手与边界

没有新增依赖。`_Handler`增加严格分帧解码；`_LocalRequestReader`在每次底层recv前设置“同一个本机body deadline−现在”，覆盖buffered read/readline内部的重复接收。body预算固定15秒；100-continue从发出100前开始计时，do_POST不会再次续期。Content-Length读入也共用该机制。超时返回408，尚未取得permit，上游为0。

| 防线 | 具体限制 |
|---|---|
| 分帧歧义 | 只允许单一TE值chunked，大小写不敏感；TE+CL、多TE、编码链、其他编码、chunked参数、HTTP/1.0 chunked均400 |
| 解码大小 | 累计JSON bytes ≤1,048,576，超过即413；不是只限制单块或线上的Content-Length |
| 块数 | 非空块≤4096；超过400，零块立即结束。这给约4KiB的JDK风格分块留足空间，同时拒绝百万个单字节块 |
| chunk行 | 含CRLF≤1024 bytes，十六进制size和token/quoted-string扩展均严格校验；符号、0x前缀、缺CRLF、坏扩展均400 |
| 数据尾分隔 | 每个非空块的数据后必须恰为CRLF；读不足不能拿部分JSON进入门禁 |
| trailer | 声明最多32个唯一合法名称；实际最多32行、每行含CRLF≤1024 bytes，总section≤8192 bytes；必须预先声明且不能重复 |
| trailer禁区 | 拒绝CL/TE/Host/Authorization/Content-Type等分帧、路由、鉴权及内容控制字段，也拒绝Connection指定字段、折行、控制字符或非法名称；trailer不转发上游 |

这不是任意HTTP透明代理。它支持评测需要的标准chunked请求和有界合法扩展/已声明非敏感trailer；对于不可确认安全的字段组合直接拒绝。没有排序、打分或TopK机制，不存在权重挤压设计。

### 3. 验证数字

仅新增专项方法13个，**13/13通过，0失败、0错误，1742.940ms**；本机替身收到7次HTTP请求，真实供应商调用0，外部请求0。证据为 `chunked-first-run.json`，独占创建，没有覆盖旧文件。

覆盖JDK风格约4KiB分块与跨块UTF-8、大小写TE、合法扩展与trailer、100-continue、分帧冲突/损坏、精确1MiB成功与累计超限、4097个微块、trailer数量/总量/语法、块大小行与数据的慢速输入、Content-Length慢速输入、解码后的主门禁拒绝、请求与响应递归重复JSON字段。测试使用本机HTTP替身；JDK风格输入不冒充真实两侧JAR接入成功。

原 `final-run.json` 的20/20是修复前证据，本批按要求没有重跑旧20项。13是测试方法分母，不是其中subTest数，也不能与历史20相加宣称当前完整33项都跑过。真实p9/候选JAR的下一次本机接入由根任务独立执行。

### 4. 设计权衡与追问

未选删除本机代理或改造旧p9：那会绕开发送门禁，失去两侧统一计数和物理发送证据。也未选直接把chunked原帧转发上游：HTTP分帧与JSON必须先分离，额度门禁才能看到确定的payload，且不受CL/TE解释差异影响。

未选JSON默认字典解析：默认last-wins会隐藏重复参数，产生解析差异和费用低估。请求直接拒绝；响应保留物理证据，把语义及用量标为未知，兼顾可追溯与账目保守性。

面试追问一：“为何要限制块数，1MiB不够吗？”答辩要点：解码长度只限制有效字节，不限制解析工作和传输开销；百万个1-byte块能放大行解析/系统调用成本。块数、行长、总时长共同约束资源，正常约4KiB分块最多约257块，4096有余量。

面试追问二：“为什么body完整却usage未知？”答辩要点：`responseComplete`是传输完整性凭据，唯一字段和可信JSON是语义凭据；重复usage/total_tokens意味着无法唯一解释供应商用量，不能从多个值中挑便宜的那个。

### 5. 主控制器接入责任

构造参数与callback接口没有变化。根控制器需继续检查after.result.error、严格核对usage与账本，且后续只做免费的真实两侧JAR本机接入。无须用户启动外部服务，也没有新增真实模型调用许可。用户仍需核对：分帧解码后的数据如何进入token许可、每次已知/未知用量、错误究竟属于传输/解析还是SQL链路。
