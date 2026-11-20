# D 付费范围拒绝后的本地锁

用户已明确“维持原限额，不再新增付费评测”。旧控制器保存着一份已经消费的 DEV100 批准；只检查那份旧文件会把后来的拒绝决定漏掉。现在 `run_replan_stage_d_reregistered.py` 的 Key 入口先读取 `human-scope-decision.json`，只有直接用户重新给出 `APPROVED_NEW_SCOPE` 且 `newPaidAuthorizationGranted=true` 才继续检查旧授权。当前记录是 `DECLINED_NO_ADDITIONAL_PAID_EVALUATION`，因此在读取环境变量/注册表 Key 之前拒绝。

旧 plan 的 `scripts/run_replan_stage_d_reregistered.py` 字节绑定故意没有被改写。新增锁使旧 plan 的源码指纹失配，旧付费入口即使绕过范围函数也会在计划验证阶段失败；未来若用户重新开放范围，必须重新登记 plan、重新做独立工程审查，不能把新授权套在旧 plan 上。

验证脚本位于 `reports/replan-stage-d-20261119/free-paid-lock-audit-20261120/test_scope_lock.py`，最终结果为 `result.json`：

- 当前拒绝决定被精确读取，候选配置下一次预留为 **¥1.093767460**，冻结上限 **¥1.00**。
- `guarded_real_key()` 返回 `Refused`；替身凭据读取器调用次数为 **0**。
- 供应商调用、真实 Key 读取、批次写入和中断重放均为 **0**。
- 76 份已答结果、7 份候选 UNKNOWN、旧 plan 与实际批次没有被修改。

## 技术与设计导读

1. **请求与数据流**：任何未来 `run-next` 先经过范围决定，再经过旧授权、plan 指纹、独立审查、窗口和费用门禁，最后才有机会读取 Key。当前第一步就停止；没有请求体、permit、HTTP 或模型调用产生。
2. **核心实现**：`SCOPE_DECISION` 绑定直接用户决定；`require_human_authorization()` 是唯一旧付费授权入口；`guarded_real_key()` 在读取凭据前调用它。当前费用身份仍由 `knownCostYuan + reservation × holds` 计算，7 个 UNKNOWN 不能被清零。
3. **效果**：免费测试约 **0.54 秒**完成；凭据路径0次、供应商0次。旧入口的误启动风险被收紧到“拒绝记录或 plan 指纹不匹配即停”。这不是业务正确率、P95或成本达标数据。
4. **权衡与面试追问**：为什么不删除旧授权文件？因为它是历史审计证据，删除会破坏不可变记录；为什么不直接把额度改成0？因为那会混淆配置账与供应商账，且破坏旧 UNKNOWN 身份。面试追问“用户改口后怎么办”时，回答是重新生成新 plan 和独立审查，不复用旧 plan。
5. **用户配合**：当前无需提供 Key 或启动服务。只有用户未来明确重新开放付费范围，才需要另行审核预算、题数、时段和新 plan。

这次修改是免费安全收口；没有触碰 DeepSeek API，也没有重新跑评测集。旧 D 工程接受只适用于原源码和原 plan；当前锁属于新的免费防误启动变更，不能被当作新的 D 业务接受。
