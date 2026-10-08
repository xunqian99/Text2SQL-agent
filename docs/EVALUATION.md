# 全流程基准评测报告与消融实验归档

最新归档日期：2026-10-08 | 报告文件：[reports/eval-20261008-185229.json](../reports/eval-20261008-185229.json)

---

## 0. 全流程演进与核心消融实验总览（Ablation Study）

系统在标准电商数仓评测集（100 题，涵盖单表基础、单表聚合、时间窗口、多表关联、深层关联、业务语义 6 层难度）上的端到端消融演进历程如下：

| 演进阶段 / 核心模块 | 执行准确率 (EX) | SQL 有效率 | P95 延迟 | 核心变化与量化贡献归因 |
|---|---:|---:|---:|---|
| **Phase 1: 全量 Schema Baseline** | 47.00% | 78.00% | 11.7s | 初始无检索形态，输入包含全库 37 张表，Prompt Token 达 2315 |
| **Phase 2: + Schema 拓扑图检索 (Top-8)** | 52.00% | 92.00% | 8.5s | **+5.0pp** 准确率，Prompt Token 暴降 **69.3%**（2315 → 709），去除噪声表干扰 |
| **Phase 4: + 业务口径语义层 (MetricRegistry)** | 71.00% | 97.00% | 4.2s | **+19.0pp** 准确率，业务语义层（T6）从 25.0% 暴涨至 **91.67%**，消灭口径盲猜 |
| **Phase 5: + Steiner Tree + 实体枚举对齐 + 代价护栏** | 77.00% | 99.00% | 3.4s | **+6.0pp** 准确率，解决 3+ 表绕路、实体代码瞎猜（ValueRetriever）与 EXPLAIN 笛卡尔积防误杀 |
| **Phase 7: + 时序列保全 + 实体寻优规范 + 最终闭环** | **84.00%** | **100.00%** | **10.3s** | **+7.0pp** 准确率，T1 达到 **100% (20/20)**，T4 达到 **86.36% (19/22)**，SQL 有效率达 **100%** |

### 最新基准评测结果（报告：`eval-20261008-185229.json`）
- **测试模型**：`deepseek-flash`（Prompt 版本：`p8-semantic-pruning-v8`）
- **综合执行准确率**：**84.00% (84/100)**
- **SQL 有效执行率**：**100.00% (100/100)**
- **自动化测试套件**：**248 项全部全绿通过（100% PASS）**
- **难度分层准确率表现**：
  - `single_table_basic`（单表基础 T1）：**100.00% (20/20)**
  - `single_table_agg`（单表聚合 T2）：**80.00% (16/20)**
  - `time_window`（时间窗口 T3）：**87.50% (14/16)**
  - `multi_join`（多表关联 T4）：**86.36% (19/22)**
  - `multi_join_deep`（深层多表 T5）：**50.00% (5/10)**
  - `business_semantics`（业务口径 T6）：**83.33% (10/12)**

---

## 1. 历史阶段收尾结论与早期归档

阶段 2 的检索实现、测试、真实链路测量与文档已收尾，**验收不是全部通过**：

| 原定验收项 | 实测 | 判定 |
|---|---|---|
| Top-5 表平均召回率 ≥ 90% | 96.53% | 达标 |
| 执行准确率比 baseline 提升至少 8 个百分点 | 47% → 52%，提升 5 个百分点 | **未达标，保留原目标** |
| 上下文输入 token 比 baseline 下降 | 2314.71 → 709.46，下降 69.35% | 达标 |

最终归档配置是检索开启、`top-k=8`、连通性修复开启；原有 Top-5 召回验收口径不改。
基础配置文件的检索默认仍是关闭，运行检索版需要显式开启。阶段 3 尚未开始。

本轮 `mvn test`：**63 条通过，0 失败，0 错误，0 跳过**。
包括校验 19、结果规范化 14、SQL 提取 7、检索 12、表召回 11。
这些是单元测试，不等同于再次完成了 100 次真实模型调用。

## 2. 正式报告与对照条件

| 角色 | 报告 |
|---|---|
| 全量 schema baseline | [eval-20261002-151939.json](../reports/eval-20261002-151939.json) |
| 阶段 2 最终 Top-8 | [eval-20261002-160914.json](../reports/eval-20261002-160914.json) |
| dev Top-5 取舍依据 | [eval-20261002-153023.json](../reports/eval-20261002-153023.json) |
| dev Top-8 取舍依据 | [eval-20261002-155612.json](../reports/eval-20261002-155612.json) |
| 早期 eval Top-5，保留历史结果 | [eval-20261002-150831.json](../reports/eval-20261002-150831.json) |

正式两份报告的共同已记录条件：

