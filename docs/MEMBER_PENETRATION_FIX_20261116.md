# 会员订单占比：生产口径修复与验收

## 结论与范围

修复生产 `member_penetration` 指标的计数实体、连接覆盖范围和空分母，并让SQL缓存随指标文件内容失效。仍在阶段3处理已确认的输入缺陷，不宣称完成阶段4的全部语义治理，也不宣称阶段3或整体项目通过。

本轮没有改评测题、Gold SQL、标准结果、评分器或历史分数。原题库仍200道，另100道AI候选题独立存放。本轮新增的是代码契约测试，不是新增评测题；未调用真实模型，模型Token与费用均0，未测新准确率或P95。

## 技术与设计导读

### 1. 请求怎么流转

以“会员订单占比是多少？”为例：

1. `MetricRegistry` 对指标名/别名做关键词匹配，得到 `member_penetration`；再确认本次Schema上下文包含 `orders/customers/members`，缺依赖表就不注入。
2. 全量模式的 `FullSchemaProvider` 和检索模式的 `HybridSchemaProvider` 都通过 `Metric.render()` 注入同一条定义。定义含表达式、有效订单过滤、连接模板、输出单位和边界说明，之后进入 `PromptTemplate.userPrompt()`。
3. 模型据此生成SQL，再走现有只读校验与执行。注册表只提供约束文本，不偷偷替换用户SQL，不按评测题ID写分支；模型仍可能不遵守提示，因此字典修复不等于模型正确率100%。
4. 同一会员通过两个 `customer_id` 贡献2笔有效订单，另一个非会员贡献1笔：分子是会员订单ID集合 `{o1,o2}`，分母是全部有效订单ID集合 `{o1,o2,o3}`，返回 `66.67`，单位为百分数。

旧式 `COUNT(DISTINCT member_id)` 把人数当订单，得到33.33%。即使改对分子，先INNER JOIN会员也会把非会员订单排除，分母剩2笔，得到100%。必须把计数实体和连接范围一起修。

### 2. 核心机制、身份标记与边界

生产定义在 `src/main/resources/schema/metrics.yml`，本次仅改这一项的内容，别名、依赖表及状态过滤不变：

```sql
SELECT ROUND(
  100.0 * COUNT(DISTINCT o.order_id) FILTER (WHERE m.member_id IS NOT NULL)
  / NULLIF(COUNT(DISTINCT o.order_id), 0), 2
) AS member_order_pct
FROM orders o
LEFT JOIN customers c ON c.customer_id = o.customer_id
LEFT JOIN members m ON m.customer_unique_id = c.customer_unique_id
WHERE o.order_status NOT IN ('canceled', 'unavailable');
```

- **订单身份**由 `o.order_id` 保留；**人的关联身份**由 `customer_unique_id` 保留。不同订单使用不同 `customer_id`，不能据此判断是不同的人。
- `m.member_id IS NOT NULL` 只标记某笔订单是否属于会员，放在聚合FILTER里；不能放到WHERE里过滤整行，否则LEFT JOIN退化、分母丢失非会员订单。
- 分子和分母都按订单ID去重。同一人有多条会员记录时，Join会扇出，但不会把一笔订单多算。这是集合计数，不涉及打分权重或Top-K。
- 百分数用 `100.0` 避免整数除法，`ROUND(...,2)` 保留两位；空分母通过 `NULLIF` 返回NULL，含义是“无有效订单，比例未定义”，不是0%。
- canceled/unavailable排除；NULL状态依照原SQL `NOT IN` 的三值逻辑也排除。本次没有擅自改变有效订单口径。
- 客户缺失或人的标识为空时，LEFT JOIN保留有效订单在分母，无法匹配的订单不进入会员分子。判断依据是**当前会员表**，并未实现“下单时是否已入会”的历史会员计算。
- 通用 `Metric.render()` 仍含原有子集过滤例外文字。本轮未治理所有指标的过滤优先级；题目自带范围与指标过滤冲突时仍存在需要后续处理的边界。

### 3. 指标指纹如何阻止旧SQL继续命中

`MetricRegistry` 内部 `SourceSnapshot` 在JVM首次使用时只读取一次classpath指标文件，保留私有字节快照。YAML解析和 `definitionSha256()` 都使用这一份快照，不在每次请求重新读磁盘。资源不存在沿用空注册表降级并记录 `missing`；读取或解析错误仍导致初始化失败，不能假装正常。

编排层 `cacheSignature()` 在启用指标注入时追加 `metrics=<SHA-256>`，关闭时为 `metrics=-`。因此相同问题、相同模型、相同Prompt版本，只要指标文件内容变了，新启动进程的缓存键就会变化，L1与持久化L2都避开旧SQL。旧记录不被删除，后续按现有缓存生命周期处理。

这是全文件指纹：改任意指标会保守地使全部启用语义注入的SQL缓存失配；只改注释或换行也会失配。代价是更大的冷启动范围，好处是不必为了算缓存键先跑一遍每题指标检索，失效逻辑更容易解释。进程内不支持热更新，编辑文件后必须重新构建/重启；它不是实时文件监控。

评测 `effectiveConfig.metricDefinitionsSha256` 记录资源版本；模型JSONL新增 `configuredSemanticEnabled` 与 `configuredMetricDefinitionsSha256`。它们表明配置及定义文件身份，**不证明某条指标实际命中**；实际发送内容仍由 `promptSha256` 追踪，专项探针另外记录渲染文本和最终用户消息指纹。默认系统Prompt版本和文字未改变，本轮改变的是业务上下文。

