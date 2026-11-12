package com.learning.discovery.product.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authorizes the caller by the subject of the client certificate it presented in the TLS handshake (mutual TLS).
 * <p>
 * By the time a request gets here Tomcat has already verified the certificate: it chains to the CA in the
 * {@code app.tls.server-bundle} truststore and is within its validity period ({@code server.ssl.client-auth=want}),
 * otherwise the handshake fails. That only proves "issued by our CA", which every service's certificate is. This
 * filter decides which of those services may call this one:
 * <ul>
 * <li>certificate, subject in {@code app.tls.client-cert.allowed-subjects}: continue, the subject is exposed as the
 * request attribute {@link #SUBJECT_ATTRIBUTE}</li>
 * <li>certificate, any other subject: 403, even with valid Basic credentials</li>
 * <li>no certificate, path in {@code app.tls.client-cert.required-paths}: 403</li>
 * <li>no certificate, any other path: continue (browsers, curl, zipkin-server carry none)</li>
 * </ul>
 * It complements Spring Security rather than replacing it: Basic/form login still applies after this filter.
 */
class ClientCertificateSubjectFilter extends OncePerRequestFilter {

    /** Set by the servlet container from the TLS session: the client's chain, leaf first (Servlet spec 3.9). */
    static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    /** RFC 2253 subject of an accepted client certificate, for code further down the chain. */
    static final String SUBJECT_ATTRIBUTE = ClientCertificateSubjectFilter.class.getName() + ".SUBJECT";

    private static final Logger log = LoggerFactory.getLogger(ClientCertificateSubjectFilter.class);

    private final Set<X500Principal> allowedSubjects;
    private final List<PathPattern> requiredPaths;

    ClientCertificateSubjectFilter(ClientCertificateProperties properties) {
        // X500Principal#equals compares the canonical form: "cn=a, ou=b" equals "CN=a,OU=b"
        this.allowedSubjects = properties.allowedSubjects().stream()
                .map(X500Principal::new)
                .collect(Collectors.toUnmodifiableSet());
        this.requiredPaths = properties.requiredPaths().stream()
                .map(PathPatternParser.defaultInstance::parse)
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        X509Certificate certificate = clientCertificate(request);
        if (certificate == null) {
            if (isRequired(request)) {
                reject(request, response, "client certificate required");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        X500Principal subject = certificate.getSubjectX500Principal();
        if (!allowedSubjects.contains(subject)) {
            reject(request, response, "client certificate subject not allowed: " + subject.getName());
            return;
        }
        request.setAttribute(SUBJECT_ATTRIBUTE, subject.getName());
        chain.doFilter(request, response);
    }

    private static X509Certificate clientCertificate(HttpServletRequest request) {
        return request.getAttribute(CERTIFICATE_ATTRIBUTE) instanceof X509Certificate[] chain && chain.length > 0
                ? chain[0]
                : null;
    }

    private boolean isRequired(HttpServletRequest request) {
        PathContainer path = PathContainer.parsePath(request.getRequestURI().substring(request.getContextPath().length()));
        return requiredPaths.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        log.warn("403 {} {} from {}: {}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), reason);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.getWriter().write(reason);
    }
}
