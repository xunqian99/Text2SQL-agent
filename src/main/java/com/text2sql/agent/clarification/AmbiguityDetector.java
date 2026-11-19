package com.text2sql.agent.clarification;

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
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 歧义检测：问题里的词在不同口径下都说得通时，先问清楚再生成 SQL。
 *
 * <p>对应 ROADMAP 阶段 5 第 5 条（歧义检测与主动反问）。
 *
 * <p><b>为什么把「猜」换成「问」</b>
 *
 * <p>口径猜错的 SQL 有两个特点：语法完全正确、结果看起来合理。它不会像表名写错
 * 那样在数据库层报错，因此**没有任何自动信号能发现它**——阶段 5 的自纠错实验已经
 * 证明了这一点：首答 SQL 有效率 100%，但整体只有 70% 左右，差额全是静默错误。
 * 既然系统自己发现不了，那唯一可靠的补救就是不要让它在不确定时下结论。
 *
 * <p><b>为什么规则写在 YAML 而不是提示词里</b>
 *
 * <p>提示词里写「遇到歧义要反问」，模型会不会照做、什么时候算歧义，都不可控，
 * 而且评估时无法统计。写成显式规则后：命中与否是确定性的，可以被单元测试固定，
 * 也可以被业务方审阅和增删——和 {@code metrics.yml}、{@code glossary.yml} 同一套思路。
 *
 * <p><b>默认关闭，由运行方案显式启用</b>：demo与完整链路评测启用同一规则，
 * 不因题目来自评测集就绕过澄清。裸销售额等未定口径仍反问，按原评分计失败。
 */
@Component
public class AmbiguityDetector {

    private static final Logger log = LoggerFactory.getLogger(AmbiguityDetector.class);
    private static final String RESOURCE = "schema/ambiguities.yml";

    /**
     * 专用 YAML 解析器，**刻意不注册成 Spring Bean**。
     *
     * <p>理由同 {@code EvalItemLoader} / {@code MetricRegistry}：Spring Boot 的 Jackson
     * 自动配置带 {@code @ConditionalOnMissingBean(ObjectMapper.class)}，一旦容器里存在
     * ObjectMapper，REST 的消息转换器就会改用这个 YAML 实例，{@code /api/ask} 会返回 YAML。
     */
    private final ObjectMapper yamlObjectMapper = new ObjectMapper(new YAMLFactory());

    private volatile List<Ambiguity> ambiguities = List.of();
    private volatile List<CompiledRule> rules = List.of();

