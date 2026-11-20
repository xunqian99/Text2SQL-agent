# 新登记控制器 revision-4：免费工程候选，未取得D额外付费授权

revision-3 虽通过免费接入测试，仍被独立审查拒绝：902最坏存储形状下，两调用的同步回调耗时10757.916ms，连同begin/HTTP_RETURN/end达14069.733ms，几乎吃完整个原15秒预算。revision-4减少事务并改变历史原件检查时机，必须重新独立审查；不能用旧accepted或既有B授权放行新增D开发100题。

本实现任务没有准备实际revision-4批次、没有创建实际人类授权、没有读取真实密钥、没有调用供应商、没有读取未见题或解析旧68题答案。实际prepare、真实C1本机替身、最终组合回归和独立审查由主任务统一完成。以下数字属于免费内部工程检查，不是模型准确率、应用P95或付费准入结论。

## 路径和历史身份

入口 `scripts/run_replan_stage_d_reregistered.py`；实际新目录固定为 `reports/replan-stage-d-20261119/batch-1/reregistered-dev-1/revision-4`，审查目录为 `reports/replan-stage-d-20261119/independent-review/reregistered-dev-1/revision-4`。新进程局部绑定冻结旧控制器；旧Java、controller、proxy和paid历史不改。

- 旧付费 `batch-1/revision-2` 始终 `INTERRUPTED_NO_REPLAY`，不得补题、续跑或复制旧答案进新轮次。独立聚合审计提供计数与账目，详细历史文件仅逐字SHA核验。
- 第一免费draft原33文件和失败原因原样封存，不能把原seed保留诊断解释成JAR已启动成功。
- 免费revision-2正式拒绝：decision SHA `d1050ff782c6ab98e96dae5db29bc1b65d8f3aa13c5e378162dba74a97179242`，manifest SHA `af434a921708d5acbf5a402f9c733ff2e43ce60fbb506ca629caa708ef5b10b4`；原源码Git `02364ab073360c352d94c698f69d59b0496fd5c1`，不改旧plan迎合新源码。
- 免费revision-3正式拒绝：plan `9b32def424104c383d8088cb538ec54698f9bda12bbbb45a3d6472b65db5d41c`，decision SHA `7997a901b684d5c3715cfdfca5258798413374dd428db2562c2923567a5aaa67`，manifest SHA `49b7b39bd8aabf505a59ac9184b105e06a574da7c5a1fe1feac0ebfba6146eee`，实现Git `b178a4b2fe454cf5b5df90e4f62de665fce1fe6c`。prepare核before/after两份实际380文件清单一致、原bytes、requests0/rounds空/ledger空/授权缺失，不重验证其旧plan为新源码。

免费入口仍是prepare→preflight→audit，存在目录拒绝覆盖。prepare复用已审核公开dev/formal/targets参考原bytes，继承旧quota完整bytes和3 UNKNOWN，核B0/旧D只读数据库身份；不执行候选SQL重评旧答案。

## revision-4机制与检查边界

以DEV_F公开T1-002为例：控制器在原`started+15`之前执行FULL verify，再把requests+1与固定公开reference对应的题目fence一并持久化。fence包含plan SHA、round、累计ordinal、questionId和strip后的UTF-8题文SHA；跳号、改题、重复begin、未绑定reference均拒绝。begin只校验内存准入凭据并保留原deadline，不追加观察。

Java产生PENDING后，before执行一个FAST事务：核当前完整journal SHA、checkpoint与state/ledger锚及其规范内容；读取quota快照A；用Java compact `[[SYSTEM,text],[USER,text]]` SHA和callNumber匹配唯一PENDING；调用冻结原gate创建私有permit与暂存ledger；读取快照B并检查A→B未回退；同一提交保存A/B原bytes、hold→token绑定、token→原fence、journal和三处锚。所有持久化成功后permit才返回proxy，代理仍检查原父deadline。after同样只有一个FAST事务，原raw/completion/rejection格式不变，Java在此时仍可PENDING。

HTTP_RETURN合并quota结算、原HTTP证据验证和durable question close。旧outer end是幂等内存关闭。两个调用原13次观察变为5次FAST提交；事务数减少本身不证明延迟达标。晚到after以持久token→原fence归属原题，不能套用当前题身份。

FULL每题之前、轮次结束/排空、首owner或新进程执行，重新读所有历史common permit/completion/raw/rejection实际bytes并SHA核验。FAST每次仍重新读并SHA整个journal、核全部当前锚/规范ledger、核quota A/B身份/费用水位/UNKNOWN/PENDING，并核公共目录精确文件集合及类型；只读本事务新增的原件内容。历史同名raw在一题内部被改，可能到下一次FULL才检出，届时禁止继续/晋级。其内容不参与当前费用重算，当前费用来自已锚ledger/journal与quota，不会因此释放UNKNOWN。该时机变化写入POLICY和新plan，不能隐藏成每回调仍核所有旧raw。

