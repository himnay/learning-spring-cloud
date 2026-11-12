package com.learning.discovery.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ClientCertificateSubjectFilter} (a WebFilter bean, picked up by WebFlux for every request). Off when
 * the server runs plain HTTP ({@code server.ssl.enabled=false}, the test profile): no TLS, no client certificate.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "server.ssl.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ClientCertificateProperties.class)
class ClientCertificateConfig {

    @Bean
    ClientCertificateSubjectFilter clientCertificateSubjectFilter(ClientCertificateProperties properties) {
        return new ClientCertificateSubjectFilter(properties);
    }
}
