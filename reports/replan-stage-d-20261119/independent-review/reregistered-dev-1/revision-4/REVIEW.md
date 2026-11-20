# Revision-4 独立工程审查

结论：`accepted=true`，仅接受当前固定身份的工程准入；`paidAuthorized=false`。没有接受模型准确率、实际 P95、阶段二验收、阶段三恢复或 unseen 使用。实际人类付费授权及 consumed seal 均不存在。本审查没有创建任何授权。

902 规模的四次同步回调为 **1014.717 ms**，严格 `≤1000 ms` 内部目标 **失败**，超出 14.717 ms / 1.4717%。不能四舍五入成过关。接受的理由是原阻塞项“同步配额观察本身吃掉约 10.75 秒”已被实质消除：本次回调约占原 15 秒限制的 6.76%，完整 begin→HTTP/end 为 1362.642 ms，满足内部 1500 ms 目标。该判断只支持工程准入，原 15 秒请求、1620 秒轮级、准确率和 P95 门槛均保留，真实模型链路仍可能失败。

## 固定身份与范围

| 对象 | 固定身份 |
|---|---|
| implementation commit | `495ebc3af03951e90df8a45786789421d861c9c3` |
| root frozen package commit | `df381efc897fa7f455fbb8e882307c93f6f5eaa8` |
| plan SHA-256 | `da6c890b4149b14075dd28bc81281af77691b2c293ed8606778378f18a80a4fe` |
| helper SHA-256 | `35b86ea00752c4e4a986480c8be266e87345c0617bd041214cbf17f53e7cb9db` |
| driver SHA-256 | `bda005614aa90c7f872f9451d0654a958204b2d4f5f807038d999245892d1d4e` |
| monitor tests SHA-256 | `482151cb37335e83808134cfb8921a6ea73c5712785801a53498cf91ae83173d` |
| root delivery manifest SHA-256 | `84a21edeeb92adef4ade1b2dc13f0ff7c48c868e3a8ff0b2b4dc63f848e56b29` |

写入范围仅为本 revision-4 独立审查目录。源代码、冻结包、实际 batch、历史判断和付费证据均未修改。实际 batch 的 **380** 份原字节与文件集合前后完全一致，requestsStarted=0、rounds={}、terminal=null，共同 calls/rejections/breaches 为空，candidate 保持精确历史种子。完整 `verify` 只在独立副本执行，因为生产 `verify` 会合法追加观察锚。

## 证据与数字

- 同版组合 **190/190**，失败/错误/跳过均为 0，耗时 **39.3519366 秒**。三个模块的 EVIDENCE 均先重定向至独立新绝对目录；真实本机 59900 测试完成并释放。不是仅引用根任务测试日志。
- 独立补充 **7/7**：首 A 截断、首 A 缺失、after 首 A 损坏、有效 A 后 B 损坏、A 的 PENDING 在 B 成 UNKNOWN、旧同名 raw 篡改在下一 FULL 拒绝、原拒绝只提交一次。
- plan 绑定 **1140** 份文件；其中 **168** 份非 runtime 与 Git 原字节一致，**972** 份 runtime SHA 一致；preflight **44** 份 SHA/Git 原字节一致；root delivery **74** 份 SHA/Git 原字节一致。
- root 三份归档逐项展开核验：final-suite-1 **770** 份（旧 helper 的 187 项，保留原身份）、final-suite-2 **805** 份（最终版 190 项）、真实 JAR 本地集成 **39** 份。旧 401 份付费 manifest、此前失败及拒绝身份只核哈希，不读取旧 68 题答案或 SQL。
- 独立副本完整 verify：首次含本机 Docker 数据库只读身份快照 **18365.838 ms**，随后 database=False 为 **1592.079 / 1606.453 ms**。此开销不计入 Java 单请求时钟，但仍占轮级时间，不能忽略。
- 缺失授权的 run-next 入口和 guarded key 两道均拒绝；key reader=0、继承 paid route=0，副本入口尝试原字节变化=0。
- 根最终版实际 JAR 证据经独立归档/原字节核验后，复制到独立目录，由新生产 monitor FULL 重建：ready-only **83.571 ms**，known-one **80.180 ms**。独立审查没有重启 Java。两份来源分别 **16/22** 文件，前后原字节不变。
- 本审查新增供应商调用 **0**、token **0**、费用 **¥0**、真实 key 读取 **0**；只使用合成、loopback 和本机服务。

### 最终版性能的唯一独立样本

| 指标（时间单位 ms） | 102 规模 | 902 规模 |
|---|---:|---:|
| 初始 journal 字节 | 4,865,175 | 43,473,492 |
| 初始 observations | 1,718 | 15,318 |
| 新实例首次 FULL | 287.895 | 2682.884 |
| gate open FULL | 263.426 | 1867.502 |
| admission（Java 时钟前） | 69.472 | 218.059 |
| begin | 6.150 | 6.956 |
| before1 / after1 | 91.300 / 106.165 | 242.157 / 272.797 |
| before2 / after2 | 85.549 / 95.077 | 271.757 / 228.006 |
| 四次回调合计 | **378.091** | **1014.717** |
| HTTP_RETURN/end | 119.098 | 284.738 |
| 外层幂等 end | 6.681 | 6.056 |
| begin→HTTP/end 整段 | **574.452** | **1362.642** |
| 题后 FULL | 162.539 | 1069.547 |
| 四回调 ≤1000 | true | **false** |
| 整段 ≤1500 | true | true |

