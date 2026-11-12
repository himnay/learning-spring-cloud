package com.learning.discovery.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientCertificateSubjectFilterTests {

    private final ClientCertificateSubjectFilter filter = new ClientCertificateSubjectFilter(
            new ClientCertificateProperties(List.of("CN=admin-server,OU=learning-spring-cloud"),
                    List.of("/internal/**")));

    private final AtomicBoolean passed = new AtomicBoolean();
    private final WebFilterChain chain = exchange -> {
        passed.set(true);
        return Mono.empty();
    };

    @Test
    void allowedSubjectPassesWhateverTheDnFormatting() {
        MockServerWebExchange exchange = exchange("/actuator/health", "cn=admin-server, ou=learning-spring-cloud");

        filter.filter(exchange, chain).block();

        assertThat(passed).isTrue();
        assertThat(exchange.<String>getAttribute(ClientCertificateSubjectFilter.SUBJECT_ATTRIBUTE))
                .isEqualTo("CN=admin-server,OU=learning-spring-cloud");
    }

    @Test
    void otherSubjectFromTheSameCaIsRejected() {
        MockServerWebExchange exchange = exchange("/actuator/health", "CN=dev-cli,OU=learning-spring-cloud");

        filter.filter(exchange, chain).block();

        assertRejected(exchange, "client certificate subject not allowed: CN=dev-cli,OU=learning-spring-cloud");
    }

    @Test
    void noCertificateOnAnOrdinaryPathFallsThroughToLogin() {
        filter.filter(exchange("/applications", null), chain).block();

        assertThat(passed).isTrue();
    }

    @Test
    void noCertificateOnARequiredPathIsRejected() {
        MockServerWebExchange exchange = exchange("/internal/batch", null);

        filter.filter(exchange, chain).block();

        assertRejected(exchange, "client certificate required");
    }

    @Test
    void runsBeforeSpringSecurity() {
        // WebFluxSecurityConfiguration.WEB_FILTER_CHAIN_FILTER_ORDER
        assertThat(filter.getOrder()).isLessThan(-100);
    }

    private void assertRejected(MockServerWebExchange exchange, String reason) {
        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(reason);
    }

    private static MockServerWebExchange exchange(String path, String subject) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (subject != null) {
            X509Certificate certificate = mock(X509Certificate.class);
            when(certificate.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
            SslInfo sslInfo = mock(SslInfo.class);
            when(sslInfo.getPeerCertificates()).thenReturn(new X509Certificate[]{certificate});
            request.sslInfo(sslInfo);
        }
        return MockServerWebExchange.from(request);
    }
}
