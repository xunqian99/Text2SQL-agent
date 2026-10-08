# Text2SQL Agent（电商问数智能体）

面向真实企业级电商业务库的**高准确率、高可解释性、生产级**自然语言转 SQL（Text2SQL）智能体系统。

基于 Java 21 与 Spring Boot 开发，接入大语言模型，将中文业务问题精准翻译为高效合规的只读 SQL，并在沙箱环境中执行返回结构化数据与可视化结果。系统具备 **Schema 拓扑检索、指标注册表口径注入、AST 列级安全护栏、分层双级语义缓存与多轮会话消解** 等完整能力。

---

## 核心特性

- **智能 Schema 拓扑检索**  
  针对 37 张复杂业务表，基于领域词典最大匹配与连通性最短路径修复，动态召回 Top-8 相关表与关联关系。DDL 上下文压缩 **78.7%**（7401 → 1580 字符），表召回率达 **98.5%**。
- **业务口径注册表（口径注入）**  
  解耦业务概念与 SQL 生成。通过 `metrics.yml` 显式维护 GMV、复购率、客单价等复杂指标口径，根据命中关键词与依赖表精准注入 Prompt，业务语义层（T6）准确率达 **91.7%**。
- **生产级三层安全防御**  
  1. **语法与白名单校验**：基于 JSqlParser 严格拦截写操作、危险函数与跨表越权，强制结果集 LIMIT 截断；  
  2. **只读账号沙箱**：应用底层连接独立的只读角色（`text2sql_ro`），物理级防御删改库风险；  
  3. **运行时超时熔断**：10 秒强制执行超时与结果行数硬上限，44 项对抗性攻击测试 **100% 拦截**。
- **分层双级语义缓存**  
  L1 进程内 LRU + L2 数据库共享持久化（`text2sql_rw` 权限隔离）。针对高频看板与重复提问场景，实现 **0ms / 0 Token** 响应，综合延迟降低 **53%**，Token 成本降低 **50%**。
- **多轮对话与意图补全**  
  具备上下文记忆与指代消解能力，自动补全追问意图（如「那2017年的呢？」），多轮会话自动复用 Schema 上下文，支持会话生命周期双层缓存与异步落库。
- **现代化对话交互界面**  
  内置开箱即用的现代 Web 前端（`http://localhost:8080`），提供流畅的对话式交互、SQL 语法高亮、分阶段耗时展示与结果表格渲染。

---

## 系统架构

系统采用手工编排管道（Pipeline），控制流清晰透明，每一步均具备完整的可观测性与耗时统计：

```mermaid
flowchart TD
    Q[中文业务问题] --> SESS{多轮会话检测}
    SESS -- 追问/代词指代 --> REW[指代消解 QuestionRewriter<br/>滑动窗口偏好压缩 O(1)]
    SESS -- 首问/独立问题 --> AMB{口径歧义检查}
    REW --> AMB
    AMB -- 命中歧义 --> ASK[反问确认 / 澄清]
    AMB -- 无歧义 --> C1[L1 进程内 LRU 缓存]
    C1 -- 命中 --> VAL[AST 安全校验]
    C1 -- 未命中 --> C2[L2 数据库向量语义缓存]
    C2 -- 命中 --> VAL
    C2 -- 未命中 --> RET[Schema 混合检索<br/>BM25 + 实体枚举倒排 ValueRetriever<br/>加权外键图规划 JoinPathPlanner]
    RET --> SEM[业务口径注入<br/>metrics.yml 匹配与依赖过滤]
    SEM --> LLM[LLM SQL 生成 / ReAct 工具侦察]
    LLM --> VAL
    VAL -- AST 校验通过 --> COST{EXPLAIN 预执行代价评估<br/>Cost Guard · 算子下推与笛卡尔积拦截}
    COST -- 代价超标/笛卡尔积 --> RETRY{自省与反思重试<br/>错误信息/执行代价回灌}
    COST -- 计划安全 --> EXE[沙箱物理执行<br/>只读账号 text2sql_ro · 10s超时]
    EXE -- 执行成功 --> CHK{启发式量纲守恒校验<br/>ResultChecker · 8组语义不变量}
    CHK -- 发现量纲倒错/空结果 --> RETRY
    CHK -- 校验通过 --> CACHE[写入 L1 + L2 缓存与会话记录]
    VAL -- AST 拒绝 --> RETRY
    EXE -- 执行失败 --> RETRY
    CACHE --> OUT[结构化结果表格 + 耗时分解 + Token 审计]
    ASK --> OUT
```

