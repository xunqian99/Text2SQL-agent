# D：N1 免费阶段审查结论

状态：**FAIL_PRECHECK；不允许付费 DEV100**。本阶段没有读取真实 Key，也没有供应商调用。

用户已确认的新范围是：只做一次 DEV100、最多 200 次模型调用，新账本 ¥2.00，旧账务完全隔离；但前置条件是 N1 免费实现和独立审查通过。这个前置条件没有通过，不能因为额度已经确认就越过门槛。

## 失败原因

N1 提案只允许改同一请求里的业务指标文本，把完全重复的普通约束提取到共享段；Schema、数据画像、示例、字段提示、系统规则、问题、检索排序和 SQL 链路都必须保持不变。封存 B0 的 200 题输入清单是 `p21-period-ranking-v21`，SHA-256 为 `8838dc9e5c88cb5d571cbfd161719c5cd038a5f0de5df64368995756369bef71`。

| 项目 | B0 数字 | N1 乐观上界 |
|---|---:|---:|
| 题数 | 200 | 200 |
| 总输入字符 | 1,657,683 | 1,599,205 |
| 业务指标段字符 | 58,478 | 假设全部删除 |
| 总字符最多减少 | — | 3.527695% |
| 总字符门槛 | — | ≥20% |
| 字符 P95 | 10,101 | 8,812 |

这个上界已经把所有指标字符都删掉，实际实现只能少于它，因为表达式、过滤、分母、身份、单位、NULL、时间、否定和例外条件必须保留。3.53% 距离 20% 有数量级差距；要达到门槛只能删 Schema、示例或系统义务，属于越界改范围。

针对 C1 失败的 10 道晚到题，B0 对应输入平均为 9,380.6 字符。把这 10 道题的全部业务指标段删除，平均也只能降到 8,470.8 字符，最多减少 9.698740%，仍低于 N1 要求的至少 10%。C1 的晚到题标识和 15 秒边界证据保存在 `scope10-final-dev-20261121/revision-3/free-timeout-audit-20261121/summary.json`；输入相关性只能作为相关性，不能冒充因果。

可复核报告：[N1 免费上界审计](../reports/replan-stage-d-20261119/n1-free-audit-20261121/summary.json)，生成脚本：[replan_n1_free_audit.py](../scripts/replan_n1_free_audit.py)。脚本只读取封存输入与已存在的超时摘要，固定输出 `modelCalls=0`、`providerCalls=0`、`realKeyReads=0`。

## 已完成的候选实现

候选代码仍保留在显式开关下，默认不改变当前 C1 生产提示词：

- `AgentProperties.Prompt.businessContextCompressionEnabled=false`，默认字节不变；
- `BusinessContextCompressor` 只解析至少两个 `METRIC` 块，且只移动逐字相同的普通两空格约束；表达式、过滤条件、单位、输出约束、查询结构和四空格嵌套内容不移动；
- 表达式首行即使括号/CASE 已平衡，也会先等待边界：SQL-like continuation 继续受余额解析保护，未知 ASCII continuation 让该指标整块 fail-open；普通说明只能在明确的 CJK/结构化边界后参与共享；畸形三格缩进不参与共享，输出保留输入尾部换行；
- 来源凭据缺失或 `fallback=true` 时直接返回原文；包含 `DIMENSION_POLICY`/`ENTITY_POLICY` 时直接返回原文；共享段增加的标记字符大于节省量时也直接返回原文；
- `SchemaContext.SourceProvenance` 分开保存 `directHits`、`expandedTables`、`metricDependencies` 和 `safetyBridges`，变换上下文时保留凭据；`HybridSchemaProvider` 只把最终进入上下文的表交给 lexical provenance，避免把被 Top-K 淘汰的候选伪装成已注入表；
- `directHits` 与 `expandedTables` 的检索权重没有改动：图扩展仍按 `source × edge × 0.4` 衰减，Top-K 仍为 8，桥接上限仍为 2。四类来源不能扁平化，否则无法证明扩展表没有挤掉直接命中。

这段代码是可回退的工程候选，不构成准入通过；免费上界已经证明它不可能满足当前 20%/10% 门槛。

## 验证结果

- Maven 编译通过；
- `BusinessContextCompressorTest`（包含多行 CASE、中文字符串、未知 continuation、重复约束、畸形 header、三格缩进和尾换行边界）、`PromptVariantTest`、`RetrievalDeterminismTest`、`MetricRegistryTest`、`SchemaDdlRendererTest` 通过；
- 独立工程审查复核 provenance 交集、四类来源身份和表达式 pending 边界；未发现会把危险续行移入共享段的现有路径；
- Python 上界审计通过并明确输出 `FAIL_PRECHECK`；
- 本阶段模型调用 0、供应商调用 0、真实 Key 读取 0、费用 0；
- 未运行 Gold 模型评测，因为前置字符门槛已经有严格上界反例；未把本地测试数量冒充准确率。

## 技术与设计导读

1. **怎么实现**：请求先由检索层产生带来源的 `SchemaContext`，再由 `PromptTemplate` 读取业务指标文本。N1 只在来源可追踪、非全量回退且存在多个指标块时，把完全相同的普通约束放入共享段；每个指标自己的表达式、过滤、分母、身份、单位、NULL、时间、否定和例外原样保留。请求继续走原生成、AST 校验、只读执行和账务链路。
2. **用的什么**：`HybridSchemaProvider`、`SchemaContext.SourceProvenance`、`BusinessContextCompressor`、`PromptTemplate`。检索排序的 direct/expanded 身份仍由证据集合记录；扩展分数乘 0.4，直接命中二级保送，Top-K=8，桥接最多 2 张。压缩器不是新打分器，也不按关键词猜规则是否适用。
3. **有什么效果**：工程边界测试通过，默认生产行为没有变化；但按冻结 B0 输入，N1 即使删除全部业务指标也只能减少 3.527695%，晚到题最多减少 9.698740%，所以没有达到候选门槛。Token、准确率、P95 和账单均为未测，不能虚报收益。
4. **为什么这样做**：保守共享重复约束是为了避免把分母、身份和 NULL 规则删掉后产生静默口径错误。直接删长指标定义看似能降字符，实际会让 SQL 仍能执行但结果口径错，面试时解释不了。面试官会追问“为什么不删表达式？”应回答：表达式和过滤是业务契约，必须保留；“扩展表为什么不会挤掉直接命中？”应回答：扩展乘 0.4 且同分 direct 二级保送，但指标依赖和安全桥接是独立来源，不能混成 lexical 命中。
5. **需要用户配合**：当前不需要 Key、Docker 或外部服务。若要继续，必须先重新批准一个可达的免费门槛或修改 N1 范围；在现有 20%/10% 门槛不变时不应发起付费请求。
