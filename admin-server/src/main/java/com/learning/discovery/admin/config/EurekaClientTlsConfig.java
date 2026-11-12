package com.learning.discovery.admin.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.netflix.eureka.http.EurekaClientHttpRequestFactorySupplier;
import org.springframework.cloud.netflix.eureka.http.RestClientDiscoveryClientOptionalArgs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Eureka client over mutual TLS, with the SSL bundle named by {@code app.tls.client-bundle}: trusts its CA when it
 * registers, renews and fetches the registry at the https:// URLs in eureka.client.service-url.defaultZone, and presents
 * this service's certificate when eureka-server asks for one (its ClientCertificateSubjectFilter checks the subject).
 * <p>
 * The Eureka client doesn't read {@code spring.ssl.bundle.*} (only {@code eureka.client.tls.*} key/trust-store
 * files), and its RestClient transport replaces the request factory of the RestClient.Builder it is given, so
 * {@code spring.http.clients.ssl.bundle} doesn't reach it either. The SSLContext on the optional-args bean is the
 * hook its own TLS support uses: DiscoveryClient hands it to RestClientTransportClientFactories, which builds an
 * Apache HttpClient 5 with it. The auto-configured bean backs off ({@code @ConditionalOnMissingBean}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "eureka.client.enabled", matchIfMissing = true)
class EurekaClientTlsConfig {

    @Bean
    RestClientDiscoveryClientOptionalArgs restClientDiscoveryClientOptionalArgs(SslBundles sslBundles,
            @Value("${app.tls.client-bundle}") String clientBundle,
            EurekaClientHttpRequestFactorySupplier requestFactorySupplier,
            ObjectProvider<RestClient.Builder> restClientBuilder) {
        RestClientDiscoveryClientOptionalArgs args = new RestClientDiscoveryClientOptionalArgs(
                requestFactorySupplier, () -> restClientBuilder.getIfAvailable(RestClient::builder));
        args.setSSLContext(sslBundles.getBundle(clientBundle).createSslContext());
        return args;
    }
}
