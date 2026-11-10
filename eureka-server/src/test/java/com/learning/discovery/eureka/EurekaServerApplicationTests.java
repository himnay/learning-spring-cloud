package com.learning.discovery.eureka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

// standalone in tests: don't register with or fetch from the other nodes, which aren't running;
// plain HTTP so RestTestClient needs no trust store (TLS itself is covered by EurekaTlsTests)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.register-with-eureka=false", "eureka.client.fetch-registry=false",
                "server.ssl.enabled=false"})
class EurekaServerApplicationTests {

    @LocalServerPort
    private int port;

    private RestTestClient restTestClient;

    @BeforeEach
    void setUp() {
        restTestClient = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void contextLoads() {
        // wiring smoke test: EnableEurekaServer must bootstrap without errors
    }

    @Test
    void dashboardIsServedAtRootWithCredentials() {
        restTestClient.get().uri("/").headers(h -> h.setBasicAuth("admin", "admin")).exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(String.class).value(body -> assertThat(body).contains("Eureka"));
    }

    @Test
    void healthEndpointIsPublicAndReportsUp() {
        restTestClient.get().uri("/actuator/health").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void registryApiRejectsAnonymousCallers() {
        restTestClient.get().uri("/eureka/apps").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void registryApiAcceptsBasicAuth() {
        restTestClient.get().uri("/eureka/apps").accept(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBasicAuth("admin", "admin")).exchange()
                .expectStatus().isOk();
    }

    @Test
    void heartbeatForUnknownInstanceIsBare404() {
        // a JSON error body here makes Eureka clients fail to parse it and never re-register
        restTestClient.put().uri("/eureka/apps/DEMO-APP/no-such-instance?status=UP&lastDirtyTimestamp=1")
                .headers(h -> h.setBasicAuth("admin", "admin")).accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isNotFound()
                .expectBody().isEmpty();
    }

    @Test
    void sbaEndpointsAreExposedBehindAuth() {
        restTestClient.get().uri("/actuator/quartz").exchange().expectStatus().isUnauthorized();
        // "startup" is absent here: it needs BufferingApplicationStartup, which only main() installs
        for (String endpoint : new String[]{"quartz", "httpexchanges", "auditevents", "jolokia", "sbom"}) {
            restTestClient.get().uri("/actuator/" + endpoint).headers(h -> h.setBasicAuth("admin", "admin")).exchange()
                    .expectStatus().isOk();
        }
    }
}
