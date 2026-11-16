# Text2SQL Agent

> 中文业务问题 → 只读 PostgreSQL SQL → 结构化结果。基于 Olist 37 表电商数仓，面向“可评估、可解释、可归因”的 Text2SQL Agent。

![CI](https://github.com/xunqian99/Text2SQL-agent/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1)
![License](https://img.shields.io/badge/License-MIT-green)

**预算对照：关闭 91.33% / 开启 91.00%** · **459 项 Java 测试通过** · **53/53 对抗回归** · **整体项目尚未通过验收**

题库规模：原基准 **200道（dev100＋eval100）**，另有候选集100道。下文“600次问答”是同一批100道eval题 × 2种配置 × 3轮，不是新增600道题；输出Token筛选另用原100道dev题 × 2种配置，共200次问答。

最新生成模式开发筛选：[结果与导读](docs/GENERATION_MODE_RESULTS_20261116.md)，测量代码`ed52a67`：同100道dev题×2模式=200次请求，默认65/100、none64/100；P95 11.198/1.733秒，none完整估价¥0.09702184、未知0，允许三轮确认。复杂层仍有退步；Java459/459、审计46/46、Gold200/200；生产默认不变，阶段3未通过。

最新开发筛选：[输出Token预算实验](docs/OUTPUT_BUDGET_RESULTS_20261116.md)，同100道dev题各跑一次，共200次问答：8192组68/100、2048组66/100，P95为15.002/13.078秒。候选出现6题明确截断、3次未知用量，未采用；生产默认未改，阶段3仍未通过。Gold200/200、离线审计40/40，Java业务源码沿用上一轮436/436证据。

前一专项：[请求总预算三轮对照](docs/REQUEST_BUDGET_RESULTS_20261116.md)，测量代码`a1730e0`，600次问答。开启组平均P95 **11.842秒**，仍未达8秒；最慢15.013秒，5次调用用量未知，费用仅有已知下界。Gold200/200、Java436/436、离线审计30/30，阶段3未通过。[子agent内部审核](docs/HOLDOUT_INTERNAL_REVIEW_20261116.md)覆盖候选集100题，发现1项确定Gold缺陷、6题业务待确认、12题弱证据；保持原题和原分数，不宣称独立盲测。

候选集历史基线：[2026-11-16 冻结候选集首轮基线](docs/HOLDOUT_RESULTS_20261116.md)，测量代码 `219f5c4`，开发集66/100、候选集87/100，P95为10.316/7.529秒，估算费用¥0.265/0.277。候选集为AI编写、未经独立审核，每组仅一轮，不能将两套题的分差解释为过拟合或与旧eval分数直接比较；独立盲测尚未完成。

前置 [SQL 提取边界修复](docs/SQL_EXTRACTION_20261116.md)已通过63/63边界契约和1,184条归档SQL回放，本轮基线已包含修复。此前[四配置 × 三轮评测](docs/ABLATION_RESULTS_20261116.md)共1,200次问答，基线92.00%/完整检查组90.33%，仍绑定旧测量代码 `3828e77` 和旧eval集合。历史96/100不能作为当前稳定准确率。

[快速开始](#快速开始) · [基准结果](#基准结果) · [评测方法](#评测方法) · [已知局限](#已知局限) · [设计文档](#设计文档)

![Demo](docs/assets/ui-ask.png)

*实机运行：嵌入电商供应链中台的 Text2SQL 智能分析工作台（以 2018 年品类销售额 TOP 5 问数为例），展示 AST 安全校验、数仓耗时与多维数据渲染。*

## 这是什么 / 不是什么

- **是**：把中文业务问题翻译成只读 PostgreSQL SQL 的 Agent，带 Schema 检索、Join 拓扑、AST 校验、EXPLAIN 护栏、8 条启发式结果检查和结果集评测。
- **不是**：不是生产级多租户 BI；不支持写操作、多 SQL 方言、模型微调；当前单租户、无 RLS；P95 未达 8s。

## 核心特性

- **Schema 检索与 Join 拓扑**：领域词典最大匹配、外键图上的斯坦纳树近似规划与Top-8上下文。最新dev/候选集表全召回92%/96%，dev中4条检索前澄清不能算作算法漏召回；候选集4条实际漏表均最终答错，但不能推断补表必然恢复。旧eval关闭澄清组三轮表全召回99%，属于不同集合。
- **业务口径注入**：`metrics.yml` 保存自定义指标定义，Prompt v9 注入口径与输出列约定；完整列/单位契约尚未建立，不宣称符合外部工业标准。
- **三层只读护栏**：JSqlParser AST（含 CTE 数据修改 / FROM 表函数检查）→ 只读账号 `text2sql_ro` → 10s 超时 + 200 行上限；53 条对抗回归全绿。
- **成本与缓存**：L1 LRU + L2 PostgreSQL的SQL缓存（归一化精确匹配+配置指纹）；最新dev/候选基线关闭应用查询缓存，100题估算 **¥0.265/0.277**，包含未采用的重试，不等于供应商账单。旧eval四组三轮均值为¥0.208–0.218/100题。
- **多轮对话与 Web UI**：指代消解、Schema 复用、会话落库；启动后打开 `http://localhost:8080` 即可问数。

## 系统架构

系统采用手工编排管道（Pipeline），每一步都有分阶段耗时与 token 审计：

```mermaid
flowchart TD
    Q["中文问题"] --> R["改写 / 澄清 / 缓存"]
    R --> S["Schema 检索 + 口径注入"]
    S --> G["SQL 生成 / 拓扑注入"]
    G --> V["AST + EXPLAIN 护栏"]
    V --> X["只读执行：text2sql_ro + 10s 超时"]
    X --> K["结果检查 / 自纠错"]
    K --> O["结果 + 耗时 + Token 审计"]
```

## 基准结果

最新单轮dev/候选集与逐次重试证据见[冻结候选基线](docs/HOLDOUT_RESULTS_20261116.md)：dev 66/100，候选集87/100。本轮观察到结果规则1次误触发，额外调用后仍保留正确首答；5次其他校验拒绝重试中仅1次最终匹配Gold。以下四配置三轮表属于旧eval集合，仍保留原始口径。

200 条六层评测集，eval 100 条；基于 `deepseek-flash` / `p9-mdl-alignment-v9` / Benchmark v2。2026-11-16 同一代码与数据版本，每配置三轮独立运行：

| 配置 | 三轮正确数（每轮 100 题） | 准确率均值 | 三轮 P95 均值 | 每 100 题平均估算成本 |
|---|---|---:|---:|---:|
| 基线：澄清开，纠错/结果检查关 | 93 / 90 / 93 | 92.00% | 10.153s | ¥0.207652 |
| 仅增加自纠错 | 94 / 93 / 95 | 94.00% | 10.393s | ¥0.209805 |
| 澄清 + 自纠错 + 结果检查 | 93 / 92 / 86 | 90.33% | 10.821s | ¥0.217553 |
| 澄清/纠错/结果检查均关 | 95 / 93 / 91 | 93.00% | 10.548s | ¥0.213380 |

该旧矩阵中，仅自纠错组实际重试0次，不能把高出的2分归为纠错收益。完整检查组实际恢复2次、退化0次，触发源均为校验拒绝，结果合理性检查触发0次；其较低均分不能证明检查器损害准确率。P95是每轮服务调用墙钟分位数的均值，不含HTTP与事后评分。详细波动、分层、逐题证据及局限见[四配置三轮归档](docs/ABLATION_RESULTS_20261116.md)。

以下保留历史结果，配置、代码和部分基准口径不同，不用于机制因果比较：

| 维度 | 2026-11-09 单轮快照（清洗后 v2） | 清洗前基准（实验四，非盲测） | 历史主口径（三轮归档） |
|---|---|---|---|
| **执行准确率** | **96.00% (96/100)** | **88.00% (88/100)** | 84 / 85 / 86 |
| **SQL 有效执行率** | **100.00%** | 100.00% | 100% |
| T1 / T2 / T3 | **100% / 95.00% / 100%** | 100% / 95.00% / 93.75% | 100% / 80–85% / 81.25–87.5% |
| T4 / T5 / T6 | **95.45% / 90.00% / 91.67%** | 90.91% / 30.00% / 91.67% | 81.82–86.36% / 40–60% / 91.67–100% |
| 按表数：1 表 / 2 表 | **96.61% / 94.44%** | 96.61% / 88.89% | 96.61% / 83–88% |
| 按表数：3 表 / 4+ 表 | **100.00% / 90.91%** | 81.82% / 50.00% | 72–81% / 41–50% |
| 表全召回 / 平均召回 / 精确率 | **99.00% / 99.80% / 约 28%** | 98.00% / 99.55% / 约 28% | 98.00% / 99.55% / 约 28% |
| Prompt Token | **2107** | 2131 | 1967 / 1971 / 1994 |
| 100 题推理成本 | **¥0.223 / 100 题**（单题 ¥0.00223） | ¥0.294 / 100 题 | ¥0.221 / 100 题 |
| P50 / P95 延迟 | **2.00s / 12.81s**（目标 <8s，未达标） | 1.98s / 14.31s | 1.89s / 12.06–17.27s |
| 测试 / 安全护栏 | **292 项全通过 / 53 条对抗 100% 拦截** | 279 全绿 / 53 条对抗 | 280 全绿 / 53 条对抗 |
| dry-run 自检 | **200/200 (100%)** | 200/200 (100%) | 200/200 (100%) |

> 历史明细：[2026-11-09 的 96% 单轮报告 (v2)](reports/eval-20261109-181803.json) · [清洗前 88% 报告](reports/eval-20261109-175426.json) · [历史基准 1](reports/eval-20261108-210214.json) · [历史基准 2](reports/eval-20261108-212550.json) · [历史基准 3](reports/eval-20261108-221405.json)。基准清洗说明与成本公式详见 [docs/EVALUATION.md](docs/EVALUATION.md)。

## 快速开始

### 1. 环境

Java 21、Maven 3.9、Docker、Python 3.10（数据脚本）。

### 2. 数据库

```powershell
docker compose -f docker/docker-compose.yml up -d
powershell -File scripts/rebuild_db.ps1
```

```bash
docker compose -f docker/docker-compose.yml up -d
for f in 01_olist_schema 02_load_olist 03_extend_schema 04_seed_extend 05_readonly_role 06_persistence; do
  docker exec text2sql-postgres psql -U text2sql -d olist -q -v ON_ERROR_STOP=1 -f "/data/schema/${f}.sql"
done
```

数据库默认 `localhost:5432/olist`；CSV 通过 Docker 只读挂载，不进仓库。

### 3. 配置与启动

```bash
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
```

```powershell
$env:AGENT_LLM_API_KEY = "sk-xxxxxxxx"
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local"
```

Key 只认环境变量 `AGENT_LLM_API_KEY` 或 `application-local.yml` 的 `api-key`。没配 Key 也能启动，但 `/api/ask` 会返回 `503 NOT_CONFIGURED`。

### 4. API

```powershell
$body = @{ question = "2018 年销售额最高的 5 个州是哪些？" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/ask `
    -ContentType "application/json; charset=utf-8" -Body $body
```

```json
{
  "status": "SUCCESS",
  "sql": "SELECT ... LIMIT 5;",
  "rowCount": 5,
  "retrievedTables": ["orders", "customers", "order_items"],
  "timings": {"retrievalMs": 12, "generationMs": 1150, "validationMs": 4, "executionMs": 18},
  "llm": {"model": "deepseek-chat", "promptTokens": 760, "completionTokens": 95}
}
```

### 5. 测试与评估

```bash
mvn test   # 当前：422 项全部通过（含 53 条对抗测试）
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_eval.ps1 -Split eval
powershell -ExecutionPolicy Bypass -File scripts/run_eval.ps1 -Split all -DryRun
```

```bash
bash scripts/run_eval.sh eval
DRY_RUN=true bash scripts/run_eval.sh all
```

`-DryRun` 不调模型，只跑 gold SQL 自检；真实 eval 需要 `AGENT_LLM_API_KEY`。

## 评测方法

- **评估集**：200 条六层难度，按 `Math.floorMod(id.hashCode(), 2)` 切 dev/eval 各 100；few-shot 只从 dev 取。
- **执行准确率**：gold SQL 与生成 SQL 都执行，`ResultNormalizer` 规范化后比较结果集；不是字符串匹配，也不是 LLM 裁判。
- **配置绑定**：报告 meta 记录模型、prompt 版本、检索开关、self-correction；数字离开配置没有意义。
- **dry-run**：把 gold SQL 当模型输出跑下游，证明评估器/校验器/执行器自身没坏；本轮 200/200。

## 技术栈

| 模块 | 选型 |
|---|---|
| 语言 / 框架 | Java 21 / Spring Boot 3.5.16 |
| LLM 接入 | Spring AI 1.1.8（OpenAI 兼容，手工装配） |
| SQL 解析 | JSqlParser 5.4 |
| 数据库 | PostgreSQL 16 |
| 编排 | 手工 Pipeline（非框架黑盒） |

## 仓库结构

```
src/main/java        # 接入 / 编排 / 检索 / 生成 / 校验 / 执行 / 缓存 / 会话
src/main/resources   # 配置、glossary / metrics / few-shot、前端
src/test/java        # 当前：422 项全部通过
data/schema          # DDL + 只读/读写角色
data/eval            # 200 条评估集
docs/                # ROADMAP / EVALUATION / FAILURE_ATTRIBUTION ...
reports/             # 多轮对照证据、历史评测、token + cost
scripts/             # 重建库、评估、成本
docker/              # PostgreSQL 容器
```

## 已知局限

| 限制 | 现状 |
|---|---|
| P95 延迟 | 四组的三轮 P95 均值 **10.153–10.821s**（目标 <8s，**未达标**）；生成占调用墙钟总和约 98%，请求总时间预算尚未完成 |
| 检索精确率 | 约 **27–28%**；Top-8 仍引入噪声表，存在 T5-007 漏表，不能用高平均召回掩盖单题缺口 |
| 多租户 / 权限 | 单租户、只读账号 `text2sql_ro`、无 RLS（行级安全隔离），不可直接外网裸露 |
| 跨业务域泛化 | 评估集聚焦于 Olist 电商场景，不宣称无需配置语义字典即可跨业务域开箱即用 |
| Agent 交互模式 | 默认 Pipeline 编排；当前四组对照关闭工具调用，不能凭本轮实验宣称 ReAct 的净收益或固定成本倍数 |
| 泛化与归因 | 当前 eval 已反复用于调优，独立盲测未完成；执行成功但结果不匹配的细分语义归因仍需人工分析 |

## 设计文档

| 文档 | 回答的问题 |
|---|---|
| [ROADMAP.md](docs/ROADMAP.md) | 做什么、不做什么、每阶段验收与取舍 |
| [IMPROVEMENT_PLAN.md](docs/IMPROVEMENT_PLAN.md) | 当前短板、分阶段改进方案与可量化验收路径 |
| [EVALUATION.md](docs/EVALUATION.md) | 消融、指标口径、历史报告、成本公式 |
| [ABLATION_RESULTS_20261116.md](docs/ABLATION_RESULTS_20261116.md) | 1,200 次真实问答、实际重试收益、延迟缺口与原始证据 |
| [SQL_EXTRACTION_20261116.md](docs/SQL_EXTRACTION_20261116.md) | SQL 输出包装修复、正文保留、安全边界与离线验收 |
| [FAILURE_ATTRIBUTION.md](docs/FAILURE_ATTRIBUTION.md) | 三次 15+14+16 条失败分别错在哪一层 |
| [DEFECT_LOG.md](docs/DEFECT_LOG.md) | 缺陷、安全绕过与修法 |
| [SECURITY.md](SECURITY.md) | 安全边界与漏洞报告方式 |

> 数据来源：Olist Brazilian E-Commerce Public Dataset；原始 CSV 不入库，见 [data/raw/README.md](data/raw/README.md)。

---

MIT License · 个人项目，欢迎 Issue / PR；提交前看 [CONTRIBUTING.md](CONTRIBUTING.md)。
