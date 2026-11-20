# D 付费范围拒绝后的本地锁

用户已明确“维持原限额，不再新增付费评测”。旧控制器保存着一份已经消费的 DEV100 批准；只检查那份旧文件会把后来的拒绝决定漏掉。当前 revision-4 已经永久关闭：`run_replan_stage_d_reregistered.py` 的 Key 入口先验证拒绝决定、提案和来源 plan 的固定 SHA，再读取拒绝内容；验证通过后直接拒绝，绝不落到历史授权文件。当前记录是 `DECLINED_NO_ADDITIONAL_PAID_EVALUATION`，因此在读取环境变量/注册表 Key 之前拒绝。任何把 JSON 改成 `APPROVED_NEW_SCOPE`、把布尔值改成 `true`、改 proposal SHA 或改 plan 字节的尝试都会先因身份漂移拒绝。

旧 plan 的 `scripts/run_replan_stage_d_reregistered.py` 字节绑定故意没有被改写。新增锁使旧 plan 的源码指纹失配，旧付费入口即使绕过范围函数也会在计划验证阶段失败；未来若用户重新开放范围，必须重新登记 plan、重新做独立工程审查，不能把新授权套在旧 plan 上。

验证脚本位于 `reports/replan-stage-d-20261119/free-paid-lock-audit-20261120/test_scope_lock.py`，修复后的结果为 `result-fixed-4.json`；独立复核结果为 `independent-fixed-recheck.json`：

- 当前拒绝决定被精确读取，候选配置下一次预留为 **¥1.093767460**，冻结上限 **¥1.00**。
- `guarded_real_key()` 返回 `Refused`；替身凭据读取器调用次数为 **0**。
- 四类篡改探针（decision、`newPaidAuthorizationGranted`、proposal SHA、source plan SHA）均在 Key 前拒绝；凭据读取器均为 **0**。
- 供应商调用、真实 Key 读取、批次写入和中断重放均为 **0**。
- 76 份已答结果、7 份候选 UNKNOWN、旧 plan 与实际批次没有被修改。

## 技术与设计导读

1. **请求与数据流**：任何未来 `run-next` 先经过 scope decision SHA、proposal SHA、source plan SHA 和拒绝字段校验，然后立即停止；旧授权、consumed seal、窗口、permit、HTTP 和模型调用都不会被触达。
2. **核心实现**：`SCOPE_DECISION`、`SCOPE_PROPOSAL` 和当前 `BATCH/plan.json` 都有源码固定 SHA；`require_human_authorization()` 是唯一旧付费授权入口，`guarded_real_key()` 在读取凭据前调用它。当前费用身份仍由 `knownCostYuan + reservation × holds` 计算，7 个 UNKNOWN 不能被清零。
3. **效果**：修复后的免费测试约 **0.7 秒**完成；正常拒绝和四类篡改探针均为凭据读取 **0**、供应商调用 **0**、批次写入 **0**。下一次预留配置占用 **¥1.093767460**，超过冻结 **¥1.00**。这不是业务正确率、P95 或成本达标数据。
4. **权衡与面试追问**：为什么不删除旧授权文件？因为它是历史审计证据，删除会破坏不可变记录；为什么不只把额度改成0？因为那会混淆配置账与供应商账，且破坏旧 UNKNOWN 身份。面试追问“用户改口后怎么办”时，回答是新建 registration、plan、直接用户授权和独立工程审查，不复用 revision-4。
5. **用户配合**：当前无需提供 Key 或启动服务。只有用户未来明确重新开放付费范围，才需要另行审核预算、题数、时段和新 plan。

这次修改是免费安全收口；没有触碰 DeepSeek API，也没有重新跑评测集。旧 D 工程接受只适用于原源码和原 plan；当前锁属于新的免费防误启动变更，不能被当作新的 D 业务接受。