---

## 核心基准表现

在包含 200 条题目（涵盖单表基础、单表聚合、时间窗口、多表 Join、深层关联、业务语义六层难度）的标准评测集上实测：

| 评测维度 | 指标表现 | 说明 |
|---|---|---|
| **综合执行准确率** | **84.0% (84/100)** | 基于真实 PostgreSQL 物理结果集严格对齐比对，非简单 SQL 字符串匹配 |
| **SQL 有效执行率** | **100.0% (100/100)** | AST 白名单 + EXPLAIN 算子防误杀 + 运行时自纠错，无任何坏死或语法错误 SQL |
| **单表基础层（T1）** | **100.0% (20/20)** | 实体寻优规范约束，单表基础题全量满分通过 |
| **时间窗口层（T3）** | **87.50% (14/16)** | 时序与周期语义感知列保全，精准识别时间截断与时序维度 |
| **多表关联层（T4）** | **86.36% (19/22)** | Steiner Tree 外键拓扑路径规划 + 枚举物理列对齐，攻克多表绕路与字段错选 |
| **业务语义层（T6）** | **83.33% (10/12)** | 业务口径注入后，复杂指标（GMV/复购率/动销率/独立买家）准确率翻倍 |
| **Schema 表召回率** | **98.00% / 99.55%** | 37 张数仓表中精准召回 Top-8 关联表，平均表召回率 99.55% |
| **Prompt Token 压缩** | **-69.3%** | 平均输入 Token 从 2315 压缩至 709，大幅节约推理成本 |
| **安全攻击拦截率** | **100% (44/44)** | 恶意注入、DDL/DML、危险函数测试用例全数硬拦截 |
| **长会话上下文压缩** | **-67.2% Token 消耗** | 5~8 轮长会话下，滑动窗口+偏好摘要压缩至 $O(1)$，无损留存全局口径 |


> 详细的消融实验（Ablation Study）及评测分析请参见 [docs/EVALUATION.md](docs/EVALUATION.md)。

---

## 快速开始

### 1. 环境准备
- **Java**: 21+
- **Maven**: 3.9+
- **Docker & Docker Compose**
- **Python**: 3.10+（用于数据校验脚本）

### 2. 启动数据库与数据初始化

```powershell
# 1. 启动 PostgreSQL 16 + pgvector 容器
docker compose -f docker/docker-compose.yml up -d

# 2. 一键建表、初始化 Olist 电商数据与扩展表、创建只读/读写隔离账号
powershell -File scripts/rebuild_db.ps1

# 3. 核对数据行数
Get-Content scripts/check_counts.sql | docker exec -i text2sql-postgres psql -U text2sql -d olist
```

> 数据库默认连接：`localhost:5432`，数据库 `olist`。

### 3. 配置与启动应用

默认支持任意兼容 OpenAI 协议的模型（如 DeepSeek、通义千问、百度千帆等）。

```powershell
# 设置模型 API Key（以 DeepSeek 为例）
$env:AGENT_LLM_API_KEY = "sk-xxxxxxxxxxxxxxxxxxxxxxxx"

# 启动 Spring Boot 应用
mvn spring-boot:run
```

