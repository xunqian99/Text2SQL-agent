package com.text2sql.agent.access;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccessProperties.class)
public class AccessConfiguration {
    @Bean ApiAdmissionGate apiAdmissionGate(AccessProperties properties) { return new ApiAdmissionGate(properties); }

    @Bean FilterRegistrationBean<SingleUserAccessFilter> singleUserAccessFilter(AccessProperties properties, ApiAdmissionGate gate) {
        var registration = new FilterRegistrationBean<>(new SingleUserAccessFilter(properties, gate));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setAsyncSupported(false); // Current controllers are synchronous; the permit covers their whole execution.
        return registration;
    }
}