每个规模仅生成并测量一次，没有重跑挑样本。合成数据每题两次调用，代表累计尾部存储形状，不代表实际平均 payload；OS 缓存未受控；没有模型、网络或 SQL 延迟，不能叫实际 P95。计时段落恰好 **5** 次持久化事务。begin/外层 end 没有新增 commit；parts 中这两项继承前次组件记录，不能把它们当独立组件分解重复求和。

冻结代理 `_after_send` 先于 `_reply`，因此 before/after 实际阻塞上游发送和响应交付。begin 在 Java HTTP 时钟前；HTTP_RETURN/end 位于原 `httpReadCompletedMonotonic` 后，虽未包含在旧 wall 指标，仍占 controller/轮级时间。不能把完整整段机械地从 Java 15 秒减去，也不能把该开销冒充模型延迟。

此前 performance-7 属于 helper `3137e6...`，902 回调 1070.506 ms、整段 1365.076 ms，仍是旧证据；没有重标为本次最终 helper。独立最终结果单独位于 final-source-performance.json。

## 原阻塞项与当前边界

revision-2 的 `DR2-CANDIDATE-QUOTA-ROLLBACK` 与 `DR2-FIRST-START-EXACT-SEED-FALSE-REJECT` 维持已修复。revision-3 的 `DR3-SYNCHRONOUS-QUOTA-OBSERVATION-BUDGET` 在当时约 10.75 秒回调、14,069.733 ms 整段下拒绝是正确判断；旧原字段 callbackBeforeAfterOnlyMs 为 **10,757.916 ms**，四个展示分项求和为 **10,749.717 ms**，两者相差 8.199 ms，不能混写。原 decision 和提交不改。本版同类合成单样本降至约 1.015 秒回调、1.363 秒整段；这不是有统计置信度的加速比。

当前接受明确包含以下边界：

1. fast 每笔仍全字节重哈希 journal、核当前配额、checkpoint/状态/ledger 锚、ledger 语义、精确事件目录集合和新事件原字节。不是 mtime/size 捷径。
2. **历史同名 raw 内容篡改延迟到下一次 FULL 检出**。fast 使用已经 hash 锚定的 ledger，不从被改旧 raw 重新推算负债。FULL 在每题前、轮末、首 owner/新进程使用时执行；下一题前拒绝已经独立验证。这是接受的有界检测延迟，不是完整逐回调 raw 验真。
3. fast capability 只存在内存，绑定 monitor 实例、controller owner 的对象身份、plan、round、admission fence 与 head；owner 退出即失效，新进程不能继承。
4. 首 A JSON 损坏或缺失且没有有效快照，poison 并以 CREATE_NEW sidecar 保存失败身份/已有无效 bytes；修复 runtime 后同实例及新实例仍拒绝。after 不能凭失败释放原 UNKNOWN 或伪造 completion。
5. 有效 A 后 B 损坏时，停止记录保留 A 中新增 known 高水位/UNKNOWN 和 B 的无效 bytes，不得因 B 不可读而丢失已观察负债。before 不返回 permit、不泄漏绑定。
6. 多文件部分提交、journal 截断或三锚不一致均 fail closed，无自动补写、修复、重建或付费重放。磁盘完全不可写时，**不能保证 sidecar 成功落盘**；内存 poison、controller 的运行/中断与 no-replay 边界仍保留，不能宣称持久化已经成功。
7. 没有外部签名锚；攻击者协调回滚整个目录及其全部锚仍在防御范围之外。不能把 SHA 链宣传成防管理员篡改或分布式事务。

## 技术与设计导读

### 1. 一个具体请求怎样流转

以冻结 reference 中的 `T1-002` 为例：controller 在每题前 FULL，核 plan/题目引用与历史原字节；state 的 requestsStarted 只能 +1，并持久化 `{planSha256, round, ordinal, questionId, questionSha256}` admission。begin 只消费一次该 admission，建立本次 activeQuestion。题面、顺序、轮次或 fence 错配均拒绝。

应用准备第一次模型请求时，before 捕获 candidate 配额快照 A，再调用继承的共同 private permit，再捕获 B。两份快照按出现顺序折叠，既不能只取 B，也不能把共同层和 candidate 层混为一份余额。共同 permit、candidate pending 来源与当前问题 fence 绑定，在 journal、checkpoint、state/ledger 锚持久化完成后才返回发送许可。

响应原 bytes 保存后 after 一笔事务观察 known/UNKNOWN 结算，代理随后才交付响应。若本题需第二次模型请求，重复 before/after；超过两次仍按原规则拒绝。HTTP finalizer 保存证据，end 立即关闭继续发送，finish_http 在一笔持久化中观察最终负债并关闭 activeQuestion。迟到 after 通过 `permitFences` 找回原问题身份，不能挂到下一题 activeQuestion。正常输出仍是原应用结果及原始证据；出错输出为持久 QUOTA_STOP/Refused，不能自动续跑。

