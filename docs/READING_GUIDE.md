# 代码阅读指南（活文档）

> **这份文档由 AI 维护，每完成一个阶段就追加一轮。**
> 你的用法：读一轮 → 勾掉「已读」→ 遇到读不懂的回来问 → 需要往下走时问「下一轮读什么」。

## 0. 怎么用这份文档

三个阶段性问题贯穿始终，每读完一轮都应该能回答其中一个：

1. **数据怎么流** —— 一个问题从进来到出结果，经过哪几步
2. **每次调用花了多少** —— token、耗时、成本
3. **错在哪一层** —— 表选错、字段选错、join 错、还是口径错

这三个问题是 AGENTS.md 里明确说 AI 不能替你做的。**每轮末尾的自检题就是在测这个。**

### 读代码的三条原则

1. **按一次请求的旅程读，不按目录顺序读。** `api → config → evaluation` 是字母序，不是依赖序。
2. **只读主干方法，不读分支。** 每个类只精读一两个方法，辅助方法等用到再回来。
3. **先知道这段是为了回答什么，再读。** 读完答不上那个问题，说明读错了地方。

### 关于注释

本项目 Java 代码约 5650 行，其中注释约 1650 行、空行约 660 行，**有效代码约 3340 行**。
注释不是装饰：每个「为什么不用另一种做法」都写在里面。**读注释的信息量大于读代码。**

---

## 1. 进度总览

| 轮次 | 范围 | 精读行数 | 预计时长 | 状态 |
|---|---|---|---|---|
| 第 1 轮 | 编排层主干 `ask()` | 40 | 20 分钟 | 已交付，待你勾选 |
| 第 2 轮 | 检索 → 生成 → 校验 → 执行 | ~300 | 90 分钟 | 已交付，待你勾选 |
| 第 3 轮 | 评估层：准确率怎么算 | ~150 | 40 分钟 | 已交付，待你勾选 |
| 第 4 轮 | 接入层与观测层 | ~80 | 20 分钟 | 已交付，待你勾选 |
| 第 5 轮 | 阶段 2 检索核心 `LexicalSchemaRetriever` | ~180 | 60 分钟 | **本轮新增** |
| 第 6 轮 | 阶段 2 的 bug 修复（校验/执行/比对） | ~120 | 40 分钟 | **本轮新增** |

**如果你现在只有 30 分钟**：做第 1 轮 + 第 8 节的断点练习。这两件事的性价比最高。

---

## 第 1 轮：只读 40 行，看清系统骨架

- [ ] 已读

打开 `src/main/java/com/text2sql/agent/orchestrator/Text2SqlOrchestrator.java`，
从 **第 69 行 `ask()`** 读到 **第 98 行**（方法体到 `return validateAndExecute(...)` 结束）。

整个系统的骨架就是这几行：

```java
SchemaContext schema = schemaProvider.provide(question);   // 检索
generated = generator.generate(question, schema);          // 生成
return validateAndExecute(...);                            // 校验 → 执行
```

**把四个依赖类当成黑盒，不要点进去。** 你只需要知道：

| 依赖 | 给它什么 | 它还你什么 |
|---|---|---|
| `schemaProvider` | 问题 | schema 文本 |
| `generator` | 问题 + schema | SQL |
| `validator` | SQL + schema | 能不能执行 |
| `executor` | SQL | 结果集 |

这四句话就是整个项目的全部。

然后看 **第 122 行 `validateAndExecute()`**，重点看它怎么把「校验失败」和「执行失败」
翻译成不同的 `status`（第 66 行的 `Status` 枚举有五种值）。

### 自检

合上文件，说出 `ask()` 里四步的先后顺序和各自的产出物。

---

## 第 2 轮：顺着数据流走一遍

- [ ] 已读

这一轮按**数据流向**读，每读一个类只回答一个问题：「给它什么，它还我什么」。

### 2.1 检索层（全量版）

`src/main/java/com/text2sql/agent/retrieval/FullSchemaProvider.java`（全文 33 行）

**注意：这个类比阶段一的旧指南短了，因为阶段 2 把它的职责拆走了。**

- `provide()` 在第 33 行 —— 输入是问题，但**它忽略问题**，直接返回全量 schema
- 真正读库的 `load()` 和渲染 DDL 的 `renderDdl()` **已经搬到别处**：
  - `SchemaCatalog.load()` —— `retrieval/SchemaCatalog.java` 第 68 行
  - `SchemaDdlRenderer.render()` —— `retrieval/SchemaDdlRenderer.java` 第 26 行

