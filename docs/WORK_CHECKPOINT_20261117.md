# 2026-11-17下午接续检查点

## 最新目标覆盖：空闲时段评测（2026-11-17）

**阶段2三轮已完成但未通过**：测量/预注册641400f；reports/stage2-regression-20261117，原eval100三轮84/86/89、均值86.33%，比提前固定默认91.00%下降4.67pp，低于89%门槛。295调用、286已知/9未知、802,389已知Token，费用仅≥¥0.59034512；P95 12.530/12.428/8.222秒，均11.060秒。1次纠错恢复T1-007、0退化；6澄清与旧基线相同，不能归罪自纠错。41失分涉及18题：26结果不匹配/9超时/6澄清。主要固定冲突：客户记录数vs真人数、两状态订单数横纵形状、差评记录vs去重订单；其他错在变化量vs增长率、维度列、Join保留、Top20依据等。failure-analysis.json保留全部原题/Gold SQL/实际SQL。阶段2仍失败，阶段3暂缓，不能停留在专项6/6就报通过。下一步按STAGE2_REGRESSION_RESULTS_20261117.md的一致性矩阵与反例路径收窄契约，不改旧分数/Gold，不马上追加300/600请求。

**审计补充**：原冻结summarize脚本初次审计16处CRLF/LF失败，原日志/退出1/摘要保留；逐文件原字节副本source-bytes和line-ending-witness证明仅换行差异。未改冻结生产或脚本、未重跑付费；reports内的归档audit只补入口与换行凭据，逐行diff保留，审计PASS但业务门槛FAIL。verify_archive.py只读校验制品与重算结论。所有4个应用正常退出、18080关闭。未提交用户演示稿改动保留；不要重放本轮run_stage2_regression.ps1。

**21点输出契约专项完成**：p10实现8c091ed、原字节属性66cb4ba、6条协议6bd1a01。Java750/750/0跳过、Gold200/200、200题检索不变、17题单位文本改变、所有最终Prompt改变。真实专项只跑一次6/6、6调用、17,372 Token、完整估价¥0.01439656、未知0，Java22732和端口54982已关闭。两份新audit通过，客户Top10严格失败不改写。下一步按STAGE2_REGRESSION_PROTOCOL_20261117.md执行原eval100三轮（300请求），基线提前固定最近默认91.00%，最低89.00%；历史比较不冒称同期因果或盲测。阶段3继续暂缓。

**20:42客户身份真实专项完成**：实现fcb1c32，日志63e8a71，协议与432运行文件身份830b0a9。4条只跑一次，HTTP4/4、严格3/4、4调用/10,499 Token/完整估价¥0.00661856/未知0。原T4-001已按真人聚合17单，但6单有6人竞争5个位置，原Gold没有二级排序，实际与Gold差1个合法并列者，严格失败保留；独立只读witness验证两边所有人数。另三条96,096人、行级分布(1,99,441)、订单99,441均匹配。Java15100退出、57622关闭。两份audit_evidence.py均通过。不要重跑这4条或旧20条，不改变Gold/评分；下一步继续阶段2输出形状及比例单位契约，正式三轮回归待整体语义改动冻结后执行，不为每个小改动追加600请求。

**20点恢复：阶段2客户身份专项**：工作目录新增EntityPolicy与customer_identity_v1，只在实际customers两身份列存在且别名命中后追加，所有Schema路径接入，不改变检索评分或列保留。Docker关闭造成首次本地回归跳过14项、after连接失败及Gold进程16228之前的39736被停止；现场保留。Docker遗留run目录原样备份后恢复，原库46d25808e1a4未重建。数据库在线重跑Java729/729/0跳过、Gold200/200，200题检索/列/DDL/示例/来源不变，25题只增身份政策。见[CUSTOMER_IDENTITY_CONTRACT_20261117.md](CUSTOMER_IDENTITY_CONTRACT_20261117.md)。下一步先提交实现与离线证据，再用CustomerIdentityHttpProbe冻结4条真实定点协议并提交后只运行一次；不要把旧20条重跑。源代码和实际runtimeFiles均冻结，保留失败，空闲门禁继续开启。输出形状、单位契约及阶段2三轮正式回归尚欠。

