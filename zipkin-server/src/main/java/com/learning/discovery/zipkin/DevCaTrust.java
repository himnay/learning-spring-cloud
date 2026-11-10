package com.learning.discovery.zipkin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Trusts the dev CA ({@code tls/truststore.p12}, written by {@code certs/generate-certs.sh}) for outbound HTTPS,
 * which in this process is only the Eureka registration at {@code https://localhost:8761}.
 * <p>
 * Why not an SSL bundle like the other modules: Zipkin disables Boot auto-configuration (no {@code SslBundles}) and
 * registers through Armeria's {@code EurekaUpdatingListener}, built by Zipkin from {@code zipkin.discovery.eureka.*}
 * with no TLS setting. Its Netty TLS client falls back to the JDK default trust managers, which read
 * {@code javax.net.ssl.trustStore}; that property needs a file, so the classpath store is copied to a temp file.
 * <p>
 * JVM-wide: the default trust store becomes the dev CA only (cacerts is no longer consulted). Fine here, nothing else
 * in this process calls HTTPS; a storage backend over HTTPS with a public CA would need the CA added to the store.
 * An explicit {@code -Djavax.net.ssl.trustStore} wins.
 */
final class DevCaTrust {

    private static final String RESOURCE = "/tls/truststore.p12";

    private DevCaTrust() {
    }

    static void install() {
        if (System.getProperty("javax.net.ssl.trustStore") != null) {
            return;
        }
        try (InputStream in = DevCaTrust.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return; // certs not generated: Eureka registration over https will fail the handshake
            }
            Path file = Files.createTempFile("zipkin-truststore", ".p12");
            file.toFile().deleteOnExit();
            Files.copy(in, file, REPLACE_EXISTING);
            System.setProperty("javax.net.ssl.trustStore", file.toString());
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            System.setProperty("javax.net.ssl.trustStorePassword",
                    System.getenv().getOrDefault("TLS_TRUSTSTORE_PASSWORD", "changeit"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot install " + RESOURCE + " as the default trust store", e);
        }
    }
}
