# 新登记控制器 revision-3：免费工程候选，新增付费仍待直接人类授权

旧 `batch-1/revision-2` 固定 `INTERRUPTED_NO_REPLAY`。本包没有补题、重放或改写旧分数/账目；没有读取真实密钥、启动供应商请求或访问未见题。主任务应先对本包执行免费 prepare、真实 JAR 本地替身 preflight 和独立工程审查，再仅就新增一次开发100题请求用户明确授权。

## 使用与固定路径

新入口为 `scripts/run_replan_stage_d_reregistered.py`，当前免费修订固定目录为 `reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-3`；独立新审查目录为 `reports/replan-stage-d-20261119/independent-review/reregistered-dev-1/revision-3`。只在新进程导入原冻结模块并替换全局路径/写盘/门禁，不编辑原文件。当前不由实现任务准备实际批次，主任务统一 prepare/真实替身预检/冻结/独立审查。

免费 revision-2 已由独立审查正式拒绝：新已知费用/新 UNKNOWN 可回退 seed，以及真实 C1 ready-only 的 JSON 重排被原字节门禁误杀。拒绝 decision SHA `d1050ff782c6ab98e96dae5db29bc1b65d8f3aa13c5e378162dba74a97179242`，独立 manifest SHA `af434a921708d5acbf5a402f9c733ff2e43ce60fbb506ca629caa708ef5b10b4`。新 prepare 核其原目录文件集/零 state/空 ledger/无人类授权、固定原 plan、预检与独立证据；旧 driver 以 Git commit `02364ab073360c352d94c698f69d59b0496fd5c1` 的原 SHA 记录，不改写旧 plan 迎合新源码。

第一份免费draft的B预检通过、F在就绪前因quota价格身份不符而失败：`CostQuota`的身份除模型/价格外还包含`agent.llm.base-url`，随机代理端口与旧59900不同。其原plan、零paid状态、原错误诊断和33份文件已由 `reregistration-first-free-failure.json` 封存，修订prepare先核该manifest、原plan、state/ledger均0且无授权/成功preflight，不覆盖原目录。原错误`inherited-quota-after-load.json`保留为失败现场，不能再据其`passed=true`解释为JAR加载成功。

```powershell
python scripts/run_replan_stage_d_reregistered.py prepare
python scripts/run_replan_stage_d_reregistered.py preflight
python scripts/run_replan_stage_d_reregistered.py audit
```

以上步骤无需付费授权且不读真实 key。prepare 先核独立中断审计结论、旧终态、401项中断 manifest、356项独立审计输入和冻结旧源码/plan/运行环境指纹，再CREATE_NEW新目录；存在就拒绝，不自动恢复。它复用已审核的公开 dev/formal/targets 参考原字节，重新从固定C1 JAR编译共同评分桥，并核 B0/旧D相同只读数据库身份。它不重新执行候选 SQL，不把旧68答复制进新轮次。

新正式运行态 `candidate-cost-quota.json` 从旧文件逐字复制，保留原 `budgetId/pricingIdentity/known/3 UNKNOWN holds`；不可变 seed 与 genesis-policy 由 plan 绑定。独立 `replan_quota_monitor.py` 观察可变运行态，原字节一致仅用于真正未开始（rounds 严格为空、requests0、calls空、未进入真实应用）；RUNNING/ready 后接受 JSON 顺序和小数等值变化。每次只捕获一份 runtime 原 bytes，原件以 base64 保存，SHA 与规范语义来自同份快照。

观察采用唯一 `candidate-quota-observations.jsonl`，每条事件含 sequence/前条SHA、原 quota bytes、语义、knowledge 叶变更、公共事件新增 SHA。只用 O_APPEND+fsync 追加，半条尾部/截断不恢复。checkpoint 固定最后序号/事件SHA/全 journal 字节数与SHA；同一 head 同时写入 state 和 send-ledger 的 `candidateQuotaObservation`。每次先重新读取并 SHA 校验整个 journal，原字节完全相同才复用解析状态；新进程必须重建全链。公共 permit/completion/raw 格式不变，位于原目录，每次串行观察重新读取和校验其实际原 bytes，不用 mtime 缓存。state/ledger未走事务的改写、孤儿/缺失发送事件、锚错位、checkpoint缺失或回滚均停止；多文件写到一半也停止，不自动补齐。

