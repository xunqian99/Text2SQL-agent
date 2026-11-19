# B独立审查提交3：REJECT，B0仍不成立

提交3仍会漏记已经发生的工具轮用量。目录发送防线已经关闭原B-01/B-02漏口，但“已发送用量保留”出口没有全部成立，因此结论是 **REJECT**，不能建立B0或进入C/D。历史提交1、2的拒绝和失败保持原状；本次是用户取消B免费次数上限后正式编号的提交3，没有重置次数。

被审生产/测试版本为`54d7a93`，授权提交`93c7d3b`。候选JAR及保存副本SHA256均为`7ed0b6a449ec18a9f4a1f9349b3a33ada96cd9515e99dc876d3b3c962f32f668`。后续证据提交不改变这个候选身份。探针使用固定`target/classes`与79个固定依赖，在审查目录单独javac编译；没有运行Maven、改写target或修改生产源码/现有测试。

## 阻断项B-03：工具轮异常只携带当前调用，或完全不携带用量

最小反例之一：关闭RequestBudget（CostQuota也停用），启用工具模式，`maxRounds=1`。使用真实`LlmSqlGenerator`，唯一模型端点是内存`ChatModel`。

1. 授权目录健康；`AGENT_ROUND_1`实际进入`model.call`，返回未知工具名。首轮合成用量A为19输入、7输出，共26 Token，缓存命中5、未命中14，合成费用¥0.0351。
2. 未知工具没有进入ToolExecutor，AgentLoop按正常可恢复Observation进入`AGENT_FINALIZE`。目录仍健康，第二次实际发送发生。
3. 第二次返回`finishReason=length`，用量B为23输入、11输出，共34 Token，缓存命中9、未命中14，合成费用¥0.0459。在第二次调用回调中，授权目录切为空；这不依赖“第N次目录读取”。
4. `callWithTools`抛OUTPUT_TRUNCATED并携带B；`AgentLoop.nextTurn`只对SCHEMA_UNAVAILABLE合并prior，故A丢失。编排重试前取得空目录，终态正确拒绝SCHEMA_UNAVAILABLE、模型不再发送、SQL/EXPLAIN均0。
5. 响应只保留B的34 Token/¥0.0459；独立trace完整记着A+B的60 Token/¥0.081。缺失的是已经发生的26 Token/¥0.0351，并非未知用量。

另两个反例使用真正的空文本，确保触发`SqlExtractor.extract`的UNPARSEABLE：

| 反例 | 实际发送 | 应保留 | 终态响应 |
|---|---:|---|---|
| 首轮无工具、文本为空，首轮后目录变空 | 1 | A：26 Token、¥0.0351 | `llmCall=null` |
| 首轮未知工具，finalize文本为空，第二轮后目录变空 | 2 | A+B：60 Token、¥0.081 | `llmCall=null` |

这两条在AgentLoop中先拿到已计量的AgentTurn，随后在SQL提取处抛出没有callRecord的异常；编排目录终态因此拿不到先前用量。三条反例均在预算关闭时失败，对应预算开启的三条控制通过，因为RequestBudget的独立账本补回了响应用量。目录/用量正确性不能依赖该开关才能成立。

源码定位以固定提交为准：AgentLoop第120行附近的首轮SQL提取、161行附近的finalize SQL提取，以及175—186行的`nextTurn`异常包装；固定原文已保存为`AgentLoop-submission3-fixed.java.txt`。没有为实现者写修复。

以上金额全部来自探针自行设置的非零合成价格，用于审计字段和累计关系，不是供应商实际费用。真实供应商调用/Token/费用均0。

## 完整分母：首次结果、假设更正与补充结果分别保留