**为什么要拆**：阶段 2 有两个 provider（全量版和检索版）。如果各自缓存一份 schema，
消融实验的两个配置看到的可能不是同一份 schema，数字就失去可比性。拆出 `SchemaCatalog`
之后，两者共享同一份不可变目录，差异只剩「选哪些表」这一个变量。

**看 `SchemaDdlRenderer.render()` 怎么把结构化数据拼成文本**——这就是模型实际读到的东西。

### 2.2 组装 prompt

`src/main/java/com/text2sql/agent/generation/PromptTemplate.java`（全文 64 行）

- 第 40 行 `systemPrompt()` —— 六条硬性规则
- 第 61 行 `userPrompt()` —— 只有 15 行，看它把 schema、数据画像、问题拼成什么顺序

**顺序是刻意的**：问题放最后，因为长上下文里模型对末尾注意力更强。

### 2.3 调模型

`src/main/java/com/text2sql/agent/generation/LlmSqlGenerator.java`

- 第 83 行 `generate()` —— 看它怎么调模型、怎么从响应里抠 token 数
- 第 154 行 `buildChatModel()` —— **先跳过**，那是厂商适配细节

### 2.4 校验（唯一需要逐行读的方法）

`src/main/java/com/text2sql/agent/validation/SqlValidator.java` 第 70 行 `validate()`

**这是唯一需要逐行读的方法。** 六个步骤有编号注释，从上往下读，
每一步问：「这一步挡住了什么？」

读完要能回答：**prompt 里已经写了规则，为什么还要校验？**

### 2.5 执行

`src/main/java/com/text2sql/agent/execution/SqlExecutor.java` 第 59 行 `execute()`

看三个设置：`setReadOnly`、`setQueryTimeout`、`setMaxRows(maxRows + 1)`。

**想一下第三个为什么是 +1。**（提示：结果刚好等于上限时，怎么区分「恰好这么多」和「还有更多」？）

### 自检

说出「校验层能挡住、执行层挡不住的」和「执行层能挡住、校验层挡不住的」各是什么。

---

## 第 3 轮：搞懂准确率这个数字

- [ ] 已读

这是面试最容易翻车的地方。

### 3.1 单条样本怎么评分

`src/main/java/com/text2sql/agent/evaluation/EvalRunner.java`

- 第 232 行 `evaluateOne()` —— 看它怎么「当场执行 gold_sql 拿期望值，再跑模型拿实际值，最后比对」
- 第 259 行 `aggregate()` —— 看准确率、SQL 有效率、P95 是怎么算的
- 第 177 行 `aggregateRetrieval()` —— 阶段 2 新增，聚合表召回率

### 3.2 两个结果集算不算相同

`src/main/java/com/text2sql/agent/execution/ResultNormalizer.java`

- 第 51 行 `orderMatters()` —— 顺序什么时候承载语义
- 第 77 行 `normalizeValue()` —— 单个值怎么归一
- 第 166 行 `equivalent()` —— 最终判定

**规则错了，准确率就整个失真。**

### 3.3 期望表从哪来（阶段 2 新增）

`src/main/java/com/text2sql/agent/evaluation/TableRecall.java` 第 46 行 `expectedTables()`

核心：期望表**不是人工标的**，是从 gold_sql 解析的。

### 自检

**为什么「每个州有多少订单」少写一个 ORDER BY 不该判错，但「销量最高的 10 个」顺序错了就该判错？**

（答案在 `orderMatters()`：只有 ORDER BY 和 LIMIT **同时**出现，顺序才承载语义。）

---

## 第 4 轮：结果怎么变成 HTTP 响应，成本记在哪

- [ ] 已读

- `src/main/java/com/text2sql/agent/api/AskController.java` 第 60 行 `ask()`
  → 接入层只做协议转换，**没有任何业务逻辑**
- `src/main/java/com/text2sql/agent/orchestrator/AgentResponse.java` 第 43 行
  → 看第 66 行 `Status` 枚举的五种值，这是**失败归因的第一层切分**
- `src/main/java/com/text2sql/agent/observability/LlmCallRecord.java`（全文 33 行）
  → 看 token / 耗时 / 成本三个字段怎么算出来

### 自检

「每次调用花了多少」这个问题的数据，从哪个类流到哪个类？

---

## 第 5 轮：阶段 2 的检索核心（本轮新增）

- [ ] 已读

**这一轮只读一个类的主方法，但它是阶段 2 最值钱的部分。**

`src/main/java/com/text2sql/agent/retrieval/LexicalSchemaRetriever.java`（559 行）

主方法 `retrieve()` 从 **第 103 行**到 **第 275 行**，是**六步流水线**，
代码里的注释已经把每一步标出来了：

