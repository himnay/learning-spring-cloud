package com.learning.discovery.eureka;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which node application.yml resolves to for each profile combination: config only, no server started.
 */
class PeerProfilesTests {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "           ; 8761; localhost",   // no profile: peer1
            "peer1      ; 8761; localhost",
            "peer2      ; 8762; 127.0.0.1",
            "peer3      ; 8763; 127.0.0.2",
            "prod       ; 8761; localhost",   // prod alone is still peer1
            "peer3,prod ; 8763; 127.0.0.2"})
    void profileSelectsNode(String profiles, int port, String hostname) {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null,
                StringUtils.commaDelimitedListToSet(profiles));

        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(port);
        assertThat(environment.getProperty("eureka.instance.hostname")).isEqualTo(hostname);
        assertThat(environment.getProperty("logging.file.name")).isEqualTo("logs/eureka-server-" + port + ".log");
    }
}