身份用 hold UUID + requestId + callNumber + promptSha256 + status 跟踪；UNKNOWN 一经观察，旧/新都不可删除或改内容。knownCalls 与 Decimal knownCost 分别取持久最高水位，允许调用数增加而费用零增。拒绝快照若已成功解析，也把新水位/全部未知内容与永久 stop 记录落盘后再拒绝，恢复旧 runtime 不能重新开闸。PENDING 不扁平化成 UNKNOWN：F proxy-before 用 Java compact `[[SYSTEM,text],[USER,text]]` 指纹和 callNumber 匹配唯一 PENDING，再将其绑定到不可变 permit token。after 时 Java 还可能未收到响应，所以允许仍 PENDING；消失必须有对应 common known completion，且已知调用/精确费用增量吻合。明确未发送取消还必须有 hash 固定的原 HTTP body/元数据、题号/requestId 与结构化预算拒绝；空闲时段分支要求完整原失败格式。凭据不足保守停机，UNKNOWN 不能走退款路径。`.10 PENDING → .00005 known` 会让 committed 正常下降，单调约束保护的是已知水位和 UNKNOWN，不是 committed 总额。

覆盖全部真实 B/F 的 verify、应用 enter/ready/exit、proxy-before/after、HTTP返回/异常；B 使用共享 quota 也观察，但不强行假设旧 B JAR 实现 candidate hold。锁序统一为 monitor 事务锁→gate RLock；audit/preflight/run-next服从同一独占 controller owner，跨进程无法并发改锚，崩溃锁不自动清理。发送前任何观察/绑定写盘失败均不能返回许可；发送后失败保留此前已落盘的未知公共承诺/PENDING，不伪造已知结算或释放未知费用。父 deadline 不刷新。

永久 quota stop 后只允许一次严格的中断封存：唯一原 RUNNING 轮次改 INTERRUPTED 并写 `INTERRUPTED_NO_REPLAY`，requests/round集合/其他字段不动；仍使用已封存拒绝快照、原 knowledge/费用/UNKNOWN/stopReason，不能读取还原后的 runtime 解套。新增轮次、增加题数、改成COMPLETED、清stop或改账本都拒绝。结构/锚已损坏或磁盘全坏时不能保证终态写出，仍靠已存证据与fail-close阻止继续；不声称所有故障都能完整落盘。

本地 journal/双锚可以发现部分回滚，无法抵抗整个目录、全部锚和完整历史被协调重写到同一旧状态；该保证需要外部可信签名锚，本项目没有声称提供。

新增 `replan_stable_proxy.py` 固定Java看到的代理入口为旧 `http://127.0.0.1:59900`，保持原quota身份，不迁移哈希、不清零预算。Windows listener显式独占 `SO_EXCLUSIVEADDRUSE=1`、`SO_REUSEADDR=0`；占用就失败，没有随机备用端口。它仅替换监听构造，继承冻结handler、转发、父deadline、解析和关闭逻辑；旧proxy源码SHA不变。新driver只在自身run/preflight调用期间临时绑定该类，调用结束恢复，不全局修改`os.replace`或旧proxy源码。helper本身也在plan指纹内。

真实免费 preflight 对 F 启动前，从seed逐字复制到 `offline-cost-quota.json`；本地合成调用只修改该离线副本。F进程结束后核预算/价格身份、旧3份UNKNOWN与已知值保留，写 `inherited-quota-after-load.json`；新诊断明确区分`applicationReachedReady/applicationBodyCompleted/inheritedQuotaDataPreserved`，只有就绪并正常完成预检体时`passed=true`，启动失败只能说数据仍保留。正式运行态不被免费预检更新。旧预检的“两份真实本机HTTP+98份显式合成Gold”仍只是接入测试，不是模型准确率。

## 两层账目与上限

旧历史固定：68题、应用预算70次、共同许可69个、传输尝试68次、用量已知67次、已知费用¥0.16312188。共同2未知保留¥0.20，共同配置占用¥0.36312188；候选应用3未知保留¥0.30，该层占用¥0.46312188。两层各自约束，不能相加冒充供应商账单，也不能借新登记释放UNKNOWN。

新当前send-ledger只存新事件，绑定旧manifest SHA；共同 `committed()` 在当前费用/预留上始终加旧¥0.36312188。当前新路线仍至多902题，累计970题；共同当前许可上限1870，由累计1940减历史**应用70次**得到，口径比只减69许可更保守。每题最多2调用，因此完整新902路线实际自身最多1804调用，所有上限共同生效。全局1002题/2004调用、¥1共享估价配额、¥0.10单次预留、15秒/2调用、单次SDK尝试、空闲窗口、开发与正式门槛、C1/JAR/公开参考均不降低。