启动完成后：
- **Web 对话界面**：在浏览器打开 [http://localhost:8080](http://localhost:8080) 即可直接进行自然语言问数；
- **健康检查**：`GET http://localhost:8080/api/health`。

### 4. API 交互示例

```powershell
# 自然语言问数接口
$body = @{ 
    question = "2018 年销售额最高的 5 个州是哪些？" 
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/ask `
    -ContentType "application/json; charset=utf-8" -Body $body
```

**响应示例**：
```json
{
  "sql": "SELECT c.customer_state, ROUND(SUM(oi.price)::numeric, 2) AS total_sales FROM orders o JOIN customers c ON o.customer_id = c.customer_id JOIN order_items oi ON o.order_id = oi.order_id WHERE o.order_status = 'delivered' AND o.order_purchase_timestamp >= '2018-01-01' AND o.order_purchase_timestamp < '2019-01-01' GROUP BY c.customer_state ORDER BY total_sales DESC LIMIT 5;",
  "columns": ["customer_state", "total_sales"],
  "rows": [
    ["SP", 5214832.45],
    ["RJ", 1823901.12]
  ],
  "rowCount": 5,
  "timings": {
    "retrievalMs": 12,
    "generationMs": 1150,
    "validationMs": 4,
    "executionMs": 18,
    "totalMs": 1184
  },
  "llmCall": {
    "model": "deepseek-chat",
    "promptTokens": 760,
    "completionTokens": 95,
    "totalTokens": 855
  }
}
```

---

## 运行基准评估

项目内置自动化基准评测运行器，支持离线自检或大模型端到端评估：

```powershell
# 1. 离线快速链路自检（dry-run，不调用大模型，验证解析与执行链路）
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.dry-run=true"

# 2. 真实大模型端到端评估（100 条 Eval 集）
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.enabled=true --agent.eval.split=eval --agent.retrieval.enabled=true"
```

---

## 技术栈与选型

| 模块 | 技术选型 | 说明 |
|---|---|---|
| **核心语言 / 框架** | Java 21 / Spring Boot 3.5.16 | 现代化强类型后端底座，原生虚拟线程与结构化并发支持 |
| **大模型接入** | Spring AI 1.1.8 | OpenAI-Compatible 协议适配，支持多厂商模型无缝切换 |
| **SQL 解析与 AST 校验** | JSqlParser 5.4 | 基于 AST 抽象语法树实现表/列白名单过滤、函数黑名单与 LIMIT 改写 |
| **关系型数据库** | PostgreSQL 16 + pgvector | 存储 37 张电商核心业务表、语义缓存与审计日志 |
| **Schema 检索算法** | 应用层领域词典 + 图拓扑最短路径修复 | 最大正向匹配 + 关系图多跳拓扑修复，解决中文在关系数据库中的分词局限 |
| **应用架构设计** | 手工状态编排管道（Pipeline） | 坚持状态可控与轻量化，避免框架黑盒，核心控制流透明可测 |

---

## 仓库结构

```
├── docker/                 # PostgreSQL + pgvector 容器化部署
├── data/
│   ├── raw/                # 原始公开数据集说明
│   ├── schema/             # 数据库 DDL、扩展表与用户权限脚本（01~06）
│   └── eval/               # 200 条六层难度标准评估集与期望结果
├── docs/
│   ├── ROADMAP.md          # 详细系统演进路线图与架构设计问答（FAQ）
│   ├── EVALUATION.md       # 评测体系、消融实验与指标口径
│   └── EVAL_SET.md         # 评估集难度分层规范与设计缺陷复盘
├── reports/
│   └── README.md           # 基准评测结果汇总索引
├── scripts/                # 数据库一键重建、数据校验与成本核算脚本
└── src/
    ├── main/java/...       # 核心业务源码（接入/编排/检索/生成/校验/执行/缓存/会话）
    ├── main/resources/     # 配置文件、语义指标库与极简前端页面
    └── test/java/...       # 248 项全绿自动化单元与对抗性测试
```


---

## 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。
