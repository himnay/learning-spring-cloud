package com.learning.discovery.eureka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** Paths reachable without authentication, from app.security.public-paths in application.yml. */
@ConfigurationProperties("app.security")
record SecurityProperties(@DefaultValue List<String> publicPaths) {
}
