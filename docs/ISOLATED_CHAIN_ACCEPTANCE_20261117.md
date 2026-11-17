# 阶段4：同机隔离环境完整链路补验

2026-11-17下午完成**同一套隔离环境的离线部分**：复用已验证的空缓存构建产物，新建数据库/数据卷、新建公开模板配置，实际启动新包并执行原200条Gold。真实模型问数尚未执行，必须等允许时段；阶段4仍未整体通过。原20条复验继续使用其冻结协议与原库，不把新库混入原评分。

## 已完成与未完成

| 项目 | 实测 | 证明范围 |
|---|---|---|
| 源码与产物 | Git来源9d6285a、源文件268个逐一核对、JAR SHA与空缓存报告一致 | 复用原隔离源码/独立缓存的构建结果，没有重新构建，也不复用工作目录target或个人local配置 |
| 独立配置 | 从该源码的application-local.example.yml原字节复制 | 新配置位于独立Temp目录；只含环境变量占位，不填真实Key；子进程清除AGENT_/SPRING_/JVM等覆盖 |
| 新数据库 | 新容器text2sql-clean-chain-20261117、新卷、127.0.0.1:15433 | 不复用昨天的新库，也不重建/停止原text2sql-postgres；沿用本机已有镜像 |
| 初始化 | 6/6 SQL脚本退出0，持久化脚本再执行成功 | 输入9份CSV＋6份SQL逐项SHA绑定；全部日志保留 |
| 数据与权限 | 37业务表＋4持久化表，2,616,948行 | 两账号业务表可写数均0，4/4实际UPDATE/CREATE探针被拒绝；本轮持久化表为空 |
| 新包无Key实际运行 | demo健康200、Schema37、ready=false；问数503/NOT_CONFIGURED | 监听实际为127.0.0.1:53407，模型端点固定本机关闭端口，Key显式空 |
| 数据库连接身份 | 新库中5个JDBC连接均text2sql_ro | ApplicationName=isolated-chain-20261117，与独立数据源URL一致；不是仅凭健康200猜测连接成功 |
| 启动观察 | 5,347ms | 只指本机启动观察，不是模型请求P95 |
| 全量Gold自检 | 200/200，进程退出0，整进程35,666ms | 使用同一新包、新配置、新库；原题库指纹一致，直接执行Gold，不是生成准确率 |
| 离线收尾 | Java39596/30400均不存在，HTTP端口关闭，新库停止、卷保留 | 原库ID/运行状态前后相同；只清理本轮拥有的进程与容器 |
| 证据审计 | 39份原始制品SHA通过 | runner/协议/配置/源码/JAR绑定；初次失败单列保留，未覆盖 |
| 真实模型问数 | **尚未执行**；已冻结1个问题和SQL期望54,011 | 只补环境链路，不新增评测题、不声称泛化准确率；原20条仍需独立完整运行 |
| 本轮供应商调用与费用 | 0次 / ¥0 | 无Key运行和Gold进程均屏蔽真实模型端点，不能把未来的一次调用提前算作成功 |

构建层沿用[空缓存报告](COLD_BUILD_ACCEPTANCE_20261117.md)的710/710测试与532个依赖制品证据，本轮没有重新执行Java测试，不将200条Gold叠加为新的Java测试数。操作系统、JDK、Maven、Docker与镜像仍是本机已有工具，不宣称全新OS/主机安装验收。

## 初次失败与修复边界

第一次从Python启动Windows PowerShell5.1时继承了PowerShell7的模块搜索路径，Get-FileHash无法发现；初始化在创建目录/容器/数据卷之前失败。原runner、日志、原库状态与诊断保留于reports/isolated-chain-20261117/initial-attempt/。

对照诊断：继承路径退出1，限定子进程为Windows PowerShell内置模块路径后退出0且找到Get-FileHash。新runner只给这个子进程设置PSModulePath，不改用户/系统环境，也不改生产源码或冻结的prepare_clean_demo.ps1。确认初次没有创建资源后才执行后续准备，未重复导入、覆盖失败或绕过文件指纹检查。初始化日志的原始编码保持不变。

