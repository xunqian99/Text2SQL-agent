package com.text2sql.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
}
