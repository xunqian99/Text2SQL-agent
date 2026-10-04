# Text2SQL Agent（电商订单域）

把中文业务问题翻译成 SQL 并执行的 Agent。目标不是「能跑」，而是**准确率可量化、
失败可归因、每个设计决策都能讲出取舍**。

## 当前进度

| 阶段 | 状态 |
|---|---|
| 阶段 0 地基（骨架 / 数据库 / 评估集） | 已完成 |
| 阶段 1 端到端最小闭环 + baseline | 已完成（真实 baseline：47%） |
| 阶段 2 Schema 检索（词典 / 词法召回 / 连通性修复） | 实现与评估已收尾（真实 Top-8：52%；原定 +8 个百分点目标未达成） |
| 阶段 3 Join 路径规划（列级关系图 / 连接树） | 实现完成、真实评估跑完；**准确率零变化、token +29%，功能默认关闭** |
| 阶段 4 语义层（业务指标注册表） | **实现完成：整体 62% → 71%（+9），补强后 T6 达到 91.7%；整体目标未达成** |
| 阶段 5 自纠错、输出纪律与护栏 | **实现完成：最优配置四次运行 77% / 77% / 75% / 77%；护栏全部就位；自纠错提升 +0pp（未达 +3pp），详见 ROADMAP** |

阶段 5 的护栏部分全部完成：AST 列级白名单、只读数据库账号、歧义反问（默认关闭）、
44 个对抗性测试。**恶意 SQL 拦截率 100%（44/44），这一项达标**；
**自纠错提升 +3pp 这一项未达标，实测 +0pp**——首答 SQL 有效率长期在 96–100%，
没有错误信号可回灌。两个数字都在 ROADMAP 第 4 节列了完整证据。

**阶段 5 的三条结论，比数字更重要**：

1. 自纠错只在有错误信号时有用。首答 SQL 有效率已经是 100%，
   所以线上可用的 `ERRORS_ONLY` 触发 0 次、收益 0；给它一个完美的结果校验器
   也只能多救 4 条。**瓶颈在首答质量，不在重试次数。**
2. 自纠错第一版把 gold 样例写进了反馈提示，70 条跑到 95.7%。
   **那是答案泄露，不是模型变强**；改掉之后同配置是 72%。
3. 同一份配置连跑三次是 **71% / 68% / 70%**。100 条 eval 的单轮波动约 ±3pp，
   **小于 3 个点的改动不能用单轮结果下结论**。

**当前最优配置**（模型 `deepseek-flash`，eval 100 条）：

| 指标 | 语义层关 | 语义层开 |
|---|---|---|
| 执行准确率 | 62% | **71%** |
| 业务语义层（T6） | 16.67% (2/12) | **91.67% (11/12)** |
| SQL 有效率 | 99% | **100%** |
| 平均输入 token | 734 | 778 |

报告：`reports/eval-20261004-180116.json`（补强前关）、`reports/eval-20261004-181111.json`（补强前开）、
`reports/eval-20261004-205657.json`（补强后开）。
**注意：阶段 1–3 的数字来自 `ernie-4.5-turbo-32k`，阶段 4 来自 `deepseek-flash`，
跨模型不可相减**——换模型本身让基线从 52% 变成 62%。

阶段 2 的实测数字（完整方法、报告来源与局限见
[docs/EVALUATION.md](docs/EVALUATION.md)，路线与验收见
[docs/ROADMAP.md](docs/ROADMAP.md) 第 11 节）：

| 指标 | 数值 | 说明 |
|---|---|---|
| 单元测试 | **114/114 通过** | 阶段 4 补强后：原有 111 条 + 3 条上下文与模板测试 |
| 历史评估器自检（dry-run，200 条） | 执行准确率 100% | 用 gold_sql 输入链路；只说明已有样本自检通过，不是模型成绩 |
| 表召回率（Top-5，评估集 100 条） | **96.53%** | 验收线 90%，检索层口径 |
| 表召回率（Top-8，评估集 100 条） | **98.48%** | 阶段 2 最终归档配置（检索需显式开启） |
| 平均召回表数 | 6.69（全量 37.0） | 平均精确率 5.05% → 28.42% |
| 平均 DDL 字符 | 1580（全量 7401） | **下降 78.7%**，直接压缩 prompt 上下文 |
| 真实 baseline 执行准确率 | **47%（47/100）** | 全量 37 张表 |
| 真实 Top-8 检索版执行准确率 | **52%（52/100）** | 相比 baseline **+5 个百分点** |
| 阶段 3 join 路径规划 | 52% → 52%（**0**） | join 类错误 4→1 条，但整体被「过度 join」抵消，详见 ROADMAP |
| 阶段 4 语义层（deepseek-flash） | **补强后 T6 91.67%（11/12）** | 语义层口径目标达标；整体仍为 71%，未达 78% |
| SQL 有效率 | 78% → **92%** | 检索后提升 14 个百分点 |
| 平均输入 token | 2315 → **709** | 下降约 69%；不包含输出 token |
| 平均 / P95 延迟 | 4837 / 11721ms → **4443 / 8496ms** | 编排链路耗时，不含执行 gold_sql |
| 金额成本 | 尚未有效估算 | 配置单价为 0，报告的 0 元不代表免费 |

