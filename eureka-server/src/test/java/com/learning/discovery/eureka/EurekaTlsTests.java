package com.learning.discovery.eureka;

import com.netflix.eureka.cluster.PeerEurekaNodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real TLS handshakes against the node: it serves HTTPS with app.tls.server-bundle, asks for a client certificate
 * (server.ssl.client-auth=want), and ClientCertificateSubjectFilter requires one on /eureka/peerreplication/**.
 * The client bundle (app.tls.client-bundle) carries this node's own certificate, CN=eureka-server, which is allowed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.register-with-eureka=false", "eureka.client.fetch-registry=false"})
class EurekaTlsTests {

    private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes());
    private static final String EMPTY_BATCH = "{\"replicationList\":[]}";

    @LocalServerPort
    private int port;

    @Autowired
    private SslBundles sslBundles;

    @Value("${app.tls.client-bundle}")
    private String clientBundle;

    @Autowired
    private PeerEurekaNodes peerEurekaNodes;

    @Test
    void registryApiIsServedOverHttpsToClientsPresentingAnAllowedCertificate() throws Exception {
        HttpResponse<String> response = send(withClientCertificate(), get("/eureka/apps"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("applications");
    }

    @Test
    void registryApiAcceptsCallersWithoutCertificate() throws Exception {
        // zipkin-server, curl and browsers send none; Basic auth still applies
        assertThat(send(trustOnly(), get("/eureka/apps")).statusCode()).isEqualTo(200);
    }

    @Test
    void peerReplicationRequiresAClientCertificate() throws Exception {
        HttpResponse<String> response = send(trustOnly(), postBatch());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEqualTo("client certificate required");
    }

    @Test
    void peerReplicationAcceptsAPeerCertificate() throws Exception {
        assertThat(send(withClientCertificate(), postBatch()).statusCode()).isEqualTo(200);
    }

    @Test
    void clientWithDefaultTrustStoreRejectsTheCertificate() {
        assertThatThrownBy(() -> send(HttpClient.newHttpClient(), get("/actuator/health")))
                .isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void peerReplicationUsesTheTlsAwarePeerNodes() {
        // Spring Cloud's RefreshablePeerEurekaNodes would replicate with the JVM default trust store
        assertThat(peerEurekaNodes.getClass().getSimpleName()).isEqualTo("TlsPeerEurekaNodes");
    }

    /** Trusts the dev CA and presents CN=eureka-server when asked. */
    private HttpClient withClientCertificate() {
        return HttpClient.newBuilder().sslContext(sslBundles.getBundle(clientBundle).createSslContext()).build();
    }

    /** Trusts the dev CA, has no certificate of its own. */
    private HttpClient trustOnly() throws Exception {
        SslBundle bundle = sslBundles.getBundle(clientBundle);
        SSLContext context = SSLContext.getInstance(bundle.getProtocol());
        context.init(null, bundle.getManagers().getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    private HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest postBatch() {
        return request("/eureka/peerreplication/batch/")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(EMPTY_BATCH))
                .build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
                .header("Authorization", BASIC)
                .header("Accept", "application/json");
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