- `dryRun=false`，`split=eval`，100 条，`limit=0`，全部难度层。
- 模型 `ernie-4.5-turbo-32k`，服务地址 `https://qianfan.baidubce.com`。
- prompt 版本 `p1-full-schema-v1`；数据画像、外键信息均开启。
- 护栏 `limitMode=APPEND`，结果行数上限 `maxRows=200`。

对照的主要开关是 `retrievalEnabled=false/true`。两份都记录 `retrievalTopK=8`，
但检索关闭时 K 不参与选表。报告未记录代码 commit、评估集哈希和全部参数，
所以只能称为相同已记录条件下的单轮内部对照，不能宣称严格因果或统计显著。

## 3. 实测数字

| 指标 | Baseline | 最终 Top-8 | 变化 |
|---|---:|---:|---|
| 执行准确率 | 47.00%（47/100） | 52.00%（52/100） | +5 个百分点 |
| SQL 有效率 | 78.00% | 92.00% | +14 个百分点 |
| 平均输入 token | 2314.71 | 709.46 | 下降 69.35% |
| 平均 DDL 字符数 | 7401.00 | 1579.55 | 下降 78.66% |
| 平均召回表数 | 37.00 | 6.69 | 上下文不再包含整库 |
| 表全召回率 | 100.00% | 93.00% | 7 条未完整召回 |
| 表平均召回率 | 100.00% | 98.48% | 不等于 98.48% 的题完整召回 |
| 表平均精确率 | 5.05% | 28.42% | 噪声表占比降低 |
| 平均延迟 | 4837.34ms | 4442.58ms | 下降 8.16% |
| P50 延迟 | 3489ms | 3203ms | 下降 286ms |
| P95 延迟 | 11721ms | 8496ms | 下降 27.51% |
| 平均金额成本 | 报告为 0 元 | 报告为 0 元 | **未有效估算，不能当免费** |

分组结果显示收益并非均匀分布：

| 难度层 | Baseline | 最终 Top-8 |
|---|---:|---:|
| 单表基础 | 85.00%（17/20） | 85.00%（17/20） |
| 单表聚合 | 75.00%（15/20） | 65.00%（13/20） |
| 时间窗口 | 43.75%（7/16） | 68.75%（11/16） |
| 多表 join | 31.82%（7/22） | 45.45%（10/22） |
| 深层 join | 10.00%（1/10） | 10.00%（1/10） |
| 业务语义 | 0.00%（0/12） | 0.00%（0/12） |

缩短上下文不保证所有题都更准。深层 join 与业务语义仍是薄弱项；
这为阶段 3 的路径规划、阶段 4 的指标口径提供方向，不是它们已经有效的证据。
项目总体的 P95 < 8 秒目标目前也尚未达到。

## 4. 为什么归档 Top-8，而不是挑最高的历史准确率

开发集上的真实链路取舍：

| 配置 | 执行准确率 | 表全召回率 | 表平均召回率 | 平均召回表数 | 平均 DDL | 平均输入 token |
|---|---:|---:|---:|---:|---:|---:|
| dev Top-5 | 46% | 89% | 97.32% | 4.73 | 1167.00 | 595.73 |
| dev Top-8 | 48% | 96% | 99.20% | 6.68 | 1548.08 | 698.72 |

Top-8 在 dev 这轮多答对 2 题、完整召回多 7 题，代价是平均多约 103 个输入 token。
选择它是为多表关联留余量，并参考开发集结果，不是因为数字 8 本身有特殊意义。

必须同时保留一个不利证据：早期 **eval Top-5 为 54%，高于最终 Top-8 的 52%**。
它与最终 Top-8 的已记录配置主要差异是 K，不能编造“模型不同所以不可比”的理由，
也不能说 Top-8 已被证明在 eval 执行准确率上更好。单轮差异可能受到模型波动影响，
但没有重复实验就不能确定原因。

Top-K 是词法命中与关系扩展候选排序后的上限，**不是直接命中种子表的上限**。
选出 K 张之后，连通性修复最多再补 2 张桥接表；无词典命中时会回退全量 schema。
因此 K 不是任何情况下最终表数的硬上限，也不能用 K=5 推算一个简单的理论准确率上限。

## 5. 指标到底在测什么

- **执行准确率**：当场执行 `gold_sql` 得到期望结果，将生成 SQL 的结果规范化后比较。
  每题对/错二值计分；Java 运行器不以 `gold_results.json` 快照作为评分依据。
- **SQL 有效率**：状态为 `SUCCESS` 的占比，即通过护栏并执行成功；答案错也计入。
  不是“SQL 语法正确且业务口径正确”的比例。
- **表全召回率**：期望表全部被召回的题数 / 有效题数。期望表来自 gold SQL 的解析，
  排除 CTE 别名；gold 的关联选择会影响这个指标。
- **表平均召回率 / 精确率**：逐题算命中表 / 期望表、命中表 / 召回表，再取平均。
  不是把所有题的表合并后计算一个比例。
