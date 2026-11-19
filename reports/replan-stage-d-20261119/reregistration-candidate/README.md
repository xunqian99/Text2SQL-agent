# 新登记控制器候选：免费接入完成，新增付费仍待人类授权

旧 `batch-1/revision-2` 固定 `INTERRUPTED_NO_REPLAY`。本包没有补题、重放或改写旧分数/账目；没有读取真实密钥、启动供应商请求或访问未见题。主任务应先对本包执行免费 prepare、真实 JAR 本地替身 preflight 和独立工程审查，再仅就新增一次开发100题请求用户明确授权。

## 使用与固定路径

新入口为 `scripts/run_replan_stage_d_reregistered.py`，当前免费修订固定目录为 `reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-2`；独立新审查目录为 `reports/replan-stage-d-20261119/independent-review/reregistered-dev-1/revision-2`。只在新进程导入原冻结模块并替换全局路径/写盘/门禁，不编辑原文件。

第一份免费draft的B预检通过、F在就绪前因quota价格身份不符而失败：`CostQuota`的身份除模型/价格外还包含`agent.llm.base-url`，随机代理端口与旧59900不同。其原plan、零paid状态、原错误诊断和33份文件已由 `reregistration-first-free-failure.json` 封存，修订prepare先核该manifest、原plan、state/ledger均0且无授权/成功preflight，不覆盖原目录。原错误`inherited-quota-after-load.json`保留为失败现场，不能再据其`passed=true`解释为JAR加载成功。

```powershell
python scripts/run_replan_stage_d_reregistered.py prepare
python scripts/run_replan_stage_d_reregistered.py preflight
python scripts/run_replan_stage_d_reregistered.py audit
```

以上步骤无需付费授权且不读真实 key。prepare 先核独立中断审计结论、旧终态、401项中断 manifest、356项独立审计输入和冻结旧源码/plan/运行环境指纹，再CREATE_NEW新目录；存在就拒绝，不自动恢复。它复用已审核的公开 dev/formal/targets 参考原字节，重新从固定C1 JAR编译共同评分桥，并核 B0/旧D相同只读数据库身份。它不重新执行候选 SQL，不把旧68答复制进新轮次。

新正式运行态 `candidate-cost-quota.json` 从旧文件逐字复制，保留原 `budgetId/pricingIdentity/known/3 UNKNOWN holds`；不可变 `candidate-quota-initial-snapshot.json` 与旧原件均由 plan 绑定。运行态不放进初始固定SHA清单，以免合法费用更新被误判为指纹漂移。第一次付费启动前要求运行态与seed字节一致；以后核预算身份、历史已知不倒退、旧3份UNKNOWN完全保留、该层占用不超¥1。

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

## 首次免费验证数字与限制

第一版定向测试34/34保留。当前修订定向测试38/38，0失败/0错误；旧controller、paid401、第一free33和free manifest共436/436原字节一致。此组测试新增外部API、Token、真实费用、真实密钥读取、HTTP请求、子进程启动均为0；socket、子进程与真实key入口都由失败替身阻断。结果见 `directed-tests-2/verification.json`。

固定listener另有7/7真实Windows检查，5次仅loopback合成上游请求；新增外部供应商API/真实key读取/费用仍为0。B→F同进程关闭后立即重开、两个子进程先后关闭重开均成功；观察到59900的两条TIME_WAIT，独占仍可按本次已验序列重新绑定。活动proxy独占时第二proxy被拒；独立进程占用时子进程WinError10048，许可/完成回调均0，无线程泄露、无备用端口；实测socket exclusive=1/reuse=0。原handler/forward/deadline/drain/shutdown方法身份相同。原始探针3/3及完整7项证据保留于 `stable-port-initial-probe.json`、`stable-proxy-checks-1/`。这些是本机本次状态证据，不承诺所有未来TIME_WAIT/进程占用都会可重开；失败仍关闭流程，不提高地址复用权限。

覆盖授权缺失/错误plan/非人类authority/错原文哈希/超限费用/未来时间/缺UNKNOWN承认/消费后漂移，授权缺失在原run前拒绝，当前同ID不与历史混合，旧共同费用占用，新许可上限扣应用70，候选quota全字节继承/禁止清零/层额度独立，helper默认参数接入、同temp同bytes重试只产生一个permit、持久失败不返回许可也不释放预留、新写入禁止触及旧批次、免费prepare/preflight控制流、F离线seed复制及关闭后保持检查、合成usage不进入真实累计。

有界helper只重试同一已fsync临时文件到同一目标的 `os.replace`；新入口仅用默认0.5秒退避重试预算/12次尝试，不暴露可调参数。同步 `os.replace` 本身不能强杀，不能保证绝对wall≤0.5秒；父15秒deadline不刷新，proxy在permit后仍检查过期并拒绝发送，整轮/发送时段门禁保留。没有HTTP/模型/题目重试。此修订的真实JAR免费预检和独立成套审查由主任务接着完成，不能把上述套接字或替身检查宣称成它们已通过。

## 技术与设计导读

以新DEV_F里的T1-002为例：控制器从冻结开发参考拿题，新ledger的第一次许可仍是attempt1；旧同ID许可留在旧目录，不参与新题的2次上限，却通过固定历史占用继续约束全局预算。before先写新唯一permit，再把同bytes新账本临时文件fsync并有界replace；成功才返回proxy，父deadline已过则proxy仍拒绝上游。真实HTTP答案进入新100题完整分母。新轮次任何中断仍封存，不补题。

技术抓手是已审核原控制器复用、Python进程局部绑定、不可变旧manifest/独立审计SHA、独立新事件ledger、原子文件替换helper、预算seed与可变运行态分离、固定独占loopback入口、匹配精确plan的双重人类授权门禁。数据出身用旧/新目录及manifest/planSHA区分；没有打分权重、TopK或失败过滤，不能靠选择性排除旧账目或新失败题改善分数。

拒绝直接续跑旧轮或复制旧events，因为会破坏中断不重放承诺、冲突同题attempt，并掩盖真实分母。拒绝把工程审查accepted当付费次数授权；拒绝重置quota，因为未知费用仍有风险。面试追问一：新ledger空是否清零？回答同时指出旧SHA固定、committed加旧占用、当前调用限额扣历史应用70和quota原bytes继承。追问二：本地replace重试会多发模型吗？回答retry循环只有原子替换，许可持久化成功前proxy无发送入口，deadline仍由原代理约束。

价格身份包含代理地址会令随机端口破坏恢复，但本轮禁止改生产/JAR或迁移旧账目，因此选择固定原入口。没有用SO_REUSEADDR抢占监听，因为已有owner必须安全拒绝；代价是59900不可用会令流程中断，不能保证任何未来运行都成功。候选层继承占用¥0.46312188后余¥0.53687812，预算不变，不能承诺完成开发或后续正式。

用户当前无需启动外部服务；最后需要的只是对完成免费预检与独立审查后的新付费100题作明确决定。需要核对的是数据流的旧/新身份、三种调用数字与两层费用占用，以及本地持久化故障与生成/评分错误的归因。
