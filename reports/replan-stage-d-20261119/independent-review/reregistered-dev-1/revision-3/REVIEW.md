# Revision-3 独立工程审查：安全修复成立，当前同步开销不准入

结论：**accepted=false**。原 revision-2 两个 P1 已修复，但新观察器把反复全量读写放进同步调用路径，在允许的最坏存储规模下严重挤占原 15 秒预算。166 项测试全绿，不能抵销这个运行代价。

精确 plan SHA256：`9b32def424104c383d8088cb538ec54698f9bda12bbbb45a3d6472b65db5d41c`。实现提交 `b178a4b2fe454cf5b5df90e4f62de665fce1fe6c`；根任务免费固定包提交 `08927fe86f95856700a5beff5b60de3eaeec5a5b`。被审 helper SHA `20871566cf63cee846e5e7813f23d267fe22e3340f458258cfd7767286d2db1a`；driver SHA `7fb43d4450b5db061ac544694ed5a7b0207399dd18b2d0595d51920b2a6da277`。

本次仅写本独立审查目录。实际批次只做原字节、模式与身份检查；所有会追加观察的完整 verify 均在副本运行。未修改源码、计划、历史决策、原付费答案或账本，未读取真实模型凭证、未创建真实授权、未执行实际 run-next、未触达供应商或新未见数据。旧 68 份答案/SQL 只核 SHA，不逐题解析。

## 新 P1：同步全量观察缺少足够的运行预算余量

来源：新驱动 `begin/end/before/after/http_json` 观察钩子；`scripts/run_replan_stage_d_reregistered.py:508、537、553、565`；全量读取路径 `scripts/replan_quota_monitor.py:265–328`。

一次 before 依次观察 PROXY_BEFORE、共同 ledger 持久化、PERMIT_BINDING，共三次；一次 after 包含 ledger 持久化、PROXY_AFTER，共两次。每次完整读取并重算 journal SHA、读取/核对全部共同原始事件、核状态与 ledger，再 fsync 追加日志并替换 state、ledger、checkpoint。解码缓存仅省解析，不能省这些全量 I/O。

独立实验复用固定包中已逐字节验证的 **902 题 / 1804 次调用**合成末态，复制到本审查目录，在这个副本上调用真实生产 SendGate 的 begin、两次 before/after、HTTP_RETURN、end。Java PENDING/结算由夹具模拟；供应商、网络等待、SQL 执行延迟均为 0，未运行模型。结果见 `tail-callback-performance.json`：

| 步骤 | 耗时 ms | 所在位置 |
| --- | ---: | --- |
| QUESTION_BEGIN | 1085.195 | 外部父 deadline 已开始，Java HTTP 尚未开始 |
| BEFORE_1 | 3237.477 | 返回发送许可之前，阻塞上游发送 |
| AFTER_1 | 2122.711 | 冻结代理回传上游完整 body 之前 |
| BEFORE_2 | 3187.464 | 第二次发送许可返回之前 |
| AFTER_2 | 2202.065 | 第二次上游 body 回传之前 |
| HTTP_RETURN | 1149.090 | HTTP body 已读取与落证后，包装器返回前 |
| QUESTION_END | 1067.392 | 外层 finally，HTTP 读取完成之后 |

两次 before/after 的实测区间共 **10757.916 ms**，完整 begin→end 共 **14069.733 ms**。回调区间还包含夹具模拟原子配额变化的极小文件写入；表中四次独立回调计时合计 10749.717 ms，差额 8.199 ms，不把这点差额算作供应商延迟。返回许可 2 个，无异常。这证明的是同步路径实际代价，不是把一次 observe 粗略乘五得出的推算。

不能把所有 14.07 秒硬扣进 Java 的 15 秒：Java 请求预算从 HTTP 内部开始，begin 在它之前；HTTP_RETURN 和 end 在原 `httpReadCompletedMonotonic` 之后，也不进入原报告的 wallLatencyMs。它们仍延长控制器与整轮运行时间。相反，before 确实在发送前；冻结 proxy 在 `_after_send` 返回后才执行 `_reply`，所以 after 也确实挡在 Java 收到完整模型响应之前。两次模型调用的这四个同步回调自身就约 10.75 秒，外部父 deadline 还包括 begin。真实模型等待、两次生成、SQL、安全校验及纠错的余量明显不足；不能通过不计 HTTP_RETURN/end 来掩盖这一点。

本审查的工程判断是：**当前固定方案不具备值得付费验证的预算余量，拒绝运行准入**。这不是宣称真实模型 P95 已失败，也不是声称所有题都会超时。902/1804 是全路线每题双调用的最坏存储形状，不是实测平均；只有一次独立尾部路径计时，不外推超时率。第一轮 100 题时更小的开销也不能证明冻结的全路线已经适用。修复应减少同步重复全核/重复锚提交，并保持同样身份、拒绝和落盘边界，在免费固定候选上重新测量；本审查不实施修复或放宽原门槛。

