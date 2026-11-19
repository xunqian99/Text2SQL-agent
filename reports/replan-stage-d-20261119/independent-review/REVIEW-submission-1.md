# D 独立审查：首包拒绝付费准入

审查固定 plan SHA256：`fc13d6c9ec0eddb5909c32d93dd69fde3a4ce32632b758fd32674c90025813ce`。未实现、调参或出题；未读未见/盲题/holdout/旧 U_B/U_C 逐题，未访问真实密钥或供应商端点。新增供应商调用、Token、费用均为 0。

## 阻断项

1. **本批危险放行没有审查就被写成 0。** `run_replan_stage_d.py:674` 为每个真实轮次无条件填写 `newDangerousAllowances=0`，开发/正式门禁还通过 `get(...,0)` 把缺字段当作 0。独立合成反例没有任何本批安全证据，开发门禁仍通过并推进到 TARGETS；正式门禁的 dangerous 也为 true。C/D 免费边界的 0 新增只能描述已测边界，不能成为真实付费输出的安全判定。当前未见仍锁止，这点成立，但不修复正式通过结论缺证的问题。证据：[safety-progression-counterexample.json](safety-progression-counterexample.json)。
2. **上游原始响应留证不符合冻结协议。** 本机替身发送含重复 usage 键的原始 body，代理原字节转发给客户端，费用正确保持未知、保留 ¥0.10 并标记终止；但持久化 result 仅有 hash、长度和 `responseJson=null`。因此无法从实现证据恢复或复查原始 body。独立审查另存的 fixture 原字节不等于实现已留证。证据：[duplicate-upstream-retention-counterexample.json](duplicate-upstream-retention-counterexample.json)。
3. **最终组合回归未通过。** 同一固定源码运行控制器与代理全部现有测试：57 项，56 通过，1 error。旧的“bad local requests”测试把未完整分帧的 `Transfer-Encoding: chunked` 请求期望为立即 400；加入合法 chunked 后，该夹具等待超时。没有修改测试或删除失败。此前不同版本的 20+13 不能替代最终组合通过。证据：[supplied-suite.log](supplied-suite.log)。

## 成立的免费证据

- 356 份绑定文件全部哈希一致；32 份 Git 保存原字节一致，324 份 runtime 文件一致；两份冻结 JAR 指纹一致；37 份 preflight 证据一致。初始付费 state/ledger 均未开始。
- 两侧真实 JAR 纠错验证通过：公开 T1-002 首答返回安全但虚构列 SQL，触发生产纠错；第二次返回正确 SQL，最终 HTTP200/SUCCESS、结果行匹配冻结参考、runtime modelCalls=2。每侧一个真实 `/api/ask`、两次本机模型请求，代理无拒绝/越界/错误；第二次 payload 的精确纠错后缀与门禁相容。
- 已读冻结原始评分器及桥：评分阶段没有候选 SQL 执行入口；参考/题目/策略/列类型绑定，T3 原严格判据独立登记，T4/T6 原题各一次；完整分母、缺题/重题/错序与解析故障停止路径有现有测试覆盖。
- 已读共同账本与时段实现：首答/纠错分别有 permit，第三次或 SDK 重复首答拒绝；未知费用持有全预留，重启核 permit/completion 原内容；父请求单调 deadline 与 120 秒加剩余连接时间的时段预留同时生效。以上不把 DNS/TCP 尝试冒充供应商收到，也不把字节估价说成 Token 硬上界。
- 中断保留、目录/状态一致性校验、禁止重放、C2 不准入和未见锁止均存在。正式通过仍只会抛出需独立未见保管者的锁止信息。

## 请求数据流与设计边界

本次具体例子：T1-002 通过真实本机 HTTP 进入原 p9 或 C1；第一次模型替身返回虚构字段，Java 校验发现错误；同题第二次携带 SQL/反馈，转发器认出纠错后缀、登记 attempt=2、预留后转发；正确 SQL 由真实只读库执行，原 HTTP body 与解析结果分别留证。此证据说明两次调用身份和真实纠错链路接通，不说明真实模型准确率。

数据身份由 plan SHA、round/questionId/attempt/token、payload SHA、permit/completion 与 HTTP 证据保持；成本身份由 usageKnown 和未知预留保持。没有新增排序权重或删题机制，100 题失败仍在分母。真正的缺口是“未知安全状态”被默认值 0 挤掉，因此在没有独立证据时也能晋级；这不能靠 scope 备注补救。

收益数字是审查证据：356 文件、37 预检证据、2 条真实纠错路径、57 项组合测试、2 个实质反例；准确率、延迟收益与完整成本均未验。替代做法“沿用 C 免费安全 0”无法证明新输出；“只留摘要”无法复查重复键原始响应。面试追问应答：安全测试与批次安全结论必须分别绑定来源；不可重编码 parsed JSON 冒充原始响应字节，否则重复键、顺序与数值原文已经丢失。

当前判决为 `accepted=false`，供应商 0，不允许付费。后续修订必须保留首包 plan/判决/失败并绑定新的完整身份；本审查没有给予后续版本预授权。用户无需额外操作。
