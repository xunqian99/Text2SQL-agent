package com.text2sql.agent.cache;

import com.text2sql.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存：相同意图的问题直接复用上次生成的 SQL，跳过一次模型调用。
 *
 * <p>对应 ROADMAP 阶段 6 第 3 条。
 *
 * <p><b>缓存的为什么是 SQL，不是查询结果</b>
 *
 * <p>这是这一层最重要的取舍。缓存结果集看起来收益更大（连数据库都不用查），
 * 但它会把**过期数据当成正确答案返回**——数据库在两次提问之间变了，
 * 用户拿到的还是旧数。而且这类错误没有信号能发现：SQL 没问题、结果格式也正常，
 * 只是数字是半小时前的。对业务问数系统来说这是最不能接受的一类错误。
 *
 * <p>缓存 SQL 则没有这个问题：SQL 只描述「怎么查」，数据每次现取。
 * 代价是执行仍要花几十毫秒，相对一次模型调用（1–3 秒 + token）可以忽略。
 *
 * <p><b>命中之后仍然走完整校验</b>
 *
 * <p>缓存里的 SQL 不绕过 {@code SqlValidator}——编排层拿到它照常校验、照常执行。
 * 这样即使缓存被污染，护栏依然拦得住。「缓存命中就免检」是这类系统常见的短路错误。
 *
 * <p><b>缓存键必须带配置指纹</b>
 *
 * <p>同一个问题在不同配置下应该得到不同的 SQL：换模型、换 prompt 版本、
 * 开关检索或口径注入，都会改变模型看到的东西。只拿问题文本做键的话，
 * 改完 prompt 旧答案会继续被命中——表现为「我改了配置，数字却一点没动」，
 * 而且只能靠抓日志才发现。所以键 = 归一化问题 + 配置指纹。
 *
 * <p><b>为什么不按向量相似度检索</b>
 *
 * <p>「近似问题」复用旧 SQL 的风险是答非所问：用户把「2018 年」改成「2017 年」，
 * 两句在向量上极近，但 SQL 必须重写。这一版只做归一化后的精确匹配，
 * 命中率低一些，但不会给出错误答案。真语义缓存要等有 embedding，
 * 属于 ROADMAP 1.3 节划出的范围之外。
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private final AgentProperties properties;

    /**
     * LRU 容器。
     *
     * <p>用 {@code accessOrder=true} 而不是普通 HashMap：问数场景有明显的热点
     * ——少数几个看板问题被反复问。按访问序淘汰能把冷门问题先挤出去。
     *
     * <p>容量上限是必须有而不是可选的：没有上限的缓存在长时间运行后就是内存泄漏，
     * 而且泄漏速度取决于用户提问速度。
     */
    private final Map<String, String> store;

    private long hits;
    private long misses;

    public SemanticCache(AgentProperties properties) {
        this.properties = properties;
        int max = Math.max(1, properties.getCache().getMaxEntries());
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > max;
            }
        };
    }

    public boolean enabled() {
        return properties.getCache().isEnabled();
    }

    /**
     * 组装缓存键：归一化问题 + 配置指纹。
     *
     * @param question        用户问题原文
     * @param configSignature 配置指纹，由编排层按当前配置算好传进来
     */
    public String key(String question, String configSignature) {
        return normalize(question) + "\u0000" + configSignature;
    }

    /**
     * 问题归一化：小写、去所有空白、去末尾标点。
     *
     * <p>只做**不改变语义**的处理。「2018 年有多少笔订单」和
     * 「2018年有多少笔订单？」应当命中同一条；而「2017 年」和「2018 年」
     * 绝不能命中同一条——这一版不做任何相似度判断，就是这个原因。
     */
    static String normalize(String question) {
        if (question == null) {
            return "";
        }
        String text = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return text.replaceAll("[。？?!！，,；;：:、]+$", "");
    }

    public Optional<String> get(String key) {
        if (!enabled()) {
            return Optional.empty();
        }
        synchronized (store) {
            String sql = store.get(key);
            if (sql == null) {
                misses++;
                return Optional.empty();
            }
            hits++;
            return Optional.of(sql);
        }
    }

    public void put(String key, String sql) {
        if (!enabled() || sql == null || sql.isBlank()) {
            return;
        }
        synchronized (store) {
            store.put(key, sql);
        }
    }

    public Stats stats() {
        synchronized (store) {
            return new Stats(hits, misses, store.size(), enabled());
        }
    }

    public void clear() {
        synchronized (store) {
            store.clear();
            hits = 0;
            misses = 0;
        }
        log.info("语义缓存已清空");
    }

    /**
     * 缓存统计。
     *
     * @param hits    命中次数（各省下一次模型调用）
     * @param misses  未命中次数（照常调用模型）
     * @param size    当前条目数，用来验证 LRU 上限是否生效
     * @param enabled 开关状态；关闭时命中率恒为 0，读数前先看这个
     */
    public record Stats(long hits, long misses, int size, boolean enabled) {

        public long total() {
            return hits + misses;
        }

        /** 命中率。无请求时返回 0 而不是 NaN，避免报告里出现 null。 */
        public double hitRate() {
            return total() == 0 ? 0 : (double) hits / total();
        }

        public String summary() {
            return "hits=%d misses=%d size=%d hitRate=%.1f%% enabled=%s"
                    .formatted(hits, misses, size, hitRate() * 100, enabled);
        }
    }
}