| 独立批次 | 分母 | 通过 | 失败 | 解释 |
|---|---:|---:|---:|---|
| 旧101项构造/故障源适配副本 | 101 | 101 | 0 | 旧原文未动；57项未授权/虚构/错属对象仍在SQL/EXPLAIN前拒绝 |
| 真实发送首批 | 338 | 324 | 14 | 首批原始结果没有改写 |
| 只补首批受影响项 | 14 | 14 | 0 | 12项更正审查假设；2项改成限定错列的新增控制，不替换原裸列观察 |
| 补充入口/健康路径/工具异常 | 30 | 29 | 1 | 1项真实阻断为两轮后截断漏掉首轮用量 |
| 必需依赖构造 | 4 | 4 | 0 | 目录、生成器、编排、工具缺失授权依赖均拒绝构造 |
| 真正空输出 | 4 | 2 | 2 | 首轮/finalize空文本，预算开/关对照；2项真实阻断 |

合计保留491条原始断言记录：474通过、17失败。17项失败归因为12项审查假设、2项已证实既有限制、3项真实B阻断。这个总数包含原失败及其补充检查，不能称为491个互不重复场景，也不能折算业务准确率。全部首次输出均为CREATE_NEW写入；未用补跑覆盖原分母。

首批12项审查假设错误是：8项把“关闭且未启用持久化的会话仍保留内存关闭状态”误判为应从store消失；4项要求不由自身启动BudgetRunner的固定SQL自检入口必须自带budget快照。更正后分别核对`isClosed`及在外层显式绑定预算，12/12通过。修改仅发生在本目录的独立探针。

部分原探针把非空字符串“没有SQL”命名为UNPARSEABLE，但SqlExtractor负责包装清理，不负责SQL语法解析；这类文本会继续交给SQL校验，不能作为真实提取失败的证明。相关目录准入/实际发送断言仍可观察，但不能据名称宣称覆盖了生成提取失败。实现者同类fixture也有此覆盖局限。最后独立空输出批次使用`""`，明确触发UNPARSEABLE，才暴露上述漏账。

## 已通过的局部边界

真实生成器矩阵在`ChatModel.call`处计发送；没有mock整个generator来证明发送0。覆盖首答、直接纠错、callMessages、callWithTools，目录null/空/异常/未实现及12种损坏结构/身份；恒定故障首轮0、调用账本0、未发送费用预留0。预算开启/关闭、允许的BYOK动态内存模型入口及费用模式禁止BYOK的现有策略均检查。

目录在首次发送后失效时，合法工具、损坏JSON、未知工具分别覆盖下一工具轮与强制收口；同义重演了提交2的8个预处理漏口，额外发送均0。生成异常与自动/显式纠错目录准入、会话改写后目录失败、缓存命中、固定SQL均检查。正常目录下仍能重试、纠错、改写、工具执行及收口，不能靠关闭这些能力过关。已知用量的Token、缓存Token、字符数、耗时、费用及合并关系分别核对；未知用量保留UNKNOWN状态和已发送预留，没有按0退款。

旧扩展33项没有冒充“原文重跑”：其故障源已不再适用，改由新矩阵同义覆盖。原4种恒定目录故障×首答失败的8项，以及4种故障×两种工具预处理失败的8项，均保留旧身份/失败材料，并以真实发送入口加强检查。检索provider只实现`provide(question)`、默认`fullSchema`未实现且独立授权目录健康的正例通过，证明没有继续向检索接口猜授权。

授权身份没有扁平化：模型输入是PART检索/剪枝对象；共享AuthorizedSchemaCatalog取得FULL局部授权快照，供发送准入、最终校验、缓存、工具使用。FULL专属标记没有进入模型消息，响应检索统计仍为1张表。表到列/别名归属仍由现有校验器处理；该补丁没有修改TopK8、0.4衰减、桥接2表上限，也没有把授权快照参与检索排序。

## 裸列观察：既有限制，不删记录，也不包装成全部列安全

首批2项使用SAMPLE_QUERY的`SELECT invented FROM orders LIMIT 1`。mock数据库确实各进入一次工具SQL和一次最终SQL（每项SQL计数2），因此原断言失败是事实。

独立从SHA256已确认的p21 JAR仅提取.class到本审查目录，用相同79个依赖运行同一个SAMPLE包装：

