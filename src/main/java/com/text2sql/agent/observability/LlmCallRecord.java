package com.text2sql.agent.observability;

/**
 * 一次 LLM 调用的成本记录。
 *
 * <p>系统核心原则之一是具备完整的可观测性与成本审计能力——必须能清晰回答「每次调用花了多少」。
 * 所以 token / 耗时 / 成本不是日志装饰，而是**一等公民的数据结构**，
 * 它会被塞进 API 响应，也会被评估报告聚合。
 *
 * <p>为什么把 prompt 字符数也记下来：阶段 2 的验收标准之一是「上下文 token 数下降」。
 * 但 token 数由服务端返回，只有调用成功才有。用 promptChars 可以在调用失败时
 * 也能对比上下文大小，避免「改了 prompt 但没跑通就没法对比」。
 *
 * <p>为什么还要把 cache hit / miss 分开记：DeepSeek 的缓存命中输入单价只有未命中的
 * 1/50（空闲时段 0.02 vs 1 元/百万 token）。只记 promptTokens 会把成本算成上限，
 * 无法回答「这次调用到底花了多少」。cacheHitTokens / cacheMissTokens 来自
 * 响应 usage 的 prompt_tokens_details.cached_tokens。
 */
public record LlmCallRecord(
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        int promptChars,
        long latencyMs,
        double costYuan,
        int cacheHitTokens,
        int cacheMissTokens) {

    /** 兼容旧签名：没有 cache 明细时按「全部缓存未命中」处理，成本与旧版一致。 */
    public LlmCallRecord(String model, int promptTokens, int completionTokens, int totalTokens,
                         int promptChars, long latencyMs, double costYuan) {
        this(model, promptTokens, completionTokens, totalTokens, promptChars, latencyMs, costYuan,
                0, promptTokens);
    }

    /** 旧工厂方法：不区分 cache，按全部缓存未命中计价。 */
    public static LlmCallRecord of(String model, Integer promptTokens, Integer completionTokens,
                                   int promptChars, long latencyMs,
                                   double inputPricePer1k, double outputPricePer1k) {
        int in = promptTokens == null ? 0 : promptTokens;
        return of(model, in, completionTokens, 0, in, promptChars, latencyMs,
                inputPricePer1k, outputPricePer1k, 0.0);
    }

    /**
     * 带 cache 明细的工厂方法。
     *
     * @param cacheHitTokens  缓存命中的输入 token（DeepSeek 的 prompt_cache_hit_tokens）
     * @param cacheMissTokens 缓存未命中的输入 token（DeepSeek 的 prompt_cache_miss_tokens）
     * @param cacheHitPricePer1k 缓存命中输入的单价（元 / 千 token）
     */
    public static LlmCallRecord of(String model, Integer promptTokens, Integer completionTokens,
                                   Integer cacheHitTokens, Integer cacheMissTokens,
                                   int promptChars, long latencyMs,
                                   double inputPricePer1k, double outputPricePer1k,
                                   double cacheHitPricePer1k) {
        int in = promptTokens == null ? 0 : promptTokens;
        int out = completionTokens == null ? 0 : completionTokens;
        int hit = cacheHitTokens == null ? 0 : Math.max(0, cacheHitTokens);
        int miss = cacheMissTokens == null ? Math.max(0, in - hit) : Math.max(0, cacheMissTokens);
        double cost = miss / 1000.0 * inputPricePer1k
                + hit / 1000.0 * cacheHitPricePer1k
                + out / 1000.0 * outputPricePer1k;
        return new LlmCallRecord(model, in, out, in + out, promptChars, latencyMs, cost, hit, miss);
    }

    /** 把首次调用和自纠错调用合并，报告总 token、耗时和成本。 */
    public static LlmCallRecord combine(LlmCallRecord first, LlmCallRecord second) {
        if (first == null) return second;
        if (second == null) return first;
        return new LlmCallRecord(
                second.model(),
                first.promptTokens() + second.promptTokens(),
                first.completionTokens() + second.completionTokens(),
                first.totalTokens() + second.totalTokens(),
                first.promptChars() + second.promptChars(),
                first.latencyMs() + second.latencyMs(),
                first.costYuan() + second.costYuan(),
                first.cacheHitTokens() + second.cacheHitTokens(),
                first.cacheMissTokens() + second.cacheMissTokens());
    }

    public String summary() {
        return ("model=%s tokens=%d(prompt=%d,completion=%d,cacheHit=%d,cacheMiss=%d) "
                + "latency=%dms cost=%.4f元 promptChars=%d")
                .formatted(model, totalTokens, promptTokens, completionTokens,
                        cacheHitTokens, cacheMissTokens, latencyMs, costYuan, promptChars);
    }
}
