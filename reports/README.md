# 评估报告索引

本目录只保留**有解释价值**的报告快照。中间调参过程产生的 12 份临时报告已删除，
因为它们只是同一变量在候选参数下的重复试错，保留会让「哪份是结论」变得含糊。
需要复现任意一行时，按下表的命令重跑即可（每次运行会生成新的时间戳文件）。

## 报告清单

| 文件 | 数据集 | 配置 | 关键数字 |
|---|---|---|---|
| `eval-20261001-134509-dryrun.json` | 50 条 | 阶段 1 评估器自检 | 50/50 = 100% |
| `eval-20261001-134713-dryrun.json` | 200 条全量 | 阶段 1 评估器自检 | 200/200 = 100% |
| `eval-20261001-222531-dryrun.json` | dev 100 | baseline 全量 DDL（检索关闭） | 表召回 100%，平均 37.0 表 / 7401 字符 |
| `eval-20261001-223851-dryrun.json` | dev 100 | 词法检索，**关**连通性修复 | 全召回 95.0%，表召回 98.95% |
| `eval-20261001-223626-dryrun.json` | dev 100 | 词法检索 + 连通性修复 | 全召回 **96.0%**，表召回 99.20% |
| `eval-20261001-223705-dryrun.json` | eval 100 | 检索版，topK=8 | 全召回 93.0%，表召回 98.48% |
| `eval-20261001-223744-dryrun.json` | eval 100 | 检索版，topK=5（验收口径） | 全召回 89.0%，表召回 **96.53%**，1185 字符 |

`223851` 与 `223626` 是同一变量的一对消融（连通性修复开/关），差值 +1.0 个百分点
就是该设计的净贡献。`223705` 与 `223744` 是同一模型、同一检索器只改 topK 的对照，
用来说明「表召回率」和「上下文长度」的取舍。

## 复现命令

开发集（调参只能看这一份）：

```powershell
$env:JAVA_HOME='C:\Users\xunqian\.jdks\corretto-21.0.11'
& 'D:\develop\Maven\apache-maven-3.9.4\bin\mvn.cmd' -o spring-boot:run `
  '-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.dry-run=true --agent.eval.split=dev --agent.retrieval.enabled=true --server.port=8099'
```

评估集：把 `--agent.eval.split=dev` 换成 `eval`。
baseline 对照：把 `--agent.retrieval.enabled=true` 换成 `false`。
topK 口径：追加 `--agent.retrieval.top-k=5`。

## 读报告时先看这三个字段

1. `meta.dryRun` —— 为 `true` 时输入是评估集自带的 `gold_sql`，测的是**校验层/执行层/比对逻辑**，
   不是模型能力。这类数字不能当执行准确率写进简历。
2. `meta.split` —— `dev` 用于调参，`eval` 只用于验收。看到 `eval` 上的参数调整记录，
   说明 5.5 的评估纪律被破坏了，那一行数字要作废。
3. `retrieval.incompleteCases` —— 每一条不完整召回都在这里，包含漏了哪张表。
   只看汇总数字会误判检索已经可用。
