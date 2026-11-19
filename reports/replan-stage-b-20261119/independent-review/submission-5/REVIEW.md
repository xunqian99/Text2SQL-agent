# B独立审查提交5：ACCEPT_B0_ONLY

**判决：ACCEPT_B0_ONLY。** 固定提交5在已验B范围内全部通过，可以建立本地工程基座B0。此结论只说明完整授权目录、发送准入及用量保留的工程出口成立；不说明模型准确率、原目标修复、真实P95或供应商成本已改善，不代表阶段2完成，也不准入付费评测。

被审源码为`37017182d67a1fba7d16b399cc616565a784aac0`，候选JAR和保存副本SHA256为`e90b29251a8afa2a6af9ec3586a846ab137f84ef4511a62812c31d775e525a9f`。提交1—4的拒绝和失败全部保留，没有重置编号、删除反例或用本次绿色结果覆盖旧输出。

## 独立首次结果与完整分母

| 独立批次 | 分母 | 通过 | 失败 |
|---|---:|---:|---:|
| 旧101项授权/目录/工具/缓存回归副本 | 101 | 101 | 0 |
| 完整真实发送矩阵 | 338 | 338 | 0 |
| 健康入口、工具累计用量、原截断阻断 | 30 | 30 | 0 |
| 必需授权目录依赖构造 | 4 | 4 | 0 |
| 首轮/finalize真实空文本，预算开/关 | 4 | 4 | 0 |
| 提交4空choices且有已知usage的两个阻断 | 2 | 2 | 0 |
| 四入口接收响应记账矩阵 | 80 | 80 | 0 |

共559项有效独立断言，559通过、0失败，全部首次运行，无补跑。另保留2项裸列既有限制观察，均复现，但不计入B通过数字。上述检查是工程断言，不是559题业务正确率。

旧101项用于授权/编排同义回归；其生成器替身不被当作真实发送证明。所有新增发送/用量探针使用真实LlmSqlGenerator和内存ChatModel，在实际`model.call`处计数。所有独立探针只编译到本submission-5/classes，固定target编译件未被覆盖，没有运行Maven。

## 历史漏口已关闭，已知/未知与一次计量均成立

提交1/2曾出现生成重试和工具预处理失败后继续发送。当前恒定目录null、空、异常、未实现及损坏结构/身份在首轮前均拒绝；四个模型入口、预算开/关、允许的BYOK动态模型均检查，模型调用数0、SQL/EXPLAIN0、未发送费用预留0。未知工具、损坏JSON即使未进入ToolExecutor，也不能绕过下一工具轮或AGENT_FINALIZE的统一发送检查。

提交3的三个漏账阻断已全部通过：预算关闭时，首轮空文本后目录失效保留A的26 Token/¥0.0351；finalize空文本和length截断后目录失效均保留A+B的60 Token/¥0.081。预算开启控制也通过，没有把此前轮次重复计入。

提交4的两个空choices反例本次均正确保留已知用量：1次实际发送后目录失效，响应与trace保留19输入、7输出、26总Token、5缓存命中、14未命中及¥0.0351；预算开启时unknownUsageCalls=0，费用账本knownCalls=1、held=0。预算关闭也不再依赖外层账本补救。

额外80项独立矩阵覆盖generate、generateCorrection、callMessages、callWithTools，输入分别为空choices已知usage、空choices未知usage、null response、null message已知usage，并组合预算关闭/开启、费用配额开启、允许BYOK等5种有效配置：

- 40项已知metadata全部恰好保留26 Token/¥0.0351，缓存Token、promptChars、耗时和配额结算分别核对。
- 40项未知信息继续明确标记UNKNOWN_USAGE；trace的usage为null，预算开启时unknownUsageCalls=1。费用开启时保留UNKNOWN预留¥0.10，不把已发送未知调用当成免费退款。
- 80项trace均恰好1条，实际model.call均1次。没有把未知伪造成已知，也没有重复计量。

合成金额只验证字段、累计与预留关系，不是供应商账单。本次供应商调用、Token与实际费用均0。

## 核心机制与数据流

具体例子：用户问题检索结果只有`orders.order_id`，模型生成包含`orders.customer_id`和`customers.city`的别名JOIN。PART检索上下文继续参与Prompt、会话和召回统计；独立FULL授权快照由AuthorizedSchemaCatalog直接读取SchemaCatalog，仅参与本地发送准入、最终SQL校验、缓存与工具。该SQL可通过完整目录并进入执行，响应仍记录检索1张表，FULL专属标记不会进入模型消息。

数据来源没有平铺混淆：PART是生成身份，FULL是本地授权身份。表到列与别名归属仍保留原关联。目录组件是必需依赖，目录/生成器/编排/工具的缺失依赖构造全部失败；只实现`provide(question)`而未实现历史`fullSchema`的检索替身，配健康独立目录仍能成功，授权不再从检索子集猜测。

实际发送前仍由唯一invoke统一检查目录，顺序早于费用预留、RequestBudget实际调用计数和model.call。目录在首次发送后才失效时，已经发生的发送不能撤回；现有用量必须保留，后续模型与SQL执行被终止。切换故障由实际model.call或明确校验/执行阶段驱动，不依赖“第N次目录读取”。