```sql
SELECT * FROM (SELECT invented FROM orders LIMIT 1) AS sample_subq LIMIT 5
```

p21与候选3均让裸列通过静态校验；将内层改为`SELECT o.invented FROM orders o LIMIT 1`，两者均以UNKNOWN_COLUMN拒绝。两份报告包含实际类加载位置。固定源码对照还确认SqlValidator相对A仅有javadoc变化，SAMPLE包装语义没有改变。

随后用公开测试的`text2sql_ro`本地只读账号独立连接PostgreSQL，开启只读事务，执行裸列/限定列两条包装SELECT。两条均SQLState **42703**（undefined_column）、返回0行；没有读取机密配置，也没有输出业务数据。本审查数据库连接1，SELECT尝试2。

因此这2项是既有裸列/派生关系静态类型边界，本轮没有观察到新增危险对象放行。但不能宣称“所有不存在的列都在数据库前拦截”；真实DB只证明这两条未知列最终拒绝，不证明任意派生列、跨作用域别名或其它SQL都安全。旧57项未授权对象组合新增危险放行为0。

## 版本与实施者证据核验

- 清单1,599/1,599、源码/资源255/255、编译件431/431无漂移；生产JAR内240个对应文件与固定编译件一致。最后一次运行后再次核对431/431及JAR身份，仍一致。
- 79/79个依赖与保存p21 JAR字节一致；该历史JAR SHA256为`e8a3db8efba97e675432b995018b23b016088604cc446513c2d84f85703fe764`。没有重建历史版本。
- 实际首答400/400逐字相等，角色、文本、消息数组顺序、问题及检索表/DDL统计均核对；三份复用p21输入文件与既有冻结对照哈希一致。PromptTemplate、检索打分/剪枝/路径组件及application.yml在A到固定候选之间无差异。
- 独立重算实施者最终XML为100套、1379项，失败/错误/跳过0；不叠加focused旧XML。Gold400、5组本地HTTP相关集成（目录11项、鉴权36项、费用4场景、会话3场景、计时4场景）的固定原产物及清单已核对。它们属于实施者运行，本审查没有独立重新运行，不拿它们增加上述491分母。
- 提交1的独立归档哈希和提交2清单中396份历史材料无漂移；旧失败候选、旧拒绝、旧探针原文均保留。

核验工具自身有三次问题，全部保留说明：旧Windows PowerShell不支持ToHexString；最终clean已移除旧依赖提取目录；首次序列化核验错误地要求测试类打入生产JAR。分别改用兼容哈希转换、保存p21 ZIP依赖、仅核对生产打包文件，产生单独corrected报告，原first报告未覆盖。这些属于审查工具问题，不能伪造为候选漂移，也没有隐藏原输出。

## 证据与剩余边界

主要文件为`decision.json`、`original-101-results-first.json`、`real-send-results-first.json`、`followup-corrected-subset/real-send-results-first.json`、`supplemental/supplemental-results-first.json`、`empty-tool-output/empty-tool-output-results-first.json`及对应run文本。裸列对照见`bare-column-baseline-first.json`、`bare-column-candidate-first.json`、`local-readonly-sqlstate-first.json`；身份见`evidence-verification-corrected.json`、`final-compiled-identity.json`、`retrieval-prompt-diff.json`。所有探针及核验工具仅在本目录。

目录仍是进程内快照，本轮证明取得快照的门槛与权限来源，没有证明数据库实时在线、在线DDL/授权刷新或完整SQL编译。发送前健康、发送后新失效时必须保留已发生费用，不能预知故障并把已发送调用当免费。当前3个漏账反例正好违反这条已授权要求，所以不能以“未新增SQL放行”或“预算开启正常”宣布B0。

本审查未参与候选实现/出题/调参，未读blind、holdout或封存逐题，未修改参考/评分/阈值、生产源码或现有测试，未Git提交。供应商调用/Token/实际费用0；本地只读DB连接1。没有测模型正确率、真实P95或供应商成本改善，阶段2未通过，C/D仍不准入。
