# 阶段2 p15固定候选回归协议

本协议在首次付费调用前提交。业务实现为d44b822，提示版本固定为p15-entity-coverage-v15；启动提交还包含本协议和运行入口的版本凭据适配，没有追加业务修改。沿用原阶段2协议的题库、评分、配置、91%历史基线与89%最低均值门槛，原84/86/89失分与报告永久保留。本轮不宣称独立盲测或阶段3延迟优化完成。

## 候选与证据范围

候选累计包含已提交的配送时钟、具名计数、客户记录/自然人默认、评价粒度，以及p15实体身份/事实覆盖和准时率分母修复。p15本批最终Java报告859/859（完整运行一次，失败2项修后仅补跑受影响测试）、Gold200/200、外部模型0调用。它们只是离线证据，不预设真实准确率改善。已知评价订单/评价行参考冲突、Top10边界并列、销售额澄清及复杂问题超时均继续按原评分计错；不会生成重复计数SQL追分。

## 固定执行

1. 原data/eval题库仍200题。先运行全200题Gold dry-run，模型调用0，必须200/200且运行Prompt版本符合本协议；预检失败停止，不发送真实请求。
2. 只运行原eval分片100题，按r1、r2、r3顺序各一次，共300请求。不得看中间结果修改代码、删题、改Gold/评分/输出、延长超时或择优重跑。任一运行失败留档并停止。
3. deepseek-flash，temperature=0，maxTokens=8192，provider-default思考，显式四步指令开启；检索TopK=8、列剪枝、join hints/plan、semantic、fewshot=2、澄清、生产自纠错与结果检查开启；eval侧纠错关闭；cache/routing/tool/conversation关闭。
4. 保持请求预算15000ms、最多2次模型调用、重试最少剩余2000ms、SDK单次尝试。启动参数复用run_eval.ps1的Preview；Gold与真实轮次都复核运行版本，防止执行过期classes。
5. 当前Git提交、协议、全部生产/测试源码、原题库、旧基线与运行脚本、私有配置、classes/test-classes/依赖JAR在运行开始时冻结SHA，每轮前后复核。本轮开始前只允许将干净受保护文本的CRLF/LF转换成Git checkout约定，必须先确认除换行外逐字节等价；这不改变代码逻辑，记录转换清单。冻结之后禁止修改。
6. 入口与每次真正发送请求都执行现有DeepSeek空闲门禁，并保留120秒余量。官方定价页于2026-11-17北京时间23:36核对：工作日09—12、14—18为高峰，其余时段允许；本次在夜间启动。Flash空闲百万Token价格为命中0.02元、未命中1元、输出4元。Key仅环境继承，不打印、不写报告。

## 验收与报告

三轮均值至少89.00%，相对固定历史默认91.00%退步不超过2pp；100题分母包含澄清、超时和所有不匹配。旧目标严格修复至少2/3仍沿用已冻结证据，不按本轮择优拼接。比较是历史多因素比较，不能将变化归因于单条新规则。运行审计按报告meta、Gold及问题SHA、调用账目、用量与时段核对；本次版本从预先提交的协议与manifest读取，其他门槛不变。

报告三轮准确率、SQL成功率、逐层结果、失败频次、P50/P95、Token/费用、未知调用和纠错恢复/退化。未知费用不能按0计，只有下界；估价不是供应商账单。P95<8秒仍是暂缓的阶段3目标，不能用本轮准确率过关替代它。

证据目录固定为reports/stage2-p15-regression-20261117，禁止覆盖旧目录。本轮只做这一组正式回归，不追加逐项付费专项。启动命令：

```powershell
./scripts/run_stage2_regression.ps1 -OutputDir reports/stage2-p15-regression-20261117 -PromptVersion p15-entity-coverage-v15 -ProtocolPath docs/STAGE2_P15_REGRESSION_PROTOCOL_20261117.md
python scripts/summarize_stage2_regression.py reports/stage2-p15-regression-20261117 --live-inputs
```

官方时段与价格来源：[DeepSeek模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)。
