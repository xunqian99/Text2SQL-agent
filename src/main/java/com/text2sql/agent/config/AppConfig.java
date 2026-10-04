package com.text2sql.agent.config;

import com.text2sql.agent.retrieval.FullSchemaProvider;
import com.text2sql.agent.retrieval.HybridSchemaProvider;
import com.text2sql.agent.retrieval.LexicalSchemaRetriever;
import com.text2sql.agent.retrieval.SchemaCatalog;
import com.text2sql.agent.retrieval.SchemaProvider;
import com.text2sql.agent.retrieval.glossary.GlossaryLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 应用级装配。
 *
 * <p>刻意保持极简：只注册配置类。LLM 客户端在 {@code LlmSqlGenerator} 里按需自建，
 * 评估集的 YAML 解析在 {@code EvalItemLoader} 内部自持，见下面第二条说明。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AppConfig {

    /** 判断 LLM 是否已配置。未配置时应用仍可启动，只有生成 SQL 这一步不可用。 */
    public static boolean llmConfigured(AgentProperties props) {
        return StringUtils.hasText(props.getLlm().getApiKey());
    }

    /**
     * 阶段 1 的全量 provider，**默认生效**。
     *
     * <p>{@code matchIfMissing = true} 是刻意的：不写任何检索配置时，
     * 系统行为必须与阶段 1 完全一致。这样「阶段 2 引入了检索」这件事
     * 不会悄悄改变已有评估结果——想变必须显式改配置。
     *
     * <p>被否掉的方案：把两个 provider 都标 {@code @Component}，
     * 用 {@code @Primary} 选一个。那样容器里永远有两个 SchemaProvider Bean，
     * 构造函数注入 {@code SchemaProvider} 时行为取决于注解优先级，
     * 排查「为什么这次跑的是全量」要翻三个类的注解。用条件装配，
     * 「谁生效」在启动日志里是确定的，且容器里只有一个实现。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agent.retrieval", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    public SchemaProvider fullSchemaProvider(SchemaCatalog catalog,
                                             com.text2sql.agent.semantic.MetricRegistry metricRegistry,
                                             AgentProperties properties) {
        return new FullSchemaProvider(catalog, metricRegistry, properties);
    }

    /**
     * 阶段 2 的检索版 provider，需要 {@code agent.retrieval.enabled=true} 才生效。
     *
     * <p>两个 {@code @ConditionalOnProperty} 的 havingValue 正好互补
     * （一个 false 一个 true），因此任何配置下都恰好有一个 Bean 被创建。
     * 这一点很重要：如果两者同时生效，Spring 会因为
     * {@code SchemaProvider} 有两个候选而启动失败；如果都不生效，
     * {@code Text2SqlOrchestrator} 会因为缺依赖而启动失败。
     * 互补条件让这两种坏情况在结构上不可能发生。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agent.retrieval", name = "enabled", havingValue = "true")
    public SchemaProvider hybridSchemaProvider(SchemaCatalog catalog, LexicalSchemaRetriever retriever,
                                               GlossaryLoader glossaryLoader, AgentProperties properties,
                                               com.text2sql.agent.semantic.MetricRegistry metricRegistry) {
        return new HybridSchemaProvider(catalog, retriever, glossaryLoader, properties, metricRegistry);
    }
}
