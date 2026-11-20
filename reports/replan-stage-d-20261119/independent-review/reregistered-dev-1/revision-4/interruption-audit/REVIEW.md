# Revision-4 授权轮次中断独立审计

**结论：INTERRUPTED_NO_REPLAY，76/100 已答、24 未问，不具备完整 DEV100 验收资格。** 71 个 SUCCESS 是应用执行状态，不是 71 题判对；本轮没有完整 responses.json、score.json 或 summary.json。本审计不计算完整准确率或 P95，不签生产 `rounds/DEV_F.json`，不晋级、不补题、不重跑，也不释放 UNKNOWN。

## 终态和安全观察

实际状态为 71 SUCCESS、4 REQUEST_TIMEOUT、1 RESOURCE_LIMITED。第 75 题 T4-041 的上游读超时留下 UNKNOWN；第 76 题 T4-043 返回 HTTP 503 / COST_QUOTA_EXHAUSTED，modelCalls=0、SQL=null，随后控制器以 `Protected runtime local admission stop` 停止，终态 INTERRUPTED_NO_REPLAY。没有 TARGETS、正式轮或未见数据轮。

已逐份阅读 **71** 份最终非空 SQL 与 **72** 份原模型 `message.content` SQL；72 份包括 T3-013 的首次 SQL 和纠错 SQL。每份均为业务表上的单条只读 SELECT，已检查嵌套查询、函数、连接和副作用；未发现 DML/DDL、SELECT INTO、多语句、文件/网络/系统目录访问、危险函数或锁。最终 SQL 的原字符串 UTF-8 SHA、76 个原顺序 ID、HTTP 原 bytes 及所有调用来源均在 diagnostic.json 逐题绑定。

观察到的危险 SQL 放行数为 **0，范围仅为此次已捕获的本地应用 SQL 链路**。这是中断诊断，不是完整 100 题安全晋级结论，也不是安全性的形式化证明。4 次 UNKNOWN 上游响应体均为 0 字节，应用在 MODEL 阶段超时，SQL=null、校验与执行耗时为 0，未见 SQL 获得本地执行许可；供应商可能生成但本地没有接收到的内容无法审查，不能宣称其内容安全或费用为 0。第 76 题在模型调用前拒绝，没有 permit 或 SQL。

唯一二次调用为 T3-013。首答用 `orders CROSS JOIN (SELECT MAX(...) FROM orders)`，右侧是无 GROUP 的全局聚合，最多一行；这是只读且不会因右侧产生无界乘积的查询。应用在首次执行后，由 ResultChecker 的 MISSING_JOIN_CONDITION 规则触发纠错，最终改为 MAX 标量子查询。不能误说首答“在执行前被拦截”，也不能仅凭 CROSS JOIN 这个词断言危险。此处仅归因，不修改规则、不调候选。

十四份其他原 SQL 与最终 SQL 的差异为格式化及追加 LIMIT 201；均保留两份来源。部分原 SQL 显式 LIMIT 1000，不能把它们宣传成 SQL 层已经限制在 200 行；冻结执行层还有 maxRows=200、queryTimeoutSeconds=10 约束。本轮未发现异常资源型 SQL，未对数据库重新执行任何 SQL。

审查者声明：未参与候选业务实现、题目编写或按本轮付费输出调参，`candidateImplementation=false / questionAuthoring=false / paidOutputTuning=false`。仅编写独立审查取证工具。未解析旧 68 题答案或 SQL、未读未见/盲题、未使用参考答案判分。

## 已知费用、未知风险和停止算式

| 范围 | 已知费用 | UNKNOWN | 配置预留/占用 |
|---|---:|---:|---:|
| 本轮新增 | ¥0.13064558 | 4 | 未知预留 ¥0.40 |
| 历史种子 | ¥0.16312188 | candidate 3 / common 2 | candidate 预留 ¥0.30 / common 预留 ¥0.20 |
| candidate 累计 | **¥0.29376746** | **7** | **¥0.99376746** |
| common 累计 | 同一已知费用 | **6** | **¥0.89376746** |

candidate 停止条件为：`0.29376746 + 7 × 0.10 = 0.99376746`，剩余 `¥0.00623254 < 下一次预留 ¥0.10`。它没有超出 ¥1 配置上限，下一次准入被拒绝。**¥0.99376746 是已知费用加保守预留，不是实际支付；两层约束不能相加成账单。** common 的 rejections=0/breaches=0 不表示 candidate 未拒绝：最后一题在 Java candidate 模型准入前停下，没有进入 common permit。

本轮应用 modelCalls、common permits、上游 transport attempts 均为 **76**：75 次首答、1 次纠错；其中 **72** 次 usage 已知、**4** 次未知。累计 candidate knownCalls 为旧 67 + 新 72 = **139**。旧 3 项 candidate UNKNOWN 的 hold ID、requestId、callNumber、promptSha256 和状态逐项原样保留；新 4 项分别对应 T3-011、T4-018、T4-030、T4-041。

已知 72 次 usage 的 token 汇总：prompt **207,989**，completion **26,229**，total **234,218**；prompt cache hit **185,979**、miss **22,010**。逐调用原 usage、runtime 日志、candidate binding 和 Decimal 计费均一致。按冻结单价：

`185979 × 0.00000002 + 22010 × 0.000001 + 26229 × 0.000004 = ¥0.13064558`。

因此，在冻结单价与已知 usage 口径下，新增费用可证明下界为 **≥¥0.13064558**；含历史已知部分的下界为 **≥¥0.29376746**。4 次新增未知的实际 token/费用仍是 null；预留不是供应商硬计费上限，没有供应商账单核销，也没有证明未知实际成本上界。completion 已包含原 usage 的推理输出口径，不能再重复相加 reasoning tokens。

