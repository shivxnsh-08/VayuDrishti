package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCategory;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Change-based AQI alert knobs ({@code saanscheck.alerts.events}).
 *
 * @param significantChange   absolute AQI points vs. what the user was last told that count as a significant change
 * @param reminderInterval    minimum time between stability reminders while the AQI stays at/above the threshold
 * @param criticalCategory    category from which an alert is "critical" (may bypass quiet hours)
 * @param criticalBypassesQuietHours global switch: critical alerts may bypass quiet hours, but only for users who
 *                            opted in ("Send Severe alerts even during quiet hours"); never otherwise
 */
@ConfigurationProperties(prefix = "saanscheck.alerts.events")
public record AlertEventProperties(Integer significantChange, Duration reminderInterval, AqiCategory criticalCategory,
                                   Boolean criticalBypassesQuietHours) {
    public AlertEventProperties {
        significantChange = significantChange == null ? 25 : significantChange;
        reminderInterval = reminderInterval == null ? Duration.ofHours(8) : reminderInterval;
        criticalCategory = criticalCategory == null ? AqiCategory.SEVERE : criticalCategory;
        criticalBypassesQuietHours = criticalBypassesQuietHours == null || criticalBypassesQuietHours;
    }

    public static final AlertEventProperties DEFAULTS = new AlertEventProperties(null, null, null, null);
}