真实付费summary保留 `currentRoundAccounting/currentAllRoundsAccounting/legacyAccounting/cumulativeCommonAccounting`，许可数与传输尝试数分开。免费替身summary使用 `syntheticCurrentAccounting`，明确本次新增外部调用/真实费用为0，不把模拟usage加到旧真实账单；旧费用仍单列存在。供应商完整费用仍受UNKNOWN影响，不能说整个历史批次0费用。

## 额外付费授权文件：精确契约

实际文件固定为新批次下 `human-paid-authorization.json`，本包**没有创建**。精确结构见同目录 `human-paid-authorization.schema.json`。它要求：

- `format="replan-d-direct-user-paid-authorization-v1"`，`approved=true`，`authority="DIRECT_HUMAN_USER"`。
- `scope="ONE_ADDITIONAL_FULL_DEV100_FIXED_C1_EXISTING_GATES"`；`planSha256` 必须是最终新plan的原字节SHA。
- `approvedAt` 必须有时区且不晚于当前时间；`directUserMessage` 是人类明确批准原文，`directUserMessageSha256` 核其精确UTF-8；`legacyManifestSha256` 为固定旧manifest身份。
- `limits` 与 schema 的100/902/970/1940/¥1/¥.10完全相等；`acknowledgments` 必须完整承认旧中断不重放、历史68/70、已知费用、共同¥.20/候选¥.30继续持有、两层不相加、未见仍锁、工程接受不能代替付费授权。

这是由可信主任务根据真实人类回复生成的授权证据门禁，不能用代理输出或独立审查决定代填；本地JSON不是密码学的人类签名。没有额外默认授权来源，没有自动创建授权的CLI。

`run-next` 在进入原付费流程前先核授权；之后继承原预检/独立审查/完整分母/时段/配额门禁，在原 `real_key()` 被调用前再次核授权并CREATE_NEW保存消费指纹。未获授权不会写RUNNING、增加题数、拿key或发模型。消费后修改该授权也拒绝。新完整DEV_F通过后，T3-002只复用**新完整开发轮唯一响应**，不从旧68答或不同来源挑高分；原目标/正式/新未见的所有停止条件保持。

## 免费验证数字与限制

历史34/34、38/38保留；revision-3 旧门禁定向39/39（`directed-tests-4`）、最终monitor定向48/48（`quota-monitor-checks-5`）通过，436/436既有固定文件原 bytes 不变。monitor新增6项验证严格终态封存及禁止题数/轮次增长、完成/恢复/改身份、终态写失败；中间 `quota-monitor-checks-4` 的6个真实失败原件保留，其原因是新增终态代码重复加载时误把正在合法提交的permit当孤儿，已经修正为原单次事务加载路径，未放松事件校验。所有纯定向检查真实供应商/key/JAR均0。主任务最终同版组合回归、真实C1替身集成与独立审查仍待完成。

性能反例完整保留 `quota-monitor-performance-2/`：小文件 delta 链在102题/204calls/1735观察约4.91MB时首观测11946ms、后586/625ms；902题/1804calls/15335观察约43.52MB时首观测95515ms、后6128/5742ms。该结构不能准入，改成唯一追加 journal。`quota-monitor-performance-3/` 给出同规模日志/公共证据实际全字节复核：102题295.400/189.221/180.258ms；902题2589.395/1049.263/1043.110ms。首轮仅“新实例、无解析缓存”，文件此前构造写过，不能称受控OS冷盘。902首轮分项：journal读+SHA45.131ms、链解码1370.975ms、公共原件读取738.894ms、公共SHA/结构106.408ms、追加/锚持久45.071ms。warm后每观察仍约1秒，发送前3次、after2次可占约5秒，两调用约10秒，15秒父时限不刷新；这不是性能门槛已过，也不证明应用P95/准确率。完整n.verify旧证据核验还计入整轮1620秒，由主任务另实测。

性能报告绑定当时精确源码SHA，早于后补的严格终态封存分支；正常观察与全部原bytes核验机制保留，没有用新源码SHA冒充旧实测版本。性能2/3所有原目录仍完整留在磁盘，Git以 `revision-3-implementation-delivery/quota-monitor-performance-{2,3}.zip` 和对应逐文件SHA清单、原verification入库，ZIP是原目录文件的归档，不能称ZIP本身就是原始单文件。归档对应文件数、字节数、SHA与逐件回读结果见同目录 `delivery-manifest.json`；原目录大体积夹具未逐文件加入Git，也未删除。