| 步 | 行号 | 干什么 | 读完要能回答 |
|---|---|---|---|
| 1 | 112 | 最大匹配扫描 | 为什么「订单明细」不会同时命中 `orders`？ |
| 2 | 136 | 英文标识符补充 | 用户直接写 `order_items` 时怎么办？ |
| 3 | 145 | 未命中回退全量 | 为什么宁可多给表也不能筛掉？ |
| 4 | 155 | 关系图扩展 | 为什么扩展分数要乘源表的分数？ |
| 5 | 209 | Top-K 全序排序 | 只按分数排会出什么问题？ |
| 6 | 232 | 连通性修复 | 为什么不直接调大 topK？ |

### 读法建议

**拿一张纸，把第 4 步的 `delta = base * decay * edge` 手算一遍。**
这一步是阶段 2 最核心的设计，也是面试最容易追问的地方。

### 主方法读顺之后，再看这四个方法

| 方法 | 行号 | 作用 |
|---|---|---|
| `repairConnectivity()` | 276 | 连通性修复的主逻辑 |
| `shortestBridge()` | 316 | 多源 BFS 找最短桥 |
| `adjacency()` | 499 | 构建带边强度的关系图 |
| `MatchKind` 枚举 | 579 | 四档权重：枚举值 > 表名 > 字段 > 标识符 |

### 然后读词典（不用读完）

`src/main/resources/schema/glossary.yml`（704 行）

只读**头部注释**和 **`relations:` 那一段（第 45–104 行）**。
知道它长什么样、能加什么就行，不需要读完 37 张表的别名。

### 最后看检索结果怎么进 prompt

`src/main/java/com/text2sql/agent/retrieval/HybridSchemaProvider.java` 第 51 行 `provide()`

### 自检

问「订单明细里有多少商品？」，说出每一张被选中的表是**直接命中**还是**关系扩展**进来的，
以及各自的分数。答不上来就回去读第 4、5 步。

---

## 第 6 轮：阶段 2 的 bug 修复（本轮新增）

- [ ] 已读

这一轮的内容是**真实链路跑起来之后才暴露的**，dry-run 测不出来。
它的价值在于：**让失败变得可见、可归因**。

背景：修复前，报告里 19 条执行失败**全部看不到 SQL**，17 条错误信息是 `bad SQL grammar []`
——方括号里空的。归因链断了。

### 6.1 校验层的两个坑

`src/main/java/com/text2sql/agent/validation/SqlValidator.java`

- 第 239 行 `needsLimit()` —— 看注释里为什么必须用 `instanceof` 而不是 `getPlainSelect()`
- 第 287 行 `hasLimit()` —— 看注释里为什么必须递归查最后一个子查询

**这两个是同一个 bug 的两面**：模型生成 `A UNION B LIMIT 5` 时，
JSqlParser 把 LIMIT 挂在最后一个子查询上，旧代码只看外层，
于是判定「没有 LIMIT」→ 在 AST 外层再补一个 → 拼出 `LIMIT 5 LIMIT 200` → 语法错误。

**模型写对了，错误是校验层自己制造的。**

### 6.2 时间归一化

`src/main/java/com/text2sql/agent/execution/ResultNormalizer.java` 第 115 行 `normalizeTemporal()`

`2018-01-01` 和 `2018-01-01 00:00:00` 是同一个时间点，不该判错；
但 `2018-01`、`2018-Q1` 这类**有损**字符串不展开，因为那需要猜粒度。

### 6.3 让失败能被看见

- `execution/SqlExecutor.java` 第 125 行 `ReadOnlyStatementCreator`
  → 一个类实现两个接口，唯一目的是让 Spring 能从对象里取到 SQL 文本
- `orchestrator/AgentResponse.java` 第 132 行 `failed(...)`
  → 从「硬编码 sql=null」改成「接受 sql 参数」
- `orchestrator/Text2SqlOrchestrator.java` 第 152 行
  → 调用点，传的是 `validation.sql()` 而不是原始 `sql`

### 自检

说出「为什么让 Spring 取到 SQL」不能靠改日志格式来解决。

---

## 7. 可以跳过的文件

按你的目标（简历 + 抗住面试），下面这些**直接跳过**，只需知道它们存在：