**收尾已确认**：真实验收与证据已提交`28655b2`。单次接续任务`text2sql`已删除，返回`deleteStatus: deleted`；不得重新创建或重放付费请求。提交后再次运行20条、隔离付费补验、当前构建入口保护三份只读审计，全部PASS，本次复核模型调用0、费用0。路线图首页与NULL报告的后续状态同步至晚间结果；业务源码、原始证据、题目、Gold、评分器均不变。未提交演示稿改动继续保留。下方早期准备命令仅为历史记录，不再执行。

**18点实际执行已完成，禁止重复付费**：晚间结果见[DEMO_HTTP_RESULTS_20261117.md](DEMO_HTTP_RESULTS_20261117.md)。原目录首次18:01起跑在Decision Instant序列化失败，0问数/0模型、旧requests为空，现场保留；伴随TimeAwareProbe只给原ObjectMapper注册已有JavaTimeModule，原run/summarize/ResultNormalizer/业务源码不变。新reports/demo-http-20-20261117-v2原字节协议、桥接source/class于d396701先提交后实际问数一次：接口19/20、严格匹配16/20（80%）、健康20/20、会话/追问/关闭删除全部通过；均值3198.3ms/P95 11030ms/最大15014ms，22调用/53715 Token/完整估价¥0.03923214、未知0。四项不匹配T3-002横纵形状（数值正确）、T4-001错用customer_id而非customer_unique_id、T5-002总预算超时（MODEL14857ms、usage已知）、T6-003小数0.0304与百分数3.04单位差异；保持原评分、不调分不重跑。

**隔离真实1条也已完成**：18:05 run_chain.py paid首次1/1、54011、HTTP2393ms/1调用/2295 Token/估价¥0.0008454/未知0；会话1轮、close/delete通过。paid-manifest与audit_paid.py补充保护，不改离线manifest。原失败启动器21564/Java26808、新20应用26296、隔离30528均退出，18087/64956关闭；15433新库停止/卷保留，原库ID/运行状态不变。本机阶段4原五项门槛通过（非全题正确），整体目标仍未完成；下一项回到阶段2结果契约，阶段3P95继续暂缓，独立盲测仍欠。18点单次自动任务完成后删除，不创建重复任务或继续按下文旧命令付费。

**18点调用工具命令更新**：原文档$demoCp来自工作目录target，已补成冷构建调用方绑定。先python reports/demo-caller-binding-20261117/audit_binding.py，再以invoke_probe.py run/summarize代替手写java -cp命令；服务端仍用原start_cold_jar.ps1，不改原20条协议/Gold/评分器/产物。wrapper核对211生产条目、98测试类/资源、114依赖JAR/116非空类路径和个人local指纹，cwd为项目根以供原工具验证Git/题库；调用进程不继承模型Key、Spring/JVM覆盖。实际15:20高峰预检在健康HTTP前拒绝、无requests/供应商0次。初次stdout/stderr未显式转发的空日志保留initial-output-capture，修复的是wrapper诊断，不是题目/业务源码。此后的新20条正式运行只执行一次，不能把两个未发送预检算成付费运行或择优。

**当前构建保护HTTP复验**：见[PROTECTED_CURRENT_ACCEPTANCE_20261117.md](PROTECTED_CURRENT_ACCEPTANCE_20261117.md)。reports/protected-current-20261117从冷构建classes/test-classes＋114缓存依赖启动原Access/Cost探针，211生产条目与JAR逐字节相同；认证36/36、费用4/4组，合成5次/供应商0次/费用0。98测试类/资源身份绑定；未重跑Java或Gold、不改源码/探针/原题。5进程26628/18484/31544/44748/41500均退出，端口55056/55068/57391/59965/59974关闭；复用15433隔离库后已停止，卷保留，原库仍运行。audit_evidence.py --live-inputs通过；不能称java -jar启动或真实模型验证。18点原20条和隔离1条安排不变，不追加其他评测批次。