性能原件全部保留。根任务末态单次 observe 为 **2420.548 / 1093.314 / 1085.632 ms**；本审查新观察器在新复制末态上的首次重构与 observe 为 **19844.024 ms**，随后 SendGate 打开 1881.359 ms，二者在题预算外。第一次没有已解码缓存，但文件此前构造/复制过；两组均不是受控操作系统冷盘测试，不能把 19.84 秒称为稳定冷启动 P95，也不能删掉这次较慢样本。

副本完整 verify 三次为 **15403.786 / 926.245 / 995.944 ms**：第一次包含只读 Docker 数据库快照；后二次 database=False 才对应逐题前的主要校验范围。逐题前 verify 在题计时外、整轮 1620 秒上限内；既不能全部记到 Java 15 秒，也不能假装没有成本。未提高任一时间上限。

## 原两个 P1 的修复核验

**DR2-CANDIDATE-QUOTA-ROLLBACK 已解决。** 新 knownCalls 与 knownCost 分别相对最近持久观察值不倒退；新旧 UNKNOWN 按 hold ID 与完整内容持续保留。新监视器进程从 journal 重建这些高水位，再以 checkpoint、state 与 send-ledger 三个同链头位置核对，不能退回旧 seed 掩盖新支出。

独立反例使用真实种子的副本：新增 0.50 元已知费用、100 knownCalls 后恢复旧种子，由全新 Python 子进程拒绝 `Latest known quota rolled back`。另一副本新增 UNKNOWN 后删除，由全新子进程拒绝 `Observed UNKNOWN removed/changed`。两种拒绝都持久化 stop；随后恢复运行文件也不能继续。共同层和候选层仍是两个独立承诺公式，未用一层替代另一层。

**DR2-FIRST-START-EXACT-SEED-FALSE-REJECT 已解决。** exact seed 只在 runtimeStarted=false、rounds 空、requestsStarted=0、共同 calls 空的真正 pristine 状态要求。APPLICATION_ENTER 先标记运行来源，之后的原字节变化由语义身份和高水位约束处理，允许 JSON 条目顺序、小数等值变化。

根任务真实冻结 C1 JAR 原始证据已按 Git bytes 核验：ready-only 0题保持 67 knownCalls/0.16312188/3 UNKNOWN；known-one 的 1 次本机替身调用后为 68/0.16317188/3 UNKNOWN，新增 0.00005 元仅是合成夹具。两份基座均有原独立 Python 子进程校验。本审查没有重复付费或重启 JAR，而把这两套原件分别复制到自有目录，使用新的真实观察器重构通过 **75.172 / 66.898 ms**；源文件集及全部 15/21 份 bytes 前后不变。小规模通过不等于 902 题规模运行合格。

## 边界核验与数字

- 单次独立六模块组合：**166/166**，0失败、0错误、0跳过，**25.622秒**。包括 monitor 的 48 项、driver 的 39 项以及冻结控制器、proxy、durable write、stable proxy。三个 EVIDENCE 全部预先指向本审查的全新绝对目录；合成授权仅在这些夹具中。
- 新计划 **756** 份绑定全部逐项 SHA；**108** 份非 runtime 与 Git 原字节匹配，**648** 份 runtime 核验；预检 **44** 份 SHA/Git 原字节匹配。
- 根免费 delivery manifest **597** 份原字节与 Git 一致；性能归档 **6041** 份展开内容逐份 SHA 一致。旧付费 manifest **401** 份、旧独立审计、第一免费失败与 revision-2 拒绝原件完整保留。
- 在组合套件之外，另做 **7/7** 独立场景：两个跨新进程回退反例；两 PENDING 同时按精确两次 usage 结算成功；只增加一次 knownCalls/成本却删除两份 PENDING 被拒；分别在 state、ledger、checkpoint 三个写入点故障，全部在新观察器重启时拒绝。
- 两个无真实授权入口均在旧 key reader/继承付费入口之前拒绝；密钥读取0、继承付费入口0、入口尝试目录 bytes 变化0。
- 实际 batch 全文件集与 bytes 前后核对不变；state 仍0请求/空rounds/无terminal，共同calls空，候选配额仍为旧种子精确bytes；真实人类授权和 consumed seal 均不存在。

