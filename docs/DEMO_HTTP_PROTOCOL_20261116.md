# 阶段4连续20条HTTP验收协议

本轮只验证完整本机demo配置的连续运行、可观测性和会话隔离，不开展阶段3 P95优化，不改原题/Gold/生产规则。它不是独立盲测，也不把20条结果外推为稳定泛化准确率。

## 冻结输入与比较

固定选择原200题中T1到T6各层ID最小的3条，共18条，不按历史答对情况选题。先发T1-002（2018订单数，orders会话）与T6-001（2018含运费GMV及不含运费销售额，gmv会话），再依次对两个会话发完全相同的“那2017年的呢？”，之后发剩余16个原题，各用新会话。

这2个衍生追问仅用于演示场景，其Gold是各自原SQL的2018开始/2019结束边界平移到2017开始/2018结束，原题库保持200题，原dev/eval不变。所有20条Gold在付费调用前以只读账号执行，保存列和结果；任一Gold失败/截断则不开始真实问数。

`freeze`记录协议原始SHA-256、原题库文件指纹、源代码提交及受保护Git树、个人local配置SHA-256（不存配置正文）、20条输入/会话ID/Gold结果。先提交实现，再冻结并提交协议，最后才运行。每次请求前检查源码树、数据集、local配置与协议指纹不变；输出目录有`requests`即拒绝重放，不覆盖历史。

比较沿用Java `ResultNormalizer`：数字四位小数、完整日期规范化；原Gold的ORDER BY+LIMIT决定顺序语义，并使用既有并列处理。列别名不打分，列数/列位置和值参与比较。SUCCESS且非截断并与Gold结果等价才算业务正确；澄清、拒绝、超时、失败、传输异常均在20分母中。SQL字符串相同不作为正确条件。

## HTTP与会话验收

启动独立demo进程，仅监听127.0.0.1；固定15秒请求预算、最多2次模型调用；模型deepseek-flash，使用原provider-default思考策略。完整demo能力与前轮一致，查询缓存/持久化/工具循环/升级路由关闭。起跑前会话列表必须为空。

每条保存完整HTTP状态与JSON、调用方墙钟、响应后健康和全部会话列表。接口SUCCESS与业务正确分别计数，全部20条墙钟排序；平均为总和/20，P95采用nearest-rank，`sorted[ceil(0.95*20)-1]`，包括失败耗时。它包含HTTP序列化等开销，与旧Java内部评测的P95口径不同，不互作性能提升对照。

会话隔离必须同时核对：返回sessionId与请求一致；交错的相同追问分别得到订单/GMV主题2017答案、改写含2017、Schema复用；每步会话集合与各自累计轮数一致，无额外会话；两个主题会话各2轮、可关闭为CLOSED；本轮18个已知会话可删除，最终列表为空。这里只覆盖单用户下顺序交错会话，不证明并发同会话安全、多租户或用户访问授权。

无应用崩溃的操作证据为：20次请求都有记录、每次后续健康成功、末尾健康成功。HTTP超时不自动等同进程崩溃；结果错误也不自动等同串话。分别报告各项失败，不用一个总PASS掩盖不同性质的问题。

## 成本与完整性

本轮指定独立JSONL目录，利用budget.requestId＋callNumber匹配每个模型调用槽位，包含改写、首次生成、未采用的纠错及超时调用。重复日志槽位拒绝，缺失/未知usage/日志多余槽位分别计数。响应中有未知usage，即使晚到日志补齐，也保留该响应时点的不确定性，不轻易宣称完整费用。

估价单价固定为输入缓存命中¥0.00002/千Token、未命中¥0.001/千Token、输出¥0.004/千Token，与当前个人配置核对。费用公式为`hit/1000*hitPrice + miss/1000*missPrice + output/1000*outputPrice`，同时核对hit+miss=input及input+output=total。单价是本轮配置估价参数，不代表当前供应商账单价。

发现未知usage时只报告已知费用下界，缺失调用不能按0元算。不因15秒总预算和最多2次调用就宣称金额硬上限。20条最多40次模型调用，本轮只跑一次；失败不换题，不悄悄重跑出一个更高分。

## 命令与门禁

```powershell
mvn test dependency:build-classpath '-Dmdep.outputFile=target/demo-http-classpath.txt'
$demoCp = 'target/test-classes;target/classes;' + (Get-Content target/demo-http-classpath.txt -Raw).Trim()
java -cp $demoCp com.text2sql.agent.acceptance.DemoHttpAcceptanceProbe freeze reports/demo-http-20-20261116
# 提交冻结protocol.json与protocol.sha256之后再运行
powershell -File scripts/start_local.ps1 -Mode demo -Port 18087 -CallLogDir reports/demo-http-20-20261116/llm-calls
# 另开终端，使用相同classpath
java -cp $demoCp com.text2sql.agent.acceptance.DemoHttpAcceptanceProbe run reports/demo-http-20-20261116 http://127.0.0.1:18087
# 停止该独立演示进程，等待其调用日志全部关闭后
java -cp $demoCp com.text2sql.agent.acceptance.DemoHttpAcceptanceProbe summarize reports/demo-http-20-20261116
```

`DemoHttpAcceptanceProbe`放在test源目录，作为显式运行的验收工具，不随普通mvn test执行真实模型问数。8项新离线契约先验证仅接受本机HTTP端点、跨主题错答不得判对及截断/澄清不得判为业务成功。实现验证已取得Java555/555、失败/错误/跳过均0；协议冻结及真实结果另行归档，不预先写成功结论。

设计取舍：复用Java结果规范化而非另写Python近似评分，避免同一结果两种判定；保留每条原始响应及哈希，替代只报总分。当前不改模型行为，只提供可追踪的验收证据。面试需说明：相同追问怎样通过sessionId找到各自上文；每次调用费用怎样按requestId+callNumber核对；业务错误、会话串话与应用不可用怎样分别判断。
