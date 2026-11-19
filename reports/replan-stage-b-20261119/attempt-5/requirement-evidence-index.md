# B提交5需求—证据索引

此索引不代替独立审查。候选源码提交`3701718`，JAR SHA256 `e90b29251a8afa2a6af9ec3586a846ab137f84ef4511a62812c31d775e525a9f`。

| B要求 | 当前可核实证据 | 结论边界 |
|---|---|---|
| 生成仍用检索/剪枝子集，执行用完整授权目录 | `CatalogValidationBoundaryTest`；`catalog-http-evidence/summary.json`中的真实遗漏表/裁剪列正例 | 独立目录局部变量不替换schema；召回分母保持1张，完整业务目录37张 |
| 四类模型入口统一检查，早于预留/计数/实际发送 | `LlmSqlGenerator.invoke`及`CatalogSendBoundaryTest`246项 | 内存ChatModel实际边界计数，真实供应商调用0；不是外部HTTP传输证据 |
| 目录常量故障：无额外发送/DB/EXPLAIN | 246项包含4入口×5故障×6配置；`catalog-http-evidence/summary.json`恒定故障mockCalls0 | 费用配额开启要求请求预算配置开启，保留该现有限制；另测配置预算开启但无当前RequestBudget上下文 |
| 首轮后故障：保留首次账目，禁止后续轮或收口 | 真实生成器的生成失败、纠错、改写、未知/损坏工具16项组合 | 失效由实际model.call驱动；不靠目录读取次数驱动；未知费用保留为未知 |
| 目录正常可修复错误仍可继续 | 发送边界中的工具健康对照，原自纠错与AgentLoop回归 | 没有通过禁掉工具/纠错掩盖故障 |
| 空choices/空消息仍有usage；null/未知用量 | 新增80项真实模型响应矩阵，四入口×四形状×五配置 | 内容校验先后顺序不决定计费：收到已知metadata先入账，再判内容；unknown仍unknown并保留预留，不能按0或退费 |
| 工具先前轮次+当前失败轮次用量 | 20项首轮/后续轮/收口×空/截断×预算开关；AgentLoop唯一异常出口 | 合并一次，不丢也不重复；提交3/4的阻断原文保留 |
| 最终SQL、缓存、显式/自动纠错、工具同源 | 必需的`AuthorizedSchemaCatalog`构造注入，工具及编排47项；真实Spring HTTP | 检索provider未实现fullSchema可有健康独立授权目录，不再把检索接口作为权限源 |
| 真表真列通过；虚构/内部/跨schema拒绝 | 47项目录矩阵及既有SqlValidator/Adversarial测试；HTTP未授权对象反例 | 既有裸列/CTE等能力未扩大，不代表完整静态类型系统 |
| 鉴权、费用预留、超时、会话收尾不退步 | `access-http-evidence`36检查；`cost-http-evidence`4场景；`session-http-evidence`3场景；`timings-http-evidence`4场景 | 内存模型/合成用量及真实受保护HTTP/只读DB；不代表生产准确率/P95 |
| Prompt、检索TopK/衰减/桥接不改 | `first-call-invariance.json`及6份逐条capture；检索生产源码未改 | 原200+V4开发100+正式100，共400逐字相同；沿用已冻结对照capture400 |
| 原Gold与新参考可执行 | `gold-execution.json`及3份dry-run明细 | 原200+V4两组100，400/400；仅参考SQL可执行性，不是模型正确率 |
| 批次验证及最终构建 | `full-build-final/execution.json`、100份当前XML | 最终clean构建1479/1479，无错误/失败/跳过；早期编译/fixture失败均保留，不计作最终通过 |
| 依赖、数据、既有用户工作及版本身份 | `candidate-identity.json`、`submission-3-manifest.json`、`committed-byte-verification.json` | 79个依赖不变、37表数据库指纹不变；525个清单文件已逐字存入Git；9处既有用户差异未动 |
| 独立审查全部B边界通过后才建立B0 | `../independent-review/submission-5/` | 目前等待独立决定；根任务的绿色测试不足以自批B0 |

真实供应商调用0、Token0、费用¥0。未读封存未见题；原U_C保持取消；阶段3实验未恢复。整体目标、C/D和真实付费评测尚未完成。