PENDING 不能当 UNKNOWN 处理。正常 PENDING 结算后，0.10 元保留可变成实际已知小额，承诺额合理下降；因此只要求 knownCalls、knownCost 分别单调，不强行要求 committed 总额单调。移除某个 PENDING 要有绑定的 permit token、完整原始响应及已知 usage，计数与费用增量必须匹配；两笔同时消失不能只拿一笔结算抵掉。cancelUnsent 还要有相同 questionId/requestId、调用计数、完整已封存 HTTP 原 bytes 与特定未发送结果，任何已有 permit 均不能退款。已观察 UNKNOWN 永远不能借这条路径释放。零价 usage 可以只增加次数，不迫使成本增加。

单 journal 由 O_APPEND/fsync 追加规范记录，记录前项 SHA、原 runtime bytes/base64、语义快照、knowledge delta、共同事件 delta。每次完整 SHA 通过才复用解码缓存；不是只看 mtime/size。新进程重放完整链；checkpoint 缺失、尾部截断、前缀变化、状态/ledger 锚不一致、事件孤儿、部分多文件提交均拒绝，不自动重建或恢复。before 落证失败不能返回许可；after 落证失败时旧共同 permit 仍按 UNKNOWN 保留，孤儿完成原件导致继续被拒。当前实现有明确停止能力，但这些验证强度也正是其同步代价的来源。

已有 quota stop 后只允许把唯一原 RUNNING 轮次封存为 INTERRUPTED 与终态 INTERRUPTED_NO_REPLAY，保留请求计数、原状态身份、其他轮次和所有费用知识；不得完成、加题、改账本或清 stop。若持久层自身损坏，连终态都可能无法写出，仍须停止，不得称作自动恢复。

本地链与多锚只能发现局部回滚或尚有锚存活的部分重写。没有外部可信签名锚，不能抵抗整个工作区、journal、checkpoint、state、ledger及所有原事件被协调恢复到同一历史。这个限制已在 POLICY 中明示，不能拿 SHA 字样冒充外部防篡改保证。

## 技术与设计导读

以一条公开请求进入 DEV_F 为例：控制器先核精确计划、历史账目、授权与预检，Java 在模型调用前创建 PENDING，记录 hold ID、requestId、callNumber、promptSha256。代理 before 把该 hold 与当前 questionId、共同 permit token 绑定，只有持久观察完成才返回许可；after 保留供应商原始响应与 usage；Java 再结算本次 hold。下一观察核验次数/成本增量及 hold 消失的证据。UNKNOWN 的来源身份一直保留，不把它们压成一个数量猜测出身。输入、SQL、评分器、原门槛均未调整。

核心组件是冻结 Java CostQuota、冻结共同 SendGate/transport、新 QuotaMonitor、规范追加 journal、checkpoint 与 state/ledger 双锚。数学约束是两层分别使用 `knownCost + 0.10 × outstanding holds`，新 known 次数和费用相对最近观察值单调，UNKNOWN身份持续，PENDING按凭据结算。没有新增排名/权重/TopK，也没有模型质量数字。本次效果是原两条安全反例失效；代价是末态单次 before约3.2秒、after约2.1秒，造成新的运行阻断。

替代方案对比：只看旧 seed 下限漏掉新增费用；全程逐字节相等误拒正常序列化；把所有 PENDING 永久保留会重复扣正常已完成调用。独立历史与逐身份状态转移是正确方向，但每个回调重复全核、重复锚提交把安全代价直接塞进模型预算，当前实现需要减少冗余事务。优化不能变成跳过 UNKNOWN、隐藏失败或放宽15秒。

面试追问一：“安全校验全绿为什么还拒绝？”——用零模型等待下约10.75秒同步回调的原始计时回答：可恢复的正确状态不代表系统能在约束内完成请求；安全和可运行性必须同时满足。明确这不是实际P95，也不删最坏规模样本。

面试追问二：“直接缓存文件不就快了？”——只能在原字节身份仍可验证时复用解码；只信mtime/size会漏回退。可以合并有相同安全顺序的观察事务和锚提交，但必须重新证明发送前持久化、逐 PENDING 结算、UNKNOWN保留及部分写失败拒绝，不能用性能需求借口移除防线。

当前无需用户配合外部服务，也不请求付费授权。应继续核对三件事：数据如何从 hold 对应到 permit/usage、每次费用与延迟分别来自哪一层、失败是控制器预算还是模型/SQL。原已知费用0.16312188元、common未知0.20元、candidate未知0.30元全部不变，不能把两层占用相加当账单。

## 决策边界

本拒绝仅针对本精确 SHA 的 revision-3 全路线工程准入。已解决的两个 revision-2 P1与历史拒绝均保留，不回写原结论；原68问不拼接新分母、不续跑。没有新的准确率、完整模型P95或供应商成本，阶段2及整体目标仍未完成，新未见继续锁定。即使未来工程接受，仍不等于直接人类付费授权。