    @PostConstruct
    void load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("未找到歧义词表 {}，主动反问不生效", RESOURCE);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            AmbiguityFile file = yamlObjectMapper.readValue(in, AmbiguityFile.class);
            List<Ambiguity> loaded = file == null || file.ambiguities() == null
                    ? List.of() : List.copyOf(file.ambiguities());
            var compiled = new ArrayList<CompiledRule>();
            for (var ambiguity : loaded) {
                var groups = new LinkedHashMap<String, List<Pattern>>();
                for (var group : ambiguity.requiredEvidence()) {
                    if (group.name() == null || group.name().isBlank() || group.patterns().isEmpty()
                            || group.patterns().stream().anyMatch(p -> p == null || p.isBlank()) || groups.containsKey(group.name()))
                        throw new IllegalArgumentException("Incomplete or duplicate clarification evidence group");
                    groups.put(group.name(), group.patterns().stream().map(Pattern::compile).toList());
                }
                compiled.add(new CompiledRule(ambiguity, Map.copyOf(groups),
                        ambiguity.unresolvedPatterns().stream().map(Pattern::compile).toList(),
                        ambiguity.triggerPatterns().stream().map(Pattern::compile).toList()));
            }
            this.ambiguities = loaded;
            this.rules = List.copyOf(compiled);
            log.info("歧义词表已加载：{} 条", this.ambiguities.size());
        } catch (IOException e) {
            throw new UncheckedIOException("歧义词表解析失败：" + RESOURCE, e);
        }
    }

    public List<Ambiguity> all() {
        return ambiguities;
    }

    /**
     * 找出需要反问的问题。
     *
     * <p>旧规则按term与hints判定；显式证据规则支持别名、必需证据组和不确定/否定拦截。
     * 组间AND、组内OR；否定或疑问证据优先。所有来源可通过decisionsFor检查。
     *
     * @return 需要向用户确认的问题；没有歧义时返回空
     */
    public java.util.Optional<String> clarificationFor(String question) {
        return decisionsFor(question).stream().filter(Decision::needsClarification).map(Decision::question).findFirst();
    }

    /** Explicit evidence stays inspectable; merely mentioning a field is not proof of its value. */
    public List<Decision> decisionsFor(String question) {
        if (question == null || question.isBlank()) return List.of();
        String text = normalize(question);
        var decisions = new ArrayList<Decision>();
        for (var rule : rules) {
            var ambiguity = rule.definition();
            var terms = new ArrayList<String>(); terms.add(ambiguity.term()); terms.addAll(ambiguity.aliases());
            String matchedTerm = terms.stream().filter(t -> t != null && !t.isBlank())
                    .filter(t -> text.contains(normalize(t))).findFirst().orElse(null);
            if (matchedTerm == null) continue;
            var evidence = new ArrayList<Evidence>(); var missing = new ArrayList<String>();
            // A trigger is an applicability gate, not evidence that the business choice is resolved.
            // Old rules with no gate retain their exact behavior.
            if (!rule.triggers().isEmpty()) {
                for (var pattern : rule.triggers()) {
                    var match = pattern.matcher(text);
                    if (match.find()) evidence.add(new Evidence("trigger-pattern", "applicability", match.group()));
                }
                if (evidence.isEmpty()) continue;
            }
            if (rule.groups().isEmpty()) {
                int found = evidence.size();
                for (var hint : ambiguity.hints()) if (hint != null && !hint.isBlank() && text.contains(normalize(hint)))
                    evidence.add(new Evidence("legacy-hint", "hint", normalize(hint)));
                if (found == evidence.size()) missing.add("legacy-hint");
            } else {
                // AND across required groups, OR across patterns within each group. No score threshold.
                for (var group : ambiguity.requiredEvidence()) {
                    int found = evidence.size();
                    for (var pattern : rule.groups().get(group.name())) {
                        var match = pattern.matcher(text);
                        if (match.find()) evidence.add(new Evidence("required-pattern", group.name(), match.group()));
                    }
                    if (found == evidence.size()) missing.add(group.name());
                }
            }
            var blockers = new ArrayList<String>();
            for (var pattern : rule.unresolved()) { var match = pattern.matcher(text); if (match.find()) blockers.add(match.group()); }
            String prompt = ambiguity.question() == null || ambiguity.question().isBlank()
                    ? "问题中的「" + ambiguity.term() + "」存在多种口径，请确认具体指哪一种。" : ambiguity.question();
            decisions.add(new Decision(ambiguity.term(), matchedTerm, List.copyOf(evidence), List.copyOf(missing),
                    List.copyOf(blockers), !missing.isEmpty() || !blockers.isEmpty(), prompt));
        }
        return List.copyOf(decisions);
    }

    /** NFKC兼容全角数字，小写并去空白；只用于判定，不改写交给模型的问题原文。 */
    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 一条歧义规则。
     *
     * @param term     有歧义的词，如「销售额」
     * @param hints    出现这些限定词就说明用户已经说清了口径，不再反问
     * @param question 反问时对用户说的话，要给出选项而不是只说「请说清楚」
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ambiguity(String term, List<String> hints, String question, List<String> aliases,
                            List<EvidenceGroup> requiredEvidence, List<String> unresolvedPatterns,
                            List<String> triggerPatterns) {
        public Ambiguity(String term, List<String> hints, String question) {
            this(term, hints, question, List.of(), List.of(), List.of(), List.of());
        }
        public Ambiguity {
            hints = hints == null ? List.of() : List.copyOf(hints);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
            unresolvedPatterns = unresolvedPatterns == null ? List.of() : List.copyOf(unresolvedPatterns);
            triggerPatterns = triggerPatterns == null ? List.of() : List.copyOf(triggerPatterns);
        }
    }

    public record EvidenceGroup(String name, List<String> patterns) {
        public EvidenceGroup { patterns = patterns == null ? List.of() : List.copyOf(patterns); }
    }
    public record Evidence(String source, String group, String matchedText) { }
    public record Decision(String term, String matchedTerm, List<Evidence> evidence, List<String> missingGroups,
                           List<String> unresolvedEvidence, boolean needsClarification, String question) { }
    private record CompiledRule(Ambiguity definition, Map<String, List<Pattern>> groups,
                                List<Pattern> unresolved, List<Pattern> triggers) { }

    /** YAML 根结构。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AmbiguityFile(List<Ambiguity> ambiguities) {
    }
}