- **schema 10 张表**：`meta.schemaTableCount` 是本轮各题上下文表数的**最大值**；
  库仍有 37 张表，平均召回 6.69 张，不是每题固定给 10 张。
- **控制台 tokens**：`overall.avgPromptTokens`，只统计输入 token。
  API 响应里的 `llmCall` 另有 `completionTokens` 和 `totalTokens`；
  当前评估汇总并没有给出平均输出或总 token。
- **延迟**：编排层的检索 + 生成 + 校验 + 执行耗时；不包括评估器执行 gold 的耗时。
  P50/P95 使用最近秩法取实测值，不是整轮运行时间除以样本数。
- **金额**：输入 token / 1000 × 输入单价 + 输出 token / 1000 × 输出单价。
  当前单价配置为 0，所以报告没有有效金额估算。缺少 usage 或调用失败时 token
  也可能记录为 0，不能据此断言没有调用。

你需要到千帆控制台核实当前账户所用模型的实际计费，再填写本地的
`agent.llm.input-price-per-1k`、`output-price-per-1k`（单位：元 / 千 token）。
没有实际账单或价格配置，不能宣称“单次成本低于 0.05 元”。

## 6. 结果比较与时间规则

当前实现见 `ResultNormalizer`；Python 离线工具的规则应与 Java 保持同步。

1. 数字四舍五入到 4 位小数，并去掉多余零；`610.0` 与 `610` 可等价。
2. NULL 与空字符串区分；不把空字符串当作 NULL。
3. 完整日期与当天零点 timestamp 统一展示形式：
   `2018-01-01`、`2018-01-01 00:00:00.0` 都归一为 `2018-01-01 00:00:00`。
   保留时分秒；非零小数秒、带时区后缀的文本不做换算。
4. `2018-01`、`2018-Q1` 保留原样，不猜月初或季度首日。T3 gold 已改用完整日期/
   `DATE_TRUNC` 等输出，避免 `TO_CHAR` 产生有损月份标签后再让比较器补猜。
5. 行顺序由 gold SQL 判断：同时含 `ORDER BY` 与 `LIMIT` 才按有序列表比较，
   否则按保留重复行的多重集比较。
6. 列名不参与比较，但**列位置参与比较**；当前不支持自动把换序后的列对齐。

例如“每个州有多少订单”只要州与数量配对一致，少写展示用的排序不应判错；
“销量最高的 10 个”有排名语义，gold 的排序与 LIMIT 会要求顺序一致。
这是项目当前评分约定，不是所有业务的通用真理：顺序检测只是字符串启发式，
尚未做顶层 AST 分析；并列排名和题目明确要求排序的特殊情况仍需审核。

dry-run 的 100% 只表示已有 gold 样本经过链路自检通过，既不是模型成绩，
也不能证明规范化永远正确，更不能把所有真实失败一概归咎于模型。

## 7. 失败与漏表清单

最终 Top-8 **48 条失败**，当前报告能给出的链路级分类如下：

| 失败状态 | Baseline | 最终 Top-8 | 含义 |
|---|---:|---:|---|
| `SUCCESS` 但结果不匹配 | 31 | 40 | 执行成功，未答对 |
| `EXECUTION_FAILED` | 21 | 7 | SQL 执行失败 |
| `REJECTED` | 1 | 1 | 被护栏拒绝 |
| 合计 | 53 | 48 | 不等于失败都已精确归因 |

这里不能把 40 条直接叫作“口径错误”：还可能是列选择、过滤、join、输出列位置，
甚至 gold/比较器问题。报告保留失败 SQL，但没保存每条 expected/actual 完整结果；
细分根因需要审核 SQL 与业务定义，不能仅根据状态自动下结论。

真实例子 `T1-021` 问“里约热内卢州（RJ）的卖家有多少个？”，报告中的 SQL 是：

```sql
SELECT COUNT(DISTINCT seller_id)
FROM sellers
WHERE seller_state = 'rj'
LIMIT 1;
```

它的状态为 `SUCCESS`，但结果不匹配。小写 `'rj'` 与枚举值大小写值得优先核查；
不能因为数据库执行了，就把这题计为正确。报告没有结果数，本文不编造数量。

最终 Top-8 不完整召回的 7 条：

| id | 漏表 |
|---|---|
| T4-020 | `orders` |
| T5-001 | `customers` |
| T5-007 | `member_levels` |
| T5-010 | `product_category_translation` |
| T5-012 | `customers` |
| T5-018 | `order_items` |
| T6-008 | `orders` |

旧路线图曾把其中部分关联说成“冗余 join，漏掉是正确行为”，本次纠正：
T6-008 的 `orders` 用来排除取消/不可用订单；T5-001 的 `customers` 参与地区与会员关联；
T5-018 的 INNER JOIN 即使不输出明细列，也可能过滤无明细订单，不能直接删。
T5-007 的会员等级关联是否符合题意仍需口径审核。**不通过删 gold 表来刷召回率**。
连通性只能解决“图上能连接”，不能证明关联路径与统计口径正确。

