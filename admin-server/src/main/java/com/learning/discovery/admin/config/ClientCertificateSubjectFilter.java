package com.learning.discovery.admin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import javax.security.auth.x500.X500Principal;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authorizes the caller by the subject of the client certificate it presented in the TLS handshake (mutual TLS).
 * WebFlux variant (SBA 4 runs on Netty): the certificate comes from {@link ServerHttpRequest#getSslInfo()} instead of
 * the servlet request attribute the other services read.
 * <p>
 * By the time a request gets here Reactor Netty has already verified the certificate: it chains to the CA in the
 * {@code app.tls.server-bundle} truststore and is within its validity period ({@code server.ssl.client-auth=want}),
 * otherwise the handshake fails. That only proves "issued by our CA", which every service's certificate is. This
 * filter decides which of those services may call this one:
 * <ul>
 * <li>certificate, subject in {@code app.tls.client-cert.allowed-subjects}: continue, the subject is exposed as the
 * exchange attribute {@link #SUBJECT_ATTRIBUTE}</li>
 * <li>certificate, any other subject: 403, even with valid Basic credentials</li>
 * <li>no certificate, path in {@code app.tls.client-cert.required-paths}: 403</li>
 * <li>no certificate, any other path: continue (browsers, curl carry none)</li>
 * </ul>
 * Ordered before Spring Security's WebFilterChainProxy (-100), which still applies Basic/form login afterwards.
 */
class ClientCertificateSubjectFilter implements WebFilter, Ordered {

    /** RFC 2253 subject of an accepted client certificate, for code further down the chain. */
    static final String SUBJECT_ATTRIBUTE = ClientCertificateSubjectFilter.class.getName() + ".SUBJECT";

    static final int ORDER = -110;

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
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        X509Certificate certificate = clientCertificate(request.getSslInfo());
        if (certificate == null) {
            return isRequired(request) ? reject(exchange, "client certificate required") : chain.filter(exchange);
        }
        X500Principal subject = certificate.getSubjectX500Principal();
        if (!allowedSubjects.contains(subject)) {
            return reject(exchange, "client certificate subject not allowed: " + subject.getName());
        }
        exchange.getAttributes().put(SUBJECT_ATTRIBUTE, subject.getName());
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static X509Certificate clientCertificate(SslInfo sslInfo) {
        X509Certificate[] chain = sslInfo == null ? null : sslInfo.getPeerCertificates();
        return chain != null && chain.length > 0 ? chain[0] : null;
    }

    private boolean isRequired(ServerHttpRequest request) {
        return requiredPaths.stream().anyMatch(pattern -> pattern.matches(request.getPath().pathWithinApplication()));
    }

    private static Mono<Void> reject(ServerWebExchange exchange, String reason) {
        ServerHttpRequest request = exchange.getRequest();
        log.warn("403 {} {} from {}: {}", request.getMethod(), request.getPath(), request.getRemoteAddress(), reason);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        DataBuffer body = response.bufferFactory().wrap(reason.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(body));
    }
}
