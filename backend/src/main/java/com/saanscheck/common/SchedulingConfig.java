package com.saanscheck.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is switchable so integration tests can drive jobs explicitly
 * (deterministic) instead of racing background threads.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "saanscheck.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
