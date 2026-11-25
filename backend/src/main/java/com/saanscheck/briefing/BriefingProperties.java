package com.saanscheck.briefing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Morning AQI briefing.
 *
 * @param catchUpWindow how long after the user's delivery time a missed briefing (backend down, restart) is still
 *                      created and delivered; after that the day is skipped rather than sending a "morning"
 *                      briefing in the afternoon
 * @param appUrl        dashboard link included in the message
 */
@ConfigurationProperties(prefix = "saanscheck.briefing")
public record BriefingProperties(boolean enabled, Duration catchUpWindow, String appUrl) {
    public BriefingProperties {
        catchUpWindow = catchUpWindow == null ? Duration.ofHours(3) : catchUpWindow;
        appUrl = appUrl == null || appUrl.isBlank() ? "http://localhost:5173" : appUrl.replaceAll("/+$", "");
    }
}
