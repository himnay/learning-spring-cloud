package com.learning.discovery.eureka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * app.tls.client-cert in application.yml, read by {@link ClientCertificateSubjectFilter}.
 *
 * @param allowedSubjects subject DNs whose client certificate may call this service, e.g.
 *                        {@code CN=gateway-service,OU=learning-spring-cloud}. Compared as X.500 names (case, spacing
 *                        and attribute-value encoding don't matter; the order of the RDNs does).
 * @param requiredPaths   path patterns ({@code /eureka/peerreplication/**}) that need a client certificate; a request
 *                        without one gets 403. Other paths accept certificate-less callers.
 */
@ConfigurationProperties("app.tls.client-cert")
record ClientCertificateProperties(@DefaultValue List<String> allowedSubjects,
                                   @DefaultValue List<String> requiredPaths) {
}
