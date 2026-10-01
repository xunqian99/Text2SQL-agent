# Text2SQL Agent（电商订单域）

把中文业务问题翻译成 SQL 并执行的 Agent。目标不是「能跑」，而是**准确率可量化、
失败可归因、每个设计决策都能讲出取舍**。

## 当前进度

| 阶段 | 状态 |
|---|---|
| 阶段 0 地基（骨架 / 数据库 / 评估集） | 已完成 |
| 阶段 1 端到端最小闭环 + baseline | 已完成（真实 baseline 待跑，Key 已配） |
| 阶段 2 Schema 检索（词典 / 词法召回 / 连通性修复） | 已完成，验收达标 |

阶段 2 的实测数字（详见 [docs/ROADMAP.md](docs/ROADMAP.md) 第 11 节与
[reports/README.md](reports/README.md)）：

| 指标 | 数值 | 说明 |
|---|---|---|
| 单元测试 | 53/53 通过 | 校验 14 + 规范化 9 + 提取 7 + 检索 12 + 表召回 11 |
| 评估器自检（dry-run，200 条） | 执行准确率 100% | 用 gold_sql 冒充模型输出，证明评估器本身无 bug |
| 表召回率（Top-5，评估集 100 条） | **96.53%** | 验收线 90% |
| 表召回率（Top-8，评估集 100 条） | 98.48% | 默认配置，多换 1.9 个百分点召回、约 400 字符上下文 |
| 平均召回表数 | 4.78（全量 37.0） | 精确率从 5.24% 提到 35.99% |
| 平均 DDL 字符 | 1185（全量 7401） | **下降 84%**，直接省 prompt token |
| 真实 baseline / 检索版执行准确率 | 待测 | 需要千帆 Key 跑真实链路，预期 baseline 55%–65% |

**为什么先跑 dry-run 而不是直接跑 baseline**：如果评估器自己有 bug，跑出来的
准确率数字就是假的，而且会误导后续所有优化方向。用标准 SQL 自检一遍，把
「评估器有问题」和「模型不准」这两件事分开——这是 100% 这个数字唯一的用途。

**检索层的数字为什么可信**：200 条评估集按 id 哈希切成开发集 100 / 评估集 100，
所有参数（topK、边权重、连通性修复开关）只在开发集上调，评估集只在验收时跑一次。
检索的期望表不是人工另标的，而是从标准 SQL 解析出来的，避免「标注和答案分叉」。

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

# 配 Key 之后才真正能回答问题（Key 只走环境变量，不写进任何文件）
$env:AGENT_LLM_API_KEY = "sk-..."
mvn spring-boot:run
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

响应里除了 `sql` 和 `rows`，还带 `llm`（token / 耗时 / 成本）与 `timings`
（检索 / 生成 / 校验 / 执行分阶段耗时）——这是为了能回答「每次调用花了多少」
和「瓶颈在哪一层」，而不是只给一个总耗时。

### 跑评估

```powershell
# 1) 先自检：用 gold_sql 冒充模型输出，必须是 100%，否则评估器有 bug
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.dry-run=true"

# 2) 调参只看开发集（检索开关、topK 都在这一份上试）
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.dry-run=true --agent.eval.split=dev --agent.retrieval.enabled=true"

# 3) 验收才跑评估集，同一套配置只跑一次
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.split=eval --agent.retrieval.enabled=true --agent.retrieval.top-k=5"

# 4) 真实链路（需要千帆 Key），先 baseline 再检索版，两行数字一起进消融表
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.split=eval --agent.retrieval.enabled=false --spring.profiles.active=local"
```

报告落在 `reports/eval-<时间戳>.json`，里面同时记录 prompt 版本、模型名、
配置开关、数据集切分和指标——数字离开配置就没有意义，两者必须绑在一起。
报告的字段含义与保留口径见 [reports/README.md](reports/README.md)。

**评估纪律**：评估集不能用来调 prompt 或调参。一旦在 `split=eval` 上试过参数，
那一行数字就作废了，后面所有消融实验都失去可比性。

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

200 条中文问题，六层难度，每条都有人工审核过的标准 SQL：

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
├── docs/          ROADMAP（路线图）、EVAL_SET（评估集说明）
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
