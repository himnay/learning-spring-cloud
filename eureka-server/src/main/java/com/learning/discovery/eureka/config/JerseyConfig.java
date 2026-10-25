package com.learning.discovery.eureka.config;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps Eureka's own error responses (e.g. 404 on a heartbeat for an unknown instance) as bare statuses.
 * By default Jersey calls sendError(), Spring Boot's /error page then adds a JSON body, and the Eureka
 * client fails to parse that body as InstanceInfo ("Root name ('timestamp') does not match expected
 * ('instance')"), reports a 400 and never re-registers. setStatusOverSendError makes Jersey use setStatus().
 */
@Configuration(proxyBeanMethods = false)
class JerseyConfig {

    @Bean
    static BeanPostProcessor eurekaJerseyStatusOverSendError() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ResourceConfig config) {
                    config.property(ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, true);
                }
                return bean;
            }
        };
    }
}