**同机隔离整链路补验**：见[ISOLATED_CHAIN_ACCEPTANCE_20261117.md](ISOLATED_CHAIN_ACCEPTANCE_20261117.md)。保留冷构建JAR/源码/缓存，公开模板另复制到Temp独立配置，新建text2sql-clean-chain-20261117及独立卷、15433本机端口。6脚本成功、37业务＋4持久化表/2,616,948行、两账号业务表可写0和4实际拒绝；新包demo无Key健康200/问数503，5个带ApplicationName的ro连接；Gold200/200、进程35,666ms、供应商0次。Java39596/30400退出、端口53407关闭，新库已停止并保留卷，原库仍运行。39制品离线manifest审计通过。首次Python→PS5.1继承PS7模块路径导致Get-FileHash失败，在创建资源前退出，initial-attempt保留；只改验收runner的子进程模块路径，不改源代码/用户环境。run_chain.py、协议、配置、JAR均已绑定，真实1条尚未执行。

**18点新增的必要收尾**：先按既有原20条协议完整运行一次，再单独补同机隔离链路的首次1条真实问数（不是扩展题库或21题合并计分）。先python reports/isolated-chain-20261117/audit_evidence.py --live-inputs；核对database-binding.json的容器ID后只启动text2sql-clean-chain-20261117，用环境继承现有Key运行python reports/isolated-chain-20261117/run_chain.py paid。入口与应用均检查空闲时间；冻结问题2018订单数、Gold54,011、15秒/最多2调用。随后运行该目录audit_evidence.py --live-inputs --paid、核对费用/未知/会话与进程退出，另存付费manifest和文档。只停新容器，卷保留，原库不动。失败留存不覆盖、不重跑择优；不要改runner导致冻结SHA失效。未来 paid-window-check.json/paid-ask.json现在均不存在。

**阶段4矩阵补充**：当前状态统一见[STAGE4_ACCEPTANCE_MATRIX_20261117.md](STAGE4_ACCEPTANCE_MATRIX_20261117.md)。本轮重新执行10份只读证据审计全部退出0，记录于reports/stage4-status-20261117/audit-results.json；没有重新跑Java、Gold、应用或模型，费用0。已修正改进方案中鉴权/限流的过期状态，原五条门槛和历史成绩未变。新20条仍未启动；同机新库与空缓存是两份分层证明，尚不合并宣称同一次干净环境整链路通过。18点执行仍按既有冻结协议，不改题/评分/预算，不新开延迟实验。

用户已将目标重新设为active，最新约束为“阶段3 P95暂缓，不在DeepSeek高峰收费时段调用用户API跑评测，仅空闲时段运行”。下文“18:00前不推进项目/整段停工”是旧安排：现在本地代码、测试、文档可继续，真实评测须符合[空闲时段门禁](OFFPEAK_EVALUATION_20261117.md)。14:00/18:00既有heartbeat已更新为费用边界/空闲接续，没有新建重复任务。

已实现官方主机/远程DeepSeek模型身份识别、北京时间区间判断、生成入口与最终网络发送复查、默认120秒边界余量；EvalRunner和新20条验收工具同样检查。周中未验证节假日保守拒绝，localhost模拟测试可运行。Java710/710、68套件；新增40项；供应商0次/¥0。初次8项断言错误及最终全部证据保留于reports/offpeak-policy-20261117/，不是付费重跑。不要关闭或绕过门禁，不把本地准入余量说成供应商账单保证。

下一项仍为新的完整20条真实HTTP功能复验。可以先用只读数据库冻结Gold并提交协议；18:00后重新核对官方规则和当前时间，用新编译进程运行。新的.gitattributes已提前保护reports/demo-http-20-20261117/原字节。不重复既有实验，不修改原题/Gold/评分器或历史数字。

