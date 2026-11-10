package com.learning.discovery.zipkin;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import zipkin2.server.internal.EnableZipkinServer;
import zipkin2.server.internal.ZipkinActuatorImporter;
import zipkin2.server.internal.ZipkinModuleImporter;
import zipkin2.server.internal.banner.ZipkinBanner;

// Same bootstrap as Zipkin's own zipkin.server.ZipkinServer. Zipkin wires everything through
// @EnableZipkinServer imports instead of Boot auto-configuration (hence enableautoconfiguration=false)
// and reads zipkin-server*.yml (spring.config.name). This module's zipkin-server.yml picks the profiles.
// The annotation lives in an internal package: Zipkin supports only its own server, not custom ones.
@EnableZipkinServer
@SpringBootApplication
public class ZipkinServerApplication {

    // public: Spring Boot 3.5's launcher looks up a public static main
    public static void main(String[] args) {
        run(args);
    }

    static ConfigurableApplicationContext run(String... args) {
        DevCaTrust.install(); // before any TLS client exists: Eureka is https:// (see DevCaTrust)
        return new SpringApplicationBuilder(ZipkinServerApplication.class)
                .banner(new ZipkinBanner())
                .initializers(new ZipkinModuleImporter(), new ZipkinActuatorImporter())
                .properties("spring.boot.enableautoconfiguration=false", "spring.config.name=zipkin-server")
                .run(args);
    }
}
