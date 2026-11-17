# 阶段4：同机空Maven缓存构建与可执行包验收

2026-11-17完成同一Windows主机上的空Maven缓存下载、后续离线构建、无Key运行和全量Gold自检。阶段3 P95继续暂缓。首轮PowerShell包装脚本因原生stderr处理退出异常，未取得原Maven退出码，失败现场保留；不能说首轮一条命令无错误通过。后续使用同一缓存，以Maven离线verify取得退出码0及完整测试/打包证据。

本轮不包含全新操作系统、全新电脑或真实模型问数，也没有把旧的新数据库初始化证据混称为“同一套全新环境全部通过”。18:00后已冻结的20条HTTP功能复验将绑定本轮可执行包，沿用原题、Gold、模型策略和个人配置身份；付费请求仍须逐次通过空闲时段门禁。

## 验收结果

| 检查 | 实测 | 证明范围 |
|---|---|---|
| 导出源码 | 冻结提交9d6285a，268个文件 | 只导出pom.xml、src、data、scripts，逐文件与归档比对无修改；不导入个人local配置、target或未提交演示稿 |
| Maven起始缓存 | 0个文件 | 新建独立临时目录，拒绝复用；命令行明确指定maven.repo.local |
| 配置隔离 | 用户与全局settings均被显式覆盖 | 同一公开Aliyun镜像，无私有仓库账号；子进程移除AGENT_/SPRING_/JVM/Maven覆盖环境，不修改User/Machine持久环境 |
| 依赖结果 | 185个JAR、347个POM，合计532个制品 | 每个本地SHA-1与下载的校验文件相符，另记SHA-256；347份远端来源记录均为本轮公开镜像ID |
| 缓存空间 | 1,411个文件、85,758,424字节 | 包含制品、校验及来源元数据，不是模型Token或调用费用 |
| 首轮现场 | 包装脚本退出1；原Maven退出码未知 | 68套件710/710 XML及可执行包已出现；日志在Mockito警告处中断，不据此推断原Maven退出成功 |
| 同缓存离线verify | 退出0；710/710，68套件，失败/错误/跳过0 | 显式-o离线，未删除缓存或重新联网下载；外部命令以退出码判定，stderr警告不作终止异常 |
| 离线verify墙钟 | 27,471ms | 只覆盖恢复后的离线verify，不是首次下载时间或模型延迟 |
| 可执行包 | Spring Boot JarLauncher，79个内嵌运行库 | 每个运行库内容SHA可追溯到冷缓存制品或其内嵌工具资源 |
| 实际无Key运行 | 8/8，启动观察5,129ms | 健康200、baseline、Schema37、llmConfigured/ready均false；问数503 NOT_CONFIGURED；无模型日志 |
| 监听与退出 | 实查127.0.0.1；进程20408已退出、端口64747关闭 | netstat按本轮PID过滤，非仅查看启动参数；终止的是本轮无写入应用，不操作用户原库 |
| 完整Gold自检 | 200/200，退出0，整进程墙钟36,288ms | demo配置、dry-run直接执行原Gold；全部模型Token/费用0；不是Text2SQL准确率100% |
| 供应商调用 | 0次，费用0 | 评测/运行进程明确空Key且模型端点设为本机关闭端口，无BYOK请求；未在高峰使用用户Key |

工具链是本机已有Amazon Corretto 21.0.11、Maven3.9.4、Windows11。数据库使用本机已有PostgreSQL和只读账号。本轮没有启动/重建/停止用户原数据库，也未启动此前已停止的隔离新库。

## 来源、失败和边界

证据目录为`reports/clean-cache-20261117/`。protocol.json记录原Git对象、空缓存起点、源ZIP、runner和settings指纹；source.zip保留冻结Git导出，dependency-witness.json逐项记录268个源文件、532个制品和79个内嵌运行库来源。manifest.json保护原始日志、两轮XML、HTTP响应和Gold报告字节。临时源码/缓存/可执行包保留在协议标记的独立Temp目录，供18:00使用；没有把整个缓存或带个人配置的工作目录纳入Git。

首轮使用Windows PowerShell5.1，`ErrorActionPreference=Stop`把Mockito写入stderr的常规警告包装为NativeCommandError，导致runner提前结束；不是依赖缺失或710项测试失败。核查对应Maven Java进程已不存在后才继续，未因观察超时重复启动。initial-runner-failure.json保留未知退出码，initial-surefire保存首轮710/710；resume_offline.ps1只对原生命令暂用Continue并检查LASTEXITCODE，再恢复Stop。同缓存离线运行的日志和XML分开保存，没有覆盖首轮失败。

