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
 * <p><b>默认关闭</b>：评估集里的题目都写明了口径，反问会让本该作答的题变成不作答，
 * 直接拉低准确率。它是给真实用户场景准备的能力，不是给离线评估准备的。
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

    @PostConstruct
    void load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("未找到歧义词表 {}，主动反问不生效", RESOURCE);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            AmbiguityFile file = yamlObjectMapper.readValue(in, AmbiguityFile.class);
            this.ambiguities = file == null || file.ambiguities() == null
                    ? List.of() : List.copyOf(file.ambiguities());
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
     * <p>命中条件：问题包含 {@code term}，且不包含该条目的任何 {@code hints}。
     * 也就是说，用户只要补上限定词，就不再触发放问——这是「问一次就够」的落地。
     *
     * @return 需要向用户确认的问题；没有歧义时返回空
     */
    public java.util.Optional<String> clarificationFor(String question) {
        if (question == null || question.isBlank()) {
            return java.util.Optional.empty();
        }
        String text = normalize(question);
        for (Ambiguity ambiguity : ambiguities) {
            if (ambiguity.term() == null || ambiguity.term().isBlank()) {
                continue;
            }
            if (!text.contains(normalize(ambiguity.term()))) {
                continue;
            }
            boolean disambiguated = ambiguity.hints().stream()
                    .filter(h -> h != null && !h.isBlank())
                    .anyMatch(h -> text.contains(normalize(h)));
            if (!disambiguated) {
                String prompt = ambiguity.question() == null || ambiguity.question().isBlank()
                        ? "问题中的「" + ambiguity.term() + "」存在多种口径，请确认具体指哪一种。"
                        : ambiguity.question();
                return java.util.Optional.of(prompt);
            }
        }
        return java.util.Optional.empty();
    }

    /** 与指标匹配同一套归一化：小写 + 去空白。用户在词中间加空格不该影响判定。 */
    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 一条歧义规则。
     *
     * @param term     有歧义的词，如「销售额」
     * @param hints    出现这些限定词就说明用户已经说清了口径，不再反问
     * @param question 反问时对用户说的话，要给出选项而不是只说「请说清楚」
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ambiguity(String term, List<String> hints, String question) {
        public Ambiguity {
            hints = hints == null ? List.of() : List.copyOf(hints);
        }
    }

    /** YAML 根结构。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AmbiguityFile(List<Ambiguity> ambiguities) {
    }
}
