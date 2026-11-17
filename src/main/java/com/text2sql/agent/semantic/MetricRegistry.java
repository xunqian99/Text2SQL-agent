package com.text2sql.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 业务指标注册表：加载 {@code schema/metrics.yml}，并提供按问题检索指标的能力。
 *
 * <p>对应 ROADMAP 阶段 4 第 1、2 条（指标配置文件格式、注册高频指标）。
 *
 * <p>先把话说准：这里的「检索指标」是**关键词子串匹配**（{@code contains}），
 * 不是语义理解。别名命中后检查指标上下文、依赖表，再把人工预写的口径文本
 * 交给 prompt；不生成 SQL、不改写 SQL。
 *
 * <p><b>为什么检索方式与 glossary 不同</b>
 *
 * <p>glossary 用的是「最大匹配」——因为它的键是表名/列名的别名，长词优先能
 * 消掉「订单明细」误命中「订单」这类包含关系。
 *
 * <p>指标的别名不存在包含关系（「复购率」和「留存率」互不包含），但有个
 * 更重要的差异：**指标必须按依赖表过滤**。用户问「复购率」时，如果这次检索
 * 只召回了 {@code orders} 没召回 {@code customers}，那把复购率的定义注入进去
 * 反而有害——模型会照抄一个引用不存在表的表达式。
 *
 * <p>所以这里的流程是：别名命中 → 上下文适用/排除 → 依赖表可用性过滤。这比「只做字符串
 * 匹配」多了一层保护，也是「指标和 schema 必须一致」这条约束的落地。
 */
@Component
public class MetricRegistry {

    private static final Logger log = LoggerFactory.getLogger(MetricRegistry.class);
    private static final String RESOURCE = "schema/metrics.yml";

    /**
     * 专用 YAML 解析器，**刻意不注册成 Spring Bean**。
     *
     * <p>理由与 {@code EvalItemLoader} 相同：Spring Boot 的 Jackson 自动配置带
     * {@code @ConditionalOnMissingBean(ObjectMapper.class)}，一旦容器里存在
     * ObjectMapper，REST 接口的消息转换器就会改用这个 YAML 实例，
     * 于是 {@code /api/ask} 返回的会是 YAML 而不是 JSON。
     */
    private final ObjectMapper yamlObjectMapper = new ObjectMapper(new YAMLFactory());

    private volatile List<Metric> metrics = List.of();
    private volatile List<DimensionPolicy> dimensionPolicies = List.of();
    private volatile List<EntityPolicy> entityPolicies = List.of();

    @PostConstruct
    void load() {
        MetricsFile file = readResource();
        List<Metric> loaded = file == null || file.metrics() == null ? List.of() : file.metrics();
        List<Metric> validated = validate(loaded);
        this.metrics = List.copyOf(validated);
        var policies = file == null || file.dimensionPolicies() == null ? List.<DimensionPolicy>of() : file.dimensionPolicies();
        var names = new LinkedHashSet<String>();
        for (var policy : policies) {
            if (policy.name() == null || policy.name().isBlank() || !names.add(policy.name())
                    || policy.table() == null || policy.column() == null || policy.aliases().isEmpty()
                    || policy.defaultFilter() == null || policy.defaultFilter().isBlank()
                    || policy.notes() == null || policy.notes().isBlank()) {
                throw new IllegalArgumentException("Incomplete or duplicate dimension policy");
            }
        }
        this.dimensionPolicies = List.copyOf(policies);
        var entities = file == null || file.entityPolicies() == null ? List.<EntityPolicy>of() : file.entityPolicies();
        for (var policy : entities) {
            if (!names.add(policy.name())) throw new IllegalArgumentException("Duplicate business policy name");
        }
        this.entityPolicies = List.copyOf(entities);
        log.info("指标注册表已加载：{} 条，维度口径：{} 条，实体口径：{} 条",
                this.metrics.size(), this.dimensionPolicies.size(), this.entityPolicies.size());
    }

    public List<Metric> all() {
        return metrics;
    }

    public List<DimensionPolicy> dimensionPolicies() { return dimensionPolicies; }

    public List<EntityPolicy> entityPolicies() { return entityPolicies; }