真实 JAR 集成的 ready-only 只启动 ready，没有题目，重建为 known67 / ¥0.16312188 / 3 UNKNOWN。known-one 只执行公开 T1-002，向 loopback 合成模型请求一次，重建为 known68 / ¥0.16317188 / 3 UNKNOWN；**¥0.00005 增量是合成计费数据，不是真实新账单**。

### 2. 核心技术、身份和数学

核心为 `scripts/replan_quota_monitor.py` 的 FULL/fast 观察、SHA-256 链/锚与事务持久化；`scripts/run_replan_stage_d_reregistered.py` 的 admission、回调 gate、owner 和 finish_http；冻结共同代理与原 candidate Java 配额实现继续使用原规则。独立工具通过 unittest/mock、Path 原字节 SHA、Git cat-file 和 ZIP 逐项展开核验取证。

来源追踪不是扁平列表：共同 permit 身份、candidate budgetId/pricingIdentity、pending/UNKNOWN 原身份、admittedRequest、activeQuestion、permitFences、journal head 各有职责。旧历史 seed 只在真正 pristine 时要求原字节精确匹配；运行后的合法序列化变化可以语义等价，但 budget、定价、known 高水位与 UNKNOWN 身份不能倒退。

这里没有 Top-K 或权重打分，防挤压依靠保守会计不变量：known 次数与费用分别维持单调高水位；UNKNOWN 每项按 **¥0.10** 保留，只有精确的原身份结算/可证明未发送条件才能释放。零费用调用仍增加 known 次数，不能只比较金额。A 看到新增负债后 B 回退/损坏仍保留 A；不能用最后一次可读快照覆盖先前负债。

历史已知账单仍为 **67** usages / **¥0.16312188**。共同层 2 UNKNOWN，held ¥0.20、commitment ¥0.36312188；candidate 层 3 UNKNOWN，held ¥0.30、commitment ¥0.46312188。两层是对同一风险的独立约束，**不能相加成已付费用**。旧 batch 仍 INTERRUPTED_NO_REPLAY，无完整 DEV100 得分或 P95。新路线 902 题加旧 68 题为 970；当前共同 permit 上限 1870 是累计 1940 减旧 application70，选定新路线最多 1804 次模型调用。原全局 1002/2004、¥1/¥0.10、15 秒、每题两次、SDK 一次尝试及窗口/晋级门槛未变。

### 3. 收益与仍需观测的数据

回调事务合并和已校验解码缓存，将尾部同步开销从旧单样本约 10.75 秒降到约 1.015 秒；保留每次 journal 原字节哈希、新事件验真和失败停止，避免用删校验换速度。190+7 项通过说明被测工程边界成立；不能推导准确率提高、费用降低或 P95 达标。

用户后续必须继续核对三件事：问题的 admission→permit→响应→结算如何对应；每次调用的 token/时间/真实费用；失败来自配额/证据链还是表、列、join、口径。当前免费审查只覆盖前两者的工程基础，不提供模型层错误归因成绩。

### 4. 替代方案与面试追问

每回调完整重读全部历史 raw 的实现简单但已测到约 10.75 秒同步开销；直接删校验或凭 mtime/size 缓存会漏掉同名修改。当前方案保留全 journal 哈希与新事件同步核验，接受旧 raw 在下一 FULL 检出。异步落盘虽更快，却可能在 permit 已返回后才发现证据失败，故未选。SQLite 单事务能缩小多文件提交缝隙，但会扩大本次冻结协议和迁移范围；当前只承诺 fail closed，不冒充原子恢复。

- 问：缓存会不会让已花费用消失？答：缓存凭据绑定实例/owner/plan/round/fence/head，每次仍核 journal 全字节和锚；known 高水位及 UNKNOWN 原身份来自已锚语义账，不从旧 raw 重算。A/B 逐次折叠，零费用次数也单独受约束；下一 FULL 检测旧 raw 篡改。主动交代延迟检测和整目录回滚边界。
- 问：测试全绿为什么不能直接宣布 P95 合格或付费？答：这里只测合成存储和本地集成，902 四回调严格 1000 ms 还失败；没有真实模型/网络分布。工程 decision 与人类付费 authorization 是不同身份，两道缺授权拒绝已经验证，原业务门槛必须由未来合法运行验证。

### 5. 用户配合事项

本独立审查无需外部操作。若进入后续付费批次，必须由用户对精确方案另行授权；本 accepted=true 不能代替该授权。未见数据仍锁定，T3 仅能使用新的完整 DEV_F 结果，不能复用旧 68 题凑验收。

## 证据封存

本目录 source-evidence-manifest.json 绑定全部可提交审查证据，性能原始目录保留本机并以 ZIP 逐份 SHA/集合往返校验后提交；顶层 decision.json 单独绑定 manifest。只排除本目录顶层 manifest/decision 与被归档原目录，嵌套合成 decision.json 必须纳入证据。历史拒绝判断及根 delivery 之外后加的只读文件未被冒称为根冻结 delivery 内容。