**14点后准备状态**：门禁实现`8c4e669`、诊断原字节属性`9d6285a`；新20条已冻结提交`7697cd8`，详见[新协议与接续命令](DEMO_HTTP_PROTOCOL_20261117.md)。只读Gold20/20，与旧协议问题/SQL/标准列和值相同（仅执行elapsedMs变化，不参与评分）。实际高峰起跑预检已拒绝于健康HTTP之前，无requests目录、供应商0次；这不是一次付费重跑。运行前先执行新目录audit_protocol.py以及offpeak-policy目录audit_evidence.py；18:00后才发首次真实请求。

**空缓存离线补验**：见[COLD_BUILD_ACCEPTANCE_20261117.md](COLD_BUILD_ACCEPTANCE_20261117.md)。从9d6285a导出268文件、独立缓存起始0；185JAR/347POM校验、79内嵌运行库来源通过。首轮PowerShell5.1 NativeCommandError使包装脚本退出1、原Maven退出码未知；保留初始XML710/710及现场，核查原Java进程不存在后，同缓存-o verify退出0、Java710/710。新JAR无Key实际HTTP8/8（进程20408/端口64747已退出）、Gold200/200且进程35268正常退出；供应商0次。源副本与缓存保留在protocol.json指定的Temp根，勿在18点前删除。

**18点启动更新**：新20条artifact-plan.json与start_cold_jar.ps1绑定该新包SHA和原协议，不再通过Maven启动旧target。先运行三个离线审计（含clean-cache目录audit_evidence.py），核对官方时段后按新20条文档启动本机local,demo；个人local文件通过其原目录加载，原SHA未变，Key仅环境注入。启动器-Preview已通过且未启动应用。保留初次UTF-8读取预览失败，不改题/Gold/评分器/原protocol。实际20条仍未开始，不把新包无Key8/8或Gold200/200当模型成绩。

## 时间与执行决定

用户目标：按阶段计划继续，阶段3 P95暂缓，今天北京时间14:00停止、18:00继续。午前工作收尾于13:45左右，已提交当前三个可独立审查的阶段4改动；不再开启新修改或模型请求。14:00停止与18:00接续的线程heartbeat均已建立，本次保存时仍ACTIVE。

18:00之前不启动项目工作；不要把阶段3、阶段4或整个目标标为完成。P95原8秒门槛保留，不能改成15秒后宣称通过。不要重新开展模型、思考模式、Prompt或路由的延迟实验。

## 已完成，不必重做

| 项目 | 提交与证据 | 结果 |
|---|---|---|
| 持久化费用预留 | `c22764b`；[设计与命令](COST_QUOTA_20261117.md) | Java625/625；费用HTTP4/4组；认证回归36/36；均为合成模型，供应商0次/¥0 |
| 商品类目NULL契约 | 实现`92d0ed2`，冻结协议`2ad8106`/字节保护`8bb260e`，结果`9448b3f`；[设计与补验](CATEGORY_NULL_CONTRACT_20261117.md) | Java640/640；数据库反例8/8；原200题检索/列/示例/系统消息不变，仅15题增加口径；真实模型HTTP3/3，3次调用、7,772 Token、估价¥0.00584928、未知0 |
| 复购率澄清证据 | `a3e2efe`；[机制与边界](REPURCHASE_CLARIFICATION_20261117.md) | Java670/670、66套件、失败/错误/跳过0；新增30反例；原200题仅T6-003解除误拦截，6→5题仍需澄清；合成HTTP8/8，供应商0次/¥0 |

用户对NULL默认口径回复“你自行决定”。已选择并公开：按商品类目分组默认排除SQL NULL；用户明确要求包含未知/缺失时保留；非类目分组总量不受影响。不是删除响应里的NULL行，也不在SQL后自动拼过滤。

费用配额是配置估价准入，默认protected总额1元、每次预留0.10元，未知不退款、重启不清零。若真实估价超过预留，记录实际费用并锁住后续调用；不能说是供应商账单硬上限。BYOK/其他模型覆盖在受控费用模式下被拒绝。原demo/baseline默认不开启入口认证/限流/金额配额。

澄清规则只改复购率：分母关系证据、别名、否定/疑问/备选拦截；销售额和活跃用户仍是旧hint机制。Decision记录source/group/matchedText，未知表达保守澄清。HTTP替身SELECT 1只证明进入生成，不能说复购率已获真实生成准确率证明。