    /** Policies only describe columns still present after pruning; they never add tables or columns. */
    public String appendPolicies(String question,
            List<com.text2sql.agent.retrieval.SchemaContext.Table> actualTables, String metricsText) {
        String dimensionText = appendDimensionPolicies(question, actualTables, metricsText);
        if (question == null || question.isBlank()) return dimensionText;
        String text = normalizeForMatch(question);
        StringBuilder result = new StringBuilder(dimensionText == null ? "" : dimensionText);
        for (var policy : entityPolicies) {
            boolean mentioned = policy.aliases().stream().anyMatch(a -> text.contains(normalizeForMatch(a)));
            boolean available = actualTables.stream().anyMatch(t -> t.name().equalsIgnoreCase(policy.table())
                    && t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(policy.rowKey()))
                    && t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(policy.identityColumn())));
            if (mentioned && available) {
                if (!result.isEmpty()) result.append("\n\n");
                result.append(policy.render());
            }
        }
        return result.toString();
    }

    /** Keep the exact table/column provenance after pruning; policies cannot introduce absent columns. */
    public String appendDimensionPolicies(String question,
            List<com.text2sql.agent.retrieval.SchemaContext.Table> actualTables, String metricsText) {
        if (question == null || question.isBlank()) return metricsText;
        String text = normalizeForMatch(question);
        StringBuilder result = new StringBuilder(metricsText == null ? "" : metricsText);
        for (var policy : dimensionPolicies) {
            boolean mentioned = policy.aliases().stream().filter(a -> a != null && !a.isBlank())
                    .anyMatch(a -> text.contains(normalizeForMatch(a)));
            boolean available = actualTables.stream().anyMatch(t -> t.name().equalsIgnoreCase(policy.table())
                    && t.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(policy.column())));
            if (mentioned && available) {
                if (!result.isEmpty()) result.append("\n\n");
                result.append(policy.render());
            }
        }
        return result.toString();
    }

    /** Identity of the exact startup resource bytes parsed below; changing the file requires restart. */
    public static String definitionSha256() {
        return SourceSnapshot.VALUE.sha256();
    }

    // One JVM-wide snapshot prevents cache/log identity from describing different bytes from the registry.
    // Keep bytes private: callers receive only the digest, never the mutable array.
    private static final class SourceSnapshot {
        private static final Source VALUE = read();

        private static Source read() {
            ClassPathResource resource = new ClassPathResource(RESOURCE);
            if (!resource.exists()) {
                log.warn("未找到指标口径注册表 {}，口径注入不生效", RESOURCE);
                return new Source(null, "missing");
            }
            try (InputStream in = resource.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                return new Source(bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (IOException e) {
                throw new UncheckedIOException("指标注册表读取失败：" + RESOURCE, e);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }

    private record Source(byte[] bytes, String sha256) { }

    /**
     * 找出问题里提到的指标。
     *
     * <p>不限制命中数量：一个复合问题（「GMV 和客单价分别是多少」）可能同时
     * 命中多个指标，只取一个会丢信息。实际命中数很少超过 2–3 个。
     *
     * <p>命中判定用 {@code contains} 而不是最大匹配，理由见类注释。
     */
    public List<Metric> findMentioned(String question) {
        return matchEvidence(question).stream().filter(e -> e.decision() == MatchDecision.MATCHED)
                .map(MetricMatch::metric).toList();
    }

    public enum MatchDecision { MATCHED, REQUIRED_CONTEXT_MISSING, EXCLUDED_CONTEXT }

    /** Configured terms that matched normalized input; original question is never rewritten. */
    public record MetricMatch(Metric metric, String alias, String requiredContextTerm,
                              String excludedContextTerm, String preferredContextTerm, MatchDecision decision) { }

    /** Alias candidates retain their identity and rejection reason; no scoring or Top-K competition. */
    public List<MetricMatch> matchEvidence(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        // 归一化去掉所有空白再匹配。
        //
        // 【实测踩到的坑】T6-011 问「运费占 GMV 的比例是多少」，而指标别名写的是
        // 「运费占GMV」。中文里「GMV」前后加不加空格完全看用户习惯，但 contains()
        // 是精确子串匹配，多一个空格就命中不了——结果是 freight_ratio 定义根本没注入，
        // 模型自己算了个没乘 100 的比值，被判错。
        //
        // 去空白只用于**匹配**，不改变交给模型的问题原文。中文没有词间必需空格，
        // 去掉空白不会把两个不同的词粘成一个，误命中风险可以接受。
        String text = normalizeForMatch(question);
        List<MetricMatch> hits = new ArrayList<>();
        for (Metric metric : metrics) {
            String alias = firstHit(metric.aliases(), text);
            if (alias != null) {
                String required = firstHit(metric.requiredContextAny(), text);
                String excluded = firstHit(metric.excludedContextAny(), text);
                String preferred = firstHit(metric.preferredContextAny(), text);
                // Explicit interval overrides only a default exclusion, never the required context.
                // Retain both pieces of evidence instead of erasing the excluded dimension term.
                MatchDecision decision = excluded != null && preferred == null ? MatchDecision.EXCLUDED_CONTEXT
                        : !metric.requiredContextAny().isEmpty() && required == null
                        ? MatchDecision.REQUIRED_CONTEXT_MISSING : MatchDecision.MATCHED;
                hits.add(new MetricMatch(metric, alias, required, excluded, preferred, decision));
            }
        }
        return List.copyOf(hits);
    }

    private static String firstHit(List<String> terms, String text) {
        return terms.stream().filter(t -> t != null && !t.isBlank())
                .filter(t -> text.contains(normalizeForMatch(t))).findFirst().orElse(null);
    }

    /** 匹配用的归一化：小写 + 去掉全部空白。只影响命中判定，不改变原始文本。 */
    private static String normalizeForMatch(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 从命中结果里挑出**依赖表都可用**的那些。
     *
     * <p><b>为什么必须过滤</b>：注入一个引用了未召回表的指标表达式，
     * 会让模型写出引用不存在表的 SQL，被校验层拦下——从「口径错」变成
     * 「跑不通」，反而更难诊断。宁可少给一条指标，也不要给一条用不了的。
     *
     * <p>表名比较不区分大小写。
     *
     * @param availableTables 本次上下文里实际包含的表
     */
    public List<Metric> findApplicable(String question, Set<String> availableTables) {
        Set<String> available = new LinkedHashSet<>();
        for (String table : availableTables) {
            available.add(table.toLowerCase(Locale.ROOT));
        }
        return findMentioned(question).stream()
                .filter(m -> available.containsAll(lower(m.tables())))
                .toList();
    }

    private static List<String> lower(List<String> tables) {
        return tables.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
    }

    private MetricsFile readResource() {
        byte[] bytes = SourceSnapshot.VALUE.bytes();
        if (bytes == null) return null;
        try {
            return yamlObjectMapper.readValue(bytes, MetricsFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("指标注册表解析失败：" + RESOURCE, e);
        }
    }

    /**
     * 校验并去重。
     *
     * <p>三类问题会被丢弃并告警：
     *
     * <ol>
     *   <li><b>缺 name / expression</b>——没有名字就无法引用，没有表达式就无法注入。</li>
     *   <li><b>name 重复</b>——同名指标会让人以为改了一处，实际另一处还在生效。</li>
     *   <li><b>aliases 为空</b>——永远匹配不上，等于没写。</li>
     * </ol>
     *
     * <p>为什么要在这里拦：这些错误在 YAML 里看着都「很正常」，只有跑起来
     * 才发现某条指标从来没生效过。启动时告警比事后排查便宜得多。
     */
    private List<Metric> validate(List<Metric> raw) {
        Map<String, Metric> byName = new LinkedHashMap<>();
        int invalid = 0;
        for (Metric metric : raw) {
            if (metric.name() == null || metric.name().isBlank()
                    || metric.expression() == null || metric.expression().isBlank()
                    || metric.aliases().isEmpty()) {
                log.warn("指标定义不完整，已丢弃：name={} aliases={} expression={}",
                        metric.name(), metric.aliases().size(),
                        metric.expression() == null ? "null" : "有");
                invalid++;
                continue;
            }
            if (byName.putIfAbsent(metric.name(), metric) != null) {
                log.warn("指标名重复，保留先出现的：{}", metric.name());
                invalid++;
            }
        }
        if (invalid > 0) {
            log.warn("指标注册表：丢弃 {} 条，保留 {} 条", invalid, byName.size());
        }
        return byName.values().stream()
                .sorted(Comparator.comparing(Metric::name))
                .toList();
    }

    /** YAML 根结构。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MetricsFile(List<Metric> metrics, List<DimensionPolicy> dimensionPolicies,
                               List<EntityPolicy> entityPolicies) {
    }
}