阶段 4 的“不同问法结果一致”尚未有独立稳定性报告，当前不能标记为已验收。
阶段 5 的入口是自纠错：保留首次 SQL，回传执行错误或结果不匹配信息，再做有限重试。

**为什么先跑 dry-run 而不是直接跑 baseline**：如果评估器自己有 bug，跑出来的
准确率数字就可能失真，而且会误导后续优化方向。用标准 SQL 自检能发现已有样本
上的链路问题，但不能证明评估器没有任何 bug，也不能排除真实结果的比对误判。

**评估边界**：200 条按 id 哈希切成 dev 100 / eval 100；期望表从标准 SQL 解析，
避免「表标注与答案分叉」。历史诊断看过全部题目，eval 也已跑过多个配置，
所以这些是内部迭代的单轮结果，不是从未见过的独立测试集成绩。
保留历史 Top-5 的 54% 结果；选 Top-8 是参考 dev 上的召回与准确率取舍，
不宣称 Top-8 在 eval 上优于 Top-5。

## 快速开始

```powershell
# 1. 下载原始数据集（未纳入版本控制，约 170MB，见 data/raw/README.md）
#    把 Olist 的 9 个 CSV 放到 data/raw/olist/

# 2. 启动数据库（首次会自动拉取 pgvector/pgvector:pg16 镜像）
docker compose -f docker/docker-compose.yml up -d

# 3. 建表 + 导入 Olist 真实数据 + 生成扩展数据（约 1-2 分钟）
powershell -File scripts/rebuild_db.ps1

# 4. 核对行数（把脚本喂给容器内的 psql）
Get-Content scripts/check_counts.sql | docker exec -i text2sql-postgres psql -U text2sql -d olist

# 5. 校验评估集（需要 Python 3）
python scripts/validate_eval_set.py
```

数据库连接：`localhost:5432`，库 `olist`，用户 `text2sql`，密码 `text2sql`。

### 启动应用

```powershell
# 不配 Key 也能启动：数据库、校验、评估链路都可用，只有 /api/ask 会返回 503
mvn spring-boot:run

# 默认 application.yml 使用 DeepSeek；配对应厂商的 Key 后才真正能回答问题
$env:AGENT_LLM_API_KEY = "sk-..."
mvn spring-boot:run

# 本项目正式评估使用千帆：先配置本地 application-local.yml，再启用 local
# 本地配置被 .gitignore 排除，切勿把真实 Key 提交到仓库
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --agent.retrieval.enabled=true --agent.retrieval.top-k=8"
```

两个接口：

```powershell
# 健康检查：一次请求就能区分「进程活着」和「能回答业务问题」
Invoke-RestMethod http://localhost:8080/api/health
# 未配 Key：{"status":"UP","ready":false,"llmConfigured":false,"schemaTableCount":37}
# 已配 Key：{"status":"UP","ready":true,"llmConfigured":true,"schemaTableCount":37}

# 提问
$body = @{ question = "2018 年有多少笔订单？" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/ask `
  -ContentType "application/json; charset=utf-8" -Body $body
```

响应里除了 `sql` 和 `rows`，还带 `llmCall`（token / 耗时 / 成本）与 `timings`
（检索 / 生成 / 校验 / 执行分阶段耗时）——这是为了能回答「每次调用花了多少」
和「瓶颈在哪一层」，而不是只给一个总耗时。

### 跑评估

```powershell
# 1) 全量自检：输入 gold_sql，不调用 LLM；非 100% 时先检查链路或评估集
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.dry-run=true --agent.eval.split=all --agent.eval.limit=0 --agent.retrieval.enabled=false"

# 2) dev 真实链路：后续调参在这一份上试（需要千帆 Key）
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --agent.eval.enabled=true --agent.eval.dry-run=false --agent.eval.split=dev --agent.eval.limit=0 --agent.retrieval.enabled=true --agent.retrieval.top-k=8"

# 3) eval baseline：全量 schema，正式对照模型 ernie-4.5-turbo-32k
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --agent.llm.model=ernie-4.5-turbo-32k --agent.eval.enabled=true --agent.eval.dry-run=false --agent.eval.split=eval --agent.eval.limit=0 --agent.retrieval.enabled=false"