源码、POM、业务配置、原200题和标准SQL均未改动，新的Java测试数仍为710，不将两次执行相加成1420项。构建来自Git导出，副本无.git，内部Gold报告可能显示Git身份不可用；其来源由外层协议的提交/对象/源ZIP逐文件指纹证明，不能编造内部Git字段。

这是依赖下载可用与后续本地离线构建的证据，不保证镜像永久可用、供应链绝对安全或跨JDK生成逐字节相同的JAR。SHA-1符合本轮Maven严格校验；额外SHA-256用于内容身份追踪，不代表取得供应商签名。全新OS上Java/Maven/Docker安装、原始CSV获取与全链路真实问数仍需分别验证。

## 可验收路径

已有证据可离线核对，无需模型Key：

```powershell
python reports/clean-cache-20261117/audit_evidence.py
python reports/demo-http-20-20261117/audit_protocol.py
```

重新进行一次空缓存实验时，新建RunId和证据目录，不删除原缓存伪造首次运行。沿原协议执行：从指定Git提交导出pom/src/data/scripts到独立目录→确认无application-local.yml→指定全新空repository→用本报告settings同时覆盖-s和-gs→启用-C严格校验→clean verify→保存原生退出码、所有日志、XML与JAR哈希。不要照原始run_cold_build.ps1在PowerShell5.1里保留错误的stderr终止处理；关键调用应按已验证恢复脚本的处理方式：

```powershell
# 在新的导出源码目录中，$coldRepo必须是已经确认为空的全新目录。
# $coldSettings使用本报告公开settings.xml；命令必须先替换成该次实验的绝对路径。
$ErrorActionPreference = 'Continue'
mvn -B -ntp -C -s $coldSettings -gs $coldSettings "-Dmaven.repo.local=$coldRepo" clean verify
$coldExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($coldExit -ne 0) { throw "构建失败，保留现场：$coldExit" }
```

随后对该次新包按probe_runtime.py/run_gold_selfcheck.py中的相同契约验证，保存独立目录，不能覆盖本轮。审计本轮证据不需要重新下载依赖或重复执行测试。

## 技术与设计导读

**数据流例子**：无Key输入“2018年有多少笔订单？”→新构建JAR的AskController接收请求→编排发现全局模型未配置→返回HTTP503/NOT_CONFIGURED。健康接口同时报告Schema37且ready=false，不把“进程启动”当作“模型可用”。Gold自检是另一条显式dry-run路径：原Gold→既有校验/只读SQL执行/结果规范化，不经过模型生成。

**核心组件与身份追踪**：Java21/Maven3.9、Spring Boot可执行JAR、Git archive、独立maven.repo.local、公开settings和严格校验；build protocol中的sourceObjects/源ZIP绑定输入，制品SHA绑定依赖，包SHA绑定运行产物，PID/端口绑定自己的进程。旧工作目录的target、个人local配置和旧Maven缓存没有混入。无排序、权重、Top-K或检索来源改动，directHits/expandedTables逻辑保持原样。

**直接收益**：将“原机器有缓存才能编译”的假设变成可审查的空缓存起点、532个依赖制品、离线710项回归及8项实际运行/200项Gold自检证据；本轮供应商成本0。完整新主机验收仍未取得，不能靠数字多掩盖范围不足。

**替代方案与面试追问**：没有在用户原目录删target和全局Maven仓库，因为会破坏现有工作且不能证明私有配置未混入；使用一次性源导出和独立缓存，代价是约86MB缓存及一次下载。问“空缓存是否就是新机器？”答：不是，JDK/Maven/OS/数据库仍沿用本机，只证明依赖与源码构建这层。问“为什么健康200还不能问数？”答：健康区分Schema/进程状态与全局模型配置；ready=false、providerVerified=false明确保留依赖边界。

**用户配合**：无需重新提供Key或修改配置。需能说明构建输入如何绑定、为何200/200不是生成准确率、为何高峰期间这次工作模型调用为0。18:00前不启动真实问数；完整20条、独立盲测、完整语义契约、新主机及远程部署仍按各自证据验收。
