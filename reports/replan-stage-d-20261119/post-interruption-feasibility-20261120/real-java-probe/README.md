# 冻结 C1 的真实 Java、本机迟到账务验证

结论只覆盖一个本机模拟请求：**15 秒 HTTP 答案截止与同一次调用的限时账务收集可以分离**。最终 `check-4/result.json` 的16项检查通过；HTTP 为504/REQUEST_TIMEOUT，外部15104.216ms，内部预算15001ms，模型调用1次；同一响应的完整模拟usage在HTTP返回后收到。候选原hold仍为UNKNOWN，已知调用/费用仍为0，未退款。供应商请求、真实Key读取均0。

这不是生产D接受，也不是业务正确率、P95或真实费用验收。未接生产monitor/共同账本，未验证TLS、官方高峰边界、资源耗尽或105秒收集期；只验证15秒答案与额外1秒本机读取。此目录不修改冻结源码、JAR、真实配额或768份实际中断原件。

## 技术与设计导读

1. **具体机制与数据流。** 输入“查询2018年的订单数”，真实冻结C1 JAR先检索（79ms），进入模型等待（14902ms）。代理只向127.0.0.1替身发送一次，发送前保存真实Java新建模拟账本的唯一PENDING及permit。在父请求15秒截止时，Java返回REQUEST_TIMEOUT、sql为空；替身到父截止后0.4秒才返回预定模拟SQL和10输入/5输出的完整usage。代理在原截止加1秒前收集原body，拒绝把迟到SQL交给客户端。之后读取候选账本，原hold身份不变、状态UNKNOWN，未被费用收集旁路核销。
2. **核心组件与来源身份。** 使用原C1 JAR（SHA `51874452…c2c2`）、`RequestBudgetRunner`原取消机制、`CostQuota.Lease`原PENDING→UNKNOWN行为，以及root的只接本机`LoopbackAccountingProbe`。`quota-before-send.json`保存真实PENDING，`synthetic-permit.json`固定token与实际payload SHA；最终hold的requestId等于原HTTP budget.requestId，callNumber=1，promptSha保持原值。promptSHA与payload SHA属于不同字节域，不能当成同一个哈希。模拟账本的budgetId/pricingIdentity与真实批次完全独立；原UNKNOWN没有迁移、释放或重命名。
3. **效果与数字。** 最终16项检查通过，1 POST/1 callback；原HTTP快照仍显示1次未知用量。迟到完整body只成为模拟传输证据，候选仍保守占用¥0.10；完整模拟usage的估价若按既定单价计算为¥0.00003，这不是实际供应商费用。原768份实际文件逐份SHA复核未变。数字证明局部链路可行，不证明未知用量必然能收齐；供应商若继续沉默或body不完整，仍必须UNKNOWN。
4. **设计权衡与答辩。** 直接延长请求预算会改变延迟和候选答题条件，故保留原15秒；用迟到SQL替换超时答案会污染测量，故只收账务。继续保留候选UNKNOWN会比共同完整usage更保守，代价是候选额度仍可能先耗尽；不能把两层占用相加成供应商账单。面试追问“迟到用量是否等于题目成功”：否，答案已冻结，准确率/超时继续按原响应；“为什么还有未知占用”：候选没有接收完整响应，现有UNKNOWN不变量禁止自动核销，账务旁证不提供退款权限。生产接入还必须证明原fence绑定、跨题回调、无截止后发送、完整usage验证、并发/字节上限和高峰前排空，不能从本例外推。
5. **用户配合。** 无需操作，无需提供Key。此实验无付费授权效力，不能续跑两次中断批次或启动第三次开发轮。用户应核对三件事：原答案何时冻结、模拟usage属于哪个原调用、候选UNKNOWN为何仍占用。

## 原件与版本

- `probe.py` 是独立启动器，环境只按名称白名单读取Windows运行必需字段；真实Key相关变量从不读取，Java只得到固定模拟模型Key和模拟访问token。命令显式指向本机模型代理/只读PG，关闭持久化、缓存、路由、工具、会话及自动模型组件。
- `check-1` 在健康检查前因探针函数名遮蔽http模块失败；`check-2` 在健康就绪前因模拟访问token不足32字符失败。两者未发模型POST，失败文件/日志保留。
- `check-3` 是首次真实链路成功（15105.012ms）；它运行时的依赖为 `d831ad69…51d1e`，结果按当时字节记录，但未另存该整模块源码原件，不将后来的新源码归到旧结果。
- `check-4` 仅为消除源码证据缺口再次运行完全相同请求/参数，没有评分或挑题。启动前归档`launcher-source.py.txt`和`imported-loopback-probe-source.py.txt`原字节，前后核一致；启动器SHA `bca2ff7d…c2a3b`，依赖SHA `acb76e4f…e01b`。最终结果只引用这份完整身份凭据。
- 原始HTTP body、模拟供应商body、permit、callback、启动前/PENDING/HTTP返回时/drain后配额、命令和日志均保留。模拟成功2个问数请求/2 POST；启动失败2次/0 POST。真实供应商合计0。

最终验证没有重跑真实评测集、没有读取旧68答案或任何未见题，也没有修改原正式完整用量门槛。
