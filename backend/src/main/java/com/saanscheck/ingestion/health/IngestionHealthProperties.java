package com.saanscheck.ingestion.health;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Thresholds for the ingestion-health status (saanscheck.ingestion.health.*). */
@ConfigurationProperties(prefix = "saanscheck.ingestion.health")
public record IngestionHealthProperties(
        Duration delayedAfter,
        Duration overdueAfter,
        int failureThreshold,
        int sourceFailureThreshold,
        double minFreshShare,
        Duration notifyAfter,
        Duration startupCatchUpAfter) {

    public IngestionHealthProperties {
        delayedAfter = delayedAfter == null ? Duration.ofMinutes(135) : delayedAfter;
        overdueAfter = overdueAfter == null ? Duration.ofMinutes(35) : overdueAfter;
        failureThreshold = failureThreshold <= 0 ? 3 : failureThreshold;
        sourceFailureThreshold = sourceFailureThreshold <= 0 ? 2 : sourceFailureThreshold;
        minFreshShare = minFreshShare <= 0 ? 0.5 : minFreshShare;
        notifyAfter = notifyAfter == null ? Duration.ofMinutes(10) : notifyAfter;
        startupCatchUpAfter = startupCatchUpAfter == null ? Duration.ofMinutes(10) : startupCatchUpAfter;
    }
}
