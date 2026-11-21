# DEV_F 免费超时审计

这份审计只读取已封存的 `state.json`、`timeout-diagnosis.json`、`input-size-diagnosis.json`、`proxy-answer-decisions.json` 和 `responses.json`。脚本不会启动 Java、读取密钥、打开网络连接或修改付费批次。

## 结果

- 100/100 题的分母、87/100 正确和 `DEVELOPMENT_FAILED_NO_C2` 终态保持一致。
- 10 个唯一超时题与代理证据逐题一致：Java `elapsedMs` 为 14,914–14,944ms，MODEL 阶段为 14,902–14,937ms；代理墙钟为 15,008.472–15,027.118ms。
- Java 自身没有报告 `DEADLINE_EXCEEDED`；10 个失败均由代理答案截止触发。P95 为 15,016.4887ms，超过开发门槛的 ≤9 个超时限制。
- 超时题的最终 504 决定标记为 `durableBeforeAnswer=true`、`durableSettlementSucceeded=false`。迟到用量通过独立账务链记录为 10 次、¥0.15659600，不能把 504 决定改写成已结算，也不能把迟到 SQL回填到答案。
- 运行记录中的超时题平均 Prompt 字符数为 7,562.6，正常题为 6,339.47；输入估算均值为 15,012.7 对 12,979.57。这是样本相关性，脚本明确不把它当成因果证明。
- 本次审计供应商调用 0、真实 Key 读取 0、付费批次改动 0；没有授权重放或放宽阈值。

## 数据流与身份边界

每题先由 `responses.json` 记录 Java 的最终 HTTP 结果，再由 `proxy-answer-decisions.json`记录代理选择的 HTTP 决定。超时题的 Java 结果是 `502/GENERATION_FAILED`，代理最终选择 `504`；两者通过题号和请求证据分别保留。迟到用量属于已发连接的账务观察，不进入 Java 的 SQL、评分、纠错或会话。

脚本把 10 个超时题的固定题号集合与代理决定、Java响应、预算快照逐项交叉核对；全量 100 题只用于检查分母和重复身份。账务的 `knownCostYuan`、`lateKnownCostYuan`、`cumulativeUnknownHeldYuan` 分开读取，未知用量没有按零处理。这里没有 Top-K 或排序权重，输入长度差异也不会改变题目身份或验收门槛。

## 结论和边界

审计没有发现确定性的 Java 计时错误，也没有为放宽 15 秒、修改超时门槛、重跑 DEV100 或启动 C2 提供依据。若要尝试输入压缩或关闭显式推理，只能另建本地候选、保持语义和评分不变，先做独立边界审查；这份报告不能证明任何新候选的业务准确率或性能收益。

运行方式：

```text
python scripts/audit_scope10_timeout_free.py \
  --batch reports/replan-stage-d-20261119/scope10-final-dev-20261121/revision-3 \
  --out reports/replan-stage-d-20261119/scope10-final-dev-20261121/revision-3/free-timeout-audit-20261121/summary.json
```