# 4) eval 检索版：阶段 2 最终归档配置
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --agent.llm.model=ernie-4.5-turbo-32k --agent.eval.enabled=true --agent.eval.dry-run=false --agent.eval.split=eval --agent.eval.limit=0 --agent.retrieval.enabled=true --agent.retrieval.top-k=8"
```

`limit=0` 表示不截断，切分后才得到完整的 dev/eval 各 100 条。
当前加载器先截断再切分，写 `limit=100` 会只留下约 50 条。
PowerShell 命令末尾不要加 Linux 的 `\`。完整机器路径命令见评估文档；
本轮正式报告已存在，无需为了文档收尾再次付费跑评估。

报告落在 `reports/eval-<时间戳>.json`，里面同时记录 prompt 版本、模型名、
配置开关、数据集切分和指标——数字离开配置就没有意义，两者必须绑在一起。
正式结果与评估口径见 [docs/EVALUATION.md](docs/EVALUATION.md)。
报告的字段含义与保留口径见 [reports/README.md](reports/README.md)。

**后续评估纪律**：调 prompt 与参数只用 dev，先冻结配置再做 eval 阶段验收。
既有 eval 结果仍保留作内部回归参照，但不能包装成严格无泄漏的泛化评估；
需要这种证明时，应另建未见测试集。

## 数据

数据集是 [Olist 巴西电商公开数据集](https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce)，
包含约 9.9 万笔订单、11.3 万条订单明细、9.6 万个自然人客户。

在这个真实数据集之上，扩展了 28 张业务表（会员、地址、营销、物流库存、客服、财务、商品、搜索），
共 **37 张表**。扩展的目的不是堆数量，而是**制造真实的 schema 歧义**：

- `customer_id`（订单级）与 `customer_unique_id`（人级别）
- `products.product_name_lenght` 保留了原始拼写错误
- 状态字段用数字代码（`1=正常 2=冻结`），含义写在列注释里
- `products` 到 `brands` 必须经过 `product_brand_map` 桥接表
- `member_snapshot` 与 `members` 存在冗余，且等级会有漂移

这些坑是刻意设计的——它们正是 schema 检索和语义层存在的理由。

## 评估集

200 条中文问题，六层难度，每条都有标准 SQL；标有【需确认】的业务口径仍需用户审核：

| 层 | 条数 | 考察 |
|---|---|---|
| T1 单表基础 | 40 | 过滤、排序、去重、NULL |
| T2 单表聚合 | 40 | COUNT/SUM/AVG、GROUP BY |
| T3 时间窗口 | 30 | 近 N 天、自然周月季 |
| T4 多表 join | 45 | 2–3 表关联 |
| T5 深层 join | 20 | 4 表以上、自连接、桥接表 |
| T6 业务语义 | 25 | GMV、复购率、留存等口径 |

评分用**执行准确率**：比较结果集而不是 SQL 字符串，因为同一个问题有无数种正确写法。

细节见 [docs/EVAL_SET.md](docs/EVAL_SET.md)。

## 技术栈

| 环节 | 选型 |
|---|---|
| 语言 / 框架 | Java 21 / Spring Boot 3.5.16 |
| LLM 接入 | Spring AI 1.1.8 |
| SQL 解析 | JSqlParser 5.4 |
| 数据库 | PostgreSQL 16 + pgvector |
| 检索 | 应用层中文语义词典 + 最大匹配 + 关系图扩展（`tsvector` 对中文实测无效，见 ROADMAP 方案变更记录） |
| 编排 | Spring Service 手工编排，不用 Agent 框架 |

完整的选型理由与被否决的替代方案见 [docs/ROADMAP.md](docs/ROADMAP.md) 第 6 节。

## 仓库结构

```
├── docs/          ROADMAP（路线图）、EVAL_SET（评估集说明）、EVALUATION（实测与验收）
├── docker/        PostgreSQL + pgvector
├── data/
│   ├── raw/       原始数据集（未入库，见 data/raw/README.md）
│   ├── schema/    建表与造数脚本（01→02→03→04）
│   └── eval/      200 条评估集 + 标准答案快照
├── scripts/       重建数据库、评估集校验、gold 结果刷新
├── reports/       评估报告（每次运行一份 JSON，含配置快照与指标）
└── src/main/
    ├── resources/schema/glossary.yml   中文语义词典（可审阅的领域知识资产）
    └── java/com/text2sql/agent/
        ├── api/            接入层
        ├── orchestrator/   编排层
        ├── retrieval/      检索层
        ├── generation/     生成层
        ├── validation/     校验层
        ├── execution/      执行层
        ├── evaluation/     评估运行器与报告
        └── observability/  观测层
```