FAST凭据只驻内存，绑定同一个真实controller owner对象、plan、round、admitted fence和当前head。缺凭据直接拒绝，没有自动FULL降级放行，没有mtime、路径或fixture标记授权。owner退出即销毁；新进程必须FULL重建。monitor→gate固定锁序及callback线程身份保护暂存ledger；只复制会增删的工作集合，cache/事件快照边界仍防御性深复制，外部返回quota或后续gate内存改动不能污染已验证历史。

journal采用O_APPEND+fsync，每条包含sequence、前条SHA、quota原bytes/base64/SHA、规范语义、knowledge叶变更、公共新增原件SHA。checkpoint记录全journal字节数/SHA和末事件身份，head同时锚在state和ledger。可变state/ledger使用紧凑JSON，规范内容身份不变；不可变公共原件仍使用冻结格式。禁止半条尾部修复、锚错位修复、孤儿原件补齐或自动重建。

callback预加载、首快照畸形或原件/锚半写导致正常事务无法提交时，CREATE_NEW的`candidate-quota-persistence-stop.json`尽力保留已捕获A/B原件、畸形quota原bytes、fence和原因，新实例也硬拒绝，不改原文件、不补锚。磁盘全部不可写时不能保证再写旁证，已有未配对锚/原件和持久PENDING仍禁止恢复，不能宣称所有故障都能完整存证。

## 费用与失败保护

数据出身由旧/新目录、manifest/plan SHA、hold UUID + requestId + callNumber + promptSha256 + status、token和requestFence共同追踪，不将PENDING/UNKNOWN或两层账目扁平混算。没有评分权重、TopK、失败过滤或样本筛选来改善分数。

knownCalls和Decimal knownCost分别保留持久最高水位；UNKNOWN观察后不可删除或改内容。A出现、B撤销的新UNKNOWN/更高known水位仍进入永久stop；未知内容变体也保留。PENDING消失必须有原token对应已知completion和严格相等的调用/费用增量，或原HTTP完整bytes、题号/requestId和结构化未发送证据；UNKNOWN不能走未发送退款。正常`.10 PENDING→.000005 known`可降低总committed，单调保护的是known水位和UNKNOWN，不能误把合法结算降占用当回退。

原common gate拒绝保留唯一原rejection并永久停机，之后SDK纠错不能再送。before提交失败不返回permit；私有不可变permit可能已经生成，不能谎称磁盘零permit。after失败仍保留之前的未知公共承诺和候选PENDING，不伪造结算。永久quota stop仅允许同一原RUNNING轮次严格封成INTERRUPTED与`INTERRUPTED_NO_REPLAY`，不能增加题数/轮次、改COMPLETED、清stop或改账本。结构已坏时终态未必能写出，但禁止继续。

本地journal/双锚只能发现部分回滚，无法抵抗整个目录、所有锚和完整历史被协调重写；项目没有外部可信签名锚。

历史账目固定：68题、应用预算70次、共同许可69、传输68、known67、¥0.16312188；共同UNKNOWN2保留¥0.20，占用¥0.36312188；候选UNKNOWN3保留¥0.30，占用¥0.46312188。两层分别约束，不能相加冒充供应商账单。新共同ledger只记新事件但committed加旧占用；许可上限1870=1940−历史应用70。新路线至多902题、累计970题、每题2调用、新路线自身最多1804调用；全局1002/2004、¥1、¥.10预留、15秒、SDK attempts1、原时段/门槛均不放松。

固定代理仍是旧 `http://127.0.0.1:59900`，保留旧pricingIdentity；Windows exclusive=1/reuse=0，占用即拒绝、无随机备用口。离线preflight从seed复制独立offline quota，本机合成usage不改正式quota，不进入真实历史账单；只有JAR就绪且预检体完成才可passed。既有“两份本机HTTP+98份显式合成Gold”是接入检查，不是模型准确率。

## 免费验证数字

主优化批次：`revision-4-directed-tests-2` 39/39，固定旧436文件bytes不变；`revision-4-monitor-checks-2` 69/69，供应商、真实key、Java进程均0。这两批绑定失败分支补封前helper `3137e6a538340299f3887e05c2d30a744e648e696efc9395c81c3bbf30240fb5`。最后发现首快照畸形后若恢复runtime，原代码还可再尝试callback，已收紧为永久stop；预加载/首快照/B畸形与恢复后禁发、费用责任/持久失败/别名组成一批 `revision-4-failure-boundary-checks-1` 12/12通过，绑定最终helper `35b86ea00752c4e4a986480c8be266e87345c0617bd041214cbf17f53e7cb9db`。最终monitor共有72项，主任务负责最终同版完整组合，不能把先前69/69冒充最终源码完整回归。monitor首轮67项66通过，唯一失败是测试自己未HTTP关闭便准入下一题，生产拒绝正确；原失败`revision-4-monitor-checks-1`与补1/1保留，不能伪称首轮全绿。69项批次额外覆盖返回值/内存别名污染和重复JSON成员拒绝。

