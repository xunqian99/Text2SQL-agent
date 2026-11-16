# 阶段4：新数据库初始化与运行复现

## 结论与范围

2026-11-16完成**同一Windows主机上的全新PostgreSQL容器/数据卷复现**。不是重装操作系统、全新电脑或空Maven缓存验收，也不是阶段4整体通过。阶段3P95优化继续暂缓；旧20条HTTP结果17/20保持历史口径。

新增入口`prepare_clean_demo.ps1`只创建独立容器和数据卷，拒绝复用已有同名资源，固定数据库绑定127.0.0.1。原`text2sql-postgres`及`text2sql-pgdata`没有被重建、停止或删除。没有运行旧`deploy.sh`，也没有修改原Compose或原本地私有配置。

## 已测量的结果

| 检查项 | 结果 | 证据与边界 |
|---|---|---|
| 原始输入 | 9份CSV，逐文件大小/SHA-256冻结 | CSV不提交，protocol.json记录身份；缺文件直接失败 |
| 初始化 | 6/6 SQL脚本成功，合计25,451ms | 建原始表、导数、扩展表、种子、只读角色、持久化；非完整启动墙钟 |
| 数据 | 37张业务表＋4张持久化表，共2,616,948行 | table-counts.json；本轮空持久化表不计入行数贡献 |
| 只读与持久化账号 | 各37张业务表可写权限数均0 | 检查INSERT/UPDATE/DELETE/TRUNCATE；两账号公共Schema CREATE均false |
| 实际防写探针 | 4/4被拒绝 | 两账号各执行UPDATE WHERE false和CREATE TABLE；检查非零退出码及permission denied |
| 日志权限 | 可INSERT，不可UPDATE/DELETE | 追加型日志权限符合原设计；未向业务账号补写权限 |
| 持久化建表幂等 | 第二次执行06脚本成功 | 没有重跑破坏性的01至04脚本 |
| 全量Gold自检 | 200/200，模型0次，费用¥0 | dry-run，不是模型准确率；原问题/Gold保持冻结 |
| 真实HTTP问数 | 1/1，54,011与新库SQL一致 | 2018年订单数；HTTP1,418ms、内部耗时见原始响应 |
| 模型用量 | 1次，2,228 Token，未知0 | 输入2,151，输出77；缓存命中1,920、未命中231 |
| 配置费用估价 | ¥0.0005774 | 231/1000×0.001＋1920/1000×0.00002＋77/1000×0.004；不是账单价承诺 |
| 会话与退出 | 1轮，关闭成功、persisted=false | demo本轮未启用持久化；HTTP端口与Java进程均已退出 |
| 数据库清理 | 新容器停止、数据卷保留；原容器仍运行 | 没有自动删除数据，可显式重启继续检查 |

镜像实际ID、端口/卷绑定、6份SQL和9份CSV指纹均归档。新库初始化脚本沿用固定setseed，但种子随机分配还受查询行顺序影响，因此**不能保证新库扩展行与旧库逐字节相同**。没有把两库结果差异算成准确率变化。Gold dry-run中的延迟只表示本地Gold执行/校验，不能代替模型P95。

## 可复现路径

