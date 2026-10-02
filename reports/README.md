# 评估报告索引

本目录区分正式对照、开发集取舍、历史诊断与 dry-run 自检。
**本次收尾不删除或覆盖任何现存报告，也不只保留高分结果**。
完整评估方法、指标口径和实验局限见 [docs/EVALUATION.md](../docs/EVALUATION.md)。

## 1. 阶段 2 正式真实链路对照

以下两份均为 `ernie-4.5-turbo-32k`、`p1-full-schema-v1`、eval 100 条、`dryRun=false`。

| 报告 | 配置 | 执行准确率 | SQL 有效率 | 平均输入 token | 平均 / P95 延迟 |
|---|---|---|---|---|---|
| [eval-20261002-151939.json](eval-20261002-151939.json) | baseline，检索关闭 | 47% | 78% | 2314.71 | 4837.34 / 11721ms |
| [eval-20261002-160914.json](eval-20261002-160914.json) | 检索 Top-8 | 52% | 92% | 709.46 | 4442.58 / 8496ms |

最终 Top-8 表平均召回 98.48%、全召回 93%、平均 6.69 表 / 1579.55 DDL 字符。
准确率提升 **5 个百分点**，原定 +8 目标未达成；输入 token 下降 69.35%。
两份金额均为 0 是单价配置为 0，不代表调用免费或成本验收通过。

## 2. Top-K 取舍与不应隐藏的历史结果

| 报告 | 数据集 / 配置 | 准确率 | 全召回率 | 用途 |
|---|---|---|---|---|
| [eval-20261002-153023.json](eval-20261002-153023.json) | dev 100 / Top-5 | 46% | 89% | 开发集取舍 |
| [eval-20261002-155612.json](eval-20261002-155612.json) | dev 100 / Top-8 | 48% | 96% | 开发集取舍 |
| [eval-20261002-150831.json](eval-20261002-150831.json) | eval 100 / Top-5 | **54%** | 89% | 保留早期真实成绩 |

归档 Top-8 是参考 dev 表现、为多表关联留余量，不宣称其 eval 准确率优于 Top-5。
早期 eval Top-5 与最终 Top-8 的已记录配置主要差异是 K，不能编造模型差异
来排除 54% 的证据。当前没有重复实验，单轮差异不能直接证明参数优劣或显著性。

## 3. 历史诊断报告（不混入正式对照）

| 报告 | 模型 / 数据集 | 结果 | 解释边界 |
|---|---|---|---|
| [eval-20261002-132033.json](eval-20261002-132033.json) | 128k / eval 100 | 39% | 早期 baseline；时间结果比较口径已迭代，不直接比较为模型收益 |
| [eval-20261002-143039-dryrun.json](eval-20261002-143039-dryrun.json) | dry-run / eval 10 | 100% | 局部链路自检，不是模型成绩 |
| [eval-20261002-143115-dryrun.json](eval-20261002-143115-dryrun.json) | dry-run / eval 50 | 100% | 局部链路自检，不是模型成绩 |
| [eval-20261002-143428.json](eval-20261002-143428.json) | 128k / eval 50 | 76% | 样本不完整、难度分布不同 |
| [eval-20261002-144110.json](eval-20261002-144110.json) | 128k / eval 50 | 68% | 另一轮局部诊断，不挑高分替代全量 |
| [eval-20261002-144917.json](eval-20261002-144917.json) | 128k / eval 100 | 47% | 模型不同，不与 32k Top-8 归为只改检索的对照 |
| [eval-20261002-145355.json](eval-20261002-145355.json) | 128k / eval 100 / Top-5 | 24% | 含 403 `account_overdue` 调用失败，不能据此判断检索退化 |
| [eval-20261002-145856.json](eval-20261002-145856.json) | `ERNIE-4.5-Turbo-32K` / eval 100 | 0% | 401 生成失败诊断，不是算法能力结论 |

50 条局部诊断偏重前面的单表层，不能拿 76% 与 100 条的 47% 直接比较。
账户/鉴权失败属于外部调用问题；token 与短延迟也可能因未成功生成而变小，
不是优化收益。

## 4. 历史 dry-run 检索消融与自检

| 文件 | 数据集 | 配置 | 关键数字 |
|---|---|---|---|
| [eval-20261001-134509-dryrun.json](eval-20261001-134509-dryrun.json) | 50 条 | 阶段 1 链路自检 | 50/50 = 100% |
| [eval-20261001-134713-dryrun.json](eval-20261001-134713-dryrun.json) | 200 条全量 | 阶段 1 链路自检 | 200/200 = 100% |
| [eval-20261001-222531-dryrun.json](eval-20261001-222531-dryrun.json) | dev 100 | baseline 全量 DDL（检索关闭） | 表召回 100%，平均 37.0 表 / 7401 字符 |
| [eval-20261001-223851-dryrun.json](eval-20261001-223851-dryrun.json) | dev 100 | 词法检索，**关**连通性修复 | 全召回 95.0%，表召回 98.95% |
| [eval-20261001-223626-dryrun.json](eval-20261001-223626-dryrun.json) | dev 100 | 词法检索 + 连通性修复 | 全召回 **96.0%**，表召回 99.20% |
| [eval-20261001-223705-dryrun.json](eval-20261001-223705-dryrun.json) | eval 100 | 检索版，topK=8 | 全召回 93.0%，表召回 98.48%，平均 DDL 1582 字符 |
| [eval-20261001-223744-dryrun.json](eval-20261001-223744-dryrun.json) | eval 100 | 检索版，topK=5（验收口径） | 全召回 89.0%，表召回 **96.53%**，1185 字符 |

`223851` 与 `223626` 在开发集对比连通性修复开/关，全召回率相差 1 个百分点。
`223705` 与 `223744` 对比 K 的检索召回与上下文长度；**dry-run 没有真实调用模型**，
meta 中的模型名只是配置值，不代表模型参与了实验。
历史 DDL 1582 与最终真实报告的 1579.55 各自保留，不能把不同时间的测量混成一行。

## 5. 复现入口

无需为了收尾再付费跑。正式真实链路的 PowerShell 命令见
[EVALUATION.md 第 9 节](../docs/EVALUATION.md#9-powershell--idea-terminal-复现)。
开发集 dry-run 检索实验可用：

```powershell
& 'D:\develop\Maven\apache-maven-3.9.4\bin\mvn.cmd' spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.dry-run=true --agent.eval.split=dev --agent.eval.limit=0 --agent.retrieval.enabled=true --agent.retrieval.top-k=8 --agent.retrieval.bridge-repair-enabled=true"
```

关闭连通性修复：把 `bridge-repair-enabled=true` 换成 `false`；
Top-5：把 `top-k=8` 换成 `5`。后续调参只用 dev。
历史报告未保存完整版本与数据哈希，命令是复现入口，不保证逐项数字完全相同。

## 6. 读报告时先看这三个地方

1. `meta`：先核对 `dryRun`、`split`、样本数、模型与配置；
   dry-run 不是模型成绩，历史 eval 多次运行的局限必须披露。
2. `overall`：区分执行准确率与 SQL 有效率；`avgPromptTokens` 仅输入 token，
   金额为 0 时先检查单价和 usage，不能直接宣称免费。
3. `failures` 与 `retrieval.incompleteCases`：前者区分链路状态，后者给出具体漏表。
   `SUCCESS` 出现在 failures 中表示执行成功但结果错误，不是评估器自相矛盾。

未来结果须连同版本、数据与参数一起保存。现有报告作为内部迭代记录，
不宣称是未见测试集或已完成七类业务错误的自动精确归因。
