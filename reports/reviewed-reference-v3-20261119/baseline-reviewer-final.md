# 341bb1e 独立补充复核

**原modelOverride漏洞已修复，但仍不能无条件声明所有远程发送受保护。** 新增发现：本机base URL配绝对远程completionsPath，可把实际请求送往通用远程代理，而工厂仍按本机端点豁免。固定官方DeepSeek地址与相对补全路径的实验配置不受该旁路影响。

审查SHA：`341bb1eb0db0f5af61d28141afb1672f31653008`；原源提交：`1e3a11f92e70ef76230fe0e5d36f0a9f7f2e894f`。初审 `7cc2efe` 报告和假传输漏洞证据完整保留。复核者未参与编写或生产调参；providerCalls=0、未读Key、未改源、未提交Git。

## 已解决的部分

工厂装配现在对所有非本机**配置base URL**设置forceOffPeak，不再看默认model。远程端点的模型覆盖、升级与BYOK因此不再绕过原缺口。官方实际请求URI即使force=false仍被自动识别。

新增假传输测试覆盖generic代理13:55创建、14:00发送：执行前抛OFF_PEAK_REQUIRED，fake execute为0。复核者另用已有编译产物与Java Proxy假传输独立复现此结果。发现的生产调用均经同一同步RestClient工厂，无新增流式通道。规则保守覆盖其他远程厂商只属于此比较副本的安全策略，不代表所有厂商有同一官方计费时段。

## 仍存在的实际URI旁路

示例配置：`baseUrl=http://127.0.0.1:1234`，`completionsPath=https://generic-remote-proxy.invalid/v1/chat/completions`。AgentProperties不限制后者为相对路径。已核对安装的Spring AI 1.1.8字节码：只验证路径非空，随后把路径直接传RestClient.uri。Spring Web 6.2.19实际URI解析结果为该远程地址，完全替换本机base。

此时工厂force=false，而HTTP层仅自动识别官方DeepSeek域名，通用远程URI不命中。固定高峰下，注入假传输execute被调用1次；无网络与用户Key。随后官方URI仍被拒绝，本机假服务仍可用。证据见 `baseline-reviewer-final-probe.json`。

最小修复是直接在 `BudgetHttpRequestFactory.createRequest` 根据**实际URI是否本机**决定保守门禁，保留execute前重检；不要再以配置base URL代替发送目的地。也可在构建前拒绝绝对/带authority补全路径，但必须覆盖所有接受该配置的入口。需要补本机base+远程绝对path的高峰创建及排队跨界测试。

## 范围和测试证据

最终相对原基线仍只有5个生产类、1个测试、1份说明变化。Prompt、检索、指标/词典、原Gold与holdout、结果比较/评分、预算参数、模型temperature/maxTokens/reasoning及依赖未发现变动，JSON包含逐项Git对象及最终diff SHA-256。没有非门禁推理语义变更，但不声称历史模型输出、延迟、成本完全相同。

读取现有targeted报告，DeepSeek32、生成模式23、预算9，共64/64，零失败/错误/跳过。未重跑Maven或完整套件；原490/490是根agent报告的既有完整验证，不被包装成复核者在341bb1e上重跑全套。新代理测试手工传force=true，未覆盖上述URI组合，因此64通过不能证明该组合安全。

## 已知限制保留

HTTP拒绝仍会被包装为CALL_FAILED；在HTTP前预留的本地调用预算可能记为unknown usage，并触发现有重试。**本地账目次数不等于供应商已发送次数或实际计费Token。** 跨入高峰而被截断的批次必须标记中断，不可作为模型准确率对照。该补丁没有修改旧预算或失败处理；这项限制不应被掩盖，也不应为了评测好看而改账。

本次结论：固定官方DeepSeek/base URL/default模型/相对path范围可视为安全准入回补；通用“全部远程实际发送”结论仍受阻。根agent已收到实际URI缺口，后续修订须单列补验。
