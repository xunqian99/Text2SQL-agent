package com.text2sql.agent.cache;

import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义缓存的单元测试。
 *
 * <p>重点测三件事，它们各自对应一类**不会报错、只会悄悄出错**的故障：
 *
 * <ol>
 *   <li>配置指纹——漏了它，改完 prompt 旧答案继续被命中，表现为「配置改了但数字没动」；</li>
 *   <li>LRU 上限——漏了它，长时间运行就是内存泄漏；</li>
 *   <li>归一化的边界——归一化过头会把「2017 年」和「2018 年」判成同一条，</li>
 *      那是最严重的一类错误：答非所问且看不出来。</li>
 * </ol>
 */
class SemanticCacheTest {

    private static SemanticCache cache(int maxEntries) {
        AgentProperties properties = new AgentProperties();
        properties.getCache().setEnabled(true);
        properties.getCache().setMaxEntries(maxEntries);
        return new SemanticCache(properties);
    }

    private static SemanticCache disabled() {
        return new SemanticCache(new AgentProperties());
    }

    private static final String SIG = "deepseek-flash|p2-output-discipline-v2|r=true:8|s=true|limit=APPEND";

    @Test
    @DisplayName("同一问题同一配置命中；不同问题不命中")
    void hitsOnSameQuestionAndConfig() {
        SemanticCache c = cache(10);
        c.put(c.key("2018 年有多少笔订单？", SIG), "SELECT count(*) FROM orders");

        assertThat(c.get(c.key("2018 年有多少笔订单？", SIG))).isPresent();
        assertThat(c.get(c.key("2017 年有多少笔订单？", SIG))).isEmpty();
    }

    @Test
    @DisplayName("归一化只吃掉空白与末尾标点，不吃掉年份这类关键词")
    void normalizationIsConservative() {
        SemanticCache c = cache(10);
        c.put(c.key("2018年有多少笔订单", SIG), "sql");

        // 加空格、加问号、换大小写，仍然是同一个问题
        assertThat(c.get(c.key("2018 年有多少笔订单？", SIG))).isPresent();
        assertThat(c.get(c.key("2018年有多少笔订单！！", SIG))).isPresent();
        // 但改一个数字就是另一个问题，绝不能命中
        assertThat(c.get(c.key("2019年有多少笔订单", SIG))).isEmpty();
    }

    @Test
    @DisplayName("配置变了就不命中——这是防「改了配置数字却没动」的唯一手段")
    void configSignatureInvalidatesCache() {
        SemanticCache c = cache(10);
        String question = "2018 年有多少笔订单？";
        c.put(c.key(question, SIG), "sql");

        String otherPrompt = "deepseek-flash|p3-xxx|r=true:8|s=true|limit=APPEND";
        String otherModel = "other-model|p2-output-discipline-v2|r=true:8|s=true|limit=APPEND";

        assertThat(c.get(c.key(question, otherPrompt))).isEmpty();
        assertThat(c.get(c.key(question, otherModel))).isEmpty();
    }

    @Test
    @DisplayName("超过上限按 LRU 淘汰，且容量不超过上限")
    void evictsLeastRecentlyUsed() {
        SemanticCache c = cache(2);
        c.put(c.key("问题A", SIG), "sqlA");
        c.put(c.key("问题B", SIG), "sqlB");
        // 访问 A，让它变成「最近使用」，这样被淘汰的应该是 B
        assertThat(c.get(c.key("问题A", SIG))).isPresent();
        c.put(c.key("问题C", SIG), "sqlC");

        assertThat(c.get(c.key("问题A", SIG))).as("A 最近用过，应保留").isPresent();
        assertThat(c.get(c.key("问题B", SIG))).as("B 最久没用，应被淘汰").isEmpty();
        assertThat(c.get(c.key("问题C", SIG))).isPresent();
        assertThat(c.stats().size()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("开关关闭时不缓存也不命中，命中率恒为 0")
    void doesNothingWhenDisabled() {
        SemanticCache c = disabled();
        c.put(c.key("问题", SIG), "sql");

        assertThat(c.get(c.key("问题", SIG))).isEmpty();
        assertThat(c.stats().hits()).isZero();
        assertThat(c.stats().size()).isZero();
    }

    @Test
    @DisplayName("空 SQL 不入缓存——缓存里出现空值等于把失败当成命中")
    void doesNotCacheBlankSql() {
        SemanticCache c = cache(10);
        c.put(c.key("问题", SIG), "   ");
        assertThat(c.get(c.key("问题", SIG))).isEmpty();
    }

    @Test
    @DisplayName("命中率按命中/总请求算，无请求时是 0 而不是 NaN")
    void countsHitRate() {
        SemanticCache c = cache(10);
        assertThat(c.stats().hitRate()).isZero();

        c.put(c.key("问题", SIG), "sql");
        c.get(c.key("问题", SIG));      // 命中
        c.get(c.key("别的问题", SIG));   // 未命中

        assertThat(c.stats().hits()).isEqualTo(1);
        assertThat(c.stats().misses()).isEqualTo(1);
        assertThat(c.stats().hitRate()).isEqualTo(0.5);
    }
}
