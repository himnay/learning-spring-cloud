package com.learning.discovery.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@EnableEurekaServer
@SpringBootApplication
class EurekaServerApplication {
    static void main(String[] args) {
        SpringApplication app = new SpringApplication(EurekaServerApplication.class);
        app.setApplicationStartup(new BufferingApplicationStartup(2048)); // backs /actuator/startup
        app.run(args);
    }
}
