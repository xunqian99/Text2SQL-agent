# 基线安全回补独立初审

**7cc2efe不能无条件通过安全准入。** 旧Prompt、检索、指标、Gold、评分与预算未发现改动，但远程代理的实际模型覆盖路径可绕过发送门禁。固定官方DeepSeek/default模型路径有保护，不能将这个有限结论扩成所有DeepSeek路径都安全。

复核者 `/root/review_joins` 未参与回补编写或生产调参。源基线为 `1e3a11f92e70ef76230fe0e5d36f0a9f7f2e894f`，审查固定于 `7cc2efe1df34754836ec4690c604edf1dec9cff2`。开始审查时为未提交改动，期间其他操作者完成提交，本报告只审查该提交，不涵盖后续变化。providerCalls=0；未读用户Key、未调用模型、未改源或提交Git。

## 阻断缺口：默认模型识别不等于本次请求模型识别

`LlmSqlGenerator.buildChatModel` 第488行只按配置默认model计算 `forceOffPeak`。四条调用路径随后都允许 `modelOverride`，升级路由也会传入覆盖模型。远程代理若默认 `gpt-test`、实际覆盖成 `deepseek-flash`，工厂的强制标记仍是false；HTTP层仅调用 `appliesTo(uri,null)`，非官方host无法从URI识别，create和execute两处检查都被跳过。

本地探针使用**注入Java Proxy假传输**，固定北京时间高峰，无网络、无Key。实际得到：默认模型识别false，覆盖模型识别true，时间准入false，但未强制工厂仍触发假execute一次。官方域名及强制代理均抛 `OFF_PEAK_REQUIRED`，假execute次数不再增长。探针加载已有编译产物，原始输出保留；源码路径分析是结论依据，不伪称重新编译或完整集成实测。

应在实际请求维度传递模型身份，或仅在隔离比较副本中保守限制所有非本机模型端点。后者属于本地实验安全规则，不能声称OpenAI等厂商也采用同一官方峰谷要求。修订后补验：远程默认非DeepSeek+实际DeepSeek覆盖、高峰创建拒绝、空闲创建后跨界发送拒绝、BYOK与升级/工具路径、localhost假服务可用。

## 已知非阻断限制：未发送不等于已发生用量

HTTP门禁抛出的 `Rejected` 在生成器四处普通catch中被包装为 `CALL_FAILED`；`invoke` 在HTTP之前已调用 `startModelCall`。因此未发送也可能增加modelCalls/unknownUsageCalls并触发现有自纠错重试。保护仍能阻止再次发送，但这不能被解释成实际计费调用，更不是模型质量错误。

入口拒绝能阻断高峰开始整批评测，长批次从空闲跨入高峰仍会在HTTP层拒绝并出现上述账目。此类批次应标为中断/不可比，不得把后半批本地拒绝当模型失败去发布准确率。保留旧预算与失败处理语义是当前边界，不能暗改预算来美化指标。

## 范围与正向检查

该提交改7文件：5个生产类、1个测试、1份安全说明。HTTP生成器唯一生产出站客户端使用RestClient和同一请求工厂，全局Key与BYOK共用装配；当前生产方法使用同步 `model.call`，未发现stream旁路。官方URI与默认DeepSeek远程代理有创建及实际执行前双检查，SDK maxAttempts仍为1。

时间逻辑固定Asia/Shanghai，工作日09–12及14–18拒绝，预留max(120秒,timeout+10秒)，节假日不做未经验证的放行。它是本地准入规则，不能保证进程暂停、供应商排队或最终计费时点。本审查没有核验最新官方价格，代码中的来源链接不替代该核验。

Prompt、retrieval、schema指标/词典、原eval/holdout、比较器/校验、RequestBudget、配置、依赖及模型选项逐项以Git对象比对；详见JSON哈希证据。EvalRunner新增部分仅为循环前准入检查，未动评分聚合。未发现非门禁的推理语义变动；这仍不能证明与历史模型输出完全相同。

现有测试源码共31个参数展开用例，覆盖边界、时区、识别、排队跨界、假服务和非法guard，但缺少上述默认非DeepSeek+覆盖DeepSeek组合。审查未运行Maven或模型评测，只做最小假传输探针。

原始证据：`baseline-reviewer-probe.json`。详细结论、定位、保护项对象哈希、审查diff SHA-256：`baseline-reviewer-initial.json`。根agent已获告知阻断缺口并将另修版本；后续须补充审查，不能覆盖这份初审来制造首次就通过的记录。
