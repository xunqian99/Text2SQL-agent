# Windows写盘候选独立免费审查

局部重试机制成立，当前接入patch尚不接受。供应商/模型调用0；没有接入已停止控制器，没有改变paid state、ledger、原始结果或未知占款。

独立运行现有4项测试全部通过，包含真实Windows目标句柄缺少FILE_SHARE_DELETE、120ms后释放再替换成功。另7项独立检查通过：WinError5/32/33都只重复同一temp→同一dest；最大次数耗尽和其他磁盘错误停止；同步replace阻塞的边界；真实永久持有目标句柄时，重试耗尽后temp新字节和dest旧字节都保留。

明确缺口：`replan_durable_write_integration.patch`增加运行时import并替换调用，但未把`replan_durable_write.py`加入`tool_files()`。若按当前patch准备未来plan，新依赖字节将不受plan/verify绑定，冻结后改helper无法触发源码漂移停止。patch可以`git apply --check`只读通过，不代表完成运行身份闭环。因此`fullIntegrationAccepted=false`；需另立修订记录补齐新依赖身份，再独立复核。当前冻结paid版未应用此patch，不追溯改写旧证据。

边界必须如实表述：接入默认参数是0.5秒、12次；helper允许显式配置最多1秒、20次。deadline限制重试调度，不是同步`os.replace`系统调用的强制墙钟中止器：独立模拟首次replace本身用0.6秒后成功，helper会报告0.6秒。它没有网络/模型/问题重放能力；集成后的父请求期限和发送门禁仍须在before返回后有效，不能因文件等待刷新预算。

此候选证明能抵御已测短暂文件共享冲突，无法证明现场WinError5的占用进程，也无法保证所有WinError5能恢复。永久ACL拒绝、文件系统错误、次数或等待上限耗尽仍须保留temp并停止。旧批次禁止补题/重放；本地候选通过不授予新的付费授权，累计成本和UNKNOWN不能清零。

技术数据流：账本JSON只写一次同一个temp并fsync；候选只对原子替换重试，不重新序列化数据、重建permit或重走HTTP。来源由候选源/测试/patch的SHA留证；缺少未来plan中的helper SHA正是本次接入拒绝原因。没有权重、TopK或评分改变。面试应能解释“文件替换重试”与“模型请求重试”的身份边界，以及为什么错误5可能是永久权限问题而不能无限重试。

用户无需额外操作。本审查产物只供下一份免费接入修订评估，不改变中断批次的结论。
