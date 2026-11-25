package com.saanscheck.common;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A single injectable clock so time-dependent logic (staleness, cooldowns, quiet hours) is testable. */
@Configuration
public class TimeConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
