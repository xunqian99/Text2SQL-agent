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
 * <p>所以这里的流程是：先按别名命中 → 再按依赖表可用性过滤。这比「只做字符串
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

    @PostConstruct
    void load() {
        List<Metric> loaded = readResource();
        List<Metric> validated = validate(loaded);
        this.metrics = List.copyOf(validated);
        log.info("指标注册表已加载：{} 条", this.metrics.size());
    }

    public List<Metric> all() {
        return metrics;
    }

    /**
     * 找出问题里提到的指标。
     *
     * <p>不限制命中数量：一个复合问题（「GMV 和客单价分别是多少」）可能同时
     * 命中多个指标，只取一个会丢信息。实际命中数很少超过 2–3 个。
     *
     * <p>命中判定用 {@code contains} 而不是最大匹配，理由见类注释。
     */
    public List<Metric> findMentioned(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        String text = question.toLowerCase(Locale.ROOT);
        List<Metric> hits = new ArrayList<>();
        for (Metric metric : metrics) {
            boolean aliasHit = metric.aliases().stream()
                    .filter(alias -> alias != null && !alias.isBlank())
                    .anyMatch(alias -> text.contains(alias.toLowerCase(Locale.ROOT)));
            if (!aliasHit) {
                continue;
            }
            boolean requiredHit = metric.requiredPhrases().isEmpty()
                    || metric.requiredPhrases().stream()
                    .anyMatch(phrase -> phrase != null && !phrase.isBlank()
                            && text.contains(phrase.toLowerCase(Locale.ROOT)));
            boolean excludedHit = metric.excludedPhrases().stream()
                    .anyMatch(phrase -> phrase != null && !phrase.isBlank()
                            && text.contains(phrase.toLowerCase(Locale.ROOT)));
            if (requiredHit && !excludedHit) {
                hits.add(metric);
            }
        }
        return List.copyOf(hits);
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

    private List<Metric> readResource() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("未找到指标注册表 {}，语义层不生效", RESOURCE);
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            MetricsFile file = yamlObjectMapper.readValue(in, MetricsFile.class);
            return file == null || file.metrics() == null ? List.of() : file.metrics();
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
    private record MetricsFile(List<Metric> metrics) {
    }
}
