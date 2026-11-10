package com.learning.discovery.eureka;

import com.netflix.eureka.cluster.PeerEurekaNodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The node serves HTTPS with the "server" bundle, a client trusting the "trust" bundle (the dev CA) can call it,
 * and a client with only the JDK's default trust store can't.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.register-with-eureka=false", "eureka.client.fetch-registry=false"})
class EurekaTlsTests {

    @LocalServerPort
    private int port;

    @Autowired
    private SslBundles sslBundles;

    @Autowired
    private PeerEurekaNodes peerEurekaNodes;

    @Test
    void registryApiIsServedOverHttpsToClientsTrustingTheDevCa() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(sslBundles.getBundle("trust").createSslContext())
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/eureka/apps"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes()))
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("applications");
    }

    @Test
    void clientWithDefaultTrustStoreRejectsTheCertificate() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/actuator/health")).build();

        assertThatThrownBy(() -> HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void peerReplicationUsesTheTlsAwarePeerNodes() {
        // Spring Cloud's RefreshablePeerEurekaNodes would replicate with the JVM default trust store
        assertThat(peerEurekaNodes.getClass().getSimpleName()).isEqualTo("TlsPeerEurekaNodes");
    }
}
