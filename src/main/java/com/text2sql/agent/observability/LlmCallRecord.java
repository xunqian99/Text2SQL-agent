package com.text2sql.agent.observability;

/**
 * 一次 LLM 调用的成本记录。
 *
 * <p>AGENTS.md 里用户必须能独立回答三个问题，其中一个是「每次调用花了多少」。
 * 所以 token / 耗时 / 成本不是日志装饰，而是**一等公民的数据结构**，
 * 它会被塞进 API 响应，也会被评估报告聚合。
 *
 * <p>为什么把 prompt 字符数也记下来：阶段 2 的验收标准之一是「上下文 token 数下降」。
 * 但 token 数由服务端返回，只有调用成功才有。用 promptChars 可以在调用失败时
 * 也能对比上下文大小，避免「改了 prompt 但没跑通就没法对比」。
 */
public record LlmCallRecord(
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        int promptChars,
        long latencyMs,
        double costYuan) {

    public static LlmCallRecord of(String model, Integer promptTokens, Integer completionTokens,
                                   int promptChars, long latencyMs,
                                   double inputPricePer1k, double outputPricePer1k) {
        int in = promptTokens == null ? 0 : promptTokens;
        int out = completionTokens == null ? 0 : completionTokens;
        double cost = in / 1000.0 * inputPricePer1k + out / 1000.0 * outputPricePer1k;
        return new LlmCallRecord(model, in, out, in + out, promptChars, latencyMs, cost);
    }

    public String summary() {
        return "model=%s tokens=%d(prompt=%d,completion=%d) latency=%dms cost=%.4f元 promptChars=%d"
                .formatted(model, totalTokens, promptTokens, completionTokens, latencyMs, costYuan, promptChars);
    }
}