先按LOCAL_DEMO准备Java、Maven、Docker镜像和Olist CSV。在仓库根目录执行（示例ID和输出目录不能已存在）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/prepare_clean_demo.ps1 -RunId my-clean-run -DatabasePort 15432 -OutputDir reports/my-clean-run
```

入口不接受“对现有库重建”的开关。目标容器和卷由RunId派生并检查不存在；原始CSV/SQL仅以只读挂载提供。容器管理员密码随机生成，只通过子进程环境传递；应用只读账号使用初始化SQL的本机演示默认凭据，不适用于公网环境。

用模板单独配置新环境，不覆盖个人application-local.yml：

```powershell
New-Item -ItemType Directory reports/my-clean-run/config
Copy-Item src/main/resources/application-local.example.yml reports/my-clean-run/config/application-local.yml
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:reports/my-clean-run/config/'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:15432/olist'
$env:SPRING_DATASOURCE_USERNAME = 'text2sql_ro'
$env:SPRING_DATASOURCE_PASSWORD = 'text2sql_ro'
$env:AGENT_PERSISTENCE_URL = 'jdbc:postgresql://127.0.0.1:15432/olist'
$env:AGENT_PERSISTENCE_ENABLED = 'false'
powershell -ExecutionPolicy Bypass -File scripts/run_eval.ps1 -Split all -DryRun -Port 18085 -OutputDir reports/my-clean-run/gold
powershell -ExecutionPolicy Bypass -File scripts/start_local.ps1 -Mode demo -Port 18086 -CallLogDir reports/my-clean-run/llm-calls
```

本轮Gold实际使用上述run_eval入口；HTTP部分使用相同Spring Boot生产主类、local,demo配置、target/classes与Maven依赖类路径直接启动，未使用模型替身、测试编排器或缓存。有效配置与新库内5个text2sql_ro JDBC连接归档，证明连接目标是新容器。HTTP检查按LOCAL_DEMO的健康/问数命令执行，将地址端口换成18086。模型Key从User环境继承，不作为参数或配置明文保存。

验收顺序：健康mode=demo且ready=true→原200题dry-run全部通过→新库只读SQL订单数54,011→真实问数相同→逐调用核对requestId:callNumber、Token、费用→会话1轮与关闭→停止自己的应用和隔离容器。恢复普通本机演示时使用新终端，避免这些临时连接变量指向已停止的新库。

```powershell
docker stop text2sql-clean-my-clean-run
# 需要重新检查时：
docker start text2sql-clean-my-clean-run
```

本次已停止的证据容器是`text2sql-clean-acceptance-20261116`。保留卷以便核查；脚本无自动删除。失败时保留现场，换新RunId重新验收，不能在原目录覆盖失败结果。

## 技术与设计导读

**数据流例子**：“2018年有多少笔订单？”→demo入口加载独立模板和127.0.0.1:15432只读连接→检索Olist表和语义信息→真实模型生成按2018年边界计数的SQL→AST与EXPLAIN→PostgreSQL返回54,011→响应与独立调用日志。模型不可接触初始化管理员账号；建库和问数是两个权限与生命周期不同的入口。

**技术抓手与来源追踪**：RunId明确标记容器、数据卷和证据目录；protocol记录镜像ID、输入SHA-256、版本与端口；Docker只读bind mount提供CSV/SQL。PowerShell检查每步退出码，SQL使用ON_ERROR_STOP防止中间失败后继续伪装成功。pg_tables/has_table_privilege检查整个37表集合，实际拒绝探针补充验证；HTTP的requestId:callNumber把响应与唯一真实模型账目连接。本轮没有排序、Top-K或权重修改，不影响directHits/expandedTables来源与0.4扩展衰减。

**效果**：把“当前机器已有库能用”补成“空数据库按公开输入重建后也能完成200项自检与一次真实问数”。新增配置工具通过实测，沿用生产Java566/566，不把200个Gold执行和手动场景加入Java测试数量。无新依赖或业务SQL生成规则改动。

**取舍与边界**：没有复用rebuild_db.ps1，因为它硬编码现有容器并执行DROP/TRUNCATE，不能拿用户的现有库做复现试验；选择新容器/卷，代价是约2.6百万行的数据空间。端口占用、缺CSV、已有目录/容器/卷、SQL失败都直接终止。镜像按已解析ID运行，避免运行期间标签漂移；但镜像下载、空缓存依赖安装和全新主机仍未验证。账户默认密码和本机绑定不等于鉴权，原运行容器端口配置也没有被本轮修复。

**面试追问1：为什么200/200不能写模型准确率100%？** dry-run直接执行Gold并验证评估链路，没有让模型生成SQL；必须将评估器可用性和模型能力分开。真实问数仅1条，不支持泛化结论。

**面试追问2：固定seed为什么还不能保证数据完全相同？** 随机数序列固定不代表序列分配给同一业务主键；无稳定ORDER BY的扫描可能改变配对。应绑定CSV/SQL/镜像和实际数据结果，若要字节级复现需另外设计确定性主键映射；本轮没有偷偷改种子脚本。

**用户配合**：本轮无需新增配置。需能解释新旧库如何区分、为何运行应用必须使用ro账号，以及为何¥0.0005774是配置估价。仍欠访问控制/限流、NULL语义与澄清误报处理、独立盲测等验收；阶段3P95继续暂缓。