性能使用真实新SendGate、真实owner/admission、真实before/after、真实HTTP wrapper和本地合成Java quota文件更新，无socket/JAR/model/SQL/key。每尺度一个样本，均保留43,473,492B、15318观察的902历史形状，尾题后1804calls/5412公共原件；102历史为4,865,175B/1718观察，尾题后204calls/612原件。不是拿小fixture外推。

| 同版免费工程测量 | 102四回调ms | 102完整ms | 902四回调ms | 902完整ms |
|---|---:|---:|---:|---:|
| performance-4 首实现 | 488.403 | 678.889 | 1888.393 | 2283.429 |
| performance-5 削减ledger复制 | 379.327 | 527.426 | 1208.601 | 1586.474 |
| performance-6 工作集合/紧凑锚 | 362.396 | 507.513 | 1090.217 | 1438.505 |
| performance-7 失败分支补封前 | 359.585 | 530.920 | **1070.506** | **1365.076** |

performance-7绑定helper `3137e6a538340299f3887e05c2d30a744e648e696efc9395c81c3bbf30240fb5`；其后仅收紧失败封存分支，未补跑性能，不以最终helper SHA冒充旧测量。两者driver同为 `bda005614aa90c7f872f9451d0654a958204b2d4f5f807038d999245892d1d4e`。完整指begin→两对回调→HTTP_RETURN/end，含模拟quota更新和真实证据写盘。工程目标是四回调≤1000ms、完整≤1500ms；最后一次测量的902四回调仍严格超1000ms，目标标记false，完整标记true，交独审裁定，不改阈值、不择优补测。新实例FULL2588.471ms、gateOpen FULL1771.661ms、准入216.605ms在父15秒之前；post-question FULL1019.254ms。首新实例无解码cache但文件刚构造，OS冷盘未受控，不能称冷盘或产品P95。FULL整轮累计成本和真实C1的总链路仍由主任务核验。

performance-2/3和revision-3独立拒绝原件全部保留；revision-4各轮原目录也保留。大体积原件通过`revision-4-implementation-delivery/`的ZIP、逐文件SHA清单和ZIP逐件回读校验入Git，不能把ZIP说成原始单文件，也不删除磁盘原目录。

真实免费入口仍是 `scripts/check_replan_real_quota_fixture.py --output <candidate内全新绝对目录>`：复制公开reference并绑定plan，经真实准入/gate跑C1 ready-only，再另一基座跑公开T1-002本机已知usage；退出JVM后两次独立Python子进程FULL冷重建。脚本存在及本次改动不等于revision-4真实集成已通过，主任务负责执行和独立冻结。

## 额外付费授权边界

实际 `human-paid-authorization.json` 本实现未创建。精确契约见`human-paid-authorization.schema.json`：DIRECT_HUMAN_USER、ONE_ADDITIONAL_FULL_DEV100_FIXED_C1_EXISTING_GATES、最终revision-4 plan原bytes SHA、带时区且非未来的批准时间、直接用户批准原文及UTF-8 SHA、固定旧manifest、100/902/970/1940/¥1/¥.10与全部旧账/UNKNOWN/不重放/未见锁定承认。工程accepted、既有B授权和代理输出均不能代填D额外付费授权；本地JSON也不是密码学人类签名。

run-next进入旧付费路径前核一次，读取真实key前再核并CREATE_NEW消费指纹；缺授权不写RUNNING、不加题数、不拿key、不送模型。新增完整DEV_F通过后，T3-002仅能复用新完整轮唯一响应，不能挑旧68答或跨来源高分。原正式/未见停止条件均保持。

## 技术与设计导读

实现抓手是固定公开reference准入fence、内存owner能力、双quota原快照、hold→token→原fence身份链、唯一追加journal/三处锚、原gate局部暂存和明确FULL/FAST边界。具体输入仍是T1-002题文；输出是保留原deadline的私有permit、原HTTP答案以及可独立重建的收费/未知责任证据，不是新业务算法。

收益是把两调用13次同步观察压成5次提交，最后一次免费902回调测量由独立revision-3的10757.916ms降到1070.506ms；这只是不同已注明源码的工程对照，不能偷换成模型效果提升。代价是题内历史同名raw篡改延后至下一FULL发现，且四回调严格1秒目标仍未过。拒绝mtime授权和跨进程cache，因为它们无法证明原bytes未变；拒绝完全不落盘的异步结算，因为许可逃逸前没有责任凭据。也不能为了速度释放UNKNOWN或刷新15秒。

面试追问一：为什么FAST不会洗掉费用？回答应沿原hold身份、A/B快照、known最高水位、sticky UNKNOWN、精确completion与HTTP证据说明，而非只说“有锁”。追问二：为什么不每回调读全部旧raw？直接说明独立实测原方案耗时、当前检查时机与篡改窗口、当前费用不依赖旧raw重算以及下一FULL阻止晋级的保底，不隐瞒该权衡。

用户当前无需操作外部服务；需要自行核对旧/新身份如何流转、许可/传输/应用调用数及两层费用如何对账、故障究竟在准入/持久化/模型/SQL/评分哪一层。新增D付费100题仅在免费真实集成和独审完成后，由直接用户另作决定。
