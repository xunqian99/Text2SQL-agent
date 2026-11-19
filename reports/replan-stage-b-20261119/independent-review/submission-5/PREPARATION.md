# B提交5独立审查准备

状态：只准备，候选尚未正式固定，正式运行0。提交4的479项断言、2失败、2项裸列公开观察及全部历史材料保持原样。

本目录复制提交4已经纠正审查假设的101项、338项真实发送、30项补充/4项构造、4项空文本、2项空choices已知usage反例。真实UNPARSEABLE继续用空文本；裸列2项只作为既有局限观察，另保留限定错列拒绝控制，不计入B通过数字。

新增四入口响应记账矩阵：generate、generateCorrection、callMessages、callWithTools，分别提供空choices已知usage、空choices未知usage、null response、null message已知usage。覆盖预算关闭/开启、费用启用、允许BYOK的5个有效配置，共80项。以真实LlmSqlGenerator和内存ChatModel.call计数，核对已知Token/缓存Token/费用、callLog/EvaluationTrace恰好一条、预算未知调用数及费用预留；未知信息不伪造为已知或免费。目录按model.call发生后切空，避免目录读取次数依赖。

正式固定后才替换候选身份占位符、编译到本目录classes、集中运行并保留首次全部失败。仍不修改源码/现有测试/参考/评分/阈值，不运行Maven覆盖target，不读未见，不调用真实API；只使用内存模型及公开只读DB连接。结果仅在本submission-5目录。