## 可验收路径

只核对已有证据，不启动应用、数据库或模型：

```powershell
python reports/isolated-chain-20261117/audit_evidence.py
# 同时确认保留的临时源码、模板和包仍与协议一致：
python reports/isolated-chain-20261117/audit_evidence.py --live-inputs
```

真实问数前必须重新核对官方规则与当前时间，先运行门禁、新20条和本目录审计。已有18:00接续安排将先完成原20条，再执行本项的**首次1条**真实问数；两项报告独立，不能相加得出21题准确率。

```powershell
# 仅允许时段执行；先核对container ID与database-binding.json一致。
docker start text2sql-clean-chain-20261117
if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) {
    $env:AGENT_LLM_API_KEY = [Environment]::GetEnvironmentVariable('AGENT_LLM_API_KEY', 'User')
}
python reports/isolated-chain-20261117/run_chain.py paid
# 上一步若非零，先检查原始记录，不要覆盖或重新跑择优。
python reports/isolated-chain-20261117/audit_evidence.py --live-inputs --paid
```

paid命令先核对冻结runner/协议/JAR/模板/源码、新容器ID与只读SQL结果，再检查本地时间；应用发送模型HTTP时还有既有DeepSeekOffPeakPolicy复查。问题固定“2018年有多少笔订单？”，期望值54,011，预算15秒/最多2次模型调用，沿用deepseek-flash/provider-default，不做思考/Prompt/路由实验。Key只从父进程环境继承，不写参数、模板或日志。

本地门禁按周中保守规则及120秒余量判断，不能承诺供应商最终计费落点。paid-window-check、启动日志和响应均只允许创建一次，失败保留，不清空重跑。其一次性约束也意味着准备不充分时应保留拒绝证据并重新审查，而不是改文件绕过。

运行后还须核对requestId:callNumber的所有调用、Token/缓存拆分/估价与未知用量、会话1轮及关闭删除、应用和端口退出，再检查原库仍运行，只停止本轮新容器并保留卷。当前没有真实响应或调用日志，不填写新费用、准确率或完成结论。离线manifest保护原39份制品；未来付费部分另存manifest，不重写离线证据。

## 技术与设计导读

**数据如何流**：“2018年订单数”→新JAR的本机demo入口→从公开模板读取配置、从子进程环境读取独立15433数据源→检索/生成→SQL AST/EXPLAIN→只读账号查询新库→返回54,011及预算/调用身份。本轮无Key在生成处返回503；Gold自检则显式绕过模型生成并执行标准SQL，所以200/200只证明评测执行链路正常。

**组件与来源追踪**：Git archive的268文件和JAR SHA绑定代码，模板SHA绑定配置，容器ID/独立卷/端口绑定数据环境，JDBC ApplicationName在pg_stat_activity中证明实际连接来源；模型阶段继续用requestId:callNumber核账。子进程环境隔离防止个人SPRING_/AGENT_覆盖把连接悄悄指回原库。没有修改检索的来源标记、打分、Top-K或0.4衰减，不改变SQL结果比较器。

**收益与代价**：已有分开的新库/新包证明，现在推进为同一环境中的启动、健康、只读连接和200项Gold证据；供应商成本0，真实问数仍待验。代价是新增一份约2.6百万行的数据库卷，已停容器保留以供追溯。没有删除原库、清空全局Maven缓存或覆盖个人配置。

**替代方案与面试追问**：不选择“拿昨天旧库结果＋今天构建结果直接拼整体通过”，因为无法证明同一包和同一连接实际跑通。问“健康200为何还查pg_stat_activity？”答：健康定义只覆盖配置/Schema快照，databaseLiveVerified=false；带独立ApplicationName的真实连接才补足数据源身份。问“同机隔离是否等于新机器？”答：不等于；已隔离源码、缓存、配置和数据库，主机工具链/镜像获取仍未重新验证。

**用户配合**：当前无需再次提供Key或修改配置。需要掌握请求在哪个环境运行、如何查到每次模型调用成本，以及失败是环境连接、生成、SQL校验还是业务口径；单条环境问数不能替代完整20条与独立盲测。
