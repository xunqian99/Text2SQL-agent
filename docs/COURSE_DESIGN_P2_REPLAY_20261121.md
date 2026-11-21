# P2 阶段报告：失败归因与离线请求回放

状态：**已完成**。P2 只增加评测证据的解释层，不改变模型调用、SQL 校验、数据库执行和原有评分器。

## 1. 具体请求的数据流

以 `p15-formal-85-33` 的 `T1-016` 为例：

1. 页面从 `/api/evaluation/reports` 读取配置白名单中的三轮 JSON 报告。读取器按源文件顺序记录 `sourceIndex=0/1/2`，并在 `itemEvidence` 中定位 `T1-016`。
2. 读取器根据请求终态和检索证据归因。该条是 `SUCCESS` 但 `correct=false`，且 `retrieval.incompleteCases` 没有它，所以分类为 `RESULT_MISMATCH`。
3. 页面点击“离线回放”后，请求 `/api/evaluation/reports/p15-formal-85-33/sources/0/items/T1-016/replay`。`reportId` 只能命中 `application.yml` 中的白名单，`sourceIndex` 只能选该版本配置的第三个源文件之一。
4. 读取器只读取三类已落盘证据：源报告中的终态、生成 SQL、消息和 `productionAttempts`；`data/eval/T1_single_table_basic.yaml` 中的题目、难度和 `gold_sql`；`data/eval/gold_results.json` 中的 Gold rows 和结果摘要。
5. 返回 `offlineReplay=true` 的 `ReplaySnapshot`。页面明确显示“离线回放、不重发模型、不执行数据库”，并同时展示 Gold SQL、Gold 结果、保存的生成 SQL、每轮 attempt、结果 SHA 和失败分类。

这里的身份追踪不是把三轮数据压成一个题目集合：`ReportFile.sourceIndex` 保留源报告出身，`ReplayIndex` 同时携带 `sourceIndex + sourceFile + itemId`。同一个题目 ID 在三轮中重复时，按钮仍然指向具体源文件，不会把第一轮的 SQL 和第三轮的 Gold 混在一起。

## 2. 实现使用的技术与组件

- `EvaluationReportReader`：只读 JSON/JSONL，重新聚合源报告，并生成 `failureAttribution` 与 `replayItems`。
- `EvaluationReportController`：提供只读汇总接口和回放接口；源文件、题目或 Gold 缺失时返回 `404 REPLAY_UNAVAILABLE`。
- Jackson `ObjectMapper` + `YAMLFactory`：分别解析评测 JSON 和题集 YAML；不把 YAML mapper 注册成 Spring 全局 mapper，避免改变 REST JSON 序列化。
- `SHA-256`：保存源报告指纹，回放返回源文件 SHA；Gold 结果同时保留文件内记录的 `digest`。
- 七类归因规则：
  - `REQUEST_TIMEOUT` / `RESOURCE_LIMITED` → `TIMEOUT`；
  - `NEEDS_CLARIFICATION` → `CLARIFICATION`；
  - `REJECTED` → `VALIDATION`；
  - `EXECUTION_FAILED` → `EXECUTION`；
  - `GENERATION_FAILED` / `NOT_CONFIGURED` → `GENERATION`；
  - `SUCCESS && !correct` 且出现在 `retrieval.incompleteCases` → `RETRIEVAL`；
  - 其余 `SUCCESS && !correct` → `RESULT_MISMATCH`。

归因优先级是终态优先：模型超时不会因为同时有检索缺口而被错误标成检索失败；只有执行成功但结果错误时，才进一步区分检索证据缺口和结果不匹配。

## 3. 效果与数字验收

P2 的收益是把“失败了”拆成可复盘的责任层，并让一次回放完全脱离付费服务和业务数据库。

| 指标 | 验收结果 |
|---|---:|
| 归因类别 | 7/7 固定输出，零计数也保留 |
| p15 三轮正式样本 | 300 条，256 正确，85.33%；结果不匹配 23、超时 15、澄清 6 |
| p18 三轮正式样本 | 300 条，234 正确，78.00%；结果不匹配 34、超时 26、澄清 6 |
| 回放模型调用 | 0 次 |
| 回放数据库执行 | 0 次 |
| 回放单条源报告定位 | `sourceIndex + itemId`，重复题目不混淆 |
| 缺少 Gold/源报告 | fail-closed，HTTP 404，返回明确错误码 |
| Java 测试 | `EvaluationReportReaderTest` 覆盖汇总、七类归因、重复 ID、成功回放、缺失证据 |

测试夹具还验证了 `RETRIEVAL`、`GENERATION`、`VALIDATION`、`EXECUTION`、`TIMEOUT`、`CLARIFICATION`、`RESULT_MISMATCH` 七类各自可达；真实 p15/p18 报告中当前没有“检索不足但执行成功且结果错误”的条目，所以线上历史数字里的 `RETRIEVAL` 为 0，不能硬凑成非零。

## 4. 设计权衡与面试追问

### 替代方案

1. **重新调用模型或重新执行 Gold SQL 回放**：被否掉。它会产生供应商费用、延迟和数据库副作用，而且回放结果可能因模型或数据变化而漂移。P2 的目标是解释历史证据，所以只接受已保存的事实。
2. **按 `itemId` 全局查找，不保存源索引**：被否掉。同一候选有三轮，题目 ID 必然重复；全局查找会把不同轮次的 SQL、状态和 SHA 拼接成伪证据。
3. **把所有错误统一叫“结果不匹配”**：被否掉。这样无法区分模型没产出 SQL、护栏拒绝 SQL、数据库执行失败和 Gold 结果错误，面试时也无法证明错误层级。
4. **Gold 缺失时返回空数组**：被否掉。空数组会伪装成“查询结果为空”，掩盖评测资产损坏；本实现直接 fail-closed。

### 面试答辩要点

- **为什么 `sourceIndex` 不是文件名就够了？** 文件名用于展示和 SHA 指纹，但接口仍必须绑定版本配置中的位置；`sourceIndex` 让重复 ID 在同一候选的三轮报告中保持稳定、可枚举的身份凭据，且不允许浏览器传任意路径。
- **为什么检索失败只在 `SUCCESS && !correct` 时成立？** `incompleteCases` 是检索层证据，不是最终终态。若请求已经超时、澄清或校验拒绝，真正阻止结果产生的是这些终态；只有 SQL 执行完成且结果错误时，检索缺口才足以成为首要归因。

## 5. 用户需要配合掌握的事项

本阶段不需要启动外部服务或确认业务口径。用户在答辩前必须能手工讲清三件事：

1. 一条回放请求从页面到白名单源报告、题集 YAML、Gold JSON，再回到页面的完整数据流；
2. 历史报告里记录的 prompt token、completion token、总 token、费用和 UNKNOWN 用量，不能把离线回放误报成新的模型成本；
3. 看到一个失败时，先看终态，再看 `retrieval.incompleteCases`、保存的 SQL、attempts 和结果 SHA，判断错误落在检索、生成、校验、执行、超时、澄清还是结果比较层。