| 文件 | 为什么可以跳过 |
|---|---|
| `Text2SqlAgentApplication`、`AppConfig` | 启动入口和 bean 注册，合计不到 80 行 |
| `HealthController`、`ApiExceptionHandler` | Web 样板，跟 Text2SQL 无关 |
| `EvalItem`、`EvalReport`、`ValidationResult`、`SchemaContext`、`QueryResult`、`GeneratedSql`、`SchemaProvider`、`RetrievalResult`、`Glossary`、`GenerationException`、`SqlExecutionException` | 全是 record 和异常类，看字段名就懂 |
| `AgentProperties` | 465 行里绝大多数是 getter/setter。**但注释值得读**——每个配置项为什么这么默认都写在里面 |
| `AskController.AskRequest / AskResponse` | DTO 字段映射 |
| `DatabaseSchemaReader` | 三段 SQL 查询，「知道它查什么」即可 |
| `EvalItemLoader` | YAML 文件读取 |
| `GlossaryLoader` | 词典加载 + 启动校验，知道「加错了会被拦住」即可 |
| `SchemaCatalog` | 知道「启动时读一次并缓存」即可 |
| `SchemaDdlRenderer` | 字符串拼接 |

### 测试文件什么时候读

**不是现在。** 等你要**改**某一段代码之前，先读那个类对应的测试——它会告诉你
「这段代码承诺了什么行为」。现在读会淹没你。

---

## 8. 立刻能做的练习：打断点看数据流

- [ ] 已完成

这是比读代码有效十倍的方法，而且**不需要 API Key**。

在 IDEA 里 Run `Text2SqlAgentApplication`，Program arguments 填：

```
--agent.eval.enabled=true --agent.eval.dry-run=true --agent.eval.limit=1
```

然后在 `Text2SqlOrchestrator.java` **第 112 行 `askWithFixedSql`** 打断点，
用 Debug 模式启动。程序会停下来。

在 Debug 窗口里：

| 看什么 | 能看到 |
|---|---|
| 展开 `schema` 变量 | 真实的 37 张表结构 |
| 展开 `schema.ddlText()` | **这就是模型实际读到的文本**，7401 个字符 |
| F8 单步走到 `validator.validate()` 之后 | `ValidationResult` 里 `sql` 和 `rewritten` 分别是什么 |
| 走到 `executor.execute()` 之后 | `result.rows()` 里真实的结果值 |

**这个练习的产物就是你面试时要讲的那段话。**

---

## 9. 每轮的自检题汇总

答不上来就回到对应轮次：

| # | 问题 | 出自 |
|---|---|---|
| 1 | 一个请求从进来到出结果，经过哪几个类？ | 第 1 轮 |
| 2 | 校验层能挡住、执行层挡不住的是什么？反过来呢？ | 第 2 轮 |
| 3 | 「每个州有多少订单」少写 ORDER BY 为什么不判错？ | 第 3 轮 |
| 4 | 「每次调用花了多少」的数据从哪个类流到哪个类？ | 第 4 轮 |
| 5 | 「订单明细里有多少商品」每一张表是直接命中还是扩展进来的？ | 第 5 轮 |
| 6 | 为什么让 Spring 取到 SQL 不能靠改日志格式？ | 第 6 轮 |

**第 1、3、5 题分别对应「数据怎么流」「错在哪一层」「检索为什么这么选」，
这三题能答顺，面试的基本盘就稳了。**

---

## 10. 更新记录

| 日期 | 阶段 | 更新内容 |
|---|---|---|
| 2026-10-02 | 阶段 2 收尾 | 建立本文档；收录阶段一四轮并修正过时行号；新增第 5、6 轮 |

### 已修正的过时行号（阶段一旧指南 → 当前代码）

阶段一那份指南是在对话里给的，没有落成文件，且行号已经漂移。以下是修正记录：

| 内容 | 旧指南 | 当前 |
|---|---|---|
| `FullSchemaProvider.load()` / `renderDdl()` | 第 60 / 80 行 | **已搬到** `SchemaCatalog.load()`(68) 与 `SchemaDdlRenderer.render()`(26) |
| `LlmSqlGenerator.generate()` | 第 79 行 | 第 83 行 |
| `SqlExecutor.execute()` | 第 54 行 | 第 59 行 |
| `EvalRunner.evaluateOne()` | 第 168 行 | 第 232 行 |
| `EvalRunner.aggregate()` | 第 195 行 | 第 259 行 |
| `ResultNormalizer.orderMatters()` | 第 43 行 | 第 51 行 |
| `ResultNormalizer.normalizeValue()` | 第 72 行 | 第 77 行 |
| `Text2SqlOrchestrator.askWithFixedSql` | 第 108 行 | 第 112 行 |
| Java 代码总量 | 2866 行 | 5653 行（阶段 2 新增检索层） |

**这就是为什么行号不能写死在脑子里**：阶段 2 一提交，阶段一的指南就有 9 处对不上。
读代码时以 IDE 的跳转为准，本文档的行号只作为起点参考。
