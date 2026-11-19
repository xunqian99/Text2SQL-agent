# B提交3需求—证据索引

此索引不代替独立审查。候选源码提交`54d7a93`，JAR SHA256 `7ed0b6a449ec18a9f4a1f9349b3a33ada96cd9515e99dc876d3b3c962f32f668`；随后`326060d`仅补交已被清单记录但被Git忽略的3份原始日志，未改候选。

| B要求 | 当前可核实证据 | 结论边界 |
|---|---|---|
| 生成仍用检索/剪枝子集，执行用完整授权目录 | `CatalogValidationBoundaryTest`；`catalog-http-evidence/summary.json`中的真实遗漏表/裁剪列正例 | 独立目录局部变量不替换schema；召回分母保持1张，完整业务目录37张 |
| 四类模型入口统一检查，早于预留/计数/实际发送 | `LlmSqlGenerator.invoke`及`CatalogSendBoundaryTest`146项 | 内存ChatModel实际边界计数，真实供应商调用0；不是外部HTTP传输证据 |
| 目录常量故障：无额外发送/DB/EXPLAIN | 146项包含4入口×5故障×6配置；`catalog-http-evidence/summary.json`恒定故障mockCalls0 | 费用配额开启要求请求预算配置开启，保留该现有限制；另测配置预算开启但无当前RequestBudget上下文 |
| 首轮后故障：保留首次账目，禁止后续轮或收口 | 真实生成器的生成失败、纠错、改写、未知/损坏工具16项组合 | 失效由实际model.call驱动；不靠目录读取次数驱动；未知费用保留为未知 |
| 目录正常可修复错误仍可继续 | 发送边界中的工具健康对照，原自纠错与AgentLoop回归 | 没有通过禁掉工具/纠错掩盖故障 |
| 最终SQL、缓存、显式/自动纠错、工具同源 | 必需的`AuthorizedSchemaCatalog`构造注入，工具及编排47项；真实Spring HTTP | 检索provider未实现fullSchema可有健康独立授权目录，不再把检索接口作为权限源 |
| 真表真列通过；虚构/内部/跨schema拒绝 | 47项目录矩阵及既有SqlValidator/Adversarial测试；HTTP未授权对象反例 | 既有裸列/CTE等能力未扩大，不代表完整静态类型系统 |
| 鉴权、费用预留、超时、会话收尾不退步 | `access-http-evidence`36检查；`cost-http-evidence`4场景；`session-http-evidence`3场景；`timings-http-evidence`4场景 | 内存模型/合成用量及真实受保护HTTP/只读DB；不代表生产准确率/P95 |
| Prompt、检索TopK/衰减/桥接不改 | `first-call-invariance.json`及6份逐条capture；检索生产源码未改 | 原200+V4开发100+正式100，共400逐字相同；沿用已冻结对照capture400 |
| 原Gold与新参考可执行 | `gold-execution.json`及3份dry-run明细 | 原200+V4两组100，400/400；仅参考SQL可执行性，不是模型正确率 |
| 批次验证及最终构建 | `full-build-final/execution.json`、100份当前XML | 最终clean构建1379/1379，无错误/失败/跳过；早期编译/fixture失败均保留，不计作最终通过 |
| 依赖、数据、既有用户工作及版本身份 | `candidate-identity.json`、`submission-3-manifest.json`、`committed-byte-verification.json` | 79个依赖不变、37表数据库指纹不变；1599个清单文件已逐字存入Git；9处既有用户差异未动 |
| 独立审查全部B边界通过后才建立B0 | `../independent-review/submission-3/` | 目前等待独立决定；根任务的绿色测试不足以自批B0 |

真实供应商调用0、Token0、费用¥0。未读封存未见题；原U_C保持取消；阶段3实验未恢复。整体目标、C/D和真实付费评测尚未完成。
