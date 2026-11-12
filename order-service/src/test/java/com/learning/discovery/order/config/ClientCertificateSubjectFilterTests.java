package com.learning.discovery.order.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientCertificateSubjectFilterTests {

    private final ClientCertificateSubjectFilter filter = new ClientCertificateSubjectFilter(
            new ClientCertificateProperties(List.of("CN=gateway-service,OU=learning-spring-cloud"),
                    List.of("/internal/**")));

    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void allowedSubjectPassesWhateverTheDnFormatting() throws Exception {
        MockHttpServletRequest request = request("/api/things", "cn=gateway-service, ou=learning-spring-cloud");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(ClientCertificateSubjectFilter.SUBJECT_ATTRIBUTE))
                .isEqualTo("CN=gateway-service,OU=learning-spring-cloud");
    }

    @Test
    void otherSubjectFromTheSameCaIsRejected() throws Exception {
        filter.doFilter(request("/api/things", "CN=dev-cli,OU=learning-spring-cloud"), response, chain);

        assertRejected("client certificate subject not allowed: CN=dev-cli,OU=learning-spring-cloud");
    }

    @Test
    void sameCommonNameUnderAnotherOrganizationalUnitIsRejected() throws Exception {
        filter.doFilter(request("/api/things", "CN=gateway-service,OU=elsewhere"), response, chain);

        assertRejected("client certificate subject not allowed: CN=gateway-service,OU=elsewhere");
    }

    @Test
    void noCertificateOnAnOrdinaryPathFallsThroughToLogin() throws Exception {
        filter.doFilter(request("/api/things", null), response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void noCertificateOnARequiredPathIsRejected() throws Exception {
        filter.doFilter(request("/internal/batch", null), response, chain);

        assertRejected("client certificate required");
    }

    private void assertRejected(String reason) throws IOException {
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo(reason);
    }

    private static MockHttpServletRequest request(String path, String subject) throws ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (subject != null) {
            X509Certificate certificate = mock(X509Certificate.class);
            when(certificate.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
            request.setAttribute(ClientCertificateSubjectFilter.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        }
        return request;
    }
}
