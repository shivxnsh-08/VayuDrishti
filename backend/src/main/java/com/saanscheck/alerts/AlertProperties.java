package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.profile.HealthProfile;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Alert-engine knobs. Default thresholds per health profile are configuration (VERIFY with health guidance). */
@ConfigurationProperties(prefix = "saanscheck.alerts")
public record AlertProperties(
        boolean enabled,
        String zone,
        Duration evaluationInterval,
        Duration dispatchInterval,
        int horizonHours,
        int windowHours,
        Duration cooldown,
        Duration staleAfter,
        int maxAttempts,
        Duration baseBackoff,
        Duration sendingTimeout,
        int trendDelta,
        Map<HealthProfile, AqiCategory> defaultMinCategory) {

    public AqiCategory minCategoryFor(HealthProfile profile, AqiCategory override) {
        if (override != null) {
            return override;
        }
        AqiCategory d = defaultMinCategory == null ? null : defaultMinCategory.get(profile);
        return d != null ? d : AqiCategory.POOR;
    }
}
