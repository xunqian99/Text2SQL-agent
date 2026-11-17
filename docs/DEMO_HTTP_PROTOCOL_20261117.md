# 2026-11-17完整20条HTTP复验：已冻结，待空闲运行

**晚间执行结果**：18:01原工具保存Decision.checkedAt时因缺少JavaTimeModule失败，第一条问数尚未发送，旧requests为空、模型0次，全部现场保留。新目录reports/demo-http-20-20261117-v2原字节复制同一协议，仅增加时间序列化伴随启动类，d396701先冻结后实际问数一次；接口19/20、严格匹配16/20、会话/健康通过，22调用/完整估价¥0.03923214，P95 11.030秒。详见[完整结果](DEMO_HTTP_RESULTS_20261117.md)。下列旧启动命令为历史准备记录，不得再运行来覆盖或重复付费。

阶段3 P95继续暂缓。本轮是阶段4功能复验，沿用[原20条协议](DEMO_HTTP_PROTOCOL_20261116.md)的选择、模型策略、15秒总预算、最多2次调用、会话隔离与结果评分，不重选题，不增加题库，不以提高分数为理由改Gold。

## 已完成的离线准备

- 门禁实现提交：`8c4e669`；原始诊断字节属性：`9d6285a`。
- 20条协议冻结提交：`7697cd8`；协议sourceCommit为`9d6285a`，src/pom/题库/启动脚本受保护对象全部记录。
- 路径：`reports/demo-http-20-20261117/protocol.json`、`protocol.sha256`。
- 只读数据库执行20条Gold成功，未截断；与历史20条的ID、顺序、会话ID、问题、SQL、列、行值和截断标志20/20相同，原题库SHA相同。重新执行产生的elapsedMs不是答案内容，不参与评分；未修改冻结文件来消除耗时差异。
- 本轮冻结协议增加空闲时段政策版本、官方来源、Asia/Shanghai、120秒边界余量和节假日保守策略。
- 后续补充artifact-plan.json绑定[空缓存构建](COLD_BUILD_ACCEPTANCE_20261117.md)取得的可执行JAR SHA、启动器SHA与原protocol SHA；启动器只预览验证，未启动付费问数。复用原local,demo配置及其冻结SHA，通过额外配置目录加载，不把个人配置写入JAR。原协议/题目/Gold/评分器未修改。
- 2026-11-17北京时间14:03后，使用实际时钟运行起跑预检，目标仅为127.0.0.1:1，工具在健康接口前抛出PEAK_HOURS，非零退出。没有requests目录、没有健康/问数HTTP、供应商调用0。保存peak-preflight.json/log，不能把它当完整20条失败或成功。

完整Java710/710是门禁代码回归数字，不是这20条的新准确率。历史17/20=85%、HTTP P95 10.083秒、未知用量1及费用下界保持原样。新的真实20条尚未执行，不提前填写结果。

## 运行与验收路径

先核对[官方规则与本地约束](OFFPEAK_EVALUATION_20261117.md)。北京时间18:00后，以新编译进程运行；不得用旧进程或关闭门禁。不改变当前冻结的受保护代码、题库或个人local配置。下面命令中的健康检查不调用模型，Key只通过现有环境注入，不放进参数或日志。

```powershell
python reports/offpeak-policy-20261117/audit_evidence.py
python reports/demo-http-20-20261117/audit_protocol.py
python reports/demo-caller-binding-20261117/audit_binding.py
# 仅在空闲时段，独立终端启动：
powershell -NoProfile -ExecutionPolicy Bypass -File reports/demo-http-20-20261117/start_cold_jar.ps1
# 另开终端，以同一classpath运行一次：
python reports/demo-caller-binding-20261117/invoke_probe.py run
# 停止该独立应用并等日志关闭后：
python reports/demo-caller-binding-20261117/invoke_probe.py summarize
```

每条调用方时间判定保存于window-checks，应用再次约束其中的生成/改写/纠错。若中途拒绝或产生未知用量，保留全部记录，禁止清空requests重新跑择优；仍以原20条分母报告未完成状态。未发送的高峰起跑预检未创建requests，可在18:00以同一冻结协议进行首次真实运行。

**调用工具补充绑定**：原说明从工作目录target取调用方类路径，仅服务端绑定冷构建包，存在旧编译产物混入风险。现在由reports/demo-caller-binding-20261117/invoke_probe.py核对冷构建的211个生产条目、98个测试类/资源、114个依赖JAR后调用原DemoHttpAcceptanceProbe，移除类路径空项且不使用工作目录target。原协议/Gold/评分器/启动器未变。wrapper仅向本机固定18087发HTTP，不继承模型Key或Spring/JVM覆盖。真实时钟15:20预检在健康HTTP前因PEAK_HOURS拒绝，正式requests仍不存在、供应商0次。初次隐藏Java子进程未转发stdout/stderr产生空诊断日志，原wrapper/绑定/日志单列保留；明确转发后取得拒绝证据，没有付费重跑。该补充只绑定测量工具，不改变模型请求或答案判定。

启动器在创建应用进程前核对JAR/自身/协议/个人配置SHA和保护源码身份，缺失或不符即停止，不自行换包或重建替代；API Key只通过子进程环境继承。预览命令加-Preview，不读取Key、不启动应用。首轮预览发现PowerShell5.1默认ANSI读取UTF-8中文协议失败，已改为明确-Encoding UTF8，旧启动器/计划与原因保留于initial-*和launcher-preview-adjustment.json；不属于模型失败或付费重跑。之后预览通过，后续实际demo健康检查仍由20条工具起跑门禁核对。

只在本机demo入口做此轮功能复验；不能宣称protected完整20条验收。认证、费用配额组合证据另见既有报告。本轮新增时间门禁不更改原结果规范化或业务SQL判断，影响范围是付费调用的准入及本轮时间证据。

下一步交付必须包括：完整20条业务/接口分别计数；全部请求HTTP墙钟平均与nearest-rank P95（含失败）；模型逐次费用/Token/未知；会话累计轮数、交错追问隔离、关闭与删除；完整日志、失败保留、凭据扫描与Git提交。不能把定点3/3、澄清替身8/8替代完整结果。