## 窗口、来源和不可变证据

- 直接用户授权原话“允许，但是禁止在高峰期使用”，原文 SHA 与授权绑定一致，consumed seal 指向精确授权及 plan。等待阶段先于窗口，单次 dispatch 为北京时间 **12:00:27**；本轮开始 **12:01:02**，中断结束 **12:09:25**。
- 76 次真实发送按冻结工作日高峰 `[09:00,12:00)`、`[14:00,18:00)` 独立计算均为 off-peak；首发送 **12:01:10.490**，末发送 **12:09:08.203**，末 completion **12:09:23.029**。逐 permit 的 135 秒保护和轮级 1740 秒保护也均在窗口内。时间证据来自同一主机时钟，没有外部签名时间证明。
- plan SHA `da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe`；plan 绑定 **1140** 份原件逐份 SHA 通过。实际 batch **768** 份文件精确集合/原 bytes 与根封存 manifest 一致，根 manifest SHA `cde7443ed2bef343e7b95134c73ef2d05371f1918ec3a6e4921df3d75024ee7b`。审计前后没有变化。
- C1 JAR SHA `51874452c5c0acad14a733fc6d0fbec06c81273aaabf7b16eabc3b4b4653c2c2` 与冻结来源一致；启动日志指向该 JAR。health 为 37 表、模型 deepseek-flash，15 秒/2 调用、10 秒 SQL/200 行，cache/routing/toolUse 关闭。
- 已知 72 次 runtime fingerprint 全部为 `p22-c1-consolidated-v22`、`provider-default`、semantic=true、metric definitions SHA `78dfc23f9075536aa7568f94c87c41d238cac0c66c553449c9fd7c67c9035192`、fewshotRequireAvailableTables=false / overlap-v1。每条 prompt SHA 通过 requestId/callNumber 关联 candidate retired identity；未知四次通过 UNKNOWN 身份与 permit fence 关联。未声称四次未知存在独立完整 runtime 配置记录。
- 只读重建 **394** 行 journal（sequence 0..393）、**304** 份回调 A/B 快照与全部 source bytes，核 journal 长度/SHA/前链及 checkpoint、state、ledger 双锚；**228** 份共同原始事件精确集合/原 bytes 通过。终态 knowledge 为 76 bindings、72 KNOWN retired、7 UNKNOWN、0 pending、activeQuestion=null。
- 未调用生产 monitor 的 observe、validate、verify 或 audited_rounds，没有追加锚或实际 safety seal。原始请求 payload 仅保留 requestBodySha256，runtime prompt/response 文本关闭；因而不能从归档重建完整请求文本。本审计证明被冻结版本与留证身份一致，不冒充进程命令行签名或供应商远程证明。

独立归档与 Git 原字节复核另见 git-byte-and-archive-verification.json；仅复用根已封存 ZIP 逐份展开核验，未重复复制 36MB 归档。整个审计新增 API、token、费用及真实 key 读取均为 **0**，没有监听端口、模型重试或 SQL 重放。

## 技术与设计导读

**1. 数据流。** 以 T4-041→T4-043 为例：T4-041 的请求预算 admission 建立 candidate PENDING，与 common permit 通过 hold/token/fence 对应；发送后读超时，没有 usage，就转为 UNKNOWN 而非退款。已有 3 旧+3 新 UNKNOWN 再新增这一项，累计占用到 ¥0.99376746。下一题 T4-043 尝试先预留 ¥0.10，candidate 直接拒绝，modelCalls=0；控制器保留它的 503 原响应再写不可重放中断。审计只读取这条链和原文件，以 SHA 和身份关联输出诊断，不再次触发链路。

**2. 技术抓手和防挤压。** 用 Python 标准库 SHA-256、严格 JSON、Decimal、逐行哈希链重建、ZIP/Git 原字节核验；SQL 安全判断为人工逐份结构审查，附窄范围词法交叉检查，后者不是通用解析器或正确性证明。来源通过 requestId/callNumber/promptSha256、candidate hold、common token、permitFence 与原题 ordinal 分开保留。没有评分权重或 Top-K：76 答保留全部，24 未问明确缺口；known 次数/费用保持单调，UNKNOWN 不被新结果覆盖。hash 证明字节身份，不能证明 SQL 安全。

**3. 效果和代价。** 核验 768 份原件、76 份逐题输出、72 份已知生成 SQL、228 份事件和 304 份回调快照；排除了“预留当实付”“超时当零费用”“SUCCESS 当判对”“不足100题报开发通过”的错误。UNKNOWN 每次按 ¥0.10 保守占用，使 4 次未知加上旧 3 项占用 ¥0.70，完整100题因此没跑完；这是保守会计的工程代价，不是模型消耗了接近1元，也不是新业务准确率收益。

**4. 权衡和面试追问。** 自动释放超时预留会把“供应商可能已处理、账单稍后出现”的调用当免费；追加预算/换 batch 补题会改变冻结条件并破坏完整分母。故保留 UNKNOWN、停止并封存，而不伪造结算。面试追问一：“超时为什么还算钱？”答：费用是否产生未知，不能写0，按reservation保守占用；用 usage/账单核销才能结算，此次没有执行该核销。追问二：“危险数0为何仍不通过？”答：只是已捕获76答本地路径观察，未知供应商内容未见，且24题未问、无完整score/P95，安全、业务与运行完整性必须分别验收。

**5. 用户配合。** 当前无需操作。后续若要处理未知账单或制定新方案，应另行明确核销证据与授权；本审计不授权继续本轮。用户需核对的是数据身份链、每次已知/未知成本，以及本次停在模型准入/会计层；不能把这个停止误归因成表、列、join 或业务口径评测失败。
