package com.text2sql.agent.cost;

import com.text2sql.agent.config.AgentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CostQuotaProperties.class)
public class CostQuotaConfiguration {
    @Bean(destroyMethod = "close") CostQuota costQuota(CostQuotaProperties properties, AgentProperties agent) {
        return new CostQuota(properties, agent);
    }
}