原20条测量`452209b`的17/20业务正确、18/20接口成功、P95 10.083秒、未知用量1及费用下界≥¥0.03040434保持原样。超时会话修复、新数据库复现、认证与限流先前已完成，不重跑那些实验来刷数字。

## 18:00接续步骤

1. 先读本文件、Git状态与上述三个文档，确认工作目录`C:\Users\xunqian\Desktop\agent`。当前业务代码应在`a3e2efe`或其之后的纯检查点提交；没有未提交业务改动。
2. 下一项是**新的完整20条真实HTTP复验**，使用原18题＋2追问的同样选择规则，保存到新目录（如`reports/demo-http-20-20261117/`）。不覆写旧目录，不替换失败题，不修改Gold，不把定点3/3当成这轮成绩。
3. 沿用`DemoHttpAcceptanceProbe freeze/run/summarize`与[原协议](DEMO_HTTP_PROTOCOL_20261116.md)。先确认新输出目录在`.gitattributes`以`-text`保存原字节，提交工具/属性后才冻结，再提交冻结protocol，最后才发模型请求。此前一次预检因Windows换行差异在启动模型前拒绝，已单列归档，不算付费重跑。
4. 原20条工具不自动携带应用认证。若沿用未修改工具，只能按原协议在本机demo入口做功能复验，不能声称protected 20条；认证/费用已有独立组合验收及3条真实protected模型证据。若要改为protected 20条，先让工具从进程环境读取独立应用认证Header并验证，再冻结，不把口令放进URL、参数或记录。
5. 每轮固定15秒、最多2次模型调用与原默认模型策略；不为避免超时扩大预算，不试验关闭思考。本轮是功能复验，不是阶段3延迟筛选。
6. 模型Key可从User环境`AGENT_LLM_API_KEY`注入子进程；已可用，不需要用户重新提供。不要读出或打印Key，不把实际Key当作Read-Host提示。模型价格是配置估价，不承诺供应商即时价格。
7. 记录20条全部状态、结果对照、平均/HTTP P95、每次调用、未知、配置估价、会话隔离/轮数/关闭删除。失败保留，不重跑挑好成绩。完整费用未知时只报下界。
8. 归档、凭据扫描、源码和原题身份核对、Git提交；只提交本次路径。保持原评测题200道，600次问答是重复请求，不是题库变成600道。

独立盲测仍未完成，AI/子agent审核不能叫第三方独立；全新主机/空Maven缓存、完整语义契约、远程TLS/代理/数据库网络部署也没有凭本轮完成。不要部署公网，不增加多租户、RLS、方言、微调或业务写权限。

## 环境与清理

Docker原`text2sql-postgres`保持运行；隔离新库`text2sql-clean-acceptance-20261116`此前已停止，保留卷。不要停止或重建用户原库。无需付费调用验证历史归档：

```powershell
python reports/cost-quota-20261117/audit_evidence.py
python reports/category-null-contract-20261117/audit_evidence.py
python reports/repurchase-clarification-20261117/audit_evidence.py
```

最近HTTP验收Java进程6052（类目真实）、31820（澄清替身）均退出，端口56700、51986均无监听；费用验收三个子进程和认证回归也已检查退出。没有本轮需要保留的应用服务或在途付费调用。`.private/runtime`正式演示账本本轮未创建；各探针账本在独立报告目录，不能拿来作运行预算。

以下是用户已有演示稿改动，未提交、未覆盖，接续时继续保留：

```text
 D docs/presentation/Text2SQL_Course_Design_Presentation.pptx
 D docs/presentation/generate_presentation.py
 D docs/presentation/index.html
?? .slidep/
?? Text2SQL中期汇报/
?? generate_midterm_ppt.py
?? scripts/build_midterm_presentation.py
?? 汇报_完整版.pptx
```

18:00接续heartbeat执行后按其指令关闭自身；14:00停止heartbeat也应在执行后关闭自身，不删除18:00安排。此处保存的时间安排不等于目标验收完成。
