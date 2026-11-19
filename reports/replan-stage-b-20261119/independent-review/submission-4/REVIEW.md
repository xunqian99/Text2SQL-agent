# B独立审查提交4：REJECT，已知响应用量仍会丢失

提交4修复了提交3的3个漏账反例，但空choices响应仍能丢掉已知用量。结论为 **REJECT**，B0不成立，C/D和付费评测不准入。此次不是恢复提交3或重置编号；提交1—3的拒绝及原始结果保持原样。

固定源码为`9d0c3908ec461f2d92821e5a41d9aa42a6b50390`，候选JAR及保存副本SHA256为`187b7ccf081c0449cc9c78b9af0ddb510a31acc4839993337e2c1622c279d389`。本审查在自己的submission-4/classes编译探针，使用固定target类和依赖；未运行Maven、未改写target、生产源码、现有测试、参考、评分或阈值，未Git提交。

## B-04最小反例：费用已结算，响应与调用记录却没有用量

用真实LlmSqlGenerator配内存ChatModel，工具模式`maxRounds=1`。授权目录开始健康，在第一次实际`model.call`回调完成时切为空；模型返回非null的：

```java
new ChatResponse(List.of(), knownMetadata)
```

`knownMetadata`含19输入、7输出、总26 Token，缓存命中5、未命中14。探针使用的非零合成价格对应¥0.0351。

实际链路为：发送准入健康 → 费用预留/调用计数 → `model.call`返回空choices但有usage → `CostQuota.Lease.complete`按metadata结算 → `extractText`发现getResult为空并抛CALL_FAILED → 编排的下一次目录准入取得空目录 → SCHEMA_UNAVAILABLE终止，额外发送0、SQL/EXPLAIN0。

问题在生成器先执行`extractText(response)`，之后才`buildRecord`。异常发生时已知metadata还没转成LlmCallRecord，也没交给callLog/RequestBudget。AgentLoop统一异常出口没有当前callRecord可合并，不能凭空找回它。

| 设置 | 实际发送 | 已知输入 | 实际响应/记录 |
|---|---:|---|---|
| 预算关闭、费用配额停用 | 1 | 26 Token、¥0.0351 | `llmCall=null`，trace调用列表为空 |
| 预算开启、费用配额启用 | 1 | 同一metadata | `llmCall=null`，unknownUsageCalls=1，trace留在IN_FLIGHT |

第二项的费用账本同时明确为knownCalls=1、knownCostYuan=¥0.0351、unknownCalls=0、heldYuan=0。这个对照证明：系统已经收到并采用了已知usage，不允许把响应缺失降格解释成供应商没给用量。即使费用配额没有退款错误，API响应和调用审计仍漏掉真实发生的用量，B的出口不能通过。

该关注最初由根任务在阅读固定源码时提出；本审查者独立构造并运行预算开/关两个反例，未参与修复实现。原始结果位于`empty-choices/empty-choices-results-first.json`，两项首次运行均失败，没有补跑或替换输入掩盖结果。异常顺序的固定源码另存于`LlmSqlGenerator-submission4-fixed.java.txt`。

所有¥金额均为内存模型探针的合成记账数据，不是供应商实际费用。供应商调用/Token/费用0。

## 成套独立分母

| 批次 | 分母 | 通过 | 失败 |
|---|---:|---:|---:|
| 旧101项授权/目录/工具/缓存同义回归 | 101 | 101 | 0 |
| 完整真实发送矩阵 | 338 | 338 | 0 |
| 补充健康入口、累计用量及原截断反例 | 30 | 30 | 0 |
| 必需目录依赖构造 | 4 | 4 | 0 |
| 首轮/finalize真实空文本，预算开/关 | 4 | 4 | 0 |
| 空choices且metadata含已知usage | 2 | 0 | 2 |

合计479项独立断言，477通过、2失败，全部为本候选首次运行，无补跑。另有2项裸列既有边界观察，两项均复现，但明确不计入B通过数字。阶段3的338首批、14项更正/补充、全部失败和审查工具问题没有被此次结果覆盖。

提交3的12项审查假设在本候选执行前已明确更正：未启用持久化的关闭会话检查isClosed；固定SQL自检需要预算快照时由探针显式绑定BudgetRunner。UNPARSEABLE场景现在用真正空文本，健康改写降级和工具提取失败也用空文本，不能再凭“no sql here”等非空字符串的名字声称触发了提取失败。

## 提交3修复确实成立的部分

