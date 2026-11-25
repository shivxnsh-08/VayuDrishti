package com.saanscheck.briefing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One shared job for all users: every minute, queue the briefings that are due (see MorningBriefingService). */
@Component
@ConditionalOnProperty(name = {"saanscheck.scheduling.enabled", "saanscheck.briefing.enabled"},
        havingValue = "true", matchIfMissing = true)
public class MorningBriefingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefingScheduler.class);

    private final MorningBriefingService briefings;

    public MorningBriefingScheduler(MorningBriefingService briefings) {
        this.briefings = briefings;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void run() {
        try {
            briefings.queueDue();
        } catch (RuntimeException e) {
            log.error("Morning briefing run failed (next attempt in 1 min): {}", e.toString());
        }
    }
}
