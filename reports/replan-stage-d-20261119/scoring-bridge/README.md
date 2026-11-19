# D 免费评分桥：只读参考与真实响应分离

本目录只交付评测侧桥接，不宣布 D 付费准入、阶段2正确率、P95或费用通过。未参与候选调参与出题；没有读取封存未见 Gold、U_B/U_C 逐题输出，没有供应商请求。

## 接口

在仓库根目录编译，输出类目录必须放在运行 classpath 最前面；必须同时显式编译 A 的原源码，不能用旧 JAR 自带的默认评分替代：

```powershell
$bridgeCp = 'target/classes;target/test-classes;' + (Get-Content target/replan-b-classpath.txt -Raw).Trim()
javac -encoding UTF-8 -cp $bridgeCp -d reports/replan-stage-d-20261119/scoring-bridge/classes reports/replan-stage-a-20261119/ReferenceContractScorer.java scripts/replan/StageDScoringBridge.java
$bridgeCp = 'reports/replan-stage-d-20261119/scoring-bridge/classes;' + $bridgeCp
java -cp $bridgeCp StageDScoringBridge prepare dev NEW-dev.json
java -cp $bridgeCp StageDScoringBridge prepare formal NEW-formal.json
java -cp $bridgeCp StageDScoringBridge prepare targets NEW-targets.json
java -cp $bridgeCp StageDScoringBridge score NEW-dev.json SAVED-responses.json NEW-score.json
```

所有输出使用 `CREATE_NEW`，已存在即拒绝；未知/重复/缺失/乱序 ID 或无效 JSON 模式使整批拒绝，不输出一个换过分母的成绩。原始 prepared/response 文件字节不改写。HTTP 状态使用整数；桥可以把合成夹具中的`0`计错，但当前新控制器遇真实网络超时或坏JSON会中断整轮，不能通过补造`0`或body继续运行。普通业务非200仍在完整分母内计错。

`prepare` 只接受 `dev`、`formal`、`targets` 三个枚举值；没有任意题库路径。开发、正式各100题，顺序与冻结 YAML 加载顺序一致；targets 只有 T4-001、T6-003，两题依次来自 A 的原目标登记。T3-002 不新增请求。

准备输出包含 `datasetKey`、`datasetSha256`、`items`、`providerCalls:0`；每项含 `id/difficulty/question/questionSha256/goldSqlSha256/gold/policy/expectedTableCount`。`gold` 为 `{columns,rows,truncated,executionMs}`，值保持字符串或真 null；`expectedTableCount` 是 Gold AST 中去重物理表数量，不是候选检索召回数。

评分输入为 `{items:[{id,httpStatus,body:{status,columns,rows,truncated,...}}]}`。评分只读取当时保存的 HTTP 字段，body 的 SQL 即使正确也不能覆盖错误 rows；本命令不连接数据库。HTTP200、SUCCESS、明确非截断后才调用冻结比较器。HTTP/状态失败和截断为错误；缺失的成功响应结构、非字符串/真null单元格等模式错误拒绝整批。结果形状和值错误为错误。

评分输出含 `items:[{id,correct,reason}]`、`total/correct/byLayer`、输入两文件的原字节 SHA256，`byLayer` 的键沿用 `difficulty`。完整无序多重集、时间升序、部分序、严格 TopN 均调用 A 冻结 `ReferenceContractScorer.equivalent`，只用参考侧 `columnKinds`。候选提供任何类型提示都不改变比较。

targets 调用 `originalTargetEquivalent`，保留原无序完整多重集与边界成员严格一致，禁止 SQL-aware tie 比较。dev 的 T3-002 另输出 `originalTargetCorrect/originalTargetReason/originalTargetQuestionSha256/originalTargetGoldSqlSha256`；先核开发题面和Gold哈希与 A 原登记完全一致。控制器合并该条与两个 targets，按原严格至少2/3判断，不能把开发 V4 的 `correct` 直接当作原目标得分。

## 边界与数字

首次编译成功。首次只读 Gold 准备为开发100、正式100、原目标2，共202/202；V4逐列核输出列名、JDBC类型及PostgreSQL类型与 A 冻结描述一致，不重新推导或修改参考类型。连接只允许本机 `olist` 的 `text2sql_ro`，核只读事务和非高权限角色；事务为 REPEATABLE READ，8秒查询期限，完整参考行数上限100,000，超过即拒绝整个准备。所有 Gold 先通过现有 AST 安全检查，再执行冻结原 SQL；仅缺 LIMIT 由 JDBC完整行数上限代管，不改Gold。

最终桥检查44/44，冻结评分器31/31。包含全分母双方回放、T3复用身份、非200/失败/截断、TopN边界伪造、顺序、重复/未知/缺失/额外题、类型提示、NULL、错误 rows 附正确 SQL、输入不变、已有文件不覆盖。完整清单与结果见 `verification-encoding-fix/verification.json`。这些是合成 HTTP 夹具，不是实际模型响应，不是100%模型准确率。供应商调用、Token、费用、实际 HTTP 请求均为0。

首次验证脚本在4项桥基线及31项冻结反例通过后，读取含中文的原目标登记时使用了Windows默认GBK，因编码中止。只修脚本为显式UTF-8；首轮目录与 `attempt-status.json` 全部保留，修正后的完整批次另存目录。没有更改生产或冻结评分规则。

桥仅核冻结数据/策略和本次准备结果的结构；Gold数据身份、HTTP响应实际来源、候选/对照JAR与运行环境指纹、调用次数、费用完整性、时段与阶段门禁必须由新控制器冻结并执行。prepared/response文件SHA必须在该控制器中登记，不能接受事后替换参考结果。

## 技术与设计导读

以 T3-002“2017年和2018年各有多少笔订单”为例：准备时从公开开发题取原题与SQL，核 A 的双哈希，执行只读 Gold，保存两列年度/计数及冻结参考类型。控制器真正调用 `/api/ask` 后保存 HTTP 状态和 body；桥逐位对齐100个ID，再核 body 中年度和计数。它同时给 V4 正确性与原目标严格正确性，不重新执行 body.sql。相同计数但转置成一行、漏一年、截断均不能借另一条SQL补救。

核心组件是 Java JDBC、现有 `SqlValidator`/`EvalItemLoader`/`TableRecall`、Jackson严格JSON读取，以及字节不变的 A 共同评分器。身份凭据用 `datasetKey + datasetSha256 + id + questionSha256 + goldSqlSha256 + policy` 连接，Gold与actual保持分离；评分没有加权和TopK截断，分母必须完整，任何题都不能因失败被排除。V4文本身份不走数值转换；原目标刻意沿用旧严格规则，不偷偷套V4类型或并列宽容。

拒绝“重新执行候选SQL再评分”，因为它会抹掉当时HTTP返回中的执行失败、形状错误与截断。拒绝“由候选列类型决定规范化”，因为编号`001`可能被改成`1`而误判通过。面试追问一：何以证明评的是当时返回？答：桥无HTTP/候选SQL执行入口，评分输入是保存响应原字节，其SHA写入输出，控制器还须锁定来源。追问二：桥通过是否等于模型达标？答：44项/31项只证明已验评分与边界，真实正确率/P95/完整费用需D后续受限批次。

本包无需用户配合外部服务；后续操作者必须核对数据从HTTP保存到评分的路径、供应商实际调用与未知费用，以及错误位于题目契约、参考评分还是生成/执行层。只读数据库当前已可用；数据库变化会使列元数据或后续控制器数据库身份检查失败。