统一AgentLoop异常出口保留了首轮和后续轮的来源：`combinedRecord`只含已成功返回的轮次；生成器异常携带当前失败轮次；SQL提取在成功轮次已加入combinedRecord后发生，提取异常本身不再另带一次已累计记录。统一出口只合并一次，原工具目录异常不提前重复附prior。

三项旧阻断现在全部通过：首轮空文本后目录故障保留A的26 Token/¥0.0351；finalize空文本和finalize length后目录故障均保留A+B的60 Token/¥0.081。预算开/关控制均通过，未观察到重复累计为86或120 Token。这个结论局部成立，但不覆盖尚未转成record的空choices响应。

完整矩阵直接在内存ChatModel.call处计发送，没有mock整个generator证明“未发送”。目录恒定null/空/异常/未实现及损坏条目时，四个生成入口首轮0；未知工具、损坏JSON在ToolExecutor之前失败，也不再绕过下一轮/收口发送检查。覆盖生成真实空输出重试、自动/显式纠错、会话改写、缓存、固定SQL、健康工具及重试，预算开/关、允许的BYOK入口、费用模式禁止BYOK的策略和未发送预留0。正常目录下能力继续可用。

授权快照和生成上下文仍分离：PART只含原检索/剪枝对象；FULL由独立AuthorizedSchemaCatalog直接取SchemaCatalog，参与本地准入/最终校验/工具/缓存。FULL专属标记没有进入模型消息，检索统计仍为原1张表。表/列归属没有压平成集合，原57项未授权/虚构/错属对象组合均在SQL/EXPLAIN前拒绝，新增危险对象放行0。

## 裸列边界继续公开

两项独立观察仍使用`SAMPLE_QUERY`的裸列`SELECT invented FROM orders LIMIT 1`，各进入一次mock工具SQL和一次mock最终SQL。它们作为既有限制保留，不能计为“全部未知列已拦截”。完整矩阵另用`o.invented`限定错列验证拒绝。

重新使用保存p21类与候选4类，运行相同`SELECT * FROM (...) AS sample_subq LIMIT 5`：两者均让裸列通过静态校验，均以UNKNOWN_COLUMN拒绝限定错列。报告记录实际类加载位置。独立只读PostgreSQL连接1、SELECT尝试2，裸列与限定列均SQLState42703、返回0行；没有读取私密配置或输出业务数据。

这证明本观察属于已公开的裸列/派生关系静态类型能力边界；不是新危险对象放行，也不是所有SQL的安全证明。跨作用域别名、完整类型推导仍不在本轮承诺内。

## 固定身份与历史保全

- 清单524/524、源码/资源255/255、编译件431/431无漂移；生产JAR中的240个对应文件一致。最终再验431件与JAR仍完全固定。
- 79/79个依赖与SHA256为`e8a3db8efba97e675432b995018b23b016088604cc446513c2d84f85703fe764`的保存p21 JAR逐字一致。
- 首答消息400/400逐字相等，角色、文本、数组顺序、问题、检索表/DDL统计均核对；三份p21复用对照与既有冻结文件哈希一致。PromptTemplate、检索打分/剪枝/路径组件及application.yml相对A无差异，TopK8、0.4衰减、桥接2表上限不变。
- 生产源码相对提交3唯一变化为AgentLoop。固定源与工作树核对无差异，随后审查只依赖固定产物。
- 提交1、2归档无漂移；提交3独立审查669份文件及保存的431份编译件全部逐字一致。没有把失败候选改写成成功候选。
- 独立重算实施者最终XML为100套、1399项，失败/错误/跳过0；166项发送边界、47项目录校验及Gold400、5组本地HTTP的固定产物在清单中核对。它们属于实施者运行，未由本审查者独立重跑，也不叠加到479分母。工程全绿仍不覆盖本次空choices反例。

主要独立材料为`real-send-results-first.json`、`original-101-results-first.json`、`supplemental/supplemental-results-first.json`、`empty-tool-output/empty-tool-output-results-first.json`、`empty-choices/empty-choices-results-first.json`及各run文本。版本证据为`evidence-verification-corrected.json`、`history-and-source-verification.json`、`final-compiled-identity.json`；裸列/只读DB证据为`bare-column-baseline-first.json`、`bare-column-candidate-first.json`、`local-readonly-sqlstate-first.json`。

目录仍是进程内元数据快照，不代表数据库实时在线，也不提供DDL/授权刷新。本次没有读blind、holdout或封存逐题，没有更改历史分数、调用额度或验收阈值。没有测模型准确率、真实P95或供应商费用改善；B0和阶段2仍未通过。后续候选应继续编号提交5，而不能覆盖提交4的两项失败。
