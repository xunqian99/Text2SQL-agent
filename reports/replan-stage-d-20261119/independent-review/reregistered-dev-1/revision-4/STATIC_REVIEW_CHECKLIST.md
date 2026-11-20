# Revision-4 独立审查准备记录

本文件记录封存前的静态准备过程；最终结论见本目录 REVIEW.md 与 decision.json。准备本身不构成工程接受或付费授权。

最初通知的 helper `3137e6a538340299f3887e05c2d30a744e648e696efc9395c81c3bbf30240fb5`、driver `bda005614aa90c7f872f9451d0654a958204b2d4f5f807038d999245892d1d4e` 已因首快照解析失败分支修复而撤回固定身份，不能作为最终版签字。performance-7 的旧源码身份和严格 1000ms 目标失败必须原样保留。

静态已经阅读的新机制：

- before 把旧三次观察折成一笔事务，事务前/后捕获原始配额 A/B，逐次折叠新 known 高水位和 UNKNOWN，直到共同 permit 与 candidate 绑定及全部锚落盘才返回许可。
- after 折成一笔事务；以 permitFences 保留原问题来源，不能拿当前问题覆盖迟到完成。
- state 的逐题 +1 持久化同时建立由冻结 reference 绑定的 admission fence，begin 仅消费该 fence；finish_http 关闭活跃问题，end 立即关闭继续发送。
- fast 路径仍全 journal SHA、当前配额、checkpoint/双锚、当前 ledger 语义与目录集合；只延后旧同名 raw bytes 的重复内容检查。新事件必须逐份核 bytes。FULL 在每题前、轮末及新拥有者/实例前执行。
- fast capability 为同一内存实例、controller owner、plan、round、admittedRequest 和链头联合凭据，不能落盘继承，也不能由新实例只凭文件猜出。

最终必验清单：

1. 最终 source/plan/preflight/legacy/原拒绝包 identities；不得实际 verify 新 batch，使用自有副本。
2. 完整同版组合，所有 EVIDENCE 预先重定向独立全新绝对路径；59900 测试等根任务允许后独占。
3. 回滚原两 P1、零价次数、旧/新 UNKNOWN、PENDING 精确结算及 cancelUnsent、terminal 只封存。
4. A/B 两快照中新增费用或 UNKNOWN 后 B 回退，停止记录保存 A 的新负债；不把合并事务变成只看 B。
5. 首 A 的畸形/重复 JSON、读取失败、无有效快照：首次拒绝后修复 runtime，同实例与新实例都不能继续。before 不返回许可；after 必须保留既有共同未知承诺及原 bytes。
6. 日志/三锚/旁证写失败、原事件部分落盘：不得重试/补写既有原件或重新建链。磁盘完全不可写不能声称旁证保证落盘。
7. capability 的新实例、owner 失效、round/fence/head/plan 错配及跨进程清理。
8. 旧事件同名篡改延迟检测必须明示；fast 不得用被改原文件的值覆写已锚 ledger，高水位及新事件校验仍即时；FULL 必须在晋级/下一题前捕获。
9. late after 仍落原 fence，不能污染新 activeQuestion；begin 重放、题面不匹配、计数跳跃或 reference 未绑定均拒绝。
10. 按最终源码实测尾部双调用同步路径，区分 callback、begin、HTTP_RETURN/end、FULL；严格1000ms若失败就记录失败，不把近似1秒四舍五入为过关。原15秒、1620秒、P95及业务门槛不变，不以免费性能计时冒充实际P95。

本准备期间没有碰59900端口、实际revision-4目录、真实凭证、人类授权、付费调用、旧68逐题SQL或新未见；根任务封存通知之后才执行独立动态审查。
