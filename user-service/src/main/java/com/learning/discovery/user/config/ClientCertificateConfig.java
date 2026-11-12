package com.learning.discovery.user.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ClientCertificateSubjectFilter} for every request. Off when the server runs plain HTTP
 * ({@code server.ssl.enabled=false}, the test profile): no TLS, no client certificate to check.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "server.ssl.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ClientCertificateProperties.class)
class ClientCertificateConfig {

    /** Before springSecurityFilterChain (order -100): a disallowed certificate is refused before credentials are checked. */
    static final int FILTER_ORDER = -110;

    @Bean
    FilterRegistrationBean<ClientCertificateSubjectFilter> clientCertificateSubjectFilter(
            ClientCertificateProperties properties) {
        FilterRegistrationBean<ClientCertificateSubjectFilter> registration =
                new FilterRegistrationBean<>(new ClientCertificateSubjectFilter(properties));
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
