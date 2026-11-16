# 阶段3：显式推理指令单因素实验入口

实验入口已实现并通过离线验收，**尚未进行该变体的真实模型筛选**。生产默认仍为原`p9-mdl-alignment-v9`，不因测试通过就推广候选。此次修改代码和评测启动配置，没有改题目、Gold或评分。

## 1. 具体请求与核心机制

输入“订单有多少？”仍经澄清、Schema检索、示例/指标组装进入`LlmSqlGenerator`。`PromptTemplate`读取`agent.prompt.explicit-reasoning-enabled`：默认`true`发送原始系统消息2795个UTF-16代码单元；仅在显式`false`时移除从“思考与推理步骤”到“硬性规则”之前的259单位，保留介绍和全部18条规则，变为2536单位。用户消息中的表、列、Join提示、示例、指标和问题不变。

首答与纠错使用同一个模板Bean。工具循环由`AgentLoop`用同一模板组装系统消息；通用`callMessages/callWithTools`接口仍透传调用方提供的消息，不擅自改写历史。测试覆盖调用方按两种模板构造消息后，经四入口、全局Key/BYOK到实际本地HTTP请求的内容。

供应商`reasoning_effort`是另一个参数：本实验两组都保持`provider-default`，不发送该参数。删除显式文字并不等于关闭模型思考。模拟服务返回`SELECT 1`只验证传输与记账，不是业务准确率证据；真实SQL仍须经过现有AST/表列/代价校验和只读执行。

## 2. 身份、缓存与失败边界

| 身份 | 默认 | 候选 |
|---|---|---|
| 配置`explicitReasoningEnabled` | true | false |
| Prompt版本 | p9-mdl-alignment-v9 | p9-mdl-alignment-v9-no-explicit-steps-v1 |
| 系统消息长度 | 2795 | 2536 |
| 供应商模式配置 | provider-default | provider-default |

`PromptTemplate.versionFor()`统一供评测元数据、调用日志的`configuredPromptVersion`与缓存签名使用；`EvalRunner.effectiveConfig`另记开关。实际消息身份仍靠包含角色与有序内容的`promptSha256`，请求与调用靠原`requestId/callNumber`追踪。通用消息接口的日志字段特意叫configured：调用方可能传入其他系统消息，配置版本不能代替实际指纹证明。

缓存签名包含所选Prompt版本，避免候选直接复用默认SQL而制造“没调用模型也一样准”的假象；本轮后续筛选仍应关闭应用缓存。此处没有引入打分、排序或Top-K变化，检索中的来源标记和衰减设计不受影响。

默认系统消息SHA-256保持`f50464555aceac53dc657613a5887c1a72128ab18ef66a3d41c5413fb2419f0e`。Java布尔绑定拒绝非法值，候选找不到文字区段则抛错，不静默删整段规则或退回默认。新旧Prompt正文发生变化后必须更新版本与契约，不能只改版本名继续引用旧指纹。默认无参构造保留给既有测试，Spring实际注入绑定后的`AgentProperties`，两种配置均已验证。

## 3. 本次验收数字与复核命令

- Java全量 **484/484**，失败、错误、跳过均0；较此前459增加25项。
- 新增25项：16个实际本地HTTP组合、5个模板/默认字节/配置绑定契约、2个评测报告契约、1个缓存隔离契约、1个工具循环调用方契约。
- Python既有证据审计 **62/62**，兼容历史报告；[提示词组成审计](PROMPT_COMPOSITION_20261116.md)仍通过。
- 两组启动参数Preview核对：仅`explicit-reasoning-enabled=true/false`不同，供应商模式和8192输出上限等相同。
- 新增供应商模型调用 **0次**、新增模型费用 **0元**；系统文本减少259/2795=**9.27%**，但仅约整个dev输入文本的**5.17%**。没有新准确率、真实延迟或Token节省数字。

```powershell
mvn -q test
python -m unittest discover -s scripts -p 'test_*.py'
python scripts/audit_prompt_composition.py
./scripts/run_eval.ps1 -Preview -Split dev -SelfCorrection -ResultChecker
./scripts/run_eval.ps1 -Preview -Split dev -SelfCorrection -ResultChecker -DisableExplicitReasoning
```

`-Preview`不调用模型。`-DisableExplicitReasoning`只设置本次进程参数，不永久修改本地配置；省略时脚本显式设为true，防止私有配置悄悄改变对照。

原始测试日志、测试套件逐项数量和源码指纹见[实施验证记录](../reports/explicit-reasoning-implementation-20261116/verification.json)及同目录归档。测试数是工程契约数，不能写成新增484道评测题。

## 4. 真实验收尚缺什么

下一步先冻结dev单因素运行协议和专用审计：原200题Gold自检200/200且无模型调用；同dev100以默认→候选各一轮，共200次真实请求。两组只允许显式指令开关、相应版本与系统消息指纹不同，用户消息与检索结果须另有可核验的配对证据。当前旧“生成模式”审计要求两组整个Prompt相同，**不能直接拿它验收本次Prompt变体，也不能放宽旧规则让历史结果混过关**。

筛选门槛沿用既定要求：正确数相对同期下降≤2/100、P95至少降低10%、新增截断≤1/100、候选完整估价≤0.30元且未知用量0、每请求≤2调用且墙钟≤16秒工程界限、取消提交拒绝0。澄清/超时/拒绝均留在100题分母，报告全部分层和对错转换。筛选有利才冻结正式eval三轮；正式正确率仍须≥90%且相对同期退步≤2pp、平均P95<8秒，费用与完整证据要求不降。不能在已经看过的eval上改规则追分。

协议、配对证据和专用审计尚未交付，因此本次没有启动真实筛选；阶段3仍未通过，整体目标继续。

## 5. 技术与设计导读及面试追问

**技术抓手**：配置由`AgentProperties.Prompt`承载，模板只裁掉固定区间；版本选择集中在`PromptTemplate`，评测、JSONL与缓存复用；启动脚本显式传参。没有新增依赖，未更改模型客户端、模型预算、纠错策略、SQL安全和评分逻辑。

**收益与权衡**：解决了“要试删指令就得全局手改Prompt、历史结果无法分辨”的实验污染风险。代价是多维护一个候选配置和版本；它只提供可追踪的实验条件，不证明候选值得使用。直接覆盖p9或同时改成none会混入两个因素；同时砍业务规则会把速度变化与语义损失混在一起，均未采用。

**追问一：删除四步文字为什么可能仍然很慢？** 模型自身可以继续推理，输入文字占比也不等于生成延迟占比；还必须用同期真实调用的P95、准确率与完整费用验证。

**追问二：配置和日志都标成候选就能证明真发了候选吗？** 不能。配置是意图，实际HTTP正文和Prompt指纹才是传输证据；测试只证明指定路径，真实筛选还需逐题配对审计，不能只看日志标签。

用户无需重配Key或手改服务。需要掌握的数据流是“检索上下文→选模板→模型→只读执行”，成本仍来自每次实际模型调用，语义错误仍要区分表/列/Join/口径；模板开关不自动修复这些错误。
