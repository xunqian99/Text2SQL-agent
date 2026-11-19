# 实际URI门禁最终补验

**3c0bda0通过本次安全回补范围复核。** 前两轮发现的modelOverride与绝对远程completionsPath旁路均已关闭，未发现当前DeepSeek评测路径仍可绕过的发送。

审查提交为 `3c0bda099c08183ff3e3b4607bd0a071158fc630`，历史源为 `1e3a11f92e70ef76230fe0e5d36f0a9f7f2e894f`。复核者未参与回补编写或生产调参；providerCalls=0，未读用户Key、未改源、未提交Git。此前两轮报告及假传输证据全部保留，没有覆盖原失败结论。

本次改变只在HTTP创建处按**实际URI是否本机**确定门禁，执行前再次查时间。它不再依赖默认model、modelOverride或配置base URL；本机base加绝对远程path也受到保护。非本机一律保守限制是此隔离比较副本的实验规则，不宣称其他供应商遵循同一计费时段。

独立假传输补验结果：

| 情况 | 结果 |
|---|---|
| force=false，本机base解析为绝对通用远程URI，高峰创建 | 在delegate创建前拒绝，假发送0 |
| force=false，通用代理13:55创建、14:00发送 | 执行前拒绝，假发送0 |
| force=false，官方DeepSeek13:55创建、14:00发送 | 执行前拒绝，假发送0 |
| 本机127.0.0.1替身，高峰 | 可执行，假发送1 |
| 通用远程URI18:00空闲 | 可执行，假发送累计2 |

探针仅加载已有编译产物、注入Java Proxy假传输，无真实HTTP。打包JAR中的HTTP工厂、其Request内部类、时间策略和LlmSqlGenerator四个class与探针所用target/classes逐字节哈希相同。原始输出与包指纹分别保存在 `baseline-reviewer-actual-uri-probe.json` 和 `baseline-reviewer-actual-uri-package.json`。

原13项保护路径的Git对象与历史源全部相同：Prompt、retrieval、semantic/schema指标词典、原eval/holdout、execution/validation、RequestBudget与Runner、配置和pom。相对历史源仍只改5个生产类、1个测试、1份安全说明；EvalRunner仅加循环前准入，模型参数、预算、比较器和评分未改。安全回补可作为新对照源版本，但不能声称模型输出、缓存、延迟或成本与历史完全相同。

现有针对性测试报告确认为33门禁+23生成模式+9预算，共65/65，失败、错误、跳过均0。复核者没有重跑Maven或完整套件，也没有把原490/490说成这个提交的新完整运行。XML文件路径及哈希保存在 `baseline-reviewer-actual-uri-tests.json`。

既有CALL_FAILED/unknown-usage限制保留：拒绝发生在实际HTTP之前，但本地调用账本可能已预留并触发现有重试。**本地调用次数和未知用量不等于供应商实际发送次数或计费Token。** 被门禁截断的批次应标记中断，不可发布为模型准确率对照。此限制不阻止发送安全结论，但必须在后续评测归因中保留。

完整审查diff SHA-256、逐项保护对象、前两轮证据哈希和最终结论见 `baseline-reviewer-actual-uri.json`。本结论不替代供应商最新价格核验，也不保证本地允许后供应商的最终排队/计费时点。