收到非null模型响应后，四入口现在先从metadata建立record，再解析文本、完整性或SQL。内容异常写入callLog并携带当前record传播。AgentLoop只在统一异常出口合并`combinedRecord`（此前已完成轮次）与异常中的当前失败轮次，合并一次。成功工具仅有tool_calls的响应继续可用；真正空消息返回有类型CALL_FAILED。null response保留明确未知，不凭空编造usage。

该设计选择避免把目录注入Prompt或把检索召回范围扩大成权限白名单；生成输入不变，因此没有靠更多Token补偿错误。它也避免在每个工具失败分支分别打补丁。代价是发送前增加本地快照准入及结构检查；本轮未测真实供应商延迟，不把本地探针速度包装成P95收益。

## 安全范围、正常控制和已公开局限

旧57项未授权、虚构、错属对象组合在最终SQL和相关工具路径均在SQL/EXPLAIN前拒绝，观察到新增危险对象放行0。正常目录下生成重试、自动/显式纠错、会话改写、合法工具、多轮及强制收口仍能成功；不能靠禁用这些能力通过。

缓存和固定SQL从同一授权组件重新取得本地目录，目录故障不会绕过执行。已经发生的首次SQL或工具SQL计数保留，不把后续终态拒绝改写成“从未执行”。普通工具参数错误在目录健康时仍可恢复，目录故障则是有类型终止，不再退化成模型可修复的普通Observation。

裸列边界仍未解决：`SAMPLE_QUERY`中的`SELECT invented FROM orders LIMIT 1`经派生表包装，仍可进入mock数据库；两项观察分别包含一次工具SQL和一次最终SQL，各SQL计数2。相同p21历史类与候选5类均放行该裸列，均以UNKNOWN_COLUMN拒绝`o.invented`限定错列，报告记录实际类加载位置。完整矩阵中的限定错列控制另行通过。

独立用公开测试的本地只读账号连接PostgreSQL，开启只读事务，对裸列与限定错列两条包装SELECT均收到SQLState42703、返回0行。本审查只读DB连接1、SELECT尝试2，没有读取私密配置或输出业务数据。这只证明该观察属于已有静态类型能力边界并且这两条由DB拒绝，不能宣称所有不存在列都在DB前拦住，也不能外推任意SQL安全。

完整授权目录仍是进程内元数据快照；不证明数据库当前在线，不提供运行中DDL/授权刷新。裸列、派生关系、跨作用域别名仍不具备完整静态类型推导。这些公开局限没有被本次通过结论掩盖，也没有扩成完整SQL编译器要求。

## 身份、输入和历史保全

- 固定清单525/525、源码/资源255/255、编译件431/431无漂移，生产JAR中240个对应文件一致；最后一次探针后再次核验431件和JAR仍匹配。
- 79/79依赖与保存p21 JAR逐字一致；其SHA256为`e8a3db8efba97e675432b995018b23b016088604cc446513c2d84f85703fe764`。没有重建历史版本。
- 首答消息400/400逐字相等；核对角色、文本、数组顺序、问题、检索表与DDL统计。三份p21复用对照哈希与既有冻结文件一致。
- PromptTemplate、检索打分/剪枝/路径组件、application.yml相对A无差异。TopK8、0.4衰减及桥接2表上限不变；完整目录只用于本地授权，没有参与检索排序、改变直接命中/扩展身份或挤压Top-K。
- 提交1独立归档、提交2的396份历史材料无漂移。提交3审查669份、提交4审查361份逐字保留；私有保存的提交3/4编译件各431份全部匹配旧身份。
- 当前工作树生产/测试/pom相对固定提交无差异；提交5生产改动相对提交4仅LlmSqlGenerator，AgentLoop的一次合并保持。

独立重算实施者最终XML为100套、1479项，失败/错误/跳过0；246项发送边界、47项目录校验、Gold400可执行、5组本地HTTP原产物在固定清单内核验。HTTP证据为目录11项、鉴权36项、费用4场景、会话3场景、计时4场景，供应商调用0。上述由实施者运行，本审查者没有独立重跑，不叠加到559分母；Gold自检也不是模型正确率。

## 判决边界与证据位置

本候选可以固定为B0，但仅能表述为“误拒修复和目录/发送/用量边界在已验范围成立”。C的输入收敛候选、后续候选冻结与D的付费准入仍须按原协议另行完成，不能因B通过自动触发模型评测。原89%、原目标至少2/3、延迟/费用护栏、付费调用上限和未见隔离均未改变。

主要结果为`decision.json`、`original-101-results-first.json`、`real-send-results-first.json`、`supplemental/supplemental-results-first.json`、`empty-tool-output/empty-tool-output-results-first.json`、`empty-choices/empty-choices-results-first.json`、`four-entry-usage/four-entry-usage-results-first.json`及对应首次run文本。精确用量汇总为`usage-matrix-summary.json`；身份见`evidence-verification-corrected.json`、`history-and-source-verification.json`、`final-compiled-identity.json`；裸列/DB对照见`bare-column-baseline-first.json`、`bare-column-candidate-first.json`、`local-readonly-sqlstate-first.json`。

本审查者未参与候选实现、出题或调参，未读blind、holdout或封存逐题，未修改生产源码/现有测试/参考/评分/阈值，未Git提交。全部新材料只写本submission-5。供应商调用/Token/实际费用0，独立只读数据库连接1。