## 8. 实验局限与后续纪律

早期诊断看过全部 200 条，且 eval 上已有多次模型/K/配置运行；
不能再写“eval 从未见过、只验收一次”。既有结果仍可作为内部回归参照，
但存在测试集反馈影响决策的风险，不能直接当作无泄漏泛化成绩。

当前只有单轮运行，报告也未记录完整版本与数据哈希。阶段 2 不追加付费调用来
挑一个好看的数字；后续应先在 dev 调参、冻结配置，再做阶段验收。
若要证明泛化或显著提升，应另建未见测试集、做重复实验并补齐版本快照。
gold 修正与比较器变化要记录版本，并重新测量受影响的对照，不能混用历史口径。

## 9. PowerShell / IDEA Terminal 复现

以下是复现入口，**收尾不要求你再跑**。先启动已有 Docker 数据库，确认
本地 `src/main/resources/application-local.yml` 配的是千帆；
Key 不提交到 Git。两轮期间保持数据、代码、模型、画像、护栏等配置不变。

```powershell
# 在 IDEA Terminal 中，工作目录为 C:\Users\xunqian\Desktop\agent
& 'D:\develop\Maven\apache-maven-3.9.4\bin\mvn.cmd' test

# baseline：关闭检索，eval 完整 100 条
& 'D:\develop\Maven\apache-maven-3.9.4\bin\mvn.cmd' spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --agent.llm.model=ernie-4.5-turbo-32k --agent.eval.enabled=true --agent.eval.dry-run=false --agent.eval.split=eval --agent.eval.limit=0 --agent.retrieval.enabled=false --agent.prompt.include-data-profile=true --agent.prompt.include-foreign-keys=true --agent.guard.limit-mode=APPEND --agent.db.max-rows=200"

# 检索版：Top-8，显式开启连通性修复
& 'D:\develop\Maven\apache-maven-3.9.4\bin\mvn.cmd' spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --agent.llm.model=ernie-4.5-turbo-32k --agent.eval.enabled=true --agent.eval.dry-run=false --agent.eval.split=eval --agent.eval.limit=0 --agent.retrieval.enabled=true --agent.retrieval.top-k=8 --agent.retrieval.bridge-repair-enabled=true --agent.retrieval.bridge-repair-max-tables=2 --agent.prompt.include-data-profile=true --agent.prompt.include-foreign-keys=true --agent.guard.limit-mode=APPEND --agent.db.max-rows=200"
```

不要在命令尾部加 `\`，PowerShell 不用它续行。`limit=0` 表示不截断；
当前加载器先截断、再按 id 哈希切分，所以 `limit=100 + split=eval` 会得到约 50 条。
启动后检查 `开始评估：100 条，dryRun=false` 与模型名；结束后核对新报告的 `meta`。
模型生成有波动，新一轮不会保证恰好重现 47%/52%。

## 10. 你现在需要掌握什么

先读本文第 3、5、7 节，分别掌握“效果多少”“花了多少”“错在哪一层”。
再看下面 4 个入口，不用从 DTO/getter 开始：

| 文件 / 方法 | 阅读目的 |
|---|---|
| `retrieval/LexicalSchemaRetriever.java#retrieve` | 词法命中、扩展、排序、补桥如何选表 |
| `retrieval/LexicalSchemaRetriever.java#repairConnectivity` | 为什么只补结构性缺口，而不是无差别调大 K |
| `orchestrator/Text2SqlOrchestrator.java#ask` | 问题 → schema → SQL → 校验 → 结果的主链路 |
| `evaluation/EvalRunner.java#evaluateOne` | 如何取得 gold 结果、比较并计分 |

上述路径均相对 `src/main/java/com/text2sql/agent/`；
DTO、配置 getter/setter 与测试脚手架可先跳过。

系统架构与技术选型应清晰解释三个核心取舍：

- 为什么不用整库 DDL？这轮输入 token 降约 69%，但仍有 7 条漏表，压缩有代价。
- 为什么不无限扩大 K？dev Top-8 提高完整召回，但 token 增加；eval Top-5 的 54%
  历史结果也必须承认，不能声称 Top-8 总是最优。
- 为什么 SQL 有效 92%，准确率只有 52%？执行成功不等于业务正确；40 条成功执行却
  结果不匹配，需要继续审核关联、过滤、口径与评分边界。

你需亲自确认千帆单价，以及 T6 的 GMV、有效订单、动销率等业务口径。
若随后开始阶段 3，以本次归档的 52% 为对照并单独统计多表表现；
本次收尾不实现阶段 3，也不通过修改验收目标把 +5 写成 +8。
