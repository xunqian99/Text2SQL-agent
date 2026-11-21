# 销售额分析闭环技术与架构规格

- 文档类型：Spec-Driven Development 技术规格
- 版本：v1.0
- 日期：2026-11-21
- 上游产品规格：[销售额分析闭环 PRD](SALES_ANALYSIS_WORKBENCH_PRD.md)
- 下游实施路线：[销售额分析闭环路线](SALES_ANALYSIS_WORKBENCH_ROADMAP.md)
- 当前实现阶段：C-1 多轮追问与结果恢复（A-1、B-1 已完成）

## 1. 规格约束

本规格把 PRD 的业务规则转换为可实现、可测试的技术契约。实现顺序固定为：

```text
规格编号 → 失败测试 → 最小实现 → 定向测试 → 全量回归 → Git 提交
```

任何没有对应规格编号和测试的代码，不进入本阶段。

## 2. 系统边界

### 2.1 当前系统负责

- 接收自然语言分析问题。
- 从指标注册表确定销售额口径。
- 检索允许访问的 Schema 和 Join 路径。
- 生成、校验并只读执行 SQL。
- 把结果包装成业务结果和可恢复的分析状态。
- 保存开发诊断证据。

### 2.2 当前系统不负责

- 访问真实生产交易库。
- 写订单、库存或商品数据。
- 处理多方言 SQL、多租户、MCP、流式协议或 React 重写。
- 让业务页面承担 SQL、Prompt、Token 和模型诊断职责。

当前 PostgreSQL Olist 快照就是分析数据库。应用使用只读账号、连接池上限 5、单查询超时 10 秒、最多返回 200 行。

## 3. 端到端数据流

以“查看 2018 年按品类销售额”为例：

1. 页面提交 `POST /api/analysis/turns`，包含问题、`sessionId`（首轮为空）和幂等键。
2. 会话服务创建 `sessionId`、`analysisId`，解析出指标 `gmv_with_freight`、时间范围 `2018` 和维度 `category`。
3. 指标注册表提供表达式、有效订单过滤、依赖表和 `metricDefinitionSha256`。
4. Schema 检索记录直接命中的表到 `directHits`，关系扩展表到 `expandedTables`；只有 Join 图断开时才加入 `safetyBridges`。
5. 生成器只接收结构化上下文和必要 Schema，SQL 经过 AST 只读校验、LIMIT 补全和超时执行。
6. 结果归一化成业务列名、金额单位和行数据，状态从 `RUNNING` 变为 `SUCCESS`。
7. 业务响应只返回标题、摘要、列、行和可用下钻；诊断响应单独保存 SQL、命中来源、校验、Token、耗时和失败阶段。
8. 页面点击“按月份查看”时提交 `analysisId + drilldown=MONTH`，服务端追加维度并重新校验上下文，前端不拼接自然语言。

## 4. 核心领域模型

### 4.1 AnalysisContext

上下文是跨轮次的唯一业务凭据，不能用完整聊天记录替代：

```text
AnalysisContext {
  sessionId: String
  analysisId: String
  metric: MetricRef
  period: Period
  dimensions: List<DimensionRef>
  filters: List<AnalysisFilter>
  sort: SortSpec?
  metricDefinitionSha256: String
}
```

每一个维度、过滤条件和指标引用都必须保留 `source`：`USER_DIRECT`、`METRIC_DEFINITION`、`INHERITED` 或 `JOIN_BRIDGE`。`directHits`、`expandedTables`、`metricDependencies`、`safetyBridges` 也必须分开保存，禁止在 Top-K 前扁平化。

### 4.2 状态机

```text
CREATED → RUNNING → SUCCESS
                  ↘ FAILED
```

状态只能单向前进。`SUCCESS` 和 `FAILED` 都是可恢复终态；通过 `analysisId` 重复读取只能返回已保存状态，不能再次调用模型。

### 4.3 业务响应与诊断响应

业务响应字段固定为：`analysisId`、`sessionId`、`status`、`title`、`unit`、`summary`、`columns`、`rows`、`availableDrilldowns`、`dataAsOf`。

诊断响应独立于业务响应，包含：SQL、Schema、来源凭据、AST 校验、模型调用次数、Token、分阶段耗时、估算成本、失败归因和预算快照。业务页面禁止通过 CSS 隐藏技术字段来“伪装隔离”，必须使用不同 DTO 和权限入口。

## 5. 检索与排序防线

- 直接命中集合 `directHits` 的排序优先级高于 `expandedTables`。
- 扩展关系使用 `0.4` 衰减；它表达“可用于连通 Join”，不表达“与用户目标同等相关”。
- Top-K 比较器先比较来源等级，再比较衰减后的得分；因此扩展表不能挤掉直接命中表。
- `safetyBridges` 只在 Join 图不连通时加入，并单独记录原因。

边界：若直接命中为空，系统可以进入扩展检索，但必须将“无直接命中”写入诊断证据；若 Join 仍不连通，安全失败并返回业务可读的无法执行提示，不能让模型自行猜表。

## 6. 接口契约

当前 `/api/ask` 保持兼容，未来业务工作台使用：

```http
POST /api/analysis/turns
GET  /api/analysis/{analysisId}
POST /api/analysis/{analysisId}/drilldowns
POST /api/analysis/{analysisId}/close
```

`POST /api/analysis/{analysisId}/drilldowns` 的请求必须携带 `dimension` 和幂等键，服务端从已保存上下文派生新上下文。会话关闭后任何修改请求返回业务错误，不触发模型调用。

## 7. 幂等、恢复和并发

- 幂等键由 `sessionId + clientRequestId` 组成，服务端保存请求与 `analysisId` 映射。
- 相同幂等键重复提交直接返回原分析结果；模型重复调用次数必须为 0。
- 短暂断线只影响传输，不影响服务端状态；客户端重新以 `analysisId` 查询。
- 同一分析对象只允许一个 `RUNNING` 执行者，其他请求读取状态或返回冲突，不启动第二个模型调用。

当前 C-1 实现由 `AnalysisStore` 承担内存状态、会话轮数、幂等索引和关闭标记；后续接入数据库持久化时保持同一状态机接口。

## 8. 安全与性能

安全边界：只读账号、AST 只读校验、LIMIT、超时、最大行数和表授权集合全部保留。任何 SQL 校验失败先终止执行，不能把异常或部分结果当成功。

性能目标：单轮查询 P95 ≤ 8 秒；上下文对象不保存全量结果，只保存摘要和可恢复结果引用；历史文本由结构化上下文取代，以避免 Prompt 随轮数线性增长。

## 9. 可观测性

每个 `analysisId` 必须能关联：模型调用次数、输入/输出/总 Token、检索/校验/数据库/端到端耗时、费用（模型单价为 0 时只报告 Token）、失败阶段和请求幂等键。

## 10. 规格到测试的映射

| 规格 | 首批测试 |
|---|---|
| CTX-001 上下文字段不可丢失 | `AnalysisContextTest` 创建测试 |
| CTX-002 维度和过滤条件追加保持不可变 | `AnalysisContextTest` 追加测试 |
| CTX-003 重复维度不重复 | `AnalysisContextTest` 去重测试 |
| CTX-004 外部列表不可修改 | `AnalysisContextTest` 防御性复制测试 |
| CTX-005 空身份和空指标拒绝 | `AnalysisContextTest` 校验测试 |
| CTX-006 指标定义摘要必须保留 | `AnalysisContextTest` 指纹测试 |

后续接口、状态机、恢复和诊断隔离测试必须在对应路线阶段增加，不能在 A-1 偷渡实现。

