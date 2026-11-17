# 阶段2：客户身份与存储行粒度契约

## 当前交付与验收路径

生产规则和离线验证已完成，真实模型验证尚待冻结后执行。Java729/729、70套件，失败/错误/跳过均0；新增19项，其中身份上下文12项、真实PostgreSQL反例7项。Gold只读自检200/200。原200题前后对比：表、列、分数、扩展来源、DDL、示例、枚举提示和系统正文全部不变；25题新增实体身份口径，每题增加548或550个Java字符，其余175题最终提示指纹不变。这不是Token测量或准确率提升。

上一轮20条的T4-001错误SQL按orders.customer_id分组，订单数都为1；参考答案按customers.customer_unique_id，最高17。该失败和80%历史成绩不覆盖。本次修复不改题、Gold、评分器、检索词典、排序参数或预算，也不按题ID决定生产行为。生产只按业务别名及当前实际表/列注入契约。

真实定点计划固定4条：原T4-001；有订单顾客的自然人去重数；明确按orders.customer_id统计的订单频数分布；未按人分组的全站订单数。只执行一次，保留所有失败，不用定点成绩宣称独立盲测或阶段2整体通过。阶段2仍欠输出形状/比例单位，以及原计划三轮整体准确率不退步门槛；阶段3P95继续暂缓。

## 技术与设计导读

**机制与数据流。** 输入“每个客户的订单数量是多少？”→原Schema检索及列剪枝→实际上下文含customers.customer_id与customers.customer_unique_id→MetricRegistry.appendPolicies追加带身份的ENTITY_POLICY customer_identity_v1→PromptTemplate将口径交给模型→模型选择orders.customer_id连接customers.customer_id，再按customer_unique_id聚合→原AST、EXPLAIN及只读查询返回每个真实客户的订单数。两条不同customer_id对应同一人、各一单时，应输出该人2单，不能输出两个1单。

**组件与来源。** 新EntityPolicy与原指标、维度政策分开建模，不把“自然人”伪装成某句查询的指标。metrics.yml仍是同一份启动快照，MetricRegistry.definitionSha256把本次新增定义纳入既有缓存键和观测身份；加载新定义需要重启。FullSchemaProvider、HybridSchemaProvider正常检索和回退路径共用appendPolicies；semantic关闭时不注入。政策要求别名命中且同一实际表内两个身份列都存在，缺表/缺列不注入，不重新补回被裁掉的字段。

**数学和边界。** 本次不参与Top-K评分，不给customers加分，不将expandedTables升级成直接命中，也不新增桥接表；关系衰减0.4、桥接最多2表均不改，200题来源/顺序/分数一致是验收证据。每人的订单数是该人关联的订单主键集合大小，跨customer_id合并、跨明细行去重；人数是非NULL身份集合大小。NULL身份不合并成一个真人，明确要求时可单列未知组；未按人分组的订单数和金额不因未知身份丢失。明确按customer_id统计时服从用户要求。要求零订单客户时从客户侧保留，COUNT订单主键而非COUNT(*)；金额不能用SUM(DISTINCT 金额)消除放大，否则同额交易会被误删。

**反例证据。** 7个只读VALUES CTE场景覆盖一人多关联键、一对多明细、NULL身份、孤儿订单、全NULL、零订单、空客户表。每个场景独立核对人级、行级、含零订单人级三种结果，Java按身份映射和订单集合构建oracle，不按数据库结果倒推答案。21组SQL比较属于7项参数化测试，不能虚增为21项测试；它们证明口径及SQL参考结构，不证明模型一定遵守。

**收益与限制。** 消除了普通客户统计缺少明确实体定义的输入缺口，保留了用户显式覆盖和缺列兜底。离线模型调用0、Token0、费用0；尚未证明真实生成准确率改善。英文customers字段说明与示例原先已有身份解释，但实测仍生成错键，因此这次使用有范围的业务契约，不声称AST安全层能够证明业务语义正确。

**替代方案。** 没有全局替换customer_id为customer_unique_id：前者仍是订单连接键，替换会直接破坏关联。没有事后改结果或评分器，也没有为T4-001写死SQL。代价是软约束仍依赖模型；真实定点失败必须记录，不能通过增加同题硬编码掩盖。

**面试追问。** “为什么一个客户有两个ID？”——分别代表订单关联行与跨订单业务身份，连接键、分组键职责不同。“有了契约为何不能承诺全部答对？”——它是生成输入约束，数据库测试验证定义，真实模型和三轮回归验证遵守情况，安全AST只验证可执行边界。

## 执行记录与复核

暂停前测试未留下完整退出记录。恢复后Docker未启动，首次本地测试694项中14项跳过，前后上下文和Gold连接失败；这些记录全部保留，不算通过。停止本次Gold进程后启动Docker，发现sailor-ingest.sock为不可访问的遗留运行端点；停止Docker后将LocalAppData/Docker/run整体改名为run.customer-identity-20261117-backup，保留原目录，让Docker自行重建运行目录。未重置安装、未删除卷、未重建容器；原text2sql-postgres容器ID仍为46d25808e1a4，恢复后healthy。随后数据库在线重跑才得到729/729和Gold200/200。

离线复核：`python reports/customer-identity-contract-20261117/audit_evidence.py`。原始证据保存在同名报告目录，实际类和依赖身份由真实验证protocol另行冻结。用户无需重新提供Key；真实验证继续遵守[空闲门禁](OFFPEAK_EVALUATION_20261117.md)及[官方时段规则](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)，不绕过门禁。

请核对三点：为什么customer_id用于连接而customer_unique_id用于识别人；为什么明细行数不能等于订单数；本轮离线通过为什么不能直接写成准确率提高。
