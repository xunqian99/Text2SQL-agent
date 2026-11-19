# A阶段公开输入与慢请求证据清单

本目录只整理公开资料和本机只读重建；供应商新调用0、费用0。生产提示词、TopK=8、关系衰减0.4与桥接上限2均未改。

免费capture由主任务统一编译/执行：运行生产类直接解自冻结p21 JAR，类路径为`target/replan-stage-a-frozen/BOOT-INF/classes`及其79个依赖；未用当前工作树重新编译的`target/classes`。探针单独编译到本目录classes。JAR来源SHA256为`e8a3db8efba97e675432b995018b23b016088604cc446513c2d84f85703fe764`；实际类/资源与依赖指纹见`execution-provenance.json`，完整版本与数据库身份由主任务清单固定。

## 输入覆盖与来源

字符单位统一为Java UTF-16 code unit，不把字符数冒充Token。完整系统/用户文本及组件在capture JSON，去重片段全文在`input-fragments.jsonl`；`request-input-inventory.csv/jsonl`逐题连接题面来源、字符数、表列来源及历史首答。

|公开语料|题数|总字符|平均|P95|系统占比|历史首答指纹比较|
|---|---:|---:|---:|---:|---:|---|
|original200|200|1657683|8288.42|10101|64.27%|{}|
|v3dev100|100|843989|8439.89|10179|63.12%|{"HASH_EQUAL": 99, "NO_FIRST_CALL_TRACE": 1}|
|v3formal100|100|843860|8438.60|10071|63.13%|{"HASH_EQUAL": 291, "NO_FIRST_CALL_TRACE": 9}|

原200的新重建与已有period-ranking公开capture逐题核对：200/200提示词指纹与全部组件长度相同。这是离线捕获复用核验，不是新增200次模型请求。系统片段与口径的独立索引见`system-rule-inventory.csv`和`definition-inventory.csv`，均有源文件/行号、字符数和全文片段指纹。

系统规则→`PromptTemplate.systemPrompt`；框架与使用原则→`userPrompt`；DDL→当前只读数据库目录和`SchemaDdlRenderer`；指标/维度/实体口径→`metrics.yml`及各render方法；示例→`examples.yml`与`ExampleSelector`；枚举→`glossary.yml`与`ValueRetriever`。数据画像来自数据库读取器。每个源文件及输入证据的SHA256见`source-manifest.json`。

检索身份未扁平化：捕获保留`retrieval.evidence/scores/expandedTables/fellBack`、剪枝后的实际表列和provider补表。`directHitsInPublishedEvidence`仅由已发布证据减扩展集合推导，生产方法内局部`directHits`未直接导出，不伪称抓到了原局部变量。指标另存`metricMatchEvidence`的MATCHED/缺上下文/排除原因，以及实际注入定义身份；依赖表补入和实体/维度列可用性仍可追溯。

不改变原选择机制：关系扩展分数取源分×0.4×边权，多路径取最大而不累加；直接命中不被扩展覆盖；同分才按直接命中优先、表名字典序裁决。它不能保证所有直接命中永不被更高分扩展挤出，桥接最多补2表也可能仍不连通。完全无命中回退全目录，未裁剪的全量路径不会强补示例。此处只留证，不调参数。

历史日志未保留原始prompt正文（prompt=null）。HASH_EQUAL表示重建消息与已记录的启动消息指纹一致，已返回调用再核字符数。V3共有390条首答启动记录匹配；另10条标为NO_FIRST_CALL_TRACE，只说明没有首答启动轨迹，不能据此断言没有HTTP发送。逐条sendEvidence仅取CONFIRMED_BY_PROVIDER_RESPONSE（有供应商返回用量）或UNKNOWN（证据不足）；哈希相同本身不证明HTTP发送。p9只保留历史记录的哈希、字符与Token，不借用p21的组件数。原始capture保持字节不变，其中captureScope的sent措辞不得越过本段发送证据边界。

## 已有首答、纠错与慢请求

7个公开批次合计700请求；父目录与continued内重复的DEV_C/B1先核对字节后去重。仅DEV_C和正式B1/B2/B3/C1/C2/C3进入清单，未读blind/holdout/未见题或逐题结果。慢请求预定义为wallLatencyMs≥8000或REQUEST_TIMEOUT，用于定位；不是验收门槛。

|批次|正确|P95 ms|超时|首答/纠错启动|未知用量调用|慢请求|慢请求主要记录阶段|
|---|---:|---:|---:|---:|---:|---:|---|
|DEV_C|88/100|15004|9|99/3|9|18|{"MODEL": 18}|
|B1|84/100|13498|5|100/1|5|19|{"MODEL": 19}|
|C1|86/100|15007|8|97/1|7|19|{"MODEL": 19}|
|C2|86/100|15000|6|97/1|6|17|{"MODEL": 17}|
|B2|82/100|14034|5|100/1|5|15|{"MODEL": 15}|
|B3|85/100|13394|4|100/0|4|13|{"MODEL": 13}|
|C3|84/100|15006|9|97/1|8|17|{"MODEL": 17}|

慢请求共118条，其中7条有第二次调用启动记录：4条获供应商返回确认，3条发送证据与用量未知；不得统称为已发送纠错。118条的记录MODEL等待至少占预算经过时间90%。这能说明观察到的等待落在哪一段，不能证明文本增长是唯一原因，也不能从266组约+82%的整批证据推出任一单规则因果。

`request-segments.jsonl`保留原始分段与productionAttempts；CSV便于筛选；`slow-request-attribution.csv`逐条给出首答、纠错、MODEL聚合等待、SQL校验、结果检查、EXPLAIN、执行与源JSON指针；`model-call-ledger.jsonl`按requestId+callNumber匹配原日志。

p21的400条请求有`request-budget-exclusive-v1`：MODEL+GENERATION+REWRITE投影到generation，VALIDATION+RESULT_CHECK投影到validation，EXPLAIN+EXECUTION投影到execution，四段+otherMs=total，已逐条核验。MODEL阶段是本地调用等待，含准备/网络/供应商推理及输出接收，不能继续拆出排队、首Token或解码。校验与DB阶段只提供请求累计数，不能精确拆成首答/纠错各自的校验/DB耗时。

p9的300条没有独占计时版本和otherMs，不强行套p21公式。特别是超时响应timings多段为0时，budget.stageMs仍记录MODEL等待；这些0不解释为模型未耗时。缺失stage字段、未返回调用latency/Token/费用全部记null，不回填0。没有纠错启动轨迹标为NO_CORRECTION_CALL_TRACE，不能提升为没有发送的证明，也不伪装为测得0ms。

历史记录启动调用698次，其中654次有返回用量并匹配逐调用日志，44次用量未知。startModelCall在transport前预留，因此未知/IN_FLIGHT记录不独立证明HTTP已发送或供应商已计费。已知费用小计¥1.60784042只是下界，不能据此宣称完整成本达标。

## 可复核入口与缺证

执行`python reports/replan-stage-a-20261119/input-inventory/build_inventory.py`只重整此目录输出，不调用模型。capture的Java入口只允许原200和两个公开V3目录，拒绝其他目录和覆盖旧capture；执行时未构造模型客户端。历史`audit_prompt_composition.py --evidence-only`仅用于核验20261116的旧组件档案，不能跨版本断言p21系统长度仍为2795。

缺证：历史原始消息正文、p9分组件正文、未知调用的供应商Token/完整费用、网络/供应商内部耗时、首答与纠错各自的校验/DB精细分段。当前记录不支持单条规则的准确率/延迟因果；本阶段未取得新业务准确率或性能改善，只固定证据。