真实监控链免费集成入口 `scripts/check_replan_real_quota_fixture.py --output <candidate内全新绝对目录>`：真实冻结 C1 ready-only 0题，另一个基座仅公开 T1-002 经真实应用、真实 monitor before/after 与本机合成上游已知结算。继承原 UNKNOWN 不动、不评分、不读旧paid答案、不产生人类授权、原实际注册前后不变。每个基座退出 JVM 后另启独立 Python `--validate-existing` 全链冷重建，保存 child 原 stdout/stderr/验证报告；fresh instance 与真正跨进程分别标记。脚本由主任务统一运行，存在源码不代表已通过。

固定listener另有7/7真实Windows检查，5次仅loopback合成上游请求；新增外部供应商API/真实key读取/费用仍为0。B→F同进程关闭后立即重开、两个子进程先后关闭重开均成功；观察到59900的两条TIME_WAIT，独占仍可按本次已验序列重新绑定。活动proxy独占时第二proxy被拒；独立进程占用时子进程WinError10048，许可/完成回调均0，无线程泄露、无备用端口；实测socket exclusive=1/reuse=0。原handler/forward/deadline/drain/shutdown方法身份相同。原始探针3/3及完整7项证据保留于 `stable-port-initial-probe.json`、`stable-proxy-checks-1/`。这些是本机本次状态证据，不承诺所有未来TIME_WAIT/进程占用都会可重开；失败仍关闭流程，不提高地址复用权限。

覆盖授权缺失/错误plan/非人类authority/错原文哈希/超限费用/未来时间/缺UNKNOWN承认/消费后漂移，授权缺失在原run前拒绝，当前同ID不与历史混合，旧共同费用占用，新许可上限扣应用70，候选quota全字节继承/禁止清零/层额度独立，helper默认参数接入、同temp同bytes重试只产生一个permit、持久失败不返回许可也不释放预留、新写入禁止触及旧批次、免费prepare/preflight控制流、F离线seed复制及关闭后保持检查、合成usage不进入真实累计。

有界helper只重试同一已fsync临时文件到同一目标的 `os.replace`；新入口仅用默认0.5秒退避重试预算/12次尝试，不暴露可调参数。同步 `os.replace` 本身不能强杀，不能保证绝对wall≤0.5秒；父15秒deadline不刷新，proxy在permit后仍检查过期并拒绝发送，整轮/发送时段门禁保留。没有HTTP/模型/题目重试。此修订的真实JAR免费预检和独立成套审查由主任务接着完成，不能把上述套接字或替身检查宣称成它们已通过。

## 技术与设计导读

以新DEV_F里的T1-002为例：控制器从冻结开发参考拿题，新ledger的第一次许可仍是attempt1；旧同ID许可留在旧目录，不参与新题的2次上限，却通过固定历史占用继续约束全局预算。before先写新唯一permit，再把同bytes新账本临时文件fsync并有界replace；成功才返回proxy，父deadline已过则proxy仍拒绝上游。真实HTTP答案进入新100题完整分母。新轮次任何中断仍封存，不补题。

技术抓手是已审核原控制器复用、Python进程局部绑定、不可变旧manifest/独立审计SHA、独立新事件ledger、原子文件替换helper、预算seed与可变运行态分离、固定独占loopback入口、匹配精确plan的双重人类授权门禁。数据出身用旧/新目录及manifest/planSHA区分；没有打分权重、TopK或失败过滤，不能靠选择性排除旧账目或新失败题改善分数。

拒绝直接续跑旧轮或复制旧events，因为会破坏中断不重放承诺、冲突同题attempt，并掩盖真实分母。拒绝把工程审查accepted当付费次数授权；拒绝重置quota，因为未知费用仍有风险。面试追问一：新ledger空是否清零？回答同时指出旧SHA固定、committed加旧占用、当前调用限额扣历史应用70和quota原bytes继承。追问二：本地replace重试会多发模型吗？回答retry循环只有原子替换，许可持久化成功前proxy无发送入口，deadline仍由原代理约束。

价格身份包含代理地址会令随机端口破坏恢复，但本轮禁止改生产/JAR或迁移旧账目，因此选择固定原入口。没有用SO_REUSEADDR抢占监听，因为已有owner必须安全拒绝；代价是59900不可用会令流程中断，不能保证任何未来运行都成功。候选层继承占用¥0.46312188后余¥0.53687812，预算不变，不能承诺完成开发或后续正式。

用户当前无需启动外部服务；最后需要的只是对完成免费预检与独立审查后的新付费100题作明确决定。需要核对的是数据流的旧/新身份、三种调用数字与两层费用占用，以及本地持久化故障与生成/评分错误的归因。
