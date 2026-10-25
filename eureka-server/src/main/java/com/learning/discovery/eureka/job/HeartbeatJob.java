package com.learning.discovery.eureka.job;

import org.jspecify.annotations.NonNull;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Demo Quartz job so /actuator/quartz (SBA "Quartz" view) has something to show.
 */
public class HeartbeatJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatJob.class);

    @Override
    protected void executeInternal(@NonNull JobExecutionContext context) {
        log.info("Heartbeat from eureka-server (next fire: {})", context.getNextFireTime());
    }
}