### 4. 验收路径与已测数字

生产修复提交 `66b37c2`；Gold自检实测也运行在该提交。工作区DIRTY来自用户演示文件和新增证据，不冒称全仓干净。

原始证据目录：`reports/member-penetration-fix-20261116/`。

| 检查 | 修复前 | 修复后 | 能证明什么 |
|---|---:|---:|---|
| 同一组PostgreSQL契约检查 | 6/22通过，14失败、2错误 | 原22项全部通过，另2项新增边界通过，共24/24，0跳过 | 表达式和推荐SQL满足12种业务反例 |
| Java完整测试 | 本轮前的历史证据484/484 | 511/511，0失败、0错误、0跳过 | 本次完整运行未发现已有工程行为回归 |
| 离线Python审计测试 | — | 74/74 | 审计拒绝路径和已有证据工具回归 |
| 两种Provider×4种问法的实际消息注入 | — | 8/8 | 修正的完整渲染定义进入实际用户消息 |
| 关闭指标注入 | — | 2/2 | 两种Provider关闭后均无指标文本 |
| 真实库独立计数、表达式、推荐SQL三方比对 | — | 三方均100.00% | 当前数据上结果一致；单独不足以证明非会员分母 |
| 原200题Gold自检 | — | 200/200，模型调用0 | 校验/执行兼容；不是模型准确率，逐题题意/Gold SQL/结果指纹与历史200/200一致 |
| 原显式指令实验历史证据审计 | — | 通过 | 历史69/100、64/100及失败判定仍保留 |

`MemberPenetrationContractTest` 使用只读VALUES CTE遮蔽同名真实表，无建表、插入或业务数据改写。每个场景分别执行**生产表达式**和**生产queryPattern**，Java以订单ID集合计算独立期望值，覆盖同人多客户ID、多订单、取消/不可用订单、无会员、全会员、仅非会员、空订单、无有效订单、重复会员记录、NULL身份、缺失客户、1/6舍入和NULL订单状态（部分边界合并在同一场景）。

真实库独立计数使用外层订单COUNT加嵌套EXISTS判断会员，避免照抄被测Join聚合。当前有效订单98,207笔，会员订单也是98,207笔；这是样本盲区：INNER JOIN也可能碰巧给出100%。所以66.67%的混合会员/非会员反例是必需证据，不能用真实库单个数字冒充反例验证。

可复现命令（在项目根目录，PostgreSQL可用）：

```powershell
mvn -q "-Dtest=MemberPenetrationContractTest,MetricRegistryTest,OrchestratorSelfCorrectionTest,PromptVariantReportTest,LlmGenerationModeTest" test
mvn -q test
python -m unittest discover -s scripts -p 'test_*.py'
# 首次构建探针依赖路径；项目已有依赖，无新增版本
mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/member-probe-classpath.txt'
$probeCp = 'target/test-classes;target/classes;' + (Get-Content target/member-probe-classpath.txt -Raw).Trim()
# 输出路径必须不存在，禁止覆盖历史证据
java -cp $probeCp com.text2sql.agent.retrieval.MemberMetricInjectionProbe target/member-metric-injection-new.json
& scripts/run_eval.ps1 -Split all -DryRun -OutputDir reports/member-gold-new -Port 18089
python scripts/summarize_prompt_directive.py reports/prompt-directive-screen-20261116 --evidence-only
```

数据库环境可用 `TEXT2SQL_TEST_DB_URL/USER/PASSWORD` 覆盖；默认沿用本地olist只读账户。本次数据库检查确实执行，不能把数据库不可用导致的跳过记为通过。完整测试裸跑通过不等于模型验收通过。

### 5. 为什么这样做、面试追问

**为什么不用会员人数，或者SUM一个会员标记？** 前者统计实体错；后者在会员表有重复匹配行时会扇出。`COUNT(DISTINCT order_id) FILTER` 明确保留订单集合身份，会员标记只控制分子，不截断分母。EXISTS同样可实现正确计算，本轮选LEFT JOIN结构以兼容现有依赖表和指标表达式注入方式，用独立EXISTS查询作交叉验证。

**为什么不改Gold或直接给这道题写固定SQL？** 生产定义自身与description冲突，错误输入会污染所有别名问法；改Gold不能修生产，按题ID硬编码也不能解释通用业务逻辑。这里改注册表定义，独立反例检验的是业务实体与集合范围。

**为什么不是只升Prompt版本或手动清缓存？** Prompt版本没描述具体指标文件身份；手工清缓存依赖每次发布操作不遗漏。内容指纹让生产定义和SQL缓存自动关联，但保守全量失效会牺牲部分命中率，且必须重启加载新资源。

**这个改动证明了什么，没证明什么？** 证明口径SQL通过边界反例、进入两种上下文路径，并阻断旧版本SQL缓存复用；没有证明模型总会遵守、没有给出新200题准确率、没有解决阶段3的P95。其余23项指标也未因这一项修复而自动获得语义正确性背书。

### 6. 用户需要配合和掌握的事

本轮无需额外配置外部服务。正在运行的旧应用须重新构建/重启才能加载新定义。请能独立解释三件事：为什么分子数订单、为什么会员判定只进入FILTER、为什么当前库100%的结果不能替代66.67%反例。调用成本本轮是0；错误归因是**生产口径实体和Join范围**，不能统称模型不好。

后续仍按阶段3门槛推进：任何真实模型复测须冻结输入与配置、保留失败与未知用量，题目数和请求次数分开报告；不因这一项修复直接追加600次评测或宣布项目验收完成。
